-- The coverage marker, base facts and unapplied contributions share one MVCC snapshot.
CREATE TABLE finance_fee_daily_coverage (
    organization_id CHAR(36) NOT NULL,
    record_date DATE NOT NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    folded_at DATETIME(6) NULL,
    PRIMARY KEY (organization_id,record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_fee_daily_facts (
    organization_id CHAR(36) COLLATE utf8mb4_0900_ai_ci NOT NULL,
    record_date DATE NOT NULL,
    shop_id CHAR(36) NOT NULL,
    platform VARCHAR(24) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    category VARCHAR(100) NOT NULL,
    records BIGINT NOT NULL,
    known_amounts BIGINT NOT NULL,
    known_revenues BIGINT NOT NULL,
    known_impressions BIGINT NOT NULL,
    known_clicks BIGINT NOT NULL,
    known_conversions BIGINT NOT NULL,
    amount DECIMAL(65,6) NOT NULL,
    revenue DECIMAL(65,6) NOT NULL,
    impressions DECIMAL(65,0) NOT NULL,
    clicks DECIMAL(65,0) NOT NULL,
    conversions DECIMAL(65,6) NOT NULL,
    PRIMARY KEY (organization_id,record_date,shop_id,platform,kind,currency,category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Append signed contributions without locking the shop/day facts on source writes.
-- Identities acknowledge exactly the visible rows and do not imply commit order.
CREATE TABLE finance_fee_daily_changes (
    change_id BIGINT NOT NULL AUTO_INCREMENT,
    organization_id CHAR(36) COLLATE utf8mb4_0900_ai_ci NOT NULL,
    record_date DATE NOT NULL,
    shop_id CHAR(36) NOT NULL,
    platform VARCHAR(24) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    category VARCHAR(100) NOT NULL,
    direction TINYINT NOT NULL,
    amount DECIMAL(24,6) NULL,
    revenue DECIMAL(24,6) NULL,
    impressions BIGINT NULL,
    clicks BIGINT NULL,
    conversions DECIMAL(24,6) NULL,
    PRIMARY KEY (change_id),
    KEY idx_fee_change_day (organization_id,record_date,change_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TRIGGER fin_fee_daily_ai AFTER INSERT ON finance_records FOR EACH ROW
    INSERT INTO finance_fee_daily_changes
     (organization_id,record_date,shop_id,platform,kind,currency,category,direction,amount,revenue,impressions,clicks,conversions)
    SELECT NEW.organization_id,NEW.record_date,NEW.shop_id,NEW.platform,NEW.kind,NEW.currency,NEW.category,1,
           NEW.amount,NEW.revenue,NEW.impressions,NEW.clicks,NEW.conversions
    FROM DUAL
    WHERE NEW.kind='FEE' AND NEW.status<>'DATE_PENDING';

CREATE TRIGGER fin_fee_daily_ad AFTER DELETE ON finance_records FOR EACH ROW
    INSERT INTO finance_fee_daily_changes
     (organization_id,record_date,shop_id,platform,kind,currency,category,direction,amount,revenue,impressions,clicks,conversions)
    SELECT OLD.organization_id,OLD.record_date,OLD.shop_id,OLD.platform,OLD.kind,OLD.currency,OLD.category,-1,
           OLD.amount,OLD.revenue,OLD.impressions,OLD.clicks,OLD.conversions
    FROM DUAL
    WHERE OLD.kind='FEE' AND OLD.status<>'DATE_PENDING';

CREATE TRIGGER fin_fee_daily_au AFTER UPDATE ON finance_records FOR EACH ROW
    INSERT INTO finance_fee_daily_changes
     (organization_id,record_date,shop_id,platform,kind,currency,category,direction,amount,revenue,impressions,clicks,conversions)
    SELECT IF(v.is_new,NEW.organization_id,OLD.organization_id),IF(v.is_new,NEW.record_date,OLD.record_date),IF(v.is_new,NEW.shop_id,OLD.shop_id),
           IF(v.is_new,NEW.platform,OLD.platform),IF(v.is_new,NEW.kind,OLD.kind),IF(v.is_new,NEW.currency,OLD.currency),IF(v.is_new,NEW.category,OLD.category),IF(v.is_new,1,-1),
           IF(v.is_new,NEW.amount,OLD.amount),IF(v.is_new,NEW.revenue,OLD.revenue),IF(v.is_new,NEW.impressions,OLD.impressions),IF(v.is_new,NEW.clicks,OLD.clicks),IF(v.is_new,NEW.conversions,OLD.conversions)
    FROM (SELECT 0 is_new UNION ALL SELECT 1) v
    WHERE IF(v.is_new,NEW.kind='FEE' AND NEW.status<>'DATE_PENDING',OLD.kind='FEE' AND OLD.status<>'DATE_PENDING')
      AND NOT (
        (OLD.kind='FEE' AND OLD.status<>'DATE_PENDING') <=> (NEW.kind='FEE' AND NEW.status<>'DATE_PENDING')
        AND HEX(OLD.organization_id) <=> HEX(NEW.organization_id)
        AND OLD.record_date <=> NEW.record_date
        AND HEX(OLD.shop_id) <=> HEX(NEW.shop_id)
        AND HEX(OLD.platform) <=> HEX(NEW.platform)
        AND HEX(OLD.kind) <=> HEX(NEW.kind)
        AND HEX(OLD.currency) <=> HEX(NEW.currency)
        AND HEX(OLD.category) <=> HEX(NEW.category)
        AND OLD.amount <=> NEW.amount
        AND OLD.revenue <=> NEW.revenue
        AND OLD.impressions <=> NEW.impressions
        AND OLD.clicks <=> NEW.clicks
        AND OLD.conversions <=> NEW.conversions
      );
