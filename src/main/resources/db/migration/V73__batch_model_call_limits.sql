CREATE TABLE commerce_batch_model_limits (
 execution_id VARCHAR(36) PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 max_calls INT NOT NULL,
 reserved_calls INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE commerce_batch_model_reservations (
 execution_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(100) NOT NULL,
 stage VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(execution_id,request_key)
);
