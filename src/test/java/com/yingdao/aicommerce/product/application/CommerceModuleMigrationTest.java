package com.yingdao.aicommerce.product.application;

import db.migration.V114__freeze_legacy_commerce_sources;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in against a disposable MySQL copy; never starts Spring or background business jobs. */
@EnabledIfEnvironmentVariable(named="MODULE_MIGRATION_URL",matches="jdbc:mysql:.*")
class CommerceModuleMigrationTest {
    @Test void realMysqlMigrationPreservesSourcesAndFreezesAllExistingBatchReferences() {
        String url=System.getenv("MODULE_MIGRATION_URL"),password=System.getenv("MODULE_MIGRATION_PASSWORD");
        assertTrue(url.contains("module_isolation_test"),"Only the disposable migration database is allowed");
        var dataSource=new DriverManagerDataSource(url,System.getenv("MODULE_MIGRATION_USER"),password);
        ModuleMigrationFixture.prepare(dataSource,false);
        var db=new JdbcTemplate(dataSource);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM product_captures",Integer.class));
        var original=db.queryForList("SELECT * FROM product_captures ORDER BY id");
        var shops=db.queryForList("SELECT * FROM shops ORDER BY id");
        var batches=db.queryForList("SELECT * FROM commerce_batches ORDER BY id");
        var flyway=Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("114").load();
        assertEquals(2,flyway.migrate().migrationsExecuted);
        assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(stable(shops),stable(db.queryForList("SELECT * FROM shops ORDER BY id")));
        assertEquals(stable(batches),stable(db.queryForList("SELECT * FROM commerce_batches ORDER BY id")));
        for(var source:original) {
            var migrated=db.queryForMap("SELECT * FROM product_captures WHERE id=?",source.get("id"));
            migrated.remove("data_module");
            assertEquals(stable(List.of(source)),stable(List.of(migrated)),"Original collection row must not be rewritten");
        }
        assertEquals(0,db.queryForObject("""
                SELECT COUNT(*) FROM commerce_batch_items i
                JOIN product_captures c ON c.id=i.capture_id AND c.organization_id=i.organization_id
                WHERE c.data_module<>'FISSION'
                """,Integer.class));
        assertEquals(0,db.queryForObject("""
                SELECT COUNT(*) FROM commerce_source_copy_images m
                LEFT JOIN product_capture_images i ON i.id=m.image_id AND i.capture_id=m.capture_id AND i.organization_id=m.organization_id
                WHERE i.id IS NULL OR m.image_id=m.source_image_id
                """,Integer.class));
        assertEquals(0,db.queryForObject("""
                SELECT COUNT(*) FROM commerce_source_copy_images m
                JOIN product_capture_images i ON i.id=m.image_id AND i.organization_id=m.organization_id
                JOIN product_watermark_checks w ON w.image_id=m.source_image_id AND w.organization_id=m.organization_id
                WHERE w.status='READY' AND JSON_UNQUOTE(JSON_EXTRACT(w.detection_json,'$.imageType'))='SIZE_CHART'
                AND i.role_name<>'SIZE_CHART'
                """,Integer.class));
        var config=db.queryForList("SELECT * FROM product_listing_templates WHERE data_module='FISSION' LIMIT 1");
        if(!config.isEmpty()) {
            var row=config.getFirst();
            var before=db.queryForList("SELECT * FROM product_listing_templates WHERE data_module<>'FISSION'");
            db.update("UPDATE product_listing_templates SET description='Isolation test' WHERE organization_id=? AND platform=? AND data_module='FISSION'",
                    row.get("organization_id"),row.get("platform"));
            assertEquals(before,db.queryForList("SELECT * FROM product_listing_templates WHERE data_module<>'FISSION'"));
        }
    }
    private static List<Map<String,Object>> stable(List<Map<String,Object>> rows) {
        return rows.stream().map(row->{
            var result=new LinkedHashMap<String,Object>();
            row.forEach((key,value)->result.put(key,value instanceof byte[] bytes?Base64.getEncoder().encodeToString(bytes):value));
            return (Map<String,Object>)result;
        }).toList();
    }
}
