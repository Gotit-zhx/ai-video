import assert from "node:assert/strict";

const values = new Map();
globalThis.chrome = {
  runtime: {
    id: "logout-revocation-test",
    getManifest: () => ({ version: "0.1.0" })
  },
  storage: {
    local: {
      async get(key) {
        return { [key]: values.get(key) };
      },
      async set(entries) {
        for (const [key, value] of Object.entries(entries)) values.set(key, value);
      },
      async remove(key) {
        values.delete(key);
      },
      async setAccessLevel() {}
    }
  }
};

const { STORAGE_KEYS } = await import("../src/shared/constants.js");
const { writeAuth, readAuth, readPendingRevocations } = await import("../src/background/storage.js");
const { logoutCurrentDevice, retryPendingRevocations } = await import("../src/background/api.js");

async function storeActiveToken(suffix) {
  await writeAuth({
    baseUrl: "http://127.0.0.1:4173",
    accessToken: `active-device-token-${suffix}`,
    expiresAt: Date.now() + 60 * 60 * 1000,
    user: { id: "user-1" },
    permissions: ["product:collect"]
  });
}

await storeActiveToken("network");
globalThis.fetch = async () => { throw new TypeError("offline"); };
await assert.rejects(logoutCurrentDevice, error => {
  assert.equal(error.code, "LOGOUT_RETRY_REQUIRED");
  assert.match(error.message, /凭证仍保留/);
  assert.match(error.message, /联网后再次点击/);
  return true;
});
assert.ok(await readAuth(), "network failure must retain the local credential for retry");

await storeActiveToken("server");
globalThis.fetch = async () => new Response(JSON.stringify({ message: "temporary outage" }), {
  status: 503,
  headers: { "Content-Type": "application/json" }
});
await assert.rejects(logoutCurrentDevice, error => error.code === "LOGOUT_RETRY_REQUIRED");
assert.ok(await readAuth(), "5xx failure must retain the local credential for retry");

await storeActiveToken("unauthorized");
globalThis.fetch = async () => new Response(JSON.stringify({ message: "already revoked" }), {
  status: 401,
  headers: { "Content-Type": "application/json" }
});
await logoutCurrentDevice();
assert.equal(await readAuth(), null, "401 means the server credential is already unusable");
assert.equal(values.has(STORAGE_KEYS.AUTH), false);

await storeActiveToken("deferred");
const deferredToken = (await readAuth()).accessToken;
globalThis.fetch = async () => { throw new TypeError("offline"); };
const deferred = await logoutCurrentDevice({ deferOnFailure: true, reason: "SWITCH" });
assert.equal(deferred.revoked, false);
assert.equal(deferred.deferred, true);
assert.equal(deferred.pendingRevocationCount, 1);
assert.equal(await readAuth(), null, "a securely queued credential releases the active binding");
assert.equal(JSON.stringify(deferred).includes(deferredToken), false, "public result must not expose the token");
const queued = await readPendingRevocations();
assert.equal(queued.length, 1);
assert.equal(queued[0].accessToken, deferredToken);
assert.equal(queued[0].reason, "SWITCH");

let retriedAuthorization = "";
globalThis.fetch = async (_url, options) => {
  retriedAuthorization = options.headers.get("Authorization");
  return new Response(null, { status: 204 });
};
const retry = await retryPendingRevocations({ force: true });
assert.equal(retry.attempted, 1);
assert.equal(retry.revoked, 1);
assert.equal(retry.pendingRevocationCount, 0);
assert.equal(retry.resolved[0].reason, "SWITCH");
assert.equal(JSON.stringify(retry).includes(deferredToken), false, "retry summary must not expose the token");
assert.equal(retriedAuthorization, `Bearer ${deferredToken}`);
assert.deepEqual(await readPendingRevocations(), []);

console.log("Logout revocation retry tests passed");
