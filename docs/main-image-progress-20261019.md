# Main Image Analysis Progress and Pi SDK

## Changes

- Upload progress uses actual XHR bytes. After transfer, server validation/save
  is a separate indeterminate phase with elapsed time. Explicit retries retain
  the original idempotency key. Company switches abort the local request and
  reject late callbacks.
- List/detail views show processed, successful, failed, analyzing and queued
  counts, active image numbers, and per-image progress. Polling remains active
  after a partial failure; prompt drafts and cursor positions are preserved.
- Poster extraction and generated-poster review now run through the existing
  Pi Agent SDK and its native streaming provider. Each extraction receives one
  reference image. Review retains its exact three-image order.
- SDK lifecycle events report waiting, streaming and result validation, with
  actual output character counts and elapsed seconds. No reasoning content is
  exposed or persisted, and no invented percentage is assigned to model work.
- Internal NDJSON progress is persisted in the existing image analysis JSON.
  Validated final results replace transient progress. No migration is required.
- Executor-queued images remain `QUEUED`, switching to `ANALYZING` only when a
  worker starts. Timeouts, incomplete output, model authentication and rate
  limits produce specific safe failures rather than the old generic message.
- Model calls have a 240-second total bound and 90-second inactivity bound.
  SDK retries are disabled; ambiguous failures are not automatically replayed.
  Existing failed images require the existing retry action, not another upload.

## Verification

- 139 focused frontend/runtime unit tests passed.
- Backend main-image, publishing and capture regression tests passed, including
  live NDJSON transport against a local HTTP fixture and queue-state tests.
- The actual installed Pi SDK passed the loopback-only streaming integration
  test inside the candidate runtime image with external networking disabled.
- Browser checks covered 7-image upload/save states, two analyzing plus five
  queued images, real event counters, and terminal partial failure.
  Desktop and 390px mobile screenshots were inspected; mobile page width and
  dialog scroll width equal their respective viewport/client widths.
- No paid model calls or marketplace publications were made by these tests.
  Successful extraction of the user's production references is not asserted
  by the mocked model integration.

## Deployment

Use `scripts/release-main-image-progress.py` to pack/build/deploy/verify an
exact-image overlay. It compares all affected live files and original class
families, preserves unrelated JAR entries, checks idle workers, and retains
the complete Compose chains, environment, mounts, ports and networks.

Release directory: `/opt/heyi/releases/main-image-style-progress-20261019`.
Candidate tags use `main-image-progress-20261019`; rollback image tags append
`-before`. Private receipts and previous JAR stay on the host. Existing
templates, image storage, finished analyses and failed-task records are not
rewritten by deployment.

## Deployment Result

Deployed on October 19, 2026 from source commit
`dac291c7d95bb27366772566944af65a75f538f0`.

- Application, runtime and frontend use the `main-image-progress-20261019`
  image tags. HTTPS asset hashes, the application JAR hash, runtime source
  hashes and all three health checks passed.
- Existing Compose configuration and unrelated running containers were
  preserved. No migration or task-state rewrite was performed.
- The user's seven previously failed images remain available for explicit
  retry through the new SDK path without reuploading.
