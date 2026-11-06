# Official source-order lookup — 2026-11-05

The order page defaults to all captured official orders by the shop's local
order-created date. Cancelled and unsettled orders are included, as are orders
settled in another month. This applies to every Shopee and TikTok Shop connection.
The scope selector retains the existing settled-order view by first settlement
date. Following the overview's settled-order metric selects that settled view.

Complete order-number searches in the all-order view cover all creation dates.
Company, visible-shop, group, platform and currency restrictions still apply.
The page and export explicitly identify this date exception. Exact lookup uses
the existing company/shop/kind/order index with the company's bounded shop set;
ordinary date paging selects identities before loading provider payloads.
No new index or schema migration is required.

Order-created and first-settlement dates are separate. Missing settlement is
shown as “暂无结算”; zero-value settlement is still a real settlement. The source
detail and CSV/XLSX exports use the same query and date basis. The source-order
export does not append an unrelated settlement-reconciliation sheet. An order's
detail can open its Qianyi aftersales by exact shop and order number across dates,
including unconfirmed source documents for inspection.

All-order loading is one bounded records request with company shop/group metadata
and the optional FX snapshot. It does not wait for dashboard aggregates, charts
or a settled-cost summary. The all-order page does not present settlement
aggregates as totals for the broader source-order list. The settled view retains
the previous summary and chart behavior. Shared aliases and group/shop cascading
remain in effect.

Financial accounting is unchanged. Income, fees and profit retain their existing
settlement basis; unsettled orders do not acquire recognized product costs.
Qianyi monthly receipt qualification, unknown tracking evidence, cost reversal
rules and synchronization schedules/checkpoints are unchanged. Source lookup does
not backfill or insert orders.

Validation:

- Four UI suites passed: source orders, date/search/export behavior, query loading
  and Qianyi refunds. Coverage includes both scopes, cancelled/November-settled
  rows, cross-date number lookup, frozen export selection, pagination, aliases,
  shop/group/platform filters, detail linkage and obsolete-response isolation.
- Four disposable MySQL checks passed: broader source visibility and exports,
  tenant/shop/group/platform/currency boundaries, and the existing settlement
  accounting/date/search/export regressions. QA schema and account were removed.
- Read-only production comparison at 2026-11-05 09:45 Beijing returned all twelve
  sample orders from YUXI-母婴-SHOPEE 2-泰国; the existing October-settlement
  query still returned four. Source lookup took 395 ms in this sample. Within one
  read-only repeatable-read transaction, the old and candidate implementations
  returned identical 379 accounting buckets and identical product-cost summaries.
  This verifies the sample and calculation preservation, not independent official
  reconciliation of every shop.
- Authenticated production UI acceptance on 2026-11-05 selected 母婴-YUXI,
  YUXI-母婴-SHOPEE 2-泰国, October 1–31 and all twelve exact order numbers.
  The page showed “当前筛选 · 共 12 条” with the explicit all-dates number-search
  label. Seven cancelled orders showed no settlement; order 261025GYD7V4M7
  showed first settlement on November 1. The twelve are captured official
  source orders; this acceptance does not establish that all twelve Qianyi
  returns were actually received in October or resolve unknown return evidence.
- Active artifacts, gzip/HTTP content, preserved runtime configuration and
  unrelated application files passed deployment verification. The homepage
  returned 200 and anonymous all-order records/export requests returned 401.
  Both application containers were running with logging disabled. Original
  schedules were restored and Qianyi reservations released after deployment.

Release helper: `scripts/release/release-finance-source-orders.py`.
Release root: `/opt/heyi/releases/finance-source-orders-20261105`.
Backend image: `ai-commerce-platform:finance-source-orders-20261105`.
Frontend image: `ai-commerce-frontend:finance-source-orders-20261105`.

Only FinanceRepository/FinanceController class families and
index.html/finance-workspace.js are replaced over the verified live artifacts.
All unrelated JAR entries, including financial calculators and sync workers,
are byte-identical. The existing image compaction guard, worker drain and Compose
activation helpers preserve runtime settings, mounted credentials, limits and
checkpoints. No persistent logs are added. Small verification receipts are
`qa-passed.json`, `read-verification.json`, `prepared.json` and `activation.json`.

After acceptance retain this backend plus finance-cost-dedupe-20261104 and
finance-fee-native-20261104; retain this frontend plus finance-group-shops-20261104
and finance-fee-native-layout-20261104. Remove only older unreferenced application
tags and this release's temporary JAR/class/test staging. Preserve Compose files,
release helpers, verification receipts, business volumes and backups.

Post-acceptance cleanup removed this release's build/test staging and the
unreferenced qianyi-reverse-phrases-20261104 backend and
qianyi-return-total-20261104 frontend tags. All six active/rollback application
tags, container mounts and Compose files were retained; core containers remained
running. The small receipt is `cleanup-verification.json`. Local staging was
also removed, retaining verification receipts and the authenticated UI screenshot.
