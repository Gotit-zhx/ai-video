# Optional Main Image Styles

## Behavior

- Upload an ordered set of 1-9 posters. Analysis identifies the reference
  product regions and extracts layout, typography, palette, background,
  lighting, decorations and reusable prompts.
- Bulk upload defaults to no template and performs no additional generation.
  Selecting a template saves only its ID, not a style snapshot. Generation and
  review continue automatically without another approval step.
- Each product/shop workflow produces one main image per template image.
  The product image and poster are supplied in that order with high quality
  and high input fidelity. Review checks product identity, reference-product
  removal, layout, style and text. At most one corrective generation is made.
- Template edits invalidate only changed unfinished slots. The latest gallery
  is rechecked immediately before external product creation in a fresh
  read-committed transaction.
- Capture originals, SKU images, descriptions, listing settings, size charts
  and multi-shop sequencing retain the existing production behavior.
- Unknown generation results only query the original operation. Cached
  recoverable results can be replayed under the same ID, with a persisted
  three-attempt recovery limit.

## Production Baseline

Source branch: `codex/main-image-styles-20261019`, based on `dff6173`.
The original development worktree contains unrelated changes and is not a
deployment input.

Production Flyway is at V115. This feature uses
`V116__product_main_image_styles.sql`; existing migration checksums are unchanged.
The production HR asset cache versions from October 16 and the concurrent
October 19 AI BOSS downloads update are retained. The pre-deployment hash
check stopped on that concurrent update before any production restart.

A normalized comparison found 863/864 baseline Java classes identical. The
only difference is `ShopIntegrationSettings`, which is not replaced. The
release helper separately requires every replaced baseline class family to
match, then verifies every unrelated JAR entry is preserved byte-for-byte.

## Verification

- Backend focused regression: 233 passed, no failures/errors; one existing
  opt-in module migration test skipped because it requires a dedicated DB.
- Frontend/runtime focused regression: 129 passed, including multi-shop,
  size-chart preservation, optional selection and default omission.
- Browser checks against the production-derived frontend using
  `frontend/test/main-image-style-preview.mjs`: ordered template detail,
  optional/default bulk selection, automatic completion without a second
  confirmation, exact request ID with no style snapshot, and preserved
  multi-shop/size-chart controls. Desktop and 390px mobile screenshots were
  inspected; mobile page width was 390px and the dialog had no horizontal
  overflow.
- No real image-model generation or marketplace product creation is performed
  by release verification. Actual generated-image fidelity requires a real
  product/reference run and is not established by mocked tests.

## Release Procedure

`scripts/release-main-image-styles.py` accepts a release directory and phase:

1. `pack <source-worktree>`: compare affected baseline bytecode and stage only
   reviewed compiled classes, V116, frontend files and runtime files.
2. `build`: verify the current production hashes and construct a narrow JAR
   overlay, preserving all unrelated entries.
3. `rehearse`: clone only schema and Flyway history into a disposable MySQL DB.
   Run the packaged Flyway engine, test V116/checks/JSON/idempotency/cascade,
   then drop the test database and database-restricted account.
4. `deploy`: recheck baseline and idle workers, build overlays on exact image
   IDs, stop the app, privately back up MySQL, and activate using each
   container's complete existing Compose chain. Verify health and equal
   environment, mounts, networks and ports.
5. `verify` / `cleanup`: validate served hashes, health, Flyway V116 and
   unauthorized API rejection; remove duplicate build artifacts, retaining
   rollback images and the private backup.

Release directory: `/opt/heyi/releases/main-image-style-20261019`.
Deployment receipts are private files there, not source-controlled secrets.
Rollback restores the prior images and keeps the additive schema/data;
automatic destructive database rollback is deliberately avoided.

## Deployment Result

Deployed on October 19, 2026 from source commit
`bc20df2454054f953c5d17d17b2dd97943d762ab`.

- Application: `ai-commerce-platform:main-image-styles-20261019`
- Runtime: `heyi-pi-runtime:main-image-styles-20261019`
- Frontend: `ai-commerce-frontend:main-image-styles-20261019`
- V116 applied successfully after the disposable-DB rehearsal.
- HTTPS asset hashes and all three service health checks passed.
- Existing environment, mounts, network and port bindings were preserved.
  Six unrelated containers retained their IDs; the HR connector remained active.
- Unauthenticated template/result requests return HTTP 401.
- Duplicate build files and the disposable database/account were removed.
  Rollback images and the private compressed MySQL backup remain on the server.
- The browser's production session was logged out; authenticated production
  template creation and paid generation were not exercised.
