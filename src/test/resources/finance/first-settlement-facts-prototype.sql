CREATE TABLE finance_first_settlement_facts (
 organization_id CHAR(36) NOT NULL,
 shop_id CHAR(36) NOT NULL,
 order_id VARCHAR(128) NOT NULL,
 requested_revision BIGINT NOT NULL DEFAULT 1,
 applied_revision BIGINT NOT NULL DEFAULT 0,
 has_settlement BOOLEAN NOT NULL DEFAULT FALSE,
 first_record_id CHAR(64) NULL,
 first_record_date DATE NULL,
 first_platform VARCHAR(24) NULL,
 first_currency VARCHAR(8) NULL,
 schema_version INT NOT NULL DEFAULT 0,
 applied_generation BIGINT NOT NULL DEFAULT 0,
 applied_registration_version INT NOT NULL DEFAULT 0,
 queued_generation BIGINT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 pending BOOLEAN GENERATED ALWAYS AS (requested_revision > applied_revision) STORED,
 PRIMARY KEY(organization_id,shop_id,order_id),
 KEY idx_fsf_pending(organization_id,pending,shop_id,order_id),
 KEY idx_fsf_date(organization_id,first_record_date,shop_id,order_id),
 CHECK(requested_revision > 0 AND applied_revision >= 0 AND applied_revision <= requested_revision),
 CHECK((has_settlement AND first_record_id IS NOT NULL AND first_record_date IS NOT NULL
        AND first_platform IS NOT NULL AND first_currency IS NOT NULL)
    OR (NOT has_settlement AND first_record_id IS NULL AND first_record_date IS NULL
        AND first_platform IS NULL AND first_currency IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_first_settlement_registration (
 id TINYINT NOT NULL PRIMARY KEY CHECK(id=1),
 schema_version INT NOT NULL,
 registration_version INT NOT NULL,
 definition_sha CHAR(64) NOT NULL,
 installation_boundary VARCHAR(128) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_first_settlement_initialization (
 organization_id CHAR(36) NOT NULL PRIMARY KEY,
 generation BIGINT NOT NULL DEFAULT 0,
 target_schema_version INT NOT NULL DEFAULT 0,
 registration_version INT NOT NULL DEFAULT 0,
 registration_definition_sha CHAR(64) NULL,
 registration_boundary VARCHAR(128) NULL,
 seed_complete BOOLEAN NOT NULL DEFAULT FALSE,
 shop_after CHAR(36) NULL,
 current_shop CHAR(36) NULL,
 order_after VARCHAR(128) NULL,
 shops_complete BOOLEAN NOT NULL DEFAULT FALSE,
 requeue_shop_after CHAR(36) NULL,
 requeue_order_after VARCHAR(128) NULL,
 requeue_complete BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$

CREATE TRIGGER fin_fsf_records_ai AFTER INSERT ON finance_records FOR EACH ROW
BEGIN
 IF NEW.kind='SETTLEMENT' AND NEW.source='API' AND NEW.status='SETTLED'
    AND NEW.order_id<>''
    AND (NEW.platform<>'TIKTOK_SHOP' OR NEW.category='ORDER') THEN
  INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
  VALUES(NEW.organization_id,NEW.shop_id,NEW.order_id)
  ON DUPLICATE KEY UPDATE
    requested_revision=requested_revision+1,
    updated_at=UTC_TIMESTAMP(6);
 END IF;
END$$

CREATE TRIGGER fin_fsf_records_au AFTER UPDATE ON finance_records FOR EACH ROW
BEGIN
 DECLARE old_eligible BOOLEAN;
 DECLARE new_eligible BOOLEAN;
 DECLARE identity_relevant_change BOOLEAN;

 SET old_eligible = OLD.kind='SETTLEMENT' AND OLD.source='API' AND OLD.status='SETTLED'
    AND OLD.order_id<>''
    AND (OLD.platform<>'TIKTOK_SHOP' OR OLD.category='ORDER');
 SET new_eligible = NEW.kind='SETTLEMENT' AND NEW.source='API' AND NEW.status='SETTLED'
    AND NEW.order_id<>''
    AND (NEW.platform<>'TIKTOK_SHOP' OR NEW.category='ORDER');
 SET identity_relevant_change = NOT (
    OLD.organization_id <=> NEW.organization_id AND OLD.shop_id <=> NEW.shop_id
    AND OLD.order_id <=> NEW.order_id AND OLD.kind <=> NEW.kind
    AND OLD.source <=> NEW.source AND OLD.status <=> NEW.status
    AND OLD.platform <=> NEW.platform AND OLD.category <=> NEW.category
    AND OLD.record_date <=> NEW.record_date AND OLD.id <=> NEW.id
    AND OLD.currency <=> NEW.currency);

 IF identity_relevant_change AND (old_eligible OR new_eligible) THEN
  IF old_eligible AND new_eligible
     AND OLD.organization_id <=> NEW.organization_id
     AND OLD.shop_id <=> NEW.shop_id
     AND OLD.order_id <=> NEW.order_id THEN
   INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
   VALUES(OLD.organization_id,OLD.shop_id,OLD.order_id)
   ON DUPLICATE KEY UPDATE
     requested_revision=requested_revision+1,
     updated_at=UTC_TIMESTAMP(6);
  ELSEIF old_eligible AND new_eligible THEN
   IF (OLD.organization_id,OLD.shop_id,OLD.order_id)
      < (NEW.organization_id,NEW.shop_id,NEW.order_id) THEN
    INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
    VALUES(OLD.organization_id,OLD.shop_id,OLD.order_id)
    ON DUPLICATE KEY UPDATE
      requested_revision=requested_revision+1,
      updated_at=UTC_TIMESTAMP(6);
    INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
    VALUES(NEW.organization_id,NEW.shop_id,NEW.order_id)
    ON DUPLICATE KEY UPDATE
      requested_revision=requested_revision+1,
      updated_at=UTC_TIMESTAMP(6);
   ELSE
    INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
    VALUES(NEW.organization_id,NEW.shop_id,NEW.order_id)
    ON DUPLICATE KEY UPDATE
      requested_revision=requested_revision+1,
      updated_at=UTC_TIMESTAMP(6);
    INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
    VALUES(OLD.organization_id,OLD.shop_id,OLD.order_id)
    ON DUPLICATE KEY UPDATE
      requested_revision=requested_revision+1,
      updated_at=UTC_TIMESTAMP(6);
   END IF;
  ELSEIF old_eligible THEN
   INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
   VALUES(OLD.organization_id,OLD.shop_id,OLD.order_id)
   ON DUPLICATE KEY UPDATE
     requested_revision=requested_revision+1,
     updated_at=UTC_TIMESTAMP(6);
  ELSE
   INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
   VALUES(NEW.organization_id,NEW.shop_id,NEW.order_id)
   ON DUPLICATE KEY UPDATE
     requested_revision=requested_revision+1,
     updated_at=UTC_TIMESTAMP(6);
  END IF;
 END IF;
END$$

CREATE TRIGGER fin_fsf_records_ad AFTER DELETE ON finance_records FOR EACH ROW
BEGIN
 IF OLD.kind='SETTLEMENT' AND OLD.source='API' AND OLD.status='SETTLED'
    AND OLD.order_id<>''
    AND (OLD.platform<>'TIKTOK_SHOP' OR OLD.category='ORDER') THEN
  INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id)
  VALUES(OLD.organization_id,OLD.shop_id,OLD.order_id)
  ON DUPLICATE KEY UPDATE
    requested_revision=requested_revision+1,
    updated_at=UTC_TIMESTAMP(6);
 END IF;
END$$

DELIMITER ;
