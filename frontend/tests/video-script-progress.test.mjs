import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../app.js', import.meta.url), 'utf8');
const functionSource = name => {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing function ${name}`);
  return source.slice(start, source.indexOf('\n}', start) + 2);
};
const script = (id, progress, analysisRunId = 'run-1', status = 'AI_ANALYZING') => ({
  id, progress, analysisRunId, status, name: id, sourceType: 'VIDEO_ANALYSIS'
});

function harness() {
  const nodes = new Map();
  const live = [];
  const timers = new Map();
  const element = () => ({
    textContent: '', innerHTML: '', style: {}, dataset: {}, children: [],
    addEventListener() {}, append(child) { this.children.push(child); }
  });
  const node = selector => {
    if (!nodes.has(selector)) nodes.set(selector, element());
    return nodes.get(selector);
  };
  const context = vm.createContext({
    document: { querySelector: node, querySelectorAll: () => live, createElement: element },
    window: {
      setInterval(callback) { const id = timers.size + 1; timers.set(id, callback); return id; },
      clearInterval(id) { timers.delete(id); }
    },
    selectedVideoScriptId: null, videoScriptProgressId: null,
    videoScriptVisualProgress: 0, videoScriptVisualStatus: '', videoScriptVisualKey: '',
    videoScriptVisualServerProgress: 0, videoScriptVisualProgressTimer: null,
    videoScriptVisualProgressUpdatedAt: 0, videoScripts: [], videoScriptSearchQuery: '',
    escapeHtml: value => String(value ?? ''), loadVideoScriptDetail: () => {}
  });
  for (const name of ['processingVideoScriptStatuses', 'videoScriptStatusNames', 'videoScriptPresentationNames', 'videoScriptProgressStates']) {
    const start = source.indexOf(`const ${name} =`);
    vm.runInContext(source.slice(start, source.indexOf(';', start) + 1), context);
  }
  for (const name of [
    'normalizeVideoScriptProgress', 'videoScriptDisplayProgress', 'resetVideoScriptTaskProgress',
    'paintVideoScriptProgress', 'stopVideoScriptVisualProgress', 'startVideoScriptVisualProgress',
    'videoScriptStatusClass', 'normalizedFuzzyValue', 'fuzzySearchMatches',
    'filteredVideoScripts', 'renderVideoScriptLibrary'
  ]) vm.runInContext(functionSource(name), context);
  const addLive = id => { const result = element(); result.dataset.videoScriptProgressLive = id; live.push(result); return result; };
  const start = item => context.startVideoScriptVisualProgress(item.status, item.progress, item.id, item.analysisRunId);
  return { context, node, timers, addLive, start };
}

test('new server checkpoints render immediately and stale polling cannot lower them', () => {
  const h = harness();
  h.context.selectedVideoScriptId = 'a';
  h.start(script('a', 28));
  assert.equal(h.node('#storyboard-inline-progress-number').textContent, '28.0');
  h.start(script('a', 58));
  assert.equal(h.node('#storyboard-inline-progress-number').textContent, '58.0');
  assert.equal(h.node('#storyboard-inline-progress-bar').style.width, '58%');
  h.start(script('a', 28, 'run-1', 'EXTRACTING_FRAMES'));
  assert.equal(h.node('#storyboard-inline-progress-number').textContent, '58.0');
  assert.equal(h.timers.size, 0, 'elapsed time must not invent progress');
});

test('list rebuilds use the same checkpoint as detail and preserve each task independently', () => {
  const h = harness();
  h.start(script('a', 58));
  h.start(script('b', 22));
  h.context.videoScripts = [script('a', 28), script('b', 12)];
  h.context.renderVideoScriptLibrary();
  const rows = h.node('#video-script-list').children;
  assert.match(rows[0].innerHTML, /data-video-script-progress-live="a">58\.0<\/span>/);
  assert.match(rows[1].innerHTML, /data-video-script-progress-live="b">22\.0<\/span>/);
  h.start(script('a', 40));
  assert.equal(h.context.videoScriptVisualProgress, 58);
});

test('a progress modal and another selected task never paint each other', () => {
  const h = harness();
  h.context.selectedVideoScriptId = 'selected';
  h.context.videoScriptProgressId = 'modal';
  const selectedRow = h.addLive('selected');
  const modalRow = h.addLive('modal');
  h.start(script('selected', 58));
  assert.equal(h.node('#storyboard-inline-progress-number').textContent, '58.0');
  assert.equal(h.node('#active-script-progress-number').innerHTML, '');
  assert.equal(selectedRow.textContent, '58.0');
  assert.equal(modalRow.textContent, '');
  h.start(script('modal', 82));
  assert.equal(h.node('#storyboard-inline-progress-number').textContent, '58.0');
  assert.equal(h.node('#active-script-progress-number').innerHTML, '<strong>82.0</strong><small>%</small>');
  assert.equal(selectedRow.textContent, '58.0');
  assert.equal(modalRow.textContent, '82.0');
});

test('a true new run resets progress and delayed responses from retired runs are ignored', () => {
  const { context: c } = harness();
  assert.equal(c.videoScriptDisplayProgress(script('a', 100, 'old', 'READY')), 100);
  assert.equal(c.videoScriptDisplayProgress(script('a', 2, 'new', 'QUEUED')), 2);
  assert.equal(c.videoScriptDisplayProgress(script('a', 100, 'old', 'READY')), 2);
  assert.equal(c.videoScriptDisplayProgress(script('a', 100, '', 'READY')), 2);
  assert.equal(c.videoScriptDisplayProgress(script('a', 58, 'new')), 58);
  assert.equal(c.videoScriptDisplayProgress(script('a', 1, 'new', 'QUEUED')), 58);
});

test('only a successful explicit legacy retry can reset the same task without a run ID', () => {
  const { context: c } = harness();
  assert.equal(c.videoScriptDisplayProgress(script('a', 70, '', 'FAILED')), 70);
  assert.equal(c.videoScriptDisplayProgress(script('a', 0, '', 'QUEUED')), 70);
  c.resetVideoScriptTaskProgress('a');
  assert.equal(c.videoScriptDisplayProgress(script('a', 0, '', 'QUEUED')), 0);
  assert.equal(c.videoScriptDisplayProgress(script('a', 18, '', 'EXTRACTING_FRAMES')), 18);
});

test('retry reset keeps old runs retired and does not reset other tasks', () => {
  const { context: c } = harness();
  c.videoScriptDisplayProgress(script('a', 70, 'old', 'FAILED'));
  c.videoScriptDisplayProgress(script('b', 58));
  c.resetVideoScriptTaskProgress('a', 'retry');
  assert.equal(c.videoScriptDisplayProgress(script('a', 70, 'old', 'FAILED')), 0);
  assert.equal(c.videoScriptDisplayProgress(script('a', 12, 'retry', 'EXTRACTING_FRAMES')), 12);
  assert.equal(c.videoScriptDisplayProgress(script('b', 1)), 58);
});

test('a delayed retry acknowledgment cannot rewind a run already advanced by polling', () => {
  const { context: c } = harness();
  c.videoScriptDisplayProgress(script('a', 70, 'old', 'FAILED'));
  c.videoScriptDisplayProgress(script('a', 28, 'retry', 'EXTRACTING_FRAMES'));
  c.resetVideoScriptTaskProgress('a', 'retry');
  assert.equal(c.videoScriptDisplayProgress(script('a', 0, 'retry', 'QUEUED')), 28);
  c.resetVideoScriptTaskProgress('a', 'old');
  assert.equal(c.videoScriptDisplayProgress(script('a', 70, 'old', 'FAILED')), 28);
});

test('processing never claims completion, failure retains progress and READY completes it', () => {
  const { context: c } = harness();
  assert.equal(c.videoScriptDisplayProgress(script('a', 58)), 58);
  assert.equal(c.videoScriptDisplayProgress(script('a', 0, 'run-1', 'FAILED')), 58);
  assert.equal(c.videoScriptDisplayProgress(script('b', 100)), 99);
  assert.equal(c.videoScriptDisplayProgress(script('b', 0, 'run-1', 'READY')), 100);
  assert.equal(c.videoScriptDisplayProgress(script('b', 70)), 100);
  for (const progress of [NaN, Infinity, -5, 'invalid']) {
    assert.equal(c.videoScriptDisplayProgress({ progress, status: 'QUEUED' }), 0);
  }
});
