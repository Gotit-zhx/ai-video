-- Match only the selected bills and their transactions without reading provider JSON.
ALTER TABLE finance_records
 ADD INDEX idx_finance_reconcile_bill (
  organization_id,user_id,shop_id,kind,source,external_id,currency,status
 ),
 ADD INDEX idx_finance_reconcile_tx (
  organization_id,user_id,shop_id,statement_id,kind,source,currency,amount
 ),
 ALGORITHM=INPLACE, LOCK=NONE;
