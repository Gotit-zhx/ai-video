CREATE TABLE commerce_artwork_matte_guides (
 organization_id VARCHAR(36) NOT NULL,
 source_sha256 VARCHAR(64) NOT NULL,
 background_seeds_json LONGTEXT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (organization_id,source_sha256)
);
