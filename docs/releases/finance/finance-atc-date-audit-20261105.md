# ATC October date-basis audit — 2026-11-05

> 发布/验收记录按文中时间适用。本次合并只同步源码和记录；当前业务规则见[财务规则](../../business/finance/current-rules.md)，操作前核对实际运行状态。

Read-only production investigation of the user's screenshot, for
ATC Healthcare Online Store, shop cacf3754-9038-4143-88d0-7205c8f452b6,
TikTok Shop PH, Asia/Manila (UTC+8), PHP.

The screenshot's system selection ends October 30; the official summary ends
October 31. The official screenshot shows total revenue PHP 7,553,119.66 and
net settlement PHP 4,087,160.79. The original official workbook was not provided
for this audit; the two screenshot totals are the external control.

Production stored settlement revenue:

- October 1–30: 32,279 rows, PHP 7,324,048.59, exactly the system screenshot.
- October 31: 930 rows, PHP 222,988.08.
- October 1–31: 33,209 rows, PHP 7,547,036.67 revenue and PHP 3,920,488.25 net.

Adding October 31 leaves a revenue difference of PHP 6,082.99. The source
date implementation in FinanceSettlementDates.day currently uses the UTC
calendar day of statement_time plus one day. This was validated against earlier
September samples, but must not be assumed to match every later report.

Using stored original BILL timestamps, with a bounded September 29–November 3
read and October 1–31 filtering under each candidate date basis:

| Date basis | Bills | Revenue PHP | Net PHP | Revenue difference | Net difference |
| --- | ---: | ---: | ---: | ---: | ---: |
| Current stored date | 40 | 7,547,036.67 | 3,920,488.25 | 6,082.99 | 166,672.54 |
| statement_time in Asia/Manila | 40 | 7,553,119.66 | 4,044,859.77 | 0.00 | 42,301.02 |
| payment_time in Asia/Manila | 39 | 7,553,119.66 | 4,087,160.79 | 0.00 | 0.00 |
| payment_time in UTC | 40 | 7,553,119.66 | 4,044,859.77 | 0.00 | 42,301.02 |

The revenue boundary difference is 237,070.79 (currently stored November 1)
minus 230,987.80 (currently stored October 1) = 6,082.99.
The November 1 stored positive bill 7702248036383065876 has payment_time
1793407377, actually October 31 in Asia/Manila, with net PHP 166,672.54.
The negative bill 7702927839326144264 has payment_time 1793477583, actually
November 1 locally, with net PHP -42,301.02; moving every statement back one
day would incorrectly include it in October.

ORDER and SETTLEMENT channels were SUCCESS with failures 0 and completed_month
2026-10-01. All forty current October bills were SETTLED; their reported
transaction counts and revenue/net sums matched the stored associated details.
No marketing exclusions or manual reporting-month overrides existed for this
shop. These checks support a date attribution mismatch, rather than an
unprocessed bill detail queue, for the reported aggregate discrepancy.

No accounting rows, synchronization checkpoints, date code or production
configuration were changed by this audit. Both official screenshot totals match
the payment-date candidate exactly; this is aggregate reconciliation, not an
independent per-order official-file verification. Any repair must preserve
amounts and identities, explicitly verify other shops/report periods, and
account for the distinct local dates of regular and adjustment bills.
