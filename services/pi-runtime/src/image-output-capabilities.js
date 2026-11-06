// Keep request routing and wire parameters on one capability list. Model aliases
// outside this list have no confirmed native transparent PNG contract.
const NATIVE_TRANSPARENT_PNG_MODELS = new Set([
  "gpt-image-1", "gpt-image-1-mini", "gpt-image-1.5", "gpt-image-2", "gpt-image-2-2026-04-21",
  "gpt-image-2.5-sunburst", "gpt-image-2.5-sunburst-2026-09-08",
  "gpt-image-2.5-flare", "gpt-image-2.5-flare-2026-09-08"
]);

export function supportsNativeTransparentPng(config) {
  return (!config?.apiFormat || config.apiFormat === "OPENAI_COMPATIBLE")
    && NATIVE_TRANSPARENT_PNG_MODELS.has(config?.modelId);
}
