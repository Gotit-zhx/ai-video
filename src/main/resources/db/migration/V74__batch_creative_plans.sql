CREATE TABLE commerce_batch_creative_plans (
 id VARCHAR(36) PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 batch_id VARCHAR(36) NOT NULL,
 version INT NOT NULL,
 plan_json JSON NOT NULL,
 created_by VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_batch_creative_version (organization_id, batch_id, version)
);
CREATE TABLE commerce_batch_execution_plans (
 execution_id VARCHAR(36) PRIMARY KEY,
 plan_id VARCHAR(36) NOT NULL,
 CONSTRAINT fk_execution_creative_plan FOREIGN KEY (plan_id) REFERENCES commerce_batch_creative_plans(id)
);
