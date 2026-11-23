(() => {
  'use strict';
  const root = document.querySelector('#print-capture-workspace');
  if (!root) return;
  const get = name => root.querySelector(`#pc-${name}`);
  const route = 'product-print-capture';
  const endpoint = '/api/v1/product-print-capture';
  const canvas = get('canvas');
  const positionDefaults = {size: 26, x: 50, y: 43};
  const attempts = new Map();
  const requests = new Set();
  let revision = 0;
  let imageRevision = 0;
  let capture = null;
  let selected = null;
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

  function updateActions() {
    get('source-images').querySelectorAll('input').forEach(input => { input.disabled = busy; });
    get('recent').querySelectorAll('button').forEach(button => { button.disabled = busy; });
    get('confirm-model').disabled = busy;
    get('extract').disabled = busy || !selected || !get('confirm-model').checked;
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

  function clearPreview(message) {
    canvas.hidden = true;
    canvas.width = 1;
    canvas.height = 1;
    setArrange(false);
    get('arrange').disabled = true;
    get('monitor-layout').textContent = '待套版';
    get('download').disabled = true;
    status('preview-state', message);
  }

  function clearPrint() {
    imageRevision++;
    print = base = null;
    templates = [];
    get('art').removeAttribute('src');
    get('print-tile').hidden = true;
    get('style-controls').hidden = true;
    get('style').replaceChildren();
    get('template-link').hidden = true;
    status('template-state');
    status('extract-status');
    get('monitor-print').textContent = selected ? '待提取' : '待选图';
    clearPreview('提取印花后，选择款式预览。');
    get('confirm-model').checked = false;
    get('extract').textContent = '提取印花';
    updateActions();
  }

  function selectSource(image) {
    selected = image;
    clearPrint();
  }

  function clearSource() {
    capture = selected = null;
    get('photo-count').textContent = '0';
    get('source-images').replaceChildren();
    get('source-result').hidden = true;
    get('print-section').hidden = true;
    get('source-section').hidden = true;
    clearPrint();
  }

  function renderSource() {
    get('product-title').textContent = capture.title || '已采集商品';
    get('photo-count').textContent = String(capture.images.length);
    renderRecent();
    get('source-result').hidden = false;
    capture.images.forEach((photo, index) => {
      const label = document.createElement('label');
      const input = document.createElement('input');
      input.type = 'radio';
      input.name = 'pc-source-image';
      input.checked = index === 0;
      input.setAttribute('aria-label', `商品图 ${index + 1}`);
      input.addEventListener('change', () => selectSource(photo));
      const surface = document.createElement('span');
      const image = document.createElement('img');
      image.src = photo.src;
      image.alt = `商品图 ${index + 1}`;
      image.referrerPolicy = 'no-referrer';
      surface.append(image);
      label.append(input, surface);
      get('source-images').append(label);
    });
    selectSource(capture.images[0]);
    get('print-section').hidden = false;
    get('extract-action').hidden = false;
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
    } catch (error) {
      if (run !== revision) return;
      const message = error.status === 404
        ? '找不到采集记录，请核对插件和网页的登录企业。'
        : error.message;
      status('source-status', message, true);
    } finally {
      if (run === revision) {
        busy = false;
        updateActions();
      }
    }
  }

  get('confirm-model').addEventListener('change', updateActions);
  function extractionKey() {
    const entry = ['heyi:print-extraction', currentUser?.organizationId, currentUser?.userId,
      capture.captureId, selected.id].join(':');
    if (attempts.has(entry)) return attempts.get(entry);
    let key;
    try { key = sessionStorage.getItem(entry); } catch { /* Storage may be disabled. */ }
    if (!key) {
      key = crypto.randomUUID();
      try { sessionStorage.setItem(entry, key); } catch { /* The active page still remembers the key. */ }
    }
    attempts.set(entry, key);
    return key;
  }

  get('extract').addEventListener('click', async () => {
    if (busy || !capture || !selected || !get('confirm-model').checked) return;
    const key = extractionKey();
    const run = revision;
    busy = true;
    updateActions();
    status('extract-status', '正在提取印花…');
    get('monitor-print').textContent = '提取中';
    try {
      const data = await request(`${endpoint}/${encodeURIComponent(capture.captureId)}/extract`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json', 'Idempotency-Key': key},
        body: JSON.stringify({imageId: selected.id, confirmed: true})
      });
      if (run !== revision) return;
      if (data.status !== 'READY' || !data.imageUrl) {
        get('extract').textContent = '查看提取结果';
        get('monitor-print').textContent = data.status === 'UNKNOWN' ? '待核查' : '等待结果';
        const message = data.status === 'UNKNOWN' ? '提取结果待核查，请勿重复提交。' : '提取结果尚未就绪，可以稍后查看。';
        status('extract-status', data.message || message);
        return;
      }
      const loadedPrint = await loadImage(data.imageUrl);
      if (run !== revision) return;
      print = cropArtwork(loadedPrint);
      get('art').src = loadedPrint.src;
      get('print-tile').hidden = false;
      get('extract-action').hidden = true;
      status('extract-status', '已提取印花');
      get('monitor-print').textContent = '已提取';
      await loadTemplates();
    } catch (error) {
      if (run === revision) {
        get('extract').textContent = '查看提取结果';
        status('extract-status', error.message, true);
        get('monitor-print').textContent = '待查看';
      }
    } finally {
      if (run === revision) {
        busy = false;
        updateActions();
      }
    }
  });

  async function loadTemplates() {
    const run = revision;
    const imageRun = imageRevision;
    const select = get('style');
    get('style-controls').hidden = false;
    select.disabled = true;
    clearPreview('正在读取款式…');
    status('template-state');
    get('template-link').hidden = true;
    try {
      const items = [];
      let offset = 0;
      for (;;) {
        const page = await request(`/api/v1/product-capture-templates?offset=${offset}&limit=100`);
        if (run !== revision || imageRun !== imageRevision) return;
        if (!page.items?.length) break;
        items.push(...page.items);
        offset += page.items.length;
        if (offset >= page.total) break;
      }
      templates = items.filter(item => imageUrl(item.variants?.[0]?.image, true));
      select.replaceChildren(new Option('选择款式', ''));
      for (const item of templates) {
        const variant = item.variants[0];
        const code = variant.styleCode ? ` · ${variant.styleCode}` : '';
        select.add(new Option(`${item.name || variant.name}${code}`, item.id));
      }
      select.disabled = !templates.length;
      get('template-link').hidden = !!templates.length;
      clearPreview(templates.length ? '选择款式后预览。' : '还没有可用款式，请先保存一张款式底图。');
    } catch (error) {
      if (run === revision && imageRun === imageRevision) {
        clearPreview('款式读取失败。返回此页可重新读取。');
        status('template-state', error.message, true);
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

  function draw() {
    if (!base || !print) return;
    const context = canvas.getContext('2d');
    const width = Math.min(canvas.width * Number(get('size').value) / 100, canvas.height * print.width / print.height);
    const height = width * print.height / print.width;
    const x = (canvas.width - width) * Number(get('x').value) / 100;
    const y = (canvas.height - height) * Number(get('y').value) / 100;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(base, 0, 0, canvas.width, canvas.height);
    context.drawImage(print.bitmap, print.left, print.top, print.width, print.height, x, y, width, height);
  }

  function updatePosition(reset = false) {
    for (const [name, value] of Object.entries(positionDefaults)) {
      if (reset) get(name).value = value;
      get(`${name}-value`).textContent = `${get(name).value}%`;
    }
    draw();
  }

  get('style').addEventListener('change', async event => {
    const run = ++imageRevision;
    const style = templates.find(item => item.id === event.target.value)?.variants[0];
    base = null;
    clearPreview(style ? '正在加载款式底图…' : '选择款式后预览。');
    if (!style) return;
    try {
      const image = await loadImage(style.image);
      if (run !== imageRevision) return;
      base = image;
      const scale = Math.min(1, 2048 / Math.max(image.naturalWidth, image.naturalHeight));
      canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
      canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
      updatePosition(true);
      status('preview-state');
      canvas.hidden = false;
      get('arrange').disabled = false;
      get('monitor-layout').textContent = '可排版';
      get('download').disabled = false;
    } catch {
      if (run === imageRevision) status('preview-state', '款式底图读取失败，请重新选择款式。', true);
    }
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
  get('download').addEventListener('click', () => {
    if (canvas.hidden) return;
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
    if (!allowed) return;
    void loadRecent();
    const id = new URLSearchParams(location.hash.split('?')[1] || '').get('capture');
    if (id && /^[a-f0-9-]{36}$/i.test(id) && id !== handoffId && !busy) {
      handoffId = id;
      void readSource(id);
    } else if (print && !templates.length && !busy) {
      void loadTemplates();
    }
  }

  window.addEventListener('heyi:commerce-section-entered', event => {
    if (event.detail.route === route) enter();
  });
  window.addEventListener('heyi:company-reset', () => {
    revision++;
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
