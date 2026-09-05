(() => {
  "use strict";

  const INIT_EVENT = "heyi-collector-observer-init-v1";
  const MAX_CACHE_KEYS = 4;
  const MAX_CACHE_BYTES = 4_000_000;
  const MAX_VALUES_PER_KEY = 8;
  const MAX_PAYLOAD_BYTES = 2_000_000;
  const channel = crypto.randomUUID();
  const values = new Map();
  const waiters = new Map();
  let activeIdentity = null;
  let authenticated = false;
  let observedUnauthenticated = false;
  let reloadRequested = false;
  let destroyed = false;
  let generation = 0;
  let cachedBytes = 0;
  let authenticationInterval = null;

  globalThis.HeyiCaptureBridge = {
    async activate() {
      return refreshAuthentication();
    },

    async read(productId, waitMs = 0) {
      const entries = await this.readEntries(productId, waitMs);
      return entries.map(entry => entry.value);
    },

    async readEntries(productId, waitMs = 0) {
      if (!(await this.activate())) return [];
      synchronizeLocation(true);
      if (productId !== activeIdentity?.productId) return [];
      const key = activationKey(activeIdentity);
      const existing = cachedEntries(key);
      if (existing.length || !waitMs) return existing;
      await new Promise(resolve => {
        const resolvers = waiters.get(key) || new Set();
        resolvers.add(resolve);
        waiters.set(key, resolvers);
        window.setTimeout(() => {
          resolvers.delete(resolve);
          if (resolvers.size === 0) waiters.delete(key);
          resolve();
        }, Math.min(Math.max(Number(waitMs) || 0, 0), 3_000));
      });
      return cachedEntries(key);
    }
  };

  document.addEventListener(`heyi-collector-data-${channel}`, event => {
    if (!authenticated) return;
    let envelope;
    try {
      const detail = String(event.detail || "");
      if (detail.length > 2_100_000) return;
      envelope = JSON.parse(detail);
      synchronizeLocation(false);
      const current = productIdentityFromLocation();
      if (envelope.productId !== current?.productId || envelope.productId !== activeIdentity?.productId
        || envelope.platform !== current?.platform || envelope.platform !== activeIdentity?.platform
        || (current?.shopId && envelope.shopId !== current.shopId)
        || envelope.generation !== activeIdentity?.generation
        || envelope.nonce !== activeIdentity?.nonce
        || !Number.isSafeInteger(envelope.sequence) || envelope.sequence < 1) return;
      if (typeof envelope.payload !== "string" || envelope.payload.length > MAX_PAYLOAD_BYTES) return;
      const payloadBytes = utf8Bytes(envelope.payload);
      if (payloadBytes > MAX_PAYLOAD_BYTES || payloadBytes > MAX_CACHE_BYTES) return;
      const parsed = JSON.parse(envelope.payload);
      const key = activationKey(activeIdentity);
      const kind = isCurrentProductBase(parsed, activeIdentity) ? "PRODUCT" : "FRAGMENT";
      storeValue(key, parsed, payloadBytes, envelope.sequence, kind);
      for (const resolve of waiters.get(key) || []) resolve();
      waiters.delete(key);
    } catch {
      // The page is untrusted; malformed bridge events are ignored.
    }
  });

  authenticationInterval = window.setInterval(() => {
    void refreshAuthentication().catch(() => {});
  }, 1_000);
  void globalThis.HeyiCaptureBridge.activate().catch(() => {});

  for (const type of ["popstate", "hashchange"]) {
    window.addEventListener?.(type, () => window.setTimeout(() => synchronizeLocation(true), 0));
  }
  globalThis.navigation?.addEventListener?.("navigate", () => {
    window.setTimeout(() => synchronizeLocation(true), 0);
  });

  function synchronizeLocation(refreshMain) {
    if (!authenticated) return;
    const identity = productIdentityFromLocation();
    const href = String(location.href || "");
    if (!identity) {
      if (activeIdentity) deactivate(false);
      return;
    }
    if (!activeIdentity || identityKey(identity) !== identityKey(activeIdentity)
      || activeIdentity.locationHref !== href) {
      activateForLocation(identity, href);
      return;
    }
    if (refreshMain) announceActivation(activeIdentity, [0]);
  }

  function activateForLocation(identity, href) {
    resolveAllWaiters();
    generation = generation >= Number.MAX_SAFE_INTEGER ? 1 : generation + 1;
    activeIdentity = {
      ...identity,
      locationHref: href,
      generation,
      nonce: crypto.randomUUID()
    };
    ensureCacheKey(activationKey(activeIdentity));
    announceActivation(activeIdentity, [0, 120, 600]);
  }

  function announceActivation(identity, delays) {
    const detail = JSON.stringify({
      channel,
      platform: identity.platform,
      shopId: identity.shopId || null,
      productId: identity.productId,
      generation: identity.generation,
      nonce: identity.nonce
    });
    for (const delay of delays) {
      window.setTimeout(() => document.dispatchEvent(new CustomEvent(INIT_EVENT, { detail })), delay);
    }
  }

  function deactivate(clearValues = true) {
    activeIdentity = null;
    resolveAllWaiters();
    if (!clearValues) return;
    values.clear();
    cachedBytes = 0;
  }

  function resolveAllWaiters() {
    for (const resolvers of waiters.values()) {
      for (const resolve of resolvers) resolve();
    }
    waiters.clear();
  }

  async function refreshAuthentication() {
    if (destroyed) return false;
    let response;
    try {
      response = await chrome.runtime.sendMessage({ type: "HEYI_GET_STATE", refresh: false });
    } catch (error) {
      if (/extension context invalidated/i.test(String(error?.message || error))) {
        stopAfterContextInvalidation();
        return false;
      }
      return authenticated;
    }
    // A transient extension messaging failure is not proof that the user
    // logged out. Only an explicit state response may change authentication.
    if (!response?.ok) return authenticated;
    const nextAuthenticated = Boolean(response.data?.authenticated);
    if (!nextAuthenticated) {
      authenticated = false;
      observedUnauthenticated = true;
      reloadRequested = false;
      deactivate();
      return false;
    }

    authenticated = true;
    const identity = productIdentityFromLocation();
    if (observedUnauthenticated && identity && !reloadRequested && typeof location.reload === "function") {
      // Responses which completed before login cannot be replayed. Refresh the
      // already-open product once, after authentication, so document_start can
      // observe a fresh, identity-bound response without asking the user to do it.
      reloadRequested = true;
      resolveAllWaiters();
      window.setTimeout(() => location.reload(), 0);
      return false;
    }
    observedUnauthenticated = false;
    synchronizeLocation(true);
    return true;
  }

  function stopAfterContextInvalidation() {
    if (destroyed) return;
    destroyed = true;
    authenticated = false;
    deactivate();
    if (authenticationInterval !== null) window.clearInterval?.(authenticationInterval);
    authenticationInterval = null;
  }

  function cachedEntries(key) {
    return (values.get(key) || []).slice(0, MAX_VALUES_PER_KEY).map(entry => ({
      value: entry.value,
      sequence: entry.sequence,
      arrival: entry.arrival,
      kind: entry.kind
    }));
  }

  function ensureCacheKey(key) {
    if (!key || values.has(key)) return;
    values.set(key, []);
    enforceCacheLimits(key);
  }

  function storeValue(key, value, bytes, sequence, kind) {
    ensureCacheKey(key);
    const stored = values.get(key);
    stored.push({ value, bytes, sequence, arrival: Date.now(), kind });
    cachedBytes += bytes;
    stored.sort((left, right) => right.sequence - left.sequence || right.arrival - left.arrival);
    while (stored.length > MAX_VALUES_PER_KEY) {
      const removed = removeOldestEntry(stored);
      if (!removed) break;
      cachedBytes -= removed.bytes;
    }
    enforceCacheLimits(key);
  }

  function enforceCacheLimits(currentKey) {
    while (values.size > MAX_CACHE_KEYS) removeCacheKey(values.keys().next().value);
    while (cachedBytes > MAX_CACHE_BYTES && values.size) {
      const oldestKey = values.keys().next().value;
      if (oldestKey !== currentKey || values.size > 1) {
        removeCacheKey(oldestKey);
        continue;
      }
      const stored = values.get(currentKey) || [];
      const removed = removeOldestEntry(stored);
      if (!removed) break;
      cachedBytes -= removed.bytes;
    }
  }

  function removeOldestEntry(stored) {
    if (!stored.length) return null;
    const protectedProduct = stored.findIndex(entry => entry.kind === "PRODUCT");
    for (let index = stored.length - 1; index >= 0; index -= 1) {
      if (index === protectedProduct) continue;
      return stored.splice(index, 1)[0];
    }
    return null;
  }

  function isCurrentProductBase(root, identity) {
    if (!root || typeof root !== "object" || !identity) return false;
    const stack = [{ value: root, depth: 0 }];
    let inspected = 0;
    while (stack.length && inspected < 12_000) {
      const { value, depth } = stack.pop();
      inspected += 1;
      if (!value || typeof value !== "object" || depth > 14) continue;
      if (!Array.isArray(value)) {
        if (identity.platform === "SHOPEE") {
          const item = value.item && typeof value.item === "object" ? value.item : value;
          const itemId = item.item_id ?? item.itemid;
          const shopId = item.shop_id ?? item.shopid;
          if (Array.isArray(item.models) && String(itemId ?? "") === identity.productId
            && String(shopId ?? "") === identity.shopId) return true;
        } else if (identity.platform === "TIKTOK_SHOP") {
          const product = value.product_model || value.productModel || value;
          const skus = tikTokSkuArray(product);
          const productId = product.product_id ?? product.productId ?? (skus ? product.id : null);
          if (skus && String(productId ?? "") === identity.productId) return true;
        }
      }
      const children = Array.isArray(value) ? value.slice(0, 500) : Object.values(value).slice(0, 500);
      for (let index = children.length - 1; index >= 0; index -= 1) {
        const child = children[index];
        if (child && typeof child === "object") stack.push({ value: child, depth: depth + 1 });
      }
    }
    return false;
  }

  function tikTokSkuArray(product) {
    const direct = product?.skus || product?.sku_infos || product?.skuInfos || product?.sku_list || product?.skuList;
    if (Array.isArray(direct)) return direct;
    const container = product?.sku_info || product?.skuInfo || product?.sku_data || product?.skuData;
    if (!container || typeof container !== "object" || Array.isArray(container)) return null;
    const nested = container.skus || container.sku_infos || container.skuInfos || container.sku_list || container.skuList;
    return Array.isArray(nested) ? nested : null;
  }

  function removeCacheKey(key) {
    for (const entry of values.get(key) || []) cachedBytes -= entry.bytes;
    values.delete(key);
    for (const resolve of waiters.get(key) || []) resolve();
    waiters.delete(key);
  }

  function utf8Bytes(value) {
    return new TextEncoder().encode(value).byteLength;
  }

  function productIdentityFromLocation() {
    const host = location.hostname.toLowerCase();
    if (/(^|\.)shopee\.(sg|ph|vn)$/.test(host) || /(^|\.)shopee\.com\.my$/.test(host)
      || /(^|\.)shopee\.co\.(th|id)$/.test(host)) {
      const direct = location.pathname.match(/-i\.(\d+)\.(\d+)(?:$|[/?])/i)
        || location.pathname.match(/\/product\/(\d+)\/(\d+)(?:$|[/?])/i);
      if (direct) return { platform: "SHOPEE", shopId: direct[1], productId: direct[2] };
      const search = new URLSearchParams(location.search);
      const shopId = search.get("shopid") || search.get("shop_id");
      const productId = search.get("itemid") || search.get("item_id");
      return /^\d{1,32}$/.test(shopId || "") && /^\d{1,32}$/.test(productId || "")
        ? { platform: "SHOPEE", shopId, productId }
        : null;
    }
    let path;
    try {
      path = decodeURIComponent(location.pathname);
    } catch {
      path = location.pathname;
    }
    const direct = path.match(/\/view\/product\/(\d{6,32})/i);
    const pdpPath = path.match(/\/(?:(?:[a-z]{2}(?:-[a-z]{2})?)\/)?(?:shop\/)?(?:pdp|product)\/(.+?)\/?$/i);
    const pdp = pdpPath?.[1]?.match(/(\d{8,32})$/);
    const query = new URLSearchParams(location.search).get("product_id")
      || new URLSearchParams(location.search).get("productId");
    const productId = direct?.[1] || pdp?.[1] || (/^\d{6,32}$/.test(query || "") ? query : "");
    return productId ? { platform: "TIKTOK_SHOP", shopId: null, productId } : null;
  }

  function identityKey(identity) {
    return identity ? `${identity.platform}|${identity.shopId || ""}|${identity.productId}` : "";
  }

  function activationKey(identity) {
    return identity ? `${identityKey(identity)}|${identity.generation}|${identity.nonce}` : "";
  }
})();
