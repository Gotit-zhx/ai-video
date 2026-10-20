# Natural Exposure for Video Reference Images

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
