CREATE TABLE product_sku_pattern_codes (
 organization_id CHAR(36) NOT NULL,
 pattern_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 style_code CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 random_suffix CHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,pattern_key),
 UNIQUE KEY uk_sku_pattern_style(organization_id,style_code),
 UNIQUE KEY uk_sku_pattern_suffix(organization_id,random_suffix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
