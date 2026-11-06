import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const documentListeners = new Map();
const windowListeners = new Map();
const initEvents = [];
let intervalCallback = null;
let authenticatedState = true;
let reloadCount = 0;
let contextInvalidated = false;
let clearedInterval = null;

const document = {
  addEventListener(type, listener) {
    const listeners = documentListeners.get(type) || [];
    listeners.push(listener);
    documentListeners.set(type, listeners);
  },
  dispatchEvent(event) {
    for (const listener of documentListeners.get(event.type) || []) listener(event);
    if (event.type === "heyi-collector-observer-init-v1") initEvents.push(JSON.parse(event.detail));
    return true;
  }
};

class CustomEvent {
  constructor(type, options = {}) {
    this.type = type;
    this.detail = options.detail;
  }
}

const window = {
  setTimeout(callback, delay = 0) {
    if (delay === 0) queueMicrotask(callback);
    return 1;
  },
  setInterval(callback) {
    intervalCallback = callback;
    return 1;
  },
  clearInterval(id) {
    clearedInterval = id;
  },
  addEventListener(type, listener) {
    const listeners = windowListeners.get(type) || [];
    listeners.push(listener);
    windowListeners.set(type, listeners);
  }
};

const location = {
  href: "https://shopee.ph/item-a-i.123.456",
  origin: "https://shopee.ph",
  hostname: "shopee.ph",
  pathname: "/item-a-i.123.456",
  search: "",
  reload() {
    reloadCount += 1;
  }
};

let uuidCounter = 0;
const crypto = {
  randomUUID() {
    uuidCounter += 1;
    return `00000000-0000-4000-8000-${String(uuidCounter).padStart(12, "0")}`;
  }
};

const chrome = {
  runtime: {
    sendMessage() {
      if (contextInvalidated) throw new Error("Extension context invalidated.");
      return Promise.resolve({ ok: true, data: { authenticated: authenticatedState, accessToken: "must-not-cross-main-world" } });
    }
  }
};

const context = vm.createContext({
  chrome,
  crypto,
  CustomEvent,
  Date,
  document,
  location,
  Map,
  Number,
  Promise,
  queueMicrotask,
  Set,
  String,
  TextEncoder,
  URLSearchParams,
  window
});
vm.runInContext(readFileSync(new URL("../src/content/tiktok-observer-client.js", import.meta.url), "utf8"), context);
await settle();

assert.ok(intervalCallback, "the SPA identity watcher must be installed");
const first = latestInit();
assert.equal(first.generation, 1);
assert.equal(first.productId, "456");
assert.ok(first.nonce);
assert.equal("token" in first, false);
assert.equal("accessToken" in first, false);

emitPayload(first, 1, { marker: "old" });
emitPayload(first, 2, { marker: "new" });
assert.deepEqual(
  Array.from(await context.HeyiCaptureBridge.read("456"), value => value.marker),
  ["new", "old"],
  "newer requests in one activation must be returned first"
);
assert.deepEqual(
  Array.from(await context.HeyiCaptureBridge.readEntries("456"), entry => entry.sequence),
  [2, 1],
  "bridge metadata must preserve response freshness for candidate selection"
);

setShopeeLocation("item-b", "123", "789");
await intervalCallback();
await settle();
const second = latestInit();
assert.equal(second.generation, 2);
assert.equal(second.productId, "789");
emitPayload(second, 1, { marker: "b" });

setShopeeLocation("item-a", "123", "456");
await intervalCallback();
await settle();
const third = latestInit();
assert.equal(third.generation, 3);
assert.equal(third.productId, "456");
assert.deepEqual(
  Array.from(await context.HeyiCaptureBridge.read("456")),
  [],
  "A to B to A must not reuse the first A activation cache"
);

emitPayload(first, 99, { marker: "late-first-a" });
assert.deepEqual(Array.from(await context.HeyiCaptureBridge.read("456")), []);
emitPayload(third, 1, { marker: "third-a" });
assert.deepEqual(
  Array.from(await context.HeyiCaptureBridge.read("456"), value => value.marker),
  ["third-a"]
);

const padding = "x".repeat(1_450_000);
emitPayload(third, 10, { marker: "large-10", padding });
emitPayload(third, 11, { marker: "large-11", padding });
emitPayload(third, 12, { marker: "large-12", padding });
assert.deepEqual(
  Array.from(await context.HeyiCaptureBridge.read("456"), value => value.marker),
  ["large-12", "large-11"],
  "the total byte ceiling must evict oldest cached responses"
);

emitPayload(third, 1, { item: { item_id: 456, shop_id: 123, models: [{ modelid: "rendered", stock: 10 }] } }, "SHOPEE_RENDERED_PDP");
for (let sequence = 20; sequence <= 30; sequence += 1) {
  emitPayload(third, sequence, { item: { item_id: 456, shop_id: 123, models: [{ modelid: "rendered", stock: null }] } });
}
const retainedRendered = await context.HeyiCaptureBridge.readEntries("456");
assert.equal(retainedRendered.length, 8);
assert.equal(retainedRendered[0].sequence, 30);
assert.equal(retainedRendered.at(-1).source, "SHOPEE_RENDERED_PDP", "retain the current page inventory alongside the newest response");
assert.equal(retainedRendered.at(-1).value.item.models[0].stock, 10);

setTikTokLocation("/ph/pdp/model-12345678-red-1736046921727116747");
await intervalCallback();
await settle();
const tiktokActivation = latestInit();
assert.equal(tiktokActivation.generation, 4);
assert.equal(tiktokActivation.productId, "1736046921727116747");

emitPayload(tiktokActivation, 1, {
  product_model: {
    product_id: "1736046921727116747",
    skus: [{ id: "sku-current-product" }]
  }
});
for (let sequence = 2; sequence <= 11; sequence += 1) {
  emitPayload(tiktokActivation, sequence, {
    product_id: "1736046921727116747",
    promotion_model: {
      promotion_product_price: {
        skus_price: { "sku-current-product": { sale_price_decimal: `${sequence}.00` } }
      }
    }
  });
}
const retainedTikTokEntries = await context.HeyiCaptureBridge.readEntries("1736046921727116747");
assert.equal(retainedTikTokEntries.length, 8);
assert.deepEqual(
  Array.from(retainedTikTokEntries, entry => entry.sequence),
  [11, 10, 9, 8, 7, 6, 5, 1],
  "promotion-only responses must not evict the current generation's only product base"
);
assert.equal(retainedTikTokEntries.at(-1).kind, "PRODUCT");

authenticatedState = false;
await intervalCallback();
authenticatedState = true;
await intervalCallback();
await settle();
assert.equal(reloadCount, 1, "a product opened before login must refresh once after authentication");

contextInvalidated = true;
await intervalCallback();
assert.equal(clearedInterval, 1, "an invalidated extension context must stop the authentication poller");
assert.deepEqual(Array.from(await context.HeyiCaptureBridge.read("1736046921727116747")), []);

console.log("Response observer generation and cache tests passed");

function latestInit() {
  assert.ok(initEvents.length > 0);
  return initEvents.at(-1);
}

function emitPayload(activation, sequence, value, source = null) {
  const envelope = {
    platform: activation.platform,
    shopId: activation.shopId,
    productId: activation.productId,
    generation: activation.generation,
    nonce: activation.nonce,
    sequence,
    source,
    payload: JSON.stringify(value)
  };
  document.dispatchEvent(new CustomEvent(`heyi-collector-data-${activation.channel}`, {
    detail: JSON.stringify(envelope)
  }));
}

function setShopeeLocation(slug, shopId, productId) {
  location.href = `https://shopee.ph/${slug}-i.${shopId}.${productId}`;
  location.origin = "https://shopee.ph";
  location.hostname = "shopee.ph";
  location.pathname = `/${slug}-i.${shopId}.${productId}`;
  location.search = "";
}

function setTikTokLocation(pathname) {
  location.href = `https://shop.tiktok.com${pathname}`;
  location.origin = "https://shop.tiktok.com";
  location.hostname = "shop.tiktok.com";
  location.pathname = pathname;
  location.search = "";
}

async function settle() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}
