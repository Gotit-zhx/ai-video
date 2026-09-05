import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const location = {
  href: "https://shop.tiktok.com/view/product/1736046921727116747?region=PH",
  hostname: "shop.tiktok.com"
};
const document = {
  title: "TikTok product fixture",
  body: { innerText: "" },
  documentElement: { lang: "en" },
  querySelector: () => null,
  querySelectorAll: () => []
};
const context = vm.createContext({
  URL,
  location,
  document,
  globalThis: null
});
context.globalThis = context;
vm.runInContext(readFileSync(new URL("../src/content/common.js", import.meta.url), "utf8"), context);

const common = context.HeyiCollector;
const candidates = [
  { url: "https://cdn.example.com/main.webp", role: "MAIN" },
  ...Array.from({ length: 20 }, (_, index) => ({
    url: `https://cdn.example.com/gallery-${index + 1}.webp`, role: "GALLERY"
  })),
  ...Array.from({ length: 12 }, (_, index) => ({
    url: `https://cdn.example.com/variant-${index + 1}.webp`, role: "VARIANT"
  })),
  ...Array.from({ length: 5 }, (_, index) => ({
    url: `https://cdn.example.com/detail-${index + 1}.webp`, role: "DETAIL"
  }))
];
const planned = common.planListingImages(candidates, 30);
assert.equal(planned.images.length, 30);
assert.equal(planned.images[0].role, "MAIN");
assert.equal(planned.images.filter(image => image.role === "VARIANT").length, 12);
assert.equal(planned.images.filter(image => image.role === "GALLERY").length, 17);
assert.equal(planned.images.filter(image => image.role === "DETAIL").length, 0);
assert.deepEqual([...planned.completeness.issues], [
  "IMAGE_LIMIT_EXCEEDED",
  "GALLERY_IMAGE_COVERAGE_TRUNCATED",
  "DETAIL_IMAGE_COVERAGE_TRUNCATED"
]);
assert.deepEqual(
  JSON.parse(JSON.stringify(common.planListingImages(candidates, 30))),
  JSON.parse(JSON.stringify(planned))
);

const duplicateRole = common.planListingImages([
  { url: "https://cdn.example.com/main.webp", role: "MAIN" },
  { url: "https://cdn.example.com/color.webp", role: "GALLERY" },
  { url: "https://cdn.example.com/color.webp", role: "VARIANT" },
  { url: "https://cdn.example.com/detail.webp", role: "DESCRIPTION" }
], 30);
assert.deepEqual(Array.from(duplicateRole.images, image => image.role), ["MAIN", "VARIANT", "DETAIL"]);
assert.equal(duplicateRole.completeness.complete, true);

const tooManyVariants = common.planListingImages([
  { url: "https://cdn.example.com/main.webp", role: "MAIN" },
  ...Array.from({ length: 31 }, (_, index) => ({
    url: `https://cdn.example.com/color-${index + 1}.webp`, role: "VARIANT"
  }))
], 30);
assert.equal(tooManyVariants.images.filter(image => image.role === "VARIANT").length, 29);
assert.ok(tooManyVariants.completeness.issues.includes("VARIANT_IMAGE_COVERAGE_TRUNCATED"));
assert.equal(tooManyVariants.completeness.complete, false);

const exactLimit = common.planListingImages([
  { url: "https://p16-oec-va.ibyteimg.com/tos-maliva-i-o3syd03w52-us/mainasset000000000000000000000001~tplv.webp", role: "MAIN" },
  { url: "https://p19-oec-va.ibyteimg.com/tos-maliva-i-o3syd03w52-us/mainasset000000000000000000000001~tplv.webp", role: "GALLERY" },
  ...Array.from({ length: 18 }, (_, index) => ({
    url: `https://p16-oec-va.ibyteimg.com/tos-alisg-i-aphluv4xwc-sg/variantasset${String(index + 1).padStart(20, "0")}~tplv.webp`,
    role: "VARIANT"
  })),
  ...Array.from({ length: 11 }, (_, index) => ({
    url: `https://p16-oec-va.ibyteimg.com/tos-maliva-i-o3syd03w52-us/galleryasset${String(index + 1).padStart(20, "0")}~tplv.webp`,
    role: "GALLERY"
  }))
], 30);
assert.equal(exactLimit.images.length, 30);
assert.equal(exactLimit.completeness.expectedCount, 30);
assert.equal(exactLimit.completeness.capturedCount, 30);
assert.equal(exactLimit.completeness.roleCounts.VARIANT, 18);
assert.deepEqual([...exactLimit.completeness.issues], []);
assert.equal(exactLimit.completeness.complete, true);

const realTikTokAliasShape = common.planListingImages([
  ...Array.from({ length: 9 }, (_, index) => ["p16", "p19"].map((host, aliasIndex) => ({
    url: `https://${host}-oec-va.ibyteimg.com/tos-maliva-i-o3syd03w52-us/galleryasset${String(index + 1).padStart(20, "0")}~tplv.webp`,
    role: index === 0 && aliasIndex === 0 ? "MAIN" : "GALLERY"
  }))).flat(),
  ...Array.from({ length: 18 }, (_, index) => ({
    url: `https://p16-oec-va.ibyteimg.com/tos-alisg-i-aphluv4xwc-sg/variantasset${String(index + 1).padStart(20, "0")}~tplv.webp`,
    role: "VARIANT"
  }))
], 30);
assert.equal(realTikTokAliasShape.images.length, 27);
assert.equal(realTikTokAliasShape.completeness.expectedCount, 27);
assert.equal(realTikTokAliasShape.completeness.capturedCount, 27);
assert.equal(realTikTokAliasShape.completeness.roleCounts.VARIANT, 18);
assert.equal(realTikTokAliasShape.completeness.roleCounts.GALLERY, 8);
assert.deepEqual([...realTikTokAliasShape.completeness.issues], []);
assert.equal(realTikTokAliasShape.completeness.complete, true);

const skuAliasWins = common.planListingImages([
  {
    url: "https://p16-oec-va.ibyteimg.com/tos-maliva-i-o3syd03w52-us/sharedasset000000000000000000001~tplv.webp",
    role: "MAIN"
  },
  {
    url: "https://p19-oec-va.ibyteimg.com/tos-maliva-i-o3syd03w52-us/sharedasset000000000000000000001~tplv.webp",
    role: "VARIANT"
  }
], 30);
assert.equal(skuAliasWins.images.length, 1);
assert.equal(
  skuAliasWins.images[0].url,
  "https://p19-oec-va.ibyteimg.com/tos-maliva-i-o3syd03w52-us/sharedasset000000000000000000001~tplv.webp"
);
assert.equal(skuAliasWins.images[0].role, "MAIN");

const tiktokProductImages = Array.from({ length: 26 }, (_, index) =>
  `https://p16-oec-va.ibyteimg.com/product-${index + 1}.webp`);
common.loadTikTokProductData = async () => ({
  source: "TIKTOK_PAGE_RESPONSE",
  productId: "1736046921727116747",
  shopId: "7000000000000000001",
  title: "TikTok 27-image fixture",
  description: "Complete image fixture",
  currency: "PHP",
  priceMin: 10,
  priceMax: 10,
  images: tiktokProductImages,
  skus: [{
    externalSkuId: "sku-1",
    variationName: "Black",
    price: 10,
    originalPrice: 10,
    stock: 3,
    attributes: { Color: "Black" },
    imageUrl: "https://p16-oec-va.ibyteimg.com/variant-black.webp",
    imageSource: "SKU",
    imageInherited: false
  }],
  skuCompleteness: {
    expectedCount: 1,
    capturedCount: 1,
    variantDimensionCount: 1,
    complete: true,
    source: "TIKTOK_PAGE_RESPONSE",
    issues: []
  },
  variantHints: [{ name: "Color", values: ["Black"] }]
});
common.parseJsonLdProduct = () => null;
common.firstText = () => ({ text: "", selector: "" });
common.metaContent = () => ({ value: "", key: "" });
common.extractPrice = () => ({
  currency: "PHP", priceText: "", priceMin: null, priceMax: null,
  originalPriceText: "", originalPriceMin: null, originalPriceMax: null,
  sourceSelector: ""
});
common.extractStats = () => ({
  salesText: "", salesCount: null, salesSelector: "",
  ratingText: "", ratingValue: null, ratingCount: null, ratingSelector: ""
});
common.extractVariantHints = () => [];
common.extractVisibleSkus = () => [];
common.extractKeyValueAttributes = () => ({});
common.canonicalUrl = value => value;
context.HeyiPageGuard = { blockedPageReason: () => null };
vm.runInContext(readFileSync(new URL("../src/content/tiktok.js", import.meta.url), "utf8"), context);
const tiktokAdapter = common.adapters.find(adapter => adapter.id === "TIKTOK_SHOP");
const tiktokCapture = await tiktokAdapter.extract(
  document,
  "https://shop.tiktok.com/view/product/1736046921727116747?region=PH",
  "DETAIL"
);
assert.equal(tiktokCapture.images.length, 27);
assert.equal(tiktokCapture.imageCompleteness.expectedCount, 27);
assert.equal(tiktokCapture.imageCompleteness.capturedCount, 27);
assert.equal(tiktokCapture.imageCompleteness.requiredSkuImageCount, 1);
assert.equal(tiktokCapture.imageCompleteness.coveredSkuImageCount, 1);
assert.equal(tiktokCapture.imageCompleteness.complete, true);
assert.equal(tiktokCapture.images.some(image => image.url.endsWith("variant-black.webp")), true);

location.href = "https://shopee.ph/complete-96-sku-i.123.456";
location.hostname = "shopee.ph";
location.origin = "https://shopee.ph";
const shopeeVariantImages = Array.from({ length: 12 }, (_, index) =>
  `https://down-ph.img.susercontent.com/file/color-${index + 1}.webp`);
const shopeeSkus = Array.from({ length: 96 }, (_, index) => ({
  externalSkuId: `model-${index + 1}`,
  variationName: `Color ${Math.floor(index / 8) + 1} / Size ${index % 8 + 1}`,
  price: 159 + index % 8,
  originalPrice: 318 + index % 8,
  stock: index,
  attributes: { Color: `Color ${Math.floor(index / 8) + 1}`, Size: `Size ${index % 8 + 1}` },
  imageUrl: shopeeVariantImages[Math.floor(index / 8)],
  imageSource: "VARIANT_OPTION",
  imageInherited: false
}));
common.loadShopeeProductData = async () => ({
  source: "SHOPEE_PDP_API",
  productId: "456",
  shopId: "123",
  title: "Complete 96 SKU fixture",
  description: "Complete SKU matrix with an oversized non-SKU gallery",
  currency: "PHP",
  priceMin: 159,
  priceMax: 166,
  originalPriceMin: 318,
  originalPriceMax: 325,
  images: [],
  imageCandidates: [
    { url: "https://down-ph.img.susercontent.com/file/main.webp", role: "MAIN" },
    ...shopeeVariantImages.map(url => ({ url, role: "VARIANT" })),
    ...Array.from({ length: 20 }, (_, index) => ({
      url: `https://down-ph.img.susercontent.com/file/gallery-${index + 1}.webp`, role: "GALLERY"
    })),
    ...Array.from({ length: 5 }, (_, index) => ({
      url: `https://down-ph.img.susercontent.com/file/detail-${index + 1}.webp`, role: "DETAIL"
    }))
  ],
  attributes: {},
  variantHints: [
    { name: "Color", values: Array.from({ length: 12 }, (_, index) => `Color ${index + 1}`) },
    { name: "Size", values: Array.from({ length: 8 }, (_, index) => `Size ${index + 1}`) }
  ],
  skus: shopeeSkus,
  skuCompleteness: {
    expectedCount: 96,
    capturedCount: 96,
    variantDimensionCount: 2,
    complete: true,
    source: "SHOPEE_PDP_API",
    issues: []
  }
});
vm.runInContext(readFileSync(new URL("../src/content/shopee.js", import.meta.url), "utf8"), context);
const shopeeAdapter = common.adapters.find(adapter => adapter.id === "SHOPEE");
const shopeeCapture = await shopeeAdapter.extract(
  document,
  "https://shopee.ph/complete-96-sku-i.123.456",
  "DETAIL"
);
assert.equal(shopeeCapture.skus.length, 96);
assert.equal(shopeeCapture.skuCompleteness.complete, true);
assert.deepEqual(Array.from(shopeeCapture.skuCompleteness.issues), []);
assert.equal(shopeeCapture.imageCompleteness.complete, false);
assert.ok(shopeeCapture.imageCompleteness.issues.includes("GALLERY_IMAGE_COVERAGE_TRUNCATED"));
assert.ok(shopeeCapture.imageCompleteness.issues.includes("DETAIL_IMAGE_COVERAGE_TRUNCATED"));
assert.equal(shopeeCapture.imageCompleteness.issues.includes("VARIANT_IMAGE_COVERAGE_TRUNCATED"), false);

console.log("Listing image planner tests passed");
