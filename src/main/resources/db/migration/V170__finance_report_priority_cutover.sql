-- Record one closed prefix of the pre-upgrade journal. A read lock waits for
-- in-flight event inserts before the prefix is captured. No events are deleted.
CREATE TABLE finance_query_priority_cutover (
 id TINYINT NOT NULL PRIMARY KEY,
 legacy_through BIGINT NOT NULL,
 CONSTRAINT ck_finance_query_priority_cutover_singleton CHECK (id=1)
) ENGINE=InnoDB;

CREATE TABLE finance_query_priority_orgs (
 organization_id CHAR(36) NOT NULL PRIMARY KEY,
 enabled_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_query_priority_baselines (
 organization_id CHAR(36) NOT NULL,
 platform_filter VARCHAR(24) NOT NULL,
 partition_month DATE NOT NULL,
 legacy_through BIGINT NOT NULL,
 PRIMARY KEY (organization_id,platform_filter,partition_month),
 CONSTRAINT fk_finance_query_priority_partition FOREIGN KEY (organization_id,platform_filter,partition_month)
 REFERENCES finance_query_partitions(organization_id,platform_filter,partition_month) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

LOCK TABLES finance_query_events READ, finance_query_priority_cutover WRITE;
INSERT INTO finance_query_priority_cutover(id,legacy_through)
 SELECT 1,COALESCE(MAX(event_id),0) FROM finance_query_events;
UNLOCK TABLES;
