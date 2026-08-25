ALTER TABLE video_scripts
    ADD COLUMN presentation_mode VARCHAR(32) NOT NULL DEFAULT 'LEGACY_COMPAT' AFTER source_type;

-- Historical scripts predate the explicit choice and must remain selectable in either workflow.
-- Every newly created script is validated and stored as MODEL_WEAR or PRODUCT_ONLY by the service.
