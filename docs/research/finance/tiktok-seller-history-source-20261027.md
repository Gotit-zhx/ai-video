# Seller Center order-history source

> 研究或实验记录；样本、限制及未确认项按文中日期适用，不作为生产能力承诺。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

The user rejected manual per-order reconciliation and asked for automatic
attribution matching Seller Center. No quarantine, month guesses, or pending
review workflow was deployed. Existing financial data remains unchanged.

## New finding

The public Seller Center frontend identifies a read endpoint:

```text
POST /api/v1/fulfillment/order/history
client: ListSellerMainOrderHistory
body: main_order_id, offset, page_size
optional schema parameter: time_zone
response: data.order_history[]
```

The order-detail component maps each history entry's `description`, `detail`,
`trans_time`, and `timestamp`. It displays the description as the event title
and formats **`timestamp * 1000`** as the event time. It does not use the
order-detail `update_time` for this timeline.

This establishes the official UI data binding, **not** a successful
authenticated response for a particular order. The endpoint belongs to the
Seller Center web application. Do not send Open API credentials to it or
claim that normal shop OAuth automatically grants a Seller Center session.

Public source artifacts:

- Shell: `https://seller.tiktokshopglobalselling.com/`
- Remote:
  `https://lf16-oversea.goofy-cdn.com/obj/goofy-sg/gftar/i18n/ecom/shop/order/1.0.2.3790/TTS/unihan/order.sg_tts_cb.js`
- Order detail:
  `https://lf-gs-frontend-cn.fanchenstatic.com/obj/she-op-static/i18n/ecom/TTS/unihan/order/static/js/order-detail.0a717deadd.js`

Saved source excerpts and hashes:
`/Users/heyi/Documents/ChatGPT/finance-order-date-audit-20261027/seller-timeline-source/seller-history-source-proof.json`.

## Verification prepared

`scripts/diagnostics/audit-tiktok-seller-history.mjs` reads the displayed order's history
using the official page's initialized client in an authenticated ego-browser
Page. It does not copy session cookies, change orders, or write financial data.
It fails on login/schema/pagination errors, collects only event timestamps
and sanitized labels, and compares a completion event with the reference.

Reference: order `586217421074433110` in YUXI-PH must produce
`2026-10-04T02:28:29+08:00`. Its September 30 financial payment and October 14
last update must not replace this event.

The parser has synthetic regression checks for event selection, conflicting
events, long order IDs, timestamp validation and exclusion of personal data.
These checks do not validate an actual Seller Center response.

## Still required before production

1. Authenticate Seller Center and verify the actual reference order response.
2. Confirm shop identity and exact event labels; inspect all history pages.
3. Verify first-batch September and second-batch October examples against
   official history. Cancelled adjustments need their original event evidence.
4. Establish an authorized, sustainable session or officially supported
   server integration before claiming unattended automatic synchronization.
5. Persist verified completion facts, use indexed completion-date filtering
   consistently across related amounts/costs/exports, and retain original
   financial payment dates for statement reconciliation.

No production reporting-date change or automated synchronization has been
deployed from this discovery.

## Resumed audit: production baseline and current blocker

The active goal is to verify the real Seller Center order-history endpoint,
integrate the validated completion source, and reconcile the user's 27 October
orders. Preparing a parser or passing synthetic tests does not satisfy this goal.

A fresh read-only production query saved
`/Users/heyi/Documents/ChatGPT/finance-order-date-audit-20261027/seller-history-current-baseline.json`.
It contains 453 allowlisted financial records and 42 order-cost records for the
two user-provided batches. All 27 second-batch orders still have a September 2026
settlement record; none has been moved to October by this investigation.

The audit helper now contains all 27 October IDs and the 15 unique September IDs
(the original September message repeated one ID). It supports bounded batches
of up to five, verifies the reference event first on every batch, checks month
boundaries in Asia/Manila, stops on session/schema/reference errors and allows
per-order checkpoints. Six synthetic regression tests pass. No authenticated
history result has been obtained.

The fresh ego-browser inventory still shows task space 31 delegated to the user,
with title `TikTok Shop Seller Log In | Cross Border`. The browser login/native
permission handoff has not been confirmed complete. Do not reclaim that space
or create a substitute space to bypass this handoff. Ask the user to log into
the relevant YUXI-PH seller account and confirm, then resume the same space.
After this check, the same login/handoff condition persisted for three goal
turns and the goal was marked blocked.

## 2026-10-28: user requested direct calls without logging in

The user explicitly chose not to log in yet. No browser handoff was reclaimed
and no session cookies were copied. A direct read request was made for the
reference order:

```text
POST https://seller.tiktokshopglobalselling.com/api/v1/fulfillment/order/history
{"main_order_id":"586217421074433110","offset":0,"page_size":50}
```

The response was HTTP 200 with only `{"code":10000}`. It contained neither an
error description nor `data.order_history`. Do not assign a specific meaning
to that code without evidence; this test establishes that the session-free
request did not obtain history.

Using existing authorized shop credentials on the documented Open API host,
the 202309 and 202507 order detail endpoints were queried again. Both returned
the same time fields for all 42 examples. For the requested 27-order second
batch, all stored time fields matched the live response and none returned a
separate completion timestamp. The reference still reports `update_time` of
October 14 at 02:28:29 UTC+8, not the screenshot completion event on October 4.
Current API statuses are 18 COMPLETED and 9 CANCELLED; current status alone does
not establish historical completion/settlement or permit dropping adjustments.

Evidence directory:
`/Users/heyi/Documents/ChatGPT/finance-order-date-audit-20261028/`

- `seller-history-direct-no-session.json`: sanitized direct-call response.
- `all42-completion-fields-live.json`: live Open API timestamp comparison.
- `27-orders-direct-api-audit.md`: per-order report for the requested batch.

Real historical completion verification, production integration and corrected
monthly accounting remain incomplete. No production date or money was changed.
