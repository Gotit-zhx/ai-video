-- Business groups are independent of the credential rotation token_group_id.
CREATE TABLE finance_groups (
 id CHAR(36) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL,
 name VARCHAR(80) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_finance_group_name(organization_id,name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE finance_connections
 ADD COLUMN group_id CHAR(36) NULL,
 ADD INDEX idx_finance_business_group(organization_id,group_id,status),
 ADD INDEX idx_finance_company_token_group(organization_id,token_group_id),
 ADD UNIQUE KEY uk_finance_company_shop(organization_id,platform,external_shop_id);

ALTER TABLE finance_ad_accounts
 ADD UNIQUE KEY uk_finance_company_ad(organization_id,advertiser_id);

ALTER TABLE finance_ad_applications
 ADD UNIQUE KEY uk_finance_company_ad_app(organization_id);

-- Keep historical owner IDs and credential AAD intact. Reads now cover the company.
ALTER TABLE finance_records
 ADD INDEX idx_finance_company_dashboard (
  organization_id,record_date,shop_id,platform,kind,currency,source,
  amount,revenue,impressions,clicks,conversions,category,status
 ),
 ALGORITHM=INPLACE, LOCK=NONE;

INSERT IGNORE INTO permissions(id,code,name,module,resource_type,description,sort_order)
 VALUES(UUID(),'finance:view','查看财务 BI','FINANCE','MENU','查看公司共享财务数据、按店铺分组统计并导出报表',720),
 (UUID(),'finance:manage','管理财务配置','FINANCE','ACTION','管理公司财务店铺、分组、授权、广告账户及账单导入',721);
