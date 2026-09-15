import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../app.js', import.meta.url), 'utf8');
const functionSource = name => {
  const start = source.search(new RegExp(`(?:async )?function ${name}\\(`));
  assert.notEqual(start, -1, `Missing function ${name}`);
  return source.slice(start, source.indexOf('\n}', start) + 2);
};
const listenerSource = (selector, event = 'click') => {
  const start = source.indexOf(`document.querySelector("${selector}").addEventListener("${event}"`);
  assert.notEqual(start, -1, `Missing listener ${selector}`);
  return source.slice(start, source.indexOf('\n});', start) + 4);
};
const script = (id, status, progress = 0) => ({
  id, status, progress, organizationId: 'company-a', sourceType: 'VIDEO_ANALYSIS', name: id
});
const detail = item => ({ data: {
  script: item,
  shots: item.status === 'READY' ? Array.from({ length: 9 }, (_, index) => ({ id: `shot-${index}` })) : []
} });
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};

function harness(items = []) {
  const nodes = new Map();
  const timers = new Map();
  const requests = [];
  let sequence = 0;
  const element = () => ({
    hidden: false, disabled: false, open: false, textContent: '', innerHTML: '',
    style: {}, dataset: {}, children: [], listeners: {},
    classList: { add() {}, remove() {}, toggle() {} },
    addEventListener(event, listener) { this.listeners[event] = listener; },
    append(child) { this.children.push(child); },
    querySelector() { return element(); },
    setAttribute() {}, removeAttribute() {},
    showModal() { this.open = true; }, close() { this.open = false; }, reset() {}
  });
  const node = selector => {
    if (!nodes.has(selector)) nodes.set(selector, element());
    return nodes.get(selector);
  };
  const setInterval = (callback, delay) => {
    const id = ++sequence;
    timers.set(id, { callback, delay });
    return id;
  };
  const clearInterval = id => timers.delete(id);
  const context = vm.createContext({
    document: { hidden: false, querySelector: node, querySelectorAll: () => [], createElement: element },
    window: { setInterval, clearInterval, confirm: () => true, alert: message => { throw Error(message); } },
    FormData: class { constructor(form) { this.form = form; } },
    setInterval, clearInterval, performance: { now: () => 1000 }, console,
    currentUser: { organizationId: 'company-a' },
    videoScriptStatusTimer: null, videoScriptPollingGeneration: 0,
    videoScriptLoadGeneration: 0, videoScriptDetailGeneration: 0,
    videoScriptProgressId: null, activeVideoScript: null,
    selectedVideoScriptId: null, selectedVideoScriptDetail: null, selectedVideoShot: null,
    videoScriptVisualProgress: 0, videoScriptVisualStatus: '', videoScriptVisualKey: '',
    videoScriptVisualServerProgress: 0, videoScriptVisualProgressTimer: null,
    videoScriptVisualProgressUpdatedAt: 0, videoScripts: [], videoScriptSearchQuery: '',
    hasAnyPermission: () => true, escapeHtml: value => String(value ?? ''),
    selectRenderedShot: () => {},
    api: async (path, options) => {
      requests.push({ path, options });
      if (path === '/api/v1/video-scripts') return { data: items };
      const item = items.find(item => path === `/api/v1/video-scripts/${item.id}`);
      if (item) return detail(item);
      throw Error(`Unexpected request ${path}`);
    }
  });
  for (const name of ['processingVideoScriptStatuses', 'videoScriptStatusNames', 'videoScriptPresentationNames', 'videoScriptVisualStageConfig']) {
    const start = source.indexOf(`const ${name} =`);
    const end = source.indexOf(';', start) + 1;
    vm.runInContext(source.slice(start, end), context);
  }
  for (const name of [
    'selectVideoScriptCreateMode', 'syncScriptPresentationModePicker', 'prepareVideoScriptCreationDialog',
    'paintVideoScriptProgress', 'stopVideoScriptVisualProgress', 'startVideoScriptVisualProgress',
    'renderVideoScriptProgress', 'videoScriptStatusClass', 'normalizedFuzzyValue', 'fuzzySearchMatches',
    'filteredVideoScripts', 'renderVideoScriptLibrary', 'renderEmptyVideoScriptDetail',
    'videoScriptRequestIsCurrent', 'isVideoScriptProgressDialogOpen', 'loadVideoScripts',
    'scriptStageClass', 'videoShotPreviewUrl', 'renderStoryboardBuildingState', 'loadVideoScriptDetail',
    'startVideoScriptPolling', 'stopVideoScriptPolling', 'openVideoScriptProgress'
  ]) vm.runInContext(functionSource(name), context);
  for (const selector of ['#import-video-script', '#retry-video-script', '#reanalyze-video-script']) {
    vm.runInContext(listenerSource(selector), context);
  }
  vm.runInContext(listenerSource("#video-script-form", "submit"), context);
  return { context, node, timers, requests, items,
    click: selector => node(selector).listeners.click(),
    poll: () => timers.get(context.videoScriptStatusTimer).callback()
  };
}

test('new upload stays available during analysis and after failure without a current-task request', async () => {
  for (const status of ['QUEUED', 'EXTRACTING_FRAMES', 'AI_ANALYZING', 'FAILED']) {
    const h = harness();
    h.context.activeVideoScript = script('old-task', status);
    h.context.renderVideoScriptProgress(h.context.activeVideoScript);
    await h.click('#import-video-script');
    assert.equal(h.node('#video-script-dialog').open, true);
    assert.equal(h.node('#script-active-analysis').hidden, true);
    assert.equal(h.node('#script-create-ai').hidden, false);
    assert.equal(h.node('#submit-video-script').hidden, false);
    assert.equal(h.node('#import-video-script').textContent, '＋ 新建脚本');
    assert.equal(h.context.videoScriptProgressId, null);
    assert.equal(h.requests.length, 0);
  }
});

test('polling tracks every processing task and stops only after all tasks are terminal', async () => {
  const h = harness([script('failed', 'FAILED'), script('a', 'AI_ANALYZING'), script('b', 'QUEUED')]);
  await h.context.loadVideoScripts('failed');
  const timer = h.context.videoScriptStatusTimer;
  assert.ok(timer);
  assert.equal(h.context.selectedVideoScriptId, 'failed');
  h.items[1] = script('a', 'READY', 100);
  await h.poll();
  assert.equal(h.context.videoScriptStatusTimer, timer);
  assert.equal(h.context.activeVideoScript.id, 'b');
  assert.equal(h.context.selectedVideoScriptId, 'failed');
  h.items[2] = script('b', 'FAILED', 70);
  await h.poll();
  assert.equal(h.context.videoScriptStatusTimer, null);
  assert.equal(h.context.activeVideoScript, null);
  assert.ok(h.requests.every(request => !request.path.endsWith('/current') && !request.path.endsWith('/status')));
});

test('polling preserves a new upload form and its entered text', async () => {
  const h = harness([script('a', 'AI_ANALYZING', 70)]);
  await h.context.loadVideoScripts();
  await h.click('#import-video-script');
  h.node('#video-script-form').draftName = '第二个视频';
  h.node('#script-source-video').files = [{ name: 'second.mp4' }];
  assert.equal(h.context.videoScriptVisualKey, '');
  await h.poll();
  assert.equal(h.context.videoScriptVisualKey, 'a');
  assert.equal(h.context.videoScriptVisualProgress, 70);
  assert.equal(h.node('#script-active-analysis').hidden, true);
  assert.equal(h.node('#script-create-ai').hidden, false);
  assert.equal(h.node('#submit-video-script').hidden, false);
  assert.equal(h.node('#video-script-form').draftName, '第二个视频');
  assert.equal(h.node('#script-source-video').files[0].name, 'second.mp4');
});

test('retry uses the failed task shown in the dialog even while another task is active', async () => {
  const h = harness([script('running', 'AI_ANALYZING', 70), script('failed', 'FAILED', 50)]);
  await h.context.loadVideoScripts('failed');
  assert.equal(h.node('#reanalyze-video-script').textContent, '重试拆解');
  await h.click('#reanalyze-video-script');
  assert.equal(h.context.videoScriptProgressId, 'failed');
  assert.equal(h.node('#retry-video-script').hidden, false);
  const originalApi = h.context.api;
  h.context.api = async (path, options) => {
    if (path.endsWith('/retry')) {
      h.requests.push({ path, options });
      h.items[1] = script('failed', 'QUEUED', 0);
      return { data: h.items[1] };
    }
    return originalApi(path, options);
  };
  await h.click('#retry-video-script');
  assert.deepEqual(h.requests.filter(request => request.options?.method === 'POST').map(request => request.path),
    ['/api/v1/video-scripts/failed/retry']);
  assert.equal(h.context.activeVideoScript.id, 'running');
  assert.equal(h.context.videoScriptProgressId, 'failed');
  assert.equal(h.node('#active-script-name').textContent, 'failed');
  assert.equal(h.node('#retry-video-script').hidden, true);
});

test('a progress dialog reaches completion while the remaining task continues polling', async () => {
  const h = harness([script('a', 'AI_ANALYZING', 70), script('b', 'QUEUED', 10)]);
  await h.context.loadVideoScripts('a');
  await h.click('#reanalyze-video-script');
  h.items[0] = script('a', 'READY', 100);
  h.context.selectedVideoScriptId = 'b';
  await h.poll();
  assert.equal(h.context.selectedVideoScriptId, 'b');
  assert.equal(h.context.videoScriptProgressId, 'a');
  assert.equal(h.context.videoScriptVisualKey, 'a');
  assert.equal(h.context.videoScriptVisualProgress, 100);
  assert.equal(h.node('#active-script-name').textContent, 'a');
  assert.equal(h.node('#video-script-dialog-title').textContent, '视频拆解完成');
  assert.ok(h.context.videoScriptStatusTimer);
});

test('successful scripts still show nine shots and reanalysis targets that selected script', async () => {
  const h = harness([script('ready', 'READY', 100), script('running', 'AI_ANALYZING')]);
  await h.context.loadVideoScripts('ready');
  assert.equal(h.context.selectedVideoScriptDetail.shots.length, 9);
  assert.equal(h.node('#reanalyze-video-script').textContent, '重新精拆 9 镜');
  assert.equal(h.node('#copy-video-script').disabled, false);
  const originalApi = h.context.api;
  h.context.api = async (path, options) => {
    if (path.endsWith('/reanalyze')) {
      h.requests.push({ path, options });
      h.items[0] = script('ready', 'QUEUED');
      return { data: h.items[0] };
    }
    return originalApi(path, options);
  };
  await h.click('#reanalyze-video-script');
  assert.ok(h.requests.some(request => request.path === '/api/v1/video-scripts/ready/reanalyze'));
  assert.equal(h.context.videoScriptProgressId, 'ready');
  assert.equal(h.node('#video-script-dialog').open, true);
});

test('late detail response cannot replace a newer task selection', async () => {
  const h = harness();
  const old = deferred();
  h.context.api = path => path.endsWith('/old') ? old.promise : Promise.resolve(detail(script('new', 'FAILED')));
  const pending = h.context.loadVideoScriptDetail('old');
  await h.context.loadVideoScriptDetail('new');
  old.resolve(detail(script('old', 'READY', 100)));
  await pending;
  assert.equal(h.context.selectedVideoScriptId, 'new');
  assert.equal(h.context.selectedVideoScriptDetail.script.id, 'new');
  assert.equal(h.node('#script-detail-title').textContent, 'new');
});

test('a selection made during a list request survives its response', async () => {
  const h = harness([script('old', 'FAILED'), script('new', 'FAILED')]);
  const list = deferred();
  const originalApi = h.context.api;
  h.context.api = path => path === '/api/v1/video-scripts' ? list.promise : originalApi(path);
  h.context.selectedVideoScriptId = 'old';
  const pending = h.context.loadVideoScripts('old');
  await h.context.loadVideoScriptDetail('new');
  list.resolve({ data: h.items });
  await pending;
  assert.equal(h.context.selectedVideoScriptId, 'new');
  assert.equal(h.context.selectedVideoScriptDetail.script.id, 'new');
});

test('stopped polling ignores its in-flight list response and cannot recreate its timer', async () => {
  const h = harness([script('a', 'AI_ANALYZING')]);
  await h.context.loadVideoScripts();
  const list = deferred();
  h.context.api = () => list.promise;
  const pending = h.poll();
  h.context.stopVideoScriptPolling();
  h.node('#video-scripts-page').hidden = true;
  list.resolve({ data: [script('stale', 'AI_ANALYZING')] });
  await pending;
  assert.equal(h.context.videoScripts[0].id, 'a');
  assert.equal(h.context.videoScriptStatusTimer, null);
});

test('a previous company response cannot display scripts or start polling after switching company', async () => {
  const h = harness();
  const list = deferred();
  h.context.api = () => list.promise;
  const pending = h.context.loadVideoScripts();
  h.context.currentUser = { organizationId: 'company-b' };
  list.resolve({ data: [script('foreign', 'AI_ANALYZING')] });
  await pending;
  assert.equal(h.context.videoScripts.length, 0);
  assert.equal(h.context.videoScriptStatusTimer, null);
});


test('two video submissions create separate tasks while the first remains in progress', async () => {
  const h = harness();
  const originalApi = h.context.api;
  h.context.api = async (path, options) => {
    if (path === '/api/v1/video-scripts/analyze') {
      h.requests.push({ path, options });
      const created = script(`uploaded-${h.items.length + 1}`, 'AI_ANALYZING', 70);
      h.items.push(created);
      return { data: created };
    }
    return originalApi(path, options);
  };
  for (const name of ['first.mp4', 'second.mp4']) {
    await h.click('#import-video-script');
    h.node('#script-source-video').files = [{ name }];
    await h.node('#video-script-form').listeners.submit({
      preventDefault() {}, currentTarget: h.node('#video-script-form')
    });
    assert.equal(h.node('#submit-video-script').disabled, false);
    assert.equal(h.node('#video-script-dialog').open, false);
  }
  assert.equal(h.requests.filter(request => request.path.endsWith('/analyze')).length, 2);
  assert.equal(h.context.videoScripts.length, 2);
  assert.equal(h.context.videoScripts[0].status, 'AI_ANALYZING');
  assert.equal(h.context.selectedVideoScriptId, 'uploaded-2');
  assert.ok(h.context.videoScriptStatusTimer);
});

test('a temporary detail error leaves all processing tasks polling', async () => {
  const h = harness([script('a', 'AI_ANALYZING')]);
  const originalApi = h.context.api;
  h.context.api = path => path === '/api/v1/video-scripts'
    ? originalApi(path) : Promise.reject(Error('temporary detail failure'));
  await h.context.loadVideoScripts('a');
  assert.ok(h.context.videoScriptStatusTimer);
  h.context.api = originalApi;
  await h.poll();
  assert.equal(h.context.selectedVideoScriptDetail.script.id, 'a');
});
