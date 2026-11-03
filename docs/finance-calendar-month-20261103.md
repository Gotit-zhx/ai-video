# Complete previous-calendar-month synchronization — 2026-11-03

Current release and subsequent missing-income batch transport correction:
`finance-qianyi-receipt-20261103.md`. The release and first acceptance figures
below describe this earlier rollout; use the latest dated receipt for progress.

## Verified discrepancy

The official `YUXI-母婴-SHOPEE 1-菲律宾-20261001_20261031.xlsx` Income sheet
contains 5,577 distinct orders. The production October ledger contained 4,846.
The missing 731 orders were released on October 1 (346), October 2 (214), and
October 3 (171). Their released income totals 184,428.85 PHP. The former first
pass selected a rolling 30-day period, leaving these days to a later historical
pass.

The complete official Income totals are 1,575,598.43 PHP released income and
2,266,788 PHP goods revenue. The separate Adjustment sheet totals 5,547.31 PHP;
do not claim whole-workbook reconciliation by silently merging or omitting it.

Target financial connection: `98c482ea-5f42-4117-a8d0-d14d8bb640da`;
organization: `6a61f788-6685-4b64-82b1-eec97ed8cf90`;
external Shopee shop: `1687390952`; business timezone: `Asia/Manila`.
Platform name is `YUXi Mall`, shared display alias is
`YUXI-母婴-SHOPEE 1-菲律宾`.

The existing 4,846 BILL dates and amounts matched the official Income rows.
Separately, 16 SETTLEMENT rows used the cumulative escrow-after-adjustment value,
overstating these payouts by a net 1,943.86 PHP. One fully refunded order
reconstructed goods revenue as 101 PHP even though the final platform field
and official revenue were zero.

## Implementation

- ORDER and SETTLEMENT prioritize the entire preceding calendar month in the
  shop's business timezone. On November 3 this means October 1 through
  October 31, not the most recent 30 days.
- The complete plan is persisted before requests. Existing historical, page,
  bill and transaction cursors remain nested resume points with their exact
  original ranges. Replayed records retain their existing idempotent identities.
  Completed month windows are skipped when the older history resumes, except
  that an already-started overlapping pagination chain is finished intact.
- `V143` adds `completed_month` and `month_completed_at` to the small
  `finance_sync_states` table. Only the successful final page of an explicit
  full-month scan certifies the corresponding channel. No existing row is
  pre-certified and no historical cursor is reset by the migration.
- The shop's completion display requires both ORDER and SETTLEMENT certificates.
  Open PARTIAL or DATE_PENDING bills still block completion. A generic recent
  `last_success_at` and an old `coverage_start` no longer prove a complete month.
  Unrelated older history does not invalidate a certified month.
- Shopee BILL and SETTLEMENT use the same validated `released_amount` for the
  represented payout. Missing/invalid values fail without advancing; zero is
  valid. Raw escrow and adjustment fields remain available.
- Shopee goods revenue prefers final `cost_of_goods_sold`, including zero,
  less seller voucher/coin discounts. The legacy reconstruction remains only
  when that final field is unavailable. Refunds/merchant discounts are not
  deducted a second time.

No platform concurrency, page size, cooldown, manual priority, Qianyi refund
source, disabled return-cost reversal, shared alias, or cache-retention rule
changed. No persistent application or diagnostic logs were added.

## Validation

- Nine unchanged class files first compiled byte-for-byte equal to production.
- 62 unit/HTTP checks and 18 disposable-MySQL checks passed, covering calendar
  rollover, exact cursor preservation, retry, lease fencing, both completion
  certificates, pending bills, idempotency, payouts and goods revenue.
- Against all 4,846 persisted October orders, the candidate had zero order-date,
  released-amount or goods-revenue mismatches against the official file, and no
  extra orders. This verifies the candidate calculation for existing rows;
  it does **not** prove that the missing 731 rows have already been fetched.

## Release

Backend: `ai-commerce-platform:finance-calendar-month-20261103`.
Frontend remains `ai-commerce-frontend:finance-shop-month-check-20261103`.
Release directory: `/opt/heyi/releases/finance-calendar-month-20261103`.
Helper: `scripts/release-finance-calendar-month.py`.

Only the FinanceSync, FinanceSyncPlan, FinanceService, FinanceSyncMonth,
FinanceMarketplace and FinanceSettlementBreakdown families plus V143 changed
in the JAR. Every unrelated entry was compared byte-for-byte. The image guard
and normal queue drain/restore completed; schema, health, exact artifact hash,
access protection and no-log settings passed after activation.

The user-requested advertising developer configuration is also activated through
a read-only `/opt/heyi/shared/finance-ads` mount at `/run/heyi-finance-ads`, with
`APP_TIKTOK_ADS_CREDENTIALS_FILE` pointing to its properties file. This is the
only runtime configuration exception; the secret is outside Git and release
archives. All other runtime settings and the frontend container are preserved.
No advertising account was authorized and no cloud API scope was changed.

Read-only verification:

```sh
python3 -B /opt/heyi/releases/finance-calendar-month-20261103/release-finance-calendar-month.py \
  /opt/heyi/releases/finance-calendar-month-20261103 verify
```

Keep the helper, receipts, live Compose dependencies and private shared
configuration. Remove owned source/test/class/JAR/upload staging after
verification. Do not rebuild a cleaned release folder without preparing it.
Retain the active backend and the two verified preceding backend versions:
`finance-query-reuse-20261103` and `finance-shop-month-check-20261103`.
Never prune volumes or delete Docker filesystem paths.

## Backfill acceptance and follow-up

All 86 active enabled connections (46 Shopee, 40 TikTok Shop) use the new monthly
planner. On activation, 109 healthy idle ORDER/SETTLEMENT schedules waiting for
their next run were brought forward once. Existing cursors, manual priorities,
active leases and error/rate-limit cooldowns were preserved.

At the first post-release comparison, the target still had 4,846 distinct
October settlements, no duplicates or extra orders, and 731 missing orders.
Its new checkpoint covered October 1–8 and was advancing. The old 16 payout
and one goods-revenue mismatches still awaited normal refresh. Do not describe
these remaining live differences as already corrected just because the code is
deployed. `backfill-verification.json` is a small acceptance receipt, not a log;
replace it when a meaningful new comparison is made.

For the official sample, compare full order sets, not only counts:

1. Missing and extra order sets must both be empty; duplicates must be zero.
2. Each matched order's business date, released amount and goods revenue must
   match the corresponding Income row.
3. Compare totals only after the per-order checks. Handle Adjustment separately.
4. Check completed-month certificates, open bills and missing linked orders.

Other shops without an official export can be checked for complete API
pagination, dates, uniqueness, preserved cursor history and unresolved details.
Do not claim their official export totals have been independently reconciled.
The existing 15-minute heartbeat now tracks this recovery and stays quiet during
normal queueing/progress; it notifies on verified completion or actionable
failures. Preserve all existing platform limits.
