import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const { JSDOM } = await import(process.env.JSDOM_MODULE || 'jsdom');
const dom = new JSDOM('<button id="batch-download-videos"></button><p id="batch-video-download-status"></p>', {
  url: 'https://example.test/', runScripts: 'outside-only'
});
const w = dom.window, downloads = [], revoked = [], calls = [];
const realTimer = w.setTimeout.bind(w);
w.setTimeout = (fn, ms) => realTimer(fn, ms === 1000 ? 0 : ms);
w.URL.createObjectURL = () => `blob:video-${downloads.length}`;
w.URL.revokeObjectURL = url => revoked.push(url);
w.HTMLAnchorElement.prototype.click = function () {
  downloads.push({ name: this.download, href: this.href, target: this.target });
};
await fs.readFile(new URL('../frontend/video-direct-downloads.js', import.meta.url), 'utf8').then(s => w.eval(s));
const file = id => ({ id, url: `/api/video/${id}?download=true`, filename: `${id}.mp4`, size: 4 });
const response = () => new Response(new Uint8Array([1, 2, 3, 4]), {
  headers: { 'Content-Type': 'video/mp4', 'Content-Length': '4' }
});
let active = 0, maximum = 0;
w.fetch = async (url, options) => {
  calls.push(url); active++; maximum = Math.max(maximum, active);
  assert.equal(options.credentials, 'same-origin');
  assert.equal(options.redirect, 'error');
  await new Promise(r => setTimeout(r, 2));
  active--; return response();
};
const updates = [];
let result = await w.HeyVideoDownloads.run([file('a'), file('b'), file('c')], { onProgress: p => updates.push(p) });
assert.deepEqual([...result.succeeded], ['a', 'b', 'c']);
assert.equal(result.failed.length, 0);
assert.equal(maximum, 1, 'video requests must be sequential');
assert.equal(downloads.length, 3);
assert.equal(revoked.length, 3, 'every file buffer must be released');
assert(downloads.every(d => d.name.endsWith('.mp4') && d.target === ''));
assert(updates.some(p => p.received === 4 && p.index === 3));
assert(calls.every(url => !url.includes('.zip')));

w.fetch = async url => url.includes('/bad?')
  ? new Response(JSON.stringify({ message: '视频源文件暂时无法读取' }), { status: 502 })
  : response();
result = await w.HeyVideoDownloads.run([file('a'), file('bad'), file('c')]);
assert.deepEqual([...result.succeeded], ['a', 'c'], 'one failure must not stop later files');
assert.equal(result.failed[0].id, 'bad');
assert.match(result.failed[0].error, /源文件/);

w.fetch = async () => new Response(new Uint8Array([1, 2]), { headers: { 'Content-Type': 'video/mp4', 'Content-Length': '4' } });
result = await w.HeyVideoDownloads.run([file('short')]);
assert.equal(result.succeeded.length, 0);
assert.match(result.failed[0].error, /未接收完整/);
w.fetch = async () => new Response('<html>login</html>', { headers: { 'Content-Type': 'text/html' } });
result = await w.HeyVideoDownloads.run([file('html')]);
assert.equal(result.succeeded.length, 0, 'an HTML login page must never be saved as MP4');
let expired = 0, requests = 0;
w.fetch = async () => { requests++; return new Response('{}', { status: 401 }); };
result = await w.HeyVideoDownloads.run([file('a'), file('b')], { onAuthExpired: () => expired++ });
assert.equal(expired, 1); assert.equal(requests, 1); assert.equal(result.failed.length, 2);

// Exercise the production click handler, including refreshes and switching tasks mid-download.
const app = await fs.readFile(new URL('../frontend/app.js', import.meta.url), 'utf8');
const start = app.indexOf('function aiVideoDownloadFiles()');
const end = app.indexOf('document.querySelector("#garment-card-grid").addEventListener("change"', start);
assert(start > 0 && end > start);
w.eval(`var selectedAiVideoTaskId = "task-a", activeAiVideoDownload = null, aiVideoDownloadResults = new Map();
var aiVideoTaskDetail = {
  task: {id: "task-a", name: "任务一"},
  items: [{id:"item-a", name:"商品/一"}, {id:"item-b", name:"商品二"}],
  assets: [{id:"a",itemId:"item-a",assetType:"VIDEO_RESULT",sizeBytes:4},
    {id:"b",itemId:"item-b",assetType:"VIDEO_RESULT",sizeBytes:4},
    {id:"image",assetType:"STORYBOARD_GRID"}]
};
function aiVideoAssetUrl(task,id) { return "/api/v1/ai-video-tasks/"+task+"/assets/"+id; }
function handleSessionExpired() {}
${app.slice(start, end)}`);
const button = w.document.querySelector('button'), status = w.document.querySelector('p');
const batches = [];
let finish;
w.HeyVideoDownloads.run = (files, options) => {
  batches.push(files);
  options.onProgress({ index: 1, file: files[0], received: 2, total: 4 });
  return new Promise(resolve => { finish = resolve; });
};
w.renderAiVideoDownloadState();
assert.match(button.textContent, /逐个下载视频（2）/);
button.click();
assert.equal(batches.length, 1);
assert.equal(batches[0].length, 2);
assert.equal(new Set(batches[0].map(f => f.filename)).size, 2);
assert(batches[0].every(f => f.url.includes('download=true') && f.filename.endsWith('.mp4')));
w.renderAiVideoDownloadState();
assert(button.disabled, 'poll refresh must not unlock an active download');
button.click(); assert.equal(batches.length, 1);
w.eval('selectedAiVideoTaskId="task-b"; aiVideoTaskDetail={task:{id:"task-b",name:"任务二"},items:[],assets:[]};');
w.renderAiVideoDownloadState();
assert.match(status.textContent, /任务一/);
finish({ succeeded: ['a'], failed: [{ ...batches[0][1], error: '测试断网' }] });
await new Promise(r => setTimeout(r, 5));
assert(!status.textContent.includes('测试断网'), 'another task must not display this task result');
w.eval(`selectedAiVideoTaskId="task-a"; aiVideoTaskDetail={
task:{id:"task-a",name:"任务一"},items:[],assets:[
{id:"a",assetType:"VIDEO_RESULT"},{id:"b",assetType:"VIDEO_RESULT"}]};`);
w.renderAiVideoDownloadState();
assert.match(button.textContent, /重试失败视频（1）/);
button.click();
assert.deepEqual(Array.from(batches[1], f => f.id), ['b']);
finish({ succeeded: ['b'], failed: [] });
await new Promise(r => setTimeout(r, 5));
assert.match(status.textContent, /已发起 2 个视频下载/);
assert.match(status.textContent, /允许下载多个文件/);
dom.window.close();
console.log('PASS: sequential MP4 downloads, failure continuation/retry, integrity, login expiry, buffer release, task isolation, refresh safety.');
