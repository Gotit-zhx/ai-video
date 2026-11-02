# Production storage cleanup — 2026-11-02

## Verified causes

- The application image accumulated 44 layers because each release copied a
  complete JAR over the previous release. Deleting upload archives does not remove
  those historical layers. Docker's image listing reported 7.45 GB for the old
  image; image inspection reported 3,297,625,464 compressed bytes.
- Automatic order-cost recalculation appended complete before/after JSON to
  `finance_workspace_audit`. This was not required to calculate or display the
  current cost. There were 248,627 automatic snapshots by cleanup time.
- Persistent system journal and syslog files occupied about 538 MB. Old release
  backups, compilation directories, and disposable QA projects also remained.
- Original financial records and their indexes remain the largest required
  storage consumer: approximately 13.7 GB data plus 19.1 GB indexes in the sampled
  MySQL statistics. They were not deleted or rebuilt.
- The previously repaired label-generation cleanup still operates. One published
  generation and, during replacement, one retiring generation were observed.
  Metadata must be read with `information_schema_stats_expiry=0`: cached table
  estimates still showed the historical 70-million-row table after replacement.

## Changes and cleanup

- Added `scripts/compact-release-image.py`. It creates unstarted, volume-free
  containers from release images, preserves image configuration, and verifies
  file contents and metadata before returning a compact image. Running
  containers and business volumes are never exported.
- Verified 12,701 filesystem paths for both the current and previous application
  images. The compact baselines each have one layer and approximately 345 MB
  compressed size. The deployed application adds the reviewed JAR overlay and has
  two layers, 413,559,568 compressed bytes.
- Added a base-image guard: more than eight layers or 600 MiB compressed size
  requires verified compaction before another overlay build. Installed the helper
  at `/usr/local/lib/heyi/compact-release-image.py`, updated the recent release
  templates, and documented mandatory use in `AGENTS.md`.
- Removed automatic `ORDER_STANDARD_COST`/`SYSTEM_COST` audit writes from
  `FinanceOrderCosts`. Cost calculation, current results, versions, queue
  completion, and manual/import audit writes are unchanged.
- Deleted 248,627 obsolete automatic snapshots in transactions of at most 2,000
  rows. Compared hashes of all 79 manual/import records before and after.
  Rebuilt only the small remaining audit table online; it now uses 65,536 data
  bytes and 32,768 index bytes. Subsequent verification found zero newly generated
  automatic cost snapshots.
- Removed 76 obsolete application/frontend image IDs (148 tags), then removed
  dangling image records through Docker. No containerd files or business volumes
  were removed directly.
- Removed 256 unreferenced build/test artifacts: 114,536,448 allocated disk bytes
  and 229,015,552 tmpfs bytes. Mounts, Compose references, active files, and process
  references were checked first.
- Removed 15 obsolete September/October release backup files, totaling
  599,490,560 allocated bytes. Active configuration and user data were preserved.
- Removed 538,468,352 bytes of old persistent system logs. Journald now uses
  volatile storage capped at 32 MiB and does not forward to syslog. Rsyslog is
  stopped and disabled. Existing financial app/frontend/MySQL/Redis Docker log
  drivers remain `none`; other service containers were not restarted.
- Removed another 76,820,480 disk bytes of release staging, 107,335,680 tmpfs bytes
  of QA staging, and 111,304,704 bytes of rebuildable APT caches under package
  manager locks.
- Mirrored the already-active backup timer/service protections in repository
  configuration: no missed-run catch-up, no persistent output, bounded runtime.
  No backup was triggered.

## Release and verification

Active application: `ai-commerce-platform:storage-bounded-20261102`.

Retained application rollback images:

- `ai-commerce-platform:storage-compact-baseline-20261102`
  (byte-identical to the preceding finance-cache release).
- `ai-commerce-platform:storage-compact-previous-20261102`.

Frontend remains `ai-commerce-frontend:finance-cache-reuse-20261102`, with its
two recent rollback versions retained. Other service images were preserved.

Release helper: `scripts/release-storage-compact.py`.
Production receipts and live Compose dependency:
`/opt/heyi/releases/storage-compact-20261102`.

- The unchanged source compiled to bytecode matching the original live
  `FinanceOrderCosts` class before editing.
- Eleven cost unit tests and two disposable-MySQL regression groups passed.
  The storage regression exercises a standard-cost calculation, replacement by actual
  shipment costs, label costs, repeated recalculation, queue completion, and
  preservation of explicit import audit records. The additional existing
  regression covers company isolation, settlement deduplication, manual mapping
  changes, cancelled-order correction, export, and unchanged platform amounts.
- Only the `FinanceOrderCosts` class family changed inside the application JAR;
  every unrelated archive entry was compared byte-for-byte.
- The normal queue-drain procedure preserved schedules and checkpoints. Runtime
  configuration equality, application health, exact JAR hash, frontend HTTP 200,
  and anonymous finance API HTTP 401 were verified after activation.
- All temporary QA schemas/accounts, staging JARs, class copies, and upload
  artifacts were removed. Keep release helpers, receipts, and referenced Compose
  files. Do not rerun the cleaned build directory without preparing it again.

Root filesystem at the start: 71,472,848,896 bytes used and 29,300,756,480
available (71%).
After cleanup: 63,819,251,712 used and 36,954,353,664 available (64%).
Net free-space increase: 7,653,597,184 bytes, approximately 7.13 GiB.
Database writes continue, so later snapshots can differ. Tmpfs reclamation is
reported separately and is not counted as disk space.

The final check at 18:39 Asia/Shanghai found 36,860,960,768 bytes available
(34.33 GiB), no financial SQL errors or database lock waits, 131 recently
successful synchronization channels, and no automatic cost audit rows.
Qianyi had 70 successful and two active connections, all without failures.
The label table contained one generation with 173,608 rows. All four financial
service containers were running with logging disabled and zero restarts.

No financial calculation basis, Qianyi return behavior, source orders, imported
invoices, aliases, platform pacing, or business assets were changed.
