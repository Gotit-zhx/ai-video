CREATE TABLE product_listing_templates (
    organization_id CHAR(36) NOT NULL,
    platform VARCHAR(24) NOT NULL,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    description TEXT NOT NULL,
    weight_grams DECIMAL(12,3) NOT NULL,
    package_length_cm DECIMAL(10,2) NOT NULL,
    package_width_cm DECIMAL(10,2) NOT NULL,
    package_height_cm DECIMAL(10,2) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by CHAR(36) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id, platform),
    CONSTRAINT fk_listing_template_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
