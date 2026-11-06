import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const listeners = new Map();
const emitted = [];
const document = {
  addEventListener(type, listener) {
    const values = listeners.get(type) || [];
    values.push(listener);
    listeners.set(type, values);
  },
  dispatchEvent(event) {
    for (const listener of listeners.get(event.type) || []) listener(event);
    if (event.type.startsWith("heyi-collector-data-")) emitted.push(event);
    return true;
  }
};
class CustomEvent {
  constructor(type, options = {}) {
    this.type = type;
    this.detail = options.detail;
  }
}

const location = {
  href: "https://shopee.ph/example-i.123.456",
  origin: "https://shopee.ph",
  hostname: "shopee.ph",
  pathname: "/example-i.123.456",
  search: ""
};
let nextResponse;
const originalFetch = async () => nextResponse;
const context = vm.createContext({
  document,
  CustomEvent,
  location,
  URL,
  URLSearchParams,
  fetch: originalFetch,
  Date
});
vm.runInContext(readFileSync(new URL("../src/content/tiktok-observer-main.js", import.meta.url), "utf8"), context);

const channel = "12345678-1234-1234-1234-123456789012";
const nonce = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({ channel, nonce, generation: 1, platform: "SHOPEE", shopId: "123", productId: "456" })
}));

nextResponse = jsonResponse("https://shopee.ph/api/v4/pdp/get_pc?shop_id=123&item_id=456", {
  unrelated: "must-not-be-bridged",
  data: {
    item: { item_id: 456, shop_id: 123, models: [{ modelid: 1 }] },
    shop_detailed: { name: "Expected shop" }
  }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 1);
const envelope = JSON.parse(emitted[0].detail);
const bridged = JSON.parse(envelope.payload);
assert.equal(envelope.generation, 1);
assert.equal(envelope.nonce, nonce);
assert.equal(envelope.sequence, 2, "network data must be newer than recovered bootstrap state");
assert.equal(bridged.item.item_id, 456);
assert.equal(bridged.shop_detailed.name, "Expected shop");
assert.equal("unrelated" in bridged, false);

nextResponse = jsonResponse("https://shopee.ph/api/v4/pdp/get_pc?shop_id=123&item_id=999", {
  data: { item: { item_id: 999, shop_id: 123, models: [{ modelid: 2 }] } }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 1, "a mismatched item_id must not cross the bridge");

nextResponse = jsonResponse("https://shopee.ph/api/v4/pdp/get_pc?shop_id=999&item_id=456", {
  data: { item: { item_id: 456, shop_id: 999, models: [{ modelid: 2 }] } }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 1, "a mismatched shop_id must not cross the bridge");

nextResponse = jsonResponse("https://shopee.ph/api/v4/pdp/get_pc?shop_id=123&item_id=456", {
  data: { item: { item_id: 456, models: [{ modelid: 2 }] } }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 1, "an authoritative Shopee item without shop_id must not cross the bridge");

nextResponse = jsonResponse("https://evil.example/product/detail", {
  data: { item: { item_id: 456, shop_id: 123, models: [{ modelid: 3 }] } }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 1, "a cross-origin response must not cross the bridge");

location.href = "https://shop.tiktok.com/ph/pdp/model-12345678-red-1736046921727116747";
location.origin = "https://shop.tiktok.com";
location.hostname = "shop.tiktok.com";
location.pathname = "/ph/pdp/model-12345678-red-1736046921727116747";
location.search = "";
const tiktokNonce = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({
    channel,
    nonce: tiktokNonce,
    generation: 2,
    platform: "TIKTOK_SHOP",
    shopId: null,
    productId: "12345678"
  })
}));
nextResponse = jsonResponse("https://shop.tiktok.com/api/product/detail", {
  product_model: { product_id: "12345678", skus: [{ id: "wrong-slug-id" }] }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 1, "TikTok slug parsing must not select an earlier numeric slug fragment");

document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({
    channel,
    nonce: tiktokNonce,
    generation: 2,
    platform: "TIKTOK_SHOP",
    shopId: null,
    productId: "1736046921727116747"
  })
}));
nextResponse = jsonResponse("https://shop.tiktok.com/api/product/detail", {
  product_model: { product_id: "1736046921727116747", skus: [{ id: "right-slug-id" }] },
  category_model: { category_name: "Women > T-shirts" },
  product_properties: [{name:"Material",value:"Cotton"}]
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 2);
assert.equal(JSON.parse(emitted[1].detail).generation, 2);
const categoryEnvelope = JSON.parse(emitted[1].detail);
assert.ok(JSON.stringify(categoryEnvelope).includes("Women > T-shirts"));
assert.ok(JSON.stringify(categoryEnvelope).includes("Cotton"));

nextResponse = jsonResponse("https://shop.tiktok.com/api/promotion/get", {
  data: {
    product_id: "1736046921727116747",
    promotion_model: {
      promotion_product_price: {
        skus_price: {
          "right-slug-id": { sale_price_decimal: "8.00", origin_price_decimal: "19.00", currency_name: "MYR" }
        }
      }
    }
  }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 3, "an explicitly scoped promotion response must cross the bridge");
const pricePayload = JSON.parse(JSON.parse(emitted.at(-1).detail).payload);
assert.equal(pricePayload.product_id, "1736046921727116747");
assert.equal(pricePayload.promotion_model.promotion_product_price.skus_price["right-slug-id"].sale_price_decimal, "8.00");

nextResponse = jsonResponse("https://shop.tiktok.com/api/sku/price", {
  promotion_product_price: {
    skus_price: { "another-product-sku": { sale_price_decimal: "1.00" } }
  }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 3, "an unscoped promotion response with foreign SKU IDs must not cross the bridge");

nextResponse = jsonResponse("https://shop.tiktok.com/api/sku/price", {
  promotion_product_price: {
    skus_price: { "right-slug-id": { sale_price_decimal: "7.50", origin_price_decimal: "19.00" } }
  }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, 4, "an unscoped promotion response must match every SKU ID of the active product");
const emittedBeforeShopeeGenerationTest = emitted.length;

setShopeeLocation("item-a", "123", "456");
const firstANonce = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({
    channel, nonce: firstANonce, generation: 3,
    platform: "SHOPEE", shopId: "123", productId: "456"
  })
}));
let resolveOldResponse;
nextResponse = new Promise(resolve => { resolveOldResponse = resolve; });
const delayedOldFetch = context.fetch("https://shopee.ph/api/v4/pdp/get_pc?shop_id=123&item_id=456");

setShopeeLocation("item-b", "123", "789");
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({
    channel, nonce: "dddddddd-dddd-4ddd-8ddd-dddddddddddd", generation: 4,
    platform: "SHOPEE", shopId: "123", productId: "789"
  })
}));
setShopeeLocation("item-a", "123", "456");
const latestANonce = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({
    channel, nonce: latestANonce, generation: 5,
    platform: "SHOPEE", shopId: "123", productId: "456"
  })
}));
resolveOldResponse(jsonResponse("https://shopee.ph/api/v4/pdp/get_pc?shop_id=123&item_id=456", {
  data: { item: { item_id: 456, shop_id: 123, models: [{ modelid: "stale-a" }] } }
}));
await delayedOldFetch;
await settle();
assert.equal(emitted.length, emittedBeforeShopeeGenerationTest, "a response started in the first A generation must not enter the later A generation");

document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({
    channel, nonce: firstANonce, generation: 3,
    platform: "SHOPEE", shopId: "123", productId: "456"
  })
}));
nextResponse = jsonResponse("https://shopee.ph/api/v4/pdp/get_pc?shop_id=123&item_id=456", {
  data: { item: { item_id: 456, shop_id: 123, models: [{ modelid: "fresh-a" }] } }
});
await context.fetch(nextResponse.url);
await settle();
assert.equal(emitted.length, emittedBeforeShopeeGenerationTest + 1);
const latestEnvelope = JSON.parse(emitted.at(-1).detail);
assert.equal(latestEnvelope.generation, 5, "a delayed init retry must not roll MAIN back to an older generation");
assert.equal(latestEnvelope.nonce, latestANonce);

// A PDP response may finish before asynchronous extension authentication. A
// later collection can recover only the current item's page-owned bootstrap.
setShopeeLocation("recover-bootstrap", "123", "456");
const recoveryNonce = "ffffffff-ffff-4fff-8fff-ffffffffffff";
context.initialState = {
  privateSession: { token: "must-not-cross-the-bridge" },
  summary: { item_id: 456, shop_id: 123, models: [{ modelid: "summary" }] },
  DOMAIN_PDP: { data: { PDP_BFF_DATA: { cachedMap: {
    current: { item: { item_id: 456, shop_id: 123, models: [{ modelid: "full-1" }, { modelid: "full-2" }] }, shop_detailed: { name: "Recovered shop" } },
    foreign: { item: { item_id: 999, shop_id: 123, models: [{ modelid: "foreign-1" }] } }
  } } } }
};
const beforeRecovery = emitted.length;
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({ channel, nonce: recoveryNonce, generation: 6,
    platform: "SHOPEE", shopId: "123", productId: "456", recoverState: true })
}));
assert.equal(emitted.length, beforeRecovery + 1);
const recoveredEnvelope = JSON.parse(emitted.at(-1).detail);
const recoveredPayload = JSON.parse(recoveredEnvelope.payload);
assert.equal(recoveredEnvelope.sequence, 1);
assert.equal(recoveredEnvelope.source, "SHOPEE_PAGE_STATE");
assert.equal(recoveredPayload.item.models.length, 2);
assert.equal(recoveredPayload.shop_detailed.name, "Recovered shop");
assert.equal(recoveredEnvelope.payload.includes("must-not-cross-the-bridge"), false);
assert.equal(recoveredEnvelope.payload.includes("foreign-1"), false);
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({ channel, nonce: recoveryNonce, generation: 6,
    platform: "SHOPEE", shopId: "123", productId: "456", recoverState: true })
}));
assert.equal(emitted.length, beforeRecovery + 1, "identical bootstrap retries must not fill the response cache");
nextResponse = jsonResponse("https://shopee.ph/api/v4/pdp/get_pc?shop_id=123&item_id=456", {
  summary: { item_id: 456, shop_id: 123, models: [{ modelid: "summary" }] },
  data: { item: { item_id: 456, shop_id: 123, models: [{ modelid: "fresh-1" }, { modelid: "fresh-2" }] } }
});
await context.fetch(nextResponse.url);
await settle();
const afterRecoveryEnvelope = JSON.parse(emitted.at(-1).detail);
assert.equal(afterRecoveryEnvelope.sequence, 2);
assert.equal(JSON.parse(afterRecoveryEnvelope.payload).item.models[0].modelid, "fresh-1");

let pageGetterReads = 0;
Object.defineProperty(context, "__INITIAL_STATE__", { get() { pageGetterReads += 1; return context.initialState; } });
delete context.initialState;
setShopeeLocation("recover-foreign", "123", "789");
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({ channel, nonce: "12345678-aaaa-4fff-8fff-ffffffffffff", generation: 7,
    platform: "SHOPEE", shopId: "123", productId: "789", recoverState: true })
}));
assert.equal(pageGetterReads, 0, "state recovery must not invoke page-owned getters");
assert.equal(emitted.length, beforeRecovery + 2, "no matching current product means no recovered event");

const renderedModels = Array.from({ length: 96 }, (_, index) => ({ model_id: 371059261370 + index, price: 26_900_000, extinfo: { tier_index: [index % 16, index % 6] } }));
const renderedItem = { item_id: 47962221805, shop_id: 1500237133, models: renderedModels, description: "Current rendered product" };
const productFiber = { memoizedProps: { item: renderedItem, privateSession: "must-not-cross-rendered-bridge" }, return: null };
productFiber.return = productFiber; // Cycle must not turn recovery into an unbounded traversal.
document.querySelectorAll = selector => selector === "h1" ? [{
  "__reactFiber$fixture": { memoizedProps: { children: "Product title" }, return: { return: productFiber } }
}] : [];
setShopeeLocation("rendered-pdp", "1500237133", "47962221805");
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({ channel, nonce: "23456789-aaaa-4fff-8fff-ffffffffffff", generation: 8,
    platform: "SHOPEE", shopId: "1500237133", productId: "47962221805", recoverState: true })
}));
const renderedEnvelope = JSON.parse(emitted.at(-1).detail);
assert.equal(renderedEnvelope.generation, 8);
assert.equal(renderedEnvelope.source, "SHOPEE_RENDERED_PDP");
assert.equal(JSON.parse(renderedEnvelope.payload).item.models.length, 96);
assert.equal(renderedEnvelope.payload.includes("must-not-cross-rendered-bridge"), false);
assert.equal(renderedEnvelope.payload.includes("Current rendered product"), true);

// The MY PDP category_info is adjacent to product_info. Project only the
// component whose nested product_model identifies the currently open product.
location.href = "https://shop.tiktok.com/view/product/1730135047201523374?region=MY&locale=en";
location.origin = "https://shop.tiktok.com";
location.hostname = "shop.tiktok.com";
location.pathname = "/view/product/1730135047201523374";
location.search = "?region=MY&locale=en";
document.dispatchEvent(new CustomEvent("heyi-collector-observer-init-v1", {
  detail: JSON.stringify({ channel, nonce: "34567890-aaaa-4fff-8fff-ffffffffffff", generation: 9,
    platform: "TIKTOK_SHOP", shopId: null, productId: "1730135047201523374" })
}));
nextResponse = jsonResponse("https://shop.tiktok.com/api/product/detail", {
  components_map: [
    { component_data: { product_info: { product_model: { product_id: "1730135047201523375", skus: [{ id: "foreign" }] } },
      category_info: { recommended_categories: [{ category_name: "Foreign breadcrumb" }] } } },
    { component_data: {
      product_info: { product_model: { product_id: "1730135047201523374", skus: [{ id: "current" }] },
        promotion_model: { product_discount: "current discount" } },
      category_info: { recommended_categories: [{ category_id: "601302", category_name: "女士短袖" }] },
      reviews_info: { text: "Do not bridge unrelated component data" }
    } }
  ]
});
await context.fetch(nextResponse.url);
await settle();
const componentPayload = JSON.parse(JSON.parse(emitted.at(-1).detail).payload);
assert.equal(componentPayload.product_model.product_id, "1730135047201523374");
assert.equal(componentPayload.category_info.recommended_categories[0].category_name, "女士短袖");
assert.equal(componentPayload.promotion_model.product_discount, "current discount");
assert.equal(JSON.stringify(componentPayload).includes("Foreign breadcrumb"), false);
assert.equal("reviews_info" in componentPayload, false);

console.log("Response observer identity guard tests passed");

function jsonResponse(url, value) {
  return {
    ok: true,
    url,
    clone() {
      return { text: async () => JSON.stringify(value) };
    }
  };
}

async function settle() {
  await new Promise(resolve => setTimeout(resolve, 0));
  await new Promise(resolve => setTimeout(resolve, 0));
}

function setShopeeLocation(slug, shopId, productId) {
  location.href = `https://shopee.ph/${slug}-i.${shopId}.${productId}`;
  location.origin = "https://shopee.ph";
  location.hostname = "shopee.ph";
  location.pathname = `/${slug}-i.${shopId}.${productId}`;
  location.search = "";
}
