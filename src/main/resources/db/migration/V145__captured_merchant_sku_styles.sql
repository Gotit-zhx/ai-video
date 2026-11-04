CREATE TABLE product_sku_styles (
 organization_id CHAR(36) NOT NULL,
 pattern_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 style_code CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,pattern_key),
 UNIQUE KEY uk_sku_style_code(organization_id,style_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
