-- Deleted captures remain available to historical audits and their child rows,
-- but must not prevent a new capture with the same organization/request key.
-- NULL permits multiple deleted generations; 1 keeps live requests unique.
ALTER TABLE product_captures
    DROP INDEX uk_product_captures_org_idempotency,
    ADD UNIQUE KEY uk_product_captures_org_idempotency
        (organization_id, idempotency_key, (IF(deleted_at IS NULL, 1, NULL)));
