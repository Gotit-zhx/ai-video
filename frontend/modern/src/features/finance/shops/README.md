# Modern finance shops and account management

`ShopsPage` is the default lazy `shops` extension in `FinanceApplication`. It uses `useFinance()` and `useFinanceQuery()` exclusively; API requests target the shared finance client and overlays stay in the ShadowRoot. An explicitly supplied `pages.shops` still overrides the default.

The shell supplies company/user permissions and group/platform/shop/native-currency filters. This page adds previous-month completeness and name/alias/ID search. Company shop/account counts remain distinct from filtered rows. Shop state, deletions and ad status retain the legacy cadence: every 15 seconds normally, every three seconds while deletion jobs remain unfinished. Polling runs only while mounted, active and document-visible, and pauses while a page dialog is open. Shop metadata shares the shell query key, avoiding a duplicate initial read. Reader access retains sync details, filters and the after-sales navigation; management commands require `session.canManage`.

## Commands and evidence

- Shop configuration sends `{groupId,currency,timezone,enabled,alias}` to `PUT /shops/{id}`. Priority sync uses `POST /shops/{id}/sync` and preserves existing checkpoints and upstream pacing. Details show execution state, queue/priority position, window, retry/next times, last progress/success, initial/month completeness and unresolved settlement dates. Completed scraping does not imply official reconciliation.
- Group creation/rename/deletion uses `/groups`; deletion remains disabled unless returned `shop_count` is exactly zero. Shop deletion requires the exact displayed shop name, including whitespace, before `DELETE /shops/{id}`. The dialog explains irreversible data and authorization removal; backend deletion progress remains visible.
- Shop OAuth sends selected platform, market, group and optional existing shop ID to `/shops/authorize`. Advertising OAuth first checks `/ads/application` then explicitly generates via `/ads/authorize`, preserving existing advertiser/shop/GMV store identity. HTTPS results are fenced against option changes, unmount, inactive surface and owner changes. Copy fallback leaves a read-only link for manual copying. Official callbacks remain unchanged.
- Ad pause/resume sends only `{enabled}` to `/ads/{advertiserId}`. It never auto-enables accounts. Existing `AccountCoverage` renders discovered store, stream, coverage and funding evidence. Unknown API results remain unknown, returned zero is retained, and unassigned account spend is never assigned from access relationships.
- `useShopCommand` synchronously excludes duplicate pending writes, checks live permission/owner/activity, surfaces errors, and never retries. An accepted shop save/delete closes its own dialog before asynchronous cache refresh, preventing a late callback from closing a later dialog.

## Reusable company Qianyi settings

```tsx
import { QianyiSettingsModal } from "./QianyiSettingsModal";
<QianyiSettingsModal
  open={settingsOpen}
  onClose={() => setSettingsOpen(false)}
  onSaved={refreshCaller}
/>;
```

Props are `open: boolean`, `onClose: () => void`, optional `onSaved: () => void`. The component consumes the existing finance context. It fetches privileged settings only for an active manager and discards local input, including secrets, on close, inactivity or owner changes. `onSaved` is notified following an accepted configuration save or explicit sync request; the component refreshes its own settings and association list.

Only global endpoints are used: `GET/PUT /qianyi`, paginated `GET /qianyi/shops` (50/page), `POST /qianyi/sync`. Form fields match `{environment,appId,appSecret,syncStart,enabled}`. Blank secret is accepted only for an unchanged saved app/environment. Identity changes require a new secret. The UI documents checkpoint reset when the start date changes and validates the five-year date window; the server remains authoritative for account association and credential validation. A paused account remains paused, and explicit sync is disabled while paused. No retired per-shop credential endpoint is used.

Verification: run `npm test -- tests/shops.test.tsx`, `npm run typecheck`, `antd lint src --format json`, `npm run build`, and `node tests/build-smoke.mjs` from `frontend/modern`. Tests render actual AntD/Query/ShadowRoot components and fake only API boundaries; no production command is required.
