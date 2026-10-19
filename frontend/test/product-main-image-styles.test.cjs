const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const bulk = require('../product-bulk-upload.js');

test('no template is the default and omits generation options entirely', () => {
  const items = bulk.plan([{id:'one',title:'Product'}], 'shop', 'request-key');
  assert.deepEqual(bulk.stylePayload(items[0]), {});
  assert.deepEqual(bulk.stylePayload({mainImageStyleTemplateId:''}), {});
  assert.equal(Object.hasOwn(items[0], 'mainImageStyleTemplateId'), false);
});
test('style choice is only a live template id, never a snapshot or manual approval', () => {
  assert.deepEqual(bulk.stylePayload({mainImageStyleTemplateId:'style-1'}),
    {mainImageStyleTemplateId:'style-1'});
});
test('style changes and clearing a choice apply only to unsent products', () => {
  const pending = {captureId:'one',title:'One',shopId:'shop',status:'PREPARE_FAILED',mainImageStyleTemplateId:'old'};
  const submitted = {captureId:'two',status:'CREATE_FAILED',payload:{mainImageStyleTemplateId:'old'},idempotencyKey:'old-key'};
  const accepted = {captureId:'three',workflow:{id:'wf'}};
  const changed = bulk.changeStyle([pending,submitted,accepted], 'new', 'request-new');
  assert.equal(changed[0].mainImageStyleTemplateId,'new');
  assert.equal(changed[0].status,'WAITING');
  assert.equal(changed[1],submitted);
  assert.equal(changed[2],accepted);
  const cleared = bulk.changeStyle(changed,'','request-clear');
  assert.deepEqual(bulk.stylePayload(cleared[0]), {});
  assert.equal(cleared[1],submitted);
});
test('changing shops preserves the optional style choice', () => {
  const changed = bulk.changeShop([{captureId:'one',shopId:'old',status:'WAITING',mainImageStyleTemplateId:'style'}], 'new','request');
  assert.equal(changed[0].mainImageStyleTemplateId,'style');
});
test('multi-shop changes preserve style, chart and thumbnail without rewriting submitted requests', () => {
  const pending = {captureId:'one',title:'One',shopId:'old',status:'WAITING',
    mainImageStyleTemplateId:'style',sizeChartImageKey:'chart',thumbnailUrl:'thumb'};
  const accepted = {captureId:'two',title:'Two',shopId:'old',workflow:{id:'wf'},
    mainImageStyleTemplateId:'other',sizeChartImageKey:'other-chart'};
  const changed = bulk.changeShops([pending, accepted], ['new','second'], 'request');
  assert.equal(changed.find(item => item.workflow?.id), accepted);
  for (const item of changed.filter(item => item.captureId === 'one')) {
    assert.equal(item.mainImageStyleTemplateId,'style');
    assert.equal(item.sizeChartImageKey,'chart');
    assert.equal(item.thumbnailUrl,'thumb');
  }
  assert.equal(changed.filter(item => item.captureId === 'one').length, 2);
  const styled = bulk.changeStyle(changed, 'replacement', 'request-style');
  assert.ok(styled.filter(bulk.canChangeShop).every(item => item.sizeChartImageKey));
  assert.equal(styled.find(item => item.workflow?.id), accepted);
  const single = bulk.changeShop([pending], 'new', 'request-single')[0];
  assert.equal(single.sizeChartImageKey, 'chart');
  assert.equal(single.thumbnailUrl, 'thumb');
});
test('navigation, optional selection and assets are wired into both frontend images', () => {
  const html = fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
  assert.match(html, /data-commerce-route="product-main-image-styles"/);
  assert.match(html, /id="product-bulk-main-style"><option value="">不使用风格模板/);
  assert.doesNotMatch(html.match(/<select id="product-bulk-main-style"[^>]*>/)[0], /required/);
  for (const file of ['Dockerfile','Dockerfile.local']) {
    const docker = fs.readFileSync(path.join(__dirname,'..',file),'utf8');
    assert.match(docker, /COPY product-main-image-styles\.js product-main-image-styles\.css/);
  }
});
