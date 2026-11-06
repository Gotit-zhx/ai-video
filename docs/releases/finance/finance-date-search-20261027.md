# Order placement visibility and exact-number lookup — 2026-10-27

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

Order-linked finance detail tables now display the original order placement
timestamp separately from the reporting date. Refund creation and platform
update timestamps do not substitute for order placement. Shop timezones apply
to display and export. Bills show the earliest/latest placement timestamps of
their related orders rather than inventing a single order timestamp. Missing
times remain blank. Page enrichment uses batches of at most 100 records,
including orders without item arrays.

Default visible/exported fields add placement time once for existing user
preferences. Subsequent explicit field choices remain respected. Order-cost
tables show placement time alongside first-settlement reporting date.

Detail and reconciliation searches accept up to 100 complete identifiers,
separated by whitespace or commas, with duplicates removed. Identifiers
remain strings, including leading zeroes and long numeric IDs. Queries are
parameterized and preserve company, active-shop, group and period constraints.
Searching a bill by an order finds the whole bill; reconciliation still
compares all of that bill's transactions. Generic dashboard aggregates keep
their period scope, with the detail-only search explicitly labeled. Cost
order summaries and exports use the same narrowed order relation.

The charge-source list also supports order-number lookup and original order
placement time. Its existing distinction between lifetime imported sources
and period-based financial summaries is preserved.

## Date audit boundary

Read-only inspection of the user's two batches (15 and 27 unique orders)
found that the current TikTok reporting date is the statement UTC calendar
day minus one. This is the day covered by the generated statement, not an
individually returned order completion timestamp.

The live official order-finance API was called for every supplied order.
The two associated statements were verified with Get Statements (202309);
their payment IDs were then checked with Get Payments (202605), including
the `paid_time` field and final `PAID` status. The first batch belongs to a
September 1 generated/paid statement; the second to a September 30
generated/paid statement. October order `update_time` values do not prove
an October settlement.

The user's requested actual-settlement basis must not silently be replaced
with order creation, order last update, or statement generation. The
remaining difference with the user's October completion examples requires
identifying the corresponding seller-center field. This release changes no
stored reporting dates, financial amounts, cost snapshots, or monthly totals.
The private per-order audit artifact is kept outside source control.

## Validation

Integration coverage includes cross-month order placement vs settlement
filtering, refund creation-time separation, missing item arrays, bill order
searches without truncated reconciliation, company isolation, exact long
identifiers, duplicated input, old field-preference upgrades, user opt-out,
and consistent financial/cost exports.

Frontend checks cover separate dates with seconds/timezones, batch-search
requests and pagination reset, scope retention, export query propagation,
and unchanged rows retaining their DOM nodes on refresh.

150 backend tests passed in a disposable MySQL schema; five frontend suites
passed. A local browser preview confirmed the separate date columns and
compact batch-search control. The production release passed asset/class
hash verification, health checks, unchanged schema 133, and queue-drain
checks. A temporary SELECT-only production account verified order, settlement,
bill and fee lookup plus original placement times; searching reconciliation
by the supplied order found its complete matching bill. The temporary
verification account was removed after use.
