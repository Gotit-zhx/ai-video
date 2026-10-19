(() => {
  'use strict';
  const root = document.querySelector('#main-style-workspace');
  if (!root) return;
  const endpoint = '/api/v1/product-main-image-templates';
  const list = document.querySelector('#main-style-list');
  const detail = document.querySelector('#main-style-detail');
  const status = document.querySelector('#main-style-status');
  const dialog = document.querySelector('#main-style-create-dialog');
  const form = document.querySelector('#main-style-create-form');
  const fileInput = document.querySelector('#main-style-files');
  const uploadList = document.querySelector('#main-style-upload-list');
  const error = document.querySelector('#main-style-upload-error');
  const save = document.querySelector('#main-style-save');
  const uploadProgress = document.querySelector('#main-style-upload-progress');
  const uploadLabel = document.querySelector('#main-style-upload-label');
  const uploadBar = document.querySelector('#main-style-upload-bar');
  const uploadMeta = document.querySelector('#main-style-upload-meta');
  const select = document.querySelector('#product-bulk-main-style');
  const bulkStatus = document.querySelector('#product-bulk-main-style-status');
  const labels = {READY: '已就绪', ANALYZING: '提取中', PENDING: '排队中', QUEUED: '排队中', FAILED: '提取失败'};
  let rows = [], selectedId = '', files = [], busy = false, requestKey = '', generation = 0, timer, bulkRevision = 0, detailSignature = '';
  let uploadRequest, uploadTimer, uploadState;
  const promptDrafts = new Map();
  const isAnalyzing = row => row.images.some(image => ['PENDING', 'QUEUED', 'ANALYZING'].includes(image.status));
  const preservesOriginal = image => image.status === 'READY' && image.analysis?.mode === 'PRESERVE_ORIGINAL';
  const esc = value => escapeHtml(String(value ?? ''));
  const canEdit = () => hasAnyPermission('product:edit');
  const notify = message => { status.textContent = message; };
  const button = (action, label, icon, extra = '') => `<button type="button" data-style-action="${action}" title="${label}" aria-label="${label}" ${extra}>${icon}</button>`;

  function analysisProgress(row, compact = false) {
    const total = row.images.length;
    const ready = row.images.filter(image => image.status === 'READY').length;
    const failed = row.images.filter(image => image.status === 'FAILED').length;
    const active = row.images.filter(image => image.status === 'ANALYZING');
    const pending = row.images.filter(image => ['PENDING', 'QUEUED'].includes(image.status)).length;
    const processed = ready + failed;
    const title = processed === total ? (failed ? '提取结束，有失败图片' : '全部提取完成') : '正在提取图集';
    const current = active.length
      ? `正在分析：第 ${active.map(image => image.index + 1).join('、')} 张`
      : pending ? '等待分析任务启动' : failed ? '失败图片可重试' : '图集已就绪';
    return `<div class="main-style-progress ${failed ? 'has-failures' : ''}" ${compact ? '' : 'role="status" aria-live="polite"'}>
      <div class="main-style-progress-heading"><strong>${compact ? '提取进度' : title}</strong><span>已处理 ${processed} / ${total} 张</span></div>
      <progress max="${total || 1}" value="${processed}" aria-label="海报分析已处理 ${processed} / ${total} 张"></progress>
      <div class="main-style-progress-counts"><span>成功 ${ready}</span><span${failed ? ' class="main-style-error"' : ''}>失败 ${failed}</span><span>提取中 ${active.length}</span><span>排队 ${pending}</span></div>
      <p>${current}</p></div>`;
  }
  function statusLabel(row) {
    return isAnalyzing(row) ? `提取中${row.status === 'FAILED' ? ' · 有失败' : ''}` : labels[row.status] || esc(row.status);
  }
  function imageProgress(image, index) {
    const active = image.status === 'ANALYZING', failed = image.status === 'FAILED';
    const progress = image.analysis?.progress || {};
    const stages = {MODEL_WAITING: 'Pi Agent 已启动，等待模型响应', MODEL_STREAMING: 'Pi Agent 正在返回分析结果', VALIDATING: '正在校验产品识别、版式与提示词'};
    const elapsed = Math.max(0, Number(progress.elapsedSeconds) || 0);
    const characters = Math.max(0, Number(progress.outputCharacters) || 0);
    return `<div class="main-style-progress main-style-image-progress" role="status" aria-live="polite">
      <strong${failed ? ' class="main-style-error"' : ''}>第 ${index + 1} 张 · ${active ? 'Pi Agent 分析中' : failed ? '提取失败' : '排队中'}</strong>
      ${failed ? '' : `<progress max="1" ${active ? '' : 'value="0"'} aria-label="第 ${index + 1} 张${active ? '正在分析' : '等待分析'}"></progress>`}
      <p${failed ? ' class="main-style-error"' : ''}>${esc(failed ? image.error || '提取失败，请重试'
        : active ? stages[progress.stage] || '正在读取参考图并启动分析' : '图片已保存，等待分析任务启动')}</p>
      ${progress.stage ? `<span class="main-style-progress-meta">已用 ${elapsed} 秒 · 已返回 ${characters} 字符</span>` : ''}</div>`;
  }
  function restorePromptFocus(editing) {
    if (!editing) return;
    const input = [...detail.querySelectorAll('[data-style-prompt]')]
      .find(node => node.closest('[data-image-id]').dataset.imageId === editing.id);
    input?.focus({preventScroll: true});
    input?.setSelectionRange(editing.start, editing.end);
  }
  function render() {
    document.querySelector('#main-style-count').textContent = `${rows.length} 套模板`;
    document.querySelector('#main-style-new').hidden = !canEdit();
    list.hidden = Boolean(selectedId);
    detail.hidden = !selectedId;
    if (!selectedId) {
      list.innerHTML = rows.length ? rows.map(row => `<article class="main-style-card">
        <button class="main-style-open" type="button" data-style-id="${esc(row.id)}">
          <div class="main-style-contact-sheet">${row.images.slice(0, 4).map(image => `<img src="${esc(image.imageUrl)}" alt="${esc(row.name)}参考海报" loading="lazy">`).join('')}</div>
          <strong>${esc(row.name)}</strong><span>${row.imageCount} 张 <span class="main-style-badge is-${esc(row.status.toLowerCase())}">${statusLabel(row)}</span></span>
          ${analysisProgress(row, true)}
        </button></article>`).join('') : '<div class="main-style-empty"><strong>暂无主图风格模板</strong><button type="button" data-style-action="new">＋ 新建模板</button></div>';
      return;
    }
    const row = rows.find(row => row.id === selectedId);
    if (!row) { selectedId = ''; render(); return; }
    const signature = JSON.stringify(row);
    if (signature === detailSignature && detail.childElementCount) return;
    const editing = detail.contains(document.activeElement) && document.activeElement.matches('[data-style-prompt]')
      ? {id: document.activeElement.closest('[data-image-id]').dataset.imageId,
        start: document.activeElement.selectionStart, end: document.activeElement.selectionEnd} : null;
    detailSignature = signature;
    detail.innerHTML = `<header class="main-style-detail-heading"><div>${button('back', '返回模板列表', '←')}<h2>${esc(row.name)}</h2><span>${row.imageCount} 张</span><span class="main-style-badge is-${esc(row.status.toLowerCase())}">${statusLabel(row)}</span></div>
      <div class="main-style-actions">${row.status === 'FAILED' && canEdit() ? '<button type="button" data-style-action="retry">重试失败图片</button>' : ''}
      ${button('export', '下载提示词', '↓')}${canEdit() ? '<button type="button" data-style-action="archive">归档模板</button>' : ''}</div></header>
      ${analysisProgress(row)}
      <div class="main-style-image-list">${row.images.map((image, index) => {
        const analysis = image.analysis || {};
        return `<article class="main-style-image-row" data-image-id="${esc(image.id)}">
          <div><div class="main-style-reference"><img src="${esc(image.imageUrl)}" alt="第${index + 1}张参考海报">
          ${(analysis.productRegions || []).map(region => `<span class="main-style-region" style="left:${region.x * 100}%;top:${region.y * 100}%;width:${region.width * 100}%;height:${region.height * 100}%" title="识别的商品区域"></span>`).join('')}</div>
          <div class="main-style-image-caption"><b>${String(index + 1).padStart(2, '0')}</b><span>${image.width} × ${image.height}</span><span>${preservesOriginal(image) ? '原样保留' : labels[image.status] || esc(image.status)}</span></div></div>
          <div class="main-style-analysis">${preservesOriginal(image)
            ? `<strong>原样保留</strong><dl><dt>图片内容</dt><dd>${esc(analysis.productDescription)}</dd><dt>海报布局</dt><dd>${esc(analysis.layout)}</dd></dl>`
            : image.status === 'READY' ? `<dl><dt>产品主体</dt><dd>${esc(analysis.productDescription)}</dd><dt>海报布局</dt><dd>${esc(analysis.layout)}</dd><dt>配色与文字</dt><dd>${esc(analysis.palette)}<br>${esc(analysis.typography)}</dd></dl>
            <details><summary>背景、光影与装饰</summary><p>${esc(analysis.background)}<br>${esc(analysis.lighting)}<br>${esc(analysis.decorations)}</p></details>
            <label>生成提示词<textarea data-style-prompt rows="5" maxlength="4000" ${canEdit() ? '' : 'readonly'}>${esc(promptDrafts.get(image.id) ?? analysis.prompt)}</textarea></label>
            <div class="main-style-actions">${button('copy', '复制提示词', '⧉')}${canEdit() ? '<button type="button" data-style-action="save-prompt">保存修改</button>' : ''}<span data-image-status role="status"></span></div>`
          : imageProgress(image, index)}</div></article>`;
      }).join('')}</div>`;
    restorePromptFocus(editing);
  }

  async function refresh({quiet = false} = {}) {
    const ticket = generation, org = currentUser?.organizationId;
    clearTimeout(timer);
    try {
      if (!quiet) notify('正在读取模板…');
      const response = await api(endpoint);
      if (ticket !== generation || currentUser?.organizationId !== org) return;
      rows = response.data;
      render();
      const active = rows.filter(isAnalyzing);
      notify(active.length ? `${active.length} 套模板正在自动提取` : '');
    } catch (failure) { if (ticket === generation) notify(failure.message); }
    finally {
      if (ticket === generation && !root.hidden && currentUser?.organizationId === org && rows.some(isAnalyzing))
        timer = setTimeout(() => refresh({quiet: true}), 3000);
    }
  }

  function clearFiles() {
    files.forEach(item => URL.revokeObjectURL(item.url));
    files = []; fileInput.value = ''; requestKey = '';
  }
  const formatBytes = bytes => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  function renderUploadProgress() {
    uploadProgress.hidden = !uploadState;
    if (!uploadState) return;
    const seconds = Math.max(0, Math.floor((Date.now() - uploadState.startedAt) / 1000));
    if (uploadState.stage === 'saving') {
      uploadLabel.textContent = '上传完成，正在校验并保存图集';
      uploadBar.removeAttribute('value');
      uploadMeta.textContent = `${files.length} 张图片已上传 · 等待服务器确认 ${seconds} 秒`;
      save.textContent = '正在保存图集…';
    } else {
      const {loaded, total} = uploadState;
      const percent = total ? Math.min(100, Math.floor(loaded / total * 100)) : null;
      uploadLabel.textContent = percent === null ? '正在上传图集…' : `正在上传图集 ${percent}%`;
      if (percent === null) uploadBar.removeAttribute('value');
      else uploadBar.value = percent;
      uploadMeta.textContent = `${formatBytes(loaded)}${total ? ` / ${formatBytes(total)}` : ' 已上传'} · ${files.length} 张 · 已用 ${seconds} 秒`;
      save.textContent = '正在上传…';
    }
  }
  function clearUploadProgress() {
    clearInterval(uploadTimer); uploadTimer = null; uploadState = null;
    renderUploadProgress();
  }
  function uploadTemplate(data, ticket, org) {
    const current = () => ticket === generation && currentUser?.organizationId === org;
    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest();
      uploadRequest = request;
      request.open('POST', endpoint);
      request.responseType = 'json';
      request.withCredentials = true;
      request.timeout = 10 * 60 * 1000;
      request.setRequestHeader('Idempotency-Key', requestKey);
      request.setRequestHeader('X-Commerce-Module', typeof commerceDataModule === 'function' ? commerceDataModule() : 'CAPTURE');
      request.upload.addEventListener('progress', event => {
        if (!current() || uploadState?.stage !== 'upload') return;
        uploadState.loaded = event.loaded;
        uploadState.total = event.lengthComputable ? event.total : 0;
        renderUploadProgress();
      });
      request.upload.addEventListener('load', () => {
        if (!current() || !uploadState) return;
        uploadState = {stage: 'saving', startedAt: Date.now()};
        renderUploadProgress();
      });
      request.addEventListener('load', () => {
        if (!current()) { reject(new Error('请求已取消')); return; }
        const result = request.response;
        if (request.status === 401) {
          handleSessionExpired();
          reject(new ApiRequestError(result?.message || '登录已失效，请重新登录', 401, result));
        } else if (request.status < 200 || request.status >= 300) {
          reject(new ApiRequestError(result?.message || (request.status === 403
            ? '当前账号没有上传模板的权限' : '图集保存失败，请重试'), request.status, result));
        } else if (!result?.data?.id || !Array.isArray(result.data.images)) {
          reject(new Error('未收到完整的保存结果，请重试确认'));
        } else resolve(result);
      });
      request.addEventListener('error', () => reject(new Error('连接中断，保存结果未确认，请检查网络后重试')));
      request.addEventListener('timeout', () => reject(new Error('等待保存结果超时，请重试确认')));
      request.addEventListener('abort', () => reject(new Error('上传已取消')));
      request.send(data);
    });
  }
  function renderUploads() {
    document.querySelector('#main-style-upload-count').textContent = `已选 ${files.length} 张`;
    save.disabled = busy || !files.length;
    uploadList.innerHTML = files.map((item, index) => `<figure><img src="${esc(item.url)}" alt="${esc(item.file.name)}"><figcaption><span>${index + 1}. ${esc(item.file.name)}</span><div>${button('up', '前移', '←', `data-index="${index}" ${index === 0 || busy ? 'disabled' : ''}`)}${button('down', '后移', '→', `data-index="${index}" ${index === files.length - 1 || busy ? 'disabled' : ''}`)}${button('remove', '移除图片', '×', `data-index="${index}" ${busy ? 'disabled' : ''}`)}</div></figcaption></figure>`).join('');
  }
  function openCreate() {
    if (!canEdit() || busy) return;
    clearFiles(); clearUploadProgress(); form.reset(); error.textContent = ''; renderUploads(); dialog.showModal();
    document.querySelector('#main-style-name').focus();
  }
  document.querySelector('#main-style-new').addEventListener('click', openCreate);
  document.querySelector('#main-style-close').addEventListener('click', () => { if (!busy) dialog.close(); });
  dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  dialog.addEventListener('close', () => { if (!busy) clearFiles(); });
  document.querySelector('#main-style-refresh').addEventListener('click', () => refresh());
  document.querySelector('#main-style-name').addEventListener('input', () => { requestKey = ''; });
  fileInput.addEventListener('change', () => {
    error.textContent = '';
    const incoming = [...fileInput.files];
    if (files.length + incoming.length > 9) { error.textContent = '每套模板最多9张图片'; fileInput.value = ''; return; }
    if (incoming.some(file => !['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > 8 * 1024 * 1024 || !file.size)) {
      error.textContent = '请选择8MB以内的PNG、JPEG或WEBP图片'; fileInput.value = ''; return;
    }
    files.push(...incoming.map(file => ({file, url: URL.createObjectURL(file)})));
    requestKey = ''; fileInput.value = ''; renderUploads();
  });
  uploadList.addEventListener('click', event => {
    const control = event.target.closest('[data-style-action]');
    if (!control || busy) return;
    const index = Number(control.dataset.index);
    if (control.dataset.styleAction === 'remove') URL.revokeObjectURL(files.splice(index, 1)[0].url);
    else {
      const next = index + (control.dataset.styleAction === 'up' ? -1 : 1);
      if (next < 0 || next >= files.length) return;
      [files[index], files[next]] = [files[next], files[index]];
    }
    requestKey = ''; renderUploads();
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !files.length || !form.reportValidity()) return;
    const org = currentUser?.organizationId, ticket = generation;
    const data = new FormData();
    data.set('name', document.querySelector('#main-style-name').value.trim());
    files.forEach(item => data.append('files', item.file));
    requestKey ||= crypto.randomUUID();
    busy = true; error.textContent = '';
    uploadState = {stage: 'upload', loaded: 0, total: 0, startedAt: Date.now()};
    renderUploadProgress(); uploadTimer = setInterval(renderUploadProgress, 1000);
    form.querySelectorAll('input, button').forEach(node => { node.disabled = true; });
    try {
      const response = await uploadTemplate(data, ticket, org);
      if (ticket !== generation || currentUser?.organizationId !== org) return;
      selectedId = response.data.id; detailSignature = '';
      rows = [response.data, ...rows.filter(row => row.id !== selectedId)];
      busy = false; dialog.close(); render(); void refresh({quiet: true});
    } catch (failure) { if (ticket === generation) error.textContent = failure.message; }
    finally {
      if (ticket === generation) {
        uploadRequest = null; clearUploadProgress();
        busy = false; save.textContent = '保存并自动提取';
        form.querySelectorAll('input, button').forEach(node => { node.disabled = false; });
        renderUploads();
      }
    }
  });
  list.addEventListener('click', event => {
    const control = event.target.closest('[data-style-id]');
    if (control) { selectedId = control.dataset.styleId; detailSignature = ''; render(); }
    if (event.target.closest('[data-style-action="new"]')) openCreate();
  });
  detail.addEventListener('input', event => {
    if (event.target.matches('[data-style-prompt]')) promptDrafts.set(event.target.closest('[data-image-id]').dataset.imageId, event.target.value);
  });
  detail.addEventListener('click', async event => {
    const control = event.target.closest('[data-style-action]');
    if (!control) return;
    const action = control.dataset.styleAction, id = selectedId, ticket = generation;
    const imageRow = control.closest('[data-image-id]');
    const imageStatus = imageRow?.querySelector('[data-image-status]');
    if (action === 'back') { selectedId = ''; detailSignature = ''; render(); return; }
    control.disabled = true;
    try {
      if (action === 'copy') {
        await navigator.clipboard.writeText(imageRow.querySelector('textarea').value);
        imageStatus.textContent = '已复制';
      } else if (action === 'save-prompt') {
        const submitted = imageRow.querySelector('textarea').value;
        const response = await api(`${endpoint}/${encodeURIComponent(id)}/images/${encodeURIComponent(imageRow.dataset.imageId)}`,
          {method: 'PATCH', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({prompt: submitted})});
        if (ticket !== generation) return;
        const changed = (promptDrafts.get(imageRow.dataset.imageId) ?? submitted) !== submitted;
        if (!changed) promptDrafts.delete(imageRow.dataset.imageId);
        rows = rows.map(row => row.id === id ? response.data : row);
        detailSignature = JSON.stringify(response.data); imageStatus.textContent = changed ? '已保存，仍有未保存修改' : '已保存';
      } else if (action === 'export') {
        const row = rows.find(row => row.id === id);
        const url = URL.createObjectURL(new Blob([JSON.stringify(row, null, 2)], {type: 'application/json'}));
        const link = document.createElement('a'); link.href = url; link.download = `${row.name.replace(/[\\/:*?"<>|]/g, '_')}-提示词.json`; link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      } else if (action === 'archive') {
        if (!window.confirm('归档此模板？尚未完成的关联上传将停止生成。')) return;
        await api(`${endpoint}/${encodeURIComponent(id)}`, {method: 'DELETE'});
        if (ticket !== generation) return;
        selectedId = ''; detailSignature = ''; await refresh();
      } else if (action === 'retry') {
        await api(`${endpoint}/${encodeURIComponent(id)}/retry`, {method: 'POST'});
        if (ticket === generation) await refresh();
      }
    } catch (failure) { if (ticket === generation) { if (imageStatus) imageStatus.textContent = failure.message; else notify(failure.message); } }
    finally { control.disabled = false; }
  });

  function bulkPreview() {
    const preview = document.querySelector('#product-bulk-main-style-preview');
    const row = rows.find(row => row.id === select.value);
    preview.hidden = !row;
    preview.innerHTML = row ? row.images.map(image => `<img src="${esc(image.imageUrl)}" alt="${esc(row.name)}参考图" loading="lazy">`).join('') : '';
    const count = productBulkItems.length || document.querySelectorAll('[data-bulk-item]').length
      * (typeof productBulkShopIds === 'function' ? productBulkShopIds().length : 1);
    const preserved = row?.images.filter(preservesOriginal).length || 0;
    bulkStatus.textContent = row ? `${row.imageCount} 张 / 商品店铺任务${preserved ? `（生成 ${row.imageCount - preserved} 张，原样保留 ${preserved} 张）` : ''} · 共 ${row.imageCount * count} 张风格主图`
      : select.value ? '保留原任务的风格模板' : '额外风格主图：0 张';
  }
  async function loadBulk(items, {org, view}) {
    const revision = ++bulkRevision;
    const item = items.find(ProductBulkUpload.canChangeShop) || items[0];
    const selected = item?.payload?.mainImageStyleTemplateId || item?.mainImageStyleTemplateId || '';
    select.replaceChildren(new Option('不使用风格模板', ''));
    if (selected) select.add(new Option('原任务风格模板', selected));
    select.value = selected; bulkPreview();
    try {
      const response = await api(endpoint);
      if (revision !== bulkRevision || currentUser?.organizationId !== org || view !== productBulkViewGeneration) return;
      rows = response.data;
      const value = select.value;
      select.replaceChildren(new Option('不使用风格模板', ''));
      rows.forEach(row => {
        const option = new Option(`${row.name} · ${row.imageCount} 张${row.status === 'READY' ? '' : ` · ${labels[row.status]}`}`, row.id);
        option.disabled = row.status !== 'READY'; select.add(option);
      });
      if (value && ![...select.options].some(option => option.value === value)) select.add(new Option('原任务风格模板（已归档）', value));
      select.value = value; bulkPreview();
    } catch (failure) {
      if (revision === bulkRevision && currentUser?.organizationId === org) bulkStatus.textContent = `模板读取失败：${failure.message}`;
    }
  }
  select.addEventListener('change', () => {
    if (productBulkBusy || currentUser?.organizationId !== productBulkOrganization) return;
    const previous = productBulkItems.find(ProductBulkUpload.canChangeShop) || productBulkItems[0];
    try {
      if (productBulkItems.length) {
        const next = ProductBulkUpload.changeStyle(productBulkItems, select.value, newProductIdempotencyKey());
        saveProductBulkItems(next); stopProductBulkPolling(); productBulkItems = next;
        renderProductBulkProgress(); void pollProductBulkProgress();
      }
      bulkPreview();
    } catch (failure) {
      select.value = previous?.payload?.mainImageStyleTemplateId || previous?.mainImageStyleTemplateId || '';
      bulkStatus.textContent = failure.message;
    }
  });
  window.ProductMainImageStyles = {loadBulk, syncBulkLock: disabled => { select.disabled = disabled || !canEdit(); bulkPreview(); }};
  window.addEventListener('heyi:commerce-section-entered', event => {
    if (event.detail.route === 'product-main-image-styles') void refresh();
    else clearTimeout(timer);
  });
  window.addEventListener('heyi:company-reset', () => {
    generation++; bulkRevision++; clearTimeout(timer);
    uploadRequest?.abort(); uploadRequest = null; clearUploadProgress();
    clearFiles(); promptDrafts.clear(); rows = []; selectedId = ''; detailSignature = ''; busy = false;
    if (dialog.open) dialog.close();
    form.querySelectorAll('input, button').forEach(node => { node.disabled = false; });
    save.textContent = '保存并自动提取'; error.textContent = ''; notify(''); renderUploads(); render();
    select.replaceChildren(new Option('不使用风格模板', '')); bulkPreview();
  });
  if (!root.hidden && currentUser?.organizationId) void refresh();
})();
