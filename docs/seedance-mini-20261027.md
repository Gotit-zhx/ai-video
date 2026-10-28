# Seedance 2.0 Mini release

Deployed October 27, 2026 as `seedance-mini-20261027`. New general, product,
direct-reference and nine-frame video submissions use
`doubao-seedance-2-0-mini-260615`. Legacy QUALITY and SPEED selections resolve
to the new model. Old accepted provider tasks are queried by their original
provider task IDs; historical job model IDs are not rewritten.

The user-supplied request options are fixed at `720p`, `ratio=adaptive`,
`duration=-1`, `generate_audio=false`, `watermark=false`,
`return_last_frame=true`. Prompts and uploaded reference content are retained;
media URLs are nested objects with explicit reference roles. Each submission
uses an idempotency key reused across its HTTP retries. Completed responses
retain video URL, usage, actual cost and the raw last-frame URL. Expired or
cancelled tasks terminate instead of polling forever.

Both organizations retain their existing `https://api.dayan.design` endpoint
and encrypted credentials. Each has one active default Mini configuration;
duplicate legacy routes were disabled, not deleted, preserving historical
task polling. The existing unique endpoint/model index prevented renaming
both old routes to the same model; the first configuration transaction rolled
back without changing data, then the deduplicated transaction succeeded.

Validation:

- 35 related tests pass: request/response contract, nested media references,
  explicit booleans, idempotent retries, historic task polling, both old
  route selections, automatic duration, tenant isolation and concurrency.
- Frontend syntax and DOM form selectors pass. The old speed/resolution
  selectors are removed and the asset version is updated.
- Existing upstream credentials return HTTP 200 on `/v1/models`, and Mini
  is visible for both distinct credential sets.
- No paid video was generated as part of validation.
- Exact production JAR overlay preserves all unrelated entries, including
  finance classes. Health, frontend hashes (gzip and identity), anonymous
  finance denial, unchanged schema and preserved container configuration pass.
- The broader first-frame lifecycle suite contains two pre-existing failures
  concerning permissive UNKNOWN/low-confidence review outcomes. The review
  method is unchanged; both failures were reproduced with the pre-change service
  compiled into an isolated test directory. They are documented separately from the passing model
  switch tests and were not concealed by changing review policy.

Production snapshot/rollback files:
`/opt/heyi/releases/seedance-mini-20261027/`.
Restore `rollback-video-models-deduplicated.sql` together with the saved
pre-release images if rolling back the model contract.
