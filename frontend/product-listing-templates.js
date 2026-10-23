(function (root) {
  'use strict';
  let context, templates = [], organization = '', pending = null, platform = 'SHOPEE', busy = false;
  let shipping = null, shippingRequest = 0, shippingLoading = false;
  let warehouse = null, warehouseRequest = 0, warehouseLoading = false;
  let dataModule = '';
  let revision = 0, dialogRequest = 0;
  const currentModule = () => typeof commerceDataModule === 'function' ? commerceDataModule() : 'CAPTURE';
  function scoped(path, options = {}, scope = dataModule || currentModule()) {
    return context.api(path,{...options,headers:{...(options.headers||{}),'X-Commerce-Module':scope}});
  }
  const normalize = value => ['TIKTOK','TIKTOK_SHOP'].includes(String(value).toUpperCase()) ? 'TIKTOK_SHOP' : 'SHOPEE';
  const byId = id => document.getElementById(id);
  function get(target) {
    return organization === context?.getUser()?.organizationId && dataModule === currentModule()
      ? templates.find(item => item.platform === normalize(target)) : null;
  }
  async function ensure(force = false) {
    const org = context?.getUser()?.organizationId;
    if (!org) throw Error('请先登录');
    const scope = currentModule();
    if (org !== organization || scope !== dataModule) {
      organization = org; dataModule = scope; templates = []; pending = null;
      draftValues.clear(); shipping = null; shippingRequest++;
      warehouse = null; warehouseRequest++;
    }
    if (!force && templates.length) return templates;
    if (!pending || force) {
      const requestRevision=++revision;
      const request = scoped('/api/v1/product-listing-templates',{},scope).then(result => {
        if (requestRevision !== revision || context.getUser()?.organizationId !== org || organization !== org || dataModule !== scope || currentModule() !== scope)
          throw Error('当前功能或公司已变更，请重新打开');
        if (!Array.isArray(result.data) || result.data.length !== 2) throw Error('模板读取不完整，请重试');
        templates = result.data; return templates;
      }).finally(() => { if (pending === request) pending = null; });
      pending = request;
    }
    return pending;
  }
  function fields(template) {
    if (!template?.enabled) return {};
    return {weightGrams:String(template.weightGrams),packageLengthCm:String(template.packageLengthCm),
      packageWidthCm:String(template.packageWidthCm),packageHeightCm:String(template.packageHeightCm)};
  }
  function logisticInfo(channels, config) {
    const enabled = channels.filter(channel=>channel.enabled === true);
    const available = new Set(enabled.map(channel=>String(channel.logistics_channel_id)));
    const selected = config.mode === 'ALL_ENABLED' ? available : new Set((config.channelIds || []).map(String));
    if (!['ALL_ENABLED','SELECTED'].includes(config.mode) || !selected.size)
      throw Error('请在定制模板配置中选择至少一个已开通的物流渠道');
    if ([...selected].some(id=>!available.has(id)))
      throw Error('配置的物流渠道已停用或不属于当前店铺，请重新配置物流');
    return enabled.map(channel=>({logistic_id:Number(channel.logistics_channel_id),enabled:selected.has(String(channel.logistics_channel_id))}));
  }
  function setBusy(value) {
    busy = value;
    byId('product-listing-template-form').querySelectorAll('input,textarea,select,button').forEach(control => {
      if (!['close-product-listing-templates','cancel-product-listing-templates'].includes(control.id))
        control.disabled = value || control.dataset.logisticsEnabled === 'false'
          || (!control.dataset.listingPlatform && !context.canEdit());
    });
    byId('save-product-listing-template').disabled = value || !context.canEdit() || (platform === 'SHOPEE' ? shippingLoading : warehouseLoading);
    byId('save-product-listing-template').textContent = value ? '保存中…' : '保存模板';
  }
  const draftValues = new Map();
  function readDraft() {
    return {name:byId('listing-template-name').value,enabled:byId('listing-template-enabled').checked,
      description:byId('listing-template-description').value,
      sizeChartImageKey:get(platform)?.sizeChartImageKey || '',
      weightGrams:String(Math.round(Number(byId('listing-template-weight').value)*1000000)/1000),
      packageLengthCm:byId('listing-template-length').value,packageWidthCm:byId('listing-template-width').value,
      packageHeightCm:byId('listing-template-height').value,version:get(platform)?.version};
  }
  function shippingDraft() {
    const shopId = byId('listing-logistics-shop').value;
    if (!shopId || platform !== 'SHOPEE') return null;
    if (!shipping || shipping.shopId !== shopId || shippingLoading) throw Error('请等待店铺物流读取成功，或清空店铺选择后仅保存详情模板');
    return {shopId,mode:byId('listing-logistics-mode').value,version:shipping.version,
      channelIds:[...byId('listing-logistics-channels').querySelectorAll('input:checked')].map(input=>input.value)};
  }
  function renderShipping() {
    byId('listing-logistics-section').hidden = platform !== 'SHOPEE';
    byId('listing-logistics-mode-label').hidden = !shipping;
    const container = byId('listing-logistics-channels'); container.replaceChildren();
    if (!shipping) return;
    byId('listing-logistics-mode').value = shipping.mode;
    for (const channel of shipping.channels) {
      const row = document.createElement('label'); row.className = 'listing-logistics-channel';
      const text = document.createElement('span'); text.textContent = channel.name;
      const note = document.createElement('small'); note.textContent = channel.enabled ? '店铺已开通' : '店铺未开启';
      text.append(note);
      const input = document.createElement('input'); input.type = 'checkbox'; input.value = channel.id;
      input.checked = channel.selected; input.dataset.logisticsEnabled = String(channel.enabled);
      input.disabled = busy || !channel.enabled || !context.canEdit();
      input.addEventListener('change',()=>{byId('listing-logistics-mode').value = 'SELECTED';});
      row.append(text,input); container.append(row);
    }
  }
  async function loadShippingShops() {
    const org = context.getUser()?.organizationId;
    shipping = null; shippingLoading = false; const request = ++shippingRequest;
    const select = byId('listing-logistics-shop');
    select.replaceChildren(new Option('选择店铺设置物流','')); renderShipping();
    byId('listing-logistics-status').textContent = '默认启用目标店铺已开通的物流渠道。';
    try {
      const result = await scoped('/api/v1/product-listing-templates/logistics/shops');
      if (request !== shippingRequest || context.getUser()?.organizationId !== org) return;
      for (const shop of result.data || []) select.append(new Option(`${shop.name || shop.externalShopId} · ${shop.region || 'Shopee'}`,shop.id));
      if (!result.data?.length) byId('listing-logistics-status').textContent = '暂无已连接的虾皮店铺；连接后可配置物流。';
    } catch(error) {
      if (request === shippingRequest && context.getUser()?.organizationId === org) byId('listing-logistics-status').textContent = `店铺读取失败：${error.message}`;
    }
  }
  async function loadShipping() {
    const shopId = byId('listing-logistics-shop').value, org = context.getUser()?.organizationId, request = ++shippingRequest;
    shipping = null; shippingLoading = Boolean(shopId); renderShipping(); setBusy(busy);
    byId('listing-logistics-status').textContent = shopId ? '正在读取该店铺的官方物流渠道…' : '默认启用目标店铺已开通的物流渠道。';
    if (!shopId) return;
    try {
      const result = await scoped('/api/v1/product-listing-templates/logistics/'+encodeURIComponent(shopId),{signal:AbortSignal.timeout(95000)});
      if (request !== shippingRequest || context.getUser()?.organizationId !== org) return;
      if (result.data?.shopId !== shopId || !Array.isArray(result.data?.channels)) throw Error('物流返回不完整，请重试');
      shipping = result.data; renderShipping();
      byId('listing-logistics-status').textContent = shipping.version ? `已保存 · 物流版本 ${shipping.version}` : '尚未单独配置，默认启用全部已开通渠道。';
    } catch(error) {
      if (request === shippingRequest && context.getUser()?.organizationId === org) byId('listing-logistics-status').textContent = error.message;
    } finally {
      if (request === shippingRequest && context.getUser()?.organizationId === org) {shippingLoading = false;setBusy(busy);}
    }
  }
  function warehouseDraft() {
    const shopId=byId('listing-warehouse-shop').value;
    if(platform!=='TIKTOK_SHOP'||!shopId)return null;
    if(warehouseLoading||!warehouse||warehouse.shopId!==shopId)
      throw Error('请等待店铺发货仓库读取成功后保存');
    const warehouseId=byId('listing-warehouse-choice').value;
    if(warehouseId&&!warehouse.warehouses.some(w=>w.id===warehouseId))throw Error('请选择当前店铺可用的发货仓库');
    if(!warehouse.warehouses.length)throw Error('目标店铺没有可用的发货仓库');
    return {shopId,warehouseId,version:warehouse.version};
  }
  function renderWarehouse() {
    byId('listing-warehouse-section').hidden=platform!=='TIKTOK_SHOP';
    const select=byId('listing-warehouse-choice');
    byId('listing-warehouse-choice-label').hidden=!warehouse;
    if(!warehouse)return;
    const automatic=[...warehouse.warehouses].sort((a,b)=>{
      const rank=w=>w.isDefault?0:w.subType==='CB_OVERSEA_WAREHOUSE'?1:2;
      return rank(a)-rank(b)||(a.id<b.id?-1:a.id>b.id?1:0);
    })[0];
    select.replaceChildren(new Option('自动选择'+(automatic?' · '+(automatic.name||automatic.id):''),''));
    for(const item of warehouse.warehouses)
      select.append(new Option(`${item.name||'发货仓'} · ${item.id}${item.isDefault?' · 平台默认':''}${item.subType==='CB_OVERSEA_WAREHOUSE'?' · 海外仓':''}`,item.id));
    if(warehouse.warehouseId&&!warehouse.warehouses.some(w=>w.id===warehouse.warehouseId)){
      const invalid=new Option('原配置仓库已不可用，请重新选择',warehouse.warehouseId);invalid.disabled=true;select.append(invalid);
    }
    select.value=warehouse.warehouseId;
    showWarehouseAddress();
  }
  function showWarehouseAddress() {
    if(!warehouse)return;
    const selected=byId('listing-warehouse-choice').value;
    const auto=[...warehouse.warehouses].sort((a,b)=>{
      const rank=w=>w.isDefault?0:w.subType==='CB_OVERSEA_WAREHOUSE'?1:2;
      return rank(a)-rank(b)||(a.id<b.id?-1:a.id>b.id?1:0);
    })[0];
    const item=selected?warehouse.warehouses.find(w=>w.id===selected):auto;
    byId('listing-warehouse-status').textContent=item
      ? `上传将使用：${item.name||item.id}${item.address?' · '+item.address:''}`
      : warehouse.error||'未读取到可用发货仓库';
  }
  async function loadWarehouseShops() {
    const request=++warehouseRequest,org=context.getUser()?.organizationId,scope=dataModule;
    warehouse=null;warehouseLoading=false;
    const select=byId('listing-warehouse-shop');select.replaceChildren(new Option('选择店铺查看发货地址',''));
    renderWarehouse();byId('listing-warehouse-status').textContent='上传自动读取目标店铺发货仓库，优先平台默认；没有默认时优先海外仓。';
    try{
      const response=await scoped('/api/v1/product-listing-templates/warehouses/shops',{},scope);
      if(request!==warehouseRequest||org!==context.getUser()?.organizationId||scope!==currentModule())return;
      for(const shop of response.data||[])select.append(new Option(`${shop.name||shop.externalShopId} · ${shop.region||'TikTok Shop'}`,shop.id));
      if(!response.data?.length)byId('listing-warehouse-status').textContent='暂无已授权的 TikTok Shop 店铺。';
      if(response.data?.length===1){select.value=response.data[0].id;await loadWarehouse();}
    }catch(error){if(request===warehouseRequest)byId('listing-warehouse-status').textContent=error.message;}
  }
  async function loadWarehouse() {
    const shopId=byId('listing-warehouse-shop').value,request=++warehouseRequest,org=context.getUser()?.organizationId,scope=dataModule;
    warehouse=null;warehouseLoading=Boolean(shopId);renderWarehouse();setBusy(busy);
    byId('listing-warehouse-status').textContent=shopId?'正在读取该店铺的官方发货仓库…':'上传时自动读取目标店铺发货仓库。';
    if(!shopId)return;
    try{
      const response=await scoped('/api/v1/product-listing-templates/warehouses/'+encodeURIComponent(shopId),{signal:AbortSignal.timeout(180000)},scope);
      if(request!==warehouseRequest||org!==context.getUser()?.organizationId||scope!==currentModule())return;
      if(response.data?.shopId!==shopId||!Array.isArray(response.data.warehouses))throw Error('发货仓库读取不完整，请重试');
      warehouse=response.data;renderWarehouse();
    }catch(error){if(request===warehouseRequest)byId('listing-warehouse-status').textContent=error.message;}
    finally{if(request===warehouseRequest){warehouseLoading=false;setBusy(busy);}}
  }
  function render() {
    const template = draftValues.get(platform) || get(platform);
    if (!template) return;
    byId('listing-template-name').value = template.name;
    byId('listing-template-enabled').checked = template.enabled;
    byId('listing-template-description').value = template.description;
    byId('listing-template-weight').value = Number(template.weightGrams)/1000;
    for (const [id,key] of [['length','packageLengthCm'],['width','packageWidthCm'],['height','packageHeightCm']])
      byId('listing-template-'+id).value = template[key];
    document.querySelectorAll('[data-listing-platform]').forEach(button => button.setAttribute('aria-selected',String(button.dataset.listingPlatform === platform)));
    byId('listing-template-error').textContent = '';
    byId('listing-template-status').textContent = template.version ? `已保存 · 版本 ${template.version}` : '通用默认模板';
    byId('listing-logistics-section').hidden = platform !== 'SHOPEE';
    byId('listing-warehouse-section').hidden = platform !== 'TIKTOK_SHOP';
    setBusy(false);
  }
  async function open() {
    const request=++dialogRequest;
    const name={CAPTURE:'采集链接',FISSION:'一键裂变',TEMPLATES:'模板配置',ADS:'广告测款',ASSETS:'素材管理'}[currentModule()];
    byId('listing-template-title').textContent=name+' · 上传配置';
    platform = 'SHOPEE'; draftValues.clear();
    byId('listing-template-error').textContent = '';
    byId('listing-template-status').textContent = '正在读取模板…';
    byId('product-listing-template-dialog').showModal(); setBusy(true);
    try { await Promise.all([ensure(true),loadShippingShops()]); if(request === dialogRequest) {render();void loadWarehouseShops();} }
    catch (error) { if(request === dialogRequest) {byId('listing-template-error').textContent = error.message; setBusy(false); byId('save-product-listing-template').disabled = true;} }
  }
  async function save(event) {
    event.preventDefault();
    if (busy || !context.canEdit()) return;
    const org = organization, target = platform, scope = dataModule, body = readDraft();
    if(scope !== currentModule()) { byId('product-listing-template-dialog').close(); return; }
    let shippingBody;
    try {
      shippingBody = shippingDraft();
      if (shippingBody) body.logisticsConfig = shippingBody;
      const warehouseBody=warehouseDraft();if(warehouseBody)body.warehouseConfig=warehouseBody;
    } catch(error) {byId('listing-template-error').textContent = error.message;return;}
    setBusy(true); byId('listing-template-error').textContent = '';
    try {
      const result = await scoped('/api/v1/product-listing-templates/'+target,
        {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)},scope);
      if (context.getUser()?.organizationId !== org || organization !== org || dataModule !== scope || currentModule() !== scope) return;
      templates = templates.map(item => item.platform === target ? result.data : item);
      if (shippingBody && shipping?.shopId === shippingBody.shopId) {
        shipping.version = shippingBody.version+1; shipping.mode = shippingBody.mode;
        shipping.channels = shipping.channels.map(channel=>({...channel,selected:channel.enabled && (shipping.mode === 'ALL_ENABLED' || shippingBody.channelIds.includes(channel.id))}));
        renderShipping();byId('listing-logistics-status').textContent = `保存成功 · 物流版本 ${shipping.version}`;
      }
      draftValues.delete(target); render();
      if(body.warehouseConfig&&warehouse?.shopId===body.warehouseConfig.shopId){
        warehouse.version=body.warehouseConfig.version+1;warehouse.warehouseId=body.warehouseConfig.warehouseId;renderWarehouse();
      }
      byId('listing-template-status').textContent = '保存成功';
      context.onSaved?.();
    } catch(error) { if (organization === org && dataModule === scope && currentModule() === scope) byId('listing-template-error').textContent = error.message; }
    finally { if (organization === org && dataModule === scope && currentModule() === scope) setBusy(false); }
  }
  function preview(capture) {
    const section = byId('product-listing-template-preview');
    if (!section) return;
    section.hidden = !capture;
    if (!capture) return;
    if (section.dataset.captureId !== capture.id) {
      section.dataset.captureId = capture.id;
      byId('product-template-preview-platform').value = normalize(capture.sourcePlatform);
    }
    const target = byId('product-template-preview-platform').value, template = get(target);
    byId('product-template-preview-specs').textContent = !template ? '正在读取平台模板…'
      : template.enabled ? `${template.name} · ${Number(template.weightGrams)/1000} kg · ${template.packageLengthCm} × ${template.packageWidthCm} × ${template.packageHeightCm} cm`
      : '沿用原商品详情与包裹设置';
    byId('product-template-preview-description').textContent = template?.enabled ? template.description : (capture.normalizedData?.originalDescription ?? capture.description ?? '');
  }
  function init(config) {
    context = config;
    root.addEventListener?.('hashchange',()=>{
      if(dataModule && dataModule !== currentModule()){
        shippingRequest++;revision++;dialogRequest++;pending=null;shipping=null;draftValues.clear();
        warehouseRequest++;warehouse=null;warehouseLoading=false;
        byId('product-listing-template-dialog').close();
      }
    });
    byId('open-product-listing-templates').addEventListener('click', open);
    for (const id of ['close-product-listing-templates','cancel-product-listing-templates'])
      byId(id).addEventListener('click', () => byId('product-listing-template-dialog').close());
    byId('product-listing-template-form').addEventListener('submit', save);
    byId('listing-logistics-shop').addEventListener('change',loadShipping);
    byId('listing-warehouse-shop').addEventListener('change',loadWarehouse);
    byId('listing-warehouse-choice').addEventListener('change',()=>{
      if(warehouse)warehouse.warehouseId=byId('listing-warehouse-choice').value;
      showWarehouseAddress();
    });
    byId('listing-logistics-mode').addEventListener('change',()=>{
      if (!shipping) return;
      shipping.mode = byId('listing-logistics-mode').value;
      if (shipping.mode === 'ALL_ENABLED') shipping.channels = shipping.channels.map(channel=>({...channel,selected:channel.enabled}));
      else shipping.channels = shipping.channels.map(channel=>({...channel,selected:[...byId('listing-logistics-channels').querySelectorAll('input:checked')].some(input=>input.value === channel.id)}));
      renderShipping();
    });
    document.querySelectorAll('[data-listing-platform]').forEach(button => button.addEventListener('click', () => {
      if (busy || button.dataset.listingPlatform === platform) return;
      if (shipping && platform === 'SHOPEE' && !shippingLoading) {
        const draft = shippingDraft(); shipping.mode = draft.mode;
        shipping.channels = shipping.channels.map(channel=>({...channel,selected:draft.channelIds.includes(channel.id)}));
      }
      draftValues.set(platform,readDraft()); platform = button.dataset.listingPlatform; render(); renderShipping();
      renderWarehouse();
    }));
    byId('product-template-preview-platform').addEventListener('change', () => preview(context.getCapture()));
  }
  const api = {init,ensure,get,fields,preview,logisticInfo,open};
  if (typeof module !== 'undefined') module.exports = {fields,normalize,logisticInfo};
  else root.ProductListingTemplates = api;
})(typeof window === 'undefined' ? globalThis : window);
