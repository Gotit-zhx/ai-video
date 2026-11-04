# Captured merchant SKU naming — 2026-11-04

New one-click captures use `COLOR-STYLE-SIZE-RANDOM`, for example
`BK-K583204-M-8F3A6C2D`. The style has one random uppercase A–Z letter and six
random Arabic digits. The final segment has eight random uppercase hexadecimal
characters. All segments contain only ASCII letters/digits; separators are
hyphens and there are no spaces.

Known colors use fixed abbreviations:

| Color | Code | Color | Code |
| --- | --- | --- | --- |
| Black | BK | White | WH |
| Pure white | PWH | Gray / grey | GY |
| Light gray | LGY | Dark gray | DGY |
| Red | RD | Blue | BL |
| Navy | NV | Light blue | LBL |
| Sky blue | SBL | Green | GN |
| Dark green | DGN | Yellow | YL |
| Pink | PK | Purple | PU |
| Orange | OR | Brown | BR |
| Beige | BG | Khaki | KH |
| Cream | CR | Ivory | IV |
| Burgundy | BD | Silver | SV |
| Gold | GD | Multicolor | MC |

Chinese and supported Indonesian labels share these codes. Already abbreviated
labels are accepted. Unknown English labels receive a stable short token with
a digest, rather than being guessed as a known color. Missing or unrecognized
non-English colors use `UNK`; original labels remain in the SKU attributes.
Unknown colors can be corrected in the existing draft editor. The maximum
generated SKU length is 36 characters.

## Identity and persistence

The company-scoped `product_sku_styles` table records a pattern fingerprint and
its allocated style code. Unique constraints prevent both duplicate allocations
for one pattern and accidental reuse of a code by different patterns in the
same company. Short independent transactions resolve concurrent captures and
random-code collisions without holding allocation locks during image/model work.

A pattern is identified from the canonical source product and explicit
pattern/print/design/style attributes. A known color with a numbered suffix
such as `Black/1` or `White-1` identifies source pattern 1 across those colors,
including when only one color is present. Distinct source pattern numbers stay
separate. Without a pattern axis, a source product's color/size variants share
one style code. Unnumbered options in a numbered matrix remain separate.
An explicit `Pure white` option (PWH) without a pattern attribute receives a
separate source-option style. Live source images for both acceptance products
showed this option was an unprinted shirt. An explicit matching pattern
attribute still links it to that verified pattern group.

This is source-pattern identity, not visual similarity inference between
unrelated products. Different source products are not automatically merged
because their titles or generic pattern names happen to match.

Saved external SKU IDs associate a recapture with its previous merchant code.
Reordering variants or updating price/stock keeps the complete code stable.
Changing color/size updates the corresponding code. Original seller SKU values
remain in `source_json._merchantSkuNaming`, and external SKU IDs, prices, stock,
images and attributes retain their original values.

Only the capture/recapture path receives the new naming policy. Existing
published listings, protected edited drafts, and generated-artwork workflows
are not silently renamed. Publishing providers use the saved merchant SKU.

## Release

Initial backend image: `ai-commerce-platform:product-sku-naming-20261104`.
Helper: `scripts/release-product-sku-naming.py`.
Directory: `/opt/heyi/releases/product-sku-naming-20261104`.

The overlay contains only ProductCaptureService, ProductSkuNaming,
ProductSkuNamingService and additive migration V145. Unrelated JAR entries
must remain byte-identical. The frontend and all runtime configurations remain
unchanged. The existing queue drain/restore and image compaction guard apply.

Validation: 73 naming/capture checks, 20 existing marketplace publishing checks,
and three disposable MySQL persistence/collision/concurrency checks. Seven
unchanged baseline class files matched production bytecode before the overlay.
The disposable test database and account were removed.

Actual marketplace publication is a separate acceptance step. Passing local
provider tests does not prove a live listing was created or approved.

Production activation completed after an existing TikTok publication finished.
The first deployment attempt correctly deferred while that request was running;
it was not interrupted or resubmitted. V145, the exact JAR hash, HTTP health,
anonymous access protection, unchanged runtime configuration and frontend,
and restoration of financial schedules/checkpoints passed after activation.
No persistent logging was added. `activation.json`, `prepared.json`,
`drain-restored.json` and `staging-cleanup.json` retain the small release receipts.

The active backend and verified `finance-qianyi-receipt-20261103` and
`finance-calendar-month-20261103` rollback images are retained. Frontend rollback
retention is unchanged. Owned class staging, the temporary JAR build directory
and the loose migration copy were removed. Preserve this release's Compose
overrides and its imported helper dependency chain.

## Follow-up activation and real marketplace acceptance

Current backend: `ai-commerce-platform:product-sku-pattern-option-20261104`.
Helper: `scripts/release-product-sku-pattern-option.py`.
Directory: `/opt/heyi/releases/product-sku-pattern-option-20261104`.

The pure-white grouping fix passed 75 naming/capture tests. The baseline policy
class matched the active bytecode before replacement. The overlay changed only
ProductSkuNaming.class; every other JAR entry remained byte-identical. Image
compaction guard, queue drain/restore, schema, HTTP health, config equality and
unchanged frontend checks passed. No persistent logs were enabled.

After the user logged in, two untouched CAPTURED drafts (version 0, no previous
approval, image job, publish workflow/audit or USER field edits) received the
new naming through the production naming service in one guarded transaction.
Only merchant codes, their source metadata, normalized SKU copies and draft
version/update timestamps changed. All source facts, raw data, source SKU IDs,
images, prices and stocks were compared before/after. Each draft has 16 SKUs:
12 printed variants sharing one style and four plain white sizes sharing a
second style. Reapplying naming preserved every complete code.

Both products were then submitted once through the authenticated normal upload
workflow, with the shop's existing defaults and no extra style-image template:

| Platform / shop | Capture | Platform product ID | Remote state | SKU verification |
| --- | --- | --- | --- | --- |
| TikTok / Sweet Me | ccf65d96-2fc1-4f90-8776-2972eb85ad1a | 1738362825316140719 | DRAFT | 16/16 seller_sku values equal; unique |
| Shopee / CurveLuxe PH | 45560ef2-7313-4d02-a48b-15c29557d70b | 48671198380 | UNLIST | 16/16 model_sku values equal; unique |

These are actual platform products in the configured pending-listing state,
not publicly available listings. TikTok verified through Get Product; Shopee
verified through Get Model List and Get Item Base Info. Audit IDs are
`69fe3872-815d-4826-a556-acb80c2aadf3` and
`dd6fde66-5f0a-44fc-985b-4ae69ea53246`, respectively.
Examples: `BK-P095998-L-3615C3E4` and `BK-R133988-L-3507FC05`.

Small receipts: `draft-sku-verification.json`,
`live-publication-verification.json`, `activation.json`, `prepared.json`,
`drain-restored.json` and `staging-cleanup.json`. Temporary classes/JAR staging
and the maintenance runtime/private environment were deleted.
Retain the active backend and the verified `product-sku-naming-20261104` and
`finance-qianyi-receipt-20261103` backend rollbacks. The now-unreferenced
`finance-calendar-month-20261103` backend image was removed; its release helpers
remain because they are required by the current deployment chain. Frontend
rollback retention is unchanged.
