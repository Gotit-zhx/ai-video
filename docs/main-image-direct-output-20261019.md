# Direct Main Image Output

The user's generation-only requirement supersedes the generated-image review
steps in `main-image-detail-progress-20261019.md`.

- Template analysis, prompts, model settings and network recovery are unchanged.
- A generated result is decoded, stored byte-for-byte and marked READY without
  AI visual review, scoring or automatic correction. Invalid or missing files
  still fail; file integrity checks do not call a model.
- Previously saved output resumes from RESULT_RECEIVED without another paid
  generation or download. Historical attempts and paid-result history remain.
- Publishing requires the current template, READY output, ownership and a
  readable archived file, but no generated-image review metadata.
- Product-free template slots still preserve the exact original, with the
  existing source/reference/storage identity checks.
- Progress is queued, generating, saving, complete. Historical attempt counts
  no longer imply an automatic-correction label.

Verification: 96 focused Java tests and 58 frontend tests passed. Coverage
includes inline/downloaded output byte equality, saved-output reuse, legacy
failed reviews, publishing without review metadata, missing files, template
changes, organization scope and original preservation.

Deployment uses `scripts/release-main-image-direct-output.py` to replace only
the StyleService and Assets class families, app.js and its cache-busting URL on
the exact current production images. Every unrelated JAR entry and HTML byte
is retained. No schema change or Pi runtime restart is needed. Active jobs
block activation; health, configuration and served hashes are checked.

The explicitly authorized failed workflow is recovered separately with a
private backup and locked row-hash guards. Its already archived first image
must be reused, not regenerated. No other failed workflow is resumed.
