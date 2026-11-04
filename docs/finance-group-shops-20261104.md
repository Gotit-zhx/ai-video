# Finance group/shop cascading — 2026-11-04

Changing a finance group now immediately rebuilds the shop selector from that
group's shops and clears the previous shop. Platform restrictions are applied at
the same time; all groups restores the available company shops, and ungrouped
selects only ungrouped shops. Company shop aliases remain the preferred labels.

The shared reporting toolbar and live dashboard update their shop options before
the financial query returns. Late responses cannot restore an old selection.
Monthly label invoices now use the same group/platform intersection instead of
listing every shop regardless of group. Both the cost-management invoice tab and
the expense-ledger invoice tab use this component. The backend already supports
the `UNGROUPED` filter.

Product-cost group switching removes the old shop options immediately and locks
the group/shop controls while the selected cost source loads. On a failed read,
the previous group, shop and verified data are restored together.

Eight UI suites passed: label invoices, query loading, live costs, charges,
retained filters, fee-export jobs, currency scopes and Qianyi refunds. Added
regressions cover delayed reads, rapid group/platform changes, empty groups,
ungrouped shops, aliases and invalid restored shop selections. The older
`test-finance-groups-ui.mjs` still has a timestamp-fixture assertion failure;
the same failure was independently reproduced with the unchanged pre-release
HEAD. No date or financial calculation behavior was changed for this release.

Frontend: `ai-commerce-frontend:finance-group-shops-20261104`.
Backend remains `ai-commerce-platform:finance-cost-dedupe-20261104`.
Release helper: `scripts/release-finance-group-shops.py`.
Release root: `/opt/heyi/releases/finance-group-shops-20261104`.

Only `index.html`, `finance-workspace.js`, `finance-label-costs.js` and
`finance-costs.js` are replaced over the verified active frontend image. Their
script versions and gzip assets are updated. The established image compaction
guard and Compose activation helper are used. Runtime configuration, mounted
credentials and ports are checked without printing their contents. The backend
container ID is unchanged; no worker drain, database mutation, migration or
persistent application logging is introduced.

Live and HTTP file hashes, compressed assets, preserved fee/Qianyi components,
home-page availability and anonymous finance access protection passed. Keep the
new frontend, `finance-fee-native-layout-20261104` and
`qianyi-return-total-20261104` as the active image and two verified rollback
versions. Backend retention is unchanged. Delete only this release's frontend
staging and an older unreferenced frontend image tag; preserve Compose
dependencies, helpers and the small verification receipts.

Authenticated production UI acceptance: the label invoice shop selector lists
seven shops for the perfume group and thirteen for the YUXI group. Switching
between those groups clears a selected perfume shop immediately. Choosing all
groups restores all 86 shops. The options update while the query progress
indicator is still visible. The shared overview toolbar also restricts the
perfume group to its three Shopee shops when that platform is selected.
