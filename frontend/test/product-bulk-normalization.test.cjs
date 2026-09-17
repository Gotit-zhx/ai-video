const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app.js'), 'utf8');

function context() {
  class ApiRequestError extends Error {
    constructor(message, status, payload) { super(message); this.status = status; this.payload = payload; }
  }
  const calls = [];
  const c = {
    ApiRequestError,
    hasAnyPermission: () => true,
    productCapturePayload: value => value.capture ? { ...value.capture, ...value, capture: undefined } : value,
    productPublishWorkflowCreateMayHaveCommitted: error => !error.status || error.status === 408 || error.status >= 500,
    productCaptureApi: async (path, options) => {
      calls.push({ path, options });
      return { data: { ...capture(), ...JSON.parse(options.body), version: 8 } };
    }
  };
  vm.createContext(c);
  for (const [start, end] of [
    ['function productCaptureSkus(', 'function canonicalProductSkuImageSource('],
    ['function nullableProductValue(', 'function productSkuAttributesComplete('],
    ['const PRODUCT_SKU_SERVER_FIELDS =', 'function productSkuDrafts('],
    ['function productSkuPriceNormalizationNeeded(', 'async function saveProductCapture(']
  ]) vm.runInContext(source.slice(source.indexOf(start), source.indexOf(end)), c);
  return { c, calls };
}

function capture() {
  return {
    id: 'bulk-product', version: 7, status: 'CAPTURED', title: 'Source product', currency: 'CNY',
    priceMin: '1', priceMax: '1',
    skuCompleteness: { expectedCount: 2, capturedCount: 2, complete: true, source: 'DOM', issues: [] },
    skus: [
      { id: 'row-a', skuKey: 'key-a', organizationId: 'org', externalSkuId: 'external-a', price: '15.20', originalPrice: null,
        sellerSku: 'red-s', variationName: 'red, S', imageUrl: 'https://example.com/red.jpg', attributesJson: '{"color":"red","size":"S"}',
        source: { sourceVariantId: 'source-a', customOptions: ['red', 'S'] }, extraCaptureField: 'keep' },
      { id: 'row-b', externalSkuId: 'external-b', price: '27.90', originalPrice: '30.00', attributes: { color: 'blue' } }
    ]
  };
}

test('bulk normalization uses the supplied capture and preserves independent SKU prices and source metadata', async () => {
  const { c, calls } = context();
  const original = capture(), before = JSON.stringify(original);
  await c.normalizeProductCaptureForUpload(original, () => true);
  assert.equal(JSON.stringify(original), before);
  assert.equal(calls.length, 1);
  const patch = JSON.parse(calls[0].options.body);
  assert.equal(calls[0].path, '/bulk-product');
  assert.equal(patch.version, 7);
  assert.equal(patch.priceMin, '15.20');
  assert.equal(patch.priceMax, '27.90');
  assert.equal(patch.title, undefined);
  assert.equal(patch.currency, undefined);
  assert.equal(patch.skus[0].price, '15.20');
  assert.equal(patch.skus[1].price, '27.90');
  assert.equal(patch.skus[0].originalPrice, '15.20');
  assert.equal(patch.skus[0].originalPriceSource, 'DERIVED');
  assert.equal(patch.skus[1].originalPrice, '30.00');
  assert.equal(patch.skus[0].id, undefined);
  assert.equal(patch.skus[0].skuKey, undefined);
  assert.equal(patch.skus[0].organizationId, undefined);
  assert.equal(patch.skus[0].externalSkuId, 'external-a');
  assert.equal(patch.skus[0].imageUrl, original.skus[0].imageUrl);
  assert.deepEqual(patch.skus[0].attributes, { color: 'red', size: 'S' });
  assert.deepEqual(patch.skus[0].source, original.skus[0].source);
  assert.equal(patch.skus[0].extraCaptureField, 'keep');
  assert.deepEqual(patch.skuCompleteness, original.skuCompleteness);
});

test('range-only normalization preserves SKU identities by leaving the SKU collection untouched', async () => {
  const { c, calls } = context();
  const product = capture(); product.skus[0].originalPrice = '18';
  await c.normalizeProductCaptureForUpload(product, () => true);
  assert.deepEqual(JSON.parse(calls[0].options.body), { version: 7, priceMin: '15.20', priceMax: '27.90' });
});

test('already-normalized and non-editable products do not mutate', async () => {
  const { c, calls } = context();
  const product = capture(); product.skus[0].originalPrice = '18'; product.priceMin = '15.20'; product.priceMax = '27.90';
  assert.equal(await c.normalizeProductCaptureForUpload(product), product);
  product.status = 'APPROVED'; product.priceMin = '1';
  assert.equal(await c.normalizeProductCaptureForUpload(product), product);
  assert.equal(calls.length, 0);
});

test('lost PATCH response is recovered with one read and never repeats the mutation', async () => {
  const { c, calls } = context();
  const product = capture();
  const updated = { ...product, version: 8, priceMin: '15.20', priceMax: '27.90',
    skus: product.skus.map(sku => ({ ...sku, originalPrice: sku.originalPrice || sku.price })) };
  c.productCaptureApi = async (path, options) => {
    calls.push({ path, options });
    if (options) throw new c.ApiRequestError('response lost', 502);
    return { data: updated };
  };
  assert.equal(await c.normalizeProductCaptureForUpload(product), updated);
  assert.deepEqual(calls.map(call => call.options?.method || 'GET'), ['PATCH', 'GET']);
});

test('an unresolved version conflict keeps the original error instead of continuing with stale prices', async () => {
  const { c, calls } = context();
  const error = new c.ApiRequestError('changed by another request', 409);
  c.productCaptureApi = async (path, options) => {
    calls.push({ path, options });
    if (options) throw error;
    return { data: capture() };
  };
  await assert.rejects(c.normalizeProductCaptureForUpload(capture()), value => value === error);
  assert.equal(calls.length, 2);
});

test('organization changes and revoked editing permission prevent further normalization', async () => {
  const { c, calls } = context();
  await assert.rejects(c.normalizeProductCaptureForUpload(capture(), () => false), /企业已切换/);
  assert.equal(calls.length, 0);
  c.hasAnyPermission = () => false;
  await assert.rejects(c.normalizeProductCaptureForUpload(capture()), error => error.status === 403);
  assert.equal(calls.length, 0);
  c.hasAnyPermission = () => true;
  let current = true;
  c.productCaptureApi = async () => { current = false; return { data: capture() }; };
  await assert.rejects(c.normalizeProductCaptureForUpload(capture(), () => current), /企业已切换/);
});

test('a missing source SKU price is not fabricated as a zero price', async () => {
  const { c, calls } = context();
  const product = capture(); product.skus[1].price = null; product.skus[1].originalPrice = null;
  await c.normalizeProductCaptureForUpload(product);
  const patch = JSON.parse(calls[0].options.body);
  assert.equal(patch.priceMin, '15.20'); assert.equal(patch.priceMax, '15.20');
  assert.equal(patch.skus[1].price, null); assert.equal(patch.skus[1].originalPrice, null);
});
