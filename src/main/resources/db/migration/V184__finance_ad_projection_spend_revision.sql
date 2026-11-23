ALTER TABLE finance_query_partitions
 ADD COLUMN spend_full_revision BIGINT NOT NULL DEFAULT 0,
 ALGORITHM=INSTANT;
