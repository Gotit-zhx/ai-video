# Poster Analysis Startup Timeout

## Cause

The runtime applied its 90-second streaming-inactivity timer immediately when
the request began. A provider that queued or prepared the first response for
longer than 90 seconds was aborted before it returned any content. This was
observed on reference image 3 after switching to the configured `gpt-6.1-sol`.

## Change

- Allow 180 seconds for the first nonempty text or reasoning delta.
- After content starts, retain the 90-second inactivity timeout.
- Retain the 240-second total limit and pass it explicitly to the native SDK.
- Empty events and application progress heartbeats do not extend deadlines.
- Continue elapsed-time progress while waiting; validation no longer resets it.
- Timeout and cancellation settle the caller even if SDK cancellation stalls.
- Keep SDK retries disabled, never switch the user's model, and never
  automatically replay a request with an ambiguous upstream outcome.

## Verification And Release

Unit tests exercise slow startup, live reasoning activity without disclosure,
silent providers, empty events, partial-stream stalls, the hard deadline,
external cancellation, late events, and elapsed-time preservation.

The native SDK integration tests run against loopback HTTP fixtures in the
candidate image with external networking disabled. They cover delayed response
headers, a silent upstream, a stalled partial stream, and normal validated JSON.

`scripts/release-main-image-timeout.py` overlays only the analysis module on the
exact running runtime image, verifies the old source hash, checks for active
tasks, preserves the complete Compose chain and configuration, and retains a
rollback image. The application, frontend, model configuration, stored source
images and successful analyses are not rewritten.
