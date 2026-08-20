UPDATE agent_model_configs
SET status = 'DISABLED', default_model = FALSE, updated_at = CURRENT_TIMESTAMP(6)
WHERE model_type = 'VIDEO' AND provider = 'FAST';
