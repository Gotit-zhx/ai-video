-- Aggregate using the compact index without fetching large provider raw_json
-- for every record. Online build keeps marketplace ingestion available.
ALTER TABLE finance_records
 ADD INDEX idx_finance_dashboard (
  organization_id,user_id,record_date,shop_id,platform,kind,currency,source,
  amount,revenue,impressions,clicks,conversions,category,status
 ),
 ALGORITHM=INPLACE, LOCK=NONE;
