# Identical cost-import duplicates — 2026-11-04

Cost imports now keep the first price when another row has the same warehouse,
model, month and numeric unit price. Duplicate records no longer reject the
entire upload. Numeric comparison accepts differences in decimal formatting,
such as `2` and `2.00`, without rounding either price. Distinct warehouses and
months remain separate, and later rows can supply additional months.

Conflicting prices for the same warehouse/model/month still reject the import
before any source is written; the message identifies the conflicting row.
Formula, negative-price and precision validation remains unchanged. Existing
file-hash deduplication and source precedence are unchanged.

The supplied original XLSX now parses to 16,459 warehouse/model records from
16,460 input records. Its one identical duplicate record covered ten months.
An independent comparison of all 164,590 unique warehouse/model/month prices
found no missing, additional or changed prices. After deployment, the user
explicitly requested uploading the original workbook to its group. The
authenticated UI import succeeded and showed one source file with 16,459 cost
records. Existing SKU matching and background order-cost recalculation were
left to their normal workflow.

Seventeen related Java checks passed, including equal prices with different
decimal scales, zero prices, complementary months, different warehouses,
long-form duplicates and genuinely conflicting prices. Test fixtures are
synthetic; the private workbook is not included in the repository.

Active backend: `ai-commerce-platform:finance-cost-dedupe-20261104`.
Frontend remains `ai-commerce-frontend:finance-fee-native-layout-20261104`.
Release helper: `scripts/release/release-finance-cost-dedupe.py`.
Release root: `/opt/heyi/releases/finance-cost-dedupe-20261104`.

Only the `FinanceCostWorkspace` class family is replaced over the verified live
JAR. All unrelated entries are byte-identical. The established queue drain and
image-compaction guard are reused; runtime configuration, credentials, volumes,
checkpoints, platform limits and frontend container are preserved. Live artifact
hashes, application health and anonymous access checks passed. Original schedules
were restored and temporary reservations released.

Keep the active backend and two verified backend rollbacks:
`finance-fee-native-20261104` and `qianyi-reverse-phrases-20261104`.
Frontend retention is unchanged from the fee-export release. Remove only
unreferenced older application tags and this release's temporary class/JAR
staging. Preserve Compose dependencies, release helpers and small verification
receipts. No schema migration or persistent application logging was added.
