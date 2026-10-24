-- Company-shared BI reads filter by type/date, without a user_id predicate.
-- Preserve the mixed-direction page ordering and cover count/filter operations.
ALTER TABLE finance_records
 ADD INDEX idx_finance_company_kind_date (
  organization_id,kind,record_date DESC,id,shop_id,platform,currency,source
 ),
 ADD INDEX idx_finance_settled_order (
  organization_id,shop_id,kind,order_id,source,status,record_date
 ),
 ALGORITHM=INPLACE, LOCK=NONE;
