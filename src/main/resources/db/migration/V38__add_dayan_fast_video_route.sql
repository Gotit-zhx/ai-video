INSERT IGNORE INTO agent_model_configs (
    id, organization_id, model_type, provider, base_url, model_id, api_format,
    api_key_ciphertext, key_hint, status, default_model, created_at, updated_at
)
SELECT UUID(), organization_id, model_type, 'DAYAN', base_url, 'minimax H3 Fast', api_format,
       api_key_ciphertext, key_hint, 'ACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM agent_model_configs
WHERE model_type = 'VIDEO'
  AND provider = 'DAYAN'
  AND status = 'ACTIVE'
  AND LOWER(model_id) <> 'minimax h3 fast';
