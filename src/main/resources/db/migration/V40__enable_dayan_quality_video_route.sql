UPDATE agent_model_configs
SET status = 'ACTIVE',
    default_model = FALSE,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE model_type = 'VIDEO'
  AND provider = 'DAYAN'
  AND LOWER(model_id) = 'minimax-h3';
