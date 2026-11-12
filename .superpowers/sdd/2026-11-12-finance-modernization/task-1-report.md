# Task 1 report — modern platform and shared contracts

Date: 2026-11-12

Branch: `feature/finance-bi-modern`

Base: `7d59990`

Scope: `frontend/modern/**`, generated-output ignore in `.gitignore`, and the two required Task 1 handoff documents.

## Result

Created one strict React/TypeScript/Vite project with exact npm locks for React 19.3.1, Ant Design 6.7.0, Lucide React 1.72.0, TanStack Query 5.107.1, ECharts 6.1.0, Vite 8.3.8, TypeScript 7.0.2, Vitest 5.1.0, and jsdom 26.1.0. The engine contract is Node `>=22.12.0`; all final checks ran under the repository runtime version Node 22.21.1.

Implemented the shared host/session/filter/API contracts, organization/user response guards, same-origin JSON and binary boundaries, cancellation, inactive-surface protection, scoped TanStack Query support, seven-locale explicit-key translation, ShadowRoot style/overlay infrastructure, real AntD shared components, ECharts lifecycle wrapper, test fixtures, and a placeholder-only mount root. No classic adapter, legacy entry, production button, route pages, or runtime activation was added.

The API client requires finance view access but does not use a blanket `canManage` rule for non-GET requests. This is intentional: the backend audit confirms viewer-safe POST/PUT operations such as `/screen`, personal field preferences, and personal export jobs. Page authors must gate management controls with `session.canManage`, while the backend remains authoritative.

## TDD evidence

### Initial RED

Command:

```text
npm test
```

Observed exit `1` at 12:46:19. Five suites failed before collecting tests because the production modules did not exist:

```text
FAIL tests/api.test.ts       Failed to resolve ../src/shared/api
FAIL tests/components.test.tsx Failed to resolve ../src/shared/components
FAIL tests/context.test.tsx  Failed to resolve ../src/features/finance/context
FAIL tests/rows.test.ts      Failed to resolve ../src/shared/rows
FAIL tests/shadow-overlays.test.tsx Failed to resolve ../src/shared/dialogs
Test Files 5 failed (5)
```

This established the requested tests before implementation for scoped requests, missing-vs-zero, request cancellation, no write replay, and ShadowRoot overlays.

### Additional RED cycles

- `npm test -- tests/i18n.test.tsx` exited `1`: the required explicit-key translator module did not exist.
- Inactive/API and complete overlay coverage initially produced two failures: inactive context still exposed the raw API, and the first command-dialog assertion ran before React committed the AntD `App` holder update. The implementation added an inactive rejecting proxy; the overlay test now waits for the real asynchronous AntD commit.
- `npm test -- tests/components.test.tsx` exited `1` because `InfoTip` exposed an image role rather than a keyboard-focusable button. It now renders a styled `type=button` trigger with visible focus treatment.
- A failed read-query test exited `1` after observing two attempts. Global TanStack query and mutation retries are now both disabled.
- A lifecycle test captured `useFinance().api`, deactivated the provider, and initially observed the captured object still reaching the underlying API. The context now exposes a stable proxy whose methods consult live active state before every call.
- The first strict typecheck exited `1` on `exactOptionalPropertyTypes`, zero-argument fetch-double inference, and Vite/Vitest config typing. The declarations and config import were corrected without relaxing compiler flags.

### GREEN

Final Node 22.21.1 command and result:

```text
PATH=/Users/gotit/.npm/_npx/938d4c0e63b51281/node_modules/node/bin:$PATH npm test

Test Files  6 passed (6)
Tests       17 passed (17)
exit 0
```

Coverage exercised:

- same-origin credentials, query serialization, `ApiResponse.data` unwrapping, 401 callback, organization drift, AbortSignal propagation, viewer-safe actions, and one-attempt failed writes;
- missing records/text/numbers and real zero values;
- session subscription, filters, navigation, query refresh, inactive query disabling, cancellation on unmount/deactivation, no automatic query replay, inactive direct API rejection, and stale captured-API fencing;
- all seven locale mappings and unknown-key fallback;
- real AntD Table/Card/Tag/Tooltip components, focusable `InfoTip`, ShadowRoot base/CSS-in-JS styles, Select popup, Modal, Drawer, and contextual modal/message holders.

## Final verification

All commands below used Node 22.21.1.

```text
node --version
v22.21.1

npm run typecheck
> tsc --noEmit
exit 0

npm run build
✓ 4198 modules transformed.
manifest.json                                  0.25 kB
assets/style-qr6Pzjqs.css                     1.11 kB (0.57 kB gzip)
assets/build-entry-BNcxIewy.js             1,336.21 kB (438.43 kB gzip)
exit 0
```

Build settings verified: public base `/assets/finance-modern/`, public `manifest.json`, no sourcemaps, `cssCodeSplit=false`, and one CSS asset in a clean generated directory. Generated browser assets are ignored and are not part of the commit.

## Boundaries and concerns

- Vite reports the single eager JavaScript entry as larger than 500 kB: 1.34 MB minified / 438.43 kB gzip. This is expected for the current all-exports foundation with AntD, full ECharts, and the existing translation catalog. Per task direction, no route/chart lazy-loading work was pulled into Task 1; Tasks 2/5 can narrow build imports and lazy-load charts, and browser acceptance must measure the final payload.
- No legacy frontend suites were rerun because Task 1 did not modify legacy/runtime files. The parent ledger records the approved baseline (740 tests plus 36 UI suites). Modern tests, strict typecheck, and build are the verified scope here.
- No Playwright/browser acceptance, classic integration, production entry, deployment, push, merge, or production mutation was performed. Those remain Tasks 5/6.
- The mount export is deliberately `mountFinanceRoot`, a placeholder root for Task 1. It is not the final Task 5 `mountFinance` facade.

Contract handoff: `.superpowers/sdd/2026-11-12-finance-modernization/task-1-contracts.md`.
