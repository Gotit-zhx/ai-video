-- Financial rows are additive across shop/day only after resolving first settlement
-- and deduplicating at the native shop/order/currency scope.
ALTER TABLE finance_query_partitions
 ADD COLUMN settlement_cost_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN settlement_cost_requested BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE finance_query_dirty_shop_days (
 organization_id CHAR(36) NOT NULL,
 platform_filter VARCHAR(24) NOT NULL DEFAULT '',
 partition_month DATE NOT NULL,
 record_date DATE NOT NULL,
 shop_id CHAR(36) NOT NULL,
 requested_revision BIGINT NOT NULL,
 cost_revision BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(organization_id,platform_filter,partition_month,record_date,shop_id),
 CONSTRAINT fk_fin_query_dirty_shop_partition FOREIGN KEY(organization_id,platform_filter,partition_month)
 REFERENCES finance_query_partitions(organization_id,platform_filter,partition_month) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_query_settlement_cost_days (
 organization_id CHAR(36) NOT NULL,
 platform_filter VARCHAR(24) NOT NULL DEFAULT '',
 partition_month DATE NOT NULL,
 record_date DATE NOT NULL,
 shop_id CHAR(36) NOT NULL,
 full_json LONGTEXT NOT NULL,
 partial_json LONGTEXT NULL,
 PRIMARY KEY(organization_id,platform_filter,partition_month,record_date,shop_id),
 CONSTRAINT fk_fin_query_cost_day_partition FOREIGN KEY(organization_id,platform_filter,partition_month)
 REFERENCES finance_query_partitions(organization_id,platform_filter,partition_month) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
