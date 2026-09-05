(() => {
  "use strict";

  const INIT_EVENT = "heyi-collector-observer-init-v1";
  const URL_PATTERN = /(?:product|detail|pdp|sku|promotion|price)/i;
  const MAX_RESPONSE_BYTES = 2_000_000;
  const MAX_NODES = 30_000;
  let active = null;

  document.addEventListener(INIT_EVENT, event => {
    let request;
    try {
      request = JSON.parse(String(event.detail || ""));
    } catch {
      return;
    }
    const current = productIdentityFromLocation();
    const validProductId = request?.platform === "SHOPEE"
      ? /^\d{1,32}$/.test(request?.productId || "")
      : /^\d{6,32}$/.test(request?.productId || "");
    if (!/^[a-f0-9-]{20,80}$/i.test(request?.channel || "")
      || !Number.isSafeInteger(request?.generation) || request.generation < 1
      || !/^[a-f0-9-]{20,80}$/i.test(request?.nonce || "")
      || !validProductId
      || request.productId !== current?.productId
      || request.platform !== current?.platform
      || (current?.shopId && request.shopId !== current.shopId)) return;
    if (active?.channel === request.channel && request.generation < active.generation) return;
    if (active?.channel === request.channel && request.generation === active.generation
      && request.nonce !== active.nonce) return;
    const sameActivation = active?.channel === request.channel
      && active.generation === request.generation && active.nonce === request.nonce;
    active = {
      channel: request.channel,
      platform: request.platform,
      productId: request.productId,
      shopId: request.shopId || null,
      generation: request.generation,
      nonce: request.nonce,
      requestSequence: sameActivation ? active.requestSequence : 0,
      tikTokSkuIds: sameActivation ? active.tikTokSkuIds : null,
      tikTokSkuSequence: sameActivation ? active.tikTokSkuSequence : 0,
      pendingTikTokPrices: sameActivation ? active.pendingTikTokPrices : [],
      expiresAt: Date.now() + 30_000
    };
  });

  const originalFetch = globalThis.fetch;
  if (typeof originalFetch === "function") {
    globalThis.fetch = function heyiObservedFetch(...args) {
      const activation = snapshotForRequest();
      const request = originalFetch.apply(this, args);
      request.then(response => inspectFetchResponse(response, activation)).catch(() => {});
      return request;
    };
  }

  const xhrPrototype = globalThis.XMLHttpRequest?.prototype;
  if (xhrPrototype?.open && xhrPrototype?.send) {
    const originalOpen = xhrPrototype.open;
    const originalSend = xhrPrototype.send;
    xhrPrototype.open = function heyiObservedOpen(method, url, ...rest) {
      this.__heyiObservedUrl = safeSameOriginUrl(url);
      return originalOpen.call(this, method, url, ...rest);
    };
    xhrPrototype.send = function heyiObservedSend(...args) {
      if (this.__heyiObservedUrl && URL_PATTERN.test(this.__heyiObservedUrl.pathname + this.__heyiObservedUrl.search)) {
        const activation = snapshotForRequest();
        this.addEventListener("load", () => inspectXhrResponse(this, activation), { once: true });
      }
      return originalSend.apply(this, args);
    };
  }

  async function inspectFetchResponse(response, activation) {
    if (!activationStillCurrent(activation) || !response?.ok) return;
    const url = safeSameOriginUrl(response.url);
    if (!url || !URL_PATTERN.test(url.pathname + url.search)) return;
    try {
      const text = await limitedResponseText(response.clone());
      if (!text || text.length > MAX_RESPONSE_BYTES) return;
      inspectValue(JSON.parse(text), activation);
    } catch {
      // Non-JSON and oversized responses are ignored.
    }
  }

  async function limitedResponseText(response) {
    if (!response.body?.getReader) return response.text();
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let bytes = 0;
    let text = "";
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        bytes += value?.byteLength || 0;
        if (bytes > MAX_RESPONSE_BYTES) {
          await reader.cancel();
          return "";
        }
        text += decoder.decode(value, { stream: true });
      }
      return text + decoder.decode();
    } finally {
      reader.releaseLock?.();
    }
  }

  function inspectXhrResponse(xhr, activation) {
    if (!activationStillCurrent(activation) || xhr.status < 200 || xhr.status >= 300) return;
    const url = safeSameOriginUrl(xhr.responseURL || xhr.__heyiObservedUrl);
    if (!url || !URL_PATTERN.test(url.pathname + url.search)) return;
    try {
      const rawText = xhr.responseType === "json" ? "" : String(xhr.responseText || "");
      if (rawText.length > MAX_RESPONSE_BYTES) return;
      const value = xhr.responseType === "json" ? xhr.response : JSON.parse(rawText);
      inspectValue(value, activation);
    } catch {
      // Non-JSON XHR responses are ignored.
    }
  }

  function inspectValue(value, activation) {
    if (!activationStillCurrent(activation)) return;
    const candidate = findProduct(value, activation);
    if (activation.platform !== "TIKTOK_SHOP") {
      if (candidate) emitCandidate(candidate, activation, activation.sequence);
      return;
    }

    if (candidate) {
      rememberTikTokSkus(candidate, activation.sequence);
      emitCandidate(candidate, activation, activation.sequence);
    }
    const fragments = findTikTokPromotionFragments(value, activation.productId);
    const candidatePriceModel = candidate ? tikTokPromotionPriceModel(candidate) : null;
    for (const fragment of fragments) {
      if (fragment.sourceModel === candidatePriceModel) continue;
      if (canAssociateTikTokPrices(fragment)) emitCandidate(fragment.payload, activation, activation.sequence);
      else if (!fragment.productScope) rememberPendingTikTokPrices(fragment, activation.sequence);
    }
    if (candidate) flushPendingTikTokPrices(activation);
  }

  function emitCandidate(candidate, activation, sequence) {
    let payload;
    try {
      payload = JSON.stringify(candidate);
    } catch {
      return;
    }
    if (!payload || payload.length > MAX_RESPONSE_BYTES) return;
    document.dispatchEvent(new CustomEvent(`heyi-collector-data-${activation.channel}`, {
      detail: JSON.stringify({
        platform: activation.platform,
        productId: activation.productId,
        shopId: activation.shopId,
        generation: activation.generation,
        nonce: activation.nonce,
        sequence,
        payload
      })
    }));
  }

  function snapshotForRequest() {
    if (!active || active.expiresAt <= Date.now()) return null;
    active.requestSequence += 1;
    return {
      channel: active.channel,
      platform: active.platform,
      productId: active.productId,
      shopId: active.shopId,
      generation: active.generation,
      nonce: active.nonce,
      sequence: active.requestSequence
    };
  }

  function activationStillCurrent(snapshot) {
    if (!snapshot || !active || active.expiresAt <= Date.now()) return false;
    if (snapshot.channel !== active.channel || snapshot.generation !== active.generation
      || snapshot.nonce !== active.nonce) return false;
    const current = productIdentityFromLocation();
    return snapshot.productId === current?.productId && snapshot.platform === current?.platform
      && (!snapshot.shopId || snapshot.shopId === current?.shopId);
  }

  function findProduct(root, identity) {
    const stack = [{ value: root, depth: 0 }];
    let inspected = 0;
    while (stack.length && inspected < MAX_NODES) {
      const { value, depth } = stack.pop();
      inspected += 1;
      if (!value || typeof value !== "object" || depth > 16) continue;
      if (!Array.isArray(value)) {
        if (identity.platform === "SHOPEE") {
          const nestedItem = value.item;
          if (nestedItem && matchesShopeeItem(nestedItem, identity)) {
            return { item: nestedItem, shop_detailed: value.shop_detailed || value.shopDetailed || null };
          }
          if (matchesShopeeItem(value, identity)) return { item: value };
        }
        const nestedProduct = value.product_model || value.productModel;
        const nestedSkus = nestedProduct && skuArray(nestedProduct);
        const nestedId = nestedProduct?.product_id ?? nestedProduct?.productId;
        if (identity.platform === "TIKTOK_SHOP" && nestedSkus && String(nestedId ?? "") === identity.productId) {
          return {
            product_model: nestedProduct,
            promotion_model: value.promotion_model || value.promotionModel || null,
            seller_model: value.seller_model || value.sellerModel || null,
            shop_model: value.shop_model || value.shopModel || null,
            review_model: value.review_model || value.reviewModel || null
          };
        }
        const skus = skuArray(value);
        const candidateId = value.product_id ?? value.productId ?? (skus ? value.id : null);
        if (identity.platform === "TIKTOK_SHOP" && skus && String(candidateId ?? "") === identity.productId) return value;
      }
      const children = Array.isArray(value) ? value.slice(0, 700) : Object.values(value).slice(0, 700);
      for (let index = children.length - 1; index >= 0; index -= 1) {
        const child = children[index];
        if (child && typeof child === "object") stack.push({ value: child, depth: depth + 1 });
      }
    }
    return null;
  }

  function rememberTikTokSkus(candidate, sequence) {
    if (!active || sequence < active.tikTokSkuSequence) return;
    const product = candidate?.product_model || candidate?.productModel || candidate;
    const skus = skuArray(product);
    if (!Array.isArray(skus) || skus.length === 0 || skus.length > 500) return;
    const ids = skus.map(sku => cleanIdentifier(sku?.sku_id ?? sku?.skuId ?? sku?.id)).filter(Boolean);
    // Anonymous price fragments are only safe to associate when every product
    // SKU has a unique, explicit ID.
    if (ids.length !== skus.length || new Set(ids).size !== ids.length) return;
    active.tikTokSkuIds = new Set(ids);
    active.tikTokSkuSequence = sequence;
  }

  function findTikTokPromotionFragments(root, productId) {
    const result = [];
    const seenModels = new Set();
    const stack = [{ value: root, depth: 0, productScope: null }];
    let inspected = 0;
    while (stack.length && inspected < MAX_NODES && result.length < 16) {
      const { value, depth, productScope } = stack.pop();
      inspected += 1;
      if (!value || typeof value !== "object" || depth > 16) continue;
      if (Array.isArray(value)) {
        for (let index = Math.min(value.length, 700) - 1; index >= 0; index -= 1) {
          const child = value[index];
          if (child && typeof child === "object") stack.push({ value: child, depth: depth + 1, productScope });
        }
        continue;
      }

      const scope = explicitTikTokProductId(value) || productScope;
      const priceModel = tikTokPromotionPriceModel(value);
      if (priceModel && !seenModels.has(priceModel)) {
        seenModels.add(priceModel);
        const entries = normalizedTikTokPriceEntries(priceModel);
        if (entries?.size && (!scope || scope === productId)) {
          const sanitizedModel = {};
          for (const key of ["currency_name", "currencyName", "currency"]) {
            if (typeof priceModel[key] === "string") sanitizedModel[key] = priceModel[key].slice(0, 16);
          }
          sanitizedModel.skus_price = Object.fromEntries(entries);
          result.push({
            productScope: scope,
            skuIds: new Set(entries.keys()),
            sourceModel: priceModel,
            payload: {
              ...(scope ? { product_id: scope } : {}),
              promotion_model: { promotion_product_price: sanitizedModel }
            }
          });
        }
      }
      for (const child of Object.values(value).slice(0, 700).reverse()) {
        if (child && typeof child === "object") stack.push({ value: child, depth: depth + 1, productScope: scope });
      }
    }
    return result;
  }

  function explicitTikTokProductId(value) {
    const direct = value?.product_id ?? value?.productId;
    if (/^\d{6,32}$/.test(String(direct ?? ""))) return String(direct);
    const nested = value?.product_model || value?.productModel;
    const nestedId = nested?.product_id ?? nested?.productId;
    return /^\d{6,32}$/.test(String(nestedId ?? "")) ? String(nestedId) : null;
  }

  function tikTokPromotionPriceModel(value) {
    const promotion = value?.promotion_model || value?.promotionModel;
    const nested = promotion?.promotion_product_price || promotion?.promotionProductPrice;
    if (nested && typeof nested === "object" && !Array.isArray(nested)) return nested;
    const direct = value?.promotion_product_price || value?.promotionProductPrice;
    if (direct && typeof direct === "object" && !Array.isArray(direct)) return direct;
    if ((value?.skus_price || value?.skusPrice) && typeof value === "object" && !Array.isArray(value)) return value;
    return null;
  }

  function normalizedTikTokPriceEntries(priceModel) {
    const raw = priceModel?.skus_price || priceModel?.skusPrice;
    if (!raw || typeof raw !== "object") return null;
    const source = Array.isArray(raw) ? raw.slice(0, 501) : Object.entries(raw).slice(0, 501);
    if (source.length === 0 || source.length > 500) return null;
    const result = new Map();
    for (const item of source) {
      const keyed = !Array.isArray(raw);
      const key = keyed ? cleanIdentifier(item[0]) : null;
      const entry = keyed ? item[1] : item;
      if (!entry || typeof entry !== "object" || Array.isArray(entry)) return null;
      const embeddedId = cleanIdentifier(entry.sku_id ?? entry.skuId ?? entry.id);
      if (key && embeddedId && key !== embeddedId) return null;
      const skuId = key || embeddedId;
      if (!skuId || result.has(skuId)) return null;
      const sanitized = {};
      for (const field of [
        "sku_id", "skuId", "id", "sale_price_decimal", "salePriceDecimal", "sale_price", "salePrice", "price",
        "origin_price_decimal", "originPriceDecimal", "original_price", "originalPrice", "origin_price", "originPrice",
        "currency_name", "currencyName", "currency"
      ]) {
        if (Object.prototype.hasOwnProperty.call(entry, field)) sanitized[field] = entry[field];
      }
      if (!hasTikTokPriceEvidence(sanitized)) return null;
      result.set(skuId, sanitized);
    }
    return result;
  }

  function hasTikTokPriceEvidence(entry) {
    return [
      "sale_price_decimal", "salePriceDecimal", "sale_price", "salePrice", "price",
      "origin_price_decimal", "originPriceDecimal", "original_price", "originalPrice", "origin_price", "originPrice"
    ].some(key => Object.prototype.hasOwnProperty.call(entry, key));
  }

  function canAssociateTikTokPrices(fragment) {
    if (!active || active.platform !== "TIKTOK_SHOP") return false;
    if (fragment.productScope) return fragment.productScope === active.productId;
    return active.tikTokSkuIds instanceof Set && fragment.skuIds.size > 0
      && [...fragment.skuIds].every(skuId => active.tikTokSkuIds.has(skuId));
  }

  function rememberPendingTikTokPrices(fragment, sequence) {
    if (!active || active.platform !== "TIKTOK_SHOP") return;
    active.pendingTikTokPrices.push({ fragment, sequence });
    while (active.pendingTikTokPrices.length > 8) active.pendingTikTokPrices.shift();
  }

  function flushPendingTikTokPrices(activation) {
    if (!active || active.platform !== "TIKTOK_SHOP") return;
    const pending = active.pendingTikTokPrices.splice(0);
    for (const entry of pending) {
      if (canAssociateTikTokPrices(entry.fragment)) emitCandidate(entry.fragment.payload, activation, entry.sequence);
    }
  }

  function cleanIdentifier(value) {
    const result = String(value ?? "").trim().slice(0, 128);
    return /^[A-Za-z0-9._:-]+$/.test(result) ? result : null;
  }

  function matchesShopeeItem(value, identity) {
    if (!value || typeof value !== "object" || !Array.isArray(value.models)) return false;
    const itemId = value.item_id ?? value.itemid;
    const shopId = value.shop_id ?? value.shopid;
    return String(itemId ?? "") === identity.productId
      && /^\d{1,32}$/.test(String(shopId ?? ""))
      && String(shopId) === identity.shopId;
  }

  function skuArray(product) {
    const direct = product.skus || product.sku_infos || product.skuInfos || product.sku_list || product.skuList;
    if (Array.isArray(direct)) return direct;
    const container = product.sku_info || product.skuInfo || product.sku_data || product.skuData;
    if (!container || typeof container !== "object") return null;
    const nested = container.skus || container.sku_infos || container.skuInfos || container.sku_list || container.skuList;
    return Array.isArray(nested) ? nested : null;
  }

  function safeSameOriginUrl(value) {
    try {
      const url = new URL(String(value || ""), location.href);
      return url.protocol === "https:" && url.origin === location.origin ? url : null;
    } catch {
      return null;
    }
  }

  function productIdentityFromLocation() {
    const host = location.hostname.toLowerCase();
    if (/(^|\.)shopee\.(sg|ph|vn)$/.test(host) || /(^|\.)shopee\.com\.my$/.test(host)
      || /(^|\.)shopee\.co\.(th|id)$/.test(host)) {
      const direct = location.pathname.match(/-i\.(\d+)\.(\d+)(?:$|[/?])/i)
        || location.pathname.match(/\/product\/(\d+)\/(\d+)(?:$|[/?])/i);
      if (direct) return { platform: "SHOPEE", shopId: direct[1], productId: direct[2] };
      const search = new URLSearchParams(location.search);
      const shopId = search.get("shopid") || search.get("shop_id");
      const productId = search.get("itemid") || search.get("item_id");
      return /^\d{1,32}$/.test(shopId || "") && /^\d{1,32}$/.test(productId || "")
        ? { platform: "SHOPEE", shopId, productId }
        : null;
    }
    let path;
    try {
      path = decodeURIComponent(location.pathname);
    } catch {
      path = location.pathname;
    }
    const direct = path.match(/\/view\/product\/(\d{6,32})/i);
    const pdpPath = path.match(/\/(?:(?:[a-z]{2}(?:-[a-z]{2})?)\/)?(?:shop\/)?(?:pdp|product)\/(.+?)\/?$/i);
    const pdp = pdpPath?.[1]?.match(/(\d{8,32})$/);
    const query = new URLSearchParams(location.search).get("product_id")
      || new URLSearchParams(location.search).get("productId");
    const productId = direct?.[1] || pdp?.[1] || (/^\d{6,32}$/.test(query || "") ? query : "");
    return productId ? { platform: "TIKTOK_SHOP", shopId: null, productId } : null;
  }
})();
