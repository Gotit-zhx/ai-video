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

## Production Rollout

Activated on October 19, 2026, with the organization default switched at
09:51:17 UTC (17:51:17 Asia/Shanghai). Release directory:
`/opt/heyi/releases/banana-default-20261019-86ec17d`.

Both paid smoke requests used the exact new runtime image in an isolated
container, two real references, and one provider call with zero retries:

| Request | Elapsed | Returned PNG |
| --- | --- | --- |
| Style poster | 72.4 seconds | 1024x1024, 1,626,535 bytes |
| Video reference, requested 9:16 / 4K | 83.8 seconds | 3072x5504, 21,637,030 bytes |

Both PNGs were fully decoded locally. These timings are measured samples,
not a latency guarantee. No AI acceptance review or redraw was performed.
Reports and original outputs are retained under
`/opt/heyi/diagnostics/banana-platform-ready-20261019/` and
`/opt/heyi/diagnostics/banana-video-ready-20261019/`.

A further HTTP regression covers a 29-million-character inline image result
through the production video gateway constructor, including recovery without
a second generation POST. The seven focused Java test classes were rerun:
125 tests passed with zero failures, errors or skips.

Activation waited for template analysis and all guarded queues to become idle.
The isolated smoke container was removed before deployment. Runtime/backend
hashes, service health, unchanged frontend and unrelated container identities
were verified after activation. The public website returned HTTP 200.

The guarded database transaction changed only IMAGE default flags and their
timestamps. Banana is the sole default; GPT Image 2 and Grok remain active.
Keys, endpoints, DRIVER models and VIDEO models were verified unchanged.
The release's `default-switch.json` records the committed change and private
backup location. No additional model-selection UI was introduced.
