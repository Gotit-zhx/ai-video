# Qianyi missing receipt display — 2026-11-05

The user requested removal of the repeated 千易物流待确认 badge in the
after-sales list. UNKNOWN and absent receipt states now display a neutral dash
in the list and detail dialog. Confirmed signed and in-transit labels remain.
The UNKNOWN filter is described as 无明确返程状态, and the excluded-count
card reads 缺少返程物流依据（未计入）.

This is a frontend presentation change. Source rows, classification,
qualification rules, totals, export contents, synchronization, and all backend
code remain unchanged. The audit view retains every row; missing evidence is
not treated as a signed return.

Files changed: frontend/finance-qianyi.js and its index.html cache version.
The existing standalone and embedded Qianyi UI suites passed, covering filters,
pagination, detail, summaries, export parameters and stale response isolation.

Frontend image: ai-commerce-frontend:qianyi-receipt-display-20261105.
Previous frontend: ai-commerce-frontend:finance-brush-original-template-20261105.
Backend remains ai-commerce-platform:finance-source-orders-20261105.
Release root: /opt/heyi/releases/qianyi-receipt-display-20261105.
Helper: scripts/release-qianyi-receipt-display.py.

The helper reuses validated frontend activation and verification. It checks
live baseline hashes, compaction safety, unchanged backend identity, runtime
configuration, compressed assets, protected API responses, and preservation
of unrelated frontend files including the original brush-fee template.

After acceptance, retain the active frontend plus finance-brush-original-template-20261105
and finance-brush-template-20261105. Remove only the unreferenced older frontend
finance-source-orders-20261105 tag and this release's frontend staging. Preserve
the current three backend images, required base images, Compose files, helper
dependencies, business volumes and small verification receipts.

Authenticated single-shop acceptance reproduced the user's thirteen rows for
YUXI-母婴-SHOPEE 2-泰国, October 7 through November 5. All thirteen unknown
receipt cells show a neutral dash. Rows, zero qualifying receipts and zero
return-cost total are preserved. Screenshot:
`/Users/heyi/Documents/ChatGPT/qianyi-receipt-display-20261105/物流状态显示验收.png`.
The initial all-shop query returned an HTTP 500; this is not recorded as
successful all-shop acceptance. The bounded single-shop query succeeded.

Frontend HTTP/gzip/hash checks, protected API responses, backend identity and
runtime configuration checks passed. Post-acceptance cleanup and retained
image checks are recorded in cleanup-verification.json in the release root.
