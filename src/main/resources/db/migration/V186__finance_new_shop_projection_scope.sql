-- A newly registered shop with no linked facts only changes the current shop list.
-- Its first financial records enqueue their own business days. Preserve the full
-- invalidation when registration makes any already stored facts visible.
DROP TRIGGER fin_qp_connections_ai;

CREATE TRIGGER fin_qp_connections_ai AFTER INSERT ON finance_connections FOR EACH ROW
    INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
    SELECT NEW.organization_id,IF(existing_facts,'ALL','DAY'),
        IF(existing_facts,NULL,COALESCE(DATE(CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00',NEW.timezone)),UTC_DATE())),
        IF(existing_facts,NULL,NEW.id),NULL,NULL,'finance_connections'
    FROM (SELECT
        EXISTS (SELECT 1 FROM finance_records r WHERE r.organization_id=NEW.organization_id AND r.shop_id=NEW.id)
        OR EXISTS (SELECT 1 FROM finance_expenses e WHERE e.organization_id=NEW.organization_id AND e.scope='SHOP' AND e.target_id=NEW.id)
        OR EXISTS (SELECT 1 FROM finance_qianyi_returns q WHERE q.organization_id=NEW.organization_id AND q.shop_id=NEW.id)
        OR EXISTS (SELECT 1 FROM finance_ad_accounts a WHERE a.organization_id=NEW.organization_id AND a.shop_id=NEW.id)
        OR EXISTS (SELECT 1 FROM finance_ad_stores d WHERE d.organization_id=NEW.organization_id AND d.shop_id=NEW.id)
        OR EXISTS (SELECT 1 FROM finance_ad_spend_allocations a JOIN finance_ad_spend_allocation_sets s
            ON s.id=a.allocation_set_id WHERE s.organization_id=NEW.organization_id AND a.shop_id=NEW.id)
        OR (NEW.platform='SHOPEE' AND (
            EXISTS (SELECT 1 FROM finance_shopee_ad_daily d WHERE d.organization_id=NEW.organization_id AND d.external_shop_id=NEW.external_shop_id)
            OR EXISTS (SELECT 1 FROM finance_shopee_gms_daily d WHERE d.organization_id=NEW.organization_id AND d.external_shop_id=NEW.external_shop_id)))
            AS existing_facts) registration;
