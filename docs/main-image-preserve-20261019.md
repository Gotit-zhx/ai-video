# Product-Free Template References

## Behavior

Poster analysis now distinguishes `REPLACE_PRODUCT` from `PRESERVE_ORIGINAL`.
Legacy analyses without a mode remain product-replacement analyses.

Only clearly product-free information pages, such as after-sales instructions,
can be preserved. They require `complete: true`, empty product regions, and an
empty generation prompt. Ambiguous or unreadable product photos remain failures.
Existing product identification, region validation, and generated-image checks
are not relaxed.

Preserved slots retain the exact archived reference bytes and their original
position. They create ready output records without a source-product download,
image-model configuration, paid generation, or generated-image review. Publishing
checks those records against the current template's storage keys and preservation
marker. It does not fabricate successful AI quality checks for original images.

Template edits still apply directly and invalidate changed slots. No template
snapshot or database migration is introduced. Opting out still generates no
additional style images.

The UI labels original references and omits their prompt editor. Bulk counts
distinguish generated images from preserved originals.

## Verification

Focused runtime/frontend tests cover the discriminated result and display.
Backend tests cover all-original and mixed templates, no-model execution,
ordering, stale slot changes, provenance checks, and normal publishing behavior.

Release helper: `scripts/release-main-image-preserve.py`. It overlays four
backend class families, one runtime module, and the template UI script on the
exact current images. It updates only the template script URL inside the live
HTML, preserving other concurrent frontend releases.
