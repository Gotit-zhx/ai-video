# Brush-cost import template — 2026-11-05

The brush-cost toolbar and import dialog offer “下载模板”. Both links download
the same blank XLSX with four existing import headers: 订单编号, 店铺,
订单原币金额 and 服务费（人民币）. The first sheet, 费用明细, contains no sample
orders or totals. A separate 填写说明 sheet explains native principal versus CNY
service fees, exact shop names/aliases, text order numbers, the 2000-record limit
and preview/confirmation.

Order-number and shop columns are formatted as text through row 2001. Amounts
retain up to six decimal places; the header is frozen. The workbook uses
unprefixed SpreadsheetML elements for compatibility with the existing parser.
Only the first worksheet is imported. Downloading the static template requires
no database access and creates no source records or order tags.

Validation:

- The existing finance-charges UI suite passed, including import controls,
  read-only permissions, group/date changes and the separate monthly-label tab.
- Both workbook sheets were rendered and visually checked.
- The actual saved workbook passed the existing Java import reader: its blank
  form contains only the four headers; a disposable filled copy preserved long
  and leading-zero order numbers, six-decimal native amounts and zero CNY fees
  at the first and last supported rows. Guide content and blank rows were ignored.
- Authenticated production UI acceptance confirmed the toolbar and import-dialog
  links and the new instructions. Served XLSX bytes matched the validated file.
  The homepage returned 200, anonymous charges API returned 401, and the backend
  container identity was unchanged after frontend activation.

Asset: `frontend/assets/templates/finance-brush-cost-template.xlsx`.
Release helper: `scripts/release-finance-brush-template.py`.
Release root: `/opt/heyi/releases/finance-brush-template-20261105`.
Frontend: `ai-commerce-frontend:finance-brush-template-20261105`.
Backend remains `ai-commerce-platform:finance-source-orders-20261105`.

The frontend-only overlay replaces index.html and finance-charges.js and adds
the static workbook. It uses the existing image compaction guard and Compose
activation. Backend container identity, runtime configuration, unrelated finance
assets, compressed/served files and access protection are verified. No backend
restart, import changes, financial writes, synchronization changes or persistent
logs are required.

After acceptance keep the active frontend and the two verified rollback versions
finance-source-orders-20261105 and finance-group-shops-20261104. Remove only the
older unreferenced finance-fee-native-layout-20261104 frontend tag and this
release's frontend staging. Preserve Compose dependencies, helpers and receipts.
This cleanup completed after acceptance; core containers remained running.
Evidence is retained in `prepared.json`, `activation.json` and
`cleanup-verification.json`.
