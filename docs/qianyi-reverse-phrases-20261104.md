# Qianyi returned-to-sender wording — 2026-11-04

The Qianyi receipt classifier also recognizes the exact terminal events
`Parcel has been returned to sender` and `Package has been returned to the seller.`
(case-insensitive, optional final period). These are observed Qianyi tracking
responses. The former was missed for Shopee returns because the original
classifier recognized only `Your package has been returned to the seller...`.
This is a Qianyi evidence-mapping correction; Shopee official return status is
not a qualification condition.

Exact tracking/order identity, latest return-event selection, conflicting-event
rejection, actual Beijing receipt date, nondeleted completed sales returns and
all existing TikTok recognition remain intact. In-transit, future, negated and
ordinary forward-delivery messages do not become signed receipts. No costs,
inventory credits or profit adjustments are written by the classifier.

## Observed production evidence

For shop `7fcf44fd-e982-4969-a1c4-30743f1ae32a`
(`YUXI-母婴-SHOPEE 2-菲律宾`), October 2026 had 31 sales returns:
30 stored the explicit returned-to-sender wording but were UNKNOWN, and one
had no terminal event. Across shops since October 1, the two unrecognized
wordings occurred on 803 records in 19 shops and 30 records in three shops
respectively (shop sets may overlap).

The remaining sample `B2610125888881` was separately read from Qianyi.
The complete one-parcel response matched tracking, sale order and online order;
its only event was `Sender is preparing to ship your parcel`, and its main
status was CREATED. It must remain unknown until explicit return receipt
evidence is obtained. Temporary Qianyi worker reservations were released, with
no checkpoint or source record changes during that probe.

## Release procedure

Helper: `scripts/release/release-qianyi-reverse-phrases.py`.
Target backend: `ai-commerce-platform:qianyi-reverse-phrases-20261104`.
Release directory: `/opt/heyi/releases/qianyi-reverse-phrases-20261104`.
Only `FinanceQianyiReceipt.class` changes, over the exact live
`qianyi-return-total-20261104` JAR. Frontend and schema are unchanged.

Twelve focused unit checks passed, including identity mismatches, conflicting
events, later in-transit evidence, negative/future wording, unchanged historical
resume cursors and the existing return-cost summary rules. The unchanged
classifier is compiled for byte-for-byte comparison to production before
building. Image compaction guard and established queue draining are mandatory.
An initial activation attempt was safely deferred for an active product
publication; Qianyi reservations were released and no application was restarted.

After activation, affected enabled shops receive one bounded receipt scan from
the start of the previous Beijing calendar month through today. Each exact
prior checkpoint is nested under `backfillResume`. Existing shared Qianyi
rate limiting, full tracking pagination and normal source ingestion perform
the repair; stored UNKNOWN states are not blindly changed to SIGNED.
The reported shop is queued first. Normal daily receipt refreshes use the
corrected classifier thereafter.

Keep the active backend plus `qianyi-return-total-20261104` and
`sku-shared-cost-total-20261104` rollback images after acceptance. Frontend
retention is unchanged. Remove only owned build staging and the older,
unreferenced `product-sku-pattern-option-20261104` backend image after successful
acceptance; retain Compose dependencies, helpers and small verification receipts.

## Acceptance

Before restart, a bounded repair used the tested classifier against newly fetched
complete Qianyi tracking responses for the reported shop. All 30 terminal
receipts were confirmed and corrected; the one preparing-to-ship parcel remained
unknown. Only sanitized logistics evidence, its source hash and local update
time changed. Cost source fields were compared unchanged; the full Qianyi
checkpoint digest was unchanged. All temporary reservations were released.

The authenticated UI and an actual downloaded Excel, filtered to
2026-10-06 through 2026-11-04, both showed 22 distinct records, 21 signed,
one unknown, standard return cost CNY 495.327091 and native refund PHP 5,785.
The exported per-document cost sum matched the UI, with zero duplicates.
The test download was removed.

After the active product publication completed, the backend activation passed
health, exact JAR hash, unchanged runtime configuration and anonymous 401 checks.
Frontend was not restarted. Existing finance schedules and leases were restored.
Twenty-one other affected shops were queued for one fresh receipt/track scan,
with their historical checkpoints nested unchanged; this queueing is not a claim
that every shop has already finished its scan.

Small production receipts: `sample-repair.json`, `prepared.json`,
`activation.json`, `receipt-rescan.json`, `drain-restored.json`.
UI/export evidence and screenshot:
`/Users/heyi/Documents/ChatGPT/qianyi-reverse-phrases-20261104/`.
