CREATE INDEX idx_product_captures_org_status_updated
    ON product_captures (organization_id, status, updated_at);
