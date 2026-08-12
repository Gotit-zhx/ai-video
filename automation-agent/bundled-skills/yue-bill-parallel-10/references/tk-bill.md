---
name: tk-bill
description: Start an exact-named Feikua isolated TikTok Shop, discover its per-session CDP port and live store URL, and export a date-ranged income report through Selenium across regional Seller Center sites. Use when the user asks to connect to, open, inspect, extract from, or automate a Feikua Browser shop.
---

# TK Bill

Use `run.ps1` as the only entry point. It checks for Python 3.10+, creates a private `.venv` inside this skill, installs missing dependencies, and then runs the exporter. Existing components are reused.

This skill is the regional entry point and shared execution kernel. Keep its default `regional` Seller Center profile. The independent `tk-bill-cn` skill invokes this same runner with `TK_BILL_SELLER_CENTER_PROFILE=cross-border`, so Feikua connection, CDP attachment, verification handling, timeout recovery, export submission, and download confirmation remain single-source.

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --start-date YYYY-MM-DD --end-date YYYY-MM-DD
```

To append regional TikTok advertising costs after the unchanged income stage,
provide a separate destination:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  "<exact shop name>" `
  --start-date YYYY-MM-DD `
  --end-date YYYY-MM-DD `
  --out "<收入板块\店铺>" `
  --ad-cost-out "<广告收入板块\店铺>"
```

`--ad-cost-out` is regional-only monthly work. It cannot be combined with
inspection, connection-only, stop, or existing-report download modes.

When the monthly task state already has a valid terminal income result but its
advertising section is incomplete, append `--ad-costs-only`. This reconnects
the same exact Feikua shop, skips Finance/income entirely, and resumes only
non-terminal advertising accounts:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  "<exact shop name>" `
  --start-date YYYY-MM-DD `
  --end-date YYYY-MM-DD `
  --ad-cost-out "<广告收入板块\店铺>" `
  --ad-costs-only
```

## Codex execution permission

When Codex runs `run.ps1`, invoke it with `sandbox_permissions: "require_escalated"` from the first attempt and use a narrow reusable prefix rule for the `run.ps1` path. The launcher manages its private environment in `%LOCALAPPDATA%\tk-bill`, which includes creating or, only when unhealthy, rebuilding `.venv`. This avoids a sandbox-denied environment recovery before the shop session starts.

`run.ps1` reuses a healthy virtual environment. It rebuilds it only when the interpreter or required `selenium` and `websocket` imports are unavailable. After a report row appears, it polls the Seller Center and local download without a fixed export timeout.
It also configures UTF-8 console output so regional site text, including Thai, can be inspected and logged without Windows code-page errors.

To inspect a running shop's current CDP port without opening, restarting, or changing the browser session:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --discover-port
```

Platform-specific skills may reuse the connection kernel with `--connect-only`. This starts or reuses the exact Feikua shop, opens its validated platform URL, emits `STORE_SESSION_READY`, and preserves the browser session without submitting a TikTok export.

To inspect the current page and visible status without navigation, export submission, or downloads:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --inspect
```

To download one exact report already shown in Export History without submitting another export:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --download-report "income_YYYYMMDDHHMMSS(UTC+7).xlsx"
```

If Python is missing and Windows `winget` is available, `run.ps1` installs Python for the current user automatically. The target computer must have the Feikua client installed and signed in. Internet access is required only when Python or Python packages are missing.

The script discovers Feikua's local service, starts the shop only when it is idle, records the shop's CDP port in `%LOCALAPPDATA%\tk-bill\sessions.json`, and reuses that exact session after a timeout or interruption. It never guesses between multiple running TikTok windows. Downloaded reports go to `%LOCALAPPDATA%\tk-bill\downloads` unless `--out` is supplied.

For an already-running shop, the launcher always resolves the current CDP port before attaching. It validates the port through Chrome's `/json/version` endpoint and, when the Feikua detection extension is the only visible page, creates a normal blank tab before ChromeDriver attaches. This avoids the extension-only attachment failure and makes the behavior repeatable across separate Codex tasks.

The start and end dates are mandatory before any shop session starts. They must use `YYYY-MM-DD`, end on or after the start date, and cover no more than 90 days. If either is missing or invalid, the script refuses to launch and prints the required command format. The validated date arguments are used to select and confirm the export range in the TikTok calendar, then submit the resulting export request.

## Script execution and recovery

Treat one invocation as an asynchronous browser-launch operation. It can finish the browser redirect before the calling terminal receives its final `CONNECTED` output. For exports, keep the terminal invocation open until the requested report is locally confirmed or an explicit platform/session terminal condition occurs. Do not apply a terminal timeout while the platform still shows the report as generating.

## RPA stability rules

Use the launch operation as a deterministic, single-pass RPA action:

1. Start an exact shop at most once per user request. If the shop is already running, reuse its registered CDP session instead of starting it again.
2. After `CONNECTED`, consider the browser opened and stop. Do not navigate, refresh, retry, inspect, scrape, or perform any other Selenium action unless the user explicitly asks.
3. For a bill export, submit the Feikua-autofilled email login once when available. If the authenticated Seller Center home is still not reached, close the exact store immediately and emit `TIKTOK_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED`.
4. Before any later launch request, inspect the shop status. If it is already running, reuse the existing session. If its saved CDP port is unavailable and multiple TikTok CDP sessions exist, stop and ask for an explicit restart or port rather than guessing.

1. Run the command once with unbuffered output through `run.ps1`; it creates/reuses the private environment and uses the saved session registry.
2. While it is running, wait for its output; do not start a second invocation for the same shop.
3. Keep the original invocation open while the requested report row is generating or downloading. Do **not** issue a second invocation for the same request.
4. Do not close the shop because a fixed amount of export time elapsed. Continue read-only polling until the file is confirmed or the platform/session reaches an explicit terminal condition.
5. If the terminal is interrupted externally, inspect the shop status before any recovery action. A status of `running` means the original invocation may still be active.
6. Never reuse an old session's CDP port. Stop only when the user explicitly requests it, then create a new session if they ask to reopen the shop.

Only stop a shop when the user explicitly asks:

```powershell
python <skill-folder>\scripts\feikua_store.py "<exact shop name>" --stop
```

## Login and verification terminal rule

For a bill export, try the Feikua-autofilled email login once when its rendered
form is available. Do not close or automate a login-page CAPTCHA. If login,
verification, CAPTCHA, challenge, or any non-Seller-Center page remains, stop
the exact Feikua shop immediately, report
`TIKTOK_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED`, and write
`未进首页，请检查登录` to that shop's Excel status cell.

## TikTok Seller Center follow-up

When the resulting page is a TikTok Shop regional Seller Center page matching `seller-xx.tiktok.com` (including `seller-ph.tiktok.com` and `seller-my.tiktok.com`) and no login or verification is pending, the launcher navigates directly to the fixed, language-independent `/finance/withdraw-new` route rather than waiting for the Finance side-menu animation. It then uses the export action's `data-tid="finance.action.modules.export_history"`, the date-picker structure, and the exact report row containing the new `income_*.xlsx` name as readiness gates. It does not decide readiness from translated words such as Export, Download, or Exported. It clicks the exact row's only enabled action once, verifies that the output file is created or updated, and stops. This supports Chinese, English, Thai, Filipino, Vietnamese, Malay, and other Seller Center languages. For non-Seller-Center platforms, it stops after opening the platform page.

On regional withdrawals pages, inspect only rendered `income_*.xlsx` report nodes rather than reading the entire page text before opening the export dialog. This lets the Export button and date panel proceed as soon as their specific elements are ready, while retaining the pre-submission report snapshot used to prevent downloading an old report.

## Appended advertising Costs section

Do not change or replace the regional income workflow. Only after the income
section has a confirmed workbook or an explicit period no-data result, reuse
the same exact Feikua browser and append this sequence:

1. From the authenticated Seller Center, open `Marketing`, select the
   `Shop ads` item identified by `data-tid="ads_creation"`, and follow the
   official TikTok Ads Manager link.
2. If the official link lands on an Ads Manager login page, submit the
   Feikua-autofilled identity and password without reading or logging either
   value. Allow at most two bounded submissions across the initial entry and a
   later session-expiry recovery. Close only a closable slider or same-shape
   object challenge through its explicit X, then resume the same advertising
   checkpoint. Recognize the English
   `Select 2 objects that are the same shape` variant as well as Chinese. If
   autofilled login and Seller Center SSO re-entry both fail, preserve the
   exact Feikua session for verification.
3. Open `Billing` > `Transactions` and select `Costs`. Hard-gate the tab by the
   active Costs panel and its Costs date picker; do not infer it from the
   translated page text or from the `Transactions` tab. If Billing,
   Transactions, or their destination panels have not rendered yet, poll
   slowly and use the current account ID's direct finance route as bounded
   recovery while the CDP target remains alive. Preserve the exact session if
   that bounded recovery still cannot render the destination.
4. Discover every selectable advertising account by its account ID. Group
   labels are not accounts.
5. For each account, switch by exact ID, reselect both task dates, click
   `Confirm`, and verify the date-picker value is exactly
   `[startDate, endDate]`.
6. Wait for a rendered cost table or the exact `No data` state. A blank,
   loading, hidden, or timed-out panel is not no-data. If the authenticated
   TikTok shell and left navigation are present while the Shop ads main content
   area is completely blank, slowly click the exact `Shop ads` menu item
   without a click-count limit while the exact CDP target remains alive. Check
   the right-side content for 30 seconds after every click, polling both the
   official entry and the main content every 500 ms. As soon as it starts
   rendering text, cards, controls, media, a table, iframe, loading skeleton,
   or spinner, stop clicking and only wait for the official Ads Manager entry,
   emitting progress heartbeats. Treat a localhost ChromeDriver read timeout from this
   idempotent menu click as continued loading; never apply this rule to an
   Export click. Do not refresh, fail, or close that shop while the browser
   target remains alive.
   On recovery, scan existing tabs first. If the exact Seller Center already
   has an `/ads-creation` page, reuse it. When that page has begun rendering,
   do not navigate Home, click `Shop ads`, or refresh; continue waiting from
   the current rendered page.
   If a recovery checkpoint already names a non-terminal audited advertising
   account and the rendered welcome shell still has no official entry after
   three minutes, navigate directly to that stable account ID. Never use this
   fallback for first-time discovery.
7. When data exists, click that account's `Export` button exactly once. Wait
   for the files created by that click, validate them, and preserve all of
   them. TikTok may create both a `Cost` workbook and a `CostTax` workbook from
   one click; this is expected and must not trigger a second click.
8. Continue through all accounts. Save results only under
   `广告收入板块\<sheet>\<shop>` and keep original income workbooks under
   `收入板块`.

Use the canonical primary filename
`店铺名（含站点）+广告户名字+YYYY.M.D-YYYY.M.D.xlsx`. For example:
`杀虫-TH-TikTok+Q1-杀虫剂-帮炜-泰国+2026.7.1-2026.7.31.xlsx`.
When one Export click also returns `CostTax`, retain it as the same base name
plus `+CostTax`; do not click Export again.

The appended stage emits per-account JSON terminal markers and a final phase
marker. `completed` and explicit `no_data` are terminal; any failed account
keeps the advertising section incomplete. The monthly coordinator must retain
the complete `filePaths` list because one account can return multiple files.
The executor also writes a task/date-scoped checkpoint in the shop's
advertising output directory. A resumed run validates existing files and
emits `TIKTOK_AD_COST_ACCOUNT_RESUMED_JSON` before skipping a terminal account;
it never treats a missing or invalid workbook as resumable.
Immediately before the browser Export click, it records
`TIKTOK_AD_COST_EXPORT_INTENT_SAVED_JSON` with the stable account ID and a
deterministic staging directory. If the process is interrupted after that
point, `--ad-costs-only` recovers or waits for that staging download and does
not issue another Export click. Account identity for this guard is the stable
account ID; an advertising-account display-name change never authorizes a
second click.

After authenticated Seller Center has been reached, if a slider or same-shape
object CAPTCHA appears during this appended section, click only its explicit
X/Close control, then revalidate the current page and resume the same
checkpoint. Do not drag, solve, or click the pictured objects. If the Ads
login server immediately returns the same verification decision after that
close, record terminal `广告登录需要验证码`, close the exact shop, and retain the
income result. For another challenge that is not closable,
invoke the original `wait_for_human_takeover` flow for the same Feikua window.
If the Ads login explicitly rejects the account or password, record terminal
`TIKTOK_AD_LOGIN_CREDENTIALS_REJECTED`, write the matching split
income/advertising failure text to the monthly workbook, close that exact shop,
and do not retry or preserve it.
An advertising-stage failure must not resubmit or enter the recovery loop for
an income export that already completed.

## Regional execution

The script treats every two-letter TikTok Shop regional host matching `seller-xx.tiktok.com` as an executable Seller Center, rather than limiting automation to one market. This includes Philippines (`seller-ph.tiktok.com`), Malaysia (`seller-my.tiktok.com`), Thailand (`seller-th.tiktok.com`), and future regional sites with the same host format. It reuses the current shop's exact CDP session, opens `/finance/withdraw-new`, submits the validated date range, and waits for that request's newly created `income_*.xlsx` report. A login or verification page that remains after the single autofilled login attempt is an immediate close-and-record terminal condition.

Before opening Finance, the launcher requires the authenticated Seller Center home and closes the exact shop when login, CAPTCHA, verification, security, or password gating remains. For other visible notification or marketing overlays, it first clicks an explicit language-independent ×/Close control. If no × exists, it clicks only a known dismiss acknowledgement (such as Close, Cancel, Later, Got it, 知道了, or เข้าใจแล้ว) after the modal is identified as promotional. It does not click action buttons such as Check now or Create campaign.

After export submission, the launcher explicitly clicks **Export History** and
first confirms that its panel really opened. It then compares the rendered
history with the pre-submission snapshot. Only a confirmed-open history panel
with no new job for this submission is a no-data result. If the new
`income_*.xlsx` job exists, wait without a fixed download deadline, click
Download once on that exact row, and confirm the local workbook. Do not close
the shop while that report remains pending. After connection, perform only the
Selenium actions the user gives. Do not navigate or scrape speculatively.
If the clicked row and the single new downloaded `income_*.xlsx` differ only
by an encoded timestamp within 120 seconds, accept that unique valid workbook;
never choose between multiple candidates.

Report names may contain regional offsets such as `UTC+8`, Thailand's `UTC+7`, `GMT+07:00`, or other whole-hour and minute offsets. The launcher parses those offsets and compares report generation times in UTC. If an unfamiliar time-zone label appears on a report that was not present in the pre-submission snapshot, that new-report boundary is authoritative and the launcher proceeds to its exact Download action rather than waiting on time-zone parsing.

## Confirmed download requirement

After `TIKTOK_EXPORT_SUBMITTED`, the launcher must keep the shop session open and wait for `TIKTOK_EXPORT_FILE_CONFIRMED`. Confirm immediately after this invocation's new XLSX has no matching `.crdownload`, its size is unchanged across two 200 ms polls, and its ZIP structure contains `xl/workbook.xml`; do not wait for a delayed page callback after the file is already confirmed. It may close the shop normally only after that confirmation. If a Selenium or connection error occurs after submission, it preserves any still-live shop session and reports `TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED`; it must not close or retry the export automatically.
