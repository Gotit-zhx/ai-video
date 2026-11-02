# Finance query caching and completion — 2026-11-02

This release addresses repeated failed monthly BI reads and redundant queries when switching shops or currencies.

## Findings

- An October overview took 26.5 seconds in the diagnostic read. Its ledger and cost queries shared a transaction with a 15-second total deadline, so a valid cold read could fail before it reached the response cache.
- The response was about 336 KB, below the existing 512 KB response-cache limit. Increasing that limit was not the fix.
- The raw daily facts were approximately 10 MB. They must be cached separately from the much smaller display response.
- The cost driver used the correct date index, but had to retrieve large ledger rows because status, category and order ID were absent from that index.

## Changes

- Dashboard cold reads retain repeatable-read consistency and may take up to 180 seconds. Other `readSnapshot` callers retain their existing 15-second limit.
- Failed browser reads no longer restart automatically every 15 seconds. An active request retains its progress rail and elapsed-wait message. Repeated refresh clicks with the same selection do not abort and restart it; changing scope still cancels the superseded browser request.
- Response caching retains 15-second freshness, at most 60 seconds of explicitly stale display, five-minute retention and 64 entries per scope.
- Raw facts are currency-neutral daily buckets. Fresh all-company facts can serve narrower shop/group requests. A shop-first visit does not initiate an all-company read.
- Raw facts are compressed, with a 16 MiB decoded ceiling, 2 MiB stored ceiling, 16 retained entries per scope and 60-second Redis retention. Reuse requires freshness and the correct data revision.
- Company/configuration revisions fence removed shops, aliases and configuration changes. Background data revisions mark in-flight older results stale without multiplying the same SQL read. Internal revision markers are removed from public responses.
- JDBC calendar dates are normalized to ISO dates before Redis serialization, preserving the existing settlement-date basis.
- V141 replaces `idx_finance_company_kind_date` while preserving its original prefix and page ordering. It covers the cost-driver fields and scalar ledger amounts. The older narrower index is not retained as a redundant second index.
- The existing `idx_finance_company_dashboard` remains the ledger aggregation index. Testing the new cost index for ledger aggregation was slower, so that query was not changed.
- No Qianyi return-credit logic, profit formula, financial date basis, runtime memory setting, credentials or sync checkpoints were changed. No persistent logs were added.

## Verification

The exact source baselines of all four changed finance class families matched the running bytecode before building the overlay.

- 43 unit tests passed, including precision, expiry, tenant isolation, revision fencing, concurrent refresh coalescing, Redis calendar dates and unchanged profit/return-credit behavior.
- Four disposable MySQL test groups passed: cached versus direct shop/group/currency/chart results, unassigned platform expense filtering, ledger dimension/visibility equivalence, settlement and tenant isolation.
- Repeating V141 is a no-op. Candidate visibility and atomic index-name replacement were tested on a disposable table.
- Browser tests passed for scope/currency changes, query loading, stale response protection, one active request, explicit manual recovery, cancellation and absence of an error retry loop.
- The read-only production comparison used one consistent snapshot. All 2,870 October cost groups were identical: 19,656 ms with the old driver index versus 8,723 ms with the candidate.
- A later representative full-month run measured 18,969 ms cold, 23 ms for the response-cache hit, 978 ms for currency switching and 754 ms for shop switching. Switching caused zero large ledger/cost scans. The projected shop metrics matched a direct SQL computation, comparing grouped rows independent of order.
- Raw facts measured 10,042,476 JSON bytes and 658,951 compressed cache bytes.
- After activation, a September probe loaded classes directly from the running application image, with no class overlay: 29,468 ms cold, 96 ms repeated, 683 ms currency switch and 892 ms shop switch. The switches ran zero large scans, and the projected shop metrics again matched direct SQL. September facts compressed from 7,600,675 to 580,303 bytes.

These are observed backend service timings under the sampled load, not a latency guarantee. A new date range still requires a bounded cold calculation; stale or incomplete costs are not presented as newly complete data.

## Release

Use `scripts/release-finance-cache-reuse.py` with the prepared release directory. It overlays only four class families, V141 and two frontend files over the running images. The verified queue-drain procedure preserves schedules, leases, configuration, ports and checkpoints.

The first promotion attempt failed during the metadata change and restored the prior images. Existing cost queries were active when investigated. The candidate was subsequently enabled successfully; the replacement uses a 30-second metadata-lock allowance after draining. It does not kill user queries or rebuild the full table.

Target images:

- `ai-commerce-platform:finance-cache-reuse-20261102`
- `ai-commerce-frontend:finance-cache-reuse-20261102`

Keep the activation/QA receipts and Compose dependencies. Remove release staging jars, class copies, temporary schemas, private probe environment files and upload packages after live verification.

Activation verified both image identities, application health, frontend identity/gzip hashes, successful Flyway version 141, unchanged runtime configuration and disabled container logging. Schedules and leases were restored by the drain helper without resetting checkpoints.
