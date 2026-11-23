ALTER TABLE product_main_image_templates
    ADD COLUMN capture_template_id CHAR(36) NULL,
    ADD COLUMN active_capture_template_id CHAR(36) GENERATED ALWAYS AS
        (CASE WHEN archived = FALSE THEN capture_template_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_main_style_active_capture (organization_id, active_capture_template_id),
    ADD CONSTRAINT fk_main_style_capture_template FOREIGN KEY (organization_id, capture_template_id)
        REFERENCES product_capture_templates (organization_id, id);

ALTER TABLE product_listing_templates
    ADD COLUMN capture_template_id CHAR(36) NOT NULL DEFAULT '',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (organization_id, platform, data_module, capture_template_id);

ALTER TABLE product_listing_logistics
    ADD COLUMN capture_template_id CHAR(36) NOT NULL DEFAULT '',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (organization_id, shop_id, data_module, capture_template_id);

ALTER TABLE product_listing_warehouses
    ADD COLUMN capture_template_id CHAR(36) NOT NULL DEFAULT '',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (organization_id, shop_id, data_module, capture_template_id);
