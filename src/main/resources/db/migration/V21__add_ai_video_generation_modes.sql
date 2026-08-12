ALTER TABLE ai_video_tasks
    ADD COLUMN generation_mode VARCHAR(32) NOT NULL DEFAULT 'MODEL_WEAR' AFTER target_market,
    ADD COLUMN product_category VARCHAR(80) NULL AFTER generation_mode,
    ADD COLUMN scene_type VARCHAR(80) NULL AFTER product_category,
    ADD COLUMN interaction_type VARCHAR(80) NULL AFTER scene_type,
    ADD COLUMN allow_hand TINYINT(1) NOT NULL DEFAULT 0 AFTER interaction_type,
    ADD COLUMN aspect_ratio VARCHAR(16) NOT NULL DEFAULT '9:16' AFTER allow_hand,
    ADD COLUMN generation_rules_json LONGTEXT NULL AFTER aspect_ratio,
    ADD COLUMN quality_rules_json LONGTEXT NULL AFTER generation_rules_json;

UPDATE ai_video_tasks
SET generation_mode = 'MODEL_WEAR',
    product_category = COALESCE(product_category, '服装'),
    scene_type = COALESCE(scene_type, '生活方式场景'),
    interaction_type = COALESCE(interaction_type, '人物穿搭展示'),
    aspect_ratio = COALESCE(aspect_ratio, '9:16')
WHERE generation_mode IS NULL OR generation_mode = 'MODEL_WEAR';
