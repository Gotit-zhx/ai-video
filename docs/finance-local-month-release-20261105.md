# Local-calendar queries and storage compression — 2026-11-05

Backend and frontend: `finance-local-month-20261105`.
Release root: `/opt/heyi/releases/finance-local-month-20261105`.
Storage receipt root: `/opt/heyi/releases/finance-storage-compression-20261105`.
Helper: `scripts/release-finance-local-month.py`.

## Delivered date contract

Fresh finance visits and requests without dates default to the complete previous
calendar month, using Beijing time to choose the month label. An explicit month
input and 上月整月 action select both boundaries together. Custom ranges and
previously pinned selections remain available. Browser/server time zones do not
shift the selected calendar dates. Existing sync planning already uses each
shop's IANA timezone; its code and checkpoints were preserved.

The UI explains shop-local dates and Beijing operational timestamps. Each shop
has its own `[local start, next local day after end)` boundary. For Thai October
2026 this corresponds to Beijing October 1 at 01:00 through November 1 at 01:00,
exclusive. Date-only official statement values remain dates.

Exports include a 店铺日期范围 sheet, date-basis version, completion qualification
and separate statement-generation, payment and actual-release timestamps in
shop-local and Beijing time. Platform payments are no longer labelled as the
report's settlement completion time. Fee matrices retain native currencies.
Qianyi continues to use actual receiveTime in Beijing time and its established
receipt/status/evidence rules; no cost credit or profit reversal was restored.

The month badge now says 上月抓取完成. Interface completeness is explicitly
distinct from independent official-file reconciliation. Cache primary and
resolved-currency alias keys include the same `shop-calendar-v1` version; running
query reuse and bounded freshness remain intact.

**This is not a completed migration of every platform's accounting dates.**
Existing financial record dates and amounts were not rewritten. The verified
cross-border TikTok sample remains unchanged. ATC October's local-payment
candidate still needs the original official workbook and transaction-level
verification; matching the screenshot aggregate is insufficient. Shopee
independent Adjustment ingestion/reconciliation is also not delivered here.
See the design and official-export audit for these explicit remaining stages.

## Storage changes

Five existing Dynamic/file-per-table tables now use native `COMPRESSION='lz4'`:
finance_records, finance_order_costs, finance_qianyi_returns,
finance_label_invoice_rows and finance_label_order_totals. This is MySQL
write-time transparent page compression, not a recurring OPTIMIZE or a new
desktop monitoring task. It applies to subsequently written data/index pages;
cold historical pages are not immediately rewritten. New real data still takes
space. No large-table rebuild, data deletion or filesystem-hole manipulation
was performed.

The verified environment uses 16 KiB InnoDB pages and 4 KiB filesystem blocks.
An isolated copy of 5,000 real rows, with all original indexes, occupied
50,335,744 allocated bytes uncompressed and 32,350,208 bytes with LZ4. Every
field's sorted SHA256 matched. The temporary schema was dropped.

The first online attempt hit the deliberate 3-second metadata lock timeout and
stopped. Compression was subsequently enabled during the normal release's
app-stop window; each table's metadata change took 0.096–0.144 seconds. Original
worker schedules, leases and cursors were restored normally.

`idx_finance_bill` occupied approximately 2,985,984,000 bytes and had no recorded
reads in the current performance-schema interval. All five nonunique columns
are the exact leading columns of the retained eight-column
`idx_finance_reconcile_tx`; application source has no hint forcing the removed
index. It was removed online without copying the table. V147 records the change
idempotently for future installations. The main table subsequently reported
3,005,218,816 bytes of internal free space. That is space available for reuse,
**not** an equal immediate increase in filesystem free space.

Before and after these operations, the 11,154 BILL/SETTLEMENT control rows for
the previously verified Shopee October sample had identical IDs, dates,
currencies and amounts: SHA256
`78234f5b6f4a685fdd8aa578fe428ddbc33fa1641ab3ad15c80f902c5ef201f1`.

Completed October 27/29 repair copies and rebuildable APT metadata caches
removed 374,575,104 allocated bytes. An older repair snapshot with a nonzero
historical difference was retained. APT cache regeneration is disabled using
`ops/admin-console/heyi-no-persistent-pkgcache.conf`, installed as
`/etc/apt/apt.conf.d/99heyi-no-persistent-pkgcache`.

The cost-dedupe rollback tag now points to its already verified compact image:
the existing source-order release guard proves identical configuration and
12,701 filesystem paths. The obsolete layered representation and its unused
old base were removed. This preserves the same rollback application contents.
Disposable image records are cleaned through Docker, never by deleting Docker
storage directories. The release helper also prunes untagged, unreferenced
build images older than 24 hours after future successful deployments; volumes
and tagged rollback images are excluded.

The final Docker dangling-image cleanup removed 42 untagged intermediate image
records. Docker reported 1.969 GB reclaimed; every running/stopped container's
image ID and all six active/rollback tags were verified unchanged. No volumes
were pruned. Filesystem availability changed from 2,591,633,408 to 8,286,830,592
bytes during that operation and measured 8,737,034,240 bytes at the later
bounded read. These larger free-space deltas include concurrent MySQL temporary
space fluctuations and must not be presented as Docker cleanup's reclaim total.
Receipt: `dangling-cleanup-verification.json`.

## Verification and limits

- All five pre-change class families compiled byte-for-byte equal to production
  before editing; unrelated JAR entries were preserved. Only the reviewed
  calendar/controller/service/fields/cache/matrix classes and V147 were added
  or replaced. Sync workers and financial fact calculators are unchanged.
- 105 Java tests passed, including boundary dates, DST, timestamp separation,
  cache currency aliases, native amounts, fees and sync month certificates.
- Five focused UI suites passed: calendar month across three browser time
  zones, retained filters, order/date/export selection, long query waiting
  without repeated requests, and sync priority/month status.
- The older groups UI suite assumes the pre-existing settled-order default and
  prior date-cell layout; it was not counted as passing. The date/search/export
  suite covers the current scope/group/shop interactions.
- HTTP asset/gzip hashes, configuration equality, protected endpoints and
  application health passed. Four core containers were running with no OOM,
  restarts or persistent log driver. V147 succeeded; lock waits were zero.
  Post-release channels: 470 SUCCESS, 6 SYNCING, 2 RUNNING, one controlled
  RATE_LIMITED channel with failures=1.
- The production browser rendered the new month controls and date explanation,
  but the session then expired. Authenticated list/export acceptance was not
  completed and must not be represented as passing; the retained new browser
  tab awaits user login.

Keep active backend plus finance-source-orders-20261105 and
finance-cost-dedupe-20261104 (verified compact equivalent); keep active frontend
plus qianyi-receipt-display-20261105 and finance-brush-original-template-20261105.
Staging classes, JARs and uploaded frontend copies were removed. Keep Compose
dependencies, release helpers, small verification receipts, original business
data and the sole database backup.
