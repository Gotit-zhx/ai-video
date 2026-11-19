package com.yingdao.aicommerce.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "MIGRATION_SYNC_QA_JDBC", matches = ".+")
class FlywayProductionChainMySqlTest {
    @Test
    void upgradesProductCaptureV160WithoutChangingHistoryOrProductData() {
        String url = System.getenv("MIGRATION_SYNC_QA_JDBC");
        assertTrue(url.matches("jdbc:mysql://[^/]+/migration_sync_qa_[a-f0-9]+\\?.*"),
                "Only a disposable migration_sync_qa schema is permitted");
        var dataSource = new DriverManagerDataSource(url, System.getenv("MIGRATION_SYNC_QA_USER"),
                System.getenv("MIGRATION_SYNC_QA_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);

        var baseline = Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("160")).load();
        assertTrue(baseline.migrate().migrationsExecuted > 0);
        assertEquals("160", baseline.info().current().getVersion().getVersion());
        assertEquals("V160__product_capture_templates.sql", baseline.info().current().getScript());
        var historyBefore = history(jdbc);
        jdbc.update("""
                INSERT INTO product_capture_templates
                    (organization_id,id,name,content_json,version,fingerprint,created_by,updated_by,created_at,updated_at)
                VALUES ('test-org','template','retained template',JSON_ARRAY(),1,REPEAT('0',64),'tester','tester',NOW(6),NOW(6))
                """);

        var candidate = Flyway.configure().dataSource(dataSource).load();
        assertEquals(5, candidate.migrate().migrationsExecuted);
        assertTrue(candidate.validateWithResult().validationSuccessful);
        assertEquals("165", candidate.info().current().getVersion().getVersion());
        var historyAfter = history(jdbc);
        assertEquals(historyBefore, historyAfter.subList(0, historyBefore.size()));
        assertEquals("retained template", jdbc.queryForObject(
                "SELECT name FROM product_capture_templates WHERE organization_id='test-org' AND id='template'",
                String.class));
        assertEquals(0, candidate.migrate().migrationsExecuted);
    }

    private List<Map<String, Object>> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT installed_rank, version, script, checksum, success
                FROM flyway_schema_history ORDER BY installed_rank
                """);
    }
}
