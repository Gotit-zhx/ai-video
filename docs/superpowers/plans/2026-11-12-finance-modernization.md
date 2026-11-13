# Finance BI Modernization Implementation Plan

> For agentic workers: implement tasks with superpowers:subagent-driven-development and two-part task reviews. Use checkbox steps and record exact evidence in the plan-owned ledger.

**Goal:** Ship complete modern financial BI with an explicit separate-tab entry, preserving the classic default and every existing operation.

**Architecture:** One modern React application mounted in a ShadowRoot via a thin classic-host adapter. The financial domain uses the existing backend and reusable shared UI/data capabilities; no classic DOM widgets are embedded in new pages.

**Tech Stack:** React, TypeScript strict, Vite, Ant Design, Lucide, TanStack Query, ECharts; Vitest/Testing Library and Playwright.

**Spec:** ../specs/2026-11-12-finance-modernization-design.md

## Global Constraints

- Classic is always the default; modern mode is current-URL-only `financeUi=modern`; preserve full authorized writes and classic code until explicit deletion approval.
- New tab links are noopener/noreferrer; URL seed whitelist contains start,end,shopId,groupId,platform,currency,displayCurrency only.
- One renderer per tab; shared Cookie/auth/server data, separate UI state. No automatic replay of writes, no production mutations in tests.
- Existing finance APIs/amounts/permissions/lease/checkpoints/fx snapshots remain authoritative. No backend migrations or recrawls for this UI change.
- Open ShadowRoot owns all modern CSS and overlays. Frontend source is not published. Generic shared code cannot import financial domain or legacy adapters.
- Agents do not spawn further agents. Only one implementation subagent at a time; independent read-only requirements audits may run alongside.
- Fresh meaningful red/green tests before implementation and exact verification before committing. Never weaken existing regression assertions to hide a difference.

## Task 1: Modern platform and shared contracts

**Files:** frontend/modern/package.json, lockfile, Vite/TS/test config, src/app, src/shared, src/features/finance/context.tsx, tests; .gitignore only for generated outputs.

**Interfaces:** `Row = Record<string, unknown>`; `FinanceFilters` fields start/end/shopId/groupId/platform/currency/displayCurrency/granularity/fxSnapshot/dateBasis. `FinanceHost` has getSnapshot(), subscribe(listener), navigate(route), onSessionExpired(); snapshot contains organizationId,userId,canView,canManage,locale,theme. `FinanceApi` has get<T>(path,params,signal?), send<T>(path,method,body), upload<T>(path,FormData), download(path,params,filename?) and returns unwrapped data, with company checks, credentials same-origin and writes retry=false. Context hook `useFinance()` exposes host/session/api/filters/setFilters/refresh/active/route/navigate. Row field helpers `rows(value), record(value), text(value), numeric(value), amountText(value,currency?)` distinguish absent vs zero.

- [ ] Write tests for scoped requests, missing-vs-zero, request cancellation, no replay and ShadowRoot overlays; observe expected red.
- [ ] Create the strict single project and exact lockfile; use mature APIs, no experimental platform.
- [ ] Implement host contracts, query providers and shared `BusinessTable` (Ant Table props), `Panel`, `MetricCard`, `InfoTip`, `StatusTag`, chart and dialog utilities. Provide context and a mountable root without enabling production entry.
- [ ] Test real components with API-boundary fakes, run typecheck/tests/build, record red/green evidence, commit only owned files.

## Task 2: Reports, records and common financial operations

**Files:** frontend/modern/src/features/finance/reports, records, operations, corresponding tests. Read /tmp/finance-modern-read-audit.md first.

**Consumes:** Task 1 contracts. **Produces:** React `ReportsPage` for overview/live/ads and `RecordsPage` for settlements/orders/fees/reconciliation; shared record detail, field configuration, import/export operations. Export route components from a financial page registry. Routes preserve classic aliases fees/fee-records/expense-ledger. Financial summaries come from backend, not current-page recomputation.

- [ ] Build a parity matrix from audited actions and add red tests for displayed backend totals, source-specific ad drilldowns, scoped query/export and null/zero values.
- [ ] Implement all report sections, trends, group/shop drilldowns, live read-screen semantics, dates/FX, BASIC/GMV coverage, source selection and unassigned-spend flows.
- [ ] Implement server-page record grids and filters including exact number queries, all-vs-settled orders, refunds entry, reconciliation, details, personal visible/export field settings, previews/import and binary/existing job exports.
- [ ] Validate command payloads, uncertain writes, permissions, stale data and actual forms/table actions; typecheck/tests/build, report and commit.

## Task 3: Store, account and synchronization management

**Files:** frontend/modern/src/features/finance/shops, tests; page registry additions only.

**Consumes:** Task 1 contracts and audited controller. **Produces:** `ShopsPage` containing current shop/group/global Qianyi configuration, plans/progress/priority, authorization, safe deletion and advertisement account management.

- [ ] Write red tests proving reader permissions, exact dangerous confirmation, auth-link commands and no duplicate mutations.
- [ ] Implement every original store/group/account/sync control including pause/resume and deletion progress, shared-account visibility and source scopes.
- [ ] Keep existing OAuth endpoints and callback handling; command failures show actionable status without retry.
- [ ] Typecheck/tests/build, report and commit.

## Task 4: Costs, adjustments, brush/label and Qianyi

**Files:** frontend/modern/src/features/finance/costs, charges, qianyi, tests; page registry additions only. Read /tmp/finance-modern-costs-audit.md first.

**Consumes:** Tasks 1/2 shared contracts and operation components. **Produces:** `CostsPage`, `ChargesPage`, `QianyiPage` and all audited nested actions.

- [ ] Inventory every audited legacy action and write red tests for its query/body and critical constraints (country warehouses, all-shop pending exports, GIFT, brush nonzero principal count/all-query fee sum, receiveTime/return evidence, empty-vs-undefined filters).
- [ ] Implement full cost sources/warehouse/SKU/SPU mapping, local manual costs, imports, recalculation controls, pending costs, explicit selection export jobs and existing job recovery.
- [ ] Implement grouped label prices, shipping/brush costs, source previews/imports, marketing orders, Qianyi returns/configuration/receipts and all current exports.
- [ ] Preserve hidden-date number searches, scope and stale-result safety; tests/typecheck/build, report and commit.

## Task 5: Classic-host facade, dual entry and static/source-development integration

**Files:** frontend/finance-entry.js (new facade), frontend/finance-workspace.js (classic entry link only), frontend/index.html (finance loader), frontend/modern/src/adapters, frontend/package.py and current source-development/CI entrypoints only as required; tests.

**Consumes:** All routes complete. **Produces:** one `mountFinance(container,host,initialFilters)` export returning enter/leave/reset/dispose handle. The classic facade preserves allowed/canManage/enter/leave/reset and lazy-loads modern only when requested. Classic entry button serializes a validated filter seed; modern header returns to explicit legacy URL. Keep legacy helpers/code accessible for fallback; never execute classic and modern simultaneously. Use manifest/hashed assets and single isolated CSS output.

- [ ] Red test classic default/no modern resource loads, safe link/filter transfer, each explicit mode and load failure fallback.
- [ ] Implement facade, legacy host, lifecycle/context propagation and shared session handling; integrate source-run and static packager so fresh checkouts compile modern assets without serving TS/source files.
- [ ] Red then green test own-tab filters/initialization, company reset, delayed bootstrap/response after leaving, route aliases and OAuth results.
- [ ] Run complete existing frontend regression plus modern tests/typecheck/build and static packaging. Report/commit only after pass.

## Task 6: Browser acceptance, final review and delivery

**Files:** modern Playwright fixtures/tests, migration parity matrix and acceptance docs, docs navigation/development documentation as needed.

- [ ] Real browser on isolated local API fixture: all routes and critical writes, overlays in Shadow DOM, seven locales/themes, desktop/mobile widths, two tabs, failure/cancellation/unknown writes and legacy return.
- [ ] Validate original frontend and non-finance navigation suites; live production verification remains read-only and only after safe integration/current-runtime coordination.
- [ ] Record feature-by-feature evidence, do broad final review, fix load-bearing findings, repeat only relevant checks.
- [ ] Commit all task files on feature branch, push fork and create review PR with honest validation limits. Source/runtime activation must preserve the classic default and use current actual host workflow, not historical deployment assumptions. Do not delete old UI.

## Task 7: Integrate the current page-query and lightweight cost contracts

The concurrent performance work preserves legacy endpoints and adds page-specific query/status and cost-context endpoints. Before experience activation, use the current contracts without reintroducing a serial overview query on record pages or loading a complete cost catalogue for monthly labels/order costs. This changes the financial read adapter and request scheduling; backend calculations, writes and task semantics stay unchanged.

- [ ] Implement a financial read adapter for `GET /workspace/query`, retaining the exact filters, historical FX receipt, record kind, page and advertising-only scope. Unwrap authoritative `records`, `adSpend` and `adAccounts`; main advertising spends use `spendPage`. Load overview/support data only when its corresponding panel needs it.
- [ ] Preserve local source compatibility because the existing development baseline may lack these optional endpoints. A scoped capability cache may fall back to the existing read flow only for endpoint-level HTTP 404/405; preserve cancellation, identity isolation and all other errors. Never replay writes or manufacture missing DTO fields.
- [ ] Load `/workspace/costs/context` for labels/order-cost navigation; management tabs load the complete catalogue/settings before enabling writes. The thin response's default shop/group is a navigation hint and must not narrow all-company filters or exports. Keep withdrawn safety controls inactive on current servers, while retaining compatible behavior for older supported backends.
- [ ] Use lightweight status for existing visible/active operational heartbeats, deferring during open dialogs. Keep amounts, filters and unsaved drafts independent of status updates; do not create a second live-screen poll or full-report refresh loop.
- [ ] Add meaningful tests for absent overview/catalogue requests, query DTO/filter/FX parity, old-endpoint compatibility, guarded writes and heartbeat draft preservation. Retain existing browser coverage and strict error audit; extend it as needed. Run type, unit/source, AntD, build/ESM/budget and complete browser/CI gates on the resulting source, then obtain a scoped independent review before activation.
- [ ] Allow 20 minutes for the complete frontend CI job: the prior 148-case Linux run took 13m29s of its 15-minute installation/build/test budget, and this integration adds protocol and session-lifecycle cases. Keep per-test timeouts, assertions, zero retries and strict error auditing unchanged.
