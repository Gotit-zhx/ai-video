package com.yingdao.aicommerce.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Flyway and helper-process exercise in the runner's unique disposable schema. */
@EnabledIfEnvironmentVariable(named = "FINANCE_QA_JDBC", matches = ".+")
class FinanceProjectionBaselineSchemaMaintenanceIntegrationTest {
    @TempDir Path root;
    static final String SQL = "V157__finance_projection_baseline_revisions.sql";
    static final String OWNER = "abababababababababababababababab";
    final ObjectMapper json = new ObjectMapper();
    String url, user, password, classpath;
    Path addition, token;

    @Test void fixedUpgradePreservesHistoryAndPayloadsAndRejectsUnsafeInvocations() throws Exception {
        url = System.getenv("FINANCE_QA_JDBC"); user = System.getenv("FINANCE_QA_USER"); password = System.getenv("FINANCE_QA_PASSWORD");
        assertTrue(url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"), "Only disposable finance_qa schemas are permitted");
        // macOS may expose the JUnit temp directory through /var -> /private/var.
        // Production rejects redirected helper paths, so construct the fixture from its canonical root.
        root = root.toRealPath();
        Path oldRuntime = Files.createDirectories(root.resolve("runtime/db/migration"));
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                var matcher = java.util.regex.Pattern.compile("V(\\d+)__.*\\.sql").matcher(file.getFileName().toString());
                if (matcher.matches() && Integer.parseInt(matcher.group(1)) <= 156)
                    Files.copy(file, oldRuntime.resolve(file.getFileName()));
            }
        }
        // Preserve the actual mixed Java/SQL history without exposing target/classes and its V157 SQL.
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", System.getProperty("java.class.path"),
            "-d", oldRuntime.getParent().getParent().toString(), "src/main/java/db/migration/V114__freeze_legacy_commerce_sources.java"));
        addition = Files.createDirectories(root.resolve("owned/migration"));
        Files.copy(Path.of("src/main/resources/db/migration/" + SQL), addition.resolve(SQL));
        token = addition.getParent().resolve("launch.json"); authorize();
        Path helperClasses = Files.createDirectory(root.resolve("helper-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", System.getProperty("java.class.path"),
            "-d", helperClasses.toString(), "scripts/qa/java/FinanceProjectionBaselineSchemaMaintenance.java"));
        // Only dependency jars plus the <=156 resource copy: target/classes includes V157 and must never enter the helper classpath.
        var dependencies = Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            .filter(item -> item.endsWith(".jar")).toList();
        assertFalse(dependencies.isEmpty());
        classpath = helperClasses + File.pathSeparator + oldRuntime.getParent().getParent() + File.pathSeparator + String.join(File.pathSeparator, dependencies);
        var historical155 = Flyway.configure().dataSource(url, user, password).locations("filesystem:" + oldRuntime)
            .javaMigrations(new db.migration.V114__freeze_legacy_commerce_sources()).target("155").load();
        historical155.migrate();
        reject("validate", "Wrong baseline must not migrate");
        var historical156 = Flyway.configure().dataSource(url, user, password).locations("filesystem:" + oldRuntime)
            .javaMigrations(new db.migration.V114__freeze_legacy_commerce_sources()).target("156").load();
        historical156.migrate();
        var java114History = rows("SELECT * FROM flyway_schema_history WHERE version='114'");
        assertEquals(1, java114History.size(), "The real historical V114 Java migration must execute in the baseline");
        assertEquals(List.of(List.of("JDBC", "1")), rows("SELECT type,success FROM flyway_schema_history WHERE version='114'"));
        try (var db = DriverManager.getConnection(url, user, password); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,currency,amount,source,raw_json) VALUES('sentinel','qa-org','qa-user','qa-shop','SHOPEE','ORDER','qa-external','2026-10-15','PHP',12.34,'API','{\"marker\":\"raw-sentinel\",\"precise\":\"12.340000\"}')");
            sql.executeUpdate("INSERT INTO finance_query_partitions(organization_id,platform_filter,partition_month,requested_revision,built_revision,full_rebuild,full_month_json,partial_first_day_json,spend_json) VALUES('qa-org','','2026-08-01',9,4,TRUE,'{\"precise\":\"9.123456789012345678\"}','{}','{}'),('qa-org','','2026-09-01',12,7,FALSE,'{\"precise\":\"7.123456789012345678\"}','{}','{}'),('qa-org','','2026-10-01',6,6,FALSE,'{\"precise\":\"6.123456789012345678\"}','{}','{}')");
            sql.executeUpdate("INSERT INTO finance_query_dirty_days(organization_id,platform_filter,partition_month,record_date) VALUES('qa-org','','2026-08-01','2026-08-03'),('qa-org','','2026-09-01','2026-09-03')");
        }
        List<List<String>> history = rows("SELECT installed_rank,version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        List<List<String>> records = rows("SELECT * FROM finance_records ORDER BY id");
        List<List<String>> partitions = rows("SELECT * FROM finance_query_partitions ORDER BY partition_month");
        List<List<String>> dirty = rows("SELECT * FROM finance_query_dirty_days ORDER BY partition_month,record_date");
        byte[] immutable = Files.readAllBytes(addition.resolve(SQL));
        Files.writeString(addition.resolve(SQL), "SELECT 1;"); reject("migrate", "Wrong SQL hash must fail");
        Files.write(addition.resolve(SQL), immutable);
        Path extra = addition.resolve("V158__unexpected.sql"); Files.writeString(extra, "SELECT 1;");
        reject("migrate", "Multiple files/pending migrations must fail"); Files.delete(extra);
        Files.writeString(token, "null\n"); reject("migrate", "Revoked launch must fail"); authorize();
        assertEquals(history, rows("SELECT installed_rank,version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank"));
        assertEquals(0, ((Number) invoke("validate", 0).get("executed")).intValue());
        assertEquals(history, rows("SELECT installed_rank,version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank"));
        Map<?, ?> migrated = invoke("migrate", 0);
        assertEquals(1, ((Number) migrated.get("executed")).intValue());
        assertEquals(true, migrated.get("oldRuntimeValidationPassed"));
        assertEquals(true, migrated.get("historyPrefixPreserved"));
        var afterHistory = rows("SELECT installed_rank,version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        assertEquals(history.size() + 1, afterHistory.size()); assertEquals(history, afterHistory.subList(0, history.size()));
        assertEquals(java114History, rows("SELECT * FROM flyway_schema_history WHERE version='114'"), "Every V114 history field must remain unchanged");
        assertEquals("157", afterHistory.getLast().get(1)); assertEquals(SQL, afterHistory.getLast().get(3));
        assertEquals(records, rows("SELECT * FROM finance_records ORDER BY id"));
        // New columns are appended; compare every pre-existing column, including exact LONGTEXT payloads.
        var newPartitions = rows("SELECT * FROM finance_query_partitions ORDER BY partition_month");
        assertEquals(partitions, newPartitions.stream().map(row -> row.subList(0, row.size() - 1)).toList());
        var newDirty = rows("SELECT * FROM finance_query_dirty_days ORDER BY partition_month,record_date");
        assertEquals(dirty, newDirty.stream().map(row -> row.subList(0, row.size() - 1)).toList());
        assertEquals(List.of(List.of("9"), List.of("7"), List.of("6")), rows("SELECT full_rebuild_revision FROM finance_query_partitions ORDER BY partition_month"));
        assertEquals(List.of(List.of("9"), List.of("12")), rows("SELECT requested_revision FROM finance_query_dirty_days ORDER BY partition_month,record_date"));
        try (var db = DriverManager.getConnection(url, user, password); var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO finance_query_partitions(organization_id,platform_filter,partition_month) VALUES('legacy-writer','','2026-10-01')");
            sql.executeUpdate("INSERT INTO finance_query_dirty_days(organization_id,platform_filter,partition_month,record_date) VALUES('legacy-writer','','2026-10-01','2026-10-02')");
        }
        assertEquals(List.of(List.of("1")), rows("SELECT full_rebuild_revision FROM finance_query_partitions WHERE organization_id='legacy-writer'"));
        assertEquals(List.of(List.of("0")), rows("SELECT requested_revision FROM finance_query_dirty_days WHERE organization_id='legacy-writer'"));
        reject("migrate", "Applied migration must never replay"); reject("validate", "Already applied baseline must require a fresh procedure");
        assertEquals(afterHistory, rows("SELECT installed_rank,version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank"));
        assertTrue(historical156.validateWithResult().validationSuccessful, "Old runtime continues to validate the verified future V157");
    }

    void authorize() throws Exception { Files.writeString(token, "\"" + OWNER + "\"\n"); }
    void reject(String mode, String reason) throws Exception { assertEquals(false, invoke(mode, 1).get("success"), reason); }
    Map<?, ?> invoke(String mode, int expectedExit) throws Exception {
        Path stdout = root.resolve("stdout-" + UUID.randomUUID() + ".json"), stderr = root.resolve("stderr-" + UUID.randomUUID() + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Dheyi.financeProjectionSchemaOwner=" + OWNER, "-cp", classpath,
            "FinanceProjectionBaselineSchemaMaintenance", mode, addition.toString(), token.toString())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            try (var input = process.getOutputStream()) { input.write(json.writeValueAsBytes(Map.of("url", url, "user", user, "password", password, "owner", OWNER))); }
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Bounded helper timeout");
            // Failure output deliberately excludes raw helper logs, database URL and credentials.
            String output = Files.readString(stdout), errors = Files.readString(stderr);
            if (!password.isEmpty()) { assertFalse(output.contains(password)); assertFalse(errors.contains(password)); }
            var summary = output.lines().filter(line -> line.startsWith("{")).reduce((first, second) -> second).orElseThrow();
            Map<?, ?> result = json.readValue(summary, Map.class);
            assertEquals(expectedExit, process.exitValue(), "Helper rejected invocation: " + result.get("failureType")
                + "; canonicalDirectory=" + addition.toRealPath().equals(addition.toAbsolutePath()));
            return result;
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
    }
    List<List<String>> rows(String query) throws Exception {
        var result = new ArrayList<List<String>>();
        try (var db = DriverManager.getConnection(url, user, password); var statement = db.createStatement(); var rows = statement.executeQuery(query)) {
            while (rows.next()) {
                var row = new ArrayList<String>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) row.add(rows.getString(column));
                result.add(row);
            }
        }
        return result;
    }
}
