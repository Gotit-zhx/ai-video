const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8');
function functionSource(name, next) {
  return source.slice(source.indexOf(`function ${name}(`), source.indexOf(`\nfunction ${next}(`));
}
const escapeHtml = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function harness() {
  const context = {escapeHtml, productPublishWorkflowIsActive: workflow => ['PENDING','RUNNING'].includes(workflow?.status),
    safeProductUrl: value => {
      if (!value) return '';
      try {const url = new URL(value, 'https://fixture.invalid'); return ['https:','http:'].includes(url.protocol) ? url.href : '';} catch {return '';}
    }};
  vm.createContext(context);
  vm.runInContext(functionSource('renderProductMainImageProgress', 'renderProductUploadResult'), context);
  return context;
}
const workflow = () => ({status:'RUNNING', currentStep:'STYLE_IMAGES', mainImageStyle:{
  templateName:'泰国A', totalImages:4, processedImages:1, imageUrls:['/api/v1/product-main-image-results/original'],
  images:[
    {index:0,status:'SUBMITTING',stage:'GENERATING',attempt:0,elapsedSeconds:42},
    {index:1,status:'RESULT_RECEIVED',stage:'REVIEW_MODEL_STREAMING',attempt:1,elapsedSeconds:13,outputCharacters:123},
    {index:2,status:'PREPARED',stage:'QUEUED',attempt:0},
    {index:3,status:'READY',stage:'PRESERVED',mode:'PRESERVE_ORIGINAL',imageUrl:'/api/v1/product-main-image-results/original'}
  ]}});

test('renders each ordered slot, actual stages, elapsed time and preserved original', () => {
  const html = harness().renderProductMainImageProgress(workflow());
  for (const text of ['泰国A','1 / 4 张就绪','第 1 张','第 2 张','第 3 张','第 4 张','正在生成',
    '本阶段已用 42 秒','正在返回检查结果','检查已返回 123 字符','排队中','原样保留 1 张']) assert.ok(html.includes(text),text);
  assert.match(html, /<progress value="1" max="4"/);
  assert.equal((html.match(/<li /g)||[]).length,4);
  assert.equal((html.match(/<img /g)||[]).length,1);
  assert.doesNotMatch(html, /%/);
  assert.ok(html.indexOf('第 1 张</strong>') < html.indexOf('第 4 张</strong>'));
});
test('a poll updates the active stage even when completed count has not changed', () => {
  const h=harness(), current=workflow();
  const before=h.renderProductMainImageProgress(current);
  current.mainImageStyle.images[0]={index:0,status:'RESULT_RECEIVED',stage:'REVIEW_MODEL_WAITING',elapsedSeconds:2};
  const after=h.renderProductMainImageProgress(current);
  assert.notEqual(before,after);
  assert.match(after,/1 \/ 4 张就绪/);
  assert.match(after,/第 1 张 · 正在检查商品与版式/);
  assert.doesNotMatch(after,/本阶段已用 42 秒/);
});
test('failed workflows show stopped stages and reasons, not active progress', () => {
  const current=workflow(); current.status='FAILED';
  current.mainImageStyle.images[0].stage='STOPPED';
  current.mainImageStyle.images[0].errorMessage='检查超时';
  current.mainImageStyle.images[1].stage='FAILED';
  const html=harness().renderProductMainImageProgress(current,true);
  assert.match(html,/is-compact/);
  assert.match(html,/已停止/);
  assert.match(html,/处理失败/);
  assert.match(html,/检查超时/);
  assert.match(html,/未完成 2 张/);
  assert.doesNotMatch(html,/main-style-workflow-current/);
});
test('opt-out is empty; older responses retain true aggregate and previews', () => {
  const h=harness();
  assert.equal(h.renderProductMainImageProgress({}), '');
  const current=workflow(); delete current.mainImageStyle.images;
  const html=h.renderProductMainImageProgress(current);
  assert.match(html,/1 \/ 4 张就绪/);
  assert.match(html,/<img /);
  assert.doesNotMatch(html,/正在生成|本阶段已用|main-style-workflow-list/);
});
test('untrusted labels and errors are escaped; non-http result links never render', () => {
  const current=workflow();
  current.mainImageStyle.templateName='<script>alert(1)</script>';
  current.mainImageStyle.images[0].errorMessage='<img onerror="alert(1)">';
  current.mainImageStyle.images[3].imageUrl='javascript:alert(1)';
  const html=harness().renderProductMainImageProgress(current);
  assert.doesNotMatch(html,/<script|<img |javascript:/);
  assert.match(html,/&lt;script&gt;/);
  assert.match(html,/&lt;img onerror=&quot;/);
});
test('ready results after a correction have previews without invented elapsed time', () => {
  const current=workflow();
  current.status='SUCCEEDED';
  current.mainImageStyle.images=[{index:0,status:'READY',stage:'READY',attempt:1,elapsedSeconds:99,imageUrl:'/api/v1/product-main-image-results/ready'}];
  const html=harness().renderProductMainImageProgress(current);
  assert.match(html,/生成完成/);
  assert.doesNotMatch(html,/自动修正/);
  assert.match(html,/alt="第 1 张风格主图"/);
  assert.doesNotMatch(html,/本阶段已用/);
});
test('generation-only progress goes from saving to ready without review or correction labels', () => {
  const h=harness(), current=workflow();
  current.mainImageStyle.images=[
    {index:0,status:'RESULT_RECEIVED',stage:'SAVING',attempt:1},
    {index:1,status:'PREPARED',stage:'QUEUED',attempt:0}
  ];
  const saving=h.renderProductMainImageProgress(current);
  assert.match(saving,/正在保存生成结果/);
  assert.doesNotMatch(saving,/检查|验收|自动修正/);
  Object.assign(current.mainImageStyle.images[0],{status:'READY',stage:'READY',imageUrl:'/api/v1/product-main-image-results/saved'});
  const ready=h.renderProductMainImageProgress(current);
  assert.match(ready,/生成完成/);
  assert.match(ready,/<img /);
  assert.doesNotMatch(ready,/检查|验收|自动修正/);
});
test('product detail, publish dialog and bulk details all render the same live progress', () => {
  assert.match(functionSource('renderProductUploadResult','syncProductActions'), /replaceHtmlPreservingMedia\(el,[\s\S]*renderProductMainImageProgress\(workflow\)/);
  const dialog=source.slice(source.indexOf('function renderProductPublishWorkflow('),source.indexOf('async function refreshProductCaptureAfterWorkflow('));
  assert.match(dialog,/replaceHtmlPreservingMedia\(styleProgress, renderProductMainImageProgress\(workflow\)\)/);
  assert.match(functionSource('renderProductBulkCard','productBulkShopIds'),/renderProductMainImageProgress\(workflow, true\)/);
  const polling=source.slice(source.indexOf('function pollProductPublishWorkflow('),source.indexOf('async function restoreProductPublishWorkflow('));
  assert.match(polling,/selectedProductCaptureId !== captureId/);
  assert.match(polling,/currentUser.organizationId !== organizationId/);
  assert.match(polling,/以上为最近一次状态/);
});
