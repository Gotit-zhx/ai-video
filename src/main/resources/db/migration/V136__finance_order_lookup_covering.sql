-- Keep the existing equality/date prefix for order lookup and first-settlement
-- checks. The extra scalar fields avoid fetching the large ledger/raw JSON row
-- for each joined order, including rejected first-settlement candidates.
-- Replacing the existing index avoids retaining a redundant second lookup index.
ALTER TABLE finance_records
 DROP INDEX idx_finance_settled_order,
 ADD INDEX idx_finance_settled_order (
  organization_id,shop_id,kind,order_id,source,status,record_date,
  platform,category,currency,amount,revenue,impressions,clicks,conversions
 ),
 ALGORITHM=INPLACE, LOCK=NONE;
