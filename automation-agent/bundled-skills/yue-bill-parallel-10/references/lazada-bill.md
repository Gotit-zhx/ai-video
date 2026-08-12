---
name: lazada-bill
description: Start or reuse an exact-named Feikua Lazada shop, skip registration pages to the existing-account login, submit Feikua-autofilled credentials without exposing them, and keep a stable Seller Center connection for training or date-ranged income and balance-detail bill exports. Use when Codex needs to connect to, inspect, train, or export bills from Lazada shops.
---

# Lazada Bill

Use `run.ps1` as the only entry point. Reuse the Feikua/CDP connection kernel from `tk-bill`; never hard-code a debugger port.

## Connection and login

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  "<exact Feikua shop name>" --connect-only
```

1. Match the exact Feikua shop name.
2. Reuse its registered live CDP session or start the isolated shop once.
3. Use Feikua's validated store URL and attach Selenium to the assigned port.
   If startup briefly exposes a non-Lazada URL, wait up to 10 seconds for the
   Lazada login or Seller Center URL before classifying the page.
4. If Lazada Seller Center is already open, skip all login actions.
5. If a Lazada registration page is open, do not fill or submit it. Immediately
   click the rendered `a.to-login-link` / `/apps/seller/login` element with a
   real CDP mouse event.
6. On the existing-account login page, wait for Feikua to fill `#account` and
   `#password`, then real-click that form's submit button once.
7. Never print, persist, or copy credentials through the operating-system clipboard.
8. After the single login submission, monitor the visible login-form error
   container. If Lazada explicitly reports an incorrect account or password,
   immediately stop that exact Feikua store, return exit code `20`, and emit
   `LAZADA_INVALID_CREDENTIALS_CONNECTION_STOPPED` plus
   `LAZADA_MANUAL_EXPORT_REQUIRED`. Never retry those credentials, and record
   `账号或密码错误，已退出` in the Excel status cell.
9. After the login submission, if Seller Center home is not reached for any
   reason, including OTP, SMS, CAPTCHA, or another secondary verification, stop
   that exact Feikua store, return exit code `25`, and emit
   `LAZADA_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED`. Record
   `未进首页，请检查登录` and do not retry it.
10. After Seller Center loads and after later page navigation, close every visible
   closable advertising, campaign, announcement, and onboarding popup. Resolve
   the popup container first, exclude containers with password, OTP, CAPTCHA,
   login, or security-verification controls, then real-click its explicit close,
   dismiss, skip, later, checkout, check-it-out, OK, continue, acknowledgement,
   or X control. If a non-security, non-export-history popup has exactly one
   enabled action, click that action even when its label is unfamiliar. Confirm
   the popup disappears and repeat until no safe popup remains.
11. In training mode, keep the confirmed Seller Center session open for the user's next instruction.
12. If finance navigation is redirected to account health and Lazada explicitly
    says the account/store is deactivated, disabled, or offline, close the
    exact shop and record `店铺已停用，已退出`. Do not leave it in the normal
    preparation-retry loop.

Use DOM structure, input attributes, enabled state, and Lazada host/path signals as the primary selectors. Visible language text is fallback evidence only.
If the customized Feikua Chrome core rejects ChromeDriver, or resolving an old
ChromeDriver fails because its download service is unavailable, immediately
switch to the no-Origin native CDP path and use `Input.dispatchMouseEvent`; do
not fail the live store session and do not fall back to JavaScript `.click()`
for login submissions.

## Income bill export

Income bills use the **transferred/released** tab only. Do not export the default
ready-to-transfer tab.

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  "<exact Feikua shop name>" --export-income `
  --start-date YYYY-MM-DD --end-date YYYY-MM-DD `
  --out-dir "<output folder>"
```

1. Wait up to 3 seconds for and then enter **My Income** by real-clicking the
   fixed sidebar element `#root_normal_rp_asc_v2_finance_rp_asc_v2_accountstatementnew`.
   If only the homepage shell is still rendered, navigate to the stable relative
   finance path `/portal/apps/finance/myIncome/index` and continue.
2. Real-click the tab identified by
   `[role=tab][data-spm=d_income_overview_released]`. This is the transferred /
   released tab regardless of interface language.
3. Wait until `#releasedCreateData` has two visible, enabled inputs and the page
   loading indicators are gone. Real-click its first input, wait for the range
   picker to expose two header inputs, replace them through trusted CDP keyboard
   input, reject future end dates, and real-click the footer confirmation button.
   Retry only the real click if the picker has not opened; do not write directly
   into React fields.
4. Verify the released panel shows the exact requested dates before exporting.
   If navigation, the active panel, a checkout popup, or the date control is
   temporarily unavailable before submission, close only safe popups and retry
   the same preparation step every 30 seconds in the same slot. Do not close
   the shop and do not submit until the requested dates are verified.
5. Open the active panel's `.download button.next-menu-btn` and real-click the
   first visible `.next-menu-item`, whose stable structural position is Order
   details (Excel). Never choose the ready-to-transfer panel's download menu.
6. The export-history dialog opens automatically. Immediately capture the new
   first-row task ID, then poll that exact row until it has a `-RELEASED-`
   `.xlsx` filename, a Finished status, and a result link. Do not close this
   dialog while polling, do not select an older row, and do not apply a fixed
   timeout while the task remains in progress.
7. Configure both browser-level and legacy page-level download behavior, then
   real-click the newest row's result link once.
8. If an old customized Feikua core aborts the browser file write, reuse that
   same signed result URL with cookies from the live isolated browser session.
   Strip only the analytics `spm` query parameter; never print cookies or the
   signed URL. Once the history row exposes a ready file, retry transient signed
   download network errors in the same slot every 30 seconds. Never resubmit the
   export, close the shop, or impose a terminal timeout before that ready file is
   locally confirmed.
9. Snapshot the income output directory before clicking, then poll only XLSX files
   created or changed by this click. Ignore stale or unrelated `.crdownload`
   files. As soon as the candidate size is stable across two 200 ms polls and the
   ZIP-based `.xlsx` contains `xl/workbook.xml`, report completion immediately;
   never add a fixed post-download wait. If the old Feikua core shows no file or
   partial-file activity within 1.5 seconds, immediately complete the download
   through the same authenticated signed result instead of waiting for the full
   browser-download timeout.
10. Real-click the visible export-history dialog's `a.next-dialog-close` X and
    verify the dialog has disappeared. This is mandatory before navigating to
    **My Balance**; never click the balance sidebar item through the modal.

The automation never disables pointer input, so the user can continue interacting
with the browser during waits.

## Balance-detail export

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  "<exact Feikua shop name>" --export-balance `
  --start-date YYYY-MM-DD --end-date YYYY-MM-DD `
  --out-dir "<output folder>"
```

1. Wait up to 3 seconds for and then enter **My Balance** by real-clicking the
   fixed sidebar element `#root_normal_rp_asc_v2_finance_rp_asc_v2_mybalance`.
   If only the homepage shell is still rendered, navigate to `/apps/balance`
   and continue.
2. Wait until `#transactionDateRange` has two visible, enabled inputs and the page
   loading indicators are gone. Real-click its first input, wait for the two
   picker header inputs, replace them through trusted CDP keyboard input, reject
   future end dates, and real-click the footer confirmation button. Retry only
   the real click when the picker has not opened.
3. Verify `#transactionDateRange` shows the exact requested inclusive dates.
   If balance navigation, a checkout popup, or this control is temporarily
   unavailable before submission, close only safe popups and retry the same
   preparation step every 30 seconds in the same slot. Do not close the shop.
4. Open `.search-export button.next-menu-btn` and real-click the first visible
   `.next-menu-item`. Its stable structural position is Transaction details
   (Excel); the second item is CSV and must not be selected.
5. The shared export-history dialog opens automatically. Capture its new
   first-row task ID immediately, then poll that exact task until its status is
   Finished and its result link exists. Do not apply a fixed timeout while the
   task remains in progress.
6. The balance history omits a server filename. Save the result as
   `balance_detail_<start>_<end>.xlsx`.
7. Configure both download APIs, real-click the requested row's result link,
   apply the authenticated signed-link fallback on old Feikua cores when needed,
   and verify the final ZIP-based XLSX contains `xl/workbook.xml`. Track only
   files created or changed by this click, ignore stale `.crdownload` files, and
   confirm a stable valid workbook immediately. If no current-download activity
   appears within 1.5 seconds, complete the same authenticated signed result
   directly instead of waiting for the full browser-download timeout.

## Combined export

Use one process and the same verified CDP session when both bills are required.
This closes the income history dialog, then opens My Balance directly without a
second Feikua/driver connection cycle.

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  "<exact Feikua shop name>" --export-both `
  --start-date YYYY-MM-DD --end-date YYYY-MM-DD `
  --income-out-dir "<income temp folder>" `
  --balance-out-dir "<balance temp folder>"
```

Emit `LAZADA_BOTH_DOWNLOADS_COMPLETE` only after both XLSX files are confirmed.

## Inspect and stop

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  "<exact Feikua shop name>" --inspect

powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  "<exact Feikua shop name>" --stop
```

`--inspect` is read-only and prints the current URL, title, and a bounded visible-text sample without credentials. Stop a training session only when the user asks or after a confirmed completed export.

## Training status

The connection/login, released income export, and balance-detail export are
implemented and live-tested. The skill now exposes both Lazada bill exports for
monthly orchestration.
