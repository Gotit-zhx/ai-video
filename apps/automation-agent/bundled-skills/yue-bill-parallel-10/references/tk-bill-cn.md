---
name: tk-bill-cn
description: Train and run the yue-bill-parallel-10 internal TikTok Shop cross-border bill executor for exact-named Feikua shops while reusing the tk-bill browser, CDP, verification, timeout, export, and download-confirmation kernel.
---

# TK Bill CN

Use the monthly skill's `run.ps1` as the only production entry point. Its
bundled `executors/tk-bill-cn` adapter is a thin cross-border configuration
layer over the bundled shared `tk-bill` execution kernel.

This reference covers only the executor bundled inside
`yue-bill-parallel-10`. It does not document or claim advertising-cost support
for the standalone `~/.codex/skills/tk-bill-cn` skill.

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --start-date YYYY-MM-DD --end-date YYYY-MM-DD
```

## Shared-kernel contract

- Reuse `executors/tk-bill/run.ps1` inside `yue-bill`; do not duplicate
  `feikua_store.py`, browser drivers, dependency installation, or verification
  logic inside the cross-border executor.
- Set `TK_BILL_SELLER_CENTER_PROFILE=cross-border` before invoking the shared runner.
- Accept only `seller.tiktokshopglobalselling.com` as an executable Seller Center in this profile.
- Keep regional `seller-xx.tiktok.com` shops assigned to `tk-bill`.
- Reuse the shared verification rules exactly: never close a login-page
  challenge. After authenticated Seller Center has been reached, close a
  visible slider CAPTCHA through only its explicit X/Close control, then
  revalidate and continue from the same checkpoint. Preserve other CAPTCHA,
  security, and challenge pages for the shared human-takeover flow.
- Reuse the shared single-pass and confirmed-download rules. Never retry an export automatically.

## Training workflow

Start with non-mutating inspection while learning the cross-border page:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 "<exact shop name>" --inspect
```

During training, record only stable page differences needed for deterministic execution. Put cross-border-specific navigation and selectors behind the `cross-border` profile in the shared kernel. Do not weaken regional behavior or duplicate common logic.

Use `--inspect-finance` only during training to click the stable cross-border Financial summary link and print its visible controls without submitting an export.
Use `--inspect-unsettled` only during training to inspect the cross-border Statements destination without submitting an export. The production route must click the language-independent third finance tab element (`role="tab"` with `aria-controls` ending in `-panel-2`). Do not click `More`, `See all`, or the fourth `-panel-3` On-hold tab. Statements exports are named `income_<generation-time>(UTC offset).xlsx`; identify a new report from the pre-submission history snapshot and its generation time because the requested date range is not embedded in this filename.

Run a live export only after the user supplies an exact shop name and date range. Treat a nonempty downloaded workbook plus `TIKTOK_EXPORT_FILE_CONFIRMED` and no `.crdownload` as the only completed result.

For a live cross-border export:

- Open the language-independent fourth finance tab and verify that it is selected.
- Submit the requested date range once.
- After submission, explicitly open Export history using `data-tid="finance.action.modules.export_history"`.
- After Export history renders, allow five seconds for the exact requested
  report row to appear. If it does not, return `no_data`, keep the output
  folder, write `无数据` to Excel, and close the store without resubmitting.
- Match only the report whose filename contains the exact requested start and end dates. Do not download a different historical report.
- Monitor that target row until its unique Download control is enabled, then download it once.
- Confirm the new local `.xlsx` is nonempty and no `.crdownload` remains.

## Monthly advertising-cost phase

For a `tiktok-global` monthly task, preserve the existing cross-border income
flow exactly. Once that income flow reaches a terminal downloaded-file or
platform-confirmed no-data result, append the advertising-cost phase in the
same exact Feikua window and CDP session. Do not reopen the store or switch to
an ordinary browser.

- The monthly coordinator passes
  `--ad-cost-out "<task output>\广告收入板块"` to the bundled
  `executors/tk-bill-cn` runner. Advertising workbooks must stay in that
  directory and must never be written to `收入板块`.
- When the income section was already terminal before a retry, invoke
  `--ad-costs-only`. This resumes the advertising phase without repeating the
  income export.
- From the authenticated cross-border Seller Center, use
  `Marketing` -> `Shop ads` -> the official TikTok Ads Manager link. In Ads
  Manager open `Billing` -> `Transactions` -> `Costs`.
- Localized shells must use stable DOM and state first: Seller Center
  `data-tid="marketing"` / `data-tid="ads_creation"`, the rendered official
  Ads Manager href, Billing routes, finance `role="menuitem"`, Costs slot 2,
  date-picker values, and export `data-testid`. Chinese or English labels are
  fallback evidence only. A multi-site shop can pass through
  `/ads-creation/welcome` before the dashboard, so wait for the official link
  instead of requiring one localized label or one fixed dashboard route.
- Enumerate every selectable advertising account. For each account, select
  the exact task start and end dates, click `Confirm`, and verify that the
  rendered range equals the requested range before export.
- Click `Export` exactly once per advertising account. A single click can
  produce both a `Cost` and a `CostTax` workbook; collect and validate all
  files produced by that one click without clicking again.
- Treat only a visible Ads Manager `No data` state as that account's terminal
  no-data result. Missing controls, stale dates, timeouts, or an empty staging
  directory are not equivalent to `No data`.
- Name the primary workbook
  `店铺名（含站点）+广告户名字+YYYY.M.D-YYYY.M.D.xlsx`, for example
  `杀虫-TH-TikTok+Q1-杀虫剂-帮炜-泰国+2026.7.1-2026.7.31.xlsx`.
  If the same export also yields a tax workbook, append `+CostTax` only to
  that secondary filename.
- Persist a per-task advertising checkpoint keyed by the exact advertising
  account ID. Record the deterministic staging directory before the Export
  click, and mark an account terminal only after a valid file or explicit
  `No data`. On resume, validate and skip terminal accounts and recover a
  recorded in-flight staging result without clicking Export again.
- After authenticated Seller Center has been reached, if a slider CAPTCHA
  appears, close it through its explicit X/Close control, revalidate the page,
  and resume the same checkpoint. If login, security, or another non-closable
  verification challenge appears, preserve the exact Feikua session and use
  the existing human-takeover flow.
- The parent `tiktok-global` monthly task is complete only when its income
  section and every discovered advertising account are terminal.

End-to-end validation completed on 2026-07-28:

- `杀虫-3pf`, 2026-07-01 through 2026-07-28: three advertising accounts
  completed, followed by a zero-click checkpoint resume.
- `YUXI-母婴-TIKTOK (3PF 1）多站点合一`, 2026-07-01 through 2026-07-28:
  the Chinese Seller Center and Chinese Ads Manager shell completed all 22
  advertising accounts (20 downloaded, two explicit no-data), followed by a
  22-account zero-click checkpoint resume.

The bundled monthly adapter is approved for execution.
