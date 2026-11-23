-- Same zero-price rule as FinanceCostReport.zeroPriceReview. Keep full cost JSON
-- authoritative for detail and export. Readiness can use the narrow index.
ALTER TABLE finance_order_costs
 ADD COLUMN zero_price_review TINYINT GENERATED ALWAYS AS (
   CASE WHEN status='INCOMPLETE' THEN COALESCE(
     JSON_CONTAINS(JSON_ARRAY('成本表为零值，需核对单价'),JSON_EXTRACT(data_json,'$.lines[*].issue')),0)
   ELSE 0 END
 ) VIRTUAL,
 ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE finance_order_costs
 ADD INDEX idx_finance_cost_readiness (order_record_id,organization_id,status,updated_at,zero_price_review) INVISIBLE,
 ALGORITHM=INPLACE, LOCK=NONE;
