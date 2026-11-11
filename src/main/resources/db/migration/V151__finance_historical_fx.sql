-- Separate from legacy snapshots so their format stays readable by older applications.
CREATE TABLE finance_fx_historical_rates (
 provider VARCHAR(24) NOT NULL,
 rate_date DATE NOT NULL,
 currency CHAR(3) NOT NULL,
 rate_cny DECIMAL(30,16) NOT NULL,
 valid_through_date DATE NOT NULL,
 fetched_at DATETIME(6) NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 PRIMARY KEY(provider,rate_date,currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_fx_historical_snapshots (
 id CHAR(32) NOT NULL PRIMARY KEY,
 provider VARCHAR(24) NOT NULL,
 cutoff_date DATE NULL,
 created_at DATETIME(6) NOT NULL,
 payload_json JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
