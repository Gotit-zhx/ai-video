const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function harness() {
  const nodes = new Map(), requests = [], timers = new Map(), intervals = new Map();
  let sequence = 0, rows = [], reads = 0, expired = 0;
  class Node {
    constructor() { this.events = {}; this.hidden = false; this.value = ''; this.textContent = ''; this.innerHTML = ''; this.childElementCount = 1; this.options = []; }
    addEventListener(name, callback) { this.events[name] = callback; }
    fire(name, data = {}) { return this.events[name]?.({preventDefault() {}, ...data}); }
    removeAttribute(name) { delete this[name]; }
    reset() {}
    reportValidity() { return true; }
    showModal() { this.open = true; }
    close() { this.open = false; this.fire('close'); }
    focus() {}
    contains() { return false; }
    querySelectorAll() { return []; }
    replaceChildren(...options) { this.options = options; this.value = options[0]?.value || ''; }
  }
  const get = id => { if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id); };
  get('main-style-workspace').hidden = true;
  class XHR extends Node {
    constructor() { super(); this.upload = new Node(); this.headers = {}; requests.push(this); }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader(name, value) { this.headers[name] = value; }
    send(data) { this.data = data; }
    abort() { this.aborted = true; this.fire('abort'); }
    respond(status, response) { this.status = status; this.response = response; this.fire('load'); }
  }
  const window = new Node();
  const context = {window, document: {querySelector: selector => get(selector.slice(1)), querySelectorAll: () => [], activeElement: null},
    currentUser: {organizationId: 'org'}, XMLHttpRequest: XHR,
    FormData: class {set() {} append() {}}, crypto: {randomUUID: () => `key-${++sequence}`},
    URL: {createObjectURL: () => 'blob:fixture', revokeObjectURL() {}},
    Option: class {constructor(text, value) { this.text = text; this.value = value; }},
    setTimeout: fn => { const id = ++sequence; timers.set(id, fn); return id; }, clearTimeout: id => timers.delete(id),
    setInterval: fn => { const id = ++sequence; intervals.set(id, fn); return id; }, clearInterval: id => intervals.delete(id),
    hasAnyPermission: () => true, escapeHtml: text => text.replaceAll('<', '&lt;'),
    api: async () => { reads++; return {data: rows}; }, commerceDataModule: () => 'CAPTURE',
    ApiRequestError: class extends Error {}, handleSessionExpired: () => { expired++; window.fire('heyi:company-reset'); },
    productBulkItems: [], console};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../product-main-image-styles.js'), 'utf8'), context);
  const flush = async () => { await Promise.resolve(); await Promise.resolve(); };
  return {get, requests, timers, intervals, context, window, expired: () => expired, reads: () => reads,
    rows(value) { rows = value; },
    async refresh() { get('main-style-workspace').hidden = false; await get('main-style-refresh').fire('click'); await flush(); },
    select(id) { get('main-style-list').fire('click', {target: {closest: selector => selector === '[data-style-id]' ? {dataset: {styleId: id}} : null}}); },
    start() {
      get('main-style-new').fire('click');
      get('main-style-name').value = 'Poster';
      get('main-style-files').files = [{name: 'test.png', type: 'image/png', size: 1000}];
      get('main-style-files').fire('change');
      return get('main-style-create-form').fire('submit');
    },
    submit() { return get('main-style-create-form').fire('submit'); }, flush};
}
const row = statuses => ({id: 'style', name: 'Poster', status: statuses.includes('FAILED') ? 'FAILED'
  : statuses.every(s => s === 'READY') ? 'READY' : 'ANALYZING', imageCount: statuses.length,
  images: statuses.map((status, index) => ({id: `image-${index}`, index, status, imageUrl: 'fixture.png', width: 1024, height: 1024,
    analysis: status === 'READY' ? {prompt: '{{product}}'} : {progress: {stage: 'MODEL_STREAMING', outputCharacters: 123, elapsedSeconds: 12}}}))});

test('upload uses real bytes, then a separate indeterminate save stage', async () => {
  const h = harness(), task = h.start(), xhr = h.requests[0];
  assert.equal(xhr.headers['X-Commerce-Module'], 'CAPTURE');
  assert.equal(xhr.withCredentials, true);
  assert.equal(h.get('main-style-upload-progress').hidden, false);
  xhr.upload.fire('progress', {loaded: 524288, total: 1048576, lengthComputable: true});
  assert.equal(h.get('main-style-upload-bar').value, 50);
  assert.match(h.get('main-style-upload-meta').textContent, /0.5 MB \/ 1.0 MB/);
  xhr.upload.fire('load');
  assert.equal(Object.hasOwn(h.get('main-style-upload-bar'), 'value'), false);
  assert.match(h.get('main-style-upload-label').textContent, /校验并保存/);
  h.rows([row(['PENDING'])]); xhr.respond(200, {data: row(['PENDING'])});
  await task;
  assert.equal(h.get('main-style-create-dialog').open, false);
  assert.equal(h.get('main-style-upload-progress').hidden, true);
  assert.equal(h.intervals.size, 0);
});
test('unknown upload length never invents a percentage and explicit retry reuses the same key', async () => {
  const h = harness(), task = h.start(), xhr = h.requests[0];
  xhr.upload.fire('progress', {loaded: 500, lengthComputable: false});
  assert.equal(Object.hasOwn(h.get('main-style-upload-bar'), 'value'), false);
  assert.doesNotMatch(h.get('main-style-upload-label').textContent, /%/);
  xhr.fire('error'); await task;
  assert.equal(h.requests.length, 1);
  assert.equal(h.get('main-style-save').disabled, false);
  const retry = h.submit();
  assert.equal(h.requests[1].headers['Idempotency-Key'], xhr.headers['Idempotency-Key']);
  h.requests[1].fire('timeout'); await retry;
  assert.match(h.get('main-style-upload-error').textContent, /超时/);
  assert.equal(h.intervals.size, 0);
});
test('company reset aborts upload and ignores late progress, results and unauthorized responses', async () => {
  const h = harness(), task = h.start(), xhr = h.requests[0];
  h.window.fire('heyi:company-reset'); h.context.currentUser = {organizationId: 'another'};
  xhr.upload.fire('load'); xhr.respond(401, {message: 'old tenant'});
  await task;
  assert.equal(xhr.aborted, true);
  assert.equal(h.expired(), 0);
  assert.equal(h.get('main-style-upload-progress').hidden, true);
  assert.equal(h.get('main-style-upload-error').textContent, '');
  assert.equal(h.intervals.size, 0);
});
test('current unauthorized request expires session; malformed success and permission failures remain retryable', async () => {
  for (const [status, body] of [[401, {}], [403, {}], [200, null]]) {
    const h = harness(), task = h.start();
    h.requests[0].respond(status, body); await task;
    assert.equal(h.expired(), status === 401 ? 1 : 0);
    assert.equal(h.intervals.size, 0);
    if (status !== 401) assert.ok(h.get('main-style-upload-error').textContent);
  }
});
test('partial failures keep polling and distinguish handled, success, queued and actual SDK activity', async () => {
  const h = harness(); h.rows([row(['READY', 'FAILED', 'ANALYZING', 'QUEUED', 'PENDING'])]);
  await h.refresh(); h.select('style');
  const html = h.get('main-style-detail').innerHTML;
  for (const text of ['已处理 2 / 5 张', '成功 1', '失败 1', '提取中 1', '排队 2', '第 3 张', '已返回 123 字符', '已用 12 秒', 'Pi Agent 正在返回分析结果']) assert.ok(html.includes(text), text);
  assert.equal(h.timers.size, 1);
  assert.doesNotMatch(html, /全部提取完成/);
  h.rows([row(['READY', 'FAILED'])]); await h.refresh();
  assert.match(h.get('main-style-detail').innerHTML, /提取结束，有失败图片/);
  assert.equal(h.timers.size, 0);
});
test('polling preserves draft prompts when a different image changes status', async () => {
  const h = harness(); h.rows([row(['READY', 'ANALYZING'])]); await h.refresh(); h.select('style');
  h.get('main-style-detail').fire('input', {target: {matches: () => true, value: 'My changed {{product}}',
    closest: () => ({dataset: {imageId: 'image-0'}})}});
  h.rows([row(['READY', 'READY'])]); await h.refresh();
  assert.match(h.get('main-style-detail').innerHTML, /My changed \{\{product\}\}/);
  assert.match(h.get('main-style-detail').innerHTML, /全部提取完成/);
});
test('product-free images are ready originals without prompt editing or a failed-state retry', async () => {
  const h = harness(), template = row(['READY']);
  template.images[0].analysis = {mode:'PRESERVE_ORIGINAL', complete:true, productRegions:[], prompt:'',
    productDescription:'售后说明，无商品主体', layout:'纵向图标列表'};
  h.rows([template]); await h.refresh(); h.select('style');
  const html = h.get('main-style-detail').innerHTML;
  assert.match(html, /原样保留/);
  assert.match(html, /全部提取完成/);
  assert.match(html, /成功 1/);
  assert.match(html, /失败 0/);
  assert.match(html, /售后说明，无商品主体/);
  assert.doesNotMatch(html, /data-style-prompt|save-prompt|重试失败图片|main-style-region/);
  assert.equal(h.timers.size, 0);
});
