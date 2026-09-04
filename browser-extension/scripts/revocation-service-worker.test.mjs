import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFileSync } from "node:fs";
import vm from "node:vm";

let source = readFileSync(new URL("../src/background/service-worker.js", import.meta.url), "utf8")
  .replace(/^import[\s\S]*?from\s+["'][^"']+["'];\n/gm, "");
source += `
globalThis.HeyiRevocationWorkerTest = {
  savePlatform,
  pair,
  logoutAndRevokePermission,
  retryRevocationsInBackground
};`;

let activeAuth = null;
let settings = { baseUrl: "https://old.example", deviceName: "Old device" };
let pendingCount = 1;
let retryResult = { attempted: 0, revoked: 0, pendingRevocationCount: 1, resolved: [] };
let retryCalls = 0;
const logoutOptions = [];
const removedOrigins = [];
let startupListener = null;
let onlineListener = null;
const noop = () => {};

class TestApiError extends Error {
  constructor(message, status = 0, code = "REQUEST_FAILED") {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const chrome = {
  runtime: {
    id: "revocation-worker-test",
    onInstalled: { addListener: noop },
    onStartup: { addListener: listener => { startupListener = listener; } },
    onMessage: { addListener: noop },
    getURL: value => `chrome-extension://revocation-worker-test/${value}`
  },
  storage: {
    session: {
      setAccessLevel: async () => {},
      get: async () => ({}),
      set: async () => {},
      remove: async () => {}
    }
  },
  permissions: {
    contains: async () => true,
    remove: async ({ origins }) => {
      removedOrigins.push(...origins);
      return true;
    }
  },
  action: {
    setBadgeBackgroundColor: async () => {},
    setBadgeText: async () => {}
  },
  tabs: { create: async () => ({}) }
};

const context = vm.createContext({
  URL,
  URLSearchParams,
  TextEncoder,
  crypto: webcrypto,
  Date,
  navigator: { platform: "Test" },
  chrome,
  addEventListener: (type, listener) => {
    if (type === "online") onlineListener = listener;
  },
  MESSAGE_TYPES: {
    GET_STATE: "GET_STATE",
    SAVE_PLATFORM: "SAVE_PLATFORM",
    OPEN_PLATFORM: "OPEN_PLATFORM",
    PAIR: "PAIR",
    LOGOUT: "LOGOUT",
    OPEN_PRODUCT_DETAIL: "OPEN_PRODUCT_DETAIL",
    TAKE_PENDING_CAPTURE: "TAKE_PENDING_CAPTURE",
    CAPTURE: "CAPTURE"
  },
  normalizePlatformUrl: value => new URL(value).origin,
  originPattern: value => `${new URL(value).origin}/*`,
  safeAuthView: (currentSettings, auth) => ({
    baseUrl: currentSettings.baseUrl,
    authenticated: Boolean(auth),
    user: auth?.user || null,
    permissions: auth?.permissions || []
  }),
  ApiError: TestApiError,
  createCapture: async () => ({}),
  exchangePairingCode: async (baseUrl, _code, deviceName) => {
    activeAuth = {
      baseUrl,
      accessToken: "new-active-token-must-not-leak",
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      user: { displayName: deviceName },
      permissions: ["product:collect"]
    };
    return activeAuth;
  },
  logoutCurrentDevice: async options => {
    logoutOptions.push(options);
    const hadCredential = Boolean(activeAuth);
    activeAuth = null;
    return {
      hadCredential,
      revoked: !hadCredential,
      deferred: hadCredential,
      pendingRevocationCount: pendingCount
    };
  },
  retryPendingRevocations: async options => {
    retryCalls += 1;
    assert.equal(options === undefined || typeof options === "object", true);
    return retryResult;
  },
  refreshCurrentUser: async () => activeAuth,
  clearAuth: async () => { activeAuth = null; },
  pendingRevocationCount: async () => pendingCount,
  readAuth: async () => activeAuth,
  readPendingRevocations: async () => pendingCount > 0
    ? [{ baseUrl: "https://old.example", reason: "LOGOUT" }]
    : [],
  readSettings: async () => settings,
  restrictStorageToExtensionPages: async () => {},
  writeSettings: async value => {
    settings = { ...settings, ...value };
    return settings;
  }
});
vm.runInContext(source, context);
await settle();

const worker = context.HeyiRevocationWorkerTest;
assert.equal(typeof startupListener, "function");
assert.equal(typeof onlineListener, "function");
assert.ok(retryCalls >= 1, "worker initialization retries queued revocations");

activeAuth = {
  baseUrl: "https://old.example",
  accessToken: "old-active-token-must-not-leak",
  expiresAt: new Date(Date.now() + 60_000).toISOString()
};
settings = { baseUrl: "https://old.example", deviceName: "Old device" };
const switched = await worker.savePlatform("https://new.example", "New device");
assert.equal(activeAuth, null);
assert.equal(settings.baseUrl, "https://new.example");
assert.equal(switched.authenticated, false);
assert.equal(switched.revocationPending, true);
assert.equal(switched.pendingRevocationCount, 1);
assert.equal(JSON.stringify(switched).includes("old-active-token"), false);
assert.equal(logoutOptions.at(-1).deferOnFailure, true);
assert.equal(logoutOptions.at(-1).reason, "SWITCH");

activeAuth = {
  baseUrl: "https://old.example",
  accessToken: "another-old-token-must-not-leak",
  expiresAt: new Date(Date.now() + 60_000).toISOString()
};
settings = { baseUrl: "https://old.example", deviceName: "Old device" };
const paired = await worker.pair({
  baseUrl: "https://new.example",
  code: "ABCDEF12",
  deviceName: "New device"
});
assert.equal(paired.authenticated, true);
assert.equal(paired.revocationPending, true);
assert.equal(activeAuth.baseUrl, "https://new.example");
assert.equal(JSON.stringify(paired).includes("token-must-not-leak"), false);
assert.equal(logoutOptions.at(-1).reason, "SWITCH");

activeAuth = {
  baseUrl: "https://old.example",
  accessToken: "logout-old-token-must-not-leak",
  expiresAt: new Date(Date.now() + 60_000).toISOString()
};
settings = { baseUrl: "https://old.example", deviceName: "Old device" };
const unbound = await worker.logoutAndRevokePermission();
assert.equal(unbound.authenticated, false);
assert.equal(unbound.localUnbound, true);
assert.equal(unbound.revocationDeferred, true);
assert.equal(unbound.revocationPending, true);
assert.equal(JSON.stringify(unbound).includes("logout-old-token"), false);
assert.equal(logoutOptions.at(-1).reason, "LOGOUT");
assert.equal(removedOrigins.length, 0, "deferred logout retains host access needed for retry");

const repeatedUnbound = await worker.logoutAndRevokePermission();
assert.equal(repeatedUnbound.authenticated, false);
assert.equal(repeatedUnbound.revocationPending, true);
assert.equal(repeatedUnbound.revocationDeferred, false);
assert.equal(removedOrigins.length, 0, "a repeated offline logout retains host access for the queued revocation");

pendingCount = 0;
retryResult = {
  attempted: 1,
  revoked: 1,
  pendingRevocationCount: 0,
  resolved: [{ baseUrl: "https://old.example", reason: "LOGOUT" }]
};
await worker.retryRevocationsInBackground({ force: true });
assert.deepEqual(removedOrigins, ["https://old.example/*"]);

const beforeStartup = retryCalls;
startupListener();
await settle();
assert.ok(retryCalls > beforeStartup, "browser startup triggers another retry pass");
const beforeOnline = retryCalls;
onlineListener();
await settle();
assert.ok(retryCalls > beforeOnline, "online event triggers a forced retry pass");

console.log("Revocation service-worker recovery tests passed");

async function settle() {
  await new Promise(resolve => setTimeout(resolve, 0));
  await new Promise(resolve => setTimeout(resolve, 0));
}
