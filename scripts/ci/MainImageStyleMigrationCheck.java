import java.sql.*;
import org.flywaydb.core.Flyway;

/** Runs only against the disposable schema cloned by the release helper. */
public class MainImageStyleMigrationCheck {
    static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    static int count(Statement sql, String query) throws Exception {
        try (var rows = sql.executeQuery(query)) { rows.next(); return rows.getInt(1); }
    }
    public static void main(String[] args) throws Exception {
        String url = System.getenv("STYLE_MIGRATION_URL");
        require(url != null && url.contains("/main_image_style_test_20261019?"), "Disposable schema required");
        String user = System.getenv("STYLE_MIGRATION_USER"), password = System.getenv("STYLE_MIGRATION_PASSWORD");
        var flyway = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load();
        require(flyway.migrate().migrationsExecuted == 1, "Exactly V116 must run");
        require(flyway.migrate().migrationsExecuted == 0, "Repeat migration must be a no-op");
        require(flyway.validateWithResult().validationSuccessful, "Migration checksums must remain valid");
        try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
            require(count(sql, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
                    + " AND table_name IN ('product_main_image_templates','product_main_image_template_images','product_main_image_jobs')") == 3,
                    "Three style tables required");
            // Synthetic parent identities avoid copying customer records into the rehearsal.
            sql.execute("SET FOREIGN_KEY_CHECKS=0");
            sql.executeUpdate("""
                    INSERT INTO product_publish_workflows
                    (id,organization_id,capture_id,created_by,idempotency_key,request_fingerprint,destination_platform,
                    capture_version,expected_capture_version,request_json,status,current_step,next_attempt_at,created_at,updated_at)
                    VALUES('style-check-workflow','style-check-org','style-check-capture','style-check-user','style-check-key',
                    REPEAT('a',64),'SHOPEE',1,1,'{}','FAILED','STYLE_IMAGES',NOW(),NOW(),NOW())
                    """);
            sql.execute("SET FOREIGN_KEY_CHECKS=1");
            boolean invalidRejected = false;
            try { sql.executeUpdate("UPDATE product_publish_workflows SET current_step='INVALID_STEP' WHERE id='style-check-workflow'"); }
            catch (SQLException expected) { invalidRejected = expected.getErrorCode() == 3819; }
            require(invalidRejected, "Workflow CHECK must still reject unknown stages");
            sql.executeUpdate("""
                    INSERT INTO product_main_image_templates
                    (id,organization_id,created_by,name,request_key,request_fingerprint,created_at)
                    VALUES('style-check-template','style-check-org','style-check-user','Test','style-test-key',REPEAT('b',64),NOW())
                    """);
            sql.executeUpdate("""
                    INSERT INTO product_main_image_template_images
                    (id,template_id,organization_id,sort_order,storage_key,width,height,status,analysis_json,updated_at)
                    VALUES('style-check-image','style-check-template','style-check-org',0,'test.png',1024,1024,'READY','{"prompt":"test"}',NOW())
                    """);
            String insert = """
                    INSERT IGNORE INTO product_main_image_jobs
                    (id,organization_id,workflow_id,capture_id,sort_order,source_key,template_fingerprint,reference_key,prompt,size,model_config_id,status,updated_at)
                    VALUES('%s','style-check-org','style-check-workflow','style-check-capture',0,'source.png',REPEAT('%s',64),
                    'ref.png','test','1024x1024','test-model','PREPARED',NOW())
                    """;
            require(sql.executeUpdate(insert.formatted("style-check-job", "c")) == 1, "First slot must be saved");
            require(sql.executeUpdate(insert.formatted("style-check-duplicate", "c")) == 0, "Exact slot must deduplicate");
            require(sql.executeUpdate(insert.formatted("style-check-edited", "d")) == 1, "Changed template must get a new slot");
            require(count(sql, "SELECT recovery_count FROM product_main_image_jobs WHERE id='style-check-job'") == 0,
                    "Recovery budget must start at zero");
            sql.executeUpdate("UPDATE product_main_image_template_images SET analysis_json='{\"prompt\":\"edited\"}' WHERE id='style-check-image'");
            sql.executeUpdate("UPDATE product_main_image_jobs SET recovery_count=1,result_json='{\"result\":true}',review_json='{\"productPreserved\":true}' WHERE id='style-check-job'");
            sql.executeUpdate("DELETE FROM product_publish_workflows WHERE id='style-check-workflow'");
            require(count(sql, "SELECT COUNT(*) FROM product_main_image_jobs") == 0, "Workflow deletion must cascade to style jobs");
            require(count(sql, "SELECT COUNT(*) FROM product_captures") == 0, "Rehearsal must not import customer products");
        }
        System.out.println("V116 migration, repeat validation, CHECK, JSON, idempotency and cascade passed.");
    }
}
