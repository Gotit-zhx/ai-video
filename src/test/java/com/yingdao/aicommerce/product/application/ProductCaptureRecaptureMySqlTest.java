package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs only against an explicitly configured disposable MySQL database. */
@EnabledIfEnvironmentVariable(named = "CAPTURE_RECOLLECT_TEST_DB",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:\\d+/capture_recollect_test(\\?.*)?")
class ProductCaptureRecaptureMySqlTest {
    private JdbcTemplate db;

    @BeforeEach
    void originalSchema() {
        db = new JdbcTemplate(new DriverManagerDataSource(
                System.getenv("CAPTURE_RECOLLECT_TEST_DB"), System.getenv().getOrDefault("HEYI_TEST_DB_USER","root"),System.getenv().getOrDefault("HEYI_TEST_DB_PASSWORD","")));
        db.execute("DROP TABLE IF EXISTS capture_history_attachment");
        db.execute("DROP TABLE IF EXISTS product_publish_workflows");
        db.execute("DROP TABLE IF EXISTS product_watermark_queue");
        db.execute("DROP TABLE IF EXISTS product_captures");
        db.execute("""
                CREATE TABLE product_captures (
                    id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL,
                    idempotency_key VARCHAR(128) NOT NULL, request_fingerprint CHAR(64) NOT NULL,
                    data_module VARCHAR(32) NOT NULL DEFAULT 'CAPTURE',
                    title VARCHAR(500) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'CAPTURED',
                    deleted_at TIMESTAMP(6) NULL, updated_at TIMESTAMP(6) NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    UNIQUE KEY uk_product_captures_org_idempotency (organization_id,idempotency_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        db.execute("""
                CREATE TABLE capture_history_attachment (
                    capture_id CHAR(36) NOT NULL, content VARCHAR(100),
                    FOREIGN KEY(capture_id) REFERENCES product_captures(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        db.execute("CREATE TABLE product_publish_workflows(capture_id CHAR(36),organization_id CHAR(36),status VARCHAR(32))");
        db.execute("CREATE TABLE product_watermark_queue(capture_id CHAR(36),organization_id CHAR(36),status VARCHAR(32))");
    }

    @Test
    void previouslyDeletedCaptureNoLongerBlocksRecollectionAndHistoryIsPreserved() throws Exception {
        insert("original", "org-a");
        db.update("INSERT INTO capture_history_attachment VALUES('original','historical image')");
        new ProductCaptureDeletionService(db).delete("original", "org-a");
        assertThrows(DuplicateKeyException.class, () -> insert("replacement", "org-a"));

        migrate();
        insert("replacement", "org-a");

        assertEquals(2, count("SELECT COUNT(*) FROM product_captures"));
        assertEquals(1, count("SELECT COUNT(*) FROM product_captures WHERE deleted_at IS NULL"));
        assertEquals("stable-key", db.queryForObject(
                "SELECT idempotency_key FROM product_captures WHERE id='original'", String.class));
        assertEquals("f".repeat(64), db.queryForObject(
                "SELECT request_fingerprint FROM product_captures WHERE id='original'", String.class));
        assertEquals("historical image", db.queryForObject(
                "SELECT content FROM capture_history_attachment WHERE capture_id='original'", String.class));
    }

    @Test
    void liveDuplicatesStayBlockedAndCompaniesRemainIndependent() throws Exception {
        migrate();
        insert("first", "org-a");
        assertThrows(DuplicateKeyException.class, () -> insert("duplicate", "org-a"));
        insert("other-company", "org-b");
        assertEquals(2, count("SELECT COUNT(*) FROM product_captures WHERE deleted_at IS NULL"));
    }

    @Test
    void repeatedDeleteAndRecaptureCyclesKeepEveryHistoricalGeneration() throws Exception {
        migrate();
        var deletion = new ProductCaptureDeletionService(db);
        for (int index = 0; index < 4; index++) {
            String id = "generation-" + index;
            insert(id, "org-a");
            if (index < 3) deletion.delete(id, "org-a");
        }
        assertEquals(3, count("SELECT COUNT(*) FROM product_captures WHERE deleted_at IS NOT NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM product_captures WHERE deleted_at IS NULL"));
    }

    @Test
    void concurrentRecollectionCanCreateOnlyOneLiveCapture() throws Exception {
        migrate();
        insert("deleted", "org-a");
        new ProductCaptureDeletionService(db).delete("deleted", "org-a");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> concurrentInsert("first", ready, start));
            var second = pool.submit(() -> concurrentInsert("second", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM product_captures WHERE deleted_at IS NULL"));
    }

    @Test
    void restoringOldCaptureCannotCreateTwoLiveCopies() throws Exception {
        migrate();
        insert("old", "org-a");
        new ProductCaptureDeletionService(db).delete("old", "org-a");
        insert("new", "org-a");
        assertThrows(DuplicateKeyException.class,
                () -> db.update("UPDATE product_captures SET deleted_at=NULL WHERE id='old'"));
    }

    private boolean concurrentInsert(String id, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            insert(id, "org-a");
            return true;
        } catch (DuplicateKeyException expected) {
            return false;
        }
    }

    private void migrate() throws Exception {
        db.execute(Files.readString(Path.of(
                "src/main/resources/db/migration/V110__capture_active_idempotency.sql")));
    }

    private void insert(String id, String organization) {
        db.update("""
                INSERT INTO product_captures(id,organization_id,idempotency_key,request_fingerprint,title)
                VALUES(?,?,'stable-key',?,'captured product')
                """, id, organization, "f".repeat(64));
    }

    private int count(String sql) {
        return db.queryForObject(sql, Integer.class);
    }
}
