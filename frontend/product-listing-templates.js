(function (root) {
  'use strict';
  let context, templates = [], organization = '', pending = null, platform = 'SHOPEE', busy = false;
  const normalize = value => ['TIKTOK','TIKTOK_SHOP'].includes(String(value).toUpperCase()) ? 'TIKTOK_SHOP' : 'SHOPEE';
  const byId = id => document.getElementById(id);
  function get(target) {
    return organization === context?.getUser()?.organizationId ? templates.find(item => item.platform === normalize(target)) : null;
  }
  async function ensure(force = false) {
    const org = context?.getUser()?.organizationId;
    if (!org) throw Error('请先登录');
    if (org !== organization) { organization = org; templates = []; pending = null; }
    if (!force && templates.length) return templates;
    if (!pending || force) {
      const request = context.api('/api/v1/product-listing-templates').then(result => {
        if (context.getUser()?.organizationId !== org || organization !== org) throw Error('当前公司已变更，请重新打开');
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
  function setBusy(value) {
    busy = value;
    byId('product-listing-template-form').querySelectorAll('input,textarea,button').forEach(control => {
      if (!['close-product-listing-templates','cancel-product-listing-templates'].includes(control.id))
        control.disabled = value || (!control.dataset.listingPlatform && !context.canEdit());
    });
    byId('save-product-listing-template').textContent = value ? '保存中…' : '保存模板';
  }
  const draftValues = new Map();
  function readDraft() {
    return {name:byId('listing-template-name').value,enabled:byId('listing-template-enabled').checked,
      description:byId('listing-template-description').value,
      weightGrams:String(Math.round(Number(byId('listing-template-weight').value)*1000000)/1000),
      packageLengthCm:byId('listing-template-length').value,packageWidthCm:byId('listing-template-width').value,
      packageHeightCm:byId('listing-template-height').value,version:get(platform)?.version};
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
    setBusy(false);
  }
  async function open() {
    platform = 'SHOPEE'; draftValues.clear();
    byId('listing-template-error').textContent = '';
    byId('listing-template-status').textContent = '正在读取模板…';
    byId('product-listing-template-dialog').showModal(); setBusy(true);
    try { await ensure(true); render(); }
    catch (error) { byId('listing-template-error').textContent = error.message; setBusy(false); byId('save-product-listing-template').disabled = true; }
  }
  async function save(event) {
    event.preventDefault();
    if (busy || !context.canEdit()) return;
    const org = organization, target = platform, body = readDraft();
    setBusy(true); byId('listing-template-error').textContent = '';
    try {
      const result = await context.api('/api/v1/product-listing-templates/'+target,
        {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
      if (context.getUser()?.organizationId !== org || organization !== org) return;
      templates = templates.map(item => item.platform === target ? result.data : item);
      draftValues.delete(target); render();
      byId('listing-template-status').textContent = '保存成功 · 后续采集及上传自动使用此模板';
      context.onSaved?.();
    } catch(error) { if (organization === org) byId('listing-template-error').textContent = error.message; }
    finally { if (organization === org) setBusy(false); }
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
    byId('open-product-listing-templates').addEventListener('click', open);
    for (const id of ['close-product-listing-templates','cancel-product-listing-templates'])
      byId(id).addEventListener('click', () => byId('product-listing-template-dialog').close());
    byId('product-listing-template-form').addEventListener('submit', save);
    document.querySelectorAll('[data-listing-platform]').forEach(button => button.addEventListener('click', () => {
      if (busy || button.dataset.listingPlatform === platform) return;
      draftValues.set(platform,readDraft()); platform = button.dataset.listingPlatform; render();
    }));
    byId('product-template-preview-platform').addEventListener('change', () => preview(context.getCapture()));
  }
  const api = {init,ensure,get,fields,preview};
  if (typeof module !== 'undefined') module.exports = {fields,normalize};
  else root.ProductListingTemplates = api;
})(typeof window === 'undefined' ? globalThis : window);
