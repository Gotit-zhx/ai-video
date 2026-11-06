# Original brush-cost template — 2026-11-05

At the user's request, the download now uses their original 母婴刷单费.xlsx
layout, replacing the newly designed four-column template. The original
Sheet1 and all eleven headers remain in their original order:

`Source.Name`, `订单编号`, `订单金额(平台显示)`, `店铺`, `shopee账号`,
`代刷费CNY`, `飞跨店铺`, `结算月份`, `买方支付运费`, `实际订单金额`, `结算月份`.

The duplicate month headers are intentional and remain positional. Original
formatting is retained; only the order-number column is widened and formatted
as text through row 2001. All original order/account/amount data is cleared.
There are no example orders or additional instruction sheets. Download links
use the filename 母婴刷单费导入模板.xlsx with a new asset version.

The original 代刷费CNY field is the existing brush fee, stored internally as
service_fee_cny. UI labels now call it 刷单费 or 代刷费 rather than suggesting
an additional service fee. No new amount field, backend change, migration or
recalculation is introduced. Native order amounts still identify and verify
orders; the existing fee deduplication, first-settlement period and operating
report exclusion rules remain unchanged.

Validation:

- Original workbook: 25 rows, CNY 175 in brush fees, matching the existing
  import record. All eleven headers match the imported source record exactly.
- Blank download: only the eleven header values remain, including both month
  columns; no original order data, unused source strings or external source
  connections remain in the XLSX.
- A disposable filled copy passed the existing Java parser with the same
  principal, fee and shop as the original, preserving a long leading-zero
  order identifier.
- Original and blank workbook views were rendered and reviewed. The existing
  charges UI suite passed. No financial records were imported during acceptance.

Asset: `frontend/assets/templates/finance-brush-cost-template.xlsx`.
Release helper: `scripts/release/release-finance-brush-original-template.py`.
Release root: `/opt/heyi/releases/finance-brush-original-template-20261105`.
Frontend: `ai-commerce-frontend:finance-brush-original-template-20261105`.
Backend remains `ai-commerce-platform:finance-source-orders-20261105`.

The release reuses the validated frontend-only activation helper and image
compaction guard. Backend container identity, runtime configuration, unrelated
finance files, HTTP bytes and API access protection must remain unchanged.
After acceptance keep this frontend plus finance-brush-template-20261105 and
finance-source-orders-20261105; remove only the older unreferenced
finance-group-shops-20261104 frontend tag. Delete this release's frontend staging
and local temporary probes, preserving Compose files, helpers and receipts.

Production acceptance completed on 2026-11-05 at 10:49 Beijing time:

- Both the page and import dialog expose the versioned original-template
  download. The authenticated dialog displays the eleven-column instructions
  and the native-order / CNY brush-fee units.
- Served XLSX, JavaScript and index hashes match the validated files. Gzip
  assets match their sources; unrelated finance assets are preserved.
- Backend container identity and runtime configuration are unchanged. Core
  containers remain running with persistent container logging disabled.
  Homepage HTTP 200 and anonymous charges API HTTP 401 were verified.
- Frontend staging and the unreferenced finance-group-shops-20261104 frontend
  tag were removed. The active image and the two specified frontend rollbacks
  remain, along with all three backend versions and required dependencies.
  Compose files, release helpers and small receipts were preserved.

Evidence is retained in prepared.json, activation.json and
cleanup-verification.json in the release root. Authenticated UI proof is saved
locally as `finance-brush-original-template-20261105/原表模板下载验收.png`.
