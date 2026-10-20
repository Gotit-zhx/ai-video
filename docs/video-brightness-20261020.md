# Natural Exposure and Fabric for Video Reference Images

## Changes

- Replace the blanket ban on window light, fill light and exposure changes with
  natural, adequately exposed smartphone photography. Background shadow detail,
  product colors, print details and natural skin tones must remain visible.
- Preserve scene type, day/night setting, spatial relationships and plausible
  light direction. Avoid crushed blacks, overexposure, bleached skin, white
  backgrounds and cinematic/HDR effects.
- Apply the same exposure policy to model images, product scene images,
  generated storyboard guides and final storyboards.
- Respect `SCRIPT_ORIGINAL` independently of real-frame availability. Without
  source frames, use the saved script's scene and image-prompt environment
  descriptions as a textual fallback. Do not call an extra analysis model or
  pass old-product source frames into the primary-image model.
- Keep `AI_DESIGNED` as redesigned scenery even when real action frames exist.
  Storyboards retain identity, product, action and framing constraints without
  inheriting underexposed reference images.
- Preserve existing generated product assets when prompts change. Explicit
  regeneration continues to reset the existing workflow as before.

No model configuration, retry budget, validation policy, video prompt, storage
policy or frontend is changed. No existing task is regenerated for verification.

## Verification and Release

Focused tests cover original/AI-designed backgrounds with and without source
frames, script-lighting evidence, missing-scene fallback, consistent exposure,
product and skin color protection, and reuse of saved product results.

All 101 focused tests passed, including 18 new lighting/background cases.
The broader first-frame lifecycle suite has two existing failures:
`unknownIncludingImageModel502NeverTriggersAutomaticPaidVideoOrImageRetry` and
`lowConfidenceSingleScenePausesInsteadOfPublishingOrRegenerating`. Both reproduce
unchanged on baseline commit `1ef32ee`; this release does not alter that behavior.
The release script passes Python syntax validation and `git diff --check` passes.

Deployment replaces only the `AiVideoImageProcessor` class family in the exact
live app image, checks baseline hashes, and preserves unrelated JAR entries.
The runtime and frontend are not restarted. Activation waits for active image
work, video submissions/reviews and video polling leases to be idle. Accepted
provider-side video jobs remain persisted and resumable.

## Production Rollout

Deployed commit `a69fa6d` on October 20, 2026 with release directory
`/opt/heyi/releases/video-brightness-20261020-a69fa6d` and active app image
`ai-commerce-platform:video-brightness-20261020`.

- Baseline processor hashes matched the live app. Only the reviewed processor
  class family changed; unrelated JAR entries remained byte-for-byte identical.
- Idle checks passed before the graceful app restart. Post-deployment checks
  confirmed a healthy app, the expected JAR hash, unchanged runtime/frontend,
  and HTTP 200 from the public site.
- Existing model and storyboard image IDs were preserved. The accepted video
  provider job remained on attempt 1; no generation, retry or paid test was
  submitted as part of this rollout.
- New generations and explicit regeneration use the updated exposure prompts.
  Existing generated images are not automatically replaced.

Release evidence is recorded in `manifest.json`, `prepared.json`,
`activation.json` and `verification.json` under the release directory.

## Dayan OSS Readback

The commerce main-image archive already routes new writes to Dayan OSS.
Production has OSS enabled with Dayan bucket `test-hoyee` and endpoint
`https://oss.cloud.dayan.cloud`; the legacy bucket remains available only for
old-location reads. This release does not change storage configuration.

The screenshot's workflow `1b8311d5-6394-436e-940d-1f4c3d21910f` completed with
all 9 images READY. The read-only `CommerceImageOssLiveCheck --read-existing`
check used the production OSS SDK configuration and verified:

- All 9 private objects were readable from Dayan OSS.
- All content SHA-256 checks matched and all images decoded successfully.
- Total verified image data was 13,313,148 bytes.
- The check performed zero writes and found zero local archive image files.
- A separate post-deployment database check found zero remaining duplicate
  result payloads after archive acknowledgement.

Stable archive references use
`oss://test-hoyee/heyi/commerce-images/<tenant-sha256>/<content-sha256>`.
The authenticated `/api/v1/product-main-image-results/{id}` route reads image
bytes from OSS, so a `hoyee.org.cn` image URL is not evidence of local storage.
New archive writes must pass OSS readback verification before success; an OSS
failure does not fall back to local image persistence.

Readback evidence is recorded at
`/opt/heyi/diagnostics/main-image-dayan-20261020/report.json`.
This check verified storage and backend reads, not browser preview rendering.

## Room Daylight Follow-Up

The first exposure policy required readable shadow detail but did not explicitly
prioritize lighting throughout the background. The follow-up strengthens the
shared policy to natural-exposure V2 and the model prompt to REALISM V5:

- Prioritize soft ambient illumination on walls, furniture, floors and the back
  of the room, avoiding a bright subject against a dull, dark background.
- For daytime or unspecified ordinary interiors, use broad diffuse daylight
  and bounced light throughout the room. Keep shadow edges soft while retaining
  contact shadows, material texture and spatial depth.
- Keep people and products normally exposed instead of brightening the entire
  image. Preserve light garment colors, folds and prints, dark product colors,
  and natural skin tones.
- Preserve existing windows, room layout and wall colors. Do not invent hard
  sunbeams, turn a night scene into daylight, or add a gray haze to lift shadows.
- Share the policy across model images, product scene images, generated guides
  and final storyboards. Existing assets and generation budgets are unchanged.

All 103 focused tests passed with zero failures, errors or skips, including
20 lighting cases covering every prompt path and explicit night-scene evidence.
Release script syntax validation and `git diff --check` also passed.
The follow-up uses the separate image tag
`ai-commerce-platform:video-room-daylight-20261020`.

The room-daylight update was deployed from commit `e599b63` at
`/opt/heyi/releases/video-room-daylight-20261020-e599b63`. Idle checks passed,
the reviewed app JAR was verified healthy, and runtime/frontend containers
remained unchanged. The rollout did not submit image or video generations.

## Natural Fabric Follow-Up

The previous shared material prompt explicitly requested continuous wrinkles
in every pose and repeatedly emphasized softness and drape. It did not request
thin fabric, but lacked an explicit source-relative opacity and stiffness guard.

The REALISM V6 model prompt and shared natural-fabric V1 policy now:

- Keep garments normally tidy and naturally relaxed, with sparse soft folds
  only at actual tension, bending or contact points.
- Preserve the source garment's thickness, weave density, opacity, stiffness
  and drape. Brighter environmental light must not make opaque fabric sheer.
- Preserve intentional pleats, distressing and sheer or mesh designs without
  adding wrinkles, wear, pilling, fading, damage or washed-out sagging.
- Limit background signs of use to furniture and room furnishings.
- Retain localized tension and gravity during explicit corner-lifting actions
  without spreading dense creases throughout the garment.

All 105 focused tests passed with zero failures, errors or skips, including
22 lighting/fabric cases. Tests cover primary images and final storyboards,
intentional source details, local grip physics, and reuse of existing outputs.
The separate deployment tag is
`ai-commerce-platform:video-natural-fabric-20261020`.

The fabric update was deployed from commit `f75ff75` at
`/opt/heyi/releases/video-natural-fabric-20261020-f75ff75`. Queue/lease idle
checks, the reviewed JAR hash and app health all passed. Runtime/frontend
containers remained unchanged and the public site returned HTTP 200.

## Requested Image Preview

After deployment the user explicitly requested a new image run using the latest
video task. The source task was `8909ffc6-d52c-48dd-9ce9-464632be6013`, created on
October 20, 2026 at 03:02:25 UTC, with item
`a84613f2-388a-455e-85c8-f407a831a01f`, named
`ScreenShot_2026-09-10_182615_395`.

Because its video was still generating, the test used an isolated image-only
task rather than deleting its existing image assets or altering its video job:

- Preview task: `4cb9b2da-e0fa-484b-874c-293f77ccc9d9`.
- Name: the original task name followed by ` - V6 preview 20261020`.
- Source image SHA-256, script JSON, generation mode and background mode matched
  the original task before submission.
- Only `start-images` was called, once. Automatic video start remained disabled
  and the preview has zero video-generation jobs.
- All original asset IDs and the original provider video task ID were preserved.

The preview reached `IMAGE_READY` at 100%. Model image, storyboard guide and
final storyboard each recorded one generation attempt; the first frame was
derived from the finished grid. The normal application analysis/validation
behavior was unchanged, and no extra quality-based generation was requested.

Both final image records use `gemini-nano-banana-2.1`, include the new background
daylight and natural-fabric policies, and point to Dayan bucket `test-hoyee`:

| Asset | ID | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Model image | `d3a47484-87d6-4592-a9e2-3c34d3a97ba2` | 19366239 | `73bbb7f8df28130a23583c7ff29f04adf0eb3acbef2a8fad23c762e1d39b1b9d` |
| Storyboard grid | `c576eb74-0b44-4fd4-a644-a6d696da0153` | 19982512 | `40de40bd667825b226a336503fcfda6d9335161323f049afcca34b13ac4fe187` |

The model result is a 3072 x 5504 PNG. Results are exported without resizing,
retouching or post-generation exposure changes. The one-off guarded helper is
`scripts/preview-latest-video-images.py`; its state is recorded in
`/opt/heyi/diagnostics/natural-fabric-preview-20261020/preview.json`.

The exported model and grid PNG hashes match their stored output bytes. Visual
inspection found black outlines on some hands and arms in the final grid.
This is a residual output-quality issue, not a claim of visual acceptance.
Both outputs are returned unchanged; no additional paid redraw or video
generation was submitted to correct it.

## Photographic Edge Follow-Up

Inspection of the actual preview guide confirms the source of the hand outlines:
it is a flat, outlined mannequin image. Its hand and arm lines match the final
grid's retained outlines, especially cells 04-08. The guide PNG SHA-256 is
`7c6585f37a0dfdb3b76474fbf6642b1b474332e00a111e3ea74c0683ee3a3708`.

The phrase permitting a neutral mannequin "or a simple realistic human pose
outline" predates the lighting and fabric changes. Both the original task and
the preview stored that wording. The recent fabric change did not modify the
guide's body instructions; the lighting changes affected its illumination.
The directly observed failure is guide-style leakage during identity transfer,
not evidence that increasing daylight draws black borders.

The fix removes the optional outline representation, specifies a shaded solid
neutral mannequin without drawn strokes, and clarifies that replacement occurs
in the next image step, not later in video generation. Final transfer must
reconstruct the entire physical body, including all visible hands, from the
model's photographic skin and shading rather than recoloring placeholder pixels.
It also handles previously saved guides that already contain outlines.

A shared photographic-edge rule applies to primary images and both final
storyboard paths. It forbids added drawn borders on physical subjects while
explicitly preserving original print artwork, lettering, logos, seams, piping,
hair and natural contact shadows. It does not apply print-preservation language
to the neutral guide. Background daylight and natural-fabric policies remain.

All 107 focused tests passed, with zero failures, errors or skips, including
24 lighting/fabric/edge cases. No extra validation request, postprocessing,
automatic redraw, budget change or saved-asset invalidation is introduced.
Prompt constraints are not a mathematical guarantee of model compliance.

The requested visual follow-up is limited to one explicit storyboard
regeneration on the isolated preview task, retaining its existing model image.
The helper checks image-ready status, the exact task/model IDs, disabled
automatic video start, and unchanged source task assets/provider ID. It records
the submission before calling the API so transport uncertainty cannot cause
an accidental duplicate request.
