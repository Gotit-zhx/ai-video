# Asynchronous shop-column fee exports — 2026-11-04

The settled platform fee composition card gains a “全体导出” action and a
dropdown task panel. Exports keep the selected calendar dates, group (including
ungrouped), shop, platform and native currency filter. Rows are fee
categories; columns are all matching shops, with company aliases preferred and
duplicate names disambiguated. A final column and row contain totals. A separate
record-count sheet keeps fee-row counts distinct from unique orders.

Exports always preserve the platform's native amounts, even when the dashboard
displays CNY. Each native currency gets separate amount and record-count sheets;
no exchange-rate service or snapshot is used and no cross-currency total exists.
Negative subsidies remain negative; absent categories are zero;
missing amounts are marked “待补齐” and cannot produce a misleading partial total.
No order-number detail search is applied: the source card is the full settled
fee composition for the selected date/shop scope.

The source reuses the dashboard's existing native daily fact cache, company
projection, settled date rules and marketing-order exclusion. No separate
per-shop raw-ledger scan is added. The shared loader is extracted without
changing the dashboard's existing calculations.

`POST /api/v1/finance/fee-exports` returns a queued task immediately. List, status
and download routes remain authenticated and bound to both company and user.
Read-only finance viewers can export. Repeated idempotency keys and simultaneous
identical selections share a job. No automatic POST or generation retry occurs.
The dropdown polls status only, allows continued page use, and retains the exact
original request/key for an explicit recovery of an uncertain submission.

One background worker has eight queued slots; each user can hold two pending
tasks. At most 32 tasks / 64 MiB of completed data are retained in memory,
with an 8 MiB per-file limit and 30-minute expiry after completion. Files are
not persisted to disk; a backend restart clears these temporary jobs. No schema,
business record, financial sync checkpoint, persistent logging or SKU policy
changes are introduced.

Release helpers: `scripts/release-finance-fee-export.py` and
`scripts/release-finance-fee-native.py`.
Final target images: `ai-commerce-platform:finance-fee-native-20261104` and
`ai-commerce-frontend:finance-fee-native-layout-20261104`.
Final release directory: `/opt/heyi/releases/finance-fee-native-20261104`;
the initial helper remains in `/opt/heyi/releases/finance-fee-export-20261104`.
Five backend class families and four frontend files are replaced over verified
baseline hashes. All other JAR entries are preserved. Image compaction, safe
queue drain, runtime-configuration equality and protected-route checks apply.
The dropdown is clamped to the workspace boundaries when header buttons wrap.
The toolbar aligns wrapped actions to the right, preserving this position across
background DOM updates. The final layout update restarts only the frontend.

Keep the active backend and `qianyi-reverse-phrases-20261104`,
`qianyi-return-total-20261104` rollbacks; keep the active frontend and
`qianyi-return-total-20261104`, `sku-shared-cost-total-layout-20261104` rollbacks.
Remove only older unreferenced application tags after acceptance. Retain Compose
dependencies, helpers and small receipts; remove owned class/JAR/frontend staging
and temporary verification files after verification.

## Acceptance

The final native-only release is active. Exact artifact hashes, unchanged
runtime configuration, health and anonymous 401 checks passed; the established
drain restored schedules and released reservations without resetting checkpoints.
79 Java checks and three frontend suites passed, including a request that carries
dashboard CNY/FX fields but still creates only a native-currency export.

Authenticated UI generation and actual downloaded workbooks covered all 86 shops,
a 13-shop group and a single shop. Scope, aliases, row totals and column totals
matched. After the native-only change, the all-shop October 2026 workbook was
submitted while the dashboard displayed CNY. It contained separate MYR, PHP and
THB amount/count sheets and no CNY conversion sheet. Comparing the downloaded
amounts to the page's original-currency view produced zero differing fee categories:

| Currency | Fee categories | Native fee total |
| --- | ---: | ---: |
| PHP | 35 | 14,494,665.82 |
| THB | 31 | 6,604,340.66 |
| MYR | 20 | 155,222.63 |

These are export-to-current-system checks, not independent reconciliation against
each platform's official files. Numeric cells, first-row/column freezing and
workbook readability were checked. The dropdown recovered completed tasks after
a page reload, and changing filters after submission did not change the queued
task's captured scope.

Small verification receipt:
`/opt/heyi/releases/finance-fee-native-20261104/export-verification.json`.
Local evidence:
`/Users/heyi/Documents/ChatGPT/finance-fee-export-20261104/verification.json`.
