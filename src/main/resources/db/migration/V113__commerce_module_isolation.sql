ALTER TABLE product_captures
    ADD COLUMN data_module VARCHAR(24) NOT NULL DEFAULT 'CAPTURE',
    ADD INDEX idx_capture_module (organization_id, data_module, updated_at);

UPDATE product_captures c
JOIN commerce_batch_publications p ON p.organization_id=c.organization_id AND p.capture_id=c.id
SET c.data_module='FISSION';

ALTER TABLE product_listing_templates
    ADD COLUMN data_module VARCHAR(24) NOT NULL DEFAULT 'CAPTURE',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (organization_id, platform, data_module);

ALTER TABLE product_listing_logistics
    ADD COLUMN data_module VARCHAR(24) NOT NULL DEFAULT 'CAPTURE',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (organization_id, shop_id, data_module);

-- Preserve the current defaults as independent starting copies, never a live fallback.
INSERT INTO product_listing_templates
    (organization_id,platform,name,enabled,description,weight_grams,package_length_cm,
     package_width_cm,package_height_cm,version,updated_by,updated_at,size_chart_image_key,data_module)
SELECT t.organization_id,t.platform,t.name,t.enabled,t.description,t.weight_grams,t.package_length_cm,
    t.package_width_cm,t.package_height_cm,t.version,t.updated_by,t.updated_at,t.size_chart_image_key,m.module
FROM product_listing_templates t
CROSS JOIN (SELECT 'FISSION' module UNION ALL SELECT 'TEMPLATES' UNION ALL SELECT 'ADS' UNION ALL SELECT 'ASSETS') m
WHERE t.data_module='CAPTURE';

INSERT INTO product_listing_logistics
    (organization_id,shop_id,selection_mode,channel_ids_json,version,updated_by,updated_at,data_module)
SELECT t.organization_id,t.shop_id,t.selection_mode,t.channel_ids_json,t.version,t.updated_by,t.updated_at,m.module
FROM product_listing_logistics t
CROSS JOIN (SELECT 'FISSION' module UNION ALL SELECT 'TEMPLATES' UNION ALL SELECT 'ADS' UNION ALL SELECT 'ASSETS') m
WHERE t.data_module='CAPTURE';

CREATE TABLE commerce_source_copies (
    organization_id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    source_capture_id CHAR(36) NOT NULL,
    capture_id CHAR(36) NOT NULL,
    PRIMARY KEY (organization_id,batch_id,source_capture_id),
    UNIQUE KEY uk_module_source_copy (capture_id)
);

CREATE TABLE commerce_source_copy_images (
    organization_id CHAR(36) NOT NULL,
    capture_id CHAR(36) NOT NULL,
    source_image_id CHAR(36) NOT NULL,
    image_id CHAR(36) NOT NULL,
    PRIMARY KEY (organization_id,capture_id,source_image_id)
);

CREATE TABLE commerce_material_library_entries (
    organization_id CHAR(36) NOT NULL,
    artwork_id CHAR(36) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    variant_index INT NOT NULL,
    batch_name VARCHAR(120) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    storage_key VARCHAR(160) NOT NULL,
    native_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_by CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id,artwork_id)
);

-- Keep the existing catalogue; subsequent generation no longer adds library entries.
INSERT INTO commerce_material_library_entries
    (organization_id,artwork_id,kind,variant_index,batch_name,content_sha256,storage_key,created_by,created_at)
SELECT j.organization_id,j.id,j.kind,j.variant_index,b.name,j.content_sha256,j.storage_key,e.created_by,j.created_at
FROM commerce_batch_artwork_jobs j
JOIN commerce_batch_executions e ON e.id=j.execution_id AND e.organization_id=j.organization_id
JOIN commerce_batches b ON b.id=e.batch_id AND b.organization_id=j.organization_id
WHERE j.status='READY' AND j.content_sha256 IS NOT NULL AND j.storage_key IS NOT NULL
    AND (j.kind='VARIATE' OR j.kind REGEXP '^VARIATE_G[1-9][0-9]*$');
