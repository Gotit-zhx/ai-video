# Completion-date filtering

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## Latest confirmed requirement: order completion, not payment

On October 27 the user clarified the desired reporting event with a Seller
Center order-history screenshot. Order `586217421074433110` completed at
**2026-10-04 02:28:29 Asia/Manila**. It must be attributed to October.
The current statement payment date is September 30; the current order
`update_time` is October 14. Neither equals the required event.

All order-linked revenue, deductions, costs, date filters and exports must
follow the actual order-completion event, with placement and platform payment
dates retained as separate informational fields. August-created orders
completed in September must appear in September; September-created orders
completed in October must not.

A fresh read-only audit of all 27 second-batch orders using
`GET /order/202507/orders` found no separate completion timestamp. The sample's
official tracking history also contained no order-completion event. This
shop's Get Shop Webhooks result has no shop-specific subscriptions; this does
not establish whether any app-level subscriptions exist. The order-status-change webhook
schema offers status/event timestamps for future capture, but has not yet
been validated against the Seller Center completion event and does not by
itself recover historical events. Do not infer completion from last update,
payment, delivery plus a fixed delay, or the latest/current status.

The temporary per-order quarantine proposal was stopped before deployment
when the user clarified this basis. No isolation table or quarantined records
were created. A historical source containing the actual completion timestamps
(such as the official order export) is still required before safely replacing
the production attribution. The implementation described below is the
previously deployed payment-based rule, **not completion of this requirement**.

## Follow-up verification, October 27 at 16:48 Asia/Shanghai

The independent read-only runner `scripts/diagnostics/audit-order-completion-fields.py`
rechecked all **42 distinct orders** (15 from the first batch, 27 from the
second). It signs HTTP requests itself instead of using the application
marketplace client, checks exact returned order identities, and compares
every timestamp-like field against the stored `raw_json`.

- `/order/202309/orders` and `/order/202507/orders`: identical timestamp fields.
- Stored snapshots and fresh official responses: 42/42 identical timestamp
  fields; no missing stored time fields.
- No separate completion timestamp in either version's responses.
- The official portal's downloaded 202507 Order Detail schema likewise has
  `create_time`, `update_time`, and `delivery_time`, but no independent
  `completed_time`, `complete_time`, or `completion_time`.
- Additional official schemas checked: order list, order price details,
  transactions by order, statements, seller affiliate orders and creator
  affiliate orders. No verified alternative for this historical completion
  timestamp was found. `Get Statements.payment_time` concerns statement
  payment and must not be relabeled as the order-history completion event.
- Official `(1) Order status change` documents a `COMPLETED` status and
  `data.update_time` for that event. This is a prospective capture path,
  not historical recovery and not yet proven equal to the screenshot event.
- The current Java application has no order-status webhook receiver or
  immutable completion-event history. A latest order snapshot alone cannot
  preserve an older completion event after subsequent updates.

No production financial dates or amounts were changed during this follow-up.
The audit establishes a reporting-source mismatch, not a missing JSON field
or failed synchronization of these 42 order snapshots. Do not substitute
latest update time or delivery-plus-days. Historical repair requires a
verified source containing the actual completion timestamps (official export
or order-history records); future capture must validate webhook event semantics,
verify signatures, deduplicate events, and retain the original completion event.

## Additional join-source check, October 27 at 17:09 Asia/Shanghai

The official Get Package Detail schema defines package status `COMPLETED` as
**package delivered**, not the order-history completion event. Its `update_time`
is the package's last update. A fresh read-only call to
`GET /fulfillment/202309/packages/1212081883479114838` succeeded and returned the
exact sample order `586217421074433110`, package status `COMPLETED` and sub-status
`DELIVERED`. Its `update_time` is **2026-09-27 19:10:54 Asia/Manila**, not the
required **2026-10-04 02:28:29**. The package response contains creation, pickup
slot and update times, but no independent order-completion time.

The Package update webhook describes split/combine operations, not historical
order-completion events. The documented Event API category contains webhook
management, not a verified order-event-history retrieval endpoint.

The newer CBT-only Get Logistics Tracking API exposes logistics events and their
`update_time_millis`. Its sample request returned API code `11007009` without
data, so this audit cannot make a claim about that order's actual payload from
this endpoint. The previously checked Get Order Tracking response remains the
successful logistics-history evidence.

The concrete association is:

- Join by organization + platform + shop + order ID, preserving IDs as strings.
- A historical source must actually contain the Seller Center order-completion
  timestamp. An official export is usable only after checking that column and
  validating the sample against October 4 at 02:28:29.
- For future events, `data.order_status = COMPLETED` with event
  `data.update_time` is a candidate. Validate it against Seller Center before
  using it for reporting; do not substitute the latest order-detail
  `update_time`, webhook delivery time or first local observation time.
- Once verified, store the completion timestamp in a separate internal
  `order_completed_at` field with source, timezone and original evidence.
  This is a proposed internal field, not a claimed existing platform field.
- Missing or conflicting completion evidence must remain visibly unresolved;
  it must not silently fall back to payment or last-update dates.

No production reporting basis was changed during this additional check.
Sanitized API evidence:
`/Users/heyi/Documents/ChatGPT/finance-order-date-audit-20261027/package-completion-candidates.json`.

Official references:

- https://partner.tiktokshop.com/docv2/page/get-order-detail-202507
- https://partner.tiktokshop.com/docv2/page/get-order-list-202309
- https://partner.tiktokshop.com/docv2/page/order-api-overview
- https://partner.tiktokshop.com/docv2/page/1-order-status-change
- https://partner.tiktokshop.com/docv2/page/get-statements-202309
- https://partner.tiktokshop.com/docv2/page/get-package-detail-202309
- https://partner.tiktokshop.com/docv2/page/4-package-update
- https://partner.tiktokshop.com/docv2/page/get-logistics-tracking-202604

Sanitized local evidence is in
`/Users/heyi/Documents/ChatGPT/finance-order-date-audit-20261027/all42-completion-fields.json`.

## Previously deployed payment-based attribution

Further official-source investigation is recorded in
`finance-completion-api-followup-20261027.md`. It includes the official
Toolkit OAS schemas, the newly linked Order Sync guide, newer aftersales and
affiliate APIs, and report-export candidates. It did not identify a verified
historical replacement field or change the production reporting basis.

The former TikTok connector assigned every settlement to the UTC statement
generation date minus one. That business-day convention does not implement the
requested completed-settlement month and dropped September 1 completed records
into August.

TikTok now uses `Get Statements.payment_time` only when `payment_status` is
`PAID` or `SETTLED`, interpreted in the shop timezone. The raw generation time,
payment time/status/id and date-source explanation are retained separately.
This is the currently implemented payment-based attribution. Its equivalence
to the settlement date used in the official Seller Center export is **not yet
verified**. Do not present it as proof of the requested reporting month, order
lifecycle `COMPLETED`, or bank receipt. Shopee continues using its actual
income-release timestamp.

Records without a verified completed financial timestamp are stored as
`DATE_PENDING`, excluded from period summaries, cost attribution and exports,
and reported in shop sync status. Their internal statement-generation storage
date is not exposed as a completion date. Sync revisits those statements even
after the ordinary lookback. A pending statement does not block subsequent
completed statements.

Existing order/refund/cost joins already select the first settlement date
independently of order creation. Correcting that shared date also corrects
those modules, fees, bills, reconciliation and exports. Later adjustments
retain their own dates; whole-order cost is counted once. Cost pricing basis
and source monetary values are unchanged.

The explicit maintenance command takes allowlisted official statement
metadata, archives prior date metadata, updates at most 200 rows per
transaction, checkpoints every committed batch and is idempotent. Only dates,
date metadata and an unresolved-to-confirmed status transition can change.
Removed shops are protected by the existing shop lock. The runner uses a
temporary SELECT/UPDATE-only record account, publishes real progress, verifies
original settlement/bill amounts and invalidates the new cache namespace.

Private audit evidence: all 704 retrieved statements from eight active TikTok
shops had positive `payment_time` and final `PAID`/`SETTLED` statuses.
The current payment-based rule places the first batch on September 1 and the
second batch on September 30. The user has explicitly confirmed that the
official export excludes the second batch from September and attributes its
formal settlement to October. That discrepancy remains unresolved. Passing
the API/date-mapping tests below does not establish reporting correctness.
Audit additional original timestamps and match the official export before
changing dates again; do not infer settlement from generic order updates.

Validation covers August orders settled in September, September orders settled
in October, timezone boundaries, separate creation timestamps, date-only repair
idempotency, company isolation, amount preservation, period details/cost
scopes/exports, and pending statement continuation.
