-- Official Shopee Return Refund Management flow:
-- https://open.shopee.com/developer-guide/227
-- ACCEPTED confirms the refund. CLOSED is a case closed without refund.
-- Recover from the original platform payload without refetching or creating records.
UPDATE finance_records
SET amount=CASE
 WHEN status='ACCEPTED'
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.refund_amount')) REGEXP '^-?[0-9]{1,18}([.][0-9]{1,6})?$'
 THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.refund_amount')) AS DECIMAL(24,6))
 ELSE NULL END,
 updated_at=UTC_TIMESTAMP(6)
WHERE platform='SHOPEE' AND kind='REFUND' AND source='API' AND status IN ('ACCEPTED','CLOSED');
