-- Preserve completed internal baselines while later invalidations remain pending.
ALTER TABLE finance_query_partitions
 ADD COLUMN full_rebuild_revision BIGINT NOT NULL DEFAULT 1;
UPDATE finance_query_partitions
 SET full_rebuild_revision=CASE WHEN full_rebuild THEN requested_revision ELSE built_revision END;

-- Legacy writers can omit the revision; their dirty days remain conservatively rebuildable.
ALTER TABLE finance_query_dirty_days
 ADD COLUMN requested_revision BIGINT NOT NULL DEFAULT 0;
UPDATE finance_query_dirty_days d JOIN finance_query_partitions p
 ON p.organization_id=d.organization_id AND p.platform_filter=d.platform_filter AND p.partition_month=d.partition_month
 SET d.requested_revision=p.requested_revision;
