# Video Recovery Verification, 2026-10-15

## Original Request Restored

The unverified omission of caller-specified `input_fidelity` was reverted.
Production now runs `heyi-pi-runtime:video-fidelity-restored-20261015`.
Only that omission was reverted; environment, mounts, and other containers
were checked unchanged.

The user subsequently changed the upstream channel. An isolated container
then completed a real garment edit with the production transport:

- Model: `gpt-image-2`, endpoint `/v1/images/edits`.
- Actual uploaded garment JPEG, singular multipart `image` field.
- `size=2160x3840`, `input_fidelity=high`.
- `stream=true`, `partial_images=0`.
- Exactly one provider POST, no model fallback or transport retries.
- Simplified diagnostic prompt, not the complete task-generated prompt.
- PNG: 2160 x 3840, 9,389,695 bytes, visually inspected.
- SHA-256: `02af33c1558e6ffe92bdcade7cd73875e84db6fa5683813b6a69336de35f6d0e`.
- OSS private upload, direct read, and signed read returned identical bytes.

Private evidence is on the server under
`/opt/heyi/releases/video-pipeline-20261015/live-proof/original-new-channel-1158`.
No model keys, session cookies, or signed URLs are included in this repository.

Earlier controls received `invalid_image_input` or
`image_task_failed_or_requires_review`. Changing the multipart field did not
produce an image. These experiments were isolated and were not deployed.
The successful original request after the channel change does not support
blaming `input_fidelity` or the singular `image` field.

## Recovery Controls

`observe-video-recovery.py` freezes the recent failures, requires the real-image
and OSS evidence before dispatch, verifies existing owner sessions, and invokes
normal application APIs. Failed scripts use `/retry`, not READY-only `/reanalyze`.
Tasks wait for their failed script dependencies and other runs by the same owner.

Every target has a durable dispatch receipt written before POST. Uncertain and
rejected submissions are not automatically posted again.
Unknown/completed image receipts block regeneration. Existing attempt IDs and
spent counts remain intact. An explicit, backed-up single recovery allowance
can be granted only to an exhausted, closed two-attempt ledger for this
user-authorized recovery.

The first allowance write did not preserve the application's file owner, so
two tasks stopped before reserving or submitting a new model request. Ownership
was corrected without changing ledger bytes. Atomic replacement now preserves
owner, group, and mode. One guarded pre-submission resume is allowed only after
checking the exact unreadable-ledger failure, unchanged two-attempt backup,
unused allowance, and original terminal receipts. The original API receipt is
retained, and a `presubmitRepairAttempted` marker prevents repetition.

Recovery completion must be judged from actual task/script results, not queue
acceptance or the isolated proof alone. Old inaccessible `oss://hoyee/` assets
are not silently redirected or regenerated.

## Unresolved Script Driver

All three script retries still failed with `CHAT_INVALID_TOOL_CALL`.
A separate one-POST tool-only diagnostic using the configured `gpt-5.5`
connection received four `read` calls, each with `arguments=""`, followed by
`finish_reason="tool_calls"`. No frame-read tool was executed by that diagnostic.
Evidence is in the server's `video-pipeline-20261015/watch-tool-proof` directory.
The empty arguments must not be repaired by inventing frame paths. The script
driver's streaming tool-call contract remains unresolved; the image/OSS proof
does not establish that the entire video pipeline is healthy.

## Tests

- 177 focused runtime tests passed in an isolated container with networking
  disabled and production dependencies. Providers in these tests are mocked.
- Ten recovery-control tests cover authorization, API cookie configuration,
  correct script retry routing, actual ledger locations, unknown receipts,
  preservation of spent counts/history and file metadata, guarded pre-submission
  recovery, and prevention of duplicate dispatch.
- The image generation and OSS checks above were real network operations,
  separate from the mocked regression suite.
