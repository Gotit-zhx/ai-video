ALTER TABLE product_capture_skus
    ADD COLUMN image_url TEXT NULL AFTER stock,
    ADD COLUMN image_source VARCHAR(60) NULL AFTER image_url;
