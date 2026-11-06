# Direct Main Image Output

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

The user's generation-only requirement supersedes the generated-image review
steps in `../../business/commerce/main-images.md`.

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

Deployment uses `scripts/release/release-main-image-direct-output.py` to replace only
the StyleService and Assets class families, app.js and its cache-busting URL on
the exact current production images. Every unrelated JAR entry and HTML byte
is retained. No schema change or Pi runtime restart is needed. Active jobs
block activation; health, configuration and served hashes are checked.

The explicitly authorized failed workflow is recovered separately with a
private backup and locked row-hash guards. Its already archived first image
must be reused, not regenerated. No other failed workflow is resumed.

## Production Record

Deployed source `2e0ab0f` to
`/opt/heyi/releases/main-image-direct-output-20261019-2e0ab0f`.
The exact live frontend baseline was `ai-boss-startup-52755999f51a`;
its unrelated changes were retained. Activation found no active application
jobs. HTTPS asset hashes, patched JAR hash, health and container configuration
matched; all unrelated containers, including Pi, retained their instance IDs.

At 16:50:58 Asia/Shanghai on October 19, 2026, the authorized workflow
`b76a2ea4-c37f-4db4-b607-cb8c92b9d8b0` resumed with a private backup and
transactional row guards. Its archived first output became READY at 16:50:59
with the same storage key, attempt count and paid-result history, and cleared
review metadata. The second image entered generation at 16:51:03; the
product-free seventh image remained READY. This records continuation, not
completion of the remaining generation or marketplace publication.
