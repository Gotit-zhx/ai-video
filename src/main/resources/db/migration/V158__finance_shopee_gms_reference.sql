-- Additive reference facts. Existing CPC amounts, raw reports and checkpoints are retained.
-- Depends on the frozen V155 query event contract (PR #65). Fail before any DDL if it is absent.
SELECT organization_id,event_scope,record_date,source_table FROM finance_query_events LIMIT 0;

CREATE TABLE finance_shopee_gms_sync (
 organization_id CHAR(36) NOT NULL, external_shop_id VARCHAR(30) NOT NULL,
 campaign_ids_json JSON NULL, campaign_revision CHAR(64) NOT NULL DEFAULT '',
 checkpoint_json JSON NULL, status VARCHAR(32) NOT NULL DEFAULT 'READY',
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), last_success_at DATETIME(6) NULL,
 failures INT NOT NULL DEFAULT 0, error_message VARCHAR(500) NULL,
 PRIMARY KEY(organization_id,external_shop_id), INDEX idx_shopee_gms_due(next_at),
 CONSTRAINT fk_shopee_gms_sync_grant FOREIGN KEY(organization_id,external_shop_id)
 REFERENCES finance_shopee_ad_grants(organization_id,external_shop_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_shopee_gms_daily (
 organization_id CHAR(36) NOT NULL, external_shop_id VARCHAR(30) NOT NULL,
 record_date DATE NOT NULL, currency CHAR(3) NOT NULL, amount DECIMAL(24,6) NOT NULL,
 campaign_revision CHAR(64) NOT NULL, source_json JSON NOT NULL,
 observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,external_shop_id,record_date),
 INDEX idx_shopee_gms_period(organization_id,record_date,currency),
 CONSTRAINT fk_shopee_gms_daily_grant FOREIGN KEY(organization_id,external_shop_id)
 REFERENCES finance_shopee_ad_grants(organization_id,external_shop_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TRIGGER fin_qp_shopee_gms_ai AFTER INSERT ON finance_shopee_gms_daily FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table)
 VALUES(NEW.organization_id,'DAY',NEW.record_date,'finance_shopee_gms_daily');

CREATE TRIGGER fin_qp_shopee_gms_au AFTER UPDATE ON finance_shopee_gms_daily FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table)
 SELECT OLD.organization_id,'DAY',OLD.record_date,'finance_shopee_gms_daily'
 WHERE NOT(OLD.organization_id <=> NEW.organization_id AND OLD.external_shop_id <=> NEW.external_shop_id AND OLD.record_date <=> NEW.record_date AND OLD.currency <=> NEW.currency AND OLD.amount <=> NEW.amount AND OLD.campaign_revision <=> NEW.campaign_revision)
 UNION ALL SELECT NEW.organization_id,'DAY',NEW.record_date,'finance_shopee_gms_daily'
 WHERE NOT(OLD.organization_id <=> NEW.organization_id AND OLD.external_shop_id <=> NEW.external_shop_id AND OLD.record_date <=> NEW.record_date AND OLD.currency <=> NEW.currency AND OLD.amount <=> NEW.amount AND OLD.campaign_revision <=> NEW.campaign_revision);

CREATE TRIGGER fin_qp_shopee_gms_ad AFTER DELETE ON finance_shopee_gms_daily FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table)
 VALUES(OLD.organization_id,'DAY',OLD.record_date,'finance_shopee_gms_daily');

-- A larger identified set makes previous G1-only facts unknown until G1+G2 is read.
CREATE TRIGGER fin_qp_shopee_gms_scope_au AFTER UPDATE ON finance_shopee_gms_sync FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table)
 SELECT d.organization_id,'DAY',d.record_date,'finance_shopee_gms_sync'
 FROM finance_shopee_gms_daily d WHERE d.organization_id=OLD.organization_id AND d.external_shop_id=OLD.external_shop_id
 AND NOT(OLD.campaign_revision <=> NEW.campaign_revision);

-- MySQL does not run child DELETE triggers for FK cascades.
CREATE TRIGGER fin_qp_shopee_gms_grant_bd BEFORE DELETE ON finance_shopee_ad_grants FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table)
 SELECT d.organization_id,'DAY',d.record_date,'finance_shopee_gms_daily'
 FROM finance_shopee_gms_daily d WHERE d.organization_id=OLD.organization_id AND d.external_shop_id=OLD.external_shop_id;

-- Explicit resume or a new OAuth group retries GMS independently of the CPC checkpoint.
CREATE TRIGGER fin_shopee_gms_grant_au AFTER UPDATE ON finance_shopee_ad_grants FOR EACH ROW
 UPDATE finance_shopee_gms_sync SET next_at=UTC_TIMESTAMP(6),status='READY',error_message=NULL
 WHERE organization_id=NEW.organization_id AND external_shop_id=NEW.external_shop_id AND NEW.enabled=TRUE
 AND (OLD.enabled=FALSE OR NOT(OLD.token_group_id <=> NEW.token_group_id) OR NOT(OLD.partner_id <=> NEW.partner_id));

INSERT INTO finance_shopee_gms_sync(organization_id,external_shop_id)
 SELECT organization_id,external_shop_id FROM finance_shopee_ad_grants WHERE status<>'REMOVED';

-- Invalidate old basis partitions/report cache through the existing versioned event path.
INSERT INTO finance_query_events(organization_id,event_scope,source_table)
 SELECT organization_id,'ALL','shopee_cpc_minus_gms_v1' FROM (
 SELECT organization_id FROM finance_shopee_ad_grants WHERE status<>'REMOVED'
 UNION SELECT organization_id FROM finance_records WHERE platform='SHOPEE' AND kind IN ('AD','AD_GMV')
 UNION SELECT organization_id FROM finance_ad_spend_imports WHERE platform='SHOPEE'
 ) shopee_scopes;
