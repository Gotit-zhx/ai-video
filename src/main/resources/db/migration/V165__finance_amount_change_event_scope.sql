-- Pure ledger metric changes on a fee or settlement keep one day invalidation.
-- First-settlement eligibility and historical attribution depend on the row
-- identity, status, category, currency and provenance, which must all match.
DROP TRIGGER IF EXISTS fin_qp_records_au;

CREATE TRIGGER fin_qp_records_au AFTER UPDATE ON finance_records FOR EACH ROW
 INSERT INTO finance_query_events(
   organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table,statement_id
 )
 WITH change_flags AS (
   SELECT (OLD.organization_id <=> NEW.organization_id AND OLD.id <=> NEW.id
       AND OLD.user_id <=> NEW.user_id AND OLD.shop_id <=> NEW.shop_id AND OLD.kind <=> NEW.kind
       AND OLD.external_id <=> NEW.external_id AND OLD.record_date <=> NEW.record_date
       AND OLD.order_id <=> NEW.order_id AND OLD.statement_id <=> NEW.statement_id
       AND HEX(OLD.currency) <=> HEX(NEW.currency) AND OLD.source <=> NEW.source
       AND OLD.status <=> NEW.status AND OLD.category <=> NEW.category
       AND OLD.raw_json <=> NEW.raw_json AND OLD.platform <=> NEW.platform) AS stable,
     (OLD.amount <=> NEW.amount AND OLD.revenue <=> NEW.revenue
       AND OLD.impressions <=> NEW.impressions AND OLD.clicks <=> NEW.clicks
       AND OLD.conversions <=> NEW.conversions) AS same_metrics
 )
 SELECT IF(v.is_new,NEW.organization_id,OLD.organization_id),
   CASE
     WHEN f.stable AND NEW.kind IN ('FEE','SETTLEMENT') THEN 'DAY'
     WHEN IF(v.is_new,NEW.kind,OLD.kind) IN ('ORDER','REFUND','SETTLEMENT') THEN 'ORDER'
     ELSE 'DAY'
   END,
   IF(v.is_new,NEW.record_date,OLD.record_date),
   IF(v.is_new,NEW.shop_id,OLD.shop_id),
   IF(v.is_new,NEW.order_id,OLD.order_id),
   IF(v.is_new,NEW.id,OLD.id),
   'finance_records',
   IF(v.is_new,IF(NEW.kind='BILL',NEW.external_id,NEW.statement_id),
     IF(OLD.kind='BILL',OLD.external_id,OLD.statement_id))
 FROM change_flags f CROSS JOIN (SELECT 0 AS is_new UNION ALL SELECT 1) v
 WHERE NOT(f.stable AND f.same_metrics)
   AND (v.is_new OR NOT(f.stable AND NEW.kind IN ('FEE','SETTLEMENT')));
