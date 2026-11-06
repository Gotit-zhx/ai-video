# Image Recovery Storage and Server Cleanup

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

## Root Cause

On October 20, 2026 the image runtime volume contained 1,821 JSON operation
records, occupying 17,681,707,008 allocated bytes. A completed result can contain
both the decoded image artifact and the original provider response, including
the same Base64 image twice. The operation ledger prevents duplicate paid calls
and supports recovery after an interrupted response, so deleting these files
as cache is unsafe.

The commerce image acknowledgment path removes its payload after verified OSS
storage and a durable business record. Historical and video operations did not
all use that acknowledgment. The runtime had no OSS connection or retention
mechanism of its own. Business image storage and recovery ledger storage are
separate; uploading the former did not release the latter.

## Fix

- New and historical completed/recoverable records are automatically moved to
  private Dayan OSS objects. Local files retain identity, counters and an
  integrity-checked pointer.
- Uploads use gzip, content-addressed keys, bounded multipart uploads and complete
  readback verification before replacing local content. These are byte-integrity
  checks, not model-based quality checks.
- Recovery hydrates the original complete response. OSS outages fail closed with
  HTTP 503 and never turn a saved paid operation into a missing operation.
- Migration is background work, outside the foreground generation response.
  The initial delay is 120 seconds; files must be inactive for 60 seconds.
  Each pass moves up to 20 records of at least 64 KiB. Full passes continue after
  one second; idle passes run every minute. New work is handled by the same loop.
- Concurrently changed or active records are not replaced. Business acknowledgments
  still work. Model routes, prompts, generation acceptance and paid-call budgets
  are unchanged.

`compose.dayan-oss.yaml` records the storage configuration for normal builds.
Set `APP_DAYAN_OSS_BUCKET` and `DAYAN_OSS_CREDENTIALS_PATH` outside the repository.
`scripts/release/release-image-operation-oss.py` overlays only the two runtime source
files and an isolated official OSS SDK onto the exact live runtime image.
It verifies an idle service, unchanged unrelated containers and the existing
environment/mounts. It enables readers before migration.

After the first migration, rollback must retain the new OSS reader. Disable
`IMAGE_OPERATION_OSS_MIGRATE` to pause migration; do not revert to an image that
cannot read external result indexes.

## Cleanup Already Applied

The reviewed cleanup removed 79 obsolete build/deployment archives, unused
dangling Docker images (Docker reported 2.081 GB) and apt download cache.
The net measured space reclaimed was 11,029,606,400 bytes.
Available space increased from approximately
3 GiB to 14 GiB; root filesystem utilization fell from 97% to 86%.

Current and rollback releases, all running containers, database backups,
business media, databases and browser profiles were preserved. Detailed private
evidence is stored at `/opt/heyi/diagnostics/server-cleanup-20261020-v2/`.

## Other Media

Generated business images already use Dayan OSS. The image read cache is capped
at 512 MiB. Source videos, extraction frames and task input files have verified
Dayan archive copies, but local working paths remain required by analysis and
retry code; those must not be deleted indiscriminately.

The October 20 asset inventory still includes 1,399 legacy OSS references.
Those are not local server files and must not be described as migrated merely
because the operation volume has been offloaded. See `../../business/storage/dayan-object-storage.md`
for the legacy source access limitation and content-proven recovery procedure.

## Rollout Evidence

- Runtime: `heyi-pi-runtime:image-operation-oss-20261020-v2`.
- Release: `/opt/heyi/releases/image-operation-oss-20261020-5df7990`.
- Runtime source commit: `5df7990`. All 215 focused tests passed locally and
  inside the exact production image without access to models.
- The official Node SDK needed Dayan-compatible V1 `Date` signing and 8 MiB
  multipart chunks, matching the existing Java uploader. A real 9 MiB random
  object passed upload, complete readback and deletion before activation.
- Two actual historical business operations passed the authenticated recovery
  HTTP endpoint with identical original responses. Their local records shrank
  from 1,071,488 and 1,221,611 bytes to 792 and 1,440 bytes. Anonymous object
  access was denied. No generation requests were made.
- At 2026-10-20 14:48:24 Asia/Shanghai, 37 records representing 257,013,729
  original bytes had been offloaded, including 21 sub-megabyte records.
  There were zero migration warnings. Historical migration was still in
  progress; 1,633 inline records of at least 64 KiB remained at that checkpoint.
- Root disk utilization was 86%, with 14,470,586,368 available bytes. Both
  application and runtime health were UP and the public site returned HTTP 200.
  Sampled CPU was 99% idle with zero I/O wait and no swap usage.
- All other running containers, including the Java app and frontend, retained
  their container IDs. Model configuration, database rows and existing business
  task states were not changed by the deployment.

Private `probe.json`, `recovery-probe.json`, `activation.json`, `tests.log` and
timestamped inventory files in the release directory hold the rollout evidence.
The continuously running migration is not a one-off cleanup command.

The separate MySQL watermark-check table occupied approximately 1.46 GB
(data and indexes) at inspection. This runtime release does not rewrite its
SQL generation history or rebuild database tables. Database backups and source
analysis working files are likewise not treated as disposable garbage.
