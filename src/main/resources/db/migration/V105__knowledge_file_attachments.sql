CREATE TABLE knowledge_files (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, article_id CHAR(36) NULL, category VARCHAR(40) NOT NULL,
 original_name VARCHAR(240) NOT NULL, content_type VARCHAR(120) NOT NULL, extension VARCHAR(12) NOT NULL,
 size_bytes BIGINT NOT NULL, sha256 CHAR(64) NOT NULL, oss_location VARCHAR(1500) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', version INT NOT NULL DEFAULT 1,
 extraction_status VARCHAR(24) NOT NULL, extraction_note VARCHAR(500) NOT NULL DEFAULT '',
 extracted_text MEDIUMTEXT NOT NULL, extracted_characters INT NOT NULL DEFAULT 0,
 uploaded_by CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), removed_by CHAR(36) NULL, removed_at DATETIME(6) NULL,
 CONSTRAINT fk_knowledge_file_org FOREIGN KEY(organization_id) REFERENCES organizations(id),
 CONSTRAINT fk_knowledge_file_article FOREIGN KEY(article_id) REFERENCES knowledge_articles(id),
 CONSTRAINT ck_knowledge_file_status CHECK(status IN ('ACTIVE','REMOVED')),
 CONSTRAINT ck_knowledge_file_extraction CHECK(extraction_status IN ('EXTRACTED','PARTIAL','STORED_ONLY')),
 CONSTRAINT ck_knowledge_file_size CHECK(size_bytes>0 AND size_bytes<=52428800),
 INDEX idx_knowledge_file_article(organization_id,article_id,status,created_at),
 INDEX idx_knowledge_file_category(organization_id,category,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE knowledge_file_events (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, article_id CHAR(36) NULL, category VARCHAR(40) NOT NULL, file_id CHAR(36) NOT NULL,
 file_version INT NOT NULL, action VARCHAR(16) NOT NULL, actor_id CHAR(36) NOT NULL, snapshot_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_knowledge_file_event FOREIGN KEY(file_id) REFERENCES knowledge_files(id),
 CONSTRAINT ck_knowledge_file_action CHECK(action IN ('UPLOAD','BIND','REMOVE')),
 UNIQUE KEY uk_knowledge_file_event(file_id,file_version),
 INDEX idx_knowledge_file_events(organization_id,article_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE knowledge_file_uploads (
 organization_id CHAR(36) NOT NULL, request_id CHAR(36) NOT NULL, file_id CHAR(36) NULL,
 request_sha256 CHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,request_id),
 CONSTRAINT fk_knowledge_upload_org FOREIGN KEY(organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
