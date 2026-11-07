package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SQL/window/tenant checks run only against a newly created, empty disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "HEYI_ARTWORK_LIBRARY_TEST_URL",
        matches = "jdbc:mysql://(127\\.0\\.0\\.1|heyi-video-e2e-mysql):[0-9]+/heyi_artwork_library_test[a-zA-Z0-9_]*(\\?.*)?")
class CommerceArtworkLibraryIntegrationTest {
    static JdbcTemplate db;
    @TempDir Path storage;
    CommerceImageArchive archive;
    CommerceArtworkLibraryService service;
    final ObjectMapper json = new ObjectMapper();
    final com.yingdao.aicommerce.auth.PlatformPrincipal user = CommerceArtworkLibraryServiceTest.user("org", "u", false, List.of("product:view"));

    @BeforeAll static void emptyDisposableSchema() throws Exception {
        String url = System.getenv("HEYI_ARTWORK_LIBRARY_TEST_URL");
        assertTrue(url.matches("jdbc:mysql://(127\\.0\\.0\\.1|heyi-video-e2e-mysql):[0-9]+/heyi_artwork_library_test[a-zA-Z0-9_]*(\\?.*)?"));
        String username = System.getenv("HEYI_ARTWORK_LIBRARY_TEST_USER");
        String password = System.getenv("HEYI_ARTWORK_LIBRARY_TEST_PASSWORD");
        assertNotNull(username, "Disposable-schema test user is required");
        assertNotNull(password, "Disposable-schema test password is required");
        var source = new DriverManagerDataSource(url, username, password);
        try (var connection = source.getConnection()) {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                assertFalse(tables.next(), "Only a new empty heyi_artwork_library_test schema may be used");
            }
            for (String migration : List.of("V68__batch_execution_checkpoints.sql", "V75__batch_artwork_jobs.sql"))
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE commerce_batches(id VARCHAR(36) PRIMARY KEY,organization_id VARCHAR(36) NOT NULL,name VARCHAR(120) NOT NULL)");
                statement.execute("CREATE TABLE commerce_batch_artwork_checks(job_id VARCHAR(36) PRIMARY KEY,report_json JSON NOT NULL,created_at TIMESTAMP(6) NOT NULL)");
            }
        }
        db = new JdbcTemplate(source);
        String migration=new ClassPathResource("db/migration/V113__commerce_module_isolation.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        db.execute(migration.substring(migration.indexOf("CREATE TABLE commerce_material_library_entries")).split(";",2)[0]);
    }

    @BeforeEach void fixture() {
        for (String table : List.of("commerce_material_library_entries", "commerce_batch_artwork_checks", "commerce_batch_artwork_jobs", "commerce_batch_execution_events", "commerce_batch_executions", "commerce_batches"))
            db.update("DELETE FROM " + table);
        archive = new CommerceImageArchive(storage.toString());
        service = new CommerceArtworkLibraryService(db, archive);
        run("self", "org", "u", "春季猫咪"); run("team", "org", "colleague", "同事印花"); run("other", "other-org", "u", "其他公司");
    }

    private void run(String id, String org, String owner, String name) {
        db.update("INSERT INTO commerce_batches(id,organization_id,name) VALUES(?,?,?)", id, org, name);
        db.update("INSERT INTO commerce_batch_executions(id,organization_id,batch_id,created_by,stage,status,message,created_at,updated_at) VALUES(?,?,?,?,'PUBLISH','FAILED','later publishing failed',NOW(6),NOW(6))", id, org, id, owner);
    }

    private void job(String id, String org, String run, String kind, String state, int order, byte[] bytes, boolean inspection) {
        var saved = archive.store(org, bytes);
        var created = Timestamp.from(Instant.parse("2026-09-16T01:00:00Z").plusSeconds(order));
        db.update("""
                INSERT INTO commerce_batch_artwork_jobs(id,organization_id,execution_id,capture_id,capture_version,kind,
                variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,
                storage_key,content_sha256,message,created_at,updated_at)
                VALUES(?,?,?,'capture',1,?,1,?,?,'fixed','IMG2','fingerprint','[]',?,?,?,?,?)
                """, id, org, run, kind, id, state, saved.storageKey(), saved.sha256(),
                inspection ? "原生透明印花已归档，透明背景检查通过" : "图片已归档", created, created);
        if (inspection) db.update("INSERT INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6))",
                id, json.writeValueAsString(Map.of("transparency", CommerceArtworkTransparency.inspect(bytes))));
    }

    @Test void sqlDeduplicatesAuthorizedCompletedPrintsWithStableLatestRepresentative() throws Exception {
        byte[] bytes = CommerceArtworkLibraryServiceTest.artwork(true);
        job("old", "org", "self", "VARIATE", "READY", 1, bytes, true);
        job("a-latest", "org", "self", "VARIATE_G1", "READY", 2, bytes, true);
        job("z-latest", "org", "self", "VARIATE_G2", "READY", 2, bytes, true);
        job("other-company", "other-org", "other", "VARIATE", "READY", 9, bytes, true);
        job("failed-later", "org", "self", "VARIATE", "FAILED", 9, bytes, true);
        job("retried-later", "org", "self", "VARIATE", "RETRIED", 9, bytes, true);
        job("product-photo", "org", "self", "MOCKUP", "READY", 9, bytes, true);
        freezeCatalogue(); // Later generation never modifies the frozen library.
        job("reused-copy", "org", "self", "VARIATE_G1", "READY", 9, bytes, true);
        db.update("UPDATE commerce_batch_artwork_jobs SET model_config_id='SAVED_ARTWORK' WHERE id='reused-copy'");
        var page = service.list(0, "", user);
        assertEquals(List.of("z-latest"), page.items().stream().map(CommerceArtworkLibraryService.Item::id).toList());
        assertFalse(page.hasMore());
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class, () -> service.resolve("other-company", user));
        assertEquals("z-latest", service.resolve("z-latest", user).id());
    }

    @Test void externalScopeIsAppliedBeforeDedupAndHistoricalNativeImagesRemainUsable() throws Exception {
        byte[] nativeBytes = CommerceArtworkLibraryServiceTest.artwork(true);
        job("own-legacy", "org", "self", "VARIATE", "READY", 1, nativeBytes, false);
        job("coworker-new", "org", "team", "VARIATE", "READY", 2, nativeBytes, true);
        job("old-opaque", "org", "self", "VARIATE", "READY", 3, CommerceArtworkLibraryServiceTest.artwork(false), false);
        freezeCatalogue();
        var external = CommerceArtworkLibraryServiceTest.user("org", "u", true, List.of("product:view"));
        assertEquals(List.of("own-legacy"), service.list(0, "", external).items().stream().map(CommerceArtworkLibraryService.Item::id).toList());
        assertEquals(List.of("coworker-new"), service.list(0, "", user).items().stream().map(CommerceArtworkLibraryService.Item::id).toList());
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class, () -> service.image("coworker-new", external));
        assertEquals("own-legacy", service.resolve("own-legacy", external).id());
    }

    @Test void pagesContainDistinctContentAndSearchUsesLiteralText() throws Exception {
        for (int n = 0; n < 27; n++) {
            var image = CommerceMockupComposer.decode(CommerceArtworkLibraryServiceTest.artwork(true));
            image.setRGB(16, 16, 0xff000000 | n);
            var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
            job("print-" + n, "org", "self", "VARIATE", "READY", n, bytes.toByteArray(), true);
        }
        freezeCatalogue();
        var first = service.list(0, "春季", user); var second = service.list(1, "春季", user);
        assertEquals(24, first.items().size()); assertTrue(first.hasMore());
        assertEquals(3, second.items().size()); assertFalse(second.hasMore());
        var ids = new java.util.HashSet<>(first.items().stream().map(CommerceArtworkLibraryService.Item::id).toList());
        assertTrue(second.items().stream().noneMatch(item -> ids.contains(item.id())));
        assertTrue(service.list(0, "%", user).items().isEmpty());
        assertTrue(service.list(0, "' OR 1=1 --", user).items().isEmpty());
    }
    private void freezeCatalogue() throws Exception {
        // V113 snapshots the historical catalogue. New generation jobs do not
        // implicitly become reusable library entries after module isolation.
        String migration=new ClassPathResource("db/migration/V113__commerce_module_isolation.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        db.execute(migration.substring(migration.indexOf("INSERT INTO commerce_material_library_entries")).split(";",2)[0]);
    }

}
