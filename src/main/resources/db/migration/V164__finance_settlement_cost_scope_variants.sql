-- Store each partial-month and currency variant under its own collated key.
-- Preserve preexisting rows and require a new build to establish exact currency coverage.
ALTER TABLE finance_query_settlement_cost_days
 DROP FOREIGN KEY fk_fin_query_cost_day_partition,
 DROP PRIMARY KEY,
 CHANGE COLUMN full_json payload_json LONGTEXT NOT NULL,
 ADD COLUMN filter_currency VARCHAR(8) NOT NULL DEFAULT '' AFTER shop_id,
 ADD COLUMN partial_month BOOLEAN NOT NULL DEFAULT FALSE AFTER filter_currency,
 ADD PRIMARY KEY(organization_id,platform_filter,partition_month,record_date,shop_id,filter_currency,partial_month),
 ADD CHECK(JSON_VALID(payload_json));

INSERT INTO finance_query_settlement_cost_days
 (organization_id,platform_filter,partition_month,record_date,shop_id,filter_currency,partial_month,payload_json)
SELECT organization_id,platform_filter,partition_month,record_date,shop_id,filter_currency,TRUE,partial_json
FROM finance_query_settlement_cost_days WHERE partial_month=FALSE AND partial_json IS NOT NULL;

ALTER TABLE finance_query_settlement_cost_days
 DROP COLUMN partial_json,
 ADD CONSTRAINT fk_fin_query_cost_day_partition FOREIGN KEY(organization_id,platform_filter,partition_month)
 REFERENCES finance_query_partitions(organization_id,platform_filter,partition_month) ON DELETE CASCADE;

UPDATE finance_query_partitions SET settlement_cost_revision=0 WHERE settlement_cost_revision>0;
