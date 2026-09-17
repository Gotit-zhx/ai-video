// Prepare every product/shop pair before a batch reserves paid image calls.
// Category matching and shop preferences intentionally share the uploader's resolver.
(() => {
  async function read(path, capture = false) {
    const controller = new AbortController();
    let deadline;
    const timeout = new Promise((_, reject) => { deadline = setTimeout(() => {
      controller.abort();reject(new Error('读取上传配置超过 180 秒，请检查店铺连接后重试'));
    }, 180000); });
    try { return await Promise.race([capture ? productCaptureApi(path, {signal: controller.signal})
      : api(path, {signal: controller.signal}), timeout]); }
    finally { clearTimeout(deadline); }
  }
  async function prepare(targets, {shops, isCurrent = () => true, onProgress = () => {}} = {}) {
    const current = () => { if (!isCurrent()) throw new Error('当前公司或批次已切换，已取消准备'); };
    current();
    if (!Array.isArray(targets) || !targets.length) throw new Error('没有需要准备上传的商品');
    const organizationId = typeof currentUser !== 'undefined' ? currentUser?.organizationId : undefined;
    const available = shops || (await read('/api/v1/shops')).data;
    current();
    if (!Array.isArray(available)) throw new Error('店铺列表读取不完整，请重试');
    const captures = new Map(), options = new Map(), results = [], errors = [];
    const once = (cache, key, read) => {
      if (!cache.has(key)) cache.set(key, Promise.resolve().then(read));
      return cache.get(key);
    };
    let next = 0, completed = 0;
    async function worker() {
      while (next < targets.length) {
        current();
        const index = next++, target = targets[index];
        const shop = available.find(row => String(row.id) === String(target.shopId));
        let title = target.captureId;
        try {
          const platform = normalizedMarketplacePlatform(shop?.platform);
          if (!shop || shop.status !== 'ACTIVE' || !['SHOPEE', 'TIKTOK_SHOP'].includes(platform)) {
            throw new Error('目标店铺授权已失效或不支持上传，请重新选择店铺');
          }
          const [capture, shopOptions] = await Promise.all([
            once(captures, target.captureId, async () => {
              const response = await read(`/${encodeURIComponent(target.captureId)}`, true);
              const value = productCapturePayload(response.data);
              if (value?.id !== target.captureId) throw new Error('商品详情读取不完整，请重试');
              return value;
            }),
            once(options, target.shopId, async () => {
              const response = await read(`/api/v1/shops/${encodeURIComponent(target.shopId)}/publish-options`);
              if (!response?.data) throw new Error('店铺类目与配送配置读取不完整');
              return response.data;
            })
          ]);
          current();
          title = capture.title || target.captureId;
          if (platform === 'TIKTOK_SHOP') requireTikTokDraftCapability(shop, shopOptions);
          const common = readPublishPreference(false, capture, target.shopId, platform, organizationId);
          const category = readPublishPreference(true, capture, target.shopId, platform, organizationId);
          const resolved = resolveDestinationPublishOptions(platform, capture, shopOptions, common, category);
          const missing = [];
          if (!resolved.categoryId) missing.push(`原商品类目“${capture.categoryName || '未采集'}”无法可靠匹配店铺类目`);
          if (platform === 'TIKTOK_SHOP' && !resolved.warehouseId) missing.push('店铺没有唯一或默认发货仓库，请设置默认仓库');
          if (platform === 'SHOPEE' && !resolved.logisticInfo?.length) missing.push('店铺未启用配送渠道');
          const defaults = automaticProductPublishDefaults(capture, common);
          for (const [key, label] of [['weightGrams', '包裹重量'], ['packageLengthCm', '包裹长度'], ['packageWidthCm', '包裹宽度'], ['packageHeightCm', '包裹高度']]) {
            if (!Number.isFinite(Number(defaults[key])) || Number(defaults[key]) <= 0) missing.push(`${label}无效`);
          }
          if (missing.length) throw new Error(missing.join('；'));
          results[index] = {captureId: target.captureId, shopId: target.shopId, request: {
            confirmed: true, destinationPlatform: platform, shopId: target.shopId, ...defaults,
            categoryId: resolved.categoryId,
            ...(platform === 'TIKTOK_SHOP' ? {warehouseId: resolved.warehouseId} : {logisticInfo: resolved.logisticInfo})
          }};
        } catch (error) {
          current();
          errors[index] = `${title} → ${shop?.name || target.shopId}：${error.message}`;
        }
        current();
        onProgress(++completed, targets.length);
      }
    }
    await Promise.all(Array.from({length: Math.min(3, targets.length)}, worker));
    current();
    if (errors.some(Boolean)) {
      const error = new Error(`上传配置尚未就绪，本次未调用生图：\n${errors.filter(Boolean).join('\n')}`);
      error.preparationFailed = true;
      throw error;
    }
    return results;
  }

  async function restore(batchId, options = {}) {
    const current = () => { if (options.isCurrent && !options.isCurrent()) throw new Error('当前批次已切换，已取消准备'); };
    const root = `/api/v1/commerce-batches/${encodeURIComponent(batchId)}`;
    const [response, savedResponse, shopsResponse] = await Promise.all([read(root), read(`${root}/publish-options`),
      options.shops ? Promise.resolve({data: options.shops}) : read('/api/v1/shops')]);
    current();
    const targets = [...new Map((response.data?.items || []).filter(item => item.shopId)
      .map(item => [`${item.captureId}:${item.shopId}`, {captureId: item.captureId, shopId: item.shopId}])).values()];
    if (!targets.length) throw new Error('本批次没有可恢复的目标店铺');
    const saved = new Map((savedResponse.data?.publicationRequests || []).map(row => [`${row.captureId}:${row.shopId}`, row]));
    const shops = shopsResponse.data;
    if (!Array.isArray(shops)) throw new Error('店铺列表读取不完整，请重试');
    const positive = value => Number.isFinite(Number(value)) && Number(value) > 0;
    const present = value => value != null && String(value).trim() !== '';
    const stock = value => present(value) && Number.isInteger(Number(value)) && Number(value) >= 0;
    const complete = target => {
      const request = saved.get(`${target.captureId}:${target.shopId}`)?.request;
      const shop = shops.find(row => String(row.id) === String(target.shopId));
      return request && shop?.status === 'ACTIVE'
        && (request.autoConvertPrice === true ? request.publishPrice == null : positive(request.publishPrice))
        && stock(request.publishStock)
        && ['weightGrams', 'packageLengthCm', 'packageWidthCm', 'packageHeightCm'].every(field => positive(request[field]))
        && String(request.categoryId || '').trim()
        && (normalizedMarketplacePlatform(shop.platform) === 'TIKTOK_SHOP' ? String(request.warehouseId || '').trim()
          : normalizedMarketplacePlatform(shop.platform) === 'SHOPEE' && request.logisticInfo?.length);
    };
    // An already submitted item keeps its frozen price and settings. Only missing
    // combinations need fresh automatic defaults; retries must not rewrite them.
    const missing = targets.filter(target => !complete(target));
    const prepared = missing.length ? await prepare(missing, {...options, shops}) : [];
    const preparedByTarget = new Map(prepared.map(row => [`${row.captureId}:${row.shopId}`, row]));
    const requests = targets.map(target => {
      const key = `${target.captureId}:${target.shopId}`, replacement = preparedByTarget.get(key), previous = saved.get(key);
      if (!replacement) return previous;
      // A missing old field must not overwrite its freshly prepared value.
      // Preserve valid explicit settings, including zero stock and fixed prices.
      const request = {...replacement.request}, old = previous?.request || {};
      for (const field of ['weightGrams', 'packageLengthCm', 'packageWidthCm', 'packageHeightCm']) if (positive(old[field])) request[field] = old[field];
      for (const field of ['categoryId', 'warehouseId', 'sizeChartImageId']) if (present(old[field])) request[field] = old[field];
      if (stock(old.publishStock)) request.publishStock = old.publishStock;
      if (positive(old.publishPrice) && old.autoConvertPrice !== true) {
        request.publishPrice = old.publishPrice;request.autoConvertPrice = false;
      } else if (old.autoConvertPrice === true) request.autoConvertPrice = true;
      if (Array.isArray(old.productAttributes)) request.productAttributes = old.productAttributes;
      if (Array.isArray(old.logisticInfo) && old.logisticInfo.length) request.logisticInfo = old.logisticInfo;
      for (const field of ['skuStocks', 'shopeePayload']) if (old[field] && typeof old[field] === 'object' && !Array.isArray(old[field])) request[field] = old[field];
      return {...replacement, request};
    });
    // Resolve and atomically save the entire batch before resuming it.
    current();
    await api(`${root}/publish-options`, {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({publicationRequests: requests})
    });
    current();
    return requests;
  }
  globalThis.CommercePublication = {prepare, restore};
})();
