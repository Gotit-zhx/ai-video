CREATE TABLE commerce_batch_executions (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 batch_id VARCHAR(36) NOT NULL,
 created_by VARCHAR(36) NOT NULL,
 stage VARCHAR(24) NOT NULL,
 status VARCHAR(32) NOT NULL,
 message VARCHAR(1000) NOT NULL,
 lease_token VARCHAR(36) NULL,
 lease_until TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_batch_execution (organization_id,batch_id),
 KEY idx_execution_poll (status,updated_at)
);
CREATE TABLE commerce_batch_execution_events (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 execution_id VARCHAR(36) NOT NULL,
 stage VARCHAR(24) NOT NULL,
 status VARCHAR(32) NOT NULL,
 message VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 KEY idx_execution_events (execution_id,created_at)
);
