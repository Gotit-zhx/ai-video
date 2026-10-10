CREATE TABLE product_listing_logistics (
    organization_id CHAR(36) NOT NULL,
    shop_id CHAR(36) NOT NULL,
    selection_mode VARCHAR(20) NOT NULL,
    channel_ids_json JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by CHAR(36) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id, shop_id),
    CONSTRAINT fk_listing_logistics_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_listing_logistics_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
