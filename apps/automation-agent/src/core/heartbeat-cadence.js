const ACTIVE_HEARTBEAT_MS = 3000;
const IDLE_HEARTBEAT_MS = 8000;

function hasActiveInvocation(tasks = []) {
  return Array.isArray(tasks) && tasks.some(task =>
    ["RUNNING", "PAUSED", "CANCELLING"].includes(String(task?.status || "").toUpperCase())
  );
}

function heartbeatDelay(tasks = []) {
  return hasActiveInvocation(tasks) ? ACTIVE_HEARTBEAT_MS : IDLE_HEARTBEAT_MS;
}

module.exports = {
  ACTIVE_HEARTBEAT_MS,
  IDLE_HEARTBEAT_MS,
  hasActiveInvocation,
  heartbeatDelay
};
