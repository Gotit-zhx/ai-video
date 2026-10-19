# Banana Default Image Generation

Final user scope: make `gemini-nano-banana-2.1` the organization's default
image model, with two existing dedicated exceptions:

- Transparent print extraction and variation remain on configured GPT Image 2.
- Watermark removal remains on configured Grok image and driver routes.
- All other new image requests, including style posters, video references,
  assistant images, product scenes and detail images, use the default Banana.
- Video-generation and text/vision-analysis models are unchanged.

The configured native `generateContent` URL previously received an appended
`/images/edits`, causing an upstream 404. The runtime now routes Gemini image
requests to the native endpoint using ordered inline references, the original
prompt, `responseModalities` and aspect-ratio/resolution settings. GPT/Grok
wire formats, transparent-PNG guards, durable operation records and recovery
budgets are retained. Native response bytes are returned without redrawing.
Video's 2160x3840 request maps to 9:16 / 4K; ordinary posters use 1:1 at the
provider's default resolution.

Before implementation, an independent native two-reference request returned
a fully decoded 1024x1024 PNG in 39.1 seconds, with one provider call.
This establishes model availability, not a completed platform rollout.

Default Banana has no Grok fallback. New mockups honor default Banana rather
than their older Grok preference. Newly prepared style slots use the current
default while retaining their product source. Already generated assets and
in-flight paid operation identities are not rewritten. Video downloads of
older paid results resolve that result's original configured image model.

Deployment uses exact live image overlays for the runtime and three backend
class families. Unrelated JAR entries and containers, including the frontend,
are retained. The organization-specific default flag is switched separately
after platform smoke tests, with a private backup and transactional guards.
Neither image keys nor endpoint URLs are overwritten, and GPT/Grok remain
active for their dedicated uses.

Regression checks passed: 155 Java tests and 198 runtime tests, including native
text-to-image and 1/2/4-reference requests, 4K parameter mapping, saved-response
reuse without another POST, invalid output, legacy download credentials and
the dedicated print/watermark exceptions.
