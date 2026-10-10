const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8');
function between(start, end) {
  const offset = source.indexOf(start);
  assert.ok(offset >= 0, `Missing ${start}`);
  const limit = source.indexOf(end, offset + start.length);
  assert.ok(limit > offset, `Missing ${end}`);
  return source.slice(offset, limit);
}
function harness(assets = []) {
  const links = [];
  const timers = [];
  let click;
  const context = {
    URL,
    window: { location: new URL('https://hoyee.org.cn/#workspace'), setTimeout: fn => timers.push(fn), alert: message => { throw new Error(message); } },
    document: {
      querySelector: selector => {
        assert.equal(selector, '#batch-download-videos');
        return { addEventListener: (event, handler) => { assert.equal(event, 'click'); click = handler; } };
      },
      createElement: tag => {
        assert.equal(tag, 'a');
        return { click() { links.push({ href: this.href, download: this.download, target: this.target, rel: this.rel }); }, remove() {} };
      },
      body: { appendChild() {} },
    },
    selectedAiVideoTaskId: 'task/one',
    aiVideoTaskDetail: { task: { name: '测试任务' }, assets },
  };
  vm.createContext(context);
  vm.runInContext([
    between('function aiVideoAssetUrl(', 'function reusableMediaKey('),
    between('async function downloadImageAsset(', 'function enableAssetWheelZoom('),
    between('document.querySelector("#batch-download-videos").addEventListener(', 'document.querySelector("#garment-card-grid").addEventListener("change"'),
  ].join('\n'), context);
  const button = { disabled: false, textContent: '下载视频' };
  return { links, button, download: (url, filename = '视频成片') => context.downloadImageAsset(button, url, filename), click: () => click({ currentTarget: button }) };
}

test('provider signed URLs remain byte-for-byte unchanged and open without replacing the platform', async () => {
  const h = harness();
  const signed = 'https://provider.example/video.mp4?X-Signature=a%2Fb%2Bc&Expires=9999999999&token=one+two';
  await h.download(signed);
  assert.equal(h.links[0].href, signed);
  assert.equal(h.links[0].target, '_blank');
  assert.equal(h.links[0].rel, 'noopener');
  assert.equal(h.button.disabled, false);
});

test('only same-origin platform APIs receive download parameters', async () => {
  const h = harness();
  await h.download('/api/v1/ai-video-tasks/t/assets/a?v=1#preview', '测试/成片');
  const url = new URL(h.links[0].href);
  assert.equal(url.origin, 'https://hoyee.org.cn');
  assert.equal(url.searchParams.get('v'), '1');
  assert.equal(url.searchParams.get('download'), 'true');
  assert.equal(url.searchParams.get('filename'), '测试-成片');
  assert.equal(url.hash, '#preview');
  for (const untouched of ['https://provider.example/api/video?token=a%2Fb', 'https://hoyee.org.cn/signed-video.mp4?token=a%2Fb']) {
    await h.download(untouched);
    assert.equal(h.links.at(-1).href, untouched);
  }
});

test('one completed video downloads its asset directly without preparing a ZIP', async () => {
  const h = harness([{ id: 'image', assetType: 'STORYBOARD_GRID' }, { id: 'asset/one', assetType: 'VIDEO_RESULT', createdAt: '2026-09-18' }]);
  await h.click();
  assert.equal(h.links.length, 1);
  const url = new URL(h.links[0].href);
  assert.equal(url.pathname, '/api/v1/ai-video-tasks/task%2Fone/assets/asset%2Fone');
  assert.equal(url.searchParams.get('download'), 'true');
  assert.equal(url.searchParams.get('filename'), '测试任务-成片');
  assert.doesNotMatch(h.button.textContent, /ZIP/);
  assert.equal(h.links[0].target, '_blank');
  assert.equal(h.links[0].rel, 'noopener');
});

test('multiple completed videos keep the ZIP download and no-result tasks do not navigate', async () => {
  const h = harness([{ id: 'a', assetType: 'VIDEO_RESULT' }, { id: 'b', assetType: 'VIDEO_RESULT' }]);
  await h.click();
  assert.equal(h.links.length, 1);
  assert.equal(h.links[0].href, '/api/v1/ai-video-tasks/task%2Fone/video-results.zip');
  assert.equal(h.button.textContent, '正在准备 ZIP…');
  const empty = harness([{ id: 'candidate', assetType: 'VIDEO_REVIEW_CANDIDATE' }]);
  await empty.click();
  assert.equal(empty.links.length, 0);
});
