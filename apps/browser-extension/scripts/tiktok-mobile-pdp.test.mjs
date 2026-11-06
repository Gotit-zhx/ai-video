import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

// Reduced public-product fixture from the PH mobile PDP, 2026-10-13.
// IDs, option associations and prices are retained; CDN URLs/text are shortened.
const fixture = JSON.parse(readFileSync(new URL("./fixtures/tiktok-mobile-pdp.json", import.meta.url)));
const common = {
  cleanText: (value, limit = 2_000) => value == null ? "" : String(value).replace(/\s+/g, " ").trim().slice(0, limit),
  absoluteUrl: value => typeof value === "string" && value.startsWith("https://") ? value : ""
};
const context = vm.createContext({ HeyiCollector: common, URL, document: { querySelectorAll: () => [] } });
vm.runInContext(readFileSync(new URL("../src/content/tiktok-data.js", import.meta.url), "utf8"), context);
const parse = value => common.parseTikTokProductData(value, { productId: fixture.product_id });

const parsed = parse(fixture);
assert.equal(parsed.skuCompleteness.complete, true);
assert.equal(parsed.skus.length, 28);
assert.equal(new Set(parsed.skus.map(sku => sku.attributes.Color)).size, 7);
assert.equal(new Set(parsed.skus.map(sku => sku.attributes.Size)).size, 4);
assert.equal(parsed.skuCompleteness.variantDimensionCount, 2);
assert.equal(parsed.skus[0].variationName, "apricot / S");
assert.equal(parsed.skus[0].price, 146.46);
assert.equal(parsed.skus[0].originalPrice, 377.8);
assert.equal(parsed.skus[0].stock, 999);
assert.equal(parsed.skus.at(-1).variationName, "PURE/WHITE / XL");
assert.equal(parsed.skus.at(-1).price, 64.4);
assert.equal(parsed.skus.at(-1).originalPrice, 220);
assert.equal(parsed.skus.at(-1).status, "0");
assert.equal(parsed.currency, "PHP");
assert.deepEqual([parsed.priceMin, parsed.priceMax], [64.4, 146.46]);
assert.ok(parsed.skus.every(sku => sku.imageSource === "VARIANT_OPTION" && !sku.imageInherited));
assert.equal(new Set(parsed.skus.map(sku => sku.imageUrl)).size, 7);
assert.equal(parsed.skus.at(-1).imageUrl, fixture.sale_props[0].sale_prop_values.at(-1).image.url_list[0]);
assert.equal(parsed.description, "Cotton crew neck T-shirt.");
assert.equal(parsed.detailImages.length, 3, "description images and the size guide are retained");
assert.ok(parsed.detailImages.includes(fixture.size_guide.image.url_list[0]));
assert.equal(parsed.attributes.Materials, "Cotton");
assert.equal(parsed.attributes.Fit, "Fitted");
assert.equal(parsed.variantHints[0].values.length, 7);

const idsOnly = structuredClone(fixture);
for (const sku of idsOnly.skus) delete sku.sku_sale_props;
const fromIds = parse(idsOnly);
assert.equal(fromIds.skuCompleteness.complete, true);
assert.equal(fromIds.skus.at(-1).variationName, "PURE/WHITE / XL");
assert.equal(fromIds.skus.at(-1).imageUrl, parsed.skus.at(-1).imageUrl);

const missingPrice = structuredClone(fixture);
delete missingPrice.skus[0].price.sale_price_decimal;
assert.equal(parse(missingPrice).skus[0].price, null, "do not invent SKU prices from product range or formatted display");
assert.equal(parse(missingPrice).skuCompleteness.complete, false);
const missingOption = structuredClone(idsOnly);
missingOption.skus[0].sale_prop_value_ids = "unknown_7654551397714773774";
assert.equal(parse(missingOption).skuCompleteness.complete, false);
assert.equal(parse({ ...fixture, product_id: "9999999999999999999" }), null);

context.document.querySelectorAll = () => [{ textContent: JSON.stringify({ loaderData: { page: { page_config: {
  components_map: [{ component_data: { product: fixture } }]
} } } }) }];
const loaded = await common.loadTikTokProductData({ productId: fixture.product_id });
assert.equal(loaded.skuCompleteness.complete, true);
assert.equal(loaded.skus.length, 28);
assert.equal(loaded.description, parsed.description);
console.log("TikTok mobile PDP: 28 complete SKUs, content, images, ID fallback and incomplete-data guards passed.");
