import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../app.js', import.meta.url), 'utf8');
const start = source.indexOf('function aiVideoImagePresentation(');
const end = source.indexOf('\nfunction isAiVideoCopyrightError', start);
const context = vm.createContext({ escapeHtml: value => String(value).replaceAll('<', '&lt;'), aiVideoPrimaryAssetType: () => 'MODEL_IMAGE' });
vm.runInContext(source.slice(start, end), context);
const state = (status, model = null, grid = null, errorMessage = '', taskStatus = 'VIDEO_GENERATING') =>
  context.aiVideoImagePresentation({ status: taskStatus }, { status, errorMessage }, model, grid);

test('legacy persisted downloads keep their status without inventing generation counts', () => {
  const item = { status: 'FAILED', imageDownloadPending: true,
    pendingImageAssetType: 'STORYBOARD_GRID', pendingImageDownloadAttempts: 4 };
  const p = context.aiVideoImagePresentation({ status: 'VIDEO_GENERATING' }, item, {}, null);
  assert.equal(p.active, true);
  assert.equal(p.shotState, 'processing');
  assert.equal(p.storyboardLabel, '下载图片中 · 已尝试 4 次');
  assert.equal(context.aiVideoImageAttemptDetails(p.gridStep), '历史次数未记录');
  item.imageProgressJson = JSON.stringify({ STORYBOARD_GRID: { stage: 'VALIDATING', generationAttempts: 1 } });
  assert.equal(context.aiVideoImagePresentation({}, item, {}, null).storyboardLabel, '正在校验');
});

test('image worker stays active after its parent has entered video generation', () => {
  const result = state('GENERATING_STORYBOARD', {});
  assert.equal(result.active, true);
  assert.equal(result.shotState, 'processing');
  assert.equal(result.storyboardLabel, '准备分镜素材');
  assert.equal(result.modelState, 'completed');
});
test('queued primary image does not claim its storyboard has started', () => {
  assert.equal(state('READY', null, null, '', 'IMAGE_GENERATING').storyboardLabel, '等待主图');
});
test('only an existing output can be shown as completed', () => {
  assert.equal(state('IMAGE_READY').shotState, 'waiting');
  assert.equal(state('IMAGE_READY', {}, {}).shotState, 'completed');
});
test('automatic retry differs from terminal failure and keeps completed model', () => {
  const retry = state('FAILED', {}, null, '图片自动恢复等待重试：下载失败');
  assert.equal(retry.shotState, 'retrying');
  assert.equal(retry.modelState, 'completed');
  assert.equal(retry.active, true);
  assert.equal(state('FAILED', {}, null, '结构校验未通过').shotState, 'failed');
});
test('video stage and finished images never look like image generation', () => {
  assert.equal(state('VIDEO_SUBMITTING', {}, {}).active, false);
  assert.equal(state('IMAGE_READY', {}, {}, '', 'IMAGE_GENERATING').active, false);
});
test('placeholder has readable status instead of nine blank tiles and escapes content', () => {
  const html = context.aiVideoImagePlaceholder('<生成>', '校验完成后自动展示');
  assert.ok(html.includes('role="status"'));
  assert.ok(html.includes('&lt;生成>'));
  assert.ok(!html.includes('<i'));
  assert.ok(!html.includes('%'));
  assert.ok(source.includes('${storyboard ? "" : "disabled"}'));
  assert.ok(!source.includes('`${itemState} · ${Number(item.progress || 0)}%`'));
});

test('actual generation and validation stages are distinct and retries exclude downloads', () => {
  const item = {status:'GENERATING_STORYBOARD',imageProgressJson:JSON.stringify({STORYBOARD_GRID:{stage:'VALIDATING',generationAttempts:2,validationAttempts:3,downloadAttempts:5}})};
  const p=context.aiVideoImagePresentation({status:'VIDEO_GENERATING'},item,{},null);
  assert.equal(p.storyboardLabel,'正在校验');
  assert.ok(context.aiVideoImageAttemptDetails(p.gridStep).includes('重试 1 次'));
  assert.ok(context.aiVideoImageAttemptDetails(p.gridStep).includes('下载 5 次'));
  item.imageProgressJson=JSON.stringify({STORYBOARD_GRID:{stage:'GENERATING',generationAttempts:3}});
  assert.equal(context.aiVideoImagePresentation({status:'VIDEO_GENERATING'},item,{},null).storyboardLabel,'正在生图');
  item.imageProgressJson=JSON.stringify({STORYBOARD_GRID:{stage:'VALIDATION_RETRY_WAIT',generationAttempts:3}});
  const waiting=context.aiVideoImagePresentation({status:'VIDEO_GENERATING'},item,{},null);
  assert.equal(waiting.storyboardLabel,'校验等待重试');
  assert.equal(waiting.shotState,'retrying');
});
