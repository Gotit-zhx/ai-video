ALTER TABLE agent_model_configs
    ADD COLUMN model_type VARCHAR(32) NOT NULL DEFAULT 'DRIVER' AFTER organization_id,
    ADD CONSTRAINT ck_agent_model_configs_type
        CHECK (model_type IN ('DRIVER', 'VIDEO', 'IMAGE'));

CREATE INDEX idx_agent_model_configs_org_type
    ON agent_model_configs(organization_id, model_type, status);
