UPDATE agent_model_configs
SET provider = 'FAST',
    base_url = 'https://ai.usmercari.com',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE model_type = 'VIDEO' AND provider = 'WENGAO';
