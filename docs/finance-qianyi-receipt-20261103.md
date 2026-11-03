# Qianyi receipt-month statistics and missing-income recovery — 2026-11-03

## Active behavior

Qianyi aftersales statistics now require all of:

1. An actual `receiveTime` in the selected calendar period, using Beijing time.
2. A non-deleted `SALES_RETURN` document with `returnStatus=FINISH`.
3. Explicit evidence that the corresponding return shipment reached the seller.

The query defaults to the current calendar month. An explicitly selected month
uses its complete date range. Creation, modification and completion times never
substitute for a missing receipt time. `refundStatus` is a separate source field;
it remains filterable but is not an additional mandatory qualification.

The list has separate receipt-time, creation-time and reverse-logistics columns.
The same qualification and date range apply to summaries, operating-cost
refund-check details and exports. “全部单据（核对）” permits inspection of other
types/states; only qualifying receipts contribute to its summary amounts.
Missing logistics evidence is displayed separately and excluded from totals.
Company isolation and shared shop aliases remain unchanged.

Amounts remain Qianyi `orderRefundAmount`, grouped by the source currency.
They are not inventory purchase costs. The previously canceled return-cost
reversal and its effect on operating cost/profit remain disabled.

## Source verification

Official references:

- `https://open.qianyierp.com/zh-CN/erp/refund.html`
- `https://open.qianyierp.com/zh-CN/erp/logistics.html`
- `https://open.qianyierp.com/zh-CN/commons/enums.html`

Real API responses confirmed receipt timestamps in milliseconds. The return
endpoint does not directly supply the required reverse-logistics status.
`customNumber` supplies the tracking number. Enrichment queries `/api/v1/firstLeg`
using service `QUERY_FIRST_LRG_TRACKING_PACKAGE`, `trackingNumbers`,
`returnDetails=true` and bounded pagination.

Matching requires the exact tracking number plus consistent sale/return order
identity. A logistics row's top-level `CANCEL` can describe the original outward
shipment; neither it nor generic `DELIVERED` proves a returned parcel was signed
for. The implemented terminal evidence recognizes the actually observed
“Your package has been returned to the seller.” event, including its “in …”
location variant. Return-in-transit events are excluded. The latest relevant
return event is used; conflicting evidence at the same timestamp is unknown.
Unrecognized wording, mismatched identifiers, missing pages or failed requests
remain unknown rather than being guessed as signed.

`data_json._returnReceipt` stores only the sanitized evidence, event time,
tracking number and source signature. The signature binds the evidence to the
return/order/tracking/receipt/status/update fields. Unchanged confirmed evidence
is reused. Logistics-only updates preserve existing cost rows and notes.

Source version 3 first scans receipt dates from the beginning of the previous
calendar month through today. Existing version-2 sources also receive a bounded
historical replay. Existing page/window cursors are nested and restored intact.
After that, daily receipt scans refresh unknown evidence even if the return
document's own update time has not changed. The shared upstream limiter remains
in force; batches contain at most 20 tracking numbers, with at most three
100-record pages per batch. Browsing never calls Qianyi remotely.

`V144__qianyi_receipt_qualification.sql` adds virtual receipt/evidence columns,
receipt-month and qualified-receipt indexes, and `last_receipt_scan_at`.
The production query plan used `idx_qianyi_receipt_qualified`.

## Shopee missing-income transport correction

The preceding calendar-month repair is documented in
`finance-calendar-month-20261103.md`. The intervening missing-first release
prioritizes missing previous-month income before normal automatic refresh, while
manual requests retain first priority. It examines 30 income rows per page,
skips only complete matching BILL/SETTLEMENT pairs and batches up to 20 escrow
details per upstream request. Full normal pagination must still finish before
month completion is certified.

The official batch-escrow documentation labels the operation GET. Production
rejected its query/body variants with `order_sn_list is required, format should
be string[]`. A real signed POST with JSON `{"order_sn_list":[...]}` returned both
requested test orders completely. The active `FinanceShopeeIncome` therefore
uses the existing signed `shopPost` transport. No endpoint or signing bypass was
introduced. Response IDs and completeness are checked before any page writes
or checkpoint advancement.

After deployment, only 28 idle schedules carrying that exact format error were
brought forward once. Platform pacing, unrelated error cooldowns and checkpoints
were retained. Do not retain the earlier broken `finance-missing-first-20261103`
image as a verified working rollback.

## Release and validation

Active images:

- `ai-commerce-platform:finance-qianyi-receipt-20261103`
- `ai-commerce-frontend:finance-qianyi-receipt-20261103`

Release directory: `/opt/heyi/releases/finance-qianyi-receipt-20261103`.
Helper: `scripts/release-finance-qianyi-receipt.py`.

Only the FinanceQianyi, FinanceQianyiClient, FinanceQianyiWorker,
FinanceQianyiEvidence, FinanceQianyiReturns, FinanceQianyiReceipt and
FinanceShopeeIncome class families plus V144 changed from the preceding live
JAR. Every unrelated entry was byte-compared. Frontend changes are limited to
`index.html`, `finance-qianyi.js` and `finance-workspace.js`.

- Eleven unchanged baseline class files matched production bytecode.
- Fifteen unit checks and 21 disposable MySQL checks passed.
- Both Qianyi JSDOM suites, JavaScript syntax and whitespace checks passed.
- Queue drain/restore, image compaction guards, schema, exact artifact hashes,
  access protection, health and disabled persistent logging passed.
- Runtime configuration, including the private advertising credential mount,
  remained unchanged. Temporary QA schemas/accounts were removed.

At **2026-11-03 19:09:59 Asia/Shanghai**, a production read-only snapshot verified
the actual service list, summaries, SQL and XLSX export:

| Receipt month | Qualified / exported | Audit rows | Awaiting logistics |
| --- | ---: | ---: | ---: |
| October 2026 | 1,278 / 1,278 | 4,332 | 2,846 |
| November 2026 | 9 / 9 | 243 | 228 |

Both periods passed qualification and currency-total equality checks.
The combined list/SQL/export/audit checks took 3,828 ms for October and 191 ms
for November. These are verification timings, not isolated endpoint latency.
The historical receipt replay was still running: these counts are a dated
snapshot, not an assertion that all historical evidence has been enriched.
The small production receipt is `read-verification.json`.

At **2026-11-03 19:10:12 Asia/Shanghai**, the official Shopee October sample had:

- 5,332 system orders against 5,577 official orders: 486 recovered, 245 missing.
- Zero duplicate orders, extra orders or matched-order date differences.
- Fifteen payout differences and one goods-revenue difference still awaiting
  the normal refresh pass; do not call the sample fully reconciled.
- Zero remaining batch-parameter errors and zero database lock waits.

The latest sample acceptance receipt replaces
`/opt/heyi/releases/finance-calendar-month-20261103/backfill-verification.json`.
Future checks must compare actual sets, dates and amounts against the official
fixture; successful HTTP calls alone do not prove reconciliation.

The follow-up at **2026-11-03 19:18:20 Asia/Shanghai** verified that all **731
missing orders were recovered**: the official and live sets both contain 5,577
orders, with zero missing, extra, duplicate or date-mismatched orders. Thirteen
previously stored released amounts and one goods-revenue value still differed
and awaited the normal refresh. Live released income was 1,577,510.56 PHP
against official 1,575,598.43 PHP; full monetary reconciliation remains open.

Read-only release verification:

```sh
python3 -B /opt/heyi/releases/finance-qianyi-receipt-20261103/release-finance-qianyi-receipt.py \
  /opt/heyi/releases/finance-qianyi-receipt-20261103 verify
```

Keep the helper dependency chain, prepared/activation/validation receipts and
all Compose files used by running containers. Remove owned JAR/class/source/test
staging, temporary private environments and upload archives after acceptance.
Do not rebuild a cleaned release folder without preparing it.

Retain the active backend plus verified `finance-calendar-month-20261103` and
`finance-query-reuse-20261103` rollback images. Retain the active frontend plus
`finance-shop-month-check-20261103` and `finance-sync-priority-ui-20261103`.
Never prune volumes or manually delete Docker filesystem paths.

Cleanup completed at **2026-11-03 19:15:54 Asia/Shanghai**: 68 owned staging
paths (751,935,488 allocated bytes) and five obsolete image tags were removed.
Live configuration, helper dependencies and the six listed active/rollback
images were retained. Free disk space increased from 11,944,402,944 to
12,678,324,224 bytes while synchronization continued. Release verification
passed again after cleanup; business volumes were untouched. The small receipt
is `cleanup-verification.json`.
