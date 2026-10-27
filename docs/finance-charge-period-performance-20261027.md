# Finance charge periods and bounded recalculation — 2026-10-27

## Accounting and configuration

- Label prices are current RMB prices per dispatched order, selected by the
  shop's group and country, with a group default as fallback. The configuration
  date no longer excludes older orders. Reporting recognizes the label charge
  once, at the order's first platform settlement within the selected period.
- Cancelled and unpaid orders do not regain label costs because a shipping
  timestamp remains in their source record.
- Charges workspace filters now retain group, shop, platform and settlement
  period. Lifetime source-import totals and settled-period marketing totals
  are separate. Duplicate imported sources do not multiply an order's fee.
- Missing group/country label configuration is reported explicitly, rather
  than implying a confirmed zero cost. The overview links to the relevant
  group's label configuration.
- The user-requested move placed all three existing country rules in the
  maternal group and merged an identical marketing source already present in
  that group. Both source and destination groups were recalculated. No price
  was converted and no original platform ledger was writable by maintenance.
- The retained marketing source has 25 rows; eight orders match existing
  platform records. Seventeen source orders are still unavailable locally.
  They remain unmatched rather than being assigned to another order.

## Cause of the long calculation

Previously a label edit requested a full historical SKU-cost rebuild for every
shop in the group. Each job loaded and recalculated product costs even when
only a flat label price had changed. The worker also processed only four
50-order batches before a five-second delay.

The new path uses persistent `LABELS` scans and lower-priority label jobs.
Existing product amounts and SKU lines are reused only when the stored source
hash still matches the order. Missing snapshots or changed order sources fall
back to a full product calculation. Live order updates and full product-cost
requests always take priority; a full request upgrades pending label jobs.
Rule removal also removes obsolete label-rule metadata from the snapshot.

Records and snapshots are loaded together in batches of at most 100. Rules
and shop context are reused within the batch. Queue inserts, changed snapshots,
audits and job deletion use bounded multi-row SQL, without depending on JDBC
batch-rewrite configuration. Each batch and its queue removal commit together.
Persisted scan cursors allow interrupted work to resume.

The worker yields between transactions, starts no new batch after a two-second
cycle budget, and waits one second between cycles. The existing transaction
timeout still bounds an individual batch. No unbounded thread pool was added.
Completed jobs invalidate processing status even when their amounts did not
change.

## Display and export consistency

- Product and label processing states are separate. Updating only labels does
  not blank already confirmed product costs or add them to the missing-SKU
  filter. Final operating cost and profit remain incomplete until label work
  finishes.
- The cost export retains confirmed product/SKU costs during label updates
  and marks the label amount as updating rather than exporting an old price.
- A missing-cost profit card now shows the number of affected orders. Its
  dialog opens the pending-order filter with the same date and shop scope.
- Investigation of the reported Malaysia shop found one completed September
  settlement whose two-pack SKU was recognized, but the cost catalog contained
  that base model only in the Philippine and Thai warehouses. The Malaysia
  price is genuinely absent. The issue now identifies the missing warehouse
  and model; no foreign-country price is substituted.

## Verification

- 148 backend checks passed against a disposable MySQL schema, including
  label-only snapshot preservation, rule deletion, source-change fallback,
  live/full-job priority, settlement-period deduplication and missing-country
  prices.
- Four frontend suites passed: charges, profit, live costs and filter retention.
  They cover the pending-order link and distinct label-update indicator.
- Read-only production checks after the group move found the historical queue
  empty. August, September and October-to-date label totals matched between the
  charges workspace and overview for both affected groups. Clothing label
  totals were zero after the move.
- The optimization introduces no schema migration; the schema remains at 133.
  Original financial ledger records and configured unit prices are unchanged.
- `finance-charge-fast-20261027` was activated on production with frontend/gzip
  hash checks, backend health checks and anonymous-access rejection. Finance
  schedules were restored and unrelated service configuration was preserved.
- A targeted recalculation refreshed only the reported Malaysia order's
  diagnostic. A subsequent SELECT-only check confirmed zero queued work for
  that report, one genuinely missing product cost, configured label coverage,
  and an explicitly incomplete profit. No unit price was invented.

Release wrappers are `release-finance-charge-period.py`,
`release-finance-charge-coverage.py` and `release-finance-charge-fast.py`.
Coverage was prepared separately and included in the fast release.
Private production verification output remains in the server's finance audit
directory; source files contain no credentials or copied customer records.
