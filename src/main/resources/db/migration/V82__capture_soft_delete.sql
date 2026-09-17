ALTER TABLE product_captures ADD COLUMN deleted_at TIMESTAMP(6) NULL;
CREATE INDEX idx_product_captures_visible ON product_captures(organization_id,deleted_at,updated_at);
