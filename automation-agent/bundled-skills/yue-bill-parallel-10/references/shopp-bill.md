---
name: shopp-bill
description: Start or reuse an exact-named Feikua Shopee shop and process date-ranged income, balance-detail, and official-warehouse all-orders exports through optional password verification, explicit submission, readiness monitoring, and confirmed downloads. Use when Codex needs to connect to, inspect, train, or export bills or official-warehouse order reports from Shopee or 虾皮 shops.
---

# Shopp Bill

Use `run.ps1` as the only entry point. Reuse the shared `tk-bill` Feikua/CDP kernel for exact store lookup, isolated startup, dynamic port discovery, ChromeDriver matching, and session attachment.

Connect and keep the shop open:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>"
```

Export an income report:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --export-income 2026-07-01 2026-07-31
```

Export both Shopee reports through one browser attachment:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --export-both 2026-07-01 2026-07-31
```

For a combined export, submit the income job first and immediately navigate to
Balance to submit the balance-detail job; do not wait for income generation.
Then refresh the income and balance export histories in turn every 30 seconds,
lock onto each request's newest matching row, and download it when ready.
Confirm each section as either a valid local file or a platform-confirmed
terminal no-file/no-data result before returning.
Do not detach and rediscover the Feikua session between the two workflows.

Success is `SHOPEE_INCOME_DOWNLOAD_COMPLETE` with the downloaded file path. Shopee downloads into `Desktop\feikua\<shop name>`; the monthly orchestrator may move and rename it afterward. After any export mode confirms every requested section as either a valid local file or a terminal no-file/no-data result, close the exact Feikua shop through the shared kernel and return the matching close marker. A confirmed export remains successful if the Feikua desktop service restarts before the close request; do not repeat the service-discovery timeout in that state. Preserve the session on an ordinary export failure. Close authentication failures, including explicit password rejection, an empty in-site password, or an in-site password dialog that never completes, and record the exact reason without calling an unconfirmed password wrong.

## Official-warehouse all-orders workflow

Run this third stage only when the source row is Shopee and its `备注` contains
`官方仓`. For a combined run, submit income, balance, and orders in sequence,
then rotate through all three histories every 30 seconds. Rows without
`官方仓` continue to rotate only income and balance and never submit orders.
Use `--export-both-official-warehouse START END` for a new combined run,
`--resume-both-official-warehouse START END` for a submitted combined run, or
`--export-orders START END` when billing is already terminal.

1. Open `/portal/sale/order` and wait for `button.export.export-with-modal`
   plus `.latest .eds-popover__ref button`.
2. Open the export modal structurally. Select the start and end through the
   rendered two-month calendar with genuine DOM-targeted pointer events.
   Derive each displayed month from the numeric year, day count, and weekday
   grid positions; do not read or translate the month or weekday labels.
   Require the modal's numeric value to equal
   `YYYY/MM/DD – YYYY/MM/DD` exactly before enabling submission.
3. Open `.latest .export-container` through its structural toggle and look for
   the report identity
   `Order.all.order_creation_date.YYYYMMDD_YYYYMMDD`. Accept `.xlsx`, `.xls`,
   or `.zip` after that identity. If a matching row already exists, never
   submit again.
4. Poll the exact row every 500 ms. Readiness is the presence of a visible,
   enabled button inside that row; do not compare translated button text.
   For a range spanning at least eight calendar days, after that exact row is
   confirmed as processing, refresh the order page every 30 seconds, reopen
   export history structurally, and reacquire the row. Continue without a
   fixed generation timeout. Treat refresh-replaced DOM nodes as transient and
   never resubmit the export.
5. Click every enabled download belonging to that exact date job. If one
   export produces multiple matching files, download and validate all of them.
   Re-click those same buttons only when a bounded download confirmation
   attempt fails; never resubmit the export job.
6. Validate `.xlsx` and `.zip` as non-empty ZIP containers with readable
   entries. Validate `.xls` as a stable, non-trivial report. Reuse an already
   valid local exact-range report on resume.
7. The coordinator moves the confirmed file or files beneath
   `官方仓数据\<sheet+platform>\<store>\` and replaces Shopee's original
   basename with `店铺名+Y.M.D-Y.M.D`. Additional files from the same job use
   `+part2`, `+part3`, and so on. Small reports normally retain `.xlsx`; large
   reports retain `.zip`, solely to keep the actual file type usable.

## Income workflow

1. Open `/portal/finance/income` through `a.sidebar-submenu-item-link[href="/portal/finance/income"]`.
2. If a rendered password field appears, resolve the exact task row's `备注`
   value from the source workbook and parse `导出密码` / `站内密码`. When that
   password is available, focus the field, select all, clear it, enter the
   workbook value, verify that replacement, and submit once. Never submit an
   old autofilled in-site-password value unchanged. Pass the workbook password
   only through the child process environment; never place it in task JSON,
   command arguments, logs, or output workbooks.
   After submission, inspect only rendered modal feedback. If the modal explicitly says the password is incorrect, wrong, or invalid, emit `SHOPEE_INVALID_PASSWORD`, return exit code `22`, stop the exact Feikua shop, then emit `SHOPEE_INVALID_PASSWORD_CONNECTION_STOPPED` and `SHOPEE_MANUAL_EXPORT_REQUIRED`. If the in-site password field is empty, emit `SHOPEE_MISSING_PASSWORD`, return exit code `23`, stop the exact shop, and record `站内密码为空，已退出`. If the filled dialog remains open without an explicit rejection, emit `SHOPEE_PASSWORD_VERIFICATION_NOT_COMPLETED`, return exit code `25`, close the shop, and record `站内密码验证未完成，已退出`. Do not classify a timeout, CAPTCHA, OTP, network error, or a merely persistent modal as an invalid password.
9. On the Shopee account-login page, submit Feikua-autofilled credentials once without reading them. If Shopee rejects that password, close and record `密码错误，已退出`. If the authenticated Seller Center home is still not reached, close and record `未进首页，请检查登录`.
3. For the default current-week range, keep `dateRange=THIS_WEEK`.
4. For another range, open `.date-range .date-range-btn`, choose `.picker-wrapper .opts-panel > ul.options:last-child > li`, and click dates by month panel and day cell. When replacing an existing custom range, Shopee may update one endpoint and close the calendar after one click; re-read `dateRange` from the URL, reopen the picker, and correct the remaining endpoint until the URL exactly matches the requested start and end. Date selection never submits the export.
5. After the range is selected, wait until the income page has completely loaded: the URL contains the requested range, the calendar is closed, `document.readyState` is complete, no loading indicator is visible, and the Export button remains enabled for a stable two-second window.
6. Only after that readiness check, click `button[data-click="export_excel"]`. Never treat date selection as export submission or click while the page is still loading.
7. If security verification consumes that click, complete verification,
   restore the selected range only if it was lost, wait for the page-readiness
   check again, and click Export again.
8. Leave the report popup open and poll it read-only. Do not repeatedly open/close it or take control away from the user. Continue without a fixed timeout while the requested report is still generating.
9. Match the report by the exact `YYYYMMDD_YYYYMMDD.xlsx` suffix inside the same `.list-item`; click only that row's enabled button.
10. Poll the exact shop download folder every 200 ms and inspect only XLSX files
    created or changed by this click. Confirm the size is stable across two
    polls and the ZIP-based workbook contains `xl/workbook.xml`, then report
    success immediately.

## Verified selectors

- Income route: `/portal/finance/income`
- Date trigger: `.date-range .date-range-btn`
- Custom option: `.picker-wrapper .opts-panel > ul.options:last-child > li`
- Calendar: `.eds-daterange-picker-panel.date-range-panel`
- Manual export: `button[data-click="export_excel"]`
- History toggle: `button[data-click="generate_excel"]`
- History panel: `[data-testid="remote-component-report-export-history"]`
- Report rows: `.list-item`, with filename in `.name`

## Training state

- Income navigation, optional verification, same/adjacent-month date selection, explicit export submission, report matching, download click, and local-file confirmation are implemented.
- If the authenticated home is reached but an income or balance sidebar entry
  is absent or does not finish navigation, immediately use that section's
  stable direct route. A missing menu must not turn a connected home page into
  a non-executing task.
- Balance-detail navigation, date selection, Apply, page-readiness waiting, asynchronous report polling, download click, and local-file confirmation are implemented.
- For normal exports, close the exact Feikua shop only after every requested section is either locally confirmed or confirmed no-file/no-data. Keep connect, inspect, date-picker training, and ordinary failed-export sessions open; authentication failures follow the explicit close-and-record rules above.

## Balance-detail workflow

Export and download a balance-detail report:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --export-balance 2026-07-01 2026-07-31
```

1. Open `/portal/finance/wallet/shopeepay` through its exact sidebar link.
2. Select the requested start and end through `.date-range .date-range-btn` and the custom-date option.
3. Click `.search-buttons button[type="submit"]` to apply the filters.
4. Wait until the selected range is visible, loading indicators disappear, and `button.report-exporter-button` remains enabled for two seconds.
5. Click `button.report-exporter-button` to submit the export.
   On a legacy Shopee profile only, if the custom calendar still cannot apply
   the exact range after four bounded UI attempts, submit the same ISO start
   and end dates through the authenticated official
   `export_wallet_transactions` endpoint. Continue with the identical history
   and download checks below; API submission is not download success.
6. Keep `[data-testid="remote-component-report-export-history"]` open and poll
   it read-only every 500 ms because report generation is asynchronous on
   Shopee's server. Continue without a fixed timeout while the requested report
   is still generating.
7. Match the exact filename `my_balance_transaction_report.shopee.YYYYMMDD_YYYYMMDD.xlsx`.
8. Wait until that row exposes an enabled `button[data-click="download"]`; then
   click it. If the balance-report API returns status `3` for that exact
   filename, treat it as a terminal no-file/no-data result. Do not keep
   refreshing and do not substitute a report with a different end date.
9. Poll `Desktop\feikua\<shop name>` every 200 ms, require a newly created or
   changed stable valid XLSX, and report `SHOPEE_BALANCE_DOWNLOAD_COMPLETE`
   immediately after `xl/workbook.xml` is readable.

Prepare and leave the custom calendar open:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --open-income-date-picker
```

Other session modes are `--inspect`, `--discover-port`, and `--stop`. Stop only when the user explicitly asks.

## Stability rules

- Match the exact shop name. Reuse a healthy session, or automatically start the exact idle shop before export.
- Parse `debugger_port` and `core_version` directly from a successful
  `--connect-only` result after starting an idle shop. Run `--discover-port`
  again only when either field is absent.
- Never hard-code or guess a CDP port.
- Always wait for the selected income page to finish loading, then click Export for either the default week or a custom range.
- Keep the user's browser interactive. During report generation, use read-only polling and avoid repeated popup toggling or focus changes.
- In the combined workflow, submit both server-side jobs before waiting. Refresh
  each job's history every 30 seconds without resubmitting, and keep the shop
  open until both sections have either a confirmed valid workbook or a
  confirmed terminal no-file/no-data result.
- In combined resume mode, open income export history directly. Do not require
  date-picker controls that are needed only for a new submission; a missing
  date picker must not block an already-submitted history job.
- Preserve MFA, CAPTCHA, and other challenge pages for manual completion.
  Follow the explicit close-and-record rules for wrong, empty, or unfinished
  Shopee password verification.
- Do not repeat an export after the expected report has appeared.
- Close the exact shop only after every requested section has a confirmed file
  or confirmed no-file/no-data result; never close it while a requested file is
  still pending.
- Keep TikTok behavior unchanged.
