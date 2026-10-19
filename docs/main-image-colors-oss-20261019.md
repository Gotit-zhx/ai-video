# Style Colors, OSS Archives, and Final Covers

## Scope

- Extract each same-product colorway's region index, body color, approximate
  hexadecimal color, and original color label separately from the poster palette.
- Preserve target product construction, material, print artwork and print colors.
  Only explicitly identified colorway positions inherit the template body colors.
  A standalone hero, same-color closeups, and ordinary product views keep source colors.
- Existing template analyses remain valid. Their generation prompt reads colorway
  positions directly from the reference image and takes precedence over older
  blanket restrictions on recoloring. No re-upload or extra selection is required.
- Information-only pages remain byte-for-byte originals; no AI review or redraw.
- Selected style galleries remain the authoritative publishing gallery.
  Generated product images lead, in template order, followed by preserved
  information pages. Reindexed sort orders survive marketplace-side sorting.
  No template selection keeps the old gallery unchanged.

## Storage

`CommerceImageArchive` now uses configured `OssStorageService` for new images.
Stable `tenant-hash/content-hash` business references are unchanged; the private
object path is `heyi/commerce-images/<tenant-hash>/<content-hash>`.
No signed URL or credentials are persisted in business references.

Uploads are read back and SHA-256 checked. An OSS outage never silently writes
new local images. Reads transparently migrate an existing local image before
removing it, and refuse wrong-tenant keys, symlinks and altered content.
Local-only construction remains available for tests/development without OSS.

Background maintenance prioritizes template/source/generated main images, then
migrates at most 25 remaining commerce files per pass. Local deletion follows
remote byte verification. At preflight, the commerce archive held 2,851 files
totaling 2,254,004,936 bytes; historical migration is incremental.

For READY main-image jobs only, maintenance verifies the OSS image, acknowledges
the durable runtime operation, then clears the database's large result payload.
The runtime drops duplicated image bytes while retaining the operation fingerprint,
attempt counters and archive receipt. Replaying an archived ID cannot generate
again. Active, failed or unverified results are not discarded.

This is a durable image-storage migration, not a blanket deletion of unrelated
video working files or in-flight model recovery records. Once images are migrated,
do not roll back to an archive implementation that only understands local files.

## Verification

Regression coverage includes six distinct template colors and labels, malformed
or collapsed color maps, legacy templates, single-color views, generated-first
gallery order, no-template opt-out, OSS deduplication and private ACLs, tenant
isolation, failed-upload/readback preservation, bounded migration, and paid-operation
replay protection. The targeted runtime suite passed 221 tests.

Deployment uses exact live image overlays. Unrelated classes, frontend, container
configuration and model defaults are not replaced. Banana remains the default,
GPT Image remains the print model, and Grok remains the watermark model.

## Production Rollout

Deployed source commit `2694be5` on 2026-10-19 using release directory
`/opt/heyi/releases/main-image-colors-oss-20261019-2694be5`. Final verification
confirmed backend/runtime hashes and health, unchanged frontend and unrelated
containers, and preserved configuration. The public site returned HTTP 200.

The isolated live OSS check passed direct upload, SHA-256 readback, legacy
migration, zero local image files, and tenant isolation. Temporary check objects
were removed. Production maintenance migrated all 29 known style-image keys:
none remained in the local archive. READY jobs with duplicated result payloads
fell to zero. Subsequent maintenance passes each migrated another 25 archive
files without a deferred-maintenance warning.

Historical migration is still running. At approximately 10:43 UTC, the local
commerce archive had 2,917 remaining files totaling 2,217,178,431 bytes; this is
a live remaining count, not a claim that all historical media has been moved.
Active and failed paid-operation recovery records remain protected.

One real Pi Agent analysis using the configured `gpt-6.1-sol` model completed
with HTTP 200 in 48.052 seconds. It returned `TEMPLATE_COLORWAYS` and six
correctly positioned labels: Black, Gray, Pink, Red, White, Cream. This check
did not change the template, regenerate product images, or republish the
already successful listing. Remote diagnostic image copies were removed after
matching their hashes against the retained local diagnostic copies.

Authenticated browser preview verification was unavailable because the isolated
browser opened at sign-in. The temporary browser task was closed; private-image
storage and publishing behavior were checked through backend tests and live
storage checks instead.
