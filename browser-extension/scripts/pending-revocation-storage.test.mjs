import assert from "node:assert/strict";

const values = new Map();
globalThis.chrome = {
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
const {
  enqueuePendingRevocation,
  markPendingRevocationFailure,
  pendingRevocationCount,
  readPendingRevocations,
  removePendingRevocation
} = await import("../src/background/storage.js");

const auth = {
  baseUrl: "http://127.0.0.1:4173",
  accessToken: "storage-pending-token-0001",
  expiresAt: Date.now() + 60 * 60 * 1000,
  user: { displayName: "must not persist" },
  permissions: ["must:not:persist"]
};

assert.deepEqual(await enqueuePendingRevocation(auth, "NETWORK_ERROR", "SWITCH"), {
  queued: true,
  count: 1
});
assert.deepEqual(await enqueuePendingRevocation(auth, "HTTP_503", "LOGOUT"), {
  queued: true,
  count: 1
});
let pending = await readPendingRevocations();
assert.equal(pending.length, 1, "the same credential must be deduplicated");
assert.equal(pending[0].reason, "LOGOUT", "explicit logout has the strongest cleanup intent");
assert.equal(pending[0].lastErrorCode, "HTTP_503");
assert.equal(pending[0].attemptCount, 2);
assert.deepEqual(Object.keys(pending[0]).sort(), [
  "accessToken", "attemptCount", "baseUrl", "expiresAt", "id",
  "lastAttemptAt", "lastErrorCode", "queuedAt", "reason"
]);
assert.equal("user" in pending[0], false);
assert.equal("permissions" in pending[0], false);

await markPendingRevocationFailure(pending[0].id, "NETWORK_ERROR");
pending = await readPendingRevocations();
assert.equal(pending[0].attemptCount, 3);
assert.equal(pending[0].lastErrorCode, "NETWORK_ERROR");
await removePendingRevocation(pending[0].id);
assert.equal(await pendingRevocationCount(), 0);
assert.equal(values.has(STORAGE_KEYS.PENDING_REVOCATIONS), false);

values.set(STORAGE_KEYS.PENDING_REVOCATIONS, [{
  id: "expired-token-id",
  baseUrl: "http://127.0.0.1:4173",
  accessToken: "expired-pending-token-0001",
  expiresAt: Date.now() - 1_000
}, {
  id: "malformed-token-id",
  baseUrl: "javascript:alert(1)",
  accessToken: "malformed-pending-token-0001",
  expiresAt: Date.now() + 60_000
}]);
assert.deepEqual(await readPendingRevocations(), [], "expired or unsafe tombstones are pruned");
assert.equal(values.has(STORAGE_KEYS.PENDING_REVOCATIONS), false);

console.log("Pending revocation storage tests passed");
