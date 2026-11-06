# Finance scope switching, Qianyi source views and performance — 2026-10-30

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## Behavior

- A reporting request captures its filters, page, record kind and search numbers. A newer load aborts the previous browser request; generation guards also reject responses from transports that ignore cancellation.
- Overview data, resolved currency/FX and detail rows are published together. During a scope change the previous results are dimmed and explicitly labelled as previous results. Their drilldowns and exports cannot accidentally use the newly selected shop.
- Existing incremental chart rendering remains; unchanged data does not trigger replacement of the whole view.
- Wasup Clothing.MY and Wasup Clothing.TH were observed with `ORDER_REPAIR` checkpoints containing zero for `from`, `to` and `until`. This is an order-ID repair cursor, not a date scan. API progress now exposes `ORDER_ID` and null date fields. The UI says “按结算账单补齐缺失订单，不按下单日期回扫”; real historical date windows remain visible. Checkpoints and order dates are unchanged.

## Query/cache changes

- MySQL InnoDB buffer pool resized online from 8 GiB to 12 GiB (`12884901888` bytes) with `SET PERSIST`. The prior runtime/persisted values are retained in a rollback receipt. Durability settings were checked and preserved.
- V140 replaces the legacy owner-prefixed dashboard index, which no deployed application class references, with a covering `(organization_id, shop_id, record_date, ...)` index. The company-wide date index remains for all-shop reports.
- A separate `(organization_id, kind, status, source, shop_id)` index avoids scanning every bill to display pending settlement dates.
- The bounded raw dashboard cache retains the existing 32-entry limit, with a 15-second TTL to reuse reads when changing display currency. Existing committed-write invalidation remains.
- The shared snapshot namespace advances to `v13-qianyi-source` so old return-cost definitions are never served after the release. Date/shop/group/platform/native currency/display currency/FX/live-screen keys remain distinct.

## Qianyi and refund synchronization

- Finance shop management exposes company-wide Qianyi credentials and synchronization controls. Credentials remain encrypted and write-only; one configuration discovers exact platform shop IDs and later-added shops. Ambiguous identities and removed shops cannot be rebound silently.
- Qianyi-native return lists paginate in SQL and support independent return/refund status filters, order/return numbers, and created/updated/receipt date bases. Details include sanitized source fields, SKUs and inventory evidence without buyer/contact information.
- Source version 2 performs a resumable history pass to retain upstream refund statuses that older source snapshots omitted.
- Return inventory credits now come only from confirmed Qianyi evidence. Historical spreadsheet receipts remain auditable but do not contribute to current return credits. Platform cash refunds still follow platform settlement.
- **Unresolved source semantics:** the upstream inventory `cost` field's currency and unit-vs-movement-total basis have not been independently confirmed for the real accounts. No connection is falsely marked verified. Unknown amounts are null/pending; they do not become zero or a falsely complete profit.
- The observed refund rate-limit error was TikTok HTTP 429 / code 36009002. A durable application-wide gate spaces refund requests across shops, backs off on rate limits, and retains range/cursor progress. Completed incremental runs use their request boundary with a five-minute overlap, then wait approximately 15 minutes plus shop jitter. Order channels remain independent.

## Validation

- 43 focused Java unit tests passed across Qianyi evidence/client, money calculations, exports and shared cache isolation.
- 39 real MySQL integration tests passed in disposable schemas, including global credential isolation, later shop discovery, removed-shop protection, native return filters/pagination, historical replay, durable rate limits, cursor retention, cost authority, dashboard equivalence and zero-date progress.
- Five UI regressions passed: Qianyi dialogs, default RMB scope, rapid scope/currency changes, operating-cost currency/drilldowns and sync progress dates.
- A real browser test with explicitly labelled example data verified loading state, blocked stale drilldowns, rapid MY→PH→MY switching and rejection of late PH responses.
- Release overlays are compiled against exact live classes and preserve unrelated classes, dependencies, frontend assets and container configuration. Temporary QA schemas/accounts are removed after each run; diagnostics use no persistent container log driver.

## Production result

- Schema 140 completed on 2026-10-30 at 10:26 UTC. Both application and frontend now run the `finance-qianyi-company-20261030` images. Activation receipt verifies unchanged runtime configuration and unrelated containers, exact backend/frontend hashes, identity/gzip assets, health and unauthenticated endpoint guards.
- The initial container activation stopped before replacing either live container because five previously cleaned image-only Compose files were still referenced by the running containers. Those files were restored from their activation receipts, and the complete Compose environment was compared with the live environment without printing secrets. The release now preflights Compose before migration and can resume after an already successful schema 140 migration.
- The production comparison used a SELECT-only temporary account and one read snapshot per pair. Both runs returned identical financial buckets:

| Query | First old/new | Activation recheck old/new |
| --- | --- | --- |
| September, one shop | 583 / 168 ms | 588 / 103 ms |
| Partial September–October, one shop | 391 / 98 ms | 362 / 60 ms |
| September, all shops | 2423 / 2395 ms | 2189 / 2260 ms |

These are database aggregate timings, not complete page-load timings. The all-shop path is unchanged and showed no material improvement; the dedicated index improves the selected-shop path.

- A final read-only probe invoked `FinanceService.syncStates()` from the **deployed JAR**, without candidate classes. All 50 repair channels returned `ORDER_ID` and absent date fields; 25 real time ranges were retained. Both Wasup Clothing.MY and Wasup Clothing.TH were verified in `SYNCING` with no exposed zero-date placeholders.
- MySQL runtime and persisted buffer pools both equal 12 GiB. Durability remains `1/1`, no lock waits or SQL-pattern sync errors were observed. Refund channels were all `SUCCESS` at the check. Qianyi global discovery was `SUCCESS`; 42 connections were replaying history and five were complete, with no falsely verified cost basis.
- The earlier real-browser fixture checks passed. The saved Ego task space no longer existed at final production verification, so no authenticated production-browser result is claimed. Production verification instead covered live service execution, exact deployed assets and HTTP guards. No replacement task space was created or user tabs closed.

## Cleanup

- Removed 42 redundant generated JAR copies from older release directories, freeing 3,070 MiB after checking that no container mounted those paths. Database backups, business files, all runtime Compose files, release helper scripts and tagged rollback images were retained.
- Disk headroom recovered from approximately 1.8 GiB to 4.7 GiB before final package cleanup. The current release's `cleanup-receipt.json` records exact removed paths and bytes.
- After successful live verification, another 165 MiB of current-release generated packages/classes and private operation snapshots were removed. Final health, asset hashes and runtime Compose dependencies still passed; disk headroom was 4.4 GiB with normal background synchronization running.
- Temporary QA schemas, read-only probe accounts and private probe environment files were removed. The local example-data server was stopped after its browser task space was found to be absent.
