# Shared pattern SKU codes and filtered cost totals — 2026-11-04

## SKU policy version 2

Captured merchant codes use `COLOR-STYLE-SIZE-SUFFIX`, for example
`BK-A01-M-8F3A6C2D`, `WH-A01-L-8F3A6C2D`.
The style is one uppercase A–Z letter plus two Arabic digits. The eight-character
uppercase hexadecimal suffix belongs to the pattern, not the individual SKU.
Color abbreviations, size normalization and source-pattern grouping are
unchanged from `product-sku-naming-20261104.md`, including the separately named
pure-white option when no explicit pattern attribute links it to another group.

Additive migration V146 creates `product_sku_pattern_codes`. Company/pattern,
company/style and company/suffix uniqueness are enforced. Style and suffix are
persisted together in a short independent transaction. Recapture, source row
reordering, new sizes, service restarts and concurrent captures keep this pair.
Two variants that collapse to the same color/pattern/size are rejected with an
actionable message instead of silently giving them different pattern suffixes.

Three-character styles provide 2,600 codes per company (A00–Z99). Allocation
skips occupied codes; exhaustion reports a clear error rather than reusing
another pattern's style. V145 and existing saved merchant codes are retained.
New captures and allowed recaptures use version 2; already published products
are not renamed locally without an accompanying verified platform update.
The two earlier live acceptance products remain the version-1 listings recorded
in `product-sku-naming-20261104.md`; they are not evidence of a version-2 upload.
Pattern identity is source-scoped, not global visual similarity detection.

## Optional upload style

The bulk upload dialog has an optional style input after the main-image style
selector and before the size chart. It accepts A01/B12-style codes, normalizes
lowercase and whitespace, and applies the selected style to this batch's
outgoing SKUs. Leaving it blank preserves the saved merchant codes.

`merchantStyleCode` is saved in each immutable upload request. Shop changes and
image-template changes preserve it; accepted or uncertain requests retain their
original body and idempotency key. Both workflow creation and direct publishing
validate the field. TikTok and Shopee apply the same naming service before
building their official payloads. Company/source-pattern persistence supplies
the suffix, including for older captured drafts. Different patterns keep
different suffixes even when this explicit batch style is the same.

The explicit style is an upload choice, not a global rename of the pattern
catalog or existing platform listings. Source SKU rows, images, price, stock,
IDs and prior publication history remain intact. Automatic catalog styles
retain their company uniqueness; an explicitly chosen upload style may be
reused because its pattern suffix distinguishes the full SKU.

## Cost amount summary

The order-cost toolbar shows the CNY product-cost sum for the current shop,
settlement date range, order-number query and cost-status filter across every
page. It excludes label fees, does not change cost calculation or profit rules,
and does not restore return-cost reversal.

The existing aggregation additionally calculates the known portion of pending
orders. `costAmountSummary` selects that amount for PENDING and the existing
overall sum for ALL, without a second aggregate query or extra frontend request.
Queued stale snapshots remain excluded. A partial result is explicitly labeled
as an already-calculated portion with a pending count; no orders yields zero,
while a failed or incomplete query does not present a fabricated zero.

## Release procedure

Active images: `ai-commerce-platform:sku-shared-cost-total-20261104` and
`ai-commerce-frontend:sku-shared-cost-total-layout-20261104`.
Directory: `/opt/heyi/releases/sku-shared-cost-total-20261104`.
Helper: `scripts/release/release-sku-shared-cost-total.py`.

Overlay scope: ProductSkuNaming, ProductSkuNamingService, FinanceCostReport,
ProductPublicationSkuNaming, ProductCaptureSku, ProductCaptureService,
ProductPublishWorkflowService, both official publishing providers, additive V146
and `index.html`, `finance-costs.js`, `finance-costs.css`, `app.js`,
`product-bulk-upload.js`.
Baseline bytecode and frontend hashes must match production. Unrelated JAR
entries remain byte-identical. Both image builds use the compaction guard.
Use the existing queue drain/restore, preserve runtime config and credential
mounts, verify artifact hashes and access protection, then delete owned staging.

## Completed verification and activation

- 147 Java unit/provider checks and five disposable MySQL checks passed.
  The database checks cover persistent suffixes, concurrent captures, collisions,
  the 2,600-style boundary and cost-filter/page/export amount equality.
- Eight frontend suites passed, including 128 bulk-upload/history/style checks.
  Invalid styles stop preparation; normalized values reach each upload;
  saved request bodies and keys survive retry unchanged.
- All 29 existing affected class files and five frontend baseline hashes
  matched production before replacement. Every unrelated JAR entry was preserved.
  Both image compaction guards ran before building.
- Migration V146, exact live artifact hashes, HTTP health, anonymous access
  protection and unchanged runtime configuration passed. All 291 financial
  schedules were restored with zero checkpoint changes; Qianyi and invoice
  reservations were released. Persistent container logging remains disabled.
- Authenticated browser verification showed the style input at the requested
  position and actual keyboard entry `a01` becoming `A01`. No new product was
  submitted during this UI check. Provider payload tests cover the new style;
  the earlier version-1 remote products remain historical acceptance only.
- The cost page for shop `81e76929-147b-44b2-8055-9269c8347e1e`,
  settlement dates 2026-10-06 through 2026-11-04, displayed CNY 365,572.0261
  for 15,552 orders across all pages, 14,537 calculated and zero pending.
  The amount matched the previous page total and the existing detailed stats.
- Browser layout inspection found that the existing wide table expanded an
  implicit grid column and moved the amount beyond the viewport. An explicit
  `minmax(0,1fr)` workspace column and shrinkable tab content contain the table.
  A scoped two-file frontend follow-up applied this correction without restarting
  the backend or touching synchronization; `frontend-layout.json` records it.

Keep this release's Compose overrides, helper dependency chain and small
`prepared.json`, `activation.json`, `qa-passed.json`, `drain-restored.json`,
`ui-verification.json` and `staging-cleanup.json` receipts. Staged classes,
JAR build directory, frontend copies and loose migration are removed after
verification. Disposable QA schemas/accounts/tunnel have been removed.

Retain the active backend plus `product-sku-pattern-option-20261104` and
`product-sku-naming-20261104` backend rollbacks, and the active frontend plus
`sku-shared-cost-total-20261104` and `finance-qianyi-receipt-20261103` frontend
rollbacks. The older unreferenced Qianyi backend and
`finance-sync-priority-ui-20261103` / `finance-shop-month-check-20261103` frontend
image tags are removed; their
required release helpers remain. Never rebuild this cleaned staging directory
without preparing new scoped artifacts.
