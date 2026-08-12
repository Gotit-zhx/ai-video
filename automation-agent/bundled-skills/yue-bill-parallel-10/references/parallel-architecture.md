# Parallel architecture

## Components

| Component | Responsibility |
|---|---|
| `run.ps1` | Plan, initialize runtimes, invoke the coordinator, finalize workbook |
| `scripts/parallel_runner.ps1` | Select tasks, enforce concurrency and store-key locks, merge worker results |
| `scripts/run_worker.ps1` | Set isolated environment and invoke one serial task |
| `serial-run.ps1` | Execute exactly one worker task with the bundled platform executor |
| `executors/tk-bill/scripts/feikua_store.py` | Shared Feikua lifecycle and CDP kernel |

## State boundaries

- `_run/tasks.json` is authoritative and coordinator-only.
- `_run/parallel-state.json` records live worker PIDs for interruption recovery.
- `_run/parallel-workers/<taskId>/tasks.json` is worker-local.
- Each worker directory contains its own logs, Node runtime junction, executor
  runtime state, and driver cache.
- `_run/parallel-sessions/<storeKeyHash>.json` persists the CDP mapping across
  serialized duplicate rows for the same exact Feikua profile.
- Store output folders remain those planned in the master task.

## Concurrency rules

1. Run three worker processes by default and at most five.
2. Count active worker stores and preserved recoverable/manual stores together.
   Enforce `min(MaxConcurrency, MaxLiveStoreSessions)` as the live Feikua
   session limit, with an absolute maximum of five. Thus concurrency three
   permits only three total active or preserved windows. On coordinator
   startup, evict older preserved-recoverable sessions first until the limit
   is met; never evict a live worker and prefer retaining manual-takeover
   sessions.
3. Prefer resuming the task that owns an existing preserved store before
   opening a new Chrome session. Pause new-store replenishment when free
   memory is below `MinimumLaunchFreeMemoryGB`; keep current exports alive and
   resume replenishment automatically after memory recovers. If a recoverable
   preserved store is in backoff and leaves a worker slot empty while consuming
   the effective live cap, close that non-running session and use the released
   capacity for another ready task. Keep the evicted task queued; never evict a
   running or manual-takeover session.
4. Use the exact `executorName` alone as the store lock key so the same Feikua
   profile cannot overlap even if two workbook rows route it through different
   platform executors.
5. When duplicate rows share the exact delivery target, reuse a confirmed
   `completed`, `no_data`, or `manual_required` result instead of submitting a
   second export.
6. Serialize only Feikua lifecycle API calls through
   `_run/feikua-lifecycle.lock`.
7. Do not call `stop_other_running_stores` in parallel mode.
8. Save the exact CDP port immediately after a successful start.
9. During HTTP 500 start recovery, consider only CDP listeners created after
   that locked start request; never select among all active browser ports.
10. Keep page interaction inside the worker that owns the saved CDP port.
11. Start each isolated task as soon as its own CDP is ready. Only when
   `-WaitForInitialWave` is explicitly enabled, preconnect the initial worker
   wave behind `_run/parallel-startup-barrier`; do not apply that optional
   barrier to later replacement workers.
   A ready CDP must also reach the authenticated platform Seller Center home.
   Otherwise close that exact shop immediately and record
   `未进首页，请检查登录` in its Excel status cell.
12. Keep a submitted export in its current slot while reconnecting to export
    history; never resubmit it after a transient CDP failure.
13. Before a terminal worker exits, repeatedly stop its exact Feikua profile
    until it is confirmed idle. Merge and replace it only after process exit.
14. Use one short shared ChromeDriver cache with a separate cross-process lock
    and atomic driver publication.

## Recovery rules

- On coordinator restart, reattach PIDs from `parallel-state.json`.
- Merge an exited worker's local task before scheduling any replacement.
- Convert a worker that exits without a terminal state to
  `PARALLEL_WORKER_EXIT_<code>`.
- Re-run only through the master task plan; never launch a worker JSON directly
  as a replacement without coordinator ownership.

## Validation gates

- Validate all PowerShell scripts by parsing them before live use.
- Compile all Python executor scripts.
- Run a multi-task mock scheduling test at five-worker concurrency and confirm
  maximum observed concurrency is five, total active plus preserved sessions
  never exceeds five, duplicate store keys never overlap, and master JSON
  stays valid.
- Run a two-store live `connect-only` pilot before the first live multi-store job
  on a machine. Confirm unique CDP ports, correct shop ownership, and exact-store
  cleanup.
- Do not live-test while the serial `yue-bill` workflow has a pending export.
