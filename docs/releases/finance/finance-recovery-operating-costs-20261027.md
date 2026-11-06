# Finance recovery and operating-cost reporting — 2026-10-27

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## Production outcome

- Resumed four unfinished settlement derivations with two workers and five-record
  transactions. All 17 active shops completed; no active API settlement lacked
  its derived pre-discount goods amount at final verification.
- September, October 1–27 and September 28–October 27 dashboard totals were
  checked through a SELECT-only account. Gross amounts matched shop rankings
  and date trends. Figures can still change when new platform records arrive.
- The original 247,377-record net-ledger baseline remains intact. One Shopee
  source amount changed after the original worker had exited: PHP 250.92 before
  adjustment, PHP -257.92 adjustment, PHP -7 after adjustment. The stored
  official response, source update time and hash were reviewed separately.
  This was not silently treated as unchanged data. Evidence is retained in
  the private recovery release directory.
- Completed progress was published and shared financial snapshots invalidated.
  The recovery worker is not left running.

## Recovery and query behavior

- Successful batches checkpoint immediately. Rolled-back transient failures
  retry in fresh transactions, with bounded retries and backoff. A permanent
  failure is reported after other shop workers finish.
- Completion requires both a zero-missing-amount check and the source-net
  audit. A failed run removes its completion marker; failed progress lasts
  seven days instead of disappearing after ten minutes.
- Partial known goods amounts remain visible with explicit coverage counts;
  incomplete figures are not presented as complete profit.
- Repeated settlement sync compares persisted fee fields and raw JSON. Only
  changed/new fees are upserted, and only obsolete generated fees are removed.
  Identical fees retain their rows and timestamps. Imported fees remain scoped
  separately.
- EXPLAIN confirmed the fee-order and statement indexes were selected. The
  principal write overhead was repeated delete/insert work, not absent indexes.

## Storage

The user authorized removal and disabling of MySQL binary logs. Replication was
checked first; full backup integrity was verified. Binary/general/slow logs are
off, and the 8 GiB InnoDB buffer pool setting survives restart. Full backups,
database files, and required redo/undo files remain. The production Compose
configuration and this repository both include `--skip-log-bin`.

## Cost presentation and scope

- The overview now has six metrics. Operating cost groups product cost and
  logistics/label cost, in the same display currency.
- Sample product costs and refund-related product costs are subsets for
  reconciliation, not additional deductions. The subsets can overlap.
  A refund alone is not evidence of returned stock or a recovered product cost.
- Sample classification requires the explicit TikTok sample flags; a zero order
  amount does not imply a sample. These classifications are queried only when
  the operating-cost dialog opens.
- Sample/refund drilldowns and their exports retain company, shop, group and
  first-settlement date scope. Product costs stay in RMB.
- The expenses page has a compact toolbar and only label-cost and tagged-order
  tabs. The legacy expense tab and its unnecessary fetch are removed.

## Order-cost prices and Excel

The old workbook's primary order amount came from the original order record.
It now uses explicit pre-discount goods revenue from the first settlement day,
with the settlement currency and provider net settlement in separate columns.
Original buyer/order amounts remain clearly labelled reference columns.

The page and export call the same bounded, indexed enrichment (100 orders per
batch). Missing goods values stay missing; buyer payment or net settlement is
never substituted. Later settlement adjustments are available in settlement
details and do not duplicate initial order product cost. SKU costs are not
changed by this revenue presentation.

## Validation and deployment

- 141 backend checks passed against a disposable MySQL schema.
- Six frontend suites cover profit, charges, live cost management,
  reconciliation, query speed and filter retention.
- Four standalone recovery retry checks passed.
- A SELECT-only production verification checked sample/refund subsets, a
  50-row order-cost page (all 50 had known goods amounts), and one actual
  single-order workbook whose primary amount matched the page.
- Backend class overlays and five static assets were deployed with live hash
  guards, matching gzip assets, health checks and configuration preservation.
  No schema migration was added. Anonymous cost-summary/export access remains
  denied.
- Browser TaskSpace 28 was no longer available, so the final layout has
  automated DOM/behavior checks but no final browser visual sign-off.

Release directories:

- `/opt/heyi/releases/finance-settlement-display-20261027`
- `/opt/heyi/releases/finance-fee-incremental-20261027`
- `/opt/heyi/releases/finance-operating-costs-20261027`
- `/opt/heyi/releases/finance-settlement-recovery-20261027` (maintenance/evidence)
