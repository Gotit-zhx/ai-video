UPDATE agent_model_configs
SET model_id = 'minimax-h3-fast',
    status = 'ACTIVE',
    default_model = TRUE,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE model_type = 'VIDEO'
  AND provider = 'DAYAN'
  AND LOWER(REPLACE(model_id, ' ', '-')) = 'minimax-h3-fast';

UPDATE agent_model_configs
SET status = 'DISABLED',
    default_model = FALSE,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE model_type = 'VIDEO'
  AND NOT (provider = 'DAYAN' AND LOWER(model_id) = 'minimax-h3-fast');
