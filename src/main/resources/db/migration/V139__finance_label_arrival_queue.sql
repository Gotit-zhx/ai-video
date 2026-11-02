-- Order ingestion only publishes a durable notification. Invoice matching runs
-- in bounded worker transactions and cannot hold up platform order ingestion.
CREATE TABLE finance_label_arrivals (
 organization_id CHAR(36) NOT NULL,
 order_id VARCHAR(255) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
