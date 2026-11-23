ALTER TABLE finance_cost_sources
 ADD source_role VARCHAR(20) NULL,
 ADD revision BIGINT NOT NULL DEFAULT 1,
 ADD CONSTRAINT ck_finance_cost_source_role CHECK (source_role IN ('MERCHANT_SKU','PLATFORM_SKU')),
 ADD UNIQUE KEY uk_finance_cost_source_role(organization_id,group_id,source_role);

CREATE TABLE finance_cost_price_scans (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 organization_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 old_book_id CHAR(36) NULL, new_book_id CHAR(36) NOT NULL,
 first_day DATE NULL, cursor_day DATE NOT NULL DEFAULT '9999-12-31',
 cursor_id CHAR(64) NOT NULL DEFAULT '',
 KEY idx_finance_cost_price_scan_scope(organization_id,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Typed sources publish selective order changes through the durable scan queue.
-- Untyped source writers retain their original invalidation behavior.
DROP TRIGGER fin_qp_cost_sources_ai;
DROP TRIGGER fin_qp_cost_sources_au;
DROP TRIGGER fin_qp_cost_sources_ad;

CREATE TRIGGER fin_qp_cost_sources_ai AFTER INSERT ON finance_cost_sources FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_sources' WHERE NEW.source_role IS NULL;

CREATE TRIGGER fin_qp_cost_sources_au AFTER UPDATE ON finance_cost_sources FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_sources' WHERE OLD.source_role IS NULL AND NEW.source_role IS NULL
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_sources' WHERE OLD.source_role IS NULL AND NEW.source_role IS NULL;

CREATE TRIGGER fin_qp_cost_sources_ad AFTER DELETE ON finance_cost_sources FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_sources' WHERE OLD.source_role IS NULL;

-- Assign only an unmistakable pair from the user-confirmed clothing group.
-- Other existing sources remain available for explicit assignment.
WITH confirmed_clothing_files AS (
SELECT f.organization_id,f.group_id,
 MAX(CASE WHEN BINARY f.source_name=BINARY '11月服装成本表.xlsx' THEN f.id END) merchant_id,
 MAX(CASE WHEN BINARY f.source_name=BINARY '11月服装成本表补充（SKU-ID).xlsx' THEN f.id END) platform_id
FROM finance_cost_sources f
JOIN finance_groups g ON g.id=f.group_id AND g.organization_id=f.organization_id
WHERE BINARY g.name=BINARY '服装组'
  AND BINARY f.source_name IN (BINARY '11月服装成本表.xlsx',BINARY '11月服装成本表补充（SKU-ID).xlsx')
GROUP BY f.organization_id,f.group_id
HAVING COUNT(*)=2 AND merchant_id IS NOT NULL AND platform_id IS NOT NULL
)
UPDATE finance_cost_sources f JOIN confirmed_clothing_files p
 ON f.organization_id=p.organization_id AND f.group_id=p.group_id AND f.id IN (p.merchant_id,p.platform_id)
SET f.source_role=IF(f.id=p.merchant_id,'MERCHANT_SKU','PLATFORM_SKU')
WHERE (SELECT COUNT(*) FROM confirmed_clothing_files)=1;
