import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { webcrypto } from "node:crypto";
import vm from "node:vm";

// Can also run against the previously published package to prove regressions.
const root = process.env.COLLECTOR_TEST_SOURCE_ROOT
  ? new URL(`file://${process.env.COLLECTOR_TEST_SOURCE_ROOT.replace(/\/$/, "")}/`)
  : new URL("../", import.meta.url);
const values = new Map();
const store = {
  get: async key => ({ [key]: values.get(key) }),
  set: async entries => { for (const [key, value] of Object.entries(entries)) values.set(key, value); },
  remove: async key => { values.delete(key); },
  setAccessLevel: async () => {},
};
globalThis.chrome = {
  storage: { local: store },
  runtime: { id: "test-extension", getManifest: () => ({ version: "0.2.8" }) },
};
const storage = await import(new URL("src/background/storage.js", root));
const constants = await import(new URL("src/shared/constants.js", root));
const api = await import(new URL("src/background/api.js", root));
const now = Date.now;
const originalFetch = globalThis.fetch;
const serverNow = now();
const expiresAt = new Date(serverNow + 8 * 3600000).toISOString();
const token = "fixture-credential-never-display";
try {
  for (const skew of [12 * 3600000, -12 * 3600000, 0]) {
    values.clear();
    Date.now = () => serverNow + skew;
    globalThis.fetch = async () => new Response(JSON.stringify({
      data: { accessToken: token, expiresAt, permissions: ["product:collect"], user: { displayName: "Fixture" } },
    }), { headers: { "content-type": "application/json", date: new Date(serverNow).toUTCString() } });
    const auth = await api.loginWithPassword("https://hoyee.org.cn", "fixture", "not-a-password", "fixture");
    assert.equal(auth.expiresAt, expiresAt, "retain the platform expiry without extending the token");
    assert.equal((await storage.readAuth()).accessToken, token, "clock skew must not discard a valid server token");
    assert.equal(constants.safeAuthView({}, auth).authenticated, true);
    // Pending logout credentials must use the same server clock basis.
    assert.equal((await storage.enqueuePendingRevocation(auth)).queued, true);
    assert.equal((await storage.readPendingRevocations()).length, 1);
    Date.now = () => serverNow + skew + 9 * 3600000;
    assert.equal(await storage.readAuth(), null, "genuinely expired credentials still clear");
    assert.equal((await storage.readPendingRevocations()).length, 0);
  }
  Date.now = now;
  values.clear();
  values.set(constants.STORAGE_KEYS.AUTH, {
    baseUrl: "https://hoyee.org.cn", accessToken: token, expiresAt,
    permissions: { legacy: true },
  });
  assert.deepEqual((await storage.readAuth()).permissions, []);
  assert.deepEqual((await storage.updateAuthProfile(null)).permissions, []);
  assert.equal((await storage.readAuth()).accessToken, token, "metadata repair preserves the active credential");
  globalThis.fetch = async () => new Response("<html>proxy login</html>", { status: 200 });
  await assert.rejects(() => api.refreshCurrentUser(), error => error.code === "INVALID_RESPONSE");
  assert.equal((await storage.readAuth()).accessToken, token, "a proxy response must not erase the login");
  globalThis.fetch = async () => new Response(JSON.stringify({ message: "Expired" }), { status: 401 });
  await assert.rejects(() => api.refreshCurrentUser(), error => error.status === 401);
  assert.equal(await storage.readAuth(), null, "server revocation remains authoritative");
} finally {
  Date.now = now;
  globalThis.fetch = originalFetch;
}

let source = readFileSync(new URL("src/background/service-worker.js", root), "utf8")
  .replace(/^import[\s\S]*?from\s+["'][^"']+["'];\n/gm, "");
source += "\nglobalThis.fixture = { captureProduct, publicError };";
let saved = 0;
const noop = () => {};
const context = vm.createContext({
  URL, URLSearchParams, TextEncoder, crypto: webcrypto, Date,
  navigator: {}, ApiError: api.ApiError, MESSAGE_TYPES: constants.MESSAGE_TYPES,
  chrome: {
    runtime: { onInstalled: { addListener: noop }, onStartup: { addListener: noop }, onMessage: { addListener: noop } },
    storage: { session: { setAccessLevel: async () => {} } },
    action: { setBadgeBackgroundColor: async () => { throw new Error("browser badge unavailable"); } },
  },
  restrictStorageToExtensionPages: async () => {},
  retryPendingRevocations: async () => ({ resolved: [] }),
  createCapture: async () => { saved++; return { id: "saved-product", status: "CAPTURED" }; },
  archiveCaptureVideos: async () => ({ complete: true }),
  deduplicateVideos: globalThis.HeyiVideoSources.deduplicate,
});
vm.runInContext(source, context);
const result = await context.fixture.captureProduct({
  sourcePlatform: "SHOPEE", sourceUrl: "https://shopee.ph/product/123/456",
  sourceShopId: "123", sourceProductId: "456", title: "Fixture product",
}, "https://shopee.ph/product/123/456");
assert.equal(saved, 1);
assert.equal(result.id, "saved-product", "badge failure must not report a saved capture as failed");
const error = context.fixture.publicError(new Error(`secret ${token}`), "HEYI_CAPTURE_PRODUCT");
assert.equal(error.code, "LOCAL_CAPTURE_FAILED");
assert.ok(!JSON.stringify(error).includes(token), "never expose raw native errors or credentials");
assert.equal(context.fixture.publicError(new Error("QUOTA_BYTES quota exceeded")).code, "LOCAL_STORAGE_UNAVAILABLE");
assert.equal(context.fixture.publicError(Object.assign(new Error("private detail"), {
  code: "BROWSER_STORAGE_UNSUPPORTED",
})).code, "BROWSER_STORAGE_UNSUPPORTED");
console.log("Local environment regressions passed: +/-12h clock, legacy cache, proxy response, revocation, badge failure and safe errors.");
