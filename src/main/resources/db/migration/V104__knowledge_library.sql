CREATE TABLE knowledge_articles (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL,
 category VARCHAR(40) NOT NULL, title VARCHAR(200) NOT NULL, summary VARCHAR(1000) NOT NULL DEFAULT '',
 body MEDIUMTEXT NOT NULL, language VARCHAR(12) NOT NULL DEFAULT 'zh', platform VARCHAR(24) NOT NULL DEFAULT 'ALL',
 market VARCHAR(12) NOT NULL DEFAULT 'ALL', tags_json JSON NOT NULL,
 source_label VARCHAR(200) NOT NULL DEFAULT '', source_url VARCHAR(2000) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', version INT NOT NULL DEFAULT 1,
 content_sha256 CHAR(64) NOT NULL, retrieval_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_by CHAR(36) NOT NULL, updated_by CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL,
 CONSTRAINT fk_knowledge_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
 CONSTRAINT ck_knowledge_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 CONSTRAINT ck_knowledge_category CHECK (category IN ('shop','influencer','video','system','faq')),
 INDEX idx_knowledge_list (organization_id,category,status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE knowledge_article_versions (
 article_id CHAR(36) NOT NULL, organization_id CHAR(36) NOT NULL, version INT NOT NULL,
 snapshot_json JSON NOT NULL, content_sha256 CHAR(64) NOT NULL, author_id CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(article_id,version),
 CONSTRAINT fk_knowledge_version_article FOREIGN KEY (article_id) REFERENCES knowledge_articles(id),
 INDEX idx_knowledge_versions_tenant (organization_id,article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE knowledge_article_chunks (
 organization_id CHAR(36) NOT NULL, article_id CHAR(36) NOT NULL, article_version INT NOT NULL,
 chunk_index INT NOT NULL, content TEXT NOT NULL, content_sha256 CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(article_id,article_version,chunk_index),
 CONSTRAINT fk_knowledge_chunk_article FOREIGN KEY (article_id) REFERENCES knowledge_articles(id),
 INDEX idx_knowledge_chunk_tenant (organization_id,article_id,article_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO permissions(id,code,name,module,resource_type,description,sort_order) VALUES
 (UUID(),'knowledge:view','查看知识库','KNOWLEDGE','MENU','查看本企业已发布的手册和知识库搜索结果',610),
 (UUID(),'knowledge:edit','编辑知识库','KNOWLEDGE','ACTION','创建、编辑本企业知识库文章及查看历史版本',620),
 (UUID(),'knowledge:publish','发布知识库','KNOWLEDGE','ACTION','发布和归档本企业知识库文章',630);
INSERT IGNORE INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE p.code='knowledge:view'
 OR (r.code IN ('ADMIN','PLATFORM_ADMIN') AND p.code IN ('knowledge:edit','knowledge:publish'));
