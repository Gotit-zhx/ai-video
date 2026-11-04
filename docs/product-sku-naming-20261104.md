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

Backend image: `ai-commerce-platform:product-sku-naming-20261104`.
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

The two requested new marketplace publications have not been submitted.
Both available browser environments showed the HOYEE login page; authenticated
collection/recapture and live publication acceptance still require a login.
The existing TikTok workflow that completed during deployment was a prior
publication, not a test publication of this new SKU policy.
