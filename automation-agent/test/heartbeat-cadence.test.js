const test = require("node:test");
const assert = require("node:assert/strict");
const {
  ACTIVE_HEARTBEAT_MS,
  IDLE_HEARTBEAT_MS,
  hasActiveInvocation,
  heartbeatDelay
} = require("../src/core/heartbeat-cadence");

test("idle helper keeps the low-frequency heartbeat", () => {
  assert.equal(hasActiveInvocation([]), false);
  assert.equal(heartbeatDelay([]), IDLE_HEARTBEAT_MS);
  assert.equal(IDLE_HEARTBEAT_MS, 8000);
});

test("a platform invocation switches heartbeat to three seconds", () => {
  const tasks = [{ id: "task-1", status: "RUNNING" }];
  assert.equal(hasActiveInvocation(tasks), true);
  assert.equal(heartbeatDelay(tasks), ACTIVE_HEARTBEAT_MS);
  assert.equal(ACTIVE_HEARTBEAT_MS, 3000);
});

test("completed tasks return to idle cadence", () => {
  assert.equal(heartbeatDelay([{ status: "SUCCEEDED" }]), IDLE_HEARTBEAT_MS);
  assert.equal(heartbeatDelay([{ status: "FAILED" }]), IDLE_HEARTBEAT_MS);
});
