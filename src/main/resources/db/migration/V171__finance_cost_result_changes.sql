-- A source fingerprint, timestamp or revision is an implementation detail.
-- The report journal tracks the actual costing result and its old/new scopes.
DROP TRIGGER fin_qp_order_costs_au;

CREATE TRIGGER fin_qp_order_costs_au AFTER UPDATE ON finance_order_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 WITH change_flags AS (
   SELECT
     NOT (BINARY OLD.organization_id <=> BINARY NEW.organization_id
       AND BINARY OLD.shop_id <=> BINARY NEW.shop_id
       AND BINARY OLD.order_id <=> BINARY NEW.order_id
       AND BINARY OLD.order_record_id <=> BINARY NEW.order_record_id
       AND OLD.record_date <=> NEW.record_date) AS scope_changed,
     NOT (OLD.status <=> NEW.status
       AND OLD.amount_cny <=> NEW.amount_cny
       AND OLD.known_amount_cny <=> NEW.known_amount_cny
       AND OLD.line_count <=> NEW.line_count
       AND OLD.matched_lines <=> NEW.matched_lines
       AND OLD.fallback_lines <=> NEW.fallback_lines
       AND OLD.label_cost_cny <=> NEW.label_cost_cny
       AND OLD.charges_pending <=> NEW.charges_pending
       AND OLD.cost_basis <=> NEW.cost_basis
       AND OLD.data_json <=> NEW.data_json) AS result_changed
 )
 SELECT IF(side.is_new,NEW.organization_id,OLD.organization_id),'ORDER',
   IF(side.is_new,NEW.record_date,OLD.record_date),
   IF(side.is_new,NEW.shop_id,OLD.shop_id),
   IF(side.is_new,NEW.order_id,OLD.order_id),
   IF(side.is_new,NEW.order_record_id,OLD.order_record_id),'finance_order_costs'
 FROM change_flags f CROSS JOIN (SELECT 0 AS is_new UNION ALL SELECT 1) side
 WHERE f.scope_changed OR (f.result_changed AND side.is_new);
