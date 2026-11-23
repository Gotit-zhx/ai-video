-- The label period read only needs order identity and settlement dimensions.
-- Keep the index invisible so other ledger and synchronization plans stay independent.
ALTER TABLE finance_dashboard_order_values
 ADD INDEX idx_dashboard_label_member (
   organization_id,shop_id,order_id,record_date,platform,currency,status
 ) INVISIBLE, ALGORITHM=INPLACE, LOCK=NONE;
