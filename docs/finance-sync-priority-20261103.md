# Finance sync priority and throughput — 2026-11-03

## Behavior

- “立即同步” creates a durable priority request for the shop's existing channels.
  Each channel keeps its priority through pagination, subsequent date windows,
  and historical resume until its complete scan succeeds. Repeated clicks join
  the outstanding request without restarting completed channels or moving the
  shop in the queue.
- The dispatcher checks manual priority before ordinary due time. It retains
  one active channel per shop, four workers, two concurrent claims per platform,
  existing page sizes, the two-second same-channel page interval, and durable
  TikTok refund pacing/backoff. Each worker can process up to four eligible steps
  before its one-second idle interval; every step gets a fresh claim.
- Candidate selection takes up to 32 eligible rows per platform, so a crowded
  platform cannot hide work for another platform with available capacity.
- The shop page distinguishes actual leased work, queueing, another active
  channel of the same shop, API pacing, retry cooldown, and scheduled refresh.
  It shows the active shop/channel/date window, manual-priority shop order,
  remaining priority channels, last progress time, and same-company/platform
  queue position. Filters cover incomplete initial history, running, priority,
  queueing, and retry waits. Queue positions are dynamic, not finish-time promises.
- The last-success heading now explicitly describes a single-channel success,
  not completion of the entire shop. UTC sync timestamps have explicit offsets.
  Existing company-shared aliases remain preferred.
- Shop rows show one concise current state by default. Per-channel dates,
  retry reasons, and progress are inside “查看 N 项进度”; refresh preserves
  the user's expanded/collapsed choice. Name and sync columns are bounded,
  and the four action buttons occupy two rows.
- The original company-wide authorized-shop and enabled-sync counts remain
  visible alongside current-group execution, incomplete-history, and priority
  counts. Progress filters do not alter the company-wide shop totals.
- Migration V142 adds only `priority_requested_at` and `last_progress_at` to
  the small sync-state table. It does not change financial records or indexes.

## Measured optimization

The previous status query repeated a pending-statement count for each settlement
channel. A company-scoped grouped count now uses the existing covering
`idx_finance_bill_status` index once.

A consistent read-only production transaction returned equal counts for all
440 channel rows. The count-query portion took 3,862.990 ms before and 3.091 ms
after. This is not a claim about total browser page load time.

Separate approximately 30-second live samples observed 58 completed page writes
before release (115.6/minute), and 93 after release (185.3/minute), approximately
60% more in this short sample. Workload and upstream latency vary, so this is not
a guaranteed multiplier. No SQL error or database lock wait was found afterward.
The two observed TikTok refund cooldowns were already established before the
release; their durable budget was preserved. Qianyi had 72 successful enabled
connections with no failures.

## Verification

- Baseline source compiled byte-for-byte to the exact live class families.
  Match the existing compilation metadata: `FinanceService` and
  `FinanceSyncClaims` use `-parameters`; `FinanceController` and `FinanceSync`
  use javac's default debug/parameter settings. All use Java 21.
- Six unit/proxy checks and thirteen disposable-MySQL checks passed, including
  manual priority/FIFO, repeated-click behavior, full historical resume, failure
  and cursor preservation, tenant boundaries, same-shop exclusivity, platform
  capacity, bounded consecutive work, pending counts, refund pacing, and pending
  collection replay integrity. The temporary schema/account/environment were
  removed.
- Four JSDOM scripts passed: sync priority, sync dates, shop deletion, and
  Qianyi refunds. They passed again for the compact layout, including preserved
  total/enabled counts, initially collapsed details, and expanded-state retention.
  The priority/filter layout was also checked in ego-browser. With six channels
  per shop and long names, the three compact fixture rows measured approximately
  89 px high; the sync block measured 230 px wide.
  The unrelated legacy group UI test still expects a creation timestamp in a
  business-date cell; it fails identically on unchanged HEAD.
- Every unrelated JAR entry was compared byte-for-byte. Frontend changes were
  based on the exact live assets. Plain and gzip assets, migration 142, runtime
  configuration equality, application health, and anonymous API protection
  were verified after activation. The production browser requires login; layout
  interaction was exercised against a local fixture, not a production login.
- The established drain procedure restored all original schedules and retained
  checkpoints. Qianyi and invoice-worker reservations were released afterward.
  Docker logging remains `none`; no persistent diagnostic log was added.
- Frontend compaction preserved all 2,416 filesystem paths and image metadata,
  reducing 265 layers to one. The compaction helper now preserves Nginx's
  `StopSignal` metadata while still rejecting other unsupported metadata.
  The backend was not restarted for this frontend-only compaction.
- The final compact layout updated only `index.html`, `finance-workspace.js`,
  and `finance-workspace.css`, with asset version `20261103-sync-priority-2`.
  Plain/gzip assets and preserved runtime configuration were verified. The
  backend container ID remained unchanged during this frontend-only release.

## Current production release

- Application: `ai-commerce-platform:finance-sync-priority-20261103`
- Frontend: `ai-commerce-frontend:finance-sync-priority-ui-20261103`
- Release directory: `/opt/heyi/releases/finance-sync-priority-20261103`
- Helper: `scripts/release-finance-sync-priority.py`
- Read-only verification:
  `python3 /opt/heyi/releases/finance-sync-priority-20261103/release-finance-sync-priority.py /opt/heyi/releases/finance-sync-priority-20261103 verify`

Retain the referenced Compose files and existing release-helper dependencies.
The source/QA/build staging, previous JAR, uploaded assets, and private drain
snapshots have been removed: 23 owned entries, 261,746,688 allocated bytes.
Six obsolete image tags were removed after checking all container references.
The application retains `storage-bounded-20261102` and
`storage-compact-baseline-20261102` as its two rollback versions. The frontend
retains `finance-sync-priority-20261103-compact` and `finance-cache-reuse-20261102`.
The final health/asset/schema verification passed after cleanup.

A cleaned release directory must not be used to rebuild without preparing it
again. Storage and backup safeguards remain documented in
`docs/storage-cleanup-20261102.md`.

No financial calculation basis, Qianyi return-cost behavior, source orders,
invoice imports, platform page sizes, or money amounts were changed.
