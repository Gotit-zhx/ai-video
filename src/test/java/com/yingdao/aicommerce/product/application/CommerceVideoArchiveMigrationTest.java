package com.yingdao.aicommerce.product.application;

import db.migration.V114__freeze_legacy_commerce_sources;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MODULE_MIGRATION_URL",matches="jdbc:mysql:.*")
class CommerceVideoArchiveMigrationTest {
    @Test void privateVideoKeepsItsFileKeyAfterSourceRecordRemoval() {
        String url=System.getenv("MODULE_MIGRATION_URL");
        assertTrue(url.contains("module_isolation_test"));
        var source=new DriverManagerDataSource(url,System.getenv("MODULE_MIGRATION_USER"),System.getenv("MODULE_MIGRATION_PASSWORD"));
        var db=new JdbcTemplate(source);
        var mapping=db.queryForList("SELECT * FROM commerce_source_copies LIMIT 1").getFirst();
        String original=UUID.randomUUID().toString(),copy=UUID.randomUUID().toString();
        String hash=CommerceImageArchive.sha(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(var pair:List.of(Map.entry(original,mapping.get("source_capture_id")),Map.entry(copy,mapping.get("capture_id"))))
            db.update("""
                    INSERT INTO product_capture_videos(id,organization_id,capture_id,source_url,mime_type,byte_length,content_sha256,created_at)
                    VALUES(?,?,?,'https://example.com/frozen-video','video/mp4',24,?,NOW(6))
                    """,pair.getKey(),mapping.get("organization_id"),pair.getValue(),hash);
        var flyway=Flyway.configure().dataSource(source).locations(System.getenv("MODULE_MIGRATION_LOCATION"))
                .javaMigrations(new V114__freeze_legacy_commerce_sources()).load();
        assertEquals(1,flyway.migrate().migrationsExecuted);
        assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(original,db.queryForObject("SELECT storage_video_id FROM product_capture_videos WHERE id=?",String.class,copy));
        db.update("DELETE FROM product_capture_videos WHERE id=?",original);
        assertEquals(original,db.queryForObject("SELECT storage_video_id FROM product_capture_videos WHERE id=?",String.class,copy));
        assertEquals("https://example.com/frozen-video",db.queryForObject("SELECT source_url FROM product_capture_videos WHERE id=?",String.class,copy));
    }
}
