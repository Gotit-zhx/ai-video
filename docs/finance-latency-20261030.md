# Finance latency investigation and refresh repair — 2026-10-30

## Observed cause and limits

- The server had about 16 GiB available RAM, 27 GiB free disk space, no swap
  activity, no row-lock wait and low overall CPU utilization. The configured
  MySQL buffer pool was already 8 GiB. SQL logging remained disabled.
- The October all-shop dashboard read roughly 600,000 ledger rows, with
  approximately 1.7 million estimated ledger rows overall. A direct uncached
  dashboard read took 5,710 ms; a September single-shop read took 735 ms.
  The component measurements were about 2.1 seconds for ledger buckets and
  3.1 seconds for first-settled orders and their costs.
- The cost worker hard-invalidated the entire company's cached reports after
  every committed batch, including batches that only completed processing
  metadata. The production hard revision changed five times in a 30-second
  sample, comparable with the time needed to rebuild a dashboard.
- Local-machine HTTPS tests also showed roughly 2–3 seconds to first response
  on a new connection and around 0.6–0.8 seconds on reused connections.
  These timings include the client's network path, and are not server SQL
  timings or an authenticated browser page measurement.

## Changes

Background cost batches now advance a separate data revision. A report no older
than the existing 60-second limit remains available with `cache.refreshing=true`
while one coalesced background job refreshes its exact query scope. The existing
15-second freshness threshold and retention limit are unchanged. Reads past the
60-second limit still require a successful query; failures do not silently turn
old totals into current totals.

The data revision is captured before reading the database. A batch committed
during that read causes another refresh and marks even an initial response as
refreshing. Configuration changes, authorization changes and shop removal still
hard-invalidate cached reports. Cache write failures retain the existing
database fallback and hard-invalidation protection.

V136 replaces the existing `idx_finance_settled_order` index with a covering
version preserving its original seven-column prefix. It adds the scalar fields
used in order aggregates and first-settlement checks, without retaining a second
redundant index. First-settlement checks explicitly use it. Historical dashboard
reads skip order/refund buckets by creation date that are replaced by
settlement-date buckets later in the same read. Live-screen behavior is retained.

The index alone did not eliminate the cold-query latency. Large uncached
all-shop reads still take approximately 5–7 seconds and require further query
or aggregate-read-model work if lower cold latency is required.

## Verification and deployment

- 75 distinct focused unit/regression tests passed, including four new cache
  tests for burst updates, updates committed during reads, hard-invalidated
  configuration changes and initial-response freshness.
- 58 MySQL integration cases passed in a disposable schema, including the new
  index and filtered-bucket equivalence across groups, currencies and visibility.
- Six production comparisons used the same read-only database snapshot for
  both implementations. Whole-month, partial-period, single-shop, group and
  platform dashboard results were identical after excluding the response's
  generated timestamp.
- Against the deployed classes, a separate temporary Redis namespace and a
  SELECT-only database account simulated 30 committed batches. Cached reads
  had a 6 ms median and 39 ms maximum. Three loads occurred in total, including
  the initial load and the conservative follow-up for an in-flight revision.
  The cache converged to fresh results; cached financial totals were preserved.
  The initial uncached load in that run took 6,877 ms.
- V136 ran online in 84 seconds while the web service stayed available.
  An initial backend activation failed its readability/health check and
  automatically rolled back. The JAR mode was corrected to 0644 and an explicit
  image check under the configured runtime user was added before activation.
- The final backend image is
  `ai-commerce-platform:finance-latency-20261030`. The frontend container,
  assets, runtime configuration and unrelated services remained unchanged.
  Financial sync schedules were restored without resetting leases or
  checkpoints. Anonymous financial access remained rejected.

Release tooling: `scripts/release-finance-latency.py` and
`scripts/FinanceQueryIndexMigration.java`. Temporary query accounts, isolated
cache keys, test schemas and private environment files were removed.

Final checks confirmed schema 136, all 15 index columns, no row-lock waits,
ongoing synchronization and healthy backend service. SQL logging remains off.
Cleanup removed 183.78 MB of this release's staging files, copied JARs, private
snapshots and temporary diagnostics, including the download-repair staging
copies. Running container mounts, Compose configuration, installer runtime and
the rollback image were preserved. A cleanup receipt is retained with the
activation receipt.
