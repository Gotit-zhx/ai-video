CREATE TABLE product_listing_country_fulfillment (
    organization_id CHAR(36) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    country CHAR(2) NOT NULL,
    origin_country CHAR(2) NOT NULL,
    config_json JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by CHAR(36) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id, platform, country, origin_country),
    CONSTRAINT fk_listing_country_fulfillment_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
