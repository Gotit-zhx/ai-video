package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC", matches=".+")
class FinanceFirstSettlementWorkerIntegrationTest extends FinanceFirstSettlementTestSupport {
    private static final String INSERT = """
        INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,
          external_id,order_id,category,status,record_date,currency,source,raw_json)
        VALUES(?,?,'u',?,'SHOPEE','SETTLEMENT',?,?,'settlement','SETTLED',?,'PHP','API','{}')
        """;

    private FinanceFirstSettlementFacts facts() { return new FinanceFirstSettlementFacts(repo); }
    private FinanceFirstSettlementInitialization initialization() { return new FinanceFirstSettlementInitialization(repo); }
    private void settlement(String id, String org, String shop, String order, LocalDate date) {
        insertSettlement(id, org, shop, order, "SHOPEE", "settlement", "SETTLED", "API", date, "PHP");
    }
    private void registered(String org) {
        initialization().discoverCompanies(100);
        assertTrue(facts().guard(org).isPresent());
    }
    private Lease claim(FinanceFirstSettlementFacts facts, String org) {
        var result = facts.claim(org, UUID.randomUUID().toString(), 30);
        if (result.isEmpty()) result = facts.claim(org, UUID.randomUUID().toString(), 30);
        assertTrue(result.isPresent(), "a registered pending owner must be claimable");
        return result.orElseThrow();
    }
    private Computed calculated(FinanceFirstSettlementFacts facts) {
        settlement("current", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        registered("org");
        return facts.calculate(claim(facts, "org"));
    }
    private Map<String, Object> fact(Owner owner) {
        return db.queryForMap("""
            SELECT * FROM finance_first_settlement_facts
            WHERE organization_id=? AND shop_id=? AND order_id=?
            """, owner.organizationId(), owner.shopId(), owner.orderId());
    }
    private long applied(Owner owner) { return ((Number) fact(owner).get("applied_revision")).longValue(); }
    private void committed(String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            updateAndCommit(connection, sql, args);
        }
    }
    private void insert(Connection connection, String id, String org, String shop, String order,
                        LocalDate date) throws SQLException {
        try (var statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, id); statement.setString(2, org); statement.setString(3, shop);
            statement.setString(4, id); statement.setString(5, order);
            statement.setDate(6, java.sql.Date.valueOf(date));
            assertEquals(1, statement.executeUpdate());
        }
    }
    private MaintenanceGate gate(Path marker) throws Exception {
        var constructor = MaintenanceGate.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(marker);
    }
    private FinanceFirstSettlementWorker worker(FinanceFirstSettlementFacts facts, int batch, int companies,
                                                java.util.function.LongSupplier clock, MaintenanceGate gate) {
        return new FinanceFirstSettlementWorker(facts, initialization(), batch, 30, companies,
            Duration.ofSeconds(2), clock, gate);
    }

    /** Pauses the real JDBC operation; every query still executes against the disposable database. */
    private final class PausingJdbc extends JdbcTemplate {
        Runnable beforeCandidate;
        Runnable beforeProof;
        Runnable afterPublication;
        PausingJdbc() { super(dataSource); }
        private void pause(String sql) {
            if (beforeProof != null && sql.contains("finance_first_settlement_registration") && sql.contains("FOR SHARE")) {
                var action = beforeProof; beforeProof = null; action.run();
            }
            if (beforeCandidate != null && sql.contains("FROM finance_records r")) {
                var action = beforeCandidate; beforeCandidate = null; action.run();
            }
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper) {
            pause(sql); return super.query(sql, mapper);
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            pause(sql); return super.query(sql, mapper, args);
        }
        @Override public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            pause(sql); return super.queryForList(sql, elementType, args);
        }
        @Override public int update(String sql, Object... args) {
            int changed = super.update(sql, args);
            if (afterPublication != null && sql.contains("applied_revision=")) {
                var action = afterPublication; afterPublication = null; action.run();
            }
            return changed;
        }
    }

    @Test void sourceCommitAfterCalculationRejectsPublicationOfTheObservedRevision() throws Exception {
        insertSettlement("current", "org", "shop", "order", "SHOPEE", "settlement", "SETTLED",
            "API", LocalDate.of(2026, 10, 2), "PHP");
        new FinanceFirstSettlementInitialization(repo).discoverCompanies(10);
        var facts = new FinanceFirstSettlementFacts(repo);
        var claimed = facts.claim("org", UUID.randomUUID().toString(), 30);
        assertTrue(claimed.isPresent(), "a registered pending owner must be claimable");
        var computed = facts.calculate(claimed.orElseThrow());
        assertEquals("current", computed.first().orElseThrow().id());
        assertEquals(1, computed.revision());

        var calculated = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var writer = pool.submit(() -> {
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        assertTrue(calculated.await(10, TimeUnit.SECONDS), "calculation must finish before source commit");
                        try (var statement = connection.prepareStatement("""
                            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,
                              external_id,order_id,category,status,record_date,currency,source,raw_json)
                            VALUES('earlier','org','u','shop','SHOPEE','SETTLEMENT','earlier','order',
                              'settlement','SETTLED',?,'PHP','API','{}')
                            """)) {
                            statement.setDate(1, java.sql.Date.valueOf(computed.first().orElseThrow().date().minusDays(1)));
                            assertEquals(1, statement.executeUpdate());
                        }
                        connection.commit();
                        committed.countDown();
                    } catch (Exception | Error failure) {
                        connection.rollback();
                        throw failure;
                    }
                }
                return null;
            });
            try {
                calculated.countDown();
                assertTrue(committed.await(10, TimeUnit.SECONDS), "the second connection must commit before publication");
                writer.get(10, TimeUnit.SECONDS);
                assertFalse(facts.publish(computed));
                var row = db.queryForMap("""
                    SELECT requested_revision,applied_revision,has_settlement,first_record_id
                    FROM finance_first_settlement_facts
                    WHERE organization_id='org' AND shop_id='shop' AND order_id='order'
                    """);
                assertEquals(2L, ((Number) row.get("requested_revision")).longValue());
                assertEquals(0L, ((Number) row.get("applied_revision")).longValue());
                assertEquals(false, row.get("has_settlement"));
                assertNull(row.get("first_record_id"));
                assertTrue(db.queryForObject("""
                    SELECT requested_revision>applied_revision FROM finance_first_settlement_facts
                    WHERE organization_id='org' AND shop_id='shop' AND order_id='order'
                    """, Boolean.class));
            } finally {
                calculated.countDown();
                writer.cancel(true);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the source writer must leave no open transaction");
        }
    }

    @Test void calculateReadsActualRevisionAfterClaimAndPublishesCopiedWinnerFields() throws Exception {
        var facts = facts();
        settlement("later", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        registered("org");
        var lease = claim(facts, "org");
        committed("UPDATE finance_records SET record_date='2020-03-04',platform='LAZADA',currency='USD' WHERE id='later'");
        var computed = facts.calculate(lease);
        assertEquals(2, computed.revision(), "calculation must read R rather than preserve the claim-time revision");
        assertEquals(new Candidate("later", LocalDate.of(2020, 3, 4), "LAZADA", "USD"), computed.first().orElseThrow());
        assertTrue(facts.publish(computed));
        var row = fact(lease.owner());
        assertEquals(2L, applied(lease.owner()));
        assertEquals("later", row.get("first_record_id"));
        assertEquals(java.sql.Date.valueOf("2020-03-04"), row.get("first_record_date"));
        assertEquals("LAZADA", row.get("first_platform")); assertEquals("USD", row.get("first_currency"));
        assertEquals(1, ((Number) row.get("schema_version")).intValue());
        assertEquals(1L, ((Number) row.get("applied_generation")).longValue());
        assertEquals(1, ((Number) row.get("applied_registration_version")).intValue());
        assertNull(row.get("lease_token")); assertNull(row.get("lease_until"));
        assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty());
    }

    @Test void calculationUsesIndependentReadOnlyRepeatableReadDespiteAnOuterOldSnapshot() throws Exception {
        var facts = facts(); var old = calculated(facts);
        var outer = new TransactionTemplate(tx);
        outer.setReadOnly(true); outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertEquals(1, revision("org", "shop", "order"));
            try { committed("UPDATE finance_records SET currency='USD' WHERE id='current'"); }
            catch (Exception failure) { throw new AssertionError(failure); }
            assertEquals(1, revision("org", "shop", "order"), "the caller really retains its earlier snapshot");
            var current = facts.calculate(old.lease());
            assertEquals(2, current.revision()); assertEquals("USD", current.first().orElseThrow().currency());
            assertEquals(1, revision("org", "shop", "order"), "calculation must restore the caller transaction");
        });
    }

    @Test void revisionAndCandidateComeFromTheSameSnapshotWhenWriterCommitsBetweenTheirReads() throws Exception {
        var setup = facts(); var old = calculated(setup);
        var jdbc = new PausingJdbc();
        jdbc.beforeCandidate = () -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            try { committed("UPDATE finance_records SET record_date='2026-09-30',currency='USD' WHERE id='current'"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        var facts = new FinanceFirstSettlementFacts(new FinanceRepository(jdbc, new ObjectMapper()));
        var computed = facts.calculate(old.lease());
        assertNull(jdbc.beforeCandidate, "the writer must commit after R and before the real candidate SELECT");
        assertEquals(1, computed.revision());
        assertEquals(new Candidate("current", LocalDate.of(2026, 10, 2), "SHOPEE", "PHP"), computed.first().orElseThrow());
        assertEquals(2, revision("org", "shop", "order"));
        assertFalse(facts.publish(computed)); assertEquals(0, applied(old.lease().owner()));
    }

    @Test void uncommittedLowIdAndEarlierEventAreInvisibleUntilTheirLateCommit() throws Exception {
        settlement("z-current", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        registered("org"); var facts = facts(); var lease = claim(facts, "org");
        var inserted = new CountDownLatch(1); var allowCommit = new CountDownLatch(1);
        var lowEvent = new AtomicLong(); var pool = Executors.newSingleThreadExecutor();
        try {
            var writer = pool.submit(() -> {
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        insert(connection, "a-low", "org", "shop", "order", LocalDate.of(2026, 10, 2));
                        try (var statement = connection.createStatement();
                             var rows = statement.executeQuery("SELECT MAX(event_id) FROM finance_query_events")) {
                            assertTrue(rows.next()); lowEvent.set(rows.getLong(1));
                        }
                        inserted.countDown();
                        assertTrue(allowCommit.await(10, TimeUnit.SECONDS)); connection.commit();
                    } catch (Exception | Error failure) { connection.rollback(); throw failure; }
                }
                return null;
            });
            try {
                assertTrue(inserted.await(10, TimeUnit.SECONDS));
                settlement("z-high", "org", "another-shop", "other-order", LocalDate.of(2026, 10, 2));
                assertTrue(db.queryForObject("SELECT MAX(event_id) FROM finance_query_events", Long.class) > lowEvent.get());
                var computed = facts.calculate(lease);
                assertEquals(1, computed.revision()); assertEquals("z-current", computed.first().orElseThrow().id());
                allowCommit.countDown(); writer.get(10, TimeUnit.SECONDS);
                assertFalse(facts.publish(computed)); assertEquals(2, revision("org", "shop", "order"));
                var next = facts.calculate(lease);
                assertEquals("a-low", next.first().orElseThrow().id());
                assertTrue(facts.publish(next)); assertEquals(2, applied(lease.owner()));
            } finally { allowCommit.countDown(); writer.cancel(true); }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void fullHistoryEligibilityAndDateIdTieAreResolvedBeforeCopiedPlatformOrCurrency() {
        settlement("later", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        insertSettlement("b-first", "org", "shop", "order", "LAZADA", "settlement", "SETTLED", "API", LocalDate.of(2019, 1, 1), "USD");
        insertSettlement("a-first", "org", "shop", "order", "SHOPEE", "settlement", "SETTLED", "API", LocalDate.of(2019, 1, 1), "PHP");
        insertSettlement("fee", "org", "shop", "order", "TIKTOK_SHOP", "FEE", "SETTLED", "API", LocalDate.of(2018, 1, 1), "USD");
        insertSettlement("cancelled", "org", "shop", "order", "SHOPEE", "settlement", "CANCELLED", "API", LocalDate.of(2017, 1, 1), "USD");
        insertSettlement("manual", "org", "shop", "order", "SHOPEE", "settlement", "SETTLED", "MANUAL", LocalDate.of(2016, 1, 1), "USD");
        settlement("foreign", "other-org", "shop", "order", LocalDate.of(2015, 1, 1));
        registered("org"); var facts = facts(); var computed = facts.calculate(claim(facts, "org"));
        assertEquals(new Candidate("a-first", LocalDate.of(2019, 1, 1), "SHOPEE", "PHP"), computed.first().orElseThrow());
        assertTrue(facts.publish(computed));
    }

    @Test void updateAfterCalculationRejectsOldMonthAndRepublishesAllCopiedFieldsTogether() throws Exception {
        var facts = facts(); var old = calculated(facts);
        committed("UPDATE finance_records SET record_date='2026-09-30',platform='LAZADA',currency='USD' WHERE id='current'");
        assertFalse(facts.publish(old)); assertEquals(0, applied(old.lease().owner()));
        var current = facts.calculate(old.lease()); assertEquals(2, current.revision());
        assertTrue(facts.publish(current));
        assertEquals(new Candidate("current", LocalDate.of(2026, 9, 30), "LAZADA", "USD"), current.first().orElseThrow());
        var row = fact(old.lease().owner());
        assertEquals(java.sql.Date.valueOf("2026-09-30"), row.get("first_record_date"));
        assertEquals("LAZADA", row.get("first_platform")); assertEquals("USD", row.get("first_currency"));
    }

    @Test void deletionRejectsStaleCandidateAndKnownEmptyCanBeDirtiedByReinsertion() throws Exception {
        var facts = facts(); var original = calculated(facts); assertTrue(facts.publish(original));
        settlement("tail", "org", "shop", "order", LocalDate.of(2026, 10, 3));
        var beforeDelete = facts.calculate(claim(facts, "org"));
        committed("DELETE FROM finance_records WHERE id='current'");
        assertFalse(facts.publish(beforeDelete));
        var tail = facts.calculate(beforeDelete.lease()); assertEquals("tail", tail.first().orElseThrow().id());
        assertTrue(facts.publish(tail));
        committed("DELETE FROM finance_records WHERE id='tail'");
        var empty = facts.calculate(claim(facts, "org")); assertTrue(empty.first().isEmpty());
        assertTrue(facts.publish(empty));
        var row = fact(empty.lease().owner());
        assertEquals(false, row.get("has_settlement"));
        for (String field : List.of("first_record_id", "first_record_date", "first_platform", "first_currency")) assertNull(row.get(field));
        assertEquals(4, applied(empty.lease().owner()));
        settlement("again", "org", "shop", "order", LocalDate.of(2026, 8, 1));
        assertEquals(5, revision("org", "shop", "order"));
        var restored = facts.calculate(claim(facts, "org")); assertEquals("again", restored.first().orElseThrow().id());
        assertTrue(facts.publish(restored));
    }

    @Test void expiredLeaseRejectsCalculationAndPublicationUntilAReplacementClaimsIt() {
        var facts = facts(); var old = calculated(facts);
        db.update("UPDATE finance_first_settlement_facts SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
        assertThrows(IllegalStateException.class, () -> facts.calculate(old.lease()));
        assertFalse(facts.publish(old)); assertEquals(0, applied(old.lease().owner()));
        var replacement = claim(facts, "org"); assertNotEquals(old.lease().token(), replacement.token());
        assertFalse(facts.publish(old)); assertTrue(facts.publish(facts.calculate(replacement)));
    }

    @Test void aReplacedTokenAloneRejectsOldCalculationAndPublication() throws Exception {
        var facts = facts(); var old = calculated(facts);
        committed("UPDATE finance_first_settlement_facts SET lease_token=? WHERE organization_id='org' AND shop_id='shop' AND order_id='order'", UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, () -> facts.calculate(old.lease()));
        assertFalse(facts.publish(old)); assertEquals(0, applied(old.lease().owner()));
    }

    @Test void changedGenerationAloneRejectsOldPublication() throws Exception {
        var facts = facts(); var old = calculated(facts);
        committed("UPDATE finance_first_settlement_initialization SET generation=2 WHERE organization_id='org'");
        assertThrows(IllegalStateException.class, () -> facts.calculate(old.lease()));
        assertFalse(facts.publish(old)); assertEquals(0, applied(old.lease().owner()));
    }

    @Test void changedTargetSchemaAloneRejectsOldPublicationAndClaim() throws Exception {
        var facts = facts(); var old = calculated(facts);
        committed("UPDATE finance_first_settlement_initialization SET target_schema_version=2 WHERE organization_id='org'");
        assertThrows(IllegalStateException.class, () -> facts.calculate(old.lease()));
        assertFalse(facts.publish(old)); assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty());
        assertEquals(0, applied(old.lease().owner()));
    }

    @Test void changedRegistrationAloneRejectsOldPublication() throws Exception {
        var facts = facts(); var old = calculated(facts);
        committed("UPDATE finance_first_settlement_initialization SET registration_version=2 WHERE organization_id='org'");
        assertThrows(IllegalStateException.class, () -> facts.calculate(old.lease()));
        assertFalse(facts.publish(old)); assertEquals(0, applied(old.lease().owner()));
    }

    @Test void mismatchedOrganizationInExpectedGuardRejectsPublication() {
        var facts = facts(); var old = calculated(facts); var g = old.lease().guard();
        settlement("foreign-source", "foreign", "shop", "order", LocalDate.of(2026, 9, 1)); registered("foreign");
        var foreign = facts.guard("foreign").orElseThrow();
        assertEquals(g.generation(), foreign.generation()); assertEquals(g.targetSchemaVersion(), foreign.targetSchemaVersion());
        assertEquals(g.registrationVersion(), foreign.registrationVersion());
        var wrong = new Lease(old.lease().owner(), new Guard("foreign", g.generation(), g.targetSchemaVersion(), g.registrationVersion(), g.seedComplete()), old.lease().token());
        assertThrows(IllegalStateException.class, () -> facts.calculate(wrong));
        assertFalse(facts.publish(new Computed(wrong, old.revision(), old.first())));
        assertEquals(0, applied(old.lease().owner()));
    }

    @Test void sourceCompanyCaseAliasConvergesThroughDiscoveryClaimCalculationAndPublication() throws Exception {
        insertSettlement("case-alias", "ORG", "SHOP", "Café", "SHOPEE", "settlement", "SETTLED", "API", LocalDate.of(2026, 10, 2), "PHP");
        committed("UPDATE finance_records SET organization_id='org',shop_id='shop',order_id='cafe',record_date='2026-09-30' WHERE id='case-alias'");
        registered("org"); var facts = facts(); var lease = claim(facts, "org");
        assertEquals("ORG", lease.owner().organizationId()); assertEquals("org", lease.guard().organizationId());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts", Integer.class));
        var computed = facts.calculate(lease);
        assertEquals(2, computed.revision());
        assertEquals(new Candidate("case-alias", LocalDate.of(2026, 9, 30), "SHOPEE", "PHP"), computed.first().orElseThrow());
        assertTrue(facts.publish(computed), "SQL-equivalent company spellings must publish the computed revision");
        assertEquals(2, applied(lease.owner())); assertEquals(false, fact(lease.owner()).get("pending"));
        assertNull(fact(lease.owner()).get("lease_token"));
    }

    @Test void sourceCompanyAccentAliasConvergesThroughDiscoveryClaimCalculationAndPublication() throws Exception {
        settlement("accent-company", "Café-Co", "shop", "order", LocalDate.of(2026, 10, 2));
        committed("UPDATE finance_records SET organization_id='cafe-co',record_date='2026-09-29' WHERE id='accent-company'");
        registered("cafe-co"); var facts = facts(); var lease = claim(facts, "cafe-co");
        assertEquals("Café-Co", lease.owner().organizationId()); assertEquals("cafe-co", lease.guard().organizationId());
        var computed = facts.calculate(lease); assertEquals(2, computed.revision());
        assertEquals(new Candidate("accent-company", LocalDate.of(2026, 9, 29), "SHOPEE", "PHP"), computed.first().orElseThrow());
        assertTrue(facts.publish(computed), "accent-equivalent company spellings must converge");
        assertEquals(2, applied(lease.owner())); assertEquals(false, fact(lease.owner()).get("pending"));
    }

    @Test void sqlEquivalentExpectedGuardSpellingBindsToTheSameCompanyForCalculationAndPublication() {
        var facts = facts(); var old = calculated(facts); var guard = old.lease().guard();
        var alias = new Lease(old.lease().owner(), new Guard("ÓRG", guard.generation(), guard.targetSchemaVersion(),
            guard.registrationVersion(), guard.seedComplete()), old.lease().token());
        var computed = assertDoesNotThrow(() -> facts.calculate(alias), "database company equality must also bind the expected guard");
        assertEquals(1, computed.revision()); assertEquals("current", computed.first().orElseThrow().id());
        assertTrue(facts.publish(computed)); assertEquals(1, applied(alias.owner()));
    }

    @Test void seedCompletionWithinTheSameVersionStillAllowsPublication() throws Exception {
        var facts = facts(); var old = calculated(facts);
        committed("UPDATE finance_first_settlement_initialization SET seed_complete=TRUE WHERE organization_id='org'");
        assertTrue(facts.publish(old)); assertEquals(1, applied(old.lease().owner()));
    }

    @Test void globalProofMustRemainEnabledSupportedAndMatchedToTheCompany() {
        var facts = facts(); var old = calculated(facts);
        for (String assignment : List.of("enabled=FALSE", "schema_version=2", "registration_version=2", "definition_sha='unreviewed'", "installation_boundary='changed'")) {
            db.update("UPDATE finance_first_settlement_registration SET " + assignment + " WHERE id=1");
            assertFalse(facts.publish(old), assignment);
            assertThrows(IllegalStateException.class, () -> facts.calculate(old.lease()), assignment);
            assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty(), assignment);
            db.update("UPDATE finance_first_settlement_registration SET enabled=TRUE,schema_version=1,registration_version=1,definition_sha=?,installation_boundary='disposable-runner-install' WHERE id=1", triggerDefinitionSha());
        }
        assertEquals(0, applied(old.lease().owner()));
    }

    @Test void publicationDatabaseFailureRollsBackCopiedFactAndAppliedRevision() {
        var facts = facts(); var original = calculated(facts); assertTrue(facts.publish(original));
        settlement("earlier", "org", "shop", "order", LocalDate.of(2026, 9, 1));
        var replacement = facts.calculate(claim(facts, "org")); var before = fact(replacement.lease().owner());
        db.execute("CREATE TRIGGER fsf_test_publish_fail BEFORE UPDATE ON finance_first_settlement_facts FOR EACH ROW BEGIN IF NEW.applied_revision>OLD.applied_revision THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected publication failure'; END IF; END");
        try {
            assertThrows(DataAccessException.class, () -> facts.publish(replacement));
            assertEquals(before, fact(replacement.lease().owner()));
        } finally { db.execute("DROP TRIGGER fsf_test_publish_fail"); }
        assertTrue(facts.publish(replacement)); assertEquals("earlier", fact(replacement.lease().owner()).get("first_record_id"));
    }

    @Test void sourceCommitAfterPublicationLeavesItsOwnerDirtyAgain() throws Exception {
        var setup = facts(); var computed = calculated(setup); var jdbc = new PausingJdbc();
        var updated = new CountDownLatch(1); var attempting = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var writer = pool.submit(() -> {
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        assertTrue(updated.await(10, TimeUnit.SECONDS)); attempting.countDown();
                        insert(connection, "after", "org", "shop", "order", LocalDate.of(2026, 9, 1));
                        connection.commit();
                    } catch (Exception | Error failure) { connection.rollback(); throw failure; }
                }
                return null;
            });
            try {
                jdbc.afterPublication = () -> {
                    updated.countDown();
                    try { assertTrue(attempting.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                };
                var facts = new FinanceFirstSettlementFacts(new FinanceRepository(jdbc, new ObjectMapper()));
                assertTrue(facts.publish(computed)); assertNull(jdbc.afterPublication);
                writer.get(10, TimeUnit.SECONDS);
                assertEquals(1, applied(computed.lease().owner())); assertEquals(2, revision("org", "shop", "order"));
                assertEquals("current", fact(computed.lease().owner()).get("first_record_id"));
                assertTrue(db.queryForObject("SELECT pending FROM finance_first_settlement_facts", Boolean.class));
            } finally { updated.countDown(); writer.cancel(true); }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void claimsBoundLeasedCandidateEnumerationAndAdvancePastThatWindow() {
        for (int i = 0; i < 40; i++) settlement("leased-" + i, "org", "shop", "%03d".formatted(i), LocalDate.of(2026, 10, 2));
        registered("org");
        db.update("UPDATE finance_first_settlement_facts SET lease_token='occupied',lease_until='2099-01-01' WHERE order_id<'039'");
        var facts = facts();
        assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty(), "one claim must stop after 32 occupied candidates");
        assertEquals("039", claim(facts, "org").owner().orderId());
        assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty());
    }

    @Test void claimSkipsLockedOwnersWithBoundedExactKeyProbes() throws Exception {
        for (int i = 0; i < 33; i++) settlement("locked-" + i, "org", "shop", "%03d".formatted(i), LocalDate.of(2026, 10, 2));
        registered("org"); var facts = facts();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("SELECT order_id FROM finance_first_settlement_facts WHERE organization_id='org' AND shop_id='shop' AND order_id=? FOR UPDATE")) {
                    for (int i = 0; i < 32; i++) {
                        statement.setString(1, "%03d".formatted(i)); statement.executeQuery().close();
                    }
                }
                assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty());
                assertEquals("032", claim(facts, "org").owner().orderId());
            } finally { connection.rollback(); }
        }
    }

    @Test void ownerCursorUsesSqlCollationAndDoesNotLetARequeuedLowKeyStarveTheTail() {
        settlement("accent", "org", "shop", "ä-low", LocalDate.of(2026, 10, 2));
        settlement("tail", "org", "shop", "z-tail", LocalDate.of(2026, 10, 2)); registered("org");
        var facts = facts(); var low = claim(facts, "org"); assertEquals("ä-low", low.owner().orderId());
        assertTrue(facts.publish(facts.calculate(low)));
        db.update("UPDATE finance_records SET currency='USD' WHERE id='accent'");
        assertEquals("z-tail", claim(facts, "org").owner().orderId());
        assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty(), "reaching the end wraps for the next bounded call");
        assertEquals("ä-low", claim(facts, "org").owner().orderId());
    }

    @Test void claimAndCalculationRejectMissingOrGenerationZeroCompanyWithoutCreatingCoverage() {
        settlement("new", "org", "shop", "order", LocalDate.of(2026, 10, 2)); var facts = facts();
        assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty());
        db.update("INSERT INTO finance_first_settlement_initialization(organization_id) VALUES('org')");
        assertTrue(facts.claim("org", UUID.randomUUID().toString(), 30).isEmpty());
        var fake = new Lease(new Owner("org", "shop", "order"), new Guard("org", 0, 0, 0, false), UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, () -> facts.calculate(fake));
        assertEquals(0, db.queryForObject("SELECT generation FROM finance_first_settlement_initialization WHERE organization_id='org'", Integer.class));
        assertEquals(0, applied(fake.owner()));
    }

    @Test void explicitTickDiscoversNewCompaniesAndKeepsEachCompanyBatchFair(@TempDir Path temporary) throws Exception {
        for (String org : List.of("a-company", "b-company")) for (int i = 0; i < 3; i++)
            settlement(org + i, org, "shop", "order-" + i, LocalDate.of(2026, 10, 2));
        var worker = worker(facts(), 1, 1, System::nanoTime, gate(temporary.resolve("gate")));
        assertEquals(new Tick(1, 1, 0), worker.tick());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id='a-company' AND pending=FALSE", Integer.class));
        assertEquals(new Tick(1, 1, 0), worker.tick());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id='b-company' AND pending=FALSE", Integer.class));
        boolean completed = false;
        for (int i = 0; i < 30; i++) {
            worker.tick();
            completed = db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_initialization WHERE seed_complete=FALSE", Integer.class) == 0
                && db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE pending=TRUE", Integer.class) == 0;
            if (completed) break;
        }
        assertTrue(completed, "bounded explicit ticks must complete initialization and owner publication");
    }

    @Test void explicitBatch256PublishesExactly256Of257PendingOwnersAndMatchesNativeWinners(@TempDir Path temporary) throws Exception {
        for (int index = 0; index < 257; index++) {
            String suffix = "%03d".formatted(index);
            settlement("tail-" + suffix, "org", "shop", "order-" + suffix, LocalDate.of(2026, 10, 2));
            settlement("winner-" + suffix, "org", "shop", "order-" + suffix, LocalDate.of(2020, 1, 1));
        }
        var facts = facts(); var init = initialization(); init.discoverCompanies(1);
        var guard = facts.guard("org").orElseThrow();
        boolean seeded = false;
        for (int step = 0; step < 12 && !seeded; step++) seeded = init.seed(guard, 64).complete();
        assertTrue(seeded, "the 257-owner seed must finish in bounded batches");
        boolean requeued = false;
        for (int step = 0; step < 12 && !requeued; step++) requeued = init.requeue(guard, 64).complete();
        assertTrue(requeued); assertTrue(init.complete(guard));
        assertTrue(facts.guard("org").orElseThrow().seedComplete());
        assertEquals(257, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE pending=TRUE AND requested_revision=3 AND applied_revision=0 AND lease_token IS NULL AND lease_until IS NULL", Integer.class));

        var worker = assertDoesNotThrow(() -> new FinanceFirstSettlementWorker(facts, init, 256, 30, 1,
            Duration.ofSeconds(30), System::nanoTime, gate(temporary.resolve("gate"))),
            "an explicitly bounded batch of 256 must be supported");
        assertEquals(new Tick(256, 256, 0), worker.tick());
        assertEquals(257, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts", Integer.class));
        assertEquals(256, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE pending=FALSE AND requested_revision=3 AND applied_revision=3 AND schema_version=1 AND applied_generation=1 AND applied_registration_version=1 AND lease_token IS NULL AND lease_until IS NULL", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE pending=TRUE", Integer.class));

        var published = db.queryForList("""
            SELECT f.order_id,f.first_record_id,f.first_record_date,f.first_platform,f.first_currency,
              r.id native_id,r.record_date native_date,r.platform native_platform,r.currency native_currency
            FROM finance_first_settlement_facts f JOIN finance_records r
              ON r.organization_id=f.organization_id AND r.shop_id=f.shop_id AND r.order_id=f.order_id
            WHERE f.organization_id='org' AND f.pending=FALSE AND
            """ + FinanceCostReport.firstSettlementIdentity("r", true) + " ORDER BY f.order_id");
        assertEquals(256, published.size(), "every published owner must have exactly one native winner");
        for (int index = 0; index < published.size(); index++) {
            var row = published.get(index); String suffix = "%03d".formatted(index);
            assertEquals("order-" + suffix, row.get("order_id")); assertEquals("winner-" + suffix, row.get("native_id"));
            assertEquals(row.get("native_id"), row.get("first_record_id"));
            assertEquals(row.get("native_date"), row.get("first_record_date"));
            assertEquals(row.get("native_platform"), row.get("first_platform"));
            assertEquals(row.get("native_currency"), row.get("first_currency"));
        }
        var untouched = fact(new Owner("org", "shop", "order-256"));
        assertEquals(3L, ((Number) untouched.get("requested_revision")).longValue());
        assertEquals(0L, ((Number) untouched.get("applied_revision")).longValue());
        assertEquals(true, untouched.get("pending")); assertEquals(false, untouched.get("has_settlement"));
        for (String field : List.of("lease_token", "lease_until", "first_record_id", "first_record_date", "first_platform", "first_currency")) assertNull(untouched.get(field), field);
        assertEquals(0, ((Number) untouched.get("schema_version")).intValue());
        assertEquals(0L, ((Number) untouched.get("applied_generation")).longValue());
        assertEquals(0, ((Number) untouched.get("applied_registration_version")).intValue());
    }

    @Test void proofMissingTickOnlyDiscoversGenerationZeroAndNeverClaimsIt(@TempDir Path temporary) throws Exception {
        settlement("new", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        db.update("DELETE FROM finance_first_settlement_registration");
        var worker = worker(facts(), 1, 1, System::nanoTime, gate(temporary.resolve("gate")));
        assertEquals(new Tick(0, 0, 0), worker.tick());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_initialization WHERE organization_id='org'", Integer.class), "explicit tick must discover a generation-zero pending company");
        assertEquals(0, db.queryForObject("SELECT generation FROM finance_first_settlement_initialization WHERE organization_id='org'", Integer.class));
        assertNull(fact(new Owner("org", "shop", "order")).get("lease_token"));
        db.update("INSERT INTO finance_first_settlement_registration(id,schema_version,registration_version,definition_sha,installation_boundary,enabled) VALUES(1,1,1,?,'disposable-runner-install',TRUE)", triggerDefinitionSha());
        worker.tick(); worker.tick();
        assertEquals(2, applied(new Owner("org", "shop", "order")), "initialization requeue and the original source insert both require application");
    }

    @Test void closedMaintenanceGatePreventsDiscoveryAndLeases(@TempDir Path temporary) throws Exception {
        settlement("new", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        var marker = temporary.resolve("maintenance.json"); Files.writeString(marker, "{}");
        var gate = gate(marker); var worker = worker(facts(), 1, 1, System::nanoTime, gate);
        assertEquals(new Tick(0, 0, 0), worker.tick());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_initialization", Integer.class));
        assertEquals(0, gate.activeWorkers());
        Files.delete(marker); assertEquals(1, worker.tick().published()); assertEquals(0, gate.activeWorkers());
    }

    @Test void tickDeadlineStopsBeforeStartingAnotherMaintenanceOrClaimUnit(@TempDir Path temporary) throws Exception {
        settlement("new", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        var calls = new AtomicInteger(); var clock = (java.util.function.LongSupplier) () -> calls.getAndIncrement() == 0 ? 0 : TimeUnit.SECONDS.toNanos(3);
        var worker = worker(facts(), 1, 1, clock, gate(temporary.resolve("gate")));
        assertEquals(new Tick(0, 0, 0), worker.tick());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_initialization", Integer.class));
        assertNull(fact(new Owner("org", "shop", "order")).get("lease_token"));
    }

    @Test void explicitTickDoesNotAdvanceAnExistingCompanyToANewInstallationProof(@TempDir Path temporary) throws Exception {
        var facts = facts(); var old = calculated(facts);
        db.update("UPDATE finance_first_settlement_registration SET registration_version=2,installation_boundary='second-installation' WHERE id=1");
        var worker = worker(facts, 1, 1, System::nanoTime, gate(temporary.resolve("gate")));
        assertEquals(new Tick(0, 0, 0), worker.tick());
        var company = db.queryForMap("SELECT generation,registration_version FROM finance_first_settlement_initialization WHERE organization_id='org'");
        assertEquals(1L, ((Number) company.get("generation")).longValue());
        assertEquals(1, ((Number) company.get("registration_version")).intValue());
        assertEquals(0, applied(old.lease().owner()));
    }

    @Test void maintenanceClosingDuringSeedStopsBeforeTheNextUnit(@TempDir Path temporary) throws Exception {
        settlement("new", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        var marker = temporary.resolve("gate"); var gate = gate(marker); var jdbc = new PausingJdbc();
        jdbc.beforeCandidate = () -> {
            assertEquals(1, gate.activeWorkers());
            try { Files.writeString(marker, "{}"); } catch (Exception failure) { throw new AssertionError(failure); }
        };
        var repository = new FinanceRepository(jdbc, new ObjectMapper());
        var worker = new FinanceFirstSettlementWorker(new FinanceFirstSettlementFacts(repository),
            new FinanceFirstSettlementInitialization(repository), 1, 30, 1, Duration.ofSeconds(2), System::nanoTime, gate);
        assertEquals(new Tick(0, 0, 0), worker.tick()); assertNull(jdbc.beforeCandidate);
        assertEquals(0, gate.activeWorkers());
        assertEquals("order", db.queryForObject("SELECT order_after FROM finance_first_settlement_initialization WHERE organization_id='org'", String.class));
        assertEquals(0, db.queryForObject("SELECT requeue_complete FROM finance_first_settlement_initialization WHERE organization_id='org'", Integer.class));
        assertNull(fact(new Owner("org", "shop", "order")).get("lease_token"));
    }

    @Test void interruptedTickStartsNoDatabaseUnit(@TempDir Path temporary) throws Exception {
        settlement("new", "org", "shop", "order", LocalDate.of(2026, 10, 2));
        var worker = worker(facts(), 1, 1, System::nanoTime, gate(temporary.resolve("gate")));
        try {
            Thread.currentThread().interrupt(); assertEquals(new Tick(0, 0, 0), worker.tick());
        } finally { Thread.interrupted(); }
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_initialization", Integer.class));
    }

    @Test void proofShareLockPrecedesCompanyLockForClaimAndPublication() throws Exception {
        var setup = facts(); var computed = calculated(setup);
        for (boolean publishing : List.of(false, true)) {
            var jdbc = new PausingJdbc(); var entered = new CountDownLatch(1); jdbc.beforeProof = entered::countDown;
            var facts = new FinanceFirstSettlementFacts(new FinanceRepository(jdbc, new ObjectMapper()));
            var pool = Executors.newSingleThreadExecutor();
            try (var maintenance = dataSource.getConnection()) {
                maintenance.setAutoCommit(false);
                try (var statement = maintenance.createStatement()) {
                    statement.executeQuery("SELECT id FROM finance_first_settlement_registration WHERE id=1 FOR UPDATE").close();
                }
                var future = pool.submit(() -> publishing ? facts.publish(computed)
                    : facts.claim("org", UUID.randomUUID().toString(), 30).isPresent());
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); boolean waiting = false;
                    for (int observation = 0; observation < 300 && System.nanoTime() < deadline; observation++) {
                        waiting = db.queryForObject("""
                            SELECT COUNT(*) FROM information_schema.processlist WHERE DB=DATABASE() AND ID<>CONNECTION_ID()
                              AND INFO LIKE '%finance_first_settlement_registration%FOR SHARE%'
                            """, Integer.class) > 0;
                        if (waiting) break;
                    }
                    assertTrue(waiting, "the operation must be waiting on the proof before taking the company lock");
                    try (var observer = dataSource.getConnection(); var statement = observer.createStatement()) {
                        observer.setAutoCommit(false);
                        try { statement.executeQuery("SELECT organization_id FROM finance_first_settlement_initialization WHERE organization_id='org' FOR UPDATE NOWAIT").close(); }
                        finally { observer.rollback(); }
                    }
                    try (var statement = maintenance.createStatement()) { statement.executeUpdate("UPDATE finance_first_settlement_registration SET enabled=FALSE WHERE id=1"); }
                    maintenance.commit(); assertFalse(future.get(10, TimeUnit.SECONDS)); assertEquals(0, applied(computed.lease().owner()));
                } finally { maintenance.rollback(); future.cancel(true); }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
            db.update("UPDATE finance_first_settlement_registration SET enabled=TRUE WHERE id=1");
        }
    }

    @Test void blockedClaimAndPublicationTimeOutAndReturnTheirConnections() throws Exception {
        var facts = facts(); var computed = calculated(facts);
        for (boolean publishing : List.of(false, true)) {
            var pool = Executors.newSingleThreadExecutor();
            try (var maintenance = dataSource.getConnection()) {
                maintenance.setAutoCommit(false);
                try (var statement = maintenance.createStatement()) {
                    statement.executeQuery("SELECT id FROM finance_first_settlement_registration WHERE id=1 FOR UPDATE").close();
                }
                var future = pool.submit(() -> assertThrows(DataAccessException.class, () -> {
                    if (publishing) facts.publish(computed);
                    else facts.claim("org", UUID.randomUUID().toString(), 30);
                }));
                try {
                    assertNotNull(future.get(8, TimeUnit.SECONDS), "the database wait must end within the transaction/query bound");
                    assertEquals(1, dataSource.getHikariPoolMXBean().getActiveConnections(), "only the explicit maintenance transaction may retain a connection");
                    assertEquals(0, applied(computed.lease().owner()));
                } finally { maintenance.rollback(); future.cancel(true); }
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    private void publicationRetryFixture(int failures) {
        db.execute("CREATE TABLE fsf_retry_attempts(id INT PRIMARY KEY,n INT NOT NULL) ENGINE=MEMORY");
        db.update("INSERT INTO fsf_retry_attempts VALUES(1,0)");
        db.execute("""
            CREATE TRIGGER fsf_test_retry BEFORE UPDATE ON finance_first_settlement_facts FOR EACH ROW
            BEGIN IF NEW.applied_revision>OLD.applied_revision THEN
              UPDATE fsf_retry_attempts SET n=n+1 WHERE id=1;
              IF (SELECT n FROM fsf_retry_attempts WHERE id=1)<=%d THEN
                SIGNAL SQLSTATE '40001' SET MYSQL_ERRNO=1213,MESSAGE_TEXT='injected retryable transaction';
              END IF;
            END IF; END
            """.formatted(failures));
    }
    private void removePublicationRetryFixture() {
        try { db.execute("DROP TRIGGER IF EXISTS fsf_test_retry"); }
        finally { db.execute("DROP TABLE IF EXISTS fsf_retry_attempts"); }
    }

    @Test void publicationRetriesTwoRetryableTransactionsThenAppliesExactlyOnce() {
        var facts = facts(); var computed = calculated(facts);
        try {
            publicationRetryFixture(2); assertTrue(facts.publish(computed));
            assertEquals(3, db.queryForObject("SELECT n FROM fsf_retry_attempts WHERE id=1", Integer.class));
            assertEquals(1, applied(computed.lease().owner())); assertNull(fact(computed.lease().owner()).get("lease_token"));
        } finally { removePublicationRetryFixture(); }
    }

    @Test void publicationReportsRetryFailureAfterThreeWholeTransactionsAndPreservesOldFact() {
        var facts = facts(); var computed = calculated(facts); var before = fact(computed.lease().owner());
        try {
            publicationRetryFixture(10); assertThrows(DataAccessException.class, () -> facts.publish(computed));
            assertEquals(3, db.queryForObject("SELECT n FROM fsf_retry_attempts WHERE id=1", Integer.class));
            assertEquals(before, fact(computed.lease().owner()));
        } finally { removePublicationRetryFixture(); }
    }

    @Test void invalidCapacityAndLeaseConfigurationAreRejected(@TempDir Path temporary) throws Exception {
        var facts = facts(); var gate = gate(temporary.resolve("gate"));
        assertThrows(IllegalArgumentException.class, () -> worker(facts, 0, 1, System::nanoTime, gate));
        assertThrows(IllegalArgumentException.class, () -> worker(facts, 257, 1, System::nanoTime, gate));
        assertThrows(IllegalArgumentException.class, () -> worker(facts, 1, 0, System::nanoTime, gate));
        assertThrows(IllegalArgumentException.class, () -> new FinanceFirstSettlementWorker(facts, initialization(), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> facts.claim("org", "", 30));
        assertThrows(IllegalArgumentException.class, () -> facts.claim("org", UUID.randomUUID().toString(), 0));
    }

    private int swapWithBoundedRetries(String record, String nextOrder, CountDownLatch start) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    updateAndCommit(connection, "UPDATE finance_records SET order_id=? WHERE id=?", nextOrder, record);
                    return attempt;
                } catch (SQLException failure) {
                    connection.rollback();
                    if (attempt == 3 || (failure.getErrorCode() != 1213 && !"40001".equals(failure.getSQLState()))) throw failure;
                }
            }
        }
        throw new AssertionError("source transaction retry bound exhausted");
    }

    @Test void concurrentOwnerSwapsAndWorkerPreserveEverySuccessfulSourceRegistration(@TempDir Path temporary) throws Exception {
        settlement("source-a", "org", "shop", "A", LocalDate.of(2026, 10, 1));
        settlement("source-b", "org", "shop", "B", LocalDate.of(2026, 10, 2)); registered("org");
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(3);
        var worker = worker(facts(), 2, 1, System::nanoTime, gate(temporary.resolve("gate")));
        try {
            var a = pool.submit(() -> swapWithBoundedRetries("source-a", "B", start));
            var b = pool.submit(() -> swapWithBoundedRetries("source-b", "A", start));
            var working = pool.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); for (int i = 0; i < 8; i++) worker.tick(); return null; });
            start.countDown();
            int attemptsA = a.get(10, TimeUnit.SECONDS); int attemptsB = b.get(10, TimeUnit.SECONDS);
            assertTrue(attemptsA <= 3); assertTrue(attemptsB <= 3); working.get(10, TimeUnit.SECONDS);
            System.out.printf("first-settlement owner-swap source attempts: %d,%d%n", attemptsA, attemptsB);
            for (int i = 0; i < 6; i++) worker.tick();
            assertEquals(4, revision("org", "shop", "A")); assertEquals(4, revision("org", "shop", "B"));
            assertEquals(4, applied(new Owner("org", "shop", "A"))); assertEquals(4, applied(new Owner("org", "shop", "B")));
            assertEquals("source-b", fact(new Owner("org", "shop", "A")).get("first_record_id"));
            assertEquals("source-a", fact(new Owner("org", "shop", "B")).get("first_record_id"));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
