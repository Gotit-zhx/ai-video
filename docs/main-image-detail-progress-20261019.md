# Style Image Workflow Detail Progress

## Cause and Change

The workflow API previously returned only a template name, completed count, and
ready image URLs. Product details displayed a generic generating label, while
bulk details displayed only aggregate progress and finished thumbnails.

The existing workflow response now includes ordered, current-template image
states: waiting, queued, generating, saving, reviewing, correcting, ready,
preserved, failed, and stopped. Reviews persist bounded public Pi SDK progress
in the existing `review_json` field. Completed quality checks replace this
transient data. No schema migration or template snapshot is introduced.

Read-only progress requests do not call a model, retry work, read image bytes,
or expose prompts, provider responses, credentials, or reasoning text. Current
template fingerprints continue to exclude stale output. A failed workflow
marks unfinished images stopped rather than displaying ongoing activity.

Product detail, the publish dialog, and bulk history details share one
renderer. Existing polling updates stages even if the completed count has not
changed. The UI displays actual generation-stage elapsed time and review event
counters, ready thumbnails, preserved originals, and failure messages. It
retains loaded media on refresh. Product detail also reports a disconnected
progress read while its existing bounded-delay reconnection continues.
Cached terminal style workflows receive one read-only refresh when their
detail view opens, so old records gain current per-image results without replay.

## Verification

- 194 product frontend unit and integration tests passed, including a
  same-count stage transition through the real bulk polling implementation.
- 91 focused backend tests passed for styles, template analysis, original
  preservation, asset validation, and publication workflows.
- Local browser fixtures verified product detail and bulk history detail on
  desktop and a 390px mobile viewport. Seven rows rendered, stage and elapsed
  values updated, images loaded, and no horizontal overflow was observed.
- No paid generation or marketplace publication was started by these tests.
- The production Dockerfile asset test passes. The separate, pre-existing
  Dockerfile.local asset test reports missing `hr-workspace.css`; this release
  neither changes that Dockerfile nor uses it for the production overlay.

## Deployment

`scripts/release-main-image-detail-progress.py` packs the changed frontend
assets and the `ProductMainImageStyleService` class family. It validates the
exact prior files and classes, patches only two asset URLs in the current
production HTML, and preserves all other JAR entries and container settings.
The Pi runtime and unrelated containers are not replaced.

Deployment requires idle background work. The user's in-flight style task was
left running during implementation. It subsequently failed its first image's
correction at the image service, before publication. No failed image or
workflow was retried or rewritten as part of the progress fix.

## Production Result

Deployed source commit `f9d8a13` on 2026-10-19. Release directory:
`/opt/heyi/releases/main-image-detail-progress-20261019-f9d8a13`.
The application and frontend use the `main-image-detail-progress-20261019`
image tags. HTTPS assets, backend JAR, health checks, complete container
configuration, and unchanged unrelated containers all verified successfully.
The Pi runtime was not restarted.

At 2026-10-19 07:01 UTC, pre/post deployment fingerprints matched for the
template, all seven template images and analyses, all seven style jobs and
their generated/review data, and the selected-style publication workflow.
No task retry, data rewrite, paid generation, or publication was performed by
the deployment. Production authenticated UI inspection remained unavailable
because the existing browser session had expired; layout and live polling
were verified using the local isolated fixture.
