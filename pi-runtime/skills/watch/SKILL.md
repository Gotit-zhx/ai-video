---
name: watch
version: "0.2.0-platform-detail2"
description: Analyze a video using watch-selected timestamped frames and verified audio transcripts. Supports prepared evidence batches and final storyboard synthesis.
allowed-tools: Read
---
# Watch video analysis

The platform has already run the original watch Python script with scene selection and deduplication. Read every frame listed for this invocation with the read tool; make one batch of tool calls. The image pixels and their exact timestamps are the visual evidence. Do not install tools, download videos, extract frames again, or infer missing images.

Follow the requested mode:
- **Evidence batch**: describe only directly visible actions, people count, object orientation/state, hand contact, framing, and camera movement supported by adjacent images. Return the requested compact JSON. Mark platform end cards, search/follow screens, QR codes, pure text/logo cards and black frames as unusable for production storyboards. Ordinary filmed scenes with overlaid subtitles remain usable; never copy the subtitles. Say uncertain when a detail or motion cannot be determined from sampled frames. Do not invent transitions between distant frames. Separate observed pose, hand contact/support, orientation, action between adjacent frames, camera versus subject movement, cut boundaries, and uncertainty. Do not guess exact angles, force, speed, or anatomical left/right from screen position.
- **Evidence review**: read only the specified conflicting frames and neighboring context. Resolve categorical disagreements from pixels, not majority voting; keep uncertain where the images cannot establish a fact. Use the same evidence schema.
- **Storyboard synthesis**: use the timestamped evidence from all batches together with the images. Resolve overlapping descriptions from the images, preserving real state changes and shot boundaries. Produce exactly the requested nine shots and schema. Output concise fields without repeating the same description across fields.

Transfer reusable motion and composition only. Replace source people/product appearance with generic task subjects; do not copy original identity, clothing, product colors/design, logos, subtitles, or watermarks. Preserve observable wearing/holding, folding/unfolding, front/side/back, grip and functional state. Presentation mode and reliable audio constraints come from the platform request. Use no narration without a reliable audio transcript.

Return the requested result directly. Do not add a separate planning or self-audit essay. Keep source timestamps distinct from the target production timeline. The original standalone setup and extraction instructions are in UPSTREAM.md; these steps are already complete for prepared platform invocations.

Temporal sheets have timestamps supplied in the manifest in left-to-right order. The first tile is the anchor frame; later tiles support motion only. Compare stable background features to separate camera movement from subject movement. Never interpret a hard cut as pan/zoom. Stable background supports a fixed-camera finding; state the specific limitation when evidence is insufficient. Original single-frame images remain the storyboard references.
