# Modern cost, source and returns workspace

`CostsPage` owns the independent monthly label, grouped price, manual cost, SKU/SPU mapping, order cost, settings and conditional input-review tabs. `ChargesPage` reuses monthly label bills and adds brush sources. `QianyiPage` is a viewer-readable after-sales workspace; its management settings reuse `shops/QianyiSettingsModal`.

The default application slots are lazy. Cost and charge entries land on monthly labels. The shell keeps normalized routing for destination selection and forwards the full route to extension components. Costs only consumes `tab`, `recordId`, `kind`, `numbers`, `start`, `end`, and `costStatus` query parameters; host filters supply shop/group/platform/native currency. Selecting another shop keeps the current tab but destroys old data and forms. Switching tabs destroys their local filters, paging, requests and polling.

## API and ownership

All requests use the scoped finance API and query providers. Reads receive a cancellation signal and a 30-second timeout. Mutations have synchronous in-flight exclusion, require live management permission, never automatically retry, and suppress stale responses after close, inactivity, filter/owner changes or permission loss. No classic DOM, globals, credentials, database or backend migrations are used.

- `/workspace/costs`: grouped source prices, manual costs, products, mappings, settings and safety capability.
- `/workspace/costs/{shop}/settings`, `/mapping`, `/manual-mapping`, `/auto-match`: original versioned contracts. Automatic matching only attempts missing unambiguous candidates on relevant management tabs; explicit retry remains available.
- `/workspace/costs/import`, `/groups/{group}/files/{id}`, `/groups/{group}/manual/{id}`: independent source/manual operations. Manual DELETE retains its JSON version body. Serial uploads preserve accepted/duplicate counts and refresh accepted results on partial failure.
- `/workspace/costs/orders`: backend all-query cost summary, scope, processing evidence and rows. Pending mode clears shop, group, platform, native currency and recordId, forces SETTLED_ORDER, and preserves dates/numbers. Returning to all orders restores the original source context.
- `/cost-exports`: reused Task2 `ExportJobs`; personal job recovery, frozen all-page selection and same-body/key uncertain submission remain unchanged.
- `/workspace/label-costs`: company-level monthly sources. Upload only sends `file`, never groupId. XLSX size limit is 96 MiB. Retry, rematch and queued removal are explicit; parsing/prepared files cannot be removed. Processing polls every 3 seconds, read failures every 10 seconds, only while visible/active/mounted.
- `/workspace/charges`: source totals are backend facts: nonzero principal row count and nonzero fee sum across all source pages, distinct from cumulative deduplicated and period-settled markers. Preview precedes import, changing the file invalidates preview, removal preserves surviving sources. Polls every 15 seconds only with unmatched sources or backend processing and without a local modal. Retired `/charges/rules` is never called.
- `/qianyi/returns`, `/detail`, `/export`: current Beijing month by default, RECEIVED and RECEIPT basis. Explicit empty dates mean all periods; NOT_RETURNED remains distinct from all. Refunds stay separated by native currency; unknown currencies are not totaled. Return cost is CNY reference only, does not reverse operating cost/profit. Detail displays receiveTime and return logistics evidence plus textual SKU identities. Export is synchronous, cancellable, error-reporting, max 50,000 backend rows; there is no invented background job.

## Input safety compatibility

When `inputSafety.enabled === true`, human imports/settings/mappings/manual changes/removal include the supplied `metadata`. Import metadata is a JSON multipart field; other operations retain exact JSON bodies plus metadata. Staging never triggers confirmation automatically. A response with `staged:true, duplicate:true, status:APPLIED` is an applied/no-change result, while a real changeId and BLOCKED/REVIEW/READY response remains unapplied.

`InputReview` implements list, fresh detail, confirm, revise, reject and undo. It uses backend canConfirm/canRevise/canReject/canUndo, current version, source declaration, target identity/unit, validation issues, diffs, independent-review and actual-impact facts. Confirm explicitly acknowledges actual scope, history when present, and price revision when required. Stale confirmation refreshes the current preview and removes old permissions/acknowledgements. Undo creates a reverse review; it does not claim immediate rollback. Unknown statuses are preserved.

An absent/false capability never invokes input-change endpoints and retains direct legacy write semantics.

## Financial boundaries

Money input uses AntD InputNumber stringMode and exact decimal strings (18 integer digits, up to 6 decimal digits); zero manual prices require explicit confirmation. Display-only matching suggestions preserve seller-SKU-first then text-SPU fallback, full bundle before base/factor fallback, ambiguity and GIFT fixed zero. The backend suggestedWarehouse is authoritative; no frontend country guessing or cross-country price borrowing is performed. Saved mapping provenance remains visible, and order SPUs are deduplicated without numeric conversion. The backend owns costing, FX, matching acceptance, aggregation and conflict resolution.

Settings preserve freight/inbound and optimistic version. Removing a manual cost retains its now-invalid references; removing a source preserves other files/manual costs. Destructive operations explain actual historical impact and require confirmation. Missing values, unknown values, partial known amounts and real zero are distinct.

## Verification

`tests/costs.test.tsx` and `tests/cost-actions.test.tsx` render real AntD in the shared ShadowRoot and replace only the API boundary. They cover route context, all-company list/export equivalence, source and manual write bodies, precision/zero/confirmation, grouped metadata, staged actions and stale revalidation, partial uploads, brush totals/preview/removal/polling, viewer/owner fences, Qianyi evidence and exports. Existing Task2 export job and Task3 Qianyi settings tests continue to exercise the reused components.

Run `npm run typecheck`, `npm test`, `npm run build`, `node tests/build-smoke.mjs`, and `antd lint src --format json` from `frontend/modern`. The full legacy language catalog and chart dependency still generate large shared chunks; no dependency version was changed here.

## Current mapping and review refresh contracts

A stored relation and an effective source are distinct. `costMappingView` validates an explicitly selected manual ID/code/warehouse or Excel code/warehouse within the current group. Deleted manual references remain pending even when an identically named Excel price exists; no fallback is selected for them. Automatic EXACT_CODE/NORMALIZED_CODE/BUNDLE_CODE/SPU_CODE relations are recomputed against current catalog candidates, including changed full bundles and ambiguity. Current local candidates and the server's stored mapping method are separate columns. The mapping editor uses the current recommendation while retaining the original optimistic version. Calculated record detail displays the backend `matchedBy` field (SKU/SPU/MANUAL).

The review tab shows group-scoped `inputSafety.pendingCount` directly, including zero and unknown. `InputReview({onChanged?})` reports every accepted action to its parent; this refreshes that count as well as any applied price/mapping/settings. The parent invalidates other current-owner query results, performs an explicit workspace refetch, and hides old editable catalog/settings data until that read succeeds. Failed reads keep the gate closed and expose a read-only retry. Generation and ownership checks prevent earlier completions from clearing a later refresh gate.

Order route context changes remount only the route-selected order state, so a new record ID cannot retain another route's kind/numbers/dates. Missing start/end use shell defaults; explicit empty strings remain empty. Ordinary local editing and rerenders keep the draft. Qianyi changes to type/return/receipt status switch to ALL whenever the RECEIVED qualification is no longer complete; choosing receipt scope restores SALES_RETURN and the corresponding FINISH/SIGNED defaults.

Post-review freshness is enforced by query-cache lifetime, not only mounted UI state. Before the required read, the parent resets every `/workspace/costs` query under the current company/user prefix, canceling earlier requests and retiring retained data for active and inactive shop/group selections. A failed read therefore leaves no old effective snapshot for a date/currency remount or page reentry to reuse. Only a successful new read can reopen settings/mapping controls; an error with no data exposes the same read-only retry even after remount. Other owners and export-job state are outside this reset prefix.
