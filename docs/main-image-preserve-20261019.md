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

## Production Result

Source commit `363b26d` was deployed on 2026-10-19 as
`main-image-preserve-20261019`. Runtime, backend, and frontend health checks
passed. The release retained unrelated services and frontend changes.

At 2026-10-19 06:12 UTC, the production template's seven images were all
`READY`. Only the previously failed seventh image was retried. It completed
with `mode: PRESERVE_ORIGINAL`, `complete: true`, an empty prompt, and no
product regions. The first six analysis hashes and all seven original
storage-key hashes matched the pre-retry audit.

This template now schedules six generated product images and one unchanged
original in the seventh position. No product publication was triggered during
verification. Browser verification of the new label requires signing in again
because the service update expired the existing session.

Verification passed 31 focused runtime/frontend tests, 86 focused backend
tests, and the candidate-image native Pi SDK regression suite. The earlier
timeout fix also completed the previously failing third image successfully.
