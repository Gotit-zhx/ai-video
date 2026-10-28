# TikTok Shop technical support draft — not submitted

## Subject

PH Seller Center order-completion timestamp does not match any returned
Order/Finance API timestamp; need the authoritative field and history source.

## Shop and order

- Platform: TikTok Shop, PH
- Shop: YUXI-PH
- Order ID: `586217421074433110`
- Required reporting timezone: Asia/Manila, UTC+08:00

We need monthly reporting to match the exact Seller Center order-history
event labelled **订单完成 / Order completed**, rather than statement payment,
delivery or the latest record update.

## Observed timestamps

| Source | Timestamp in Asia/Manila |
| --- | --- |
| Order creation | 2026-09-23 20:08:04 |
| Order delivery | 2026-09-27 19:10:55 |
| Statement payment | 2026-09-30 12:11:52 |
| Seller Center order-history “订单完成” | **2026-10-04 02:28:29** |
| Current Order Detail `update_time` | 2026-10-14 02:28:29 |
| Package Detail `update_time` | 2026-09-27 19:10:54 |

Independent signed requests to both `/order/202309/orders` and
`/order/202507/orders` return the same timestamps. Stored snapshots match
the current official response. No independent order-completion timestamp is
present. Package ID `1212081883479114838` maps to the same order; its
`COMPLETED` status has sub-status `DELIVERED`.

On 2026-10-27 at 17:50 UTC+08:00, a further read-only
`POST /order/202309/orders/search` comparison returned:

- Completion-event window, `update_time_ge=1791052080`,
  `update_time_lt=1791052140`: code 0, zero results.
  Request ID `20261027175058312805299A1FD504ECD5`.
- Current-update window, `update_time_ge=1791916080`,
  `update_time_lt=1791916140`: code 0, the sample order returned.
  Request ID `20261027175058523361D365BE19043FA3`.
- Original creation window, `create_time_ge=1790165280`,
  `create_time_lt=1790165340`: code 0, the sample returned with its current
  status/timestamps, not a historical version.
  Request ID `202610271750596B4792D2FE97D4041157`.

## Questions requiring confirmation

1. Which official API field, endpoint and version returns the exact
   2026-10-04 02:28:29 Seller Center event? Please specify applicable region,
   seller type, permission scope and timezone.
2. Does Order API `COMPLETED` represent exactly the same event as the
   Seller Center “订单完成” timeline entry, or the later closure of
   return/refund eligibility?
3. For `ORDER_STATUS_CHANGE` with `data.order_status = COMPLETED`, is
   `data.update_time` guaranteed to be the Seller Center event timestamp?
   Please explain the observed ten-day difference for this order.
4. Can this event be retrieved historically if no webhook receipt was
   preserved? Is there an event-history/replay API or a Seller Center export
   with an authoritative completion column?
5. If export is required, what exact report, column name, timezone and
   historical retention should be used?

We have not replaced this timestamp with `update_time`, commission settlement,
statement payment or a calculated delivery-plus-days value.

Before submission, attach the original Seller Center timeline screenshot and
allowlisted timestamp/request-ID evidence. Exclude tokens, signatures, buyer
names, addresses and payment-account details.
