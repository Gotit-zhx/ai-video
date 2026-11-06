# Finance query reuse and detail pagination — 2026-11-03

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## Findings and changes

Background cost and invoice batches were advancing the data revision during
monthly reads. A newly completed raw snapshot could immediately fail the revision
check, causing currency and shop changes to repeat large ledger/cost queries.

- Reuse raw daily facts for their existing maximum of 15 seconds after calculation,
  including across soft data revisions. Preserve the original revision and
  generation time in every projection. Responses explicitly indicate background
  updates when a newer data revision exists.
- Configuration changes, shop removal and company boundaries still fence cache
  addresses immediately. Raw facts expire after the same 15-second reuse window;
  displayed stale responses retain their original 60-second hard age limit.
  Redis retention, entry limits, payload limits and runtime memory are unchanged.
- Detail pagination now selects ordered record IDs and dates through the existing
  covering index, then fetches full provider payloads for that page. All date,
  company, group, status and exact-number predicates remain inside the page
  selection. The count query and stable date/ID ordering are unchanged.
- No new index, schema migration, financial calculation change, persistent log,
  platform request change or checkpoint reset is included. Qianyi refunds,
  disabled return-cost reversal, shared aliases and the month completion marker
  retain the behavior documented in the preceding releases.

## Validation and measurements

The three unchanged source class families first compiled byte-for-byte equal to
the live image. Java 21 and `-parameters` match the existing compilation metadata.

- 28 cache/unit checks passed, including money precision, tenant separation,
  concurrency, hard invalidation, continuous soft changes, original snapshot age,
  expiry and removal of internal metadata from responses.
- Five disposable-MySQL checks passed: page ordering/counts with large payloads,
  exact-number lookup and visibility; company projections across shop/group/
  currency/chart selections; unassigned expenses; ledger dimensions; settlement
  date and company isolation. The temporary schema and account were removed.
- Read-only production comparisons used one consistent snapshot per run and
  isolated temporary Redis keys. Production financial records were not changed.
  Explicit invalidation of these isolated keys simulated background worker batches.

| Operation | Previous implementation | Candidate |
| --- | ---: | ---: |
| Currency switch after a worker batch | 47,117 ms; 2 large scans | 1,527 ms; 0 large scans |
| Shop switch after a worker batch | 842 ms; 2 large scans | 738 ms; 0 large scans |
| Populated October detail page, 50 rows | 123,305 ms | 796 ms |

The page candidate ran **before** the previous query; both returned exactly the
same ordered full records. Projected shop metrics matched a direct SQL computation.
An additional empty-group sample also returned equal results (166,525 vs 1,336 ms);
the populated-page comparison is the primary evidence.

A new whole-company October range still required a cold calculation: 44,280 ms in
the baseline run and 37,067 ms in the candidate run. Workload/cache conditions vary;
this release does not claim that first-time monthly calculations became instant.
The main improvement is avoiding repeat scans and unnecessary full-payload reads.
Measurements are backend service/SQL timings, not authenticated browser load times.

## Release and operation

Backend: `ai-commerce-platform:finance-query-reuse-20261103`.
Frontend remains `ai-commerce-frontend:finance-shop-month-check-20261103`.

Release directory: `/opt/heyi/releases/finance-query-reuse-20261103`.
Helper: `scripts/release/release-finance-query-reuse.py`.

Only `FinanceReadCache`, `FinanceService` and `FinanceRepository` class families
are overlaid. Every unrelated JAR entry is verified byte-for-byte. Use the existing
drain/restore procedure and image compaction guard. Preserve runtime configuration,
all referenced Compose files, previous release-helper dependencies and receipts.

Read-only verification:

```sh
python3 -B /opt/heyi/releases/finance-query-reuse-20261103/release-finance-query-reuse.py \
  /opt/heyi/releases/finance-query-reuse-20261103 verify
```

Remove source/build/test staging, copied dependencies and JARs, temporary probe
classes, private environment/drain snapshots and uploaded archives after verification.
Do not rebuild a cleaned release folder without preparing it again.

Activation and the post-cleanup verification both passed: exact JAR hash, unchanged
frontend assets, service health, API access protection and disabled container
logging. Runtime configuration and the frontend container were preserved. The
drain helper restored 299 schedules with zero checkpoint changes and released
the Qianyi and idle invoice reservations.

At 15:15 Asia/Shanghai, order and settlement synchronization had resumed and all
74 enabled Qianyi connections had succeeded within the preceding 15 minutes,
without failures. There were no database lock waits or SQL-classified sync
errors. Existing platform pending-query errors and refund rate-limit cooldowns
remain under their normal retry policy; this release does not alter that policy.
All four finance service containers were running, with zero restarts/OOM and
logging disabled.

Removed 22 owned staging/private-file entries, totaling 247,029,760 allocated
bytes. Kept only the release helper, verification receipts and referenced Compose
files. The obsolete, container-unreferenced `storage-bounded-20261102` backend
image tag was removed through Docker. The active backend and two verified
rollbacks (`finance-shop-month-check-20261103` and `finance-sync-priority-20261103`)
remain. Frontend rollback retention is unchanged. No volume was pruned.
