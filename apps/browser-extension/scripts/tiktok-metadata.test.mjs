import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const productId = "1730135047201523374";
const common = {
  cleanText: (value, limit = 2_000) => value == null ? "" : String(value).replace(/\s+/g, " ").trim().slice(0, limit),
  absoluteUrl: value => typeof value === "string" && value.startsWith("https://") ? value : ""
};
const context = vm.createContext({ HeyiCollector: common, URL, document: { querySelectorAll: () => [] } });
vm.runInContext(readFileSync(new URL("../src/content/tiktok-data.js", import.meta.url), "utf8"), context);

function product(overrides = {}) {
  return {
    product_id: productId,
    seller_id: "7000000000000000001",
    name: "Metadata fixture",
    currency: "MYR",
    images: ["https://example.com/product.png"],
    skus: [{ id: "sku-1", price: { sale_price: "25", original_price: "30" }, stock: 1 }],
    ...overrides
  };
}

function parse(value) {
  return common.parseTikTokProductData(value, { productId });
}

assert.equal(parse(product({ category_name: "Women's T-Shirts" })).categoryName, "Women's T-Shirts");
assert.equal(parse(product({ categoryName: "Women's T-Shirts" })).categoryName, "Women's T-Shirts");
assert.equal(parse(product({
  categories: [],
  category_path: [{ category_name: "Womenswear" }, { categoryName: "Women's Tops" }, { name: "Women's T-Shirts" }]
})).categoryName, "Womenswear > Women's Tops > Women's T-Shirts", "an empty alias must not hide the category path");
assert.equal(parse({
  product_model: product(),
  category_model: { category_list: [{ name: "Womenswear" }, { name: "Women's T-Shirts" }] },
  recommendations: [{ category_name: "Unrelated category" }]
}).categoryName, "Womenswear > Women's T-Shirts");
assert.equal(parse({ product_model: product(), recommendations: [{ category_name: "Unrelated category" }] }).categoryName, "");
assert.equal(parse(product({ category: { id: "842248" } })).categoryName, "", "category IDs are not display names");

// Actual MY PDP shape: category_info belongs to the same component_data as
// product_info, rather than to product_model or product_info itself.
const actualCategoryInfo = {
  recommended_categories: [
    { category_id: "601152", category_name: "女装与女士内衣", category_name_en: "womenswear-underwear" },
    { category_id: "842248", category_name: "女士上装", category_name_en: "women-s-tops" },
    { category_id: "601302", category_name: "女士短袖", category_name_en: "women-s-t-shirts" }
  ]
};
const componentCategory = parse({
  page_config: { components_map: [
    { component_data: { product_info: { product_model: product({ product_id: "1730135047201523375" }) },
      category_info: { recommended_categories: [{ category_name: "Unrelated category" }] } } },
    { component_data: { product_info: { product_model: product() }, category_info: actualCategoryInfo } }
  ] }
});
assert.equal(componentCategory.categoryName, "女装与女士内衣 > 女士上装 > 女士短袖");
assert.equal(parse({
  page_config: { components_map: [
    { component_data: { product_info: { product_model: product({ product_id: "1730135047201523375" }) },
      category_info: actualCategoryInfo } },
    { component_data: { product_info: { product_model: product() } } }
  ] }
}).categoryName, "", "a foreign component's category_info must not leak into the current product");
assert.equal(parse({
  productInfo: { productModel: product() },
  categoryInfo: { recommendedCategories: [{ categoryName: "Women's T-Shirts" }] }
}).categoryName, "Women's T-Shirts");

const attributes = parse({
  product_model: product({
    product_properties: [],
    product_attributes: [
      { propertyName: "Product type", propertyValues: [{ valueName: "T-Shirt" }] },
      { attribute_name: "Material", values: [{ property_value_name: "Cotton" }, { name: "Polyester" }] },
      { name: "Stretch", value: false },
      { name: "Unknown", value: { id: 17 } }
    ]
  }),
  product_properties: { "Product type": "Incorrect older type", Sleeve: "Short", "Pack quantity": 0 }
}).attributes;
assert.deepEqual({ ...attributes }, {
  "Product type": "T-Shirt", Material: "Cotton, Polyester", Stretch: "false", Sleeve: "Short", "Pack quantity": "0"
});

async function select(bootstrap, response) {
  context.document.querySelectorAll = () => [{ textContent: JSON.stringify(bootstrap) }];
  context.HeyiCaptureBridge = { readEntries: async () => [{ value: response, sequence: 20 }] };
  return common.loadTikTokProductData({ productId });
}

const older = product({
  description: [{ type: "text", text: "Product details" }, { type: "image", image: { url: "https://example.com/detail.png" } }],
  category_name: "Womenswear > Women's T-Shirts",
  brand_name: "Example brand",
  product_attributes: { "Product type": "T-Shirt", Material: "Cotton" }
});
const current = product({
  name: "Current product",
  product_attributes: { Material: "Polyester" },
  skus: [{ id: "sku-1", price: { sale_price: "26", original_price: "32" } }]
});
const selected = await select(older, current);
assert.equal(selected.title, "Current product");
assert.equal(selected.categoryName, "Womenswear > Women's T-Shirts");
assert.equal(selected.brandName, "Example brand");
assert.deepEqual({ ...selected.attributes }, { Material: "Polyester", "Product type": "T-Shirt" });
assert.equal(selected.skus[0].price, 26);
assert.equal(selected.skus[0].originalPrice, 32);
assert.equal(selected.skus[0].stock, null, "metadata supplementation must not revive old stock");
assert.equal(selected.source, "TIKTOK_PAGE_RESPONSE");
assert.equal(selected.description, "Product details");
assert.deepEqual(Array.from(selected.detailImages), ["https://example.com/detail.png"]);

const changedDescription = await select(older, { ...current, description: "Current description without images" });
assert.equal(changedDescription.description, "Current description without images");
assert.deepEqual(Array.from(changedDescription.detailImages), [], "a current nonempty description must not revive older removed images");

const changedCategory = await select(older, { ...current, category_name: "Updated category", brand_name: "Updated brand" });
assert.equal(changedCategory.categoryName, "Updated category");
assert.equal(changedCategory.brandName, "Updated brand");

const differentSkus = await select(older, product({
  skus: [{ id: "sku-2", price: { sale_price: "26", original_price: "32" }, stock: 5 }]
}));
assert.equal(differentSkus.skus[0].externalSkuId, "sku-2");
assert.equal(differentSkus.categoryName, "", "different SKU snapshots must not contribute metadata");
assert.deepEqual({ ...differentSkus.attributes }, {});
assert.deepEqual(Array.from(differentSkus.detailImages), [], "different SKU snapshots must not contribute description images");

const differentShop = await select(older, { ...current, seller_id: "7000000000000000002" });
assert.equal(differentShop.categoryName, "", "conflicting seller identities must not contribute metadata");
assert.deepEqual(Array.from(differentShop.detailImages), []);

const foreignProduct = await select({ ...older, product_id: "1730135047201523375" }, current);
assert.equal(foreignProduct.categoryName, "", "other products must not contribute metadata");

console.log("TikTok metadata extraction and snapshot preservation tests passed.");
