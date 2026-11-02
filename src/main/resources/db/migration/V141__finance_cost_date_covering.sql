-- Preserve the existing equality/date/page-order prefix. Cost scans and typed
-- counts need these three scalar fields, not the large raw JSON ledger row.
-- A verified online candidate may already have replaced this index at release.
SET @finance_cost_date_covered = (
 SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='finance_records'
   AND index_name='idx_finance_company_kind_date'
   AND column_name IN ('status','category','order_id')
);
SET @finance_cost_date_ddl = IF(@finance_cost_date_covered=3,'DO 0',
 'ALTER TABLE finance_records
  DROP INDEX idx_finance_company_kind_date,
  ADD INDEX idx_finance_company_kind_date (
   organization_id,kind,record_date DESC,id,shop_id,platform,currency,source,status,category,order_id,
   amount,revenue,impressions,clicks,conversions
  ), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE finance_cost_date_stmt FROM @finance_cost_date_ddl;
EXECUTE finance_cost_date_stmt;
DEALLOCATE PREPARE finance_cost_date_stmt;
