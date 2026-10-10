import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const appSource = readFileSync(new URL('../app.js', import.meta.url), 'utf8');
const terminalSource = readFileSync(new URL('../video-script-terminal.js', import.meta.url), 'utf8');
const functionSource = name => {
  const start = appSource.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `Missing function ${name}`);
  return appSource.slice(start, appSource.indexOf('\n}', start) + 2);
};
const task = (run, text = '', options = {}) => ({
  id: 'script-a', analysisRunId: run, analysisOutput: text,
  analysisOutputCharacters: text.length, status: 'AI_ANALYZING', progress: 58,
  ...options
});

function harness({ reducedMotion = true } = {}) {
  const hosts = new Set();
  const events = new Map();
  const frames = new Map();
  let frameId = 0;
  let time = 0;
  const element = () => ({
    textContent: '', style: {}, attributes: {}, listeners: new Map(),
    scrollHeight: 2000, clientHeight: 200, scrollTop: 0,
    classList: { values: new Set(), toggle(name, value) { value ? this.values.add(name) : this.values.delete(name); } },
    setAttribute(name, value) { this.attributes[name] = value; },
    addEventListener(name, listener) { this.listeners.set(name, listener); }
  });
  const createHost = () => {
    let markup = '';
    let children = new Map();
    const host = {
      isConnected: true, hidden: false, creations: 0,
      get innerHTML() { return markup; },
      set innerHTML(value) {
        markup = value;
        children = new Map();
        if (value) this.creations++;
      },
      querySelector(selector) {
        if (!markup) return null;
        if (!children.has(selector)) {
          const child = element();
          child.parentElement = host;
          child.querySelector = selector => host.querySelector(selector);
          children.set(selector, child);
        }
        return children.get(selector);
      }
    };
    hosts.add(host);
    return host;
  };
  const context = vm.createContext({
    window: {
      matchMedia: () => ({ matches: reducedMotion }),
      addEventListener(name, listener) { events.set(name, listener); }
    },
    document: { querySelectorAll: () => [...hosts].map(host => host.querySelector('.model-terminal')).filter(Boolean) },
    navigator: { clipboard: { writeText: async () => {} } },
    requestAnimationFrame(callback) { const id = ++frameId; frames.set(id, callback); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
    performance: { now: () => time },
    videoScriptVisualKey: '', videoScriptVisualProgress: 0, videoScriptVisualServerProgress: 0, videoScriptVisualStatus: ''
  });
  for (const name of ['videoScriptProgressStates', 'processingVideoScriptStatuses']) {
    const start = appSource.indexOf(`const ${name} =`);
    vm.runInContext(appSource.slice(start, appSource.indexOf(';', start) + 1), context);
  }
  for (const name of ['normalizeVideoScriptProgress', 'videoScriptDisplayProgress', 'resetVideoScriptTaskProgress', 'updateVideoScriptOutput']) {
    vm.runInContext(functionSource(name), context);
  }
  vm.runInContext(terminalSource, context);
  const host = createHost();
  const update = item => context.updateVideoScriptOutput(host, item);
  const flush = () => {
    for (let i = 0; frames.size && i < 200; i++) {
      time += 16;
      const callbacks = [...frames.values()];
      frames.clear();
      callbacks.forEach(callback => callback(time));
    }
    assert.equal(frames.size, 0);
  };
  return { context, host, update, flush, frames, events };
}

test('polling preserves terminal DOM and a textless list snapshot preserves output', () => {
  const h = harness();
  h.update(task('run', '模型已生成第一镜'));
  const code = h.host.querySelector('code');
  h.update(task('run', undefined, { analysisOutput: undefined, analysisOutputCharacters: 50 }));
  assert.equal(h.host.querySelector('code'), code);
  assert.equal(code.textContent, '模型已生成第一镜');
  assert.equal(h.host.creations, 1);
  h.update(task('run', '旧结果'));
  assert.equal(code.textContent, '模型已生成第一镜');
});

test('retired run snapshots never replace a newer run or its progress', () => {
  const h = harness();
  h.update(task('old', '旧模型回复', { status: 'FAILED', progress: 100 }));
  h.update(task('new', '新回复', { progress: 56 }));
  const terminal = h.host.querySelector('.model-terminal');
  h.update(task('old', '旧模型回复', { status: 'READY', progress: 100 }));
  assert.equal(h.host.querySelector('.model-terminal'), terminal);
  assert.equal(h.host.querySelector('code').textContent, '新回复');
  assert.equal(h.host.querySelector('.model-terminal-meter').attributes['aria-valuenow'], '56');
  assert.equal(h.host.querySelector('.model-terminal-eyebrow b').textContent, '● LIVE');
});

test('an accepted retry with an empty run ID immediately clears old model text', () => {
  const h = harness();
  h.update(task('failed-run', '上次收到的文字', { status: 'FAILED' }));
  h.context.resetVideoScriptTaskProgress('script-a');
  h.update(task('', '', { status: 'QUEUED', progress: 5 }));
  assert.equal(h.host.querySelector('code').textContent, '');
  assert.equal(h.host.querySelector('.model-terminal-progress strong').textContent, '01 / 04');
  h.update(task('failed-run', '上次收到的文字', { status: 'FAILED' }));
  assert.equal(h.host.querySelector('code').textContent, '');
  h.update(task('retry-run', '新一轮模型返回'));
  assert.equal(h.host.querySelector('code').textContent, '新一轮模型返回');
});

test('missing-run stale data cannot restore an old response once the real run is known', () => {
  const h = harness();
  h.update(task('current', '本轮文字'));
  h.update(task('', '旧数据比新数据更长', { status: 'FAILED' }));
  assert.equal(h.host.querySelector('code').textContent, '本轮文字');
});

test('task changes replace output immediately rather than mixing the two responses', () => {
  const h = harness();
  h.update(task('run-a', '甲任务文本'));
  h.update(task('run-b', '', { id: 'script-b' }));
  assert.equal(h.host.querySelector('code').textContent, '');
  assert.equal(h.host.creations, 2);
});

test('stream animation is preserved between polls and completion reveals all remaining text', () => {
  const h = harness({ reducedMotion: false });
  h.update(task('run'));
  h.update(task('run', '正在编排九个分镜的模型返回文字'));
  assert.equal(h.frames.size, 1);
  const code = h.host.querySelector('code');
  h.update(task('run', '正在编排九个分镜的模型返回文字，继续新增内容'));
  assert.equal(h.frames.size, 1);
  assert.equal(h.host.querySelector('code'), code);
  h.update(task('run', '最终结果', { status: 'READY', progress: 100, analysisStage: 'RESULT_READY', analysisOutputCharacters: 100 }));
  assert.equal(h.frames.size, 0);
  assert.equal(code.textContent, '最终结果');
  assert.equal(h.host.querySelector('.model-terminal-status').textContent, '拆解完成');
});

test('scrolling up pauses following and the latest-output button resumes it', () => {
  const h = harness({ reducedMotion: false });
  h.update(task('run', '第一段'));
  const screen = h.host.querySelector('.model-terminal-screen');
  screen.scrollTop = 500;
  screen.listeners.get('scroll')();
  h.update(task('run', '第一段和新的一段文字'));
  h.flush();
  assert.equal(screen.scrollTop, 500);
  const follow = h.host.querySelector('[data-terminal-follow]');
  assert.equal(follow.attributes['aria-pressed'], 'false');
  follow.listeners.get('click')();
  assert.equal(screen.scrollTop, 2000);
  assert.equal(follow.attributes['aria-pressed'], 'true');
});

test('reduced motion displays new text immediately without animation frames', () => {
  const h = harness();
  h.update(task('run'));
  h.update(task('run', '<script>模型返回原文</script>'));
  assert.equal(h.frames.size, 0);
  assert.equal(h.host.querySelector('code').textContent, '<script>模型返回原文</script>');
});

test('company reset removes old output and cancels in-flight reveal callbacks', () => {
  const h = harness({ reducedMotion: false });
  h.update(task('run'));
  h.update(task('run', '公司甲的输出'));
  assert.equal(h.frames.size, 1);
  h.events.get('heyi:company-reset')();
  assert.equal(h.frames.size, 0);
  assert.equal(h.host.innerHTML, '');
  assert.equal(h.host.querySelector('.model-terminal'), null);
});

test('same-length snapshots cannot replace newer output with an older timestamp', () => {
  const h = harness();
  h.update(task('run', '新结果', { analysisOutputUpdatedAt: '2026-09-17T12:00:02Z' }));
  h.update(task('run', '旧结果', { analysisOutputUpdatedAt: '2026-09-17T12:00:01Z' }));
  assert.equal(h.host.querySelector('code').textContent, '新结果');
});
