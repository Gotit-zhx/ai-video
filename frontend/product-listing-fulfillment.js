(function(root) {
  'use strict';
  const markets = ['PH','MY','SG','TH','VN','ID','KH','LA','MM','BN','TL'];
  const fields = ['warehouseName','contactName','phone','province','city','postalCode','address'];
  let context, platform = 'SHOPEE', config = null, request = 0, loading = false, saving = false;
  const byId = id => document.getElementById(id);
  const names = typeof Intl.DisplayNames === 'function' ? new Intl.DisplayNames(['zh'],{type:'region'}) : null;
  const label = code => `${names?.of(code) || code} · ${code}`;
  function origin() {
    const type = byId('listing-fulfillment-type').value;
    return type === 'DOMESTIC' ? 'CN' : type === 'OTHER' ? byId('listing-fulfillment-origin').value : byId('listing-fulfillment-country').value;
  }
  function controls() {
    byId('save-listing-fulfillment').disabled = loading || saving || !config || !context.canEdit();
    for (const field of fields) byId('listing-fulfillment-'+field).disabled = loading || saving || !context.canEdit();
    for (const id of ['country','type','origin']) byId('listing-fulfillment-'+id).disabled = saving;
    byId('save-listing-fulfillment').textContent = saving ? '保存中…' : '保存国家地址';
    byId('listing-fulfillment-origin-label').hidden = byId('listing-fulfillment-type').value !== 'OTHER';
  }
  async function load() {
    if (saving) return;
    const current = ++request, target = platform, market = byId('listing-fulfillment-country').value;
    const from = origin(), org = context.getUser()?.organizationId;
    config = null; loading = true; controls();
    for (const field of fields) byId('listing-fulfillment-'+field).value = '';
    byId('listing-fulfillment-status').textContent = '正在读取国家地址…';
    byId('listing-fulfillment-note').textContent = target === 'TIKTOK_SHOP'
      ? '海外和国内地址分别保存。上传时按发货国家及官方仓库名称选择店铺仓库；详细地址用于统一维护，实际仓库地址须在 TikTok 卖家后台登记一致。'
      : '海外和国内地址分别保存。虾皮商品上传接口不修改店铺发货地址；此处保存共用地址资料，实际地址请在虾皮卖家后台登记。';
    try {
      const result = await context.api(`/api/v1/product-listing-templates/fulfillment/${target}/${market}?originCountry=${from}`);
      if (current !== request || context.getUser()?.organizationId !== org) return;
      if (result.data?.platform !== target || result.data?.country !== market || result.data?.originCountry !== from)
        throw Error('地址配置返回不完整，请重试');
      config = result.data;
      for (const field of fields) byId('listing-fulfillment-'+field).value = config[field] || '';
      byId('listing-fulfillment-status').textContent = config.version ? `已保存 · ${label(market)}店铺从${label(from)}发货` : '尚未配置 · 填写后保存';
    } catch(error) {
      if (current === request) byId('listing-fulfillment-status').textContent = error.message;
    } finally { if (current === request) { loading = false; controls(); } }
  }
  async function save() {
    if (loading || saving || !config || !context.canEdit()) return;
    const target = platform, market = config.country, org = context.getUser()?.organizationId;
    const body = {...config};
    for (const field of fields) body[field] = byId('listing-fulfillment-'+field).value.trim();
    saving = true; controls();
    try {
      const result = await context.api(`/api/v1/product-listing-templates/fulfillment/${target}/${market}`,
        {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
      if (platform !== target || context.getUser()?.organizationId !== org) return;
      config = result.data;
      byId('listing-fulfillment-status').textContent = '保存成功 · 批量上传时可选择此地址';
    } catch(error) { byId('listing-fulfillment-status').textContent = error.message; }
    finally { saving = false; controls(); }
  }
  function setPlatform(value) { platform=value; return load(); }
  function reset() {
    byId('listing-fulfillment-type').value = 'OVERSEAS';
    return setPlatform('SHOPEE');
  }
  function init(options) {
    context = options;
    for (const id of ['listing-fulfillment-country','listing-fulfillment-origin']) {
      byId(id).replaceChildren(...markets.map(code=>new Option(label(code),code)));
      byId(id).addEventListener('change',load);
    }
    byId('listing-fulfillment-type').addEventListener('change',load);
    byId('save-listing-fulfillment').addEventListener('click',save);
    const bulk = byId('product-bulk-origin-country');
    for (const code of markets) bulk.append(new Option(label(code),code));
  }
  root.ProductListingFulfillment = {init,setPlatform,reset};
})(window);
