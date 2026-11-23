const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const { resolve } = require('node:path');
const { JSDOM } = require('../../scripts/ci/qa/node_modules/jsdom');

const html = readFileSync(resolve(__dirname, '../index.html'), 'utf8');
const source = readFileSync(resolve(__dirname, '../product-print-capture.js'), 'utf8');
const captureId = '11111111-2222-4333-8444-555555555555';
const endpoint = '/api/v1/product-print-capture';

async function workspace(t, respond) {
  const dom = new JSDOM(html, {
    url: `https://example.test/#product-print-capture?capture=${captureId}`,
    runScripts: 'outside-only'
  });
  t.after(() => dom.window.close());
  const { window } = dom;
  const get = name => window.document.querySelector(`#pc-${name}`);
  const submissions = [];
  const flush = () => new Promise(setImmediate);
  window.currentUser = { organizationId: 'company', userId: 'user' };
  window.hasAnyPermission = () => true;
  window.api = async (path, options) => {
    if (path === endpoint) return { success: true, data: [] };
    if (path === `${endpoint}/${captureId}`) return { success: true, data: {
      captureId, title: '示例', images: [{ id: 'photo-a', url: '/a.png' }, { id: 'photo-b', url: '/b.png' }]
    } };
    assert.equal(path, `${endpoint}/${captureId}/extract`);
    submissions.push(options);
    return respond(submissions.length);
  };

  window.document.querySelector('#print-capture-workspace').hidden = false;
  window.eval(source);
  await flush();

  return {
    get,
    submissions,
    select: index => get('source-images').querySelectorAll('input')[index].click(),
    extract: async () => {
      if (!get('confirm-model').checked) get('confirm-model').click();
      get('extract').click();
      await flush();
    }
  };
}

test('a provider refusal stops extraction for that image and allows another image to be selected', async t => {
  const app = await workspace(t, () => {
    throw Object.assign(new Error('图片内容未通过服务商审核，本次未生成图片；同一请求不会自动重试生图'), { status: 409 });
  });
  await app.extract();
  assert.equal(app.get('monitor-print').textContent, '提取已拒绝');
  assert.match(app.get('extract-status').textContent, /未通过服务商审核.*有权使用的其他商品图/);
  assert.equal(app.get('extract-action').hidden, true);
  await app.extract();
  assert.equal(app.submissions.length, 1);

  app.select(1);
  assert.equal(app.get('extract-action').hidden, false);
  assert.equal(app.get('confirm-model').checked, false);
  assert.equal(app.get('extract-status').hidden, true);

  app.select(0);
  assert.equal(app.get('extract-action').hidden, true);
  assert.equal(app.get('monitor-print').textContent, '提取已拒绝');
  assert.equal(app.submissions.length, 1);
});

test('an unconfirmed outcome can still be checked with the same request key', async t => {
  const app = await workspace(t, () => ({ success: true, data: { status: 'UNKNOWN' } }));
  await app.extract();
  assert.equal(app.get('extract-action').hidden, false);
  assert.equal(app.get('extract').textContent, '查看提取结果');
  await app.extract();
  assert.equal(app.submissions.length, 2);
  assert.equal(app.submissions[0].headers['Idempotency-Key'], app.submissions[1].headers['Idempotency-Key']);
});
