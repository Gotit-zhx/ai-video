package com.yingdao.aicommerce.product.application;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic pre-V113 data; never reads or copies a production database. */
final class ModuleMigrationFixture {
    static void prepare(DataSource source, boolean through114) {
        var db=new JdbcTemplate(source);
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)!=0)
            throw new IllegalStateException("Migration fixture requires a new empty database");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("112").load().migrate();
        db.update("INSERT INTO organizations(id,code,name) VALUES('test-org','synthetic','Synthetic migration tenant')");
        db.update("INSERT INTO app_users(id,organization_id,username,display_name) VALUES('test-user','test-org','migration-user','Synthetic user')");
        db.update("INSERT INTO shops(id,organization_id,platform,name,region) VALUES('test-shop','test-org','SHOPEE','Synthetic shop','PH')");
        db.update("""
            INSERT INTO product_captures(id,organization_id,created_by,idempotency_key,request_fingerprint,
              source_platform,source_url,source_product_id,status,title,normalized_data,raw_data,created_at,updated_at)
            VALUES('test-source','test-org','test-user','fixture-source',REPEAT('a',64),'SHOPEE',
              'https://example.invalid/synthetic','source-1','CAPTURED','Synthetic source','{}','{}',NOW(6),NOW(6))
            """);
        db.update("""
            INSERT INTO product_capture_images(id,organization_id,capture_id,original_url,status,role_name,source_json,created_at,updated_at)
            VALUES('test-image','test-org','test-source','https://example.invalid/image.png','PENDING','MAIN','{}',NOW(6),NOW(6))
            """);
        db.update("""
            INSERT INTO commerce_template_versions(id,organization_id,template_id,version,kind,name,config_json,created_by,created_at)
            VALUES('test-template','test-org','test-template',1,'WORKFLOW','Synthetic template','{}','test-user',NOW(6))
            """);
        db.update("""
            INSERT INTO commerce_batches(id,organization_id,name,template_snapshot_id,idempotency_key,request_hash,status,created_by,created_at)
            VALUES('test-batch','test-org','Synthetic batch','test-template','fixture-batch',REPEAT('b',64),'DRAFT','test-user',NOW(6))
            """);
        db.update("""
            INSERT INTO commerce_batch_items(id,organization_id,batch_id,capture_id,capture_version,shop_id,shop_name,planned_day,status,created_at)
            VALUES('test-item','test-org','test-batch','test-source',0,'test-shop','Synthetic shop',1,'DRAFT',NOW(6))
            """);
        if(through114) Flyway.configure().dataSource(source).locations("classpath:db/migration").target("114").load().migrate();
    }
}
