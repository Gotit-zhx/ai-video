-- Keep the wide settled-order index for its existing covering lookup consumers.
-- A separate first-row index orders ties by id before the covering attributes.
ALTER TABLE finance_records
 ADD INDEX idx_finance_first_settlement (
  organization_id,shop_id,kind,order_id,source,status,record_date,id,
  platform,category,currency
 ),
 ALGORITHM=INPLACE, LOCK=NONE;
