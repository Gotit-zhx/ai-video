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
test('general previews bypass old local redirects even with server-provided URLs and preserve external signatures', () => {
  const context={URL,GENERAL_VIDEO_API_ROOT:'/api/v1/general-video-tasks',
    window:{location:new URL('https://hoyee.org.cn/?lang=zh')}};
  vm.createContext(context);
  vm.runInContext(between('function generalVideoAssetUrl(', 'function uploadFormData('),context);
  for(const path of ['/api/v1/general-video-tasks/t/assets/a?preview=true',
      'https://hoyee.org.cn/api/v1/general-video-tasks/t/assets/a?preview=true']){
    const url=new URL(context.generalVideoAssetUrl('t',{previewUrl:path},true),'https://hoyee.org.cn');
    assert.equal(url.searchParams.get('cache'),'20261008-local');
    assert.equal(url.searchParams.get('preview'),'true');
  }
  const signed='https://storage.example/asset.jpg?X-Signature=a%2Fb%2Bc&token=one+two';
  assert.equal(context.generalVideoAssetUrl('t',{previewUrl:signed},true),signed);
  assert.equal(context.generalVideoAssetUrl('t',{url:'/other/file.jpg?token=a%2Fb'}),'/other/file.jpg?token=a%2Fb');
  assert.match(context.generalVideoAssetUrl('t',{id:'a'},true),/preview=true&cache=20261008-local/);
});
function harness(assets = []) {
  const links = [];
  const timers = [];
  const streamed=[];
  let click;
  const context = {
    URL,activeAiVideoDownload:null,aiVideoDownloadResults:new Map(),handleSessionExpired(){},renderAiVideoDownloadState(){},
    window: { HeyVideoDownloads:{async run(files){streamed.push(...files);return {succeeded:files.map(f=>f.id),failed:[]};}},location: new URL('https://hoyee.org.cn/#workspace'), setTimeout: fn => timers.push(fn), alert: message => { throw new Error(message); } },
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
    aiVideoTaskDetail: { task: { id:'task/one',name: '测试任务' }, assets },
  };
  vm.createContext(context);
  vm.runInContext([
    between('function aiVideoAssetUrl(', 'function reusableMediaKey('),
    between('async function downloadImageAsset(', 'function enableAssetWheelZoom('),
    between('function aiVideoDownloadFiles()', 'function renderAiVideoDownloadState()'),
    between('document.querySelector("#batch-download-videos").addEventListener(', 'document.querySelector("#garment-card-grid").addEventListener("change"'),
  ].join('\n'), context);
  const button = { disabled: false, textContent: '下载视频' };
  return { links,streamed, button, download: (url, filename = '视频成片') => context.downloadImageAsset(button, url, filename), click: () => click({ currentTarget: button }) };
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

test('one completed video enters the bounded downloader with its exact asset URL and filename', async () => {
  const h=harness([{id:'image',assetType:'STORYBOARD_GRID'},{id:'asset/one',assetType:'VIDEO_RESULT',createdAt:'2026-09-18'}]);
  await h.click();assert.equal(h.streamed.length,1);assert.equal(h.links.length,0);
  const url=new URL(h.streamed[0].url);
  assert.equal(url.pathname,'/api/v1/ai-video-tasks/task%2Fone/assets/asset%2Fone');
  assert.equal(url.searchParams.get('download'),'true');assert.equal(url.searchParams.get('filename'),'测试任务-01-成片.mp4');
});
test('multiple completed videos use individual downloads and no-result tasks do not navigate',async()=>{
 const h=harness([{id:'a',assetType:'VIDEO_RESULT'},{id:'b',assetType:'VIDEO_RESULT'}]);
 await h.click();assert.deepEqual(h.streamed.map(f=>f.id),['a','b']);assert.equal(h.links.length,0);
 assert.ok(h.streamed.every(f=>!f.url.includes('.zip')));
 const empty=harness([{id:'candidate',assetType:'VIDEO_REVIEW_CANDIDATE'}]);await empty.click();assert.equal(empty.streamed.length,0);
});
