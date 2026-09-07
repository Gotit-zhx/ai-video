const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../commerce-planner.js'), 'utf8');

function setup(api) {
  const handlers = {};
  function element() {
    return { textContent: '', children: [], append(e) { this.children.push(e); },
      replaceChildren() { this.children = []; this.textContent = ''; } };
  }
  const result = element();
  const button = { disabled: false };
  const form = { addEventListener(name, handler) { handlers[name] = handler; },
    elements: { namedItem: () => ({ checked: false }) }, querySelector: () => button };
  const values = { mode: 'POD_DESIGN', platform: 'TIKTOK_SHOP', market: 'PH', sourceProducts: '30',
    designsPerProduct: '10', imagesPerDesign: '1', shopCount: '6', dailyListingsPerShop: '50',
    dailyBudgetPerShop: '1000', observationDays: '7', targetRoi: '6.5' };
  vm.runInNewContext(source, { document: { getElementById: id => id === 'commerce-plan-form' ? form : id === 'commerce-plan-result' ? result : null,
    createElement: element }, FormData: function() { return Object.entries(values); }, api, Intl });
  return { handlers, result, button };
}
const plan = { designVersions: 300, materialImages: 300, listingDrafts: 300, minimumPublishingDays: 1,
  currency: 'PHP', dailyBudgetAllShops: 6000, observationBudgetCeiling: 42000, stages: ['采集', '审核'],
  schedule: [{ day: 1, listingsPerShop: [50,50,50,50,50,50] }], blockers: ['<img src=x onerror=alert(1)>'], warnings: ['仅预览'] };

test('only calls preview API and renders returned strings as text', async () => {
  const calls = [];
  const s = setup(async (url, options) => { calls.push({ url, options }); return { data: plan }; });
  await s.handlers.submit({ preventDefault() {} });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/v1/commerce-plans/preview');
  const sent = JSON.parse(calls[0].options.body);
  assert.equal(sent.sourceProducts, 30); assert.equal(sent.rightsConfirmed, false);
  assert.equal(s.button.disabled, false);
  assert.ok(JSON.stringify(s.result).includes('尚未创建任务'));
  assert.ok(JSON.stringify(s.result).includes('<img src=x onerror=alert(1)>'));
  assert.ok(!source.includes('innerHTML'));
});
test('editing invalidates an in-flight preview', async () => {
  let finish;
  const s = setup(() => new Promise(resolve => { finish = resolve; }));
  const pending = s.handlers.submit({ preventDefault() {} });
  s.handlers.input();
  finish({ data: plan }); await pending;
  assert.match(s.result.textContent, /配置已变更/);
  assert.equal(s.result.children.length, 0);
  assert.equal(s.button.disabled, false);
});
test('permission failures remain errors, never success previews', async () => {
  const s = setup(async () => { throw new Error('没有权限'); });
  await s.handlers.submit({ preventDefault() {} });
  assert.equal(s.result.textContent, '没有权限');
  assert.equal(s.button.disabled, false);
});
