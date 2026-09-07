CREATE TABLE commerce_design_reviews (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 output_id VARCHAR(36) NOT NULL,
 content_sha256 CHAR(64) NOT NULL,
 decision VARCHAR(16) NOT NULL,
 checks_json JSON NOT NULL,
 notes VARCHAR(2000) NOT NULL,
 reviewed_by VARCHAR(36) NOT NULL,
 reviewed_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_design_output_review (organization_id,output_id)
);
