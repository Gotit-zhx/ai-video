---
name: yue-bill-parallel-10
description: Run resumable date-ranged monthly bill exports from a multi-sheet store workbook with three default workers and a hard limit of five total active or preserved Feikua store sessions. Use when Codex needs to process 月数据导出店铺.xlsx or a similar TikTok, TikTok cross-border, Shopee, and Lazada store list quickly without allowing failed browser windows to accumulate, while preserving per-store CDP, download, log, session, and task isolation.
---

# Yue Bill Parallel

Use `run.ps1` as the only entry point. Keep the source workbook unchanged.
Do not run the serial `yue-bill` skill at the same time: its lifecycle policy
closes other Feikua stores.

## Workflow

1. Start from no unrelated Feikua windows.
2. Create or refresh the task plan with a dry run.
3. Inspect `_run/tasks.json`.
4. Execute with `-Execute -Resume`; default worker concurrency is 3 and the
   hard live-session cap is 5.
5. Resume the same task file after interruption. The coordinator reattaches live
   workers recorded in `_run/parallel-state.json`.
6. Deliver the finalized workbook plus the income, TikTok advertising-cost,
   and balance-detail folders.

Create the plan:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  -Workbook "<月数据导出店铺.xlsx>" `
  -StartDate YYYY-MM-DD `
  -EndDate YYYY-MM-DD `
  -OutputRoot "<output-folder>"
```

Execute three concurrent workers with no more than five total active or
preserved store sessions:

```powershell
powershell -ExecutionPolicy Bypass -File <skill-folder>\run.ps1 `
  -TasksPath "<output-folder>\_run\tasks.json" `
  -Execute `
  -Resume `
  -MaxConcurrency 3 `
  -MaxLiveStoreSessions 5
```

Use `-OnlyStore`, `-OnlyTaskId`, `-OnlySheet`, `-OnlyPlatform`, or
`-OnlyStatuses` for pilots and targeted retries. Use
`-OfficialWarehouseOnly -OnlyPlatform shopee -MaxTasks N` for a bounded
official-warehouse pilot without starting ordinary Shopee rows. Prefer
`-OnlyTaskId` when
multiple workbook rows share the same exact Feikua store name. Use
`-MaxConcurrency 2` for the first live pilot on a new machine, then increase to
3 after confirming two distinct CDP ports and correct per-store downloads.
Never set `-MaxConcurrency` or `-MaxLiveStoreSessions` above 5.

## Isolation guarantees

- Keep one worker task file, runtime directory, session registry, and log folder
  per task.
- Treat `MaxConcurrency` as fixed task slots with a default of 3 and maximum
  of 5. Start a replacement only after
  the prior worker reaches a terminal result and its exact Feikua store is
  confirmed idle.
- Count both active workers and preserved recoverable/manual Feikua windows
  toward the live-session limit. Set the effective limit to
  `min(MaxConcurrency, MaxLiveStoreSessions)`, with an absolute maximum of 5.
  Therefore a three-worker run may have only three total active or preserved
  store windows. On coordinator startup, close older preserved-recoverable
  exact stores first until the effective cap is satisfied; retain active
  workers and prefer retaining manual-takeover sessions. Never open a new exact
  store while the cap is full.
- Prefer a queued task that owns an existing preserved exact-store session
  before opening a new store. Before each new store launch, require at least
  `MinimumLaunchFreeMemoryGB` free memory (default 2 GB); pause replenishment
  and resume automatically when memory recovers.
- When a recoverable preserved session is in backoff and leaves an execution
  slot empty while consuming the effective live-window cap, close that exact
  non-running session and let a different ready task fill the slot. Keep the
  evicted task queued for a later reopen. Never evict a running or
  manual-takeover session for this purpose.
- By default, let each isolated slot execute immediately when its own CDP is
  ready. Use `-WaitForInitialWave` only when the user explicitly wants every
  initial store preconnected before any page interaction begins.
- After connection, require the authenticated platform Seller Center home.
  Execute immediately when it is reached and emit the platform
  `*_EXPORT_EXECUTION_STARTED` marker before finance navigation; otherwise
  close that exact shop and write `未进首页，请检查登录` into its Excel status
  cell. A connected window without that execution marker is not an active bill
  task and must never be reported as executing.
- For Shopee, submit Feikua-autofilled login credentials once. For an in-site
  password dialog, resolve the exact task row's `备注` cell from the source
  workbook at execution time. When a parsed `导出密码` / `站内密码` is available,
  always focus the field, select all, clear it, enter the workbook value,
  verify the replacement, and submit once. Do not submit an old autofilled
  in-site-password value unchanged.
  Never store that password in task JSON, command arguments, logs, or output
  workbooks. Close and record the shop only when the workbook password is
  unavailable or explicitly rejected.
- On legacy Shopee profiles, allow the version-translation CDP proxy up to 30
  seconds to enumerate the exact profile's Seller Center target before
  classifying the shop as not reached. Accept a rendered
  `seller.shopee.<site>` root as well as Finance routes; do not fail merely
  because the second WebDriver attachment initially exposes no tabs.
- When the legacy Shopee balance-detail date picker remains unstable after
  bounded UI attempts, submit the same exact date range through Shopee's
  authenticated official `export_wallet_transactions` endpoint, then use the
  ordinary export-history and XLSX validation path. Never manufacture a local
  workbook or treat API submission alone as download success.
- For a Shopee task whose source-workbook `备注` contains `官方仓`, include the
  all-orders export. Submit income, balance, and the official-warehouse order
  job in sequence without waiting for an earlier server job to finish, then
  rotate through all three export histories every 30 seconds. A Shopee row
  without `官方仓` in `备注` must keep the ordinary two-job income-and-balance
  rotation and must never submit an order export. Open
  `/portal/sale/order`, select the exact inclusive range, require the numeric
  DOM value to equal the request before submission, and reuse an existing
  `Order.all.order_creation_date.YYYYMMDD_YYYYMMDD` report instead of
  resubmitting. Locate the history toggle, history panel, report row, and ready
  button by DOM structure and enabled state, not translated UI text. Poll the
  ready state every 500 ms. If one submitted order job exposes multiple
  matching downloadable files, click every enabled download for that exact
  date job and validate every local file; never resubmit the export to obtain
  missing parts. Small reports may be `.xlsx`; large reports may be `.zip`.
  Validate the actual content, then place it under `官方仓数据` with the filename
  stem `店铺名+Y.M.D-Y.M.D`; append `+part2`, `+part3`, and so on only when the
  same job returns additional files. Preserve the real `.xlsx` or `.zip`
  suffix only so Windows can open the file; never preserve Shopee's original
  `Order.all...` basename.
- For an official-warehouse order range spanning at least eight calendar days,
  once the exact report row is confirmed as processing, do not apply a fixed
  generation timeout. Refresh the order page every 30 seconds, reopen export
  history structurally, and inspect only that exact report row. Download as
  soon as its enabled button appears. A refresh-replaced/stale DOM node is a
  signal to reacquire the row, not a task failure. Never resubmit the export.
- Let only the coordinator write the master `_run/tasks.json`.
- Serialize Feikua start and stop API calls with a cross-process lock while
  allowing browser page work and export waiting to run concurrently.
- Preserve other running stores in parallel mode.
- Never run duplicate rows for the same exact Feikua profile at the same time.
- Keep each task's output directories and download confirmation independent.
- For TikTok and TikTok cross-border only, after the authenticated Seller
  Center has been reached, close a visible slider CAPTCHA through only that
  challenge container's explicit X/Close control and continue from the same
  workflow checkpoint. Never close or bypass a login-page challenge. Login
  failure still closes the exact shop under the existing terminal rule.
  Shopee and Lazada keep their existing verification handling.
- Poll authenticated TikTok pages for a closable slider at 250 ms intervals.
  Prefer the challenge's exact `a.verify-bar-close` control and confirm the
  actual CAPTCHA container/iframe disappeared; do not use the persistent
  body `captcha-disable-scroll` class as disappearance evidence. If SecSDK
  ignores a synthetic DOM click, issue a genuine WebDriver pointer click on
  that exact close anchor and re-check the CAPTCHA container; never broaden
  this fallback to a page/window close control.
  `body.captcha-disable-scroll` class as the disappearance gate.
- Do not impose a timeout after an export-history job is confirmed as processing.
- After every TikTok income export submission, explicitly open Export History,
  confirm that the history panel really opened, and inspect it for this
  submission's new job. Only after that confirmed inspection may an absent new
  job become no-data. When the new job exists, click Download on that exact row
  and validate the local XLSX before advancing to advertising or closing.
  If TikTok encodes the downloaded filename timestamp one second differently
  from the clicked history row, accept only one unique new valid
  `income_*.xlsx` whose encoded time is within 120 seconds of that exact row.
- A TikTok SPA with its authenticated shell/left navigation rendered but a
  blank main content area is still loading. For the Seller Center `Shop ads`
  entry specifically, inspect the right-side main content after every wait.
  Support both the newer marketing container (`data-tid="marketing"`) and the
  older Philippine menu (`data-tid="4"`), including the old variant where that
  node is itself the clickable menu header. Expand it and require the exact
  `data-tid="ads_creation"` Shop ads entry. The homepage
  `homepage.ads.set_up.create_campaign` card opens campaign creation and must
  never be accepted as the Shop ads billing entry.
  If it remains completely blank, slowly re-click the exact menu item without
  a click-count limit while the CDP target remains alive. After each click,
  observe for 30 seconds, polling both the official entry and the main content
  every 500 ms before deciding whether another click is needed. As soon as
  that main area starts rendering text, cards, controls, media, a table,
  iframe, loading skeleton, or spinner, stop clicking and only wait for the
  official Ads Manager entry, emitting progress heartbeats. Treat a localhost ChromeDriver
  read timeout during the idempotent menu action as continued loading. Never
  extend that rule to an advertising Export click. Do not refresh, fail the
  task, or close the shop unless the browser/CDP target itself has actually
  disappeared.
  When a recovery checkpoint already contains one or more audited advertising
  account IDs and that rendered welcome shell still has no official entry
  after three minutes, open the stable Ads Manager route for the first
  non-terminal checkpoint account. This fallback must not refresh the Seller
  page, re-click Shop ads, or run for a first-time account discovery.
- Attach ChromeDriver with a non-blocking page-load strategy and gate every
  action on explicit rendered-state checks. Seller Center SPAs may remain in
  native `loading` while their usable shell is already rendered; background
  subresources must not stall worker startup. If the exact validated store
  host and CDP target remain alive after the advisory document-readiness wait,
  continue to explicit login, verification, disabled-store, and Seller Center
  checks; do not call `Page.stopLoading` or close the shop for that timeout.
- Apply the same rule to Ads Manager direct-route recovery: a local
  ChromeDriver response timeout after `get()` means observe the dispatched
  navigation on the same target. Never call `window.stop`, refresh, or issue
  another click while that target remains alive.
- On recovery, scan existing tabs before navigating. If the exact Seller
  Center already has an `/ads-creation` page, reuse that tab and preserve its
  rendered content. If content is already present, do not return Home, do not
  click `Shop ads` again, and do not refresh; continue waiting from that page.
- Before an advertising recovery reopens the official Ads Manager target,
  close prior Ads Manager tabs and older duplicate `/ads-creation` tabs inside
  that exact isolated Feikua profile, retaining one rendered Shop ads tab.
  Never close the Feikua store window, detection page, or another store. This
  prevents stale-tab selection and CPU growth across repeated retries.
- If TikTok explicitly renders `账号已停用` or `店铺状态无效` (or their
  localized equivalents), record the task as `TIKTOK_STORE_DISABLED` and
  force-close that exact Feikua shop immediately in either the income or
  advertising phase.
- Close a normal platform task only after every required section has either a
  confirmed valid downloaded file or a platform-confirmed terminal no-file /
  no-data result.
- For regional TikTok (`tk-bill`) and TikTok cross-border (`tk-bill-cn`) tasks
  inside this monthly skill, keep the existing income export unchanged. After
  that income section is terminal, pass `--ad-cost-out <广告收入板块目录>`, reuse
  the same exact Feikua/CDP session, and append the Ads Manager Costs section.
  Save its files only under `广告收入板块`; never mix them into `收入板块`.
- In the TikTok advertising section, enumerate every selectable advertising
  account. For each account, select `Costs`, reselect the exact task dates,
  click `Confirm`, verify the rendered range equals the task, then click
  `Export` exactly once. One click may legitimately return both `Cost` and
  `CostTax` workbooks; preserve and validate every file from that single click.
- After Ads Manager opens, if it lands on a login page, submit the
  Feikua-autofilled identity and password without reading or logging their
  values, at most twice across bounded recovery. On the TikTok for Business
  form, click the pink email/password `Log in` button even when it is rendered
  as `type="button"`; never substitute the black `Log in with TikTok` control.
  Close a closable slider or same-shape-object challenge through its own X,
  then resume the advertising checkpoint. Recognize both Chinese and English
  prompts, including `Select 2 objects that are the same shape`; never solve
  or click the pictured objects. If
  automatic login and Seller Center SSO re-entry both fail, preserve the exact
  session for verification.
- Observe only sanitized Ads login response metadata, never request bodies,
  autofilled values, tokens, or raw response payloads. A repeated server
  verification decision after the exact CAPTCHA X was closed is terminal
  `广告登录需要验证码`: close the exact shop and do not let recovery-exhaustion
  logic overwrite that split income/advertising result.
- If that advertising login instead reaches an email/phone one-time-code or
  two-step-verification page, do not wait and do not preserve the shop. Close
  the exact store, keep the already-terminal income result unchanged, set the
  advertising result to failed, and write
  `收入表成功，广告账单失败（广告登录需要验证码）` (or the matching
  income no-data variant) into the workbook status cell.
- If the advertising login explicitly reports an incorrect account or
  password, do not retry the credentials and do not preserve the shop. Close
  the exact store, keep the already-terminal income result unchanged, set the
  advertising result to failed, write
  `收入表成功，广告账单失败（广告账号密码错误）` (or the matching
  income no-data variant) into the workbook status cell, and treat that result
  as terminal so the recovery coordinator does not schedule it again.
- Recovery exhaustion must not rewrite terminal advertising OTP/verification
  or credentials-rejected outcomes to generic `待人工`. Preserve their exact
  split status text in the finalized workbook.
- Treat missing `Billing`, `Transactions`, or their destination panels as slow
  network loading while the CDP target remains alive. Poll slowly, then use
  the current account ID's direct Billing/Transactions route as a bounded
  recovery. Preserve the exact session if the destination still cannot render.
- After a TikTok Finance route change, the URL may update before Chrome
  installs the replacement document body. Wait up to 30 seconds for the new
  `body` target before checking disabled/no-results text; never classify that
  transient DOM gap as an income-stage failure.
- Name each primary advertising workbook
  `店铺名（含站点）+广告户名字+YYYY.M.D-YYYY.M.D.xlsx`, for example
  `杀虫-TH-TikTok+Q1-杀虫剂-帮炜-泰国+2026.7.1-2026.7.31.xlsx`.
  If the same click also returns a tax workbook, append `+CostTax` only to
  that secondary filename.
- Treat only an explicit Ads Manager `No data` state as an advertising
  no-data terminal result. The parent TikTok task is complete only after the
  income section and every discovered advertising account are terminal.
- When an account switch, Costs panel, or date picker times out before an
  Export intent exists, retry that exact account up to three times in the same
  session through its stable account-ID Transactions/Costs route. This must
  rebuild the interrupted account panel instead of letting one stale popover
  or picker cascade into failures for following accounts. Never use this
  recovery after an Export intent has been recorded.
- Treat a missing advertising-account dropdown or empty account list as slow
  rendering while the Ads Manager target is alive. Poll it for up to three
  minutes with 30-second heartbeats and no refresh or close before returning a
  recoverable timeout to the coordinator.
- Distinguish an actual rendered verification challenge from an ordinary
  transient renderer timeout. If no challenge is present, retry the
  checkpointed advertising phase in the same session immediately and do not
  emit a manual-verification state.
- If authenticated TikTok presents a slider CAPTCHA during the appended
  advertising section, close it through its explicit X and resume the same
  checkpoint. For any
  security challenge that is not a closable slider, use the existing
  exact-window human-takeover flow and preserve the Feikua session.
- Checkpoint each advertising account after a confirmed file or explicit
  no-data result. On retry, validate and skip those terminal accounts. If the
  income section is already terminal, use the executor's `--ad-costs-only`
  resume mode so neither the income export nor a completed advertising
  account is clicked again.
- For a dedicated retry wave containing only TikTok tasks whose income stage
  is terminal but advertising stage is not, run the coordinator with
  `-RecoveryPass -AdCostsOnlyRecovery`. Completed/no-data advertising tasks and
  all non-TikTok platforms must stay outside that wave.
- The advertising arguments above describe only the bundled monthly executors
  under this skill. They do not assert support in the standalone
  `~/.codex/skills/tk-bill-cn` skill.
- Before each advertising Export click, atomically checkpoint its exact
  account ID and deterministic staging directory. If execution is interrupted
  after that intent is recorded, a resumed run waits for or validates the
  files from that staging directory and must not click Export again.
- Keep a submitted export in its existing slot, reconnect to export history
  after transient CDP failures, and never submit that export a second time.
- Reuse the short shared ChromeDriver cache; serialize and atomically publish
  downloads so long worker paths and concurrent extraction cannot corrupt it.
- Refuse a multi-worker launch when free memory is below the configured safety
  threshold unless the user explicitly supplies `-AllowLowMemory`.

## Completion

Apply the same platform terminal rules and file validation as `yue-bill`.
Generate the final workbook only after every selected worker has reached a
terminal state. Never manually edit worker JSON or `parallel-state.json`.

Read [parallel-architecture.md](references/parallel-architecture.md) before
changing scheduling, Feikua lifecycle behavior, state recovery, or download
isolation. Read the matching platform reference only when changing that
executor: [tk-bill.md](references/tk-bill.md),
[tk-bill-cn.md](references/tk-bill-cn.md),
[shopp-bill.md](references/shopp-bill.md), or
[lazada-bill.md](references/lazada-bill.md).
