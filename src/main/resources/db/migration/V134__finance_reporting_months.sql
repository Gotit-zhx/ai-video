-- Explicit reporting-month corrections, separate from original platform timestamps.
CREATE TABLE finance_reporting_months (
    organization_id CHAR(36) NOT NULL,
    shop_id CHAR(36) NOT NULL,
    order_id VARCHAR(128) NOT NULL,
    statement_id VARCHAR(128) NOT NULL,
    reporting_month DATE NOT NULL,
    source VARCHAR(40) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    confirmed_by VARCHAR(80) NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id, shop_id, order_id, statement_id),
    KEY idx_finance_reporting_month (organization_id, reporting_month, shop_id),
    CONSTRAINT chk_finance_reporting_month CHECK (DAYOFMONTH(reporting_month)=1)
);
