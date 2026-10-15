# Commerce Module Isolation

## Scope

- Collection, fission, templates, ad testing, and materials use separate listing-content and shop-logistics configurations.
- Authorized shops remain shared. Organization permissions and existing shop authorization are unchanged.
- Creating a fission batch imports private product, SKU, image, provenance, and video records. Changing or deleting a collection product does not change the imported records.
- Image identifiers and explicit size-chart selections are remapped. Verified processed images use immutable archives, and detected size-chart classifications are retained.
- Historical batches are migrated to private sources without changing their request keys, source versions, generated results, or publication records.
- Generated prints enter the material catalogue only after an explicit save. Existing catalogue entries are preserved as frozen records.
- Existing publication requests retain their frozen settings and idempotency behavior.

Namespaces are `CAPTURE`, `FISSION`, `TEMPLATES`, `ADS`, and `ASSETS`, carried by `X-Commerce-Module`. The header selects a namespace, not an organization or authorization level. Background publication reads the product's stored namespace rather than the currently open browser page.

## Validation

- 229 focused frontend tests passed.
- Full frontend comparison: 717 tests, 614 passed, 41 skipped, and the same 62 failures reproduced on the unchanged baseline. No additional failures.
- Backend: 265 selected tests, 262 passed and 3 opt-in integrations skipped. The real MySQL migration rehearsal executed and passed.
- V113 and V114 were applied to a disposable copy of production tables with a database-restricted account. A second migration run made no changes.
- Migration assertions preserve original product rows, shops, and batches; verify independent configuration updates; and check copied image ownership and size-chart classification.
- Browser fixture QA saved distinct configurations in all five modules. Desktop and 390px mobile layouts had no horizontal overflow.
- The public production page loaded all updated script versions and the correct namespace mapping. Authenticated product publishing and paid generation were not performed.

## Deployment

Source branch: `codex/commerce-module-isolation-20261015`, based on `aaa8c14`.

Images:

- `ai-commerce-platform:module-isolation-20261015`
- `ai-commerce-frontend:module-isolation-20261015`

The release overlays only reviewed classes, V113 SQL, V114 Java migration, and six frontend files onto the exact live images. Replaced Java classes were compared with a separately compiled baseline. Unrelated JAR entries, container environment variables, mounts, and seven other running containers were preserved.

Server release directory: `/opt/heyi/releases/module-isolation-20261015`.

The directory retains activation and verification manifests, test output, the original JAR, and an access-restricted database backup. Disposable MySQL data, the test account, and duplicate build artifacts are removed after verification.

## Rollback

The previous images are recorded in `activation.json`. An immediate rollback must restore the pre-migration table schema and data before starting the old application; reverting images alone is not compatible with the new configuration primary keys.

The release script automatically performs this schema-aware rollback if activation fails. Do not restore the initial table backup after new business writes have resumed without first reconciling those writes.
