(function (root) {
  'use strict';
  let context, organization = '', batch = '', imageKey = '', uploading = false, generation = 0;
  let controls = {busy:false, editable:true, platform:'SHOPEE'};
  const byId = id => document.getElementById(id);
  function current(epoch, org, key) {
    return epoch === generation && org === organization && key === batch
      && context.getOrganization() === org;
  }
  function render(options = controls) {
    controls = options;
    const section = byId('product-bulk-size-chart');
    section.hidden = options.platform !== 'SHOPEE';
    const preview = byId('product-bulk-size-chart-preview');
    preview.hidden = !imageKey;
    if (imageKey) preview.src = '/api/v1/product-listing-templates/size-chart-image?key='+encodeURIComponent(imageKey);
    else preview.removeAttribute('src');
    byId('product-bulk-size-chart-remove').hidden = !imageKey;
    byId('product-bulk-size-chart-file').disabled = uploading || options.busy || !options.editable;
    byId('product-bulk-size-chart-remove').disabled = uploading || options.busy || !options.editable;
    byId('product-bulk-size-chart-status').textContent = uploading ? '尺码图上传中…'
      : imageKey ? '本次上传使用此图' : '未上传，自动使用原商品尺码表';
  }
  function open(org, key, items = []) {
    generation++; uploading = false; organization = org; batch = key;
    imageKey = items.find(item => item.sizeChartImageKey || item.payload?.sizeChartImageKey)?.sizeChartImageKey
      || items.find(item => item.payload?.sizeChartImageKey)?.payload.sizeChartImageKey || '';
    byId('product-bulk-size-chart-file').value = '';
    byId('product-bulk-size-chart-error').textContent = '';
  }
  function close() { generation++; uploading = false; }
  async function upload(file) {
    if (!file || uploading || controls.busy || !controls.editable
      || controls.platform !== 'SHOPEE' || !context.canPublish()) return;
    const error = byId('product-bulk-size-chart-error');
    error.textContent = '';
    if (!['image/png','image/jpeg'].includes(file.type) || file.size <= 0 || file.size > 8*1024*1024) {
      error.textContent = '请选择 8 MB 以内的 PNG 或 JPG 尺码图';
      byId('product-bulk-size-chart-file').value = '';
      return;
    }
    const epoch = ++generation, org = organization, key = batch;
    uploading = true; context.changed(); render();
    try {
      const body = new FormData(); body.append('file',file);
      const result = await context.api('/api/v1/product-listing-templates/size-chart-image',
        {method:'POST',body,signal:AbortSignal.timeout(60000)});
      if (!current(epoch,org,key)) return;
      if (!/^[a-f0-9]{64}\/[a-f0-9]{64}$/.test(result?.data?.storageKey || '')) throw Error('尺码图未上传成功，请重试');
      imageKey = result.data.storageKey;
      context.selected(imageKey);
    } catch (e) {
      if (current(epoch,org,key)) error.textContent = e.message || '尺码图上传失败，请重试';
    } finally {
      if (current(epoch,org,key)) {
        uploading = false; byId('product-bulk-size-chart-file').value = '';
        context.changed(); render();
      }
    }
  }
  function init(value) {
    context = value;
    byId('product-bulk-size-chart-file').addEventListener('change', event => void upload(event.target.files?.[0]));
    byId('product-bulk-size-chart-remove').addEventListener('click', () => {
      if (uploading || controls.busy || !controls.editable || !context.canPublish()) return;
      imageKey = ''; byId('product-bulk-size-chart-error').textContent = '';
      context.selected(''); context.changed(); render();
    });
  }
  const api = {init,open,close,render,upload,isUploading:()=>uploading,key:()=>imageKey};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ProductBulkSizeChart = api;
})(typeof window === 'undefined' ? globalThis : window);
