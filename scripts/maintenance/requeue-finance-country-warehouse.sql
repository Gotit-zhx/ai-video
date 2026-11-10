-- One-time post-release requeue through the existing bounded worker, under both release locks.
-- Run only after verifying that the country-matching fix is active. This is not a schema migration.
-- Never change prices, manual settings, source records, or platform checkpoints here.
INSERT INTO finance_cost_rebuilds(organization_id,shop_id)
SELECT c.organization_id,c.id
FROM finance_connections c
LEFT JOIN finance_cost_settings s ON s.organization_id=c.organization_id AND s.shop_id=c.id
WHERE c.status<>'REMOVED'
  AND UPPER(c.region) IN ('PH','TH','MY','VN','SG','ID')
  AND TRIM(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(s.data_json,'$.warehouse')),''))=''
  AND EXISTS (
    SELECT 1 FROM finance_cost_books b
    WHERE b.organization_id=c.organization_id AND b.group_id=c.group_id
  )
  AND EXISTS (
    SELECT 1 FROM finance_order_costs oc
    WHERE oc.organization_id=c.organization_id AND oc.shop_id=c.id
      AND oc.cost_basis='MERCHANT_SKU_STANDARD'
  )
ON DUPLICATE KEY UPDATE cursor_id='',status='READY',requested_at=UTC_TIMESTAMP(6);
