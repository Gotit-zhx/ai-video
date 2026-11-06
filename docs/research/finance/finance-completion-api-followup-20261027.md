# Order completion: additional official API investigation

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

Checked on 2026-10-27, Asia/Shanghai. This is a source investigation, not a
deployment or a change to production financial attribution.

## Finding

No independently verified public API field has yet been found that returns
the exact historical Seller Center “订单完成” event for the sample order.
The desired timestamp remains **2026-10-04 02:28:29 Asia/Manila** for order
`586217421074433110` in YUXI-PH.

The order's latest `update_time` is October 14, package last update is
September 27, and statement payment time is September 30. None can replace
that event. The ten-day gap between the first two order-level dates is still
unexplained; no fixed subtraction rule is justified.

## Additional official sources inspected

| Source | Result |
| --- | --- |
| Orders and Fulfillment API Solution Guide, including its linked Order Sync guide | Polling samples the latest state and can miss intermediate states. The guide recommends webhook intake with polling reconciliation. It defines `COMPLETED` as a terminal state in which returns/refunds are no longer allowed. This alone does not establish equivalence to the sample Seller Center timeline event. |
| Official `@tts-open-toolkit/cli` package, version 0.1.7 | Downloaded as a data archive, verified its published SHA-512 integrity, and scanned the 25 bundled OAS domain files (428 operations) for completion/history fields. Did not install or execute the package. |
| Bundled ordinary order detail schemas, 202309 and 202507 | No separate order-completion timestamp. |
| Bundled `GetPrivilegedOrderDetail` schema | No separate completion timestamp either. No privileged production request was made. |
| Event API schema and current webhook guide | Webhook configuration endpoints; no verified historical order-event retrieval/replay endpoint found. Event `data.update_time` and envelope push `timestamp` have distinct meanings. |
| Get Aftersale Eligibility 202602 and Search Aftersales Requests 202603 | Eligibility, return/refund statuses and return-record create/update times; no verified forward-order completion timestamp. |
| Search CAP/TAP Affiliate Orders 202603 | `settlement_time` is commission settlement; `last_update_time` is the affiliate record's latest update. These are not documented as the requested order-history completion event. |
| Search Affiliate Orders by Creator 202505 | Creator-attributed order and commission timing, not a verified seller-order completion source; it also requires the corresponding creator authorization. |
| Create Compass Offline Export Task 202603 | Creator-list and core-performance reports with fixed time windows. It is not a general Seller Center order-history export. No export job was submitted. |
| Finance API Updates: New Fee, Tax, Trade-In, and SKU Data | Additive fee/tax/SKU and Trade-In changes. No order-completion timestamp added. |
| Bundled Data Reconciliation APIs | Import/exchange operations into TikTok's reconciliation system, not an endpoint for retrieving historical lifecycle events. No write calls were made. |
| Bundled full-service shipment `completion_time` | A different supplier/logistics entity with different semantics. Must not be mapped to a TikTok retail order's completion time. |

The package schemas can lag the live portal. They were used to discover
additional candidates, not to override current endpoint documentation.
Keyword-search results also use broad token matching: a hit for “completion”
does not establish an applicable order field.

## Live historical-window test at 17:50 Asia/Shanghai

The Order Sync guide says historical orders can be recovered after an outage.
That must not be interpreted as a guarantee that the APIs replay every past
status version or expose its original event time.

Three independently signed, read-only calls to
`POST /order/202309/orders/search` tested this distinction for the same
sample. All three returned code 0, and pagination was exhausted.

| Filter, interpreted in Asia/Manila | Result |
| --- | --- |
| `update_time` from 2026-10-04 02:28:00 inclusive to 02:29:00 exclusive, containing the screenshot completion event | Zero orders returned. |
| `update_time` from 2026-10-14 02:28:00 inclusive to 02:29:00 exclusive | The sample order returned as `COMPLETED`, with `update_time` 2026-10-14 02:28:29. |
| `create_time` from 2026-09-23 20:08:00 inclusive to 20:09:00 exclusive | The same order returned with its current `COMPLETED` status and October 14 update time, not its September 23 status version. |

This test found no way to recover the October 4 completion event by querying
that historical update window. It does not prove that no private or
permission-specific historical source exists.

Evidence:
`/Users/heyi/Documents/ChatGPT/finance-order-date-audit-20261027/order-history-window-audit.json`.
The original timestamp-only evidence includes API request IDs for support.

## Safe optimization design

1. Keep `update_time` for incremental synchronization. The current finance
   connector already queries `update_time_ge` / `update_time_lt`; it must not
   become the monthly accounting date.
2. Capture signed `ORDER_STATUS_CHANGE` events and preserve their event ID,
   platform shop ID, order ID, status and event time independently of the
   latest order snapshot. Reconcile current state through polling.
3. Treat the `COMPLETED` event timestamp as a candidate until compared with
   the Seller Center event for actual orders. A polling observation time or
   notification delivery timestamp must not silently stand in for it.
4. Once a source is validated, store an immutable completion fact with
   provenance and use indexed local date queries for BI. Later unrelated
   order updates must not move an order between accounting months.
5. Historical records need a source that actually contains the required
   completion event: a verified official export, timeline source, or an
   officially confirmed API. An exported column's name and timezone must
   be checked before import.
6. Missing or conflicting evidence stays explicitly unresolved. Neither
   retries, more threads, a different database nor added cache reconstructs
   an event that the source response does not contain.

No webhook receiver, subscription, completion-date backfill, or finance-basis
change was deployed as part of this investigation.

## Official source locations

- https://partner.tiktokshop.com/docv2/page/ct1jsxkb
- https://bytedance.us.larkoffice.com/docx/GTgMdfok5oXd4fxLqQNucRAgs7d
- https://partner.tiktokshop.com/docv2/page/tts-webhooks-overview
- https://partner.tiktokshop.com/docv2/page/1-order-status-change
- https://partner.tiktokshop.com/docv2/page/tts-open-toolkit-install-setup
- https://registry.npmjs.org/@tts-open-toolkit%2fcli/latest
- https://partner.tiktokshop.com/docv2/page/get-aftersale-eligibility-202602
- https://partner.tiktokshop.com/docv2/page/search-aftersales-requests-202603
- https://partner.tiktokshop.com/docv2/page/search-cap-affiliate-orders-202603
- https://partner.tiktokshop.com/docv2/page/search-tap-affiliate-orders-202603
- https://partner.tiktokshop.com/docv2/page/search-affiliate-orders-by-creator-202505
- https://partner.tiktokshop.com/docv2/page/create-compass-offline-export-task-202603
- https://partner.tiktokshop.com/docv2/page/62g4f932

Source snapshots and the operation index are saved under
`/Users/heyi/Documents/ChatGPT/finance-order-date-audit-20261027/official-api-followup/`.
