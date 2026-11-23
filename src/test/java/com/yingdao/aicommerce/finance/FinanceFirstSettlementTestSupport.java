package com.yingdao.aicommerce.finance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

abstract class FinanceFirstSettlementTestSupport extends FinanceConfigurationIntegrationTest {
    private static final Pattern DISPOSABLE_JDBC = Pattern.compile(
        "jdbc:mysql://[^/]+/(finance_qa_[a-f0-9]+)\\?.*");
    private static final Path PROTOTYPE = Path.of(
        "src/test/resources/finance/first-settlement-facts-prototype.sql");

    private Path prototypeLocation;
    int registrationRowsAfterInstall;
    Map<String, String> existingTriggerDefinitionsBeforeInstall = Map.of();
    Map<String, String> existingTriggerDefinitionsAfterInstall = Map.of();

    @BeforeAll
    void installPrototype() throws Exception {
        String configured = System.getenv("FINANCE_QA_JDBC");
        var configuredMatch = DISPOSABLE_JDBC.matcher(configured == null ? "" : configured);
        if (!configuredMatch.matches()) {
            throw new IllegalStateException("First-settlement tests require a disposable finance_qa JDBC URL");
        }
        String current = db.queryForObject("SELECT DATABASE()", String.class);
        if (!configuredMatch.group(1).equals(current)) {
            throw new IllegalStateException("The connected schema does not match the disposable runner target");
        }
        try (var connection = dataSource.getConnection()) {
            var actualMatch = DISPOSABLE_JDBC.matcher(connection.getMetaData().getURL());
            if (!actualMatch.matches() || !current.equals(actualMatch.group(1))) {
                throw new IllegalStateException("The active connection is outside the disposable runner target");
            }
        }
        if (!Files.exists(PROTOTYPE)) {
            return;
        }

        existingTriggerDefinitionsBeforeInstall = triggerDefinitions(false);
        int nextVersion;
        try (var migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            nextVersion = migrations.map(path -> path.getFileName().toString())
                .filter(name -> name.matches("V[0-9]+__.*\\.sql"))
                .mapToInt(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                .max().orElseThrow() + 1;
        }
        prototypeLocation = Files.createTempDirectory("finance-first-settlement-flyway-");
        Files.copy(PROTOTYPE, prototypeLocation.resolve(
            "V" + nextVersion + "__finance_first_settlement_facts_prototype.sql"));
        var flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:" + prototypeLocation)
            .baselineOnMigrate(true)
            .baselineVersion(String.valueOf(nextVersion - 1))
            .target(String.valueOf(nextVersion))
            .load();
        if (flyway.migrate().migrationsExecuted != 1) {
            throw new IllegalStateException("The disposable prototype migration did not execute exactly once");
        }
        registrationRowsAfterInstall = tableExists("finance_first_settlement_registration")
            ? db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_registration", Integer.class)
            : -1;
        existingTriggerDefinitionsAfterInstall = triggerDefinitions(false);
    }

    @BeforeEach
    void resetFirstSettlementPrototype() {
        if (!tableExists("finance_first_settlement_facts")) {
            return;
        }
        db.update("DELETE FROM finance_first_settlement_facts");
        db.update("DELETE FROM finance_first_settlement_initialization");
        db.update("DELETE FROM finance_first_settlement_registration");
        db.update("""
            INSERT INTO finance_first_settlement_registration
              (id,schema_version,registration_version,definition_sha,installation_boundary,enabled)
            VALUES(1,1,1,?,'disposable-runner-install',TRUE)
            """, triggerDefinitionSha());
    }

    boolean tableExists(String table) {
        return db.queryForObject("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema=DATABASE() AND table_name=?
            """, Integer.class, table) == 1;
    }

    int triggerCount() {
        return db.queryForObject("""
            SELECT COUNT(*) FROM information_schema.triggers
            WHERE trigger_schema=DATABASE() AND trigger_name IN
              ('fin_fsf_records_ai','fin_fsf_records_au','fin_fsf_records_ad')
            """, Integer.class);
    }

    Map<String, String> triggerDefinitions(boolean firstSettlementOnly) {
        String predicate = firstSettlementOnly
            ? "trigger_name LIKE 'fin_fsf_records_a%'"
            : "trigger_name NOT LIKE 'fin_fsf_records_a%'";
        var result = new LinkedHashMap<String, String>();
        for (var row : db.queryForList("""
            SELECT trigger_name,action_timing,event_manipulation,action_statement
            FROM information_schema.triggers
            WHERE trigger_schema=DATABASE() AND %s
            ORDER BY trigger_name
            """.formatted(predicate))) {
            String name = String.valueOf(row.get("trigger_name"));
            result.put(name, FinanceData.hash(name + "\n" + row.get("action_timing") + "\n"
                + row.get("event_manipulation") + "\n" + row.get("action_statement")));
        }
        return result;
    }

    String triggerDefinitionSha() {
        return FinanceData.hash(triggerDefinitions(true).toString());
    }

    void insertSettlement(String id, String organization, String shop, String order,
                          String platform, String category, String status, String source,
                          LocalDate date, String currency) {
        db.update("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,
              external_id,order_id,category,status,record_date,currency,source,raw_json)
            VALUES(?,?,?,? ,?,'SETTLEMENT',?,?, ?,?,?,?,?,'{}')
            """, id, organization, organization + "-user", shop, platform, id, order,
            category, status, java.sql.Date.valueOf(date), currency, source);
    }

    long revision(String organization, String shop, String order) {
        return db.queryForObject("""
            SELECT requested_revision FROM finance_first_settlement_facts
            WHERE organization_id=? AND shop_id=? AND order_id=?
            """, Long.class, organization, shop, order);
    }

    int ownerRows(String organization, String shop, String order) {
        return db.queryForObject("""
            SELECT COUNT(*) FROM finance_first_settlement_facts
            WHERE organization_id=? AND shop_id=? AND order_id=?
            """, Integer.class, organization, shop, order);
    }

    void updateAndCommit(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Expected exactly one source row update");
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        }
    }

    @AfterAll
    void removePrototypeLocation() throws Exception {
        if (prototypeLocation == null) {
            return;
        }
        try (var paths = Files.walk(prototypeLocation)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
