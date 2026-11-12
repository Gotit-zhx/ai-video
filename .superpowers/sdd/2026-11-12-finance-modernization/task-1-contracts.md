# Task 1 modern Finance BI contracts

All modern code lives under `frontend/modern`. The canonical barrel is `src/app/index.ts`; feature code may import narrower modules to keep route chunks small later. Task 1 does not connect the classic workspace or expose a production entry button.

## Domain-neutral contracts

`src/shared/contracts.ts` exports:

- `Row = Record<string, unknown>`.
- `FinanceFilters`, with required string fields `start`, `end`, `shopId`, `groupId`, `platform`, `currency`, `displayCurrency`, `granularity`, `fxSnapshot`, and `dateBasis`.
- `emptyFinanceFilters`, with every filter initialized to `''`.
- `FinanceHostSnapshot`, with `organizationId`, `userId`, `canView`, `canManage`, `locale`, and `theme`.
- `FinanceHost`, with `getSnapshot()`, `subscribe(listener)`, `navigate(route)`, and `onSessionExpired()`.
- `FinanceApi`, with `get<T>(path, params?, signal?)`, `send<T>(path, method, body?)`, `upload<T>(path, formData)`, and `download(path, params, filename?)`.
- `FinanceRequestParam`, `FinanceRequestParams`, and `FinanceWriteMethod` (`POST | PUT | PATCH | DELETE`).

`src/shared/rows.ts` exports `rows`, `record`, `text`, `numeric`, and `amountText`. `null`, `undefined`, an empty numeric string, malformed numbers, and non-record values are absent. Numeric zero and string zero remain real values. `amountText(undefined, 'USD')` is `—`; `amountText(0, 'USD')` is `USD 0.00`.

## API boundary

Create the runtime client with `createFinanceApi(host)` from `src/shared/api.ts`. Optional construction seams are `baseUrl`, `fetch`, and `saveBlob`; the latter two exist for boundary tests and non-browser hosts.

- JSON requests target `/api/v1/finance` by default, use `credentials: 'same-origin'`, and unwrap the existing `ApiResponse.data` envelope.
- Empty-string query parameters are retained; `null` and `undefined` are omitted.
- Every request captures `[organizationId,userId]` before dispatch and checks it again after the response and body. A changed company/user rejects with `FinanceScopeChangedError` before stale data or a download is exposed.
- A 401 calls `host.onSessionExpired()`. Other HTTP/envelope errors preserve the backend message and status in `FinanceApiError`.
- `get` passes the caller's `AbortSignal`. `send` performs one JSON request; `upload` performs one multipart request without setting a conflicting content type. Binary downloads use the separate `download` path.
- The client requires `canView`, but intentionally does not blanket-block non-GET methods for a viewer. The backend is authoritative because viewer-safe POST/PUT operations exist (`/screen`, personal fields and export jobs). Pages must hide or disable management controls with `session.canManage`; backend permission checks remain mandatory.
- No API method retries. TanStack Query reads and mutations both default to `retry: false`; a narrowly audited page-specific operation may opt in explicitly later.

## Finance context and queries

`useFinance()` from `src/features/finance/context.tsx` exposes exactly the shared working surface:

```ts
{
  host,
  session,
  api,
  filters,
  setFilters,
  refresh,
  active,
  route,
  navigate,
  t
}
```

`setFilters` is a normal React state dispatcher. `refresh()` invalidates the current organization/user query scope. `navigate(route)` delegates to the host. `t(sourceLabel)` translates explicit source keys without DOM rewriting.

Use `useFinanceQuery({ queryKey, queryFn, ...options })` instead of raw `useQuery` for finance reads. It prefixes cache keys with `['finance', organizationId, userId]`, disables reads when the surface is inactive or the user cannot view finance, and receives TanStack's cancellation signal. Company/user changes cancel and remove the old scope and reset filters. Setting `active=false` cancels the current scope. `useFinance().api` is a stable live-gated proxy: while inactive it rejects with `FinanceInactiveError`, including when a stale callback retained the API object captured before deactivation.

`createFinanceQueryClient()` and `FinanceRootProviders` are in `src/app/providers.tsx`. The provider owns one QueryClient per mounted root.

## Localization

`src/shared/i18n.ts` uses the existing `frontend/i18n/catalog.json` as explicit-key translations for Chinese, English, Indonesian, Thai, Vietnamese, Malay, and Filipino. It exports `createFinanceTranslator(locale)` and `normalizeFinanceLocale(locale)`. React pages must call `const { t } = useFinance()` for interface labels; do not use the legacy DOM translator inside the ShadowRoot.

Unknown keys return their source label. Add source labels to the shared catalog in the later integration task if a new label requires translation; Task 1 does not edit the legacy catalog.

## ShadowRoot and UI

`createShadowSurface(host, cssText)` creates an open ShadowRoot containing dedicated style, React mount, and overlay containers. `FinanceUiProvider` uses Ant Design `StyleProvider` with the style container, `ConfigProvider.getPopupContainer` with the overlay container, and AntD `App` for contextual modal/message/notification utilities.

Use these exports:

- `BusinessTable<T>`: full Ant Design `TableProps<T>`.
- `Panel`: Ant Design Card wrapper.
- `MetricCard`: label/value/detail with default, positive, warning, or negative tone.
- `InfoTip`: focusable button-backed Ant Tooltip suitable for keyboard and touch interaction.
- `StatusTag`: Ant Tag with default/success/processing/warning/error status colors.
- `FinanceChart`: ECharts lifecycle, resize, option update, and disposal wrapper.
- `FinanceModal` and `FinanceDrawer`: explicit ShadowRoot overlay containers.
- `useFinanceDialogs()`: AntD `App.useApp()` modal/message/notification API, whose holders stay inside the ShadowRoot.

Do not call AntD static `Modal.*`, `message.*`, or `notification.*` APIs outside `useFinanceDialogs()`.

## Mount and build

`mountFinanceRoot(container, options)` in `src/app/mount.tsx` creates the ShadowRoot and React root. It renders only the Task 1 placeholder unless a child application is supplied later. The returned handle exposes `surface`, `render(options)`, and `dispose()`. This is not the Task 5 `mountFinance` facade and is not connected to classic code.

Vite builds from `src/app/build-entry.ts` to ignored generated files under `frontend/assets/finance-modern/`. The public base is `/assets/finance-modern/`; the public manifest is `manifest.json`; sourcemaps are disabled; CSS splitting is disabled and the current build emits exactly one CSS asset. Task 5 owns atomic staging, retained hashes, classic loading, and source-tree serving restrictions.

## Test fixtures

`tests/fixtures.tsx` exports:

- `defaultSession`.
- `createFinanceHostFixture(initial?)`, with `update`, `navigate`, and `onSessionExpired` spies.
- `createFinanceApiFixture(overrides?)`.
- `renderFinance(children, options?)`, which renders real AntD/Query/context providers in a real open ShadowRoot and returns Testing Library queries scoped to the ShadowRoot mount plus `surface`.

New page tests should use these fixtures and fake only the API boundary. They should assert rendered AntD behavior and request inputs rather than replacing shared components with mocks.
