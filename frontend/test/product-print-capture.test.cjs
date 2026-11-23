const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const { resolve } = require('node:path');
const { JSDOM } = require('../../scripts/ci/qa/node_modules/jsdom');

const html = readFileSync(resolve(__dirname, '../index.html'), 'utf8');
const source = readFileSync(resolve(__dirname, '../product-print-capture.js'), 'utf8');
const captureId = '11111111-2222-4333-8444-555555555555';
const endpoint = '/api/v1/product-print-capture';
const flush = () => new Promise(setImmediate);
const photos = ['a', 'b', 'c'].map(id => ({id, url: `/${id}.png`}));
const result = imageId => ({id: `print-${imageId}`, imageId, status: 'READY', imageUrl: `/print-${imageId}.png`});

const shortSleeve = {id: 'style', name: '短袖', variants: [{image: '/base.png', styleCode: 'A01'}]};

async function workspace(t, submit, history = [], styles = {ready: [shortSleeve], editable: [shortSleeve]}) {
  const dom = new JSDOM(html, {
    url: `https://example.test/#product-print-capture?capture=${captureId}`,
    runScripts: 'outside-only'
  });
  t.after(() => dom.window.close());
  const { window } = dom;
  const get = name => window.document.querySelector(`#pc-${name}`);
  const submissions = [];
  window.currentUser = { organizationId: 'company', userId: 'user' };
  window.hasAnyPermission = () => true;
  window.Image = class {
    naturalWidth = 2;
    naturalHeight = 2;
    async decode() {}
  };
  window.HTMLCanvasElement.prototype.getContext = () => ({
    drawImage() {}, clearRect() {}, getImageData: () => ({data: new Uint8Array(16).fill(255)})
  });
  window.api = async (path, options) => {
    const success = data => ({success: true, data});
    if (path === endpoint) return success([]);
    if (path === `${endpoint}/${captureId}`) return success({captureId, title: '示例', images: photos});
    if (path === `${endpoint}/${captureId}/results`) return success(history);
    if (path.startsWith('/api/v1/product-capture-templates?')) {
      const items = path.includes('readyOnly=true') ? styles.ready : styles.editable;
      if (!items) throw new Error('款式暂不可用');
      return success({total: items.length, items});
    }
    assert.equal(path, `${endpoint}/${captureId}/extract`);
    const imageId = JSON.parse(options.body).imageId;
    assert.equal(JSON.parse(options.body).confirmed, true);
    submissions.push({imageId, key: options.headers['Idempotency-Key']});
    return success(await submit(imageId, history));
  };
  window.document.querySelector('#print-capture-workspace').hidden = false;
  window.eval(source);
  await flush();

  return {
    get,
    submissions,
    resultButtons: () => [...get('results').querySelectorAll('[data-result]')],
    sourceImages: () => [...get('source-images').querySelectorAll('input')],
    extract: async () => {
      get('confirm-model').click();
      get('extract').click();
      await flush();
    },
    chooseStyle: async (id = 'style') => {
      get('style').value = id;
      get('style').dispatchEvent(new window.Event('change'));
      await flush();
    }
  };
}

test('a batch continues after one refusal and completed prints remain selectable', async t => {
  const app = await workspace(t, (imageId, history) => {
    if (imageId === 'b') {
      history.push({id: 'rejection-b', imageId, status: 'REJECTED', message: '图片内容未通过服务商审核'});
      throw Object.assign(new Error('图片内容未通过服务商审核；同一请求不会自动重试生图'), {status: 409});
    }
    const saved = result(imageId);
    history.push(saved);
    return saved;
  });
  for (const input of app.sourceImages()) input.click();
  assert.equal(app.get('extract').textContent, '提取 3 张');
  assert.equal(app.get('extract').disabled, true);
  await app.extract();
  assert.deepEqual(app.submissions.map(entry => entry.imageId), ['a', 'b', 'c']);
  assert.equal(new Set(app.submissions.map(entry => entry.key)).size, 3);
  assert.match(app.get('results').textContent, /未通过服务商审核/);
  assert.equal(app.resultButtons().length, 2);
  assert.equal(app.get('confirm-model').checked, false);
  await app.chooseStyle();
  assert.equal(app.get('canvas').hidden, false);
  app.resultButtons()[1].click();
  await flush();
  assert.equal(app.get('canvas').hidden, false);
  assert.equal(app.get('style').value, 'style');
  assert.equal(app.resultButtons()[1].getAttribute('aria-pressed'), 'true');
  app.sourceImages()[0].click();
  await flush();
  assert.equal(app.resultButtons()[0].getAttribute('aria-pressed'), 'true');
  assert.equal(app.get('extract').disabled, true);
  assert.equal(app.submissions.length, 3);
});

test('opening a saved capture restores ready and uncertain results without calling the model', async t => {
  const app = await workspace(t, () => assert.fail('prior extraction must not submit again'), [
    result('a'), {id: 'pending-b', imageId: 'b', status: 'UNKNOWN'}
  ]);
  assert.equal(app.resultButtons()[0].dataset.result, 'print-a');
  assert.match(app.get('results').textContent, /结果待核查/);
  assert.equal(app.sourceImages()[1].disabled, true);
  app.get('refresh-results').click();
  await flush();
  assert.equal(app.resultButtons().length, 1);
  assert.equal(app.submissions.length, 0);
});

test('a saved print is previewed before a style is selected, even without a usable style', async t => {
  const app = await workspace(t, () => assert.fail('history is read only'), [result('a')], {ready: [], editable: []});
  assert.equal(app.get('canvas').hidden, false);
  assert.equal(app.get('canvas').dataset.preview, 'artwork');
  assert.equal(app.get('composition-heading').textContent, '印花预览');
  assert.equal(app.get('arrange').disabled, true);
  assert.equal(app.get('download').disabled, true);
  assert.match(app.get('template-state').textContent, /上传款式底图/);
});

test('a draft with a base image can be selected before its style name is filled in', async t => {
  const draft = {id: 'draft', draft: true, name: 'txu', variants: [{name: '', image: '/draft.png', styleCode: 'B02'}]};
  const app = await workspace(t, () => assert.fail('style selection is read only'), [result('a')], {
    ready: [shortSleeve],
    editable: [
      {...shortSleeve, draft: true, name: '未完成修改', variants: []},
      draft,
      {id: 'empty-draft', draft: true, name: '没有底图', variants: [{}]}
    ]
  });
  const labels = [...app.get('style').options].map(option => option.textContent);
  assert.deepEqual(labels, ['选择款式', '短袖 · A01', 'txu · B02 · 草稿']);
  await app.chooseStyle('draft');
  assert.equal(app.get('canvas').dataset.preview, 'composition');
  assert.equal(app.get('download').disabled, false);
  await app.chooseStyle('');
  assert.equal(app.get('canvas').dataset.preview, 'artwork');
  assert.equal(app.get('download').disabled, true);
});

test('a template read failure does not hide a loaded print', async t => {
  const app = await workspace(t, () => assert.fail('history is read only'), [result('a')], {ready: [], editable: null});
  assert.equal(app.get('canvas').hidden, false);
  assert.equal(app.get('canvas').dataset.preview, 'artwork');
  assert.match(app.get('template-state').textContent, /款式读取失败/);
});

test('a network failure without a saved attempt can be retried with the same key', async t => {
  let calls = 0;
  const app = await workspace(t, imageId => {
    if (++calls === 1) throw new Error('网络异常');
    return result(imageId);
  });
  app.sourceImages()[0].click();
  await app.extract();
  assert.match(app.get('results').textContent, /网络异常/);
  app.sourceImages()[0].click();
  await app.extract();
  assert.equal(app.submissions.length, 2);
  assert.equal(app.submissions[0].key, app.submissions[1].key);
  assert.equal(app.resultButtons().length, 1);
});
