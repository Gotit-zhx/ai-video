-- Change the capture while source writers are drained. The general report queue still receives every event.
DROP TRIGGER fin_dashboard_order_event_ai;

CREATE TRIGGER fin_dashboard_order_event_ai AFTER INSERT ON finance_query_events FOR EACH ROW
 INSERT INTO finance_dashboard_order_events(event_id,organization_id,event_scope,shop_id,order_id,record_id)
 SELECT NEW.event_id,NEW.organization_id,NEW.event_scope,NEW.shop_id,NEW.order_id,NEW.record_id
 FROM finance_dashboard_order_companies c
 WHERE c.organization_id=NEW.organization_id AND c.enabled AND
   (NEW.event_scope='ORDER' OR (NEW.event_scope='ALL' AND NEW.source_table NOT IN (
     'finance_ad_accounts','finance_ad_stores','finance_ad_spend_allocation_sets','finance_ad_spend_allocations',
     'finance_connections','finance_groups','finance_expenses'
   )));
