import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFileSync } from "node:fs";
import vm from "node:vm";

let source = readFileSync(new URL("../src/background/service-worker.js", import.meta.url), "utf8")
  .replace(/^import[\s\S]*?from\s+["'][^"']+["'];\n/gm, "");
source += `
globalThis.HeyiSanitizeTest = {
  safeNumber, safeInteger, boundedNumber, sanitizeSkus, sanitizeSkuCompleteness,
  sanitizeImageCompleteness, sanitizeCapture, idempotencyKey
};`;

const noop = () => {};
const chrome = {
  runtime: {
    id: "test-extension",
    onInstalled: { addListener: noop },
    onStartup: { addListener: noop },
    onMessage: { addListener: noop },
    getURL: value => value
  },
  storage: {
    session: {
      setAccessLevel: async () => {},
      get: async () => ({}),
      remove: async () => {}
    }
  },
  permissions: {},
  action: {},
  tabs: {}
};
const context = vm.createContext({
  URL,
  URLSearchParams,
  TextEncoder,
  crypto: webcrypto,
  Date,
  navigator: {},
  chrome,
  MESSAGE_TYPES: {},
  normalizePlatformUrl: value => value,
  originPattern: value => value,
  safeAuthView: () => ({}),
  ApiError: class ApiError extends Error {},
  createCapture: async () => {},
  exchangePairingCode: async () => {},
  logoutCurrentDevice: async () => {},
  refreshCurrentUser: async () => {},
  clearAuth: async () => {},
  readAuth: async () => null,
  readSettings: async () => ({}),
  restrictStorageToExtensionPages: async () => {},
  writeSettings: async () => ({})
});
vm.runInContext(readFileSync(new URL("../src/shared/video-source.js", import.meta.url), "utf8"), context);
context.deduplicateVideos = context.HeyiVideoSources.deduplicate;
vm.runInContext(source, context);

const {
  safeNumber, safeInteger, boundedNumber, sanitizeSkus, sanitizeSkuCompleteness,
  sanitizeImageCompleteness, sanitizeCapture, idempotencyKey
} = context.HeyiSanitizeTest;
for (const missing of [null, undefined, "", "   "]) {
  assert.equal(safeNumber(missing), null);
  assert.equal(safeInteger(missing), null);
}
assert.equal(safeNumber(0), 0);
assert.equal(safeNumber("12.50"), 12.5);
assert.equal(safeInteger("12"), 12);
for (const missing of [null, undefined, "", "   "]) {
  assert.equal(boundedNumber(missing, 0, 5), null);
}
assert.equal(boundedNumber("4.8", 0, 5), 4.8);

const missingPriceRaw = [{
  externalSkuId: "model-1",
  price: null,
  originalPrice: null,
  stock: 2,
  imageUrl: "https://cf.shopee.ph/file/model-1.webp"
}];
const missingPriceSkus = sanitizeSkus(missingPriceRaw);
assert.equal(missingPriceSkus[0].price, null);
assert.equal(missingPriceSkus[0].originalPrice, null);
assert.equal(sanitizeSkuCompleteness({
  expectedCount: 1,
  variantDimensionCount: 0,
  complete: true,
  issues: []
}, missingPriceSkus, missingPriceRaw).complete, false);

const invalidOriginalRaw = [{
  externalSkuId: "model-2",
  price: 99,
  originalPrice: 89,
  stock: 2,
  imageUrl: "https://cf.shopee.ph/file/model-2.webp"
}];
const invalidOriginalSkus = sanitizeSkus(invalidOriginalRaw);
assert.equal(sanitizeSkuCompleteness({
  expectedCount: 1,
  variantDimensionCount: 0,
  complete: true,
  issues: []
}, invalidOriginalSkus, invalidOriginalRaw).complete, false);

const optionalStockRaw = [{
  externalSkuId: "model-without-stock",
  price: 99,
  originalPrice: 129,
  stock: null,
  imageUrl: "https://cf.shopee.ph/file/model-without-stock.webp"
}];
const optionalStockSkus = sanitizeSkus(optionalStockRaw);
const optionalStockCompleteness = sanitizeSkuCompleteness({
  expectedCount: 1,
  variantDimensionCount: 0,
  complete: true,
  issues: ["SKU_1_STOCK_MISSING"]
}, optionalStockSkus, optionalStockRaw);
assert.equal(optionalStockSkus[0].stock, null);
assert.equal(optionalStockCompleteness.complete, true);
assert.deepEqual(Array.from(optionalStockCompleteness.issues), []);

const imageSkus = sanitizeSkus([{
  externalSkuId: "sku-image-1",
  price: 10,
  originalPrice: 10,
  stock: 2,
  imageUrl: "https://p16-oec-va.ibyteimg.com/variant.webp"
}]);
const imageValues = [
  { url: "https://p16-oec-va.ibyteimg.com/main.webp", role: "MAIN" },
  { url: "https://p16-oec-va.ibyteimg.com/variant.webp", role: "VARIANT" }
];
const sanitizedImages = context.HeyiSanitizeTest.sanitizeCapture({
  sourceUrl: "https://shop.tiktok.com/view/product/1736046921727116747?region=PH",
  sourcePlatform: "TIKTOK_SHOP",
  sourceProductId: "1736046921727116747",
  title: "TikTok image fixture",
  description: "First line\r\nSecond  line\u0000",
  ratingValue: null,
  images: imageValues,
  skus: [{
    externalSkuId: "sku-image-1",
    price: 10,
    originalPrice: 10,
    stock: 2,
    imageUrl: "https://p16-oec-va.ibyteimg.com/variant.webp"
  }],
  imageCompleteness: {
    expectedCount: 2,
    capturedCount: 2,
    requiredSkuImageCount: 1,
    coveredSkuImageCount: 1,
    complete: true,
    roleCounts: { MAIN: 1, GALLERY: 0, VARIANT: 1, DETAIL: 0 },
    selectedRoleCounts: { MAIN: 1, GALLERY: 0, VARIANT: 1, DETAIL: 0 },
    source: "TIKTOK_PAGE_RESPONSE",
    issues: []
  }
}, "TIKTOK_SHOP");
assert.equal(sanitizedImages.domData.ratingValue, null);
assert.equal(sanitizedImages.description, "First line\nSecond  line");
assert.equal(sanitizedImages.imageCompleteness.expectedCount, 2);
assert.equal(sanitizedImages.imageCompleteness.capturedCount, 2);
assert.equal(sanitizedImages.imageCompleteness.requiredSkuImageCount, 1);
assert.equal(sanitizedImages.imageCompleteness.coveredSkuImageCount, 1);
assert.equal(sanitizedImages.imageCompleteness.complete, true);
assert.equal(sanitizedImages.imageCompleteness.source, "TIKTOK_PAGE_RESPONSE");
assert.equal(sanitizedImages.imageCompleteness.roleCounts.VARIANT, 1);
assert.equal(sanitizedImages.domData.imageCompleteness, sanitizedImages.imageCompleteness);

const completeLargeCapture = sanitizeCapture({
  sourceUrl: "https://shopee.ph/full-capture-i.123.456",
  sourcePlatform: "SHOPEE",
  sourceProductId: "456",
  sourceShopId: "123",
  title: "Gallery plus below-fold description images",
  images: Array.from({ length: 120 }, (_, index) => ({
    url: `https://down-ph.img.susercontent.com/file/capture-${index}.webp`,
    role: index === 0 ? "MAIN" : index < 16 ? "VARIANT" : "DETAIL"
  })),
  imageCompleteness: { expectedCount: 120, capturedCount: 120, complete: true, issues: [] }
}, "SHOPEE");
assert.equal(completeLargeCapture.images.length, 120);
assert.equal(completeLargeCapture.images.at(-1).role, "DETAIL");
assert.equal(completeLargeCapture.imageCompleteness.complete, true);
const oversizedCapture = sanitizeCapture({
  sourceUrl: "https://shopee.ph/full-capture-i.123.456",
  sourcePlatform: "SHOPEE",
  sourceProductId: "456",
  sourceShopId: "123",
  title: "Oversized description images",
  images: [...completeLargeCapture.images, { url: "https://down-ph.img.susercontent.com/file/extra.webp", role: "DETAIL" }],
  imageCompleteness: { expectedCount: 121, capturedCount: 121, complete: true, issues: [] }
}, "SHOPEE");
assert.equal(oversizedCapture.images.length, 120);
assert.equal(oversizedCapture.imageCompleteness.complete, false);

const missingSkuImage = sanitizeImageCompleteness({
  expectedCount: 1,
  capturedCount: 1,
  requiredSkuImageCount: 1,
  coveredSkuImageCount: 1,
  complete: true,
  issues: []
}, [{ url: "https://p16-oec-va.ibyteimg.com/main.webp", role: "MAIN" }], imageSkus);
assert.equal(missingSkuImage.complete, false);
assert.equal(missingSkuImage.coveredSkuImageCount, 0);
assert.ok(missingSkuImage.issues.includes("VARIANT_IMAGE_COVERAGE_TRUNCATED"));

const keyWithoutShop = await idempotencyKey({
  sourcePlatform: "TIKTOK_SHOP",
  sourceProductId: "1736046921727116747",
  sourceShopId: null,
  sourceUrl: "https://shop.tiktok.com/view/product/1736046921727116747"
});
const keyWithShop = await idempotencyKey({
  sourcePlatform: "TIKTOK_SHOP",
  sourceProductId: "1736046921727116747",
  sourceShopId: "7000000000000000001",
  sourceUrl: "https://shop.tiktok.com/view/product/1736046921727116747?shop_id=7000000000000000001"
});
assert.equal(keyWithoutShop, keyWithShop);

console.log("Background sanitize tests passed");

const renditionUrl = "https://down-aka-ph.vod.susercontent.com/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd.16000081784791738.mp4";
const previewUrl = "https://mms.vod.susercontent.com/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd.default.mp4";
const otherVideo = renditionUrl.replace("mqzwevrhc35wbd", "anotherasset123");
const sanitizedVideos = sanitizeCapture({
  sourceUrl:"https://shopee.ph/product-i.123.456",sourcePlatform:"SHOPEE",sourceProductId:"456",sourceShopId:"123",title:"Video fixture",
  videos:[renditionUrl,previewUrl,previewUrl+"?v=2",previewUrl+"?v=3",previewUrl+"?v=4",otherVideo].map(url=>({url}))
}, "SHOPEE");
assert.deepEqual(Array.from(sanitizedVideos.domData.videos,video=>video.url),[renditionUrl,otherVideo]);
