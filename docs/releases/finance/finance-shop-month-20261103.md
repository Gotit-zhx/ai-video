# Shop month status and compact controls — 2026-11-03

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## Display and query

- The shop page retains the total authorized-shop and enabled-sync counts.
  A current-group count and each shop's badge show “上月已完成” or “上月未完成”,
  with the explicit target month.
- The server determines the target month using its clock and the shop's
  timezone. Both `ORDER` and `SETTLEMENT` require full-scan history covering the
  previous month's start and a successful timestamp in the current month.
  **A previous success is insufficient**: an unfinished scan whose remaining
  date range intersects the previous month blocks completion. An unknown
  unfinished range also blocks it. Refreshes covering only the current month
  do not reopen the previous month.
- Previous-month API bills in `PARTIAL` or `DATE_PENDING` also block completion.
  The company-scoped query groups only open bills using the existing
  `idx_finance_bill_status` index. It does not aggregate all transaction rows.
  The production `EXPLAIN ANALYZE` sample read four open bills in approximately
  0.15 ms. Other companies, imported bills and other months do not affect the mark.
- Frontend markers require explicit server verification for both channels.
  Missing or unchecked responses cannot turn green. Hovering the marker shows
  which scan or bill check is unfinished. This describes synchronization,
  not financial close or a guarantee that every order has settled.
- Remove the execution-state dropdown. Retain only the prior-month completion
  filter and a search box matching the shop's alias, original name, and exact
  external/internal identifier. Country, platform and group names also match.
- Filtering is local to the loaded company/group rows and makes no server
  request. Background refresh retains the query/filter and user-expanded
  channel details. Search is case insensitive; long numeric IDs remain strings.
- All four shop action buttons remain in one line. The table keeps bounded
  name/sync columns and existing horizontal scrolling for narrow screens.

## Verification and release

- Eight unit checks and two isolated-MySQL checks passed, covering unfinished
  October ranges despite November success, month/timezone boundaries, year
  rollover, missing evidence, open bills, and company/month/source isolation.
  The test schema and account were removed. Original `FinanceService` source
  compiled byte-for-byte to the live class family before making the change.
- Four UI checks passed: priority/month, sync dates, shop deletion, and Qianyi
  refunds. Coverage includes server verification, pending detail overrides,
  missing flags, exact IDs, aliases, combined search/filter, counts, no extra
  requests, and refresh retention.
- Ego-browser fixture checked six channels per shop, long names, a 19-digit
  shop identifier, month filtering and search. All four action buttons had
  equal vertical positions. This was local browser acceptance; production
  health and exact asset hashes are checked separately.
- Release only `FinanceService` and the new `FinanceSyncMonth` class families
  in the application, plus `index.html`, `finance-workspace.js`,
  `finance-workspace.css` and their gzip variants. Version:
  `20261103-shop-month-2`. No SQL migration, financial recalculation, extra
  persistent log, cursor reset or rate-limit change is required.
- Frontend image: `ai-commerce-frontend:finance-shop-month-check-20261103`.
  Backend: `ai-commerce-platform:finance-shop-month-check-20261103`.
- Release directory: `/opt/heyi/releases/finance-shop-month-check-20261103`.
  Helper: `scripts/release/release-finance-shop-month-check.py`. Use the established
  drain/restore procedure for the application restart.
- Activation verified the exact plain/gzip assets, JAR hash, application health,
  API access guard, unchanged runtime configuration and disabled logging.
  Original schedules/checkpoints were restored and Qianyi reservations released.
  Running the new classifier on a fresh production snapshot found 72 complete
  and 14 unfinished shops; `YUXi .PH` and `YUXi Mall อัญชาญา` were correctly
  changed from the old false-positive result to unfinished.
- Verification:
  `python3 -B /opt/heyi/releases/finance-shop-month-check-20261103/release-finance-shop-month-check.py /opt/heyi/releases/finance-shop-month-check-20261103 verify`

Preserve the earlier month/priority-release helpers and their dependencies,
verification receipts, and all referenced Compose files. Uploaded/build/QA
staging and private drain snapshots were removed after verification: 19 entries,
239,677,440 allocated bytes. Verification passed again after cleanup.

Retained application rollbacks are `finance-sync-priority-20261103` and
`storage-bounded-20261102`. Retained frontend rollbacks are
`finance-sync-priority-ui-20261103` and `finance-sync-priority-20261103-compact`.
The frontend version with premature month completion is deliberately excluded
from rollback retention. Each service keeps its active image plus two rollbacks.
