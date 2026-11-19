-- Current official metadata is separate from financial facts and confirmed allocations.
CREATE TABLE finance_ad_campaign_metadata (
 organization_id CHAR(36) NOT NULL, advertiser_id VARCHAR(64) NOT NULL,
 campaign_id VARCHAR(64) NOT NULL, data_json JSON NOT NULL,
 observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,advertiser_id,campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- V155 owns finance_query_events. Notify only affected financial days, without
-- altering any amount, source payload, allocation, or projection revision in place.
CREATE TRIGGER fin_qp_ad_campaign_meta_ai AFTER INSERT ON finance_ad_campaign_metadata FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table)
 SELECT DISTINCT NEW.organization_id,'DAY',r.record_date,'finance_ad_campaign_metadata' FROM finance_records r
 WHERE r.organization_id=NEW.organization_id AND r.platform='TIKTOK_SHOP' AND r.kind IN ('AD','AD_GMV')
 AND JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.advertiser_id'))=NEW.advertiser_id
 AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.dimensions.campaign_id')),JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.campaign_id')))=NEW.campaign_id
 UNION SELECT NEW.organization_id,'DAY',i.record_date,'finance_ad_campaign_metadata' FROM finance_ad_spend_imports i
 WHERE i.organization_id=NEW.organization_id AND i.platform='TIKTOK_SHOP' AND i.account_id=NEW.advertiser_id AND i.campaign_id=NEW.campaign_id;

CREATE TRIGGER fin_qp_ad_campaign_meta_au AFTER UPDATE ON finance_ad_campaign_metadata FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table)
 SELECT DISTINCT NEW.organization_id,'DAY',r.record_date,'finance_ad_campaign_metadata' FROM finance_records r
 WHERE NOT(OLD.data_json <=> NEW.data_json) AND r.organization_id=NEW.organization_id AND r.platform='TIKTOK_SHOP' AND r.kind IN ('AD','AD_GMV')
 AND JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.advertiser_id'))=NEW.advertiser_id
 AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.dimensions.campaign_id')),JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.campaign_id')))=NEW.campaign_id
 UNION SELECT NEW.organization_id,'DAY',i.record_date,'finance_ad_campaign_metadata' FROM finance_ad_spend_imports i
 WHERE NOT(OLD.data_json <=> NEW.data_json) AND i.organization_id=NEW.organization_id AND i.platform='TIKTOK_SHOP' AND i.account_id=NEW.advertiser_id AND i.campaign_id=NEW.campaign_id;

CREATE TABLE finance_ad_campaign_jobs (
 organization_id CHAR(36) NOT NULL, advertiser_id VARCHAR(64) NOT NULL,
 campaign_id VARCHAR(64) NOT NULL, is_gmv BOOLEAN NOT NULL DEFAULT FALSE,
 status VARCHAR(24) NOT NULL DEFAULT 'READY', error_message VARCHAR(300) NULL,
 checkpoint_json JSON NULL, failures INT NOT NULL DEFAULT 0,
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL,
 last_success_at DATETIME(6) NULL,
 PRIMARY KEY(organization_id,advertiser_id,campaign_id),
 INDEX idx_ad_metadata_due(next_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TRIGGER fin_qp_ad_campaign_jobs_au AFTER UPDATE ON finance_ad_campaign_jobs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table)
 SELECT DISTINCT NEW.organization_id,'DAY',r.record_date,'finance_ad_campaign_jobs' FROM finance_records r
 WHERE NOT(OLD.status <=> NEW.status AND OLD.error_message <=> NEW.error_message)
 AND r.organization_id=NEW.organization_id AND r.platform='TIKTOK_SHOP' AND r.kind IN ('AD','AD_GMV')
 AND JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.advertiser_id'))=NEW.advertiser_id
 AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.dimensions.campaign_id')),JSON_UNQUOTE(JSON_EXTRACT(r.raw_json,'$.campaign_id')))=NEW.campaign_id
 UNION SELECT NEW.organization_id,'DAY',i.record_date,'finance_ad_campaign_jobs' FROM finance_ad_spend_imports i
 WHERE NOT(OLD.status <=> NEW.status AND OLD.error_message <=> NEW.error_message)
 AND i.organization_id=NEW.organization_id AND i.platform='TIKTOK_SHOP' AND i.account_id=NEW.advertiser_id AND i.campaign_id=NEW.campaign_id;
