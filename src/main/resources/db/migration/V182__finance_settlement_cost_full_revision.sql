ALTER TABLE finance_query_partitions
 ADD COLUMN settlement_cost_full_revision BIGINT NOT NULL DEFAULT 0,
 ALGORITHM=INSTANT;

-- Existing full rebuilds may include settlement changes from an earlier application.
UPDATE finance_query_partitions SET settlement_cost_full_revision=requested_revision WHERE full_rebuild;
