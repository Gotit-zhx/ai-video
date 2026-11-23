-- Upload and reconciliation progress live in finance_label_state. Only the
-- published generation contributes to reports.
DROP TRIGGER fin_qp_label_state_ai;
DROP TRIGGER fin_qp_label_state_au;
DROP TRIGGER fin_qp_label_state_ad;
DROP TRIGGER fin_qp_label_order_totals_ai;
DROP TRIGGER fin_qp_label_order_totals_au;
DROP TRIGGER fin_qp_label_order_totals_ad;

CREATE TRIGGER fin_qp_label_order_totals_ai AFTER INSERT ON finance_label_order_totals FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,shop_id,order_id,source_table)
 SELECT NEW.organization_id,'ORDER',NEW.shop_id,NEW.order_id,'finance_label_order_totals'
 WHERE EXISTS (
   SELECT 1 FROM finance_label_state s
   WHERE s.organization_id=NEW.organization_id AND s.active_generation=NEW.generation
 );

CREATE TRIGGER fin_qp_label_order_totals_au AFTER UPDATE ON finance_label_order_totals FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,shop_id,order_id,source_table)
 SELECT s.organization_id,'ORDER',OLD.shop_id,OLD.order_id,'finance_label_order_totals'
 FROM finance_label_state s
 WHERE s.organization_id=OLD.organization_id AND s.active_generation=OLD.generation
 AND NOT (BINARY OLD.organization_id <=> BINARY NEW.organization_id
   AND BINARY OLD.generation <=> BINARY NEW.generation
   AND BINARY OLD.shop_id <=> BINARY NEW.shop_id AND BINARY OLD.order_id <=> BINARY NEW.order_id
   AND OLD.amount_cny <=> NEW.amount_cny AND OLD.pending_count <=> NEW.pending_count
   AND OLD.shipment_count <=> NEW.shipment_count)
 UNION
 SELECT s.organization_id,'ORDER',NEW.shop_id,NEW.order_id,'finance_label_order_totals'
 FROM finance_label_state s
 WHERE s.organization_id=NEW.organization_id AND s.active_generation=NEW.generation
 AND NOT (BINARY OLD.organization_id <=> BINARY NEW.organization_id
   AND BINARY OLD.generation <=> BINARY NEW.generation
   AND BINARY OLD.shop_id <=> BINARY NEW.shop_id AND BINARY OLD.order_id <=> BINARY NEW.order_id
   AND OLD.amount_cny <=> NEW.amount_cny AND OLD.pending_count <=> NEW.pending_count
   AND OLD.shipment_count <=> NEW.shipment_count);

CREATE TRIGGER fin_qp_label_order_totals_ad AFTER DELETE ON finance_label_order_totals FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,shop_id,order_id,source_table)
 SELECT OLD.organization_id,'ORDER',OLD.shop_id,OLD.order_id,'finance_label_order_totals'
 WHERE EXISTS (
   SELECT 1 FROM finance_label_state s
   WHERE s.organization_id=OLD.organization_id AND s.active_generation=OLD.generation
 );

CREATE TRIGGER fin_qp_label_state_ai AFTER INSERT ON finance_label_state FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,shop_id,order_id,source_table)
 SELECT NEW.organization_id,'ORDER',t.shop_id,t.order_id,'finance_label_order_totals'
 FROM finance_label_order_totals t
 WHERE t.organization_id=NEW.organization_id AND t.generation=NEW.active_generation;

-- The new totals and their invalidation are committed with the active pointer.
-- Entries with the same posted amount, gaps and shipment count require no work.
CREATE TRIGGER fin_qp_label_state_au AFTER UPDATE ON finance_label_state FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,shop_id,order_id,source_table)
 SELECT OLD.organization_id,'ORDER',b.shop_id,b.order_id,'finance_label_order_totals'
 FROM finance_label_order_totals b
 LEFT JOIN finance_label_order_totals n
   ON n.organization_id=NEW.organization_id AND n.generation=NEW.active_generation
   AND n.shop_id=b.shop_id AND n.order_id=b.order_id
 WHERE (OLD.organization_id<>NEW.organization_id OR OLD.active_generation<>NEW.active_generation)
 AND b.organization_id=OLD.organization_id AND b.generation=OLD.active_generation
 AND (n.order_id IS NULL OR NOT (
   BINARY OLD.organization_id <=> BINARY NEW.organization_id
   AND BINARY b.shop_id <=> BINARY n.shop_id AND BINARY b.order_id <=> BINARY n.order_id
   AND b.amount_cny <=> n.amount_cny AND b.pending_count <=> n.pending_count
   AND b.shipment_count <=> n.shipment_count))
 UNION ALL
 SELECT NEW.organization_id,'ORDER',n.shop_id,n.order_id,'finance_label_order_totals'
 FROM finance_label_order_totals n
 LEFT JOIN finance_label_order_totals b
   ON b.organization_id=OLD.organization_id AND b.generation=OLD.active_generation
   AND b.shop_id=n.shop_id AND b.order_id=n.order_id
 WHERE (OLD.organization_id<>NEW.organization_id OR OLD.active_generation<>NEW.active_generation)
 AND n.organization_id=NEW.organization_id AND n.generation=NEW.active_generation
 AND (b.order_id IS NULL OR NOT (
   BINARY OLD.organization_id <=> BINARY NEW.organization_id
   AND BINARY b.shop_id <=> BINARY n.shop_id AND BINARY b.order_id <=> BINARY n.order_id));

CREATE TRIGGER fin_qp_label_state_ad AFTER DELETE ON finance_label_state FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,shop_id,order_id,source_table)
 SELECT OLD.organization_id,'ORDER',t.shop_id,t.order_id,'finance_label_order_totals'
 FROM finance_label_order_totals t
 WHERE t.organization_id=OLD.organization_id AND t.generation=OLD.active_generation;
