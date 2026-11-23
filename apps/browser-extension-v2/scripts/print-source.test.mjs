import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const worker = readFileSync(new URL('../../browser-extension/src/background/service-worker.js', import.meta.url), 'utf8')
  .replace(/^import[\s\S]*?from\s+["'][^"']+["'];\n/gm, '');
const productUrl = 'https://shopee.ph/product/105/209';
const captureId = '11111111-2222-4333-8444-555555555555';
const sender = { id: 'print-extension', frameId: 0, url: productUrl, tab: { id: 1, url: productUrl } };
const message = {
  type: 'HEYI_CAPTURE_PRINT_SOURCE',
  payload: {
    sourceUrl: 'https://shopee.ph/product/400/999',
    title: ' 花朵印花 ',
    images: ['https://cdn.example.com/first.png', 'http://cdn.example.com/invalid.png', 'https://cdn.example.com/first.png'],
    skus: [{ id: 'excluded' }],
    videos: ['excluded']
  }
};

function startWorker(shortName) {
  const requests = [];
  const tabs = [];
  let listener;
  const noop = () => {};
  const context = vm.createContext({
    URL,
    chrome: {
      runtime: {
        id: sender.id,
        getManifest: () => ({ short_name: shortName }),
        onInstalled: { addListener: noop },
        onStartup: { addListener: noop },
        onMessage: { addListener: callback => { listener = callback; } }
      },
      storage: {},
      tabs: { create: async options => tabs.push(options.url) }
    },
    MESSAGE_TYPES: { CAPTURE: 'HEYI_CAPTURE_PRODUCT' },
    ApiError: class extends Error {
      constructor(message, status, code) {
        super(message);
        this.status = status;
        this.code = code;
      }
    },
    readAuth: async () => ({ baseUrl: 'https://hoyee.org.cn' }),
    restrictStorageToExtensionPages: async () => {},
    createPrintSource: async payload => {
      requests.push(JSON.parse(JSON.stringify(payload)));
      return { id: captureId };
    }
  });
  vm.runInContext(worker, context);
  return {
    requests, tabs,
    send: (request, from = sender) => new Promise(resolve => listener(request, from, resolve))
  };
}

test('v2 sends the active product identity and images, then opens the matching capture record', async () => {
  const { send, requests, tabs } = startWorker('赫逸印花采集 v2.0');
  const response = await send(message);
  assert.equal(response.ok, true);
  assert.equal(response.data.id, captureId);
  assert.deepEqual(requests, [{ sourceUrl: productUrl, title: '花朵印花', images: ['https://cdn.example.com/first.png'] }]);
  assert.deepEqual(tabs, [`https://hoyee.org.cn/#product-print-capture?capture=${captureId}`]);
});

test('the two editions only handle their own capture messages and require the active top frame', async () => {
  const legacy = startWorker('赫逸商品一键采集');
  assert.equal((await legacy.send(message)).error.code, 'WRONG_EDITION');
  const v2 = startWorker('赫逸印花采集 v2.0');
  assert.equal((await v2.send({ type: 'HEYI_CAPTURE_PRODUCT' })).error.code, 'WRONG_EDITION');
  assert.equal((await v2.send(message, { ...sender, frameId: 1 })).error.code, 'CONTENT_SCRIPT_REQUIRED');
  assert.equal((await v2.send(message, { ...sender, id: 'unknown' })).error.code, 'UNTRUSTED_SENDER');
  assert.equal(v2.requests.length, 0);
  assert.equal(legacy.requests.length, 0);
});
