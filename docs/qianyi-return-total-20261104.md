# Qianyi returned-goods cost total — 2026-11-04

The Qianyi after-sales dialog and embedded refund page display a separate
`退货成本合计（CNY）` card for every qualifying document in the current filter,
across all pages. Native-currency refund totals remain separate.

This is a read-only **standard-cost valuation**, not an inventory cost credit:
effective Qianyi return SKU quantities are matched to the company's current
group cost catalog and the shop's configured/inferred cost warehouse. Prices
belong to the actual receipt month in Beijing time. Existing SKU normalization,
bundle conversion, historical-price fallback and zero-price confirmation rules
are reused. Platform cancellation does not make the returned goods free.
Qianyi `orderSkuId` is not treated as a platform model ID; ambiguous/unmatched
SKUs remain incomplete instead of guessing a platform mapping.

Identical source lines with the same `orderSkuId` are counted once; conflicting
duplicates are incomplete. Deleted and unselected lines are excluded.
The calculation uses `returnSkuList.quantity`, including returned goods marked
good or bad; it is not a valuation of only saleable inventory.

The existing receipt qualification is unchanged: actual receive time, completed
sales return, nondeleted source, and verified return-to-seller tracking evidence.
Unqualified documents in the audit view do not enter the total. No label fees,
refund money, inventory credit tables, profit calculations, synchronization
checkpoints or upstream APIs are changed.

Missing costs produce an explicit known subtotal and pending-document count;
they never appear as a complete zero. Documents using previous-month prices
are counted separately. A request-local cost context is reused per shop.
Summary, paged detail, source detail and Excel export share one calculator
and a repeatable-read snapshot. The export includes complete/known amounts and
matching status so partial results remain auditable.

## Verification sample

Shop `81e76929-147b-44b2-8055-9269c8347e1e`, receipt dates 2026-10-06 through
2026-11-04: 187 qualifying documents, CNY 5,301.471514, no pending costs,
nine documents using October prices for November receipts. Independent
Decimal calculation against the source quantities/catalog matched every
document, with zero differences. Native refund total remains THB 44,803.96.
The local sample calculation took 87 ms; the production SQL snapshot took
1.873 s including starting the MySQL client. These are separate measurements,
not an end-to-end browser latency claim.

## Release

Active backend and frontend tags: `qianyi-return-total-20261104`.
Directory: `/opt/heyi/releases/qianyi-return-total-20261104`.
Helper: `scripts/release/release-qianyi-return-total.py`.
Only `FinanceQianyiReturns`, the new `FinanceQianyiReturnCostSummary`,
`frontend/finance-qianyi.js` and `frontend/index.html` are replaced.
There is no migration or data backfill.

Before building, compare the original class and frontend hashes against the
live SKU/cost-summary release. The original Qianyi class was compiled with
`javac --release 21 -parameters` (default source/line debug metadata, without
local-variable debug tables). Compiling the unchanged source with these flags
reproduces the live class byte-for-byte. Preserve every unrelated JAR entry,
run the mandatory compaction guard, drain/restore using the established helper,
and verify runtime configuration, artifact hashes, health and anonymous 401s.

Keep the active release plus backend rollbacks
`sku-shared-cost-total-20261104` and `product-sku-pattern-option-20261104`,
and frontend rollbacks `sku-shared-cost-total-layout-20261104` and
`sku-shared-cost-total-20261104`. Remove owned staging only after verification;
retain Compose overrides, required helper dependencies and small receipts.

## Completed acceptance

- 37 Java unit checks, three disposable MySQL checks and two frontend suites
  passed. The new all-page fixture uses one batched insert to avoid spending
  a synchronization transaction's 15-second deadline on QA tunnel round trips.
  Its failed initial setup wrote only to the disposable schema; no application
  timeout was relaxed. All temporary QA schemas/users/tunnels were removed.
- The unchanged baseline class matched production byte-for-byte. Both scoped
  image builds passed the compaction guard, and unrelated JAR entries stayed
  identical. There are no new persistent application logs.
- Queue draining, activation, health checks, exact artifact checks, unchanged
  runtime configuration, anonymous endpoint 401 checks and schedule restoration
  passed. Qianyi reservations were released.
- At 2026-11-04 13:29 Beijing time, the authenticated browser displayed
  187 documents, CNY 5,301.471514 and THB 44,803.96. The actual downloaded
  Excel had 187 unique return numbers and exactly the same per-document
  cost amounts as the independent calculation. The temporary download was removed.
- Screenshot: `/Users/heyi/Documents/ChatGPT/qianyi-return-total-20261104/return-cost-total.png`.
  Small remote `read-verification.json` and `ui-verification.json` receipts
  retain the result without the full source data.
