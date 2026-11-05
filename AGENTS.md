# Production storage and release rules

For production operations, use the latest release document,
`docs/finance-gift-zero-20261105.md` (active backend:
`ai-commerce-platform:finance-gift-zero-20261105`; frontend:
`ai-commerce-frontend:finance-gift-zero-20261105`). Exact merchant SKU GIFT,
case-insensitive with surrounding whitespace trimmed, has fixed zero product
cost and needs no cost mapping. Other SKUs in mixed orders retain their normal
cost requirements. Original ERP evidence is retained. Unknown costs must not
appear as confirmed zero. The settled-order amount card is original order value,
not platform net settlement, and is not used to calculate profit.
The historical GIFT scan finished for 86 shops and 1,197,175 source orders;
14,245 GIFT orders passed, with zero pending GIFT cost jobs at final acceptance.
See gift-final-verification.json in the production GIFT release directory.
Do not rerun that completed one-shot scan.
Date and receipt rules remain in `docs/finance-report-basis-20261105.md`.
Date inputs use complete
shop-local calendar months independently of the browser timezone. Operational
timestamps display Beijing time. TikTok LOCAL uses shop-local payment_time balance
entry dates; CROSS_BORDER preserves the independently verified Statement Date.
ATC plus four new October original files have been independently compared;
historical migration completion must be checked in date-repair-verification.json.
Independent Shopee Adjustment reconciliation remains pending. Native LZ4
write-time compression is enabled on five main finance
tables; do not turn this into recurring large-table OPTIMIZE. The redundant
idx_finance_bill was dropped (V147); idx_finance_reconcile_tx covers its prefix.
The cost-dedupe rollback uses the previously filesystem-verified compact image.
The report-basis release supersedes `docs/qianyi-receipt-display-20261105.md`:
Qianyi list/detail/export share the same labels, with 未匹配到返程状态 for unknown
evidence. Exact Thai reverse-delivery events are recognized. Empty completion
markers are not pagination cursors; the receipt-resume fix preserves real cursors.
Do not equate forward DELIVERED or returnStatus=FINISH alone with reverse signed
delivery. Original brush-cost template acceptance is in
`docs/finance-brush-original-template-20261105.md`. Brush-cost
download uses the user's original eleven-column 母婴刷单费.xlsx layout, including
both positional month headers, with all original data cleared. 代刷费CNY is the
existing brush fee; no extra service-fee field or financial rule is added.
Source-order lookup remains documented in
`docs/finance-source-orders-20261105.md`. All official orders are
queryable by shop-local creation date, including cancelled and unsettled orders;
exact source-order numbers search across dates while preserving company/shop/group
restrictions. The separate settled view and financial calculations retain their
existing settlement basis. Qianyi receipt dates are independent of settlement dates.
Group/shop cascading remains documented in `docs/finance-group-shops-20261104.md`.
Finance group and platform
filters immediately restrict shop options and clear obsolete shop selections,
including monthly label invoices. Cost-import acceptance is in
`docs/finance-cost-dedupe-20261104.md`. Cost imports keep one
entry when warehouse, model, month and numeric unit price are identical; different
prices for the same key still require review. Fee export acceptance remains in
`docs/finance-fee-export-20261104.md`. Fee-matrix exports always
retain native currency in separate currency sheets, regardless of the dashboard's
CNY display. Receipt recognition remains in
`docs/qianyi-reverse-phrases-20261104.md`. Return-cost totals are in
`docs/qianyi-return-total-20261104.md`. Shared-pattern SKU and
cost-page behavior is in `docs/sku-shared-cost-total-20261104.md`. Prior SKU acceptance is in
`docs/product-sku-naming-20261104.md`; financial synchronization and receipt rules
remain documented in `docs/finance-qianyi-receipt-20261103.md`; storage safeguards remain in
`docs/storage-cleanup-20261102.md`. Preserve credentials, runtime configuration,
business volumes, synchronization checkpoints, rate limits, and current
financial calculation rules.

- Never add persistent application or diagnostic logs. Short-lived operational
  output and small release verification receipts are sufficient.
- Do not grow application images by indefinitely layering complete JARs over
  previous releases. Before building an overlay, run
  `scripts/compact-release-image.py --prepare SOURCE UNIQUE_TARGET`; use the
  returned immutable `baseImage` as the Dockerfile base. It checks image layer
  count/size and verifies the full filesystem and metadata if compaction is
  needed. Never export a running container or one with business volumes.
- Keep the active image and two verified recent rollback versions. Remove older
  application/frontend image tags only after activation and verification.
  Preserve all container-referenced images and unrelated service images. Never
  delete Docker/containerd directories manually or prune volumes.
- After release verification, delete staging JARs, class copies, test schemas,
  private test environment files, and uploaded archives. Preserve Compose files
  referenced by running containers, required release helpers, and verification
  receipts. A historical deployment folder may still contain live configuration.
- Automatic order-cost recalculation must update its current result without
  appending complete before/after JSON snapshots. Manual cost configuration and
  explicit import records remain available. Do not remove original finance data
  as a storage optimization.
- Keep backup bounds and `Persistent=false` for the daily backup timer. Do not
  trigger missed backups during deployment.
