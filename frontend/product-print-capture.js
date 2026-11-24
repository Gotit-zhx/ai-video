(() => {
  'use strict';
  const root = document.querySelector('#print-capture-workspace');
  if (!root) return;
  const get = name => root.querySelector(`#pc-${name}`);
  const route = 'product-print-capture';
  const endpoint = '/api/v1/product-print-capture';
  const canvas = get('canvas');
  const printBox = get('print-box');
  let drag = null;
  let preparation = null;
  const positionDefaults = {size: 26, x: 50, y: 43};
  const attempts = new Map();
  const selectedIds = new Set();
  const results = new Map();
  const failures = new Map();
  const rejectionSuffix = '；同一请求不会自动重试生图';
  const requests = new Set();
  let revision = 0;
  let imageRevision = 0;
  let capture = null;
  let activeResult = null;
  let printRevision = 0;
  let templatesLoading = false;
  let historyReady = false;
  let print = null;
  let base = null;
  let templates = [];
  let busy = false;
  let handoffId = null;
  let recent = [];
  let loadingRecent = false;

  function status(name, message = '', error = false) {
    const element = get(name);
    element.textContent = message;
    element.hidden = !message;
    element.toggleAttribute('data-error', error);
  }

  const blocksExtraction = result => result.status !== 'PREPARED';
  const completedSource = id => [...results.values()].some(result => result.imageId === id && blocksExtraction(result));
  const selectable = id => !completedSource(id) && !failures.get(id)?.uncertain && failures.get(id)?.status !== 'REJECTED';

  function updateActions() {
    const count = [...selectedIds].filter(selectable).length;
    get('source-images').querySelectorAll('input').forEach(input => {
      const saved = [...results.values()].find(result => result.imageId === input.value && result.imageUrl);
      input.disabled = !saved && (busy || !historyReady || !selectable(input.value));
      input.checked = selectedIds.has(input.value) || Boolean(saved && saved.id === activeResult);
      input.closest('label').toggleAttribute('data-extracted', Boolean(saved));
    });
    get('recent').querySelectorAll('button').forEach(button => { button.disabled = busy; });
    get('refresh-results').disabled = busy || !capture;
    get('confirm-model').disabled = busy || !count || !historyReady;
    get('extract').disabled = busy || !count || !historyReady || !get('confirm-model').checked;
    get('extract').textContent = count ? `提取 ${count} 张` : '提取印花';
  }

  async function request(path, options = {}) {
    const controller = new AbortController();
    requests.add(controller);
    try {
      const result = await api(path, {...options, signal: controller.signal});
      if (!result?.success || !result.data) throw new Error(result?.message || '服务返回的数据不完整，请重试');
      return result.data;
    } finally {
      requests.delete(controller);
    }
  }

  function imageUrl(value, sameOrigin = false) {
    if (typeof value !== 'string' || !value) return '';
    try {
      const url = new URL(value, location.origin);
      if (url.origin === location.origin) return url.href;
      if (!sameOrigin && url.protocol === 'https:' && !url.username && !url.password) return url.href;
    } catch { /* A malformed URL cannot be displayed. */ }
    return '';
  }

  async function loadImage(value) {
    const src = imageUrl(value, true);
    if (!src) throw new Error('图片地址无效');
    const image = new Image();
    image.src = src;
    await image.decode();
    return image;
  }

  function renderRecent() {
    const query = get('search').value.trim().toLowerCase();
    const rows = recent.filter(item => (item.title || '').toLowerCase().includes(query));
    const list = get('recent');
    list.replaceChildren();
    for (const item of rows) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'quiet-button';
      row.setAttribute('aria-current', String(capture?.captureId === item.id));
      const title = document.createElement('span');
      title.textContent = item.title;
      const platform = document.createElement('small');
      platform.textContent = item.sourcePlatform === 'SHOPEE' ? 'Shopee' : 'TikTok Shop';
      row.append(title, platform);
      row.addEventListener('click', () => {
        handoffId = item.id;
        window.history.replaceState(null, '', `#${route}?capture=${encodeURIComponent(item.id)}`);
        void readSource(item.id);
      });
      list.append(row);
    }
    updateActions();
    status('recent-status', rows.length ? '' : query ? '没有匹配的商品' : '从商品页点击 v2.0 插件采集');
  }

  async function loadRecent() {
    if (loadingRecent) return;
    const run = revision;
    loadingRecent = true;
    get('refresh-recent').disabled = true;
    get('recent').setAttribute('aria-busy', 'true');
    status('recent-status', '正在读取…');
    try {
      const response = await request(endpoint);
      if (run !== revision) return;
      recent = Array.isArray(response) ? response : [];
      get('recent-count').textContent = String(recent.length);
      renderRecent();
    } catch (error) {
      if (run === revision) status('recent-status', error.message, true);
    } finally {
      if (run === revision) {
        loadingRecent = false;
        get('refresh-recent').disabled = false;
        get('recent').setAttribute('aria-busy', 'false');
      }
    }
  }

  function setArrange(open) {
    get('placement').hidden = !open;
    get('arrange').setAttribute('aria-expanded', String(open));
  }

  function resetComposition() {
    setArrange(false);
    get('arrange').disabled = true;
    get('monitor-layout').textContent = '待套版';
    get('download').disabled = true;
    get('produce').disabled = true;
    get('adjust-hint').hidden = printBox.hidden = true;
    drag = null;
    status('production-status');
  }

  function clearPreview(message) {
    canvas.hidden = true;
    canvas.width = 1;
    canvas.height = 1;
    delete canvas.dataset.preview;
    get('composition-heading').textContent = '套版预览';
    resetComposition();
    status('preview-state', message);
  }

  function showArtworkPreview() {
    if (!print) return;
    canvas.width = print.width;
    canvas.height = print.height;
    canvas.getContext('2d').drawImage(print.bitmap, print.left, print.top, print.width, print.height,
      0, 0, canvas.width, canvas.height);
    canvas.dataset.preview = 'artwork';
    canvas.setAttribute('aria-label', '已提取的透明印花');
    canvas.hidden = false;
    get('composition-heading').textContent = '印花预览';
    resetComposition();
    status('preview-state');
  }

  function clearSource() {
    imageRevision++;
    printRevision++;
    capture = activeResult = print = base = null;
    results.clear();
    failures.clear();
    selectedIds.clear();
    templates = [];
    templatesLoading = historyReady = false;
    get('photo-count').textContent = '0';
    for (const name of ['source-images', 'style', 'results']) get(name).replaceChildren();
    for (const name of ['source-result', 'source-section', 'print-section', 'style-controls', 'template-link', 'result-section']) {
      get(name).hidden = true;
    }
    get('confirm-model').checked = false;
    get('monitor-print').textContent = '待选图';
    status('template-state');
    status('extract-status');
    status('results-status');
    clearPreview('提取印花后，选择款式预览。');
    updateActions();
  }

  function renderSource() {
    get('product-title').textContent = capture.title || '已采集商品';
    get('photo-count').textContent = String(capture.images.length);
    renderRecent();
    get('source-result').hidden = false;
    capture.images.forEach((photo, index) => {
      const label = document.createElement('label');
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.value = photo.id;
      input.setAttribute('aria-label', `商品图 ${index + 1}`);
      input.addEventListener('change', () => {
        const saved = [...results.values()].find(result => result.imageId === photo.id && result.imageUrl);
        if (saved) {
          void showResult(saved);
          return;
        }
        if (input.checked) selectedIds.add(photo.id);
        else selectedIds.delete(photo.id);
        get('confirm-model').checked = false;
        updateActions();
      });
      const surface = document.createElement('span');
      const image = document.createElement('img');
      image.src = photo.src;
      image.alt = '';
      image.referrerPolicy = 'no-referrer';
      surface.append(image);
      label.append(input, surface);
      get('source-images').append(label);
    });
    get('print-section').hidden = false;
    updateActions();
  }

  function resultLabel(result) {
    const index = capture.images.findIndex(photo => photo.id === result.imageId);
    return index < 0 ? '已采集商品图' : `商品图 ${index + 1}`;
  }

  function renderResults() {
    const gallery = get('results');
    const focused = gallery.contains(document.activeElement) ? document.activeElement.dataset.result : null;
    gallery.replaceChildren();
    const pendingEntries = [...failures].filter(([imageId]) => !completedSource(imageId))
      .map(([imageId, failure]) => ({imageId, ...failure}));
    const entries = [...results.values()].filter(blocksExtraction).concat(pendingEntries);
    for (const result of entries) {
      const item = document.createElement('div');
      item.className = 'pc-result';
      const label = resultLabel(result);
      const url = imageUrl(result.imageUrl, true);
      if (url) {
        const control = document.createElement('button');
        control.type = 'button';
        control.className = 'quiet-button';
        control.dataset.result = result.id;
        control.setAttribute('aria-pressed', String(result.id === activeResult));
        const image = document.createElement('img');
        image.src = url;
        image.alt = '';
        control.append(image, document.createTextNode(label));
        control.onclick = () => void showResult(result);
        item.append(control);
        if (focused === result.id) queueMicrotask(() => control.isConnected && control.focus());
      } else {
        const title = document.createElement('strong');
        title.textContent = label;
        item.append(title);
      }
      if (['RESULT_RECEIVED','ARCHIVE_PENDING'].includes(result.status)) {
        const archive = document.createElement('button');
        archive.className = 'quiet-button';
        archive.type = 'button';
        archive.disabled = busy;
        archive.textContent = '重试归档';
        archive.onclick = () => void archiveResult(result);
        item.append(archive);
      }
      const message = result.message || ({
        UNKNOWN: '结果待核查',
        SUBMITTING: '提取中',
        REJECTED: '提取已拒绝',
        ARCHIVE_PENDING: '待归档',
        RESULT_RECEIVED: '待归档',
        TRANSPARENCY_REJECTED: '未通过透明 PNG 检查',
        READY: url ? '' : '图片暂不可用'
      }[result.status]);
      if (message) {
        const note = document.createElement('small');
        note.textContent = message;
        item.append(note);
      }
      gallery.append(item);
    }
    if (!busy) {
      const available = entries.filter(result => result.imageUrl).length;
      get('monitor-print').textContent = available ? `可用 ${available} 张` : entries.length ? '查看结果' : '待提取';
    }
  }

  async function showResult(result) {
    if (!result?.imageUrl) return;
    const run = ++printRevision;
    activeResult = result.id;
    print = null;
    renderResults(); updateActions();
    canvas.hidden = true;
    get('download').disabled = get('arrange').disabled = true;
    status('preview-state', '正在读取印花…');
    try {
      const image = await loadImage(result.imageUrl);
      if (run !== printRevision) return;
      print = cropArtwork(image);
      if (base) showComposition();
      else showArtworkPreview();
      if (!templates.length) void loadTemplates();
    } catch (error) {
      if (run !== printRevision) return;
      print = null;
      status('preview-state', error.message || '印花读取失败，请重试。', true);
    }
  }

  async function refreshResults() {
    const source = capture;
    if (!source) return;
    get('result-section').hidden = false;
    status('results-status', '正在读取结果…');
    try {
      const data = await request(`${endpoint}/${encodeURIComponent(source.captureId)}/results`);
      if (capture !== source) return;
      if (!Array.isArray(data)) throw new Error('提取结果读取不完整');
      for (const result of data) {
        if (result.id && result.imageId) {
          const previous = results.get(result.id);
          results.set(result.id, {...result, imageUrl: result.imageUrl || (
            result.status === 'RESULT_RECEIVED' ? previous?.imageUrl : null)});
        }
        if (blocksExtraction(result)) selectedIds.delete(result.imageId);
      }
      historyReady = true;
      for (const [imageId, failure] of failures) {
        if (completedSource(imageId)) failures.delete(imageId);
        else if (failure.uncertain) failure.uncertain = false;
      }
      status('results-status');
      renderResults();
      if (!activeResult) void showResult([...results.values()].find(result => result.imageUrl));
      get('result-section').hidden = !results.size && !failures.size;
    } catch (error) {
      if (capture !== source) return;
      historyReady = false;
      status('results-status', error.message || '提取历史读取失败，请刷新结果。', true);
    } finally {
      if (capture === source) updateActions();
    }
  }

  async function archiveResult(result) {
    if (busy) return;
    const source = capture;
    busy = true;
    updateActions(); renderResults();
    try {
      const data = await request(`${endpoint}/results/${encodeURIComponent(result.id)}/archive`, {method: 'POST'});
      if (capture !== source) return;
      results.set(result.id, {...data, imageId: result.imageId});
      status('results-status', data.status === 'READY' ? '已归档' : '印花待归档，稍后可重试。');
      if (data.imageUrl && (result.id === activeResult || !activeResult)) void showResult(data);
    } catch (error) {
      if (capture === source) {
        await refreshResults();
        if (capture === source) status('results-status', error.message, true);
      }
    } finally {
      if (capture === source) {
        busy = false;
        updateActions(); renderResults();
      }
    }
  }

  async function readSource(id) {
    if (busy) return;
    clearSource();
    const run = revision;
    busy = true;
    updateActions();
    get('source-section').hidden = false;
    status('source-status', '正在读取商品图…');
    try {
      const data = await request(`${endpoint}/${encodeURIComponent(id)}`);
      if (run !== revision) return;
      const images = (data.images || []).map(photo => ({...photo, src: imageUrl(photo.url)})).filter(photo => photo.id && photo.src);
      if (!images.length) {
        status('source-status', '该记录没有可用图片，请用 v2.0 插件重新采集。', true);
        return;
      }
      capture = {...data, images};
      renderSource();
      status('source-status');
      await refreshResults();
    } catch (error) {
      if (run !== revision) return;
      const message = error.status === 404
        ? '找不到采集记录，请核对插件和网页的登录企业。'
        : error.message;
      status('source-status', message, true);
    } finally {
      if (run === revision) {
        busy = false;
        if (capture) renderResults();
        updateActions();
      }
    }
  }

  get('confirm-model').addEventListener('change', updateActions);
  get('refresh-results').addEventListener('click', async () => {
    if (busy || !capture) return;
    const source = capture;
    busy = true;
    renderResults(); updateActions();
    try { await refreshResults(); }
    finally {
      if (source === capture) {
        busy = false;
        renderResults(); updateActions();
      }
    }
  });
  function extractionKey(imageId) {
    const entry = ['heyi:print-extraction', currentUser?.organizationId, currentUser?.userId,
      capture.captureId, imageId].join(':');
    if (attempts.has(entry)) return attempts.get(entry);
    let key;
    try { key = sessionStorage.getItem(entry); } catch { /* Storage may be disabled. */ }
    if (!key) {
      const stable = `v2-${capture.captureId}-${imageId}`;
      key = /^[A-Za-z0-9_-]{8,80}$/.test(stable) ? stable : crypto.randomUUID();
      try { sessionStorage.setItem(entry, key); } catch { /* The active page still remembers the key. */ }
    }
    attempts.set(entry, key);
    return key;
  }

  get('extract').addEventListener('click', async () => {
    if (busy || !capture || !historyReady || !get('confirm-model').checked) return;
    const batch = [...selectedIds].filter(selectable);
    if (!batch.length) return;
    const source = capture;
    busy = true;
    get('confirm-model').checked = false;
    get('result-section').hidden = false;
    get('monitor-print').textContent = '提取中';
    renderResults(); updateActions();
    try {
      for (const [index, imageId] of batch.entries()) {
        status('extract-status', `正在处理 ${index + 1} / ${batch.length} 张…`);
        try {
          const data = await request(`${endpoint}/${encodeURIComponent(source.captureId)}/extract`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'Idempotency-Key': extractionKey(imageId)},
            body: JSON.stringify({imageId, confirmed: true})
          });
          if (capture !== source) return;
          if (!data.id) throw new Error('提取结果读取不完整');
          results.set(data.id, {...data, imageId});
          failures.delete(imageId);
          if (!activeResult && data.imageUrl) void showResult(data);
        } catch (error) {
          if (capture !== source) return;
          const rejected = error.status === 409 && error.message.endsWith(rejectionSuffix);
          failures.set(imageId, {
            uncertain: !rejected,
            status: rejected ? 'REJECTED' : 'UNKNOWN',
            message: rejected ? error.message.slice(0, -rejectionSuffix.length) : error.message
          });
        }
        selectedIds.delete(imageId);
        renderResults(); updateActions();
      }
      await refreshResults();
      if (capture === source) status('extract-status');
    } finally {
      if (capture === source) {
        busy = false;
        renderResults(); updateActions();
      }
    }
  });

  async function readTemplatePages(source, readyOnly) {
    const items = [];
    let offset = 0;
    for (;;) {
      const page = await request(`/api/v1/product-capture-templates?readyOnly=${readyOnly}&offset=${offset}&limit=100`);
      if (source !== capture || !page.items?.length) break;
      items.push(...page.items);
      offset += page.items.length;
      if (offset >= page.total) break;
    }
    return items;
  }

  async function loadTemplates() {
    if (templatesLoading) return;
    templatesLoading = true;
    const source = capture;
    const select = get('style');
    get('style-controls').hidden = false;
    select.disabled = true;
    status('template-state', '正在读取款式…');
    get('template-link').hidden = true;
    try {
      const [ready, editable] = await Promise.all([
        readTemplatePages(source, true),
        readTemplatePages(source, false)
      ]);
      if (source !== capture) return;
      const previewable = new Map();
      const current = new Map(editable.map(item => [item.id, item]));
      for (const item of [...ready, ...editable]) {
        const variant = item.variants?.[0];
        if (!previewable.has(item.id) && imageUrl(variant?.image, true)) {
          const latest = current.get(item.id) || item;
          previewable.set(item.id, {...item, version: latest.version,
            variants: [{...variant, colors: latest.variants?.[0]?.colors || [],
              sizes: latest.variants?.[0]?.sizes || []}]});
        }
      }
      templates = [...previewable.values()];
      const previous = select.value;
      select.replaceChildren(new Option('选择款式', ''));
      for (const item of templates) {
        const variant = item.variants[0];
        const code = variant.styleCode ? ` · ${variant.styleCode}` : '';
        const label = item.name || variant.name || '未命名款式';
        select.add(new Option(`${label}${code}${item.draft ? ' · 草稿' : ''}`, item.id));
      }
      if (previewable.has(previous)) select.value = previous;
      else if (previous) {
        imageRevision++;
        base = null;
        showArtworkPreview();
      }
      get('template-link').hidden = Boolean(templates.length);
      status('template-state', templates.length ? '' : '上传款式底图后即可套版。');
    } catch {
      if (source === capture) status('template-state', '款式读取失败，重新打开此页后再试。', true);
    } finally {
      if (source === capture) {
        select.disabled = !templates.length;
        templatesLoading = false;
      }
    }
  }

  // Trim transparent margins before scaling so two prints with different padding have the same visual size.
  function cropArtwork(image) {
    const scale = Math.min(1, 2048 / Math.max(image.naturalWidth, image.naturalHeight));
    const bitmap = document.createElement('canvas');
    bitmap.width = Math.max(1, Math.round(image.naturalWidth * scale));
    bitmap.height = Math.max(1, Math.round(image.naturalHeight * scale));
    const context = bitmap.getContext('2d', {willReadFrequently: true});
    context.drawImage(image, 0, 0, bitmap.width, bitmap.height);
    const {data} = context.getImageData(0, 0, bitmap.width, bitmap.height);
    let left = bitmap.width, right = 0, top = bitmap.height, bottom = 0;
    for (let y = 0; y < bitmap.height; y++) {
      for (let x = 0; x < bitmap.width; x++) {
        if (data[(y * bitmap.width + x) * 4 + 3] <= 8) continue;
        left = Math.min(left, x);
        right = Math.max(right, x);
        top = Math.min(top, y);
        bottom = Math.max(bottom, y);
      }
    }
    if (right < left || bottom < top) throw new Error('提取结果没有可见印花，请检查原商品图。');
    return {bitmap, left, top, width: right - left + 1, height: bottom - top + 1};
  }

  function placement() {
    const width = Math.min(canvas.width * Number(get('size').value) / 100, canvas.height * print.width / print.height);
    const height = width * print.height / print.width;
    return {
      width, height,
      x: (canvas.width - width) * Number(get('x').value) / 100,
      y: (canvas.height - height) * Number(get('y').value) / 100
    };
  }

  function draw() {
    if (!base || !print) return;
    const context = canvas.getContext('2d');
    const {x, y, width, height} = placement();
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(base, 0, 0, canvas.width, canvas.height);
    context.drawImage(print.bitmap, print.left, print.top, print.width, print.height, x, y, width, height);
    Object.assign(printBox.style, {
      left: `${x / canvas.width * 100}%`, top: `${y / canvas.height * 100}%`,
      width: `${width / canvas.width * 100}%`, height: `${height / canvas.height * 100}%`
    });
  }

  function relativePoint(event) {
    const rect = canvas.getBoundingClientRect();
    return {x: (event.clientX - rect.left) * canvas.width / rect.width,
      y: (event.clientY - rect.top) * canvas.height / rect.height};
  }

  function placeAt(x, y, width, height) {
    const percent = (coordinate, space) => space <= 0 ? 50 : Math.max(0, Math.min(100, 100 * coordinate / space));
    get('x').value = percent(x, canvas.width - width);
    get('y').value = percent(y, canvas.height - height);
  }

  printBox.addEventListener('pointerdown', event => {
    if (event.button !== 0 || drag) return;
    const point = relativePoint(event);
    drag = {pointer: event.pointerId, point, start: placement(), resize: event.target === get('print-resize')};
    printBox.setPointerCapture(event.pointerId);
    event.preventDefault();
  });

  printBox.addEventListener('pointermove', event => {
    if (event.pointerId !== drag?.pointer) return;
    const point = relativePoint(event);
    const dx = point.x - drag.point.x;
    const dy = point.y - drag.point.y;
    const start = drag.start;
    if (drag.resize) {
      const aspect = print.width / print.height;
      const change = (dx + dy / aspect) / (1 + 1 / (aspect * aspect));
      const width = Math.min(start.width + change, canvas.width - start.x, (canvas.height - start.y) * aspect);
      get('size').value = Math.max(5, Math.min(85, Math.round(100 * width / canvas.width)));
      const next = placement();
      placeAt(start.x, start.y, next.width, next.height);
    } else {
      placeAt(start.x + dx, start.y + dy, start.width, start.height);
    }
    updatePosition();
  });
  printBox.addEventListener('lostpointercapture', () => { drag = null; });

  function updatePosition(reset = false) {
    for (const [name, value] of Object.entries(positionDefaults)) {
      if (reset) get(name).value = value;
      get(`${name}-value`).textContent = `${get(name).value}%`;
    }
    draw();
  }

  function showComposition() {
    if (!base || !print) return;
    const scale = Math.min(1, 2048 / Math.max(base.naturalWidth, base.naturalHeight));
    canvas.width = Math.max(1, Math.round(base.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(base.naturalHeight * scale));
    updatePosition();
    canvas.dataset.preview = 'composition';
    canvas.setAttribute('aria-label', '印花套版预览');
    get('composition-heading').textContent = '套版预览';
    status('preview-state');
    canvas.hidden = false;
    printBox.hidden = get('adjust-hint').hidden = false;
    get('produce').disabled = Boolean(preparation);
    status('production-status');
    get('arrange').disabled = false;
    get('monitor-layout').textContent = '可排版';
    get('download').disabled = false;
  }

  get('style').addEventListener('change', async event => {
    const run = ++imageRevision;
    const style = templates.find(item => item.id === event.target.value)?.variants[0];
    base = null;
    showArtworkPreview();
    status('template-state', style ? '正在加载款式底图…' : '');
    if (!style) return;
    try {
      const image = await loadImage(style.image);
      if (run !== imageRevision) return;
      base = image;
      status('template-state');
      updatePosition(true);
      showComposition();
    } catch {
      if (run === imageRevision) status('template-state', '款式底图读取失败，请重新选择款式。', true);
    }
  });

  get('recent-toggle').addEventListener('click', event => {
    const panel = get('recent-panel');
    panel.hidden = !panel.hidden;
    event.currentTarget.setAttribute('aria-expanded', String(!panel.hidden));
  });
  get('search').addEventListener('input', renderRecent);
  get('refresh-recent').addEventListener('click', loadRecent);
  get('monitor-toggle').addEventListener('click', () => {
    const monitor = get('monitor');
    monitor.hidden = !monitor.hidden;
    get('monitor-toggle').setAttribute('aria-expanded', String(!monitor.hidden));
  });
  get('layout').addEventListener('click', () => {
    const stacked = get('editor').dataset.layout !== 'stacked';
    get('editor').dataset.layout = stacked ? 'stacked' : 'split';
    get('layout').setAttribute('aria-pressed', String(stacked));
    get('layout').textContent = stacked ? '布局 · 上下' : '布局 · 左右';
    get('layout').title = stacked ? '切换左右布局' : '切换上下布局';
  });
  get('arrange').addEventListener('click', () => {
    const open = get('placement').hidden;
    setArrange(open);
    if (open) get('size').focus();
  });

  for (const name of Object.keys(positionDefaults)) get(name).addEventListener('input', () => updatePosition());
  get('reset-position').addEventListener('click', () => updatePosition(true));
  get('produce').addEventListener('click', async () => {
    if (preparation || !capture || !activeResult || canvas.dataset.preview !== 'composition'
        || !hasAnyPermission('product:publish')) return;
    const template = templates.find(item => item.id === get('style').value);
    if (!template) return;
    const pending = {captureId: capture.captureId, resultId: activeResult, templateId: template.id,
      templateVersion: template.version, org: currentUser?.organizationId};
    const current = () => preparation === pending && currentUser?.organizationId === pending.org
      && capture?.captureId === pending.captureId && activeResult === pending.resultId
      && get('style').value === pending.templateId && location.hash.split('?')[0] === '#product-print-capture'
      && canvas.dataset.preview === 'composition';
    preparation = pending;
    get('produce').disabled = true;
    status('production-status', '正在读取采集规格…');
    try {
      const png = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
      if (!current()) return;
      if (!png || png.size > 8 * 1024 * 1024) throw new Error('套版图导出失败或大于 8 MiB，请调整后重试。');
      const {resultId, templateId, templateVersion} = pending;
      const body = new FormData();
      body.append('settings', new Blob([JSON.stringify({resultId, templateId, templateVersion})], {type: 'application/json'}));
      body.append('image', png, 'composition.png');
      const draft = await request(endpoint + '/' + encodeURIComponent(pending.captureId) + '/production', {
        method: 'POST', body, headers: {'X-Commerce-Module': 'CAPTURE'}
      });
      if (!current()) return;
      await openProductBulkUpload('', {...draft, printProduction: true}, current);
      if (current()) status('production-status');
    } catch (error) {
      if (current()) status('production-status', error.message || '商品准备失败，请重试。', true);
    } finally {
      if (preparation === pending) {
        preparation = null;
        get('produce').disabled = canvas.dataset.preview !== 'composition';
      }
    }
  });

  get('download').addEventListener('click', () => {
    if (canvas.hidden || canvas.dataset.preview !== 'composition') return;
    const selectedStyle = templates.find(item => item.id === get('style').value);
    const code = selectedStyle?.variants[0]?.styleCode;
    const fileName = code && /^[a-z0-9_-]+$/i.test(code) ? `套版-${code}.png` : '套版预览.png';
    const run = revision;
    canvas.toBlob(blob => {
      if (run !== revision) return;
      if (!blob) {
        status('preview-state', '图片导出失败，请重试。', true);
        return;
      }
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }, 'image/png');
  });

  function enter() {
    const allowed = hasAnyPermission('product:edit');
    get('editor').hidden = !allowed;
    get('monitor-toggle').hidden = get('layout').hidden = !allowed;
    get('monitor').hidden = !allowed || get('monitor-toggle').getAttribute('aria-expanded') === 'false';
    get('no-access').hidden = allowed;
    const canPublish = hasAnyPermission('product:publish');
    get('produce').hidden = !canPublish;
    get('task-list').hidden = !allowed || !canPublish;
    if (!allowed) return;
    void loadRecent();
    const id = new URLSearchParams(location.hash.split('?')[1] || '').get('capture');
    if (id && /^[a-f0-9-]{36}$/i.test(id) && id !== handoffId && !busy) {
      handoffId = id;
      void readSource(id);
    } else if (print && !busy) {
      void loadTemplates();
    }
  }

  window.addEventListener('heyi:commerce-section-entered', event => {
    if (event.detail.route === route) enter();
  });
  window.addEventListener('heyi:company-reset', () => {
    revision++;
    preparation = null;
    for (const request of requests) request.abort();
    attempts.clear();
    handoffId = null;
    recent = [];
    loadingRecent = false;
    get('refresh-recent').disabled = false;
    get('recent').removeAttribute('aria-busy');
    get('recent').replaceChildren();
    get('recent-count').textContent = '—';
    get('search').value = '';
    status('recent-status');
    busy = false;
    status('source-status');
    clearSource();
  });
  if (!root.hidden) enter();
})();
