import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const location = {
  href: "https://shopee.ph/example-i.123.456",
  origin: "https://shopee.ph",
  hostname: "shopee.ph"
};
const common = {
  cleanText(value, maxLength = 2_000) {
    if (value === null || value === undefined) return "";
    return String(value).replace(/\s+/g, " ").trim().slice(0, maxLength);
  },
  absoluteUrl(value) {
    try {
      const url = new URL(value, location.href);
      return url.protocol === "https:" && !url.username && !url.password ? url.toString() : "";
    } catch {
      return "";
    }
  }
};
const context = vm.createContext({
  HeyiCollector: common,
  URL,
  location,
  document: { querySelectorAll: () => [] },
  fetch: async () => { throw new Error("network disabled in parser tests"); },
  window: { setTimeout, clearTimeout }
});
vm.runInContext(readFileSync(new URL("../src/shared/video-source.js", import.meta.url), "utf8"), context);
vm.runInContext(readFileSync(new URL("../src/content/shopee-data.js", import.meta.url), "utf8"), context);
vm.runInContext(readFileSync(new URL("../src/content/tiktok-data.js", import.meta.url), "utf8"), context);

const colors = Array.from({ length: 12 }, (_, index) => `Color ${index + 1}`);
const sizes = Array.from({ length: 8 }, (_, index) => `Size ${index + 1}`);
const models96 = colors.flatMap((color, colorIndex) => sizes.map((size, sizeIndex) => ({
  modelid: String(10_000 + colorIndex * sizes.length + sizeIndex),
  sku: `C${colorIndex + 1}-S${sizeIndex + 1}`,
  name: `${color},${size}`,
  tier_index: [colorIndex, sizeIndex],
  price: 15_900_000 + sizeIndex * 100_000,
  price_before_discount: 31_800_000 + sizeIndex * 100_000,
  stock: colorIndex + sizeIndex
})));
const fullVideoUrl = "https://down-aka-ph.vod.susercontent.com/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd.16000081784791738.mp4";
const previewVideoUrl = "https://mms.vod.susercontent.com/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd.default.mp4";
const secondVideoUrl = fullVideoUrl.replace("mqzwevrhc35wbd", "differentasset123");
const shopee96Fixture = {
  data: {
    shop_detailed: { name: "SukiSilk" },
    item: {
      item_id: 456,
      shop_id: 123,
      name: "96 SKU Fixture",
      video_info_list: [{video_url:fullVideoUrl,default_url:previewVideoUrl},{video_url:secondVideoUrl}],
      currency: "PHP",
      image: "ph-product-main-image-123456",
      images: ["ph-product-main-image-123456", "ph-product-second-image-12345"],
      extended_description: {
        field_list: [
          { field_type: "text", text: "Rich product description" },
          { field_type: "image", image_info: { image_id: "ph-detail-image-0001" } },
          { field_type: "image", image_info: { image_url: "https://down-ph.img.susercontent.com/file/ph-detail-image-0002" } }
        ]
      },
      item_rating: {
        review_images: ["ph-review-image-must-not-be-collected"]
      },
      tier_variations: [
        { name: "Color", options: colors, images: colors.map((_, index) => `ph-color-image-${String(index).padStart(4, "0")}`) },
        { name: "Size", options: sizes }
      ],
      models: models96
    }
  }
};
const parsed96 = common.parseShopeeProductData(shopee96Fixture, { shopId: "123", productId: "456" }, "TEST");
assert.deepEqual(Array.from(parsed96.videos, video=>video.url),[fullVideoUrl,secondVideoUrl]);
assert.equal(parsed96.skuCompleteness.expectedCount, 96);
assert.equal(parsed96.skuCompleteness.capturedCount, 96);
assert.equal(parsed96.skuCompleteness.variantDimensionCount, 2);
assert.equal(parsed96.skuCompleteness.complete, true);
assert.equal(parsed96.skus[0].price, 159);
assert.equal(parsed96.skus[0].originalPrice, 318);
assert.deepEqual({ ...parsed96.skus[95].attributes }, { Color: "Color 12", Size: "Size 8" });
assert.ok(parsed96.skus.every(sku => sku.externalSkuId && sku.stock !== null && sku.imageUrl?.startsWith("https://down-ph.img.susercontent.com/file/")));
assert.equal(parsed96.imageCandidates.filter(image => image.role === "MAIN").length, 1);
assert.equal(parsed96.imageCandidates.filter(image => image.role === "VARIANT").length, 12);
assert.deepEqual(
  Array.from(parsed96.imageCandidates.filter(image => image.role === "DETAIL"), image => image.url),
  [
    "https://down-ph.img.susercontent.com/file/ph-detail-image-0001",
    "https://down-ph.img.susercontent.com/file/ph-detail-image-0002"
  ]
);
assert.equal(parsed96.imageCandidates.some(image => image.url.includes("review-image")), false);
const chartFixture=structuredClone(shopee96Fixture);
chartFixture.data.item.size_chart="ph-11134207-820lc-mu8q8kokcefbfe";
const withChart=common.parseShopeeProductData(chartFixture,{shopId:"123",productId:"456"},"TEST");
assert.equal(withChart.skus.length,96);
assert.deepEqual(Array.from(withChart.imageCandidates.filter(image=>image.role==="SIZE_CHART"),image=>image.url),
  ["https://down-ph.img.susercontent.com/file/ph-11134207-820lc-mu8q8kokcefbfe"]);

const summaryBeforeFullItem = {
  recommendation: { item_id: 456, shop_id: 123, name: "Summary", price: 15_900_000 },
  cached: structuredClone(shopee96Fixture)
};
const parsedAfterSummary = common.parseShopeeProductData(summaryBeforeFullItem, { shopId: "123", productId: "456" }, "TEST");
assert.equal(parsedAfterSummary.skuCompleteness.complete, true, "a matching summary must not hide the full PDP in the same bootstrap");
assert.equal(parsedAfterSummary.skus.length, 96);
assert.equal(parsedAfterSummary.title, "96 SKU Fixture");
const ratedFixture = structuredClone(shopee96Fixture);
ratedFixture.data.item.item_rating = { rating_star: 5, total_rating_count: 9, rating_count: [9, 0, 0, 0, 0, 9] };
const ratedProduct = common.parseShopeeProductData(ratedFixture, { shopId: "123", productId: "456" }, "TEST");
assert.equal(ratedProduct.ratingValue, 5);
assert.equal(ratedProduct.ratingCount, 9);

const shopeeSingleFixture = {
  data: {
    item: {
      item_id: 456,
      shop_id: 123,
      name: "Single SKU Fixture",
      currency: "PHP",
      image: "single-product-image-123456",
      tier_variations: [],
      models: [{ modelid: "999", sku: "ONLY-ONE", name: "Default", price: 12_500_000, price_before_discount: 0, stock: 0 }]
    }
  }
};
const parsedSingle = common.parseShopeeProductData(shopeeSingleFixture, { shopId: "123", productId: "456" }, "TEST");
assert.equal(parsedSingle.skuCompleteness.complete, true);
assert.equal(parsedSingle.skuCompleteness.variantDimensionCount, 0);
assert.deepEqual({ ...parsedSingle.skus[0].attributes }, {});
assert.equal(parsedSingle.skus[0].originalPrice, 125);
assert.equal(parsedSingle.skus[0].originalPriceSource, "NO_DISCOUNT_SAME_AS_PRICE");
assert.equal(parsedSingle.skus[0].imageSource, "PRODUCT_MAIN");

const nullOriginalFixture = structuredClone(shopeeSingleFixture);
nullOriginalFixture.data.item.models[0].price_before_discount = null;
const parsedNullOriginal = common.parseShopeeProductData(
  nullOriginalFixture,
  { shopId: "123", productId: "456" },
  "TEST"
);
assert.equal(parsedNullOriginal.skus[0].originalPrice, null);
assert.equal(parsedNullOriginal.skus[0].originalPriceSource, null);
assert.equal(parsedNullOriginal.skuCompleteness.complete, false);
assert.ok(parsedNullOriginal.skuCompleteness.issues.includes("SKU_1_ORIGINAL_PRICE_MISSING"));

const invalidOriginalFixture = structuredClone(shopeeSingleFixture);
invalidOriginalFixture.data.item.models[0].price_before_discount = "0";
const parsedInvalidOriginal = common.parseShopeeProductData(
  invalidOriginalFixture,
  { shopId: "123", productId: "456" },
  "TEST"
);
assert.equal(parsedInvalidOriginal.skus[0].originalPrice, 0);
assert.equal(parsedInvalidOriginal.skuCompleteness.complete, false);
assert.ok(parsedInvalidOriginal.skuCompleteness.issues.includes("SKU_1_ORIGINAL_PRICE_INVALID"));

const fallbackStockFixture = structuredClone(shopeeSingleFixture);
fallbackStockFixture.data.item.models[0].stock = null;
fallbackStockFixture.data.item.models[0].normal_stock = 7;
const parsedFallbackStock = common.parseShopeeProductData(
  fallbackStockFixture,
  { shopId: "123", productId: "456" },
  "TEST"
);
assert.equal(parsedFallbackStock.skus[0].stock, 7);
assert.equal(parsedFallbackStock.skuCompleteness.complete, true);

const stockInfoV2Fixture = structuredClone(shopeeSingleFixture);
delete stockInfoV2Fixture.data.item.models[0].stock;
stockInfoV2Fixture.data.item.models[0].stock_info_v2 = {
  summary_info: { total_available_stock: 11, total_reserved_stock: 2 }
};
const parsedStockInfoV2 = common.parseShopeeProductData(
  stockInfoV2Fixture,
  { shopId: "123", productId: "456" },
  "TEST"
);
assert.equal(parsedStockInfoV2.skus[0].stock, 11);
assert.equal(parsedStockInfoV2.skuCompleteness.complete, true);

const priceStockFixture = structuredClone(shopeeSingleFixture);
delete priceStockFixture.data.item.models[0].stock;
priceStockFixture.data.item.models[0].price_stocks = [
  {
    promotion_type: 0,
    stock_breakdown_by_location: [
      { location_id: "PH-A", available_stock: 4 },
      { location_id: "PH-B", available_stock: 5 }
    ]
  },
  {
    promotion_type: 1,
    stock_breakdown_by_location: [
      { location_id: "PH-A", available_stock: 4 },
      { location_id: "PH-B", available_stock: 5 }
    ]
  }
];
const parsedPriceStock = common.parseShopeeProductData(
  priceStockFixture,
  { shopId: "123", productId: "456" },
  "TEST"
);
assert.equal(parsedPriceStock.skus[0].stock, 9);
assert.equal(parsedPriceStock.skuCompleteness.complete, true);

const conflictingPriceStockFixture = structuredClone(priceStockFixture);
conflictingPriceStockFixture.data.item.models[0].price_stocks[1].stock_breakdown_by_location[1].available_stock = 6;
const parsedConflictingPriceStock = common.parseShopeeProductData(
  conflictingPriceStockFixture,
  { shopId: "123", productId: "456" },
  "TEST"
);
assert.equal(parsedConflictingPriceStock.skus[0].stock, null);
assert.equal(parsedConflictingPriceStock.skuCompleteness.complete, true);
assert.deepEqual(Array.from(parsedConflictingPriceStock.skuCompleteness.issues), []);

const incompletePriceStockFixture = structuredClone(priceStockFixture);
incompletePriceStockFixture.data.item.models[0].price_stocks[0].stock_breakdown_by_location = [
  { location_id: "PH-A", available_stock: 0 }
];
incompletePriceStockFixture.data.item.models[0].price_stocks[1].stock_breakdown_by_location = [
  { location_id: "PH-A" }
];
const parsedIncompletePriceStock = common.parseShopeeProductData(
  incompletePriceStockFixture,
  { shopId: "123", productId: "456" },
  "TEST"
);
assert.equal(parsedIncompletePriceStock.skus[0].stock, null);
assert.equal(parsedIncompletePriceStock.skuCompleteness.complete, true);
assert.deepEqual(Array.from(parsedIncompletePriceStock.skuCompleteness.issues), []);

const emptyPriceStockFixture = structuredClone(priceStockFixture);
emptyPriceStockFixture.data.item.models[0].price_stocks[1].stock_breakdown_by_location = [];
const parsedEmptyPriceStock = common.parseShopeeProductData(
  emptyPriceStockFixture,
  { shopId: "123", productId: "456" },
  "TEST"
);
assert.equal(parsedEmptyPriceStock.skus[0].stock, null);
assert.equal(parsedEmptyPriceStock.skuCompleteness.complete, true);
assert.deepEqual(Array.from(parsedEmptyPriceStock.skuCompleteness.issues), []);

const duplicateFixture = structuredClone(shopeeSingleFixture);
duplicateFixture.data.item.models.push({ ...duplicateFixture.data.item.models[0] });
const parsedDuplicate = common.parseShopeeProductData(duplicateFixture, { shopId: "123", productId: "456" }, "TEST");
assert.equal(parsedDuplicate.skuCompleteness.complete, false);
assert.equal(parsedDuplicate.skuCompleteness.expectedCount, 2);
assert.equal(parsedDuplicate.skuCompleteness.capturedCount, 1);
assert.ok(parsedDuplicate.skuCompleteness.issues.includes("DUPLICATE_MODEL_ID"));
assert.equal(common.parseShopeeProductData(shopee96Fixture, { shopId: "123", productId: "999" }, "TEST"), null);
assert.equal(common.parseShopeeProductData(shopee96Fixture, { shopId: "999", productId: "456" }, "TEST"), null);
const missingShopFixture = structuredClone(shopeeSingleFixture);
delete missingShopFixture.data.item.shop_id;
assert.equal(
  common.parseShopeeProductData(missingShopFixture, { shopId: "123", productId: "456" }, "TEST"),
  null,
  "authoritative Shopee data must carry its own matching shop id"
);

const incompletePdp = structuredClone(shopeeSingleFixture);
incompletePdp.data.item.name = "Incomplete direct PDP";
incompletePdp.data.item.models[0].price_before_discount = null;
const completeBridge = structuredClone(shopeeSingleFixture);
completeBridge.data.item.name = "Complete bridge response";
completeBridge.data.item.models[0].stock = 42;
context.fetch = async () => jsonResponse(incompletePdp);
context.HeyiCaptureBridge = { read: async () => [completeBridge] };
const aggregatedComplete = await common.loadShopeeProductData({ shopId: "123", productId: "456" });
assert.equal(aggregatedComplete.source, "SHOPEE_PAGE_RESPONSE");
assert.equal(aggregatedComplete.title, "Complete bridge response");
assert.equal(aggregatedComplete.skus[0].stock, 42);

const olderBridge = structuredClone(shopeeSingleFixture);
olderBridge.data.item.name = "Older complete response";
olderBridge.data.item.models[0].stock = 3;
const newerBridge = structuredClone(shopeeSingleFixture);
newerBridge.data.item.name = "Newer complete response";
newerBridge.data.item.models[0].stock = 99;
context.fetch = async () => { throw new Error("direct PDP unavailable"); };
context.HeyiCaptureBridge = { read: async () => [newerBridge, olderBridge] };
const newestComplete = await common.loadShopeeProductData({ shopId: "123", productId: "456" });
assert.equal(newestComplete.title, "Newer complete response");
assert.equal(newestComplete.skus[0].stock, 99);

const latestWithoutStock = structuredClone(shopee96Fixture);
latestWithoutStock.data.item.name = "Latest network prices";
for (const model of latestWithoutStock.data.item.models) model.stock = null;
latestWithoutStock.data.item.models[0].price = 16_900_000;
latestWithoutStock.data.item.models[1].stock = 0;
const currentRendered = structuredClone(ratedFixture);
for (const model of currentRendered.data.item.models) model.stock = 10;
currentRendered.data.item.models.at(-1).modelid = "unrelated-model-id";
context.HeyiCaptureBridge = { readEntries: async () => [
  { value: latestWithoutStock, sequence: 4 },
  { value: currentRendered, sequence: 1, source: "SHOPEE_RENDERED_PDP" }
] };
const enrichedStock = await common.loadShopeeProductData({ shopId: "123", productId: "456" });
assert.equal(enrichedStock.title, "Latest network prices");
assert.equal(enrichedStock.source, "SHOPEE_PAGE_RESPONSE");
assert.equal(enrichedStock.skus[0].price, 169, "current network price must survive stock enrichment");
assert.equal(enrichedStock.skus[0].stock, 10);
assert.equal(enrichedStock.skus[0].stockSource, "SHOPEE_RENDERED_PDP");
assert.equal(enrichedStock.skus[1].stock, 0, "explicit sold-out stock must never be overwritten");
assert.equal(enrichedStock.skus[1].stockSource, "SHOPEE_PAGE_RESPONSE");
assert.equal(enrichedStock.skus.at(-1).stock, null, "a different model ID must not supply inventory");
assert.equal(enrichedStock.ratingValue, 5);
assert.equal(enrichedStock.ratingCount, 9);
const foreignRendered = structuredClone(currentRendered);
foreignRendered.data.item.shop_id = 999;
context.HeyiCaptureBridge = { readEntries: async () => [
  { value: latestWithoutStock, sequence: 4 },
  { value: foreignRendered, sequence: 1, source: "SHOPEE_RENDERED_PDP" }
] };
const foreignStockRejected = await common.loadShopeeProductData({ shopId: "123", productId: "456" });
assert.equal(foreignStockRejected.skus[0].stock, null, "same model ID from a different shop must not supply inventory");
context.HeyiCaptureBridge = { readEntries: async () => [
  { value: latestWithoutStock, sequence: 4 },
  { value: currentRendered, sequence: 1, source: "SHOPEE_PAGE_STATE" }
] };
const staleBootstrapStockRejected = await common.loadShopeeProductData({ shopId: "123", productId: "456" });
assert.equal(staleBootstrapStockRejected.skus[0].stock, null, "generic bootstrap state must not pretend to be current rendered inventory");

const delayedBootstrap = {
  initialState: {
    DOMAIN_PDP: {
      data: {
        PDP_BFF_DATA: {
          cachedMap: {
            "123/456": structuredClone(shopee96Fixture.data)
          }
        }
      }
    }
  }
};
delayedBootstrap.initialState.DOMAIN_PDP.data.PDP_BFF_DATA.cachedMap["123/456"].item.name = "Delayed bootstrap";
for (const model of delayedBootstrap.initialState.DOMAIN_PDP.data.PDP_BFF_DATA.cachedMap["123/456"].item.models) {
  model.stock = 10;
}
const responseBeforeBootstrap = structuredClone(shopee96Fixture);
responseBeforeBootstrap.data.item.name = "Response before bootstrap";
for (const model of responseBeforeBootstrap.data.item.models) {
  delete model.stock;
  delete model.price_before_discount;
}
let embeddedScriptScans = 0;
context.document.querySelectorAll = () => {
  embeddedScriptScans += 1;
  return embeddedScriptScans === 1 ? [] : [{ textContent: JSON.stringify(delayedBootstrap) }];
};
context.fetch = async () => { throw new Error("direct PDP unavailable"); };
context.HeyiCaptureBridge = { read: async () => [responseBeforeBootstrap] };
const rescannedBootstrap = await common.loadShopeeProductData({ shopId: "123", productId: "456" });
assert.equal(embeddedScriptScans, 2);
assert.equal(rescannedBootstrap.source, "SHOPEE_BOOTSTRAP");
assert.equal(rescannedBootstrap.title, "Delayed bootstrap");
assert.equal(rescannedBootstrap.skus.length, 96);
assert.ok(rescannedBootstrap.skus.every(sku => sku.stock === 10));
assert.equal(rescannedBootstrap.skuCompleteness.complete, true);
context.document.querySelectorAll = () => [{ textContent: `window.initialState = ${JSON.stringify(delayedBootstrap.initialState)};` }];
context.HeyiCaptureBridge = { read: async () => [] };
const assignedInitialState = await common.loadShopeeProductData({ shopId: "123", productId: "456" });
assert.equal(assignedInitialState.skus.length, 96, "Shopee's lower-case initialState assignment must be parsed without evaluating JavaScript");
assert.equal(assignedInitialState.skuCompleteness.complete, true);
context.document.querySelectorAll = () => [];

const tiktokFixture = {
  loaderData: {
    productPage: {
      component_data: {
        product_info: {
          product_model: {
            product_id: "7499999999999999999",
            seller_id: "7000000000000000001",
            name: "TikTok Multi-SKU Fixture",
            description: JSON.stringify([
              {
                type: "image",
                image: { url_list: ["https://p16-oec-va.ibyteimg.com/detail-one.webp"] }
              },
              { type: "text", text: "Ready Stock", sub: [{ t: "Ready Stock" }] },
              { type: "text", text: "Purchase Note\nCheck the size chart", sub: [{ t: "duplicate text" }] },
              {
                type: "image",
                text: "image alt text must not become description",
                image: { url_list: ["https://p16-oec-va.ibyteimg.com/detail-two.webp"] }
              },
              { type: "paragraph", children: [{ type: "text", text: "Return & Refund" }] }
            ]),
            images: [{ url_list: ["https://p16-oec-va.ibyteimg.com/product-main.webp"] }],
            sale_properties: [
              {
                property_id: "color",
                property_name: "Color",
                property_values: [
                  {
                    property_value_id: "black",
                    property_value_name: "Black",
                    image: { url_list: ["https://p16-oec-va.ibyteimg.com/black.webp"] }
                  }
                ]
              },
              {
                property_id: "size",
                property_name: "Size",
                property_values: [
                  { property_value_id: "s", property_value_name: "S" },
                  { property_value_id: "m", property_value_name: "M" }
                ]
              }
            ],
            skus: [
              {
                id: "sku-1",
                seller_sku: "BLACK-S",
                sku_quantity: { available_quantity: 10 },
                sku_origin_image: { url_list: ["https://p16-oec-va.ibyteimg.com/black-s.webp"] },
                property_pairs: [
                  { sku_property_id: "color", sku_property_value_id: "black", sku_property_name: "Color", sku_property_value_name: "Black" },
                  { sku_property_id: "size", sku_property_value_id: "s", sku_property_name: "Size", sku_property_value_name: "S" }
                ]
              },
              {
                id: "sku-2",
                seller_sku: "BLACK-M",
                sku_quantity: { available_quantity: 3 },
                property_pairs: [
                  { sku_property_id: "color", sku_property_value_id: "black", sku_property_name: "Color", sku_property_value_name: "Black" },
                  { sku_property_id: "size", sku_property_value_id: "m", sku_property_name: "Size", sku_property_value_name: "M" }
                ]
              }
            ]
          },
          promotion_model: {
            promotion_product_price: {
              skus_price: {
                "sku-1": { sale_price_decimal: "159.00", origin_price_decimal: "318.00", currency_name: "PHP" },
                "sku-2": { sale_price_decimal: "169.00", origin_price_decimal: null }
              }
            }
          },
          logistic_model: {},
          seller_model: {}
        }
      }
    }
  }
};
const parsedTikTok = common.parseTikTokProductData(
  tiktokFixture,
  { productId: "7499999999999999999", shopId: null },
  "TEST"
);
assert.equal(parsedTikTok.skuCompleteness.complete, true);
assert.equal(parsedTikTok.currency, "PHP");
assert.equal(parsedTikTok.skuCompleteness.variantDimensionCount, 2);
assert.equal(parsedTikTok.skus[0].stock, 10);
assert.equal(parsedTikTok.skus[0].price, 159);
assert.equal(parsedTikTok.skus[0].imageSource, "SKU");
assert.equal(parsedTikTok.skus[1].imageSource, "VARIANT_OPTION");
assert.equal(parsedTikTok.skus[1].originalPrice, 169);
assert.equal(parsedTikTok.skus[1].originalPriceSource, "NO_DISCOUNT_SAME_AS_PRICE");
assert.deepEqual({ ...parsedTikTok.skus[1].attributes }, { Color: "Black", Size: "M" });
assert.equal(parsedTikTok.description, "Ready Stock\nPurchase Note\nCheck the size chart\nReturn & Refund");
assert.equal(parsedTikTok.description.includes("ibyteimg.com"), false);
assert.equal(parsedTikTok.description.split("Ready Stock").length - 1, 1);
assert.deepEqual(Array.from(parsedTikTok.images), ["https://p16-oec-va.ibyteimg.com/product-main.webp"]);
assert.deepEqual(Array.from(parsedTikTok.detailImages), [
  "https://p16-oec-va.ibyteimg.com/detail-one.webp",
  "https://p16-oec-va.ibyteimg.com/detail-two.webp"
]);
assert.equal(common.parseTikTokProductData(tiktokFixture, { productId: "7400000000000000000" }, "TEST"), null);

const tiktokArrayDescriptionFixture = structuredClone(tiktokFixture);
const arrayDescriptionProduct = tiktokArrayDescriptionFixture.loaderData.productPage.component_data.product_info.product_model;
arrayDescriptionProduct.description = [
  { type: "text", text: "Array block one", sub: [{ t: "Array block one" }] },
  { type: "image", image: { url_list: ["https://p16-oec-va.ibyteimg.com/array-detail.webp"] } },
  { type: "text", sub: [{ t: "Array block two" }] }
];
const parsedTikTokArrayDescription = common.parseTikTokProductData(
  tiktokArrayDescriptionFixture,
  { productId: "7499999999999999999" },
  "TEST"
);
assert.equal(parsedTikTokArrayDescription.description, "Array block one\nArray block two");
assert.deepEqual(Array.from(parsedTikTokArrayDescription.detailImages), ["https://p16-oec-va.ibyteimg.com/array-detail.webp"]);

const scopedTikTokDescriptionFixture = structuredClone(tiktokFixture);
const scopedDescriptionProduct = scopedTikTokDescriptionFixture.loaderData.productPage.component_data.product_info.product_model;
scopedDescriptionProduct.description = { blocks: [
  { type: "image", image: { url: "tos-storage-key-must-not-become-page-url", url_list: [
    "https://p16-oec-va.ibyteimg.com/scoped-detail.webp", "https://p19-oec-va.ibyteimg.com/scoped-detail.webp"
  ] } },
  { field_type: "image", image_info: { image_url: "//p16-oec-va.ibyteimg.com/second-detail.webp" } },
  { type: "image", url: "https://p16-oec-va.ibyteimg.com/scoped-detail.webp" },
  { type: "image", image: { url: "javascript:alert(1)" } },
  { type: "image", image: { url: "http://p16-oec-va.ibyteimg.com/insecure.webp" } },
  { type: "image", image: { url: "tos-storage-key-only" } },
  { type: "video", image: { url: "https://p16-oec-va.ibyteimg.com/video-poster.webp" } },
  { type: "review", children: [{ type: "image", url: "https://example.com/review.webp" }] },
  { type: "text", text: "https://example.com/plain-url-is-not-an-image.webp" }
], reviews: [{ image: { url: "https://example.com/unrelated-review.webp" } }] };
scopedDescriptionProduct.review_images = ["https://example.com/product-review.webp"];
scopedTikTokDescriptionFixture.recommendations = [{
  product_id: "7400000000000000000", description: [{ type: "image", url: "https://example.com/recommended-product.webp" }]
}];
const scopedTikTokDescription = common.parseTikTokProductData(scopedTikTokDescriptionFixture, { productId: "7499999999999999999" });
assert.deepEqual(Array.from(scopedTikTokDescription.detailImages), [
  "https://p16-oec-va.ibyteimg.com/scoped-detail.webp", "https://p16-oec-va.ibyteimg.com/second-detail.webp"
]);
assert.equal(scopedTikTokDescription.images[0], "https://p16-oec-va.ibyteimg.com/product-main.webp");

const tiktokPlainDescriptionFixture = structuredClone(tiktokFixture);
const plainDescriptionProduct = tiktokPlainDescriptionFixture.loaderData.productPage.component_data.product_info.product_model;
plainDescriptionProduct.description = "[Ready stock]\nLine  two";
const parsedTikTokPlainDescription = common.parseTikTokProductData(
  tiktokPlainDescriptionFixture,
  { productId: "7499999999999999999" },
  "TEST"
);
assert.equal(parsedTikTokPlainDescription.description, "[Ready stock]\nLine  two");
assert.deepEqual(Array.from(parsedTikTokPlainDescription.detailImages), []);

const staleTikTokBootstrap = structuredClone(tiktokFixture);
const staleTikTokProduct = staleTikTokBootstrap.loaderData.productPage.component_data.product_info.product_model;
staleTikTokProduct.name = "Stale image-rich bootstrap";
staleTikTokProduct.images.push(
  { url_list: ["https://p16-oec-va.ibyteimg.com/stale-extra-1.webp"] },
  { url_list: ["https://p16-oec-va.ibyteimg.com/stale-extra-2.webp"] }
);
staleTikTokProduct.skus[0].sku_quantity.available_quantity = 1;
const freshTikTokResponse = structuredClone(tiktokFixture);
const freshTikTokProduct = freshTikTokResponse.loaderData.productPage.component_data.product_info.product_model;
freshTikTokProduct.name = "Fresh current-page response";
freshTikTokProduct.skus[0].sku_quantity.available_quantity = 77;
freshTikTokResponse.loaderData.productPage.component_data.product_info.promotion_model
  .promotion_product_price.skus_price["sku-1"].sale_price_decimal = "149.00";
context.document.querySelectorAll = () => [{ textContent: JSON.stringify(staleTikTokBootstrap) }];
context.HeyiCaptureBridge = {
  readEntries: async () => [{ value: freshTikTokResponse, sequence: 20, arrival: 200 }]
};
const selectedFreshTikTok = await common.loadTikTokProductData({ productId: "7499999999999999999" });
assert.equal(selectedFreshTikTok.title, "Fresh current-page response");
assert.equal(selectedFreshTikTok.skus[0].stock, 77);
assert.equal(selectedFreshTikTok.skus[0].price, 149);

const freshTikTokWithoutStock = structuredClone(freshTikTokResponse);
freshTikTokWithoutStock.loaderData.productPage.component_data.product_info.product_model.name = "Fresh response without stock";
for (const sku of freshTikTokWithoutStock.loaderData.productPage.component_data.product_info.product_model.skus) {
  delete sku.sku_quantity;
}
context.HeyiCaptureBridge = {
  readEntries: async () => [{ value: freshTikTokWithoutStock, sequence: 21, arrival: 210 }]
};
const selectedTikTokWithoutStock = await common.loadTikTokProductData({ productId: "7499999999999999999" });
assert.equal(selectedTikTokWithoutStock.title, "Fresh response without stock");
assert.ok(selectedTikTokWithoutStock.skus.every(sku => sku.stock === null));
assert.equal(selectedTikTokWithoutStock.skuCompleteness.complete, true);
assert.deepEqual(Array.from(selectedTikTokWithoutStock.skuCompleteness.issues), []);

const splitTikTokBase = structuredClone(tiktokFixture);
delete splitTikTokBase.loaderData.productPage.component_data.product_info.promotion_model;
const splitTikTokPromotion = {
  data: {
    product_id: "7499999999999999999",
    promotion_model: {
      promotion_product_price: {
        skus_price: {
          "sku-1": { sale_price_decimal: "151.00", origin_price_decimal: "302.00", currency_name: "PHP" },
          "sku-2": { sale_price_decimal: "161.00", origin_price_decimal: null, currency_name: "PHP" }
        }
      }
    }
  }
};
context.document.querySelectorAll = () => [];
context.HeyiCaptureBridge = {
  readEntries: async () => [
    { value: splitTikTokPromotion, sequence: 31, arrival: 310 },
    { value: splitTikTokBase, sequence: 30, arrival: 300 }
  ]
};
const mergedSplitTikTok = await common.loadTikTokProductData({ productId: "7499999999999999999" });
assert.equal(mergedSplitTikTok.skuCompleteness.complete, true);
assert.equal(mergedSplitTikTok.skus[0].price, 151);
assert.equal(mergedSplitTikTok.skus[0].originalPrice, 302);
assert.equal(mergedSplitTikTok.skus[1].price, 161);
assert.equal(mergedSplitTikTok.currency, "PHP");

const directPriceTikTokBase = structuredClone(splitTikTokBase);
const directPriceSkus = directPriceTikTokBase.loaderData.productPage.component_data.product_info.product_model.skus;
directPriceSkus[0].sale_price = "145.00";
directPriceSkus[0].original_price = "290.00";
directPriceSkus[1].sale_price = "155.00";
directPriceSkus[1].original_price = "310.00";
context.HeyiCaptureBridge = {
  readEntries: async () => [
    { value: directPriceTikTokBase, sequence: 35, arrival: 350 },
    { value: splitTikTokPromotion, sequence: 34, arrival: 340 }
  ]
};
const noStalePromotionOverride = await common.loadTikTokProductData({ productId: "7499999999999999999" });
assert.equal(noStalePromotionOverride.skus[0].price, 145);
assert.equal(noStalePromotionOverride.skus[0].originalPrice, 290);
assert.equal(noStalePromotionOverride.skus[1].price, 155);

const foreignPromotion = structuredClone(splitTikTokPromotion);
foreignPromotion.data.product_id = "7488888888888888888";
foreignPromotion.data.promotion_model.promotion_product_price.skus_price = {
  "foreign-sku": { sale_price_decimal: "1.00", origin_price_decimal: "2.00" }
};
context.HeyiCaptureBridge = {
  readEntries: async () => [
    { value: foreignPromotion, sequence: 41, arrival: 410 },
    { value: splitTikTokBase, sequence: 40, arrival: 400 }
  ]
};
const unmergedForeignTikTok = await common.loadTikTokProductData({ productId: "7499999999999999999" });
assert.equal(unmergedForeignTikTok.skuCompleteness.complete, false);
assert.equal(unmergedForeignTikTok.skus[0].price, null);

const tiktokImagePriorityFixture = structuredClone(tiktokFixture);
const imagePriorityProduct = tiktokImagePriorityFixture.loaderData.productPage.component_data.product_info.product_model;
imagePriorityProduct.images = [{
  url: "tos-alisg-i-aphluv4xwc-sg/product-main-storage-key",
  uri: "tos-alisg-i-aphluv4xwc-sg/product-main-storage-key",
  url_list: [
    "https://p16-oec-va.ibyteimg.com/tos-alisg-i-aphluv4xwc-sg/product-main-cdn.webp",
    "https://p19-oec-va.ibyteimg.com/tos-alisg-i-aphluv4xwc-sg/product-main-cdn.webp"
  ]
}];
imagePriorityProduct.skus[0].sku_origin_image = "tos-alisg-i-aphluv4xwc-sg/sku-storage-key";
imagePriorityProduct.skus[0].sku_image = {
  url: "tos-alisg-i-aphluv4xwc-sg/sku-storage-key",
  url_list: ["https://p16-oec-va.ibyteimg.com/tos-alisg-i-aphluv4xwc-sg/sku-cdn.webp"]
};
const parsedTikTokImagePriority = common.parseTikTokProductData(
  tiktokImagePriorityFixture,
  { productId: "7499999999999999999" },
  "TEST"
);
assert.deepEqual(Array.from(parsedTikTokImagePriority.images), [
  "https://p16-oec-va.ibyteimg.com/tos-alisg-i-aphluv4xwc-sg/product-main-cdn.webp"
]);
assert.equal(
  parsedTikTokImagePriority.skus[0].imageUrl,
  "https://p16-oec-va.ibyteimg.com/tos-alisg-i-aphluv4xwc-sg/sku-cdn.webp"
);
assert.equal(parsedTikTokImagePriority.skus[0].imageUrl.includes("shop.tiktok.com/view/product/tos-"), false);

const hiddenPropertyFixture = {
  product_info: {
    product_model: {
      product_id: "7499999999999999998",
      images: [{ url_list: ["https://p16-oec-va.ibyteimg.com/default.webp"] }],
      sale_properties: [{
        property_id: "hidden",
        property_name: "-",
        property_values: [{ property_value_id: "hidden-value", property_value_name: "-" }]
      }],
      skus: [{
        id: "default-sku",
        sku_quantity: { available_quantity: 8 },
        property_pairs: [{
          sku_property_id: "hidden",
          sku_property_value_id: "hidden-value",
          sku_property_name: "-",
          sku_property_value_name: "-"
        }]
      }]
    },
    promotion_model: {
      promotion_product_price: {
        skus_price: {
          "default-sku": { sale_price_decimal: "88.00", origin_price_decimal: null, currency_name: "PHP" }
        }
      }
    }
  }
};
const parsedHidden = common.parseTikTokProductData(hiddenPropertyFixture, { productId: "7499999999999999998" }, "TEST");
assert.equal(parsedHidden.skuCompleteness.complete, true);
assert.equal(parsedHidden.skuCompleteness.variantDimensionCount, 0);
assert.deepEqual({ ...parsedHidden.skus[0].attributes }, {});

common.adapters = [];
vm.runInContext(readFileSync(new URL("../src/content/tiktok.js", import.meta.url), "utf8"), context);
const tiktokAdapter = common.adapters.find(value => value.id === "TIKTOK_SHOP");
assert.equal(tiktokAdapter.isDetailUrl("https://shop.tiktok.com/us/pdp/1736041234567890123"), true);
assert.equal(tiktokAdapter.isDetailUrl("https://shop.tiktok.com/ph/pdp/nice-product-1736041234567890123"), true);
assert.equal(tiktokAdapter.isDetailUrl("https://www.tiktok.com/en-US/pdp/nice-product-1736041234567890123"), true);
assert.equal(tiktokAdapter.isDetailUrl("https://shop.tiktok.com/us/pdp/not-a-product"), false);

console.log("SKU parser tests passed");

function jsonResponse(value) {
  return {
    ok: true,
    headers: { get: () => null },
    body: null,
    async text() {
      return JSON.stringify(value);
    }
  };
}
