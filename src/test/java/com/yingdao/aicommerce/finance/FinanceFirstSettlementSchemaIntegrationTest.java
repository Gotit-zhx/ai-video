package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC", matches=".+")
class FinanceFirstSettlementSchemaIntegrationTest extends FinanceFirstSettlementTestSupport {
    @Test
    void prototypeSchemaAndIndependentSourceTriggersAreInstalled() {
        for (String table : new String[]{
            "finance_first_settlement_facts",
            "finance_first_settlement_registration",
            "finance_first_settlement_initialization"
        }) {
            assertTrue(tableExists(table), "Missing first-settlement prototype table: " + table);
        }
        assertEquals(3, triggerCount(), "All three independent source registration triggers must exist");
        assertEquals(0, registrationRowsAfterInstall,
            "Installing prototype DDL must not publish a registration proof");
        assertEquals(1, db.queryForObject(
            "SELECT enabled FROM finance_first_settlement_registration WHERE id=1", Integer.class));
        assertEquals(triggerDefinitionSha(), db.queryForObject(
            "SELECT definition_sha FROM finance_first_settlement_registration WHERE id=1", String.class));
    }

    @Test
    void schemaMatchesSourceIdentityAndUsesPendingAndDateIndexes() {
        assertEquals(List.of("organization_id", "shop_id", "order_id", "requested_revision",
            "applied_revision", "has_settlement", "first_record_id", "first_record_date",
            "first_platform", "first_currency", "schema_version", "applied_generation",
            "applied_registration_version", "queued_generation", "lease_token", "lease_until",
            "updated_at", "pending"), tableColumns("finance_first_settlement_facts"));
        assertEquals(List.of("id", "schema_version", "registration_version", "definition_sha",
            "installation_boundary", "enabled", "updated_at"),
            tableColumns("finance_first_settlement_registration"));
        assertEquals(List.of("organization_id", "generation", "target_schema_version",
            "registration_version", "registration_definition_sha", "registration_boundary",
            "seed_complete", "shop_after", "current_shop", "order_after", "shops_complete",
            "requeue_shop_after", "requeue_order_after", "requeue_complete", "updated_at"),
            tableColumns("finance_first_settlement_initialization"));
        var identity = db.queryForList("""
            SELECT table_name,column_name,column_type,character_set_name,collation_name
            FROM information_schema.columns
            WHERE table_schema=DATABASE()
              AND table_name IN ('finance_records','finance_first_settlement_facts','finance_label_order_totals')
              AND column_name IN ('organization_id','shop_id','order_id')
            ORDER BY table_name,column_name
            """);
        Map<String, Map<String, Object>> columns = identity.stream().collect(java.util.stream.Collectors.toMap(
            row -> text(row.get("table_name")) + "." + text(row.get("column_name")), row -> row));
        for (String column : List.of("organization_id", "shop_id", "order_id")) {
            var source = columns.get("finance_records." + column);
            var fact = columns.get("finance_first_settlement_facts." + column);
            assertEquals(source.get("column_type"), fact.get("column_type"), column);
            assertEquals(source.get("character_set_name"), fact.get("character_set_name"), column);
            assertEquals(source.get("collation_name"), fact.get("collation_name"), column);
        }
        assertEquals("varchar(255)", columns.get("finance_label_order_totals.order_id").get("column_type"));

        assertEquals(List.of("organization_id", "pending", "shop_id", "order_id"), indexColumns("idx_fsf_pending"));
        assertEquals(List.of("organization_id", "first_record_date", "shop_id", "order_id"), indexColumns("idx_fsf_date"));
        assertEquals("STORED GENERATED", db.queryForObject("""
            SELECT extra FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='finance_first_settlement_facts' AND column_name='pending'
            """, String.class));

        for (int index = 0; index < 200; index++) {
            db.update("""
                INSERT INTO finance_first_settlement_facts
                  (organization_id,shop_id,order_id,requested_revision,applied_revision)
                VALUES('org',?, ?,1,1)
                """, String.format("clean-%03d", index), String.format("order-%03d", index));
        }
        db.update("INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id) VALUES('org','pending-a','a'),('org','pending-b','b')");
        var explain = db.queryForMap("""
            EXPLAIN SELECT organization_id,shop_id,order_id
            FROM finance_first_settlement_facts
            WHERE organization_id='org' AND pending=1
            ORDER BY shop_id,order_id LIMIT 10
            """);
        assertEquals("idx_fsf_pending", explain.get("key"), explain.toString());
        assertFalse("ALL".equals(explain.get("type")), explain.toString());

        var invalidRevision = assertThrows(DataAccessException.class, () -> db.update("""
            INSERT INTO finance_first_settlement_facts
              (organization_id,shop_id,order_id,requested_revision)
            VALUES('org','invalid','revision',0)
            """));
        assertTrue(invalidRevision.getMostSpecificCause().getMessage().contains("Check constraint"));
        var invalidShape = assertThrows(DataAccessException.class, () -> db.update("""
            INSERT INTO finance_first_settlement_facts
              (organization_id,shop_id,order_id,has_settlement)
            VALUES('org','invalid','shape',TRUE)
            """));
        assertTrue(invalidShape.getMostSpecificCause().getMessage().contains("Check constraint"));
    }

    @Test
    void sourceRollbackAlsoRollsBackOwnerRegistration() {
        String shop = groupedShop(owner, "fsf-schema", null);
        new TransactionTemplate(tx).execute(status -> {
            insertSettlement("fsf-a", owner.organizationId(), shop, "a", "SHOPEE", "settlement",
                "SETTLED", "API", LocalDate.of(2026, 10, 2), "PHP");
            assertEquals(1L, revision(owner.organizationId(), shop, "a"));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(0, ownerRows(owner.organizationId(), shop, "a"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE id='fsf-a'", Integer.class));
    }

    @Test
    void insertDeleteAndIdentityRelevantUpdatesAdvanceWithoutOverwritingPublishedState() {
        insertSettlement("winner", "org", "shop", "order", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        insertSettlement("later", "org", "shop", "order", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 3), "PHP");
        assertEquals(2L, revision("org", "shop", "order"));
        db.update("""
            UPDATE finance_first_settlement_facts
            SET applied_revision=2,has_settlement=TRUE,first_record_id='winner',
                first_record_date='2026-10-02',first_platform='SHOPEE',first_currency='PHP',
                schema_version=1,applied_generation=4,applied_registration_version=1,
                lease_token='00000000-0000-0000-0000-000000000001',
                lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)
            WHERE organization_id='org' AND shop_id='shop' AND order_id='order'
            """);
        db.update("UPDATE finance_records SET platform='TIKTOK_SHOP',category='ORDER' WHERE id='later'");
        db.update("UPDATE finance_records SET currency='USD' WHERE id='later'");
        db.update("UPDATE finance_records SET record_date='2026-10-04' WHERE id='later'");
        db.update("UPDATE finance_records SET id='later-renamed' WHERE id='later'");
        var state = db.queryForMap("""
            SELECT * FROM finance_first_settlement_facts
            WHERE organization_id='org' AND shop_id='shop' AND order_id='order'
            """);
        assertEquals(6L, number(state.get("requested_revision")));
        assertEquals(2L, number(state.get("applied_revision")));
        assertEquals("winner", text(state.get("first_record_id")));
        assertEquals("SHOPEE", text(state.get("first_platform")));
        assertEquals("PHP", text(state.get("first_currency")));
        assertEquals(4L, number(state.get("applied_generation")));
        assertNotNull(state.get("lease_token"));

        int events = db.queryForObject("SELECT COUNT(*) FROM finance_query_events", Integer.class);
        db.update("UPDATE finance_records SET amount=12,raw_json='{\"sync\":true}',updated_at=UTC_TIMESTAMP(6) WHERE id='winner'");
        assertEquals(6L, revision("org", "shop", "order"),
            "Amount, raw payload and sync timestamp do not change first identity revision");
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM finance_query_events", Integer.class) > events,
            "The existing detail/amount event trigger must remain active");

        db.update("DELETE FROM finance_records WHERE id='later-renamed'");
        assertEquals(7L, revision("org", "shop", "order"));
    }

    @Test
    void eligibilityExactlyMatchesConfirmedAndTikTokOrderRules() {
        insertSettlement("non-api", "org", "shop", "non-api", "SHOPEE", "settlement", "SETTLED", "IMPORT",
            LocalDate.of(2026, 10, 2), "PHP");
        insertSettlement("cancelled", "org", "shop", "cancelled", "SHOPEE", "settlement", "CANCELLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        insertSettlement("tiktok-refund", "org", "shop", "tiktok-refund", "TIKTOK_SHOP", "REFUND", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        insertSettlement("empty", "org", "shop", "", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts", Integer.class));

        insertSettlement("mixed-invalid", "org", "shop", "mixed", "TIKTOK_SHOP", "REFUND", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        insertSettlement("mixed-valid", "org", "shop", "mixed", "TIKTOK_SHOP", "ORDER", "SETTLED", "API",
            LocalDate.of(2026, 10, 3), "PHP");
        assertEquals(1L, revision("org", "shop", "mixed"));
        db.update("UPDATE finance_records SET category='REFUND' WHERE id='mixed-valid'");
        assertEquals(2L, revision("org", "shop", "mixed"), "Leaving eligibility dirties the existing owner");
        db.update("UPDATE finance_records SET category='ORDER' WHERE id='mixed-valid'");
        assertEquals(3L, revision("org", "shop", "mixed"), "Entering eligibility dirties the owner");
        db.update("DELETE FROM finance_records WHERE id='mixed-invalid'");
        assertEquals(3L, revision("org", "shop", "mixed"), "Deleting an ineligible sibling is irrelevant");
    }

    @Test
    void everyEligibilityFieldTransitionDirtiesTheAffectedOwner() {
        insertSettlement("elig-kind", "org", "shop", "kind-order", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        db.update("UPDATE finance_records SET kind='FEE' WHERE id='elig-kind'");
        assertEquals(2L, revision("org", "shop", "kind-order"));
        db.update("UPDATE finance_records SET kind='SETTLEMENT' WHERE id='elig-kind'");
        assertEquals(3L, revision("org", "shop", "kind-order"));

        insertSettlement("elig-source", "org", "shop", "source-order", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        db.update("UPDATE finance_records SET source='IMPORT' WHERE id='elig-source'");
        assertEquals(2L, revision("org", "shop", "source-order"));
        db.update("UPDATE finance_records SET source='API' WHERE id='elig-source'");
        assertEquals(3L, revision("org", "shop", "source-order"));

        insertSettlement("elig-status", "org", "shop", "status-order", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        db.update("UPDATE finance_records SET status='CANCELLED' WHERE id='elig-status'");
        assertEquals(2L, revision("org", "shop", "status-order"));
        db.update("UPDATE finance_records SET status='SETTLED' WHERE id='elig-status'");
        assertEquals(3L, revision("org", "shop", "status-order"));

        insertSettlement("elig-platform", "org", "shop", "platform-order", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        db.update("UPDATE finance_records SET platform='TIKTOK_SHOP' WHERE id='elig-platform'");
        assertEquals(2L, revision("org", "shop", "platform-order"));
        db.update("UPDATE finance_records SET category='ORDER' WHERE id='elig-platform'");
        assertEquals(3L, revision("org", "shop", "platform-order"));

        insertSettlement("elig-order", "org", "shop", "present-order", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        db.update("UPDATE finance_records SET order_id='' WHERE id='elig-order'");
        assertEquals(2L, revision("org", "shop", "present-order"));
        assertEquals(0, ownerRows("org", "shop", ""));
        db.update("UPDATE finance_records SET order_id='present-order' WHERE id='elig-order'");
        assertEquals(3L, revision("org", "shop", "present-order"));
    }

    @Test
    void registrationFailureSurfacesAndRollsBackTheSourceUpdate() {
        insertSettlement("feedback", "org", "shop", "feedback-order", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        db.update("""
            UPDATE finance_first_settlement_facts SET requested_revision=9223372036854775807
            WHERE organization_id='org' AND shop_id='shop' AND order_id='feedback-order'
            """);
        try {
            var failure = assertThrows(DataAccessException.class,
                () -> db.update("UPDATE finance_records SET currency='USD' WHERE id='feedback'"));
            assertNotNull(failure.getMostSpecificCause());
            assertEquals("PHP", db.queryForObject("SELECT currency FROM finance_records WHERE id='feedback'", String.class));
            assertEquals(Long.MAX_VALUE, revision("org", "shop", "feedback-order"));
        } finally {
            db.update("""
                UPDATE finance_first_settlement_facts SET requested_revision=1
                WHERE organization_id='org' AND shop_id='shop' AND order_id='feedback-order'
                """);
        }
    }

    @Test
    void repositorySaveAndUpsertUseTheSameTransactionalRegistration() {
        String shop = groupedShop(owner, "fsf-repository", null);
        var original = new Entry(shop, "SHOPEE", "SETTLEMENT", "repo-settlement", "repo-order", "bill",
            "settlement", "SETTLED", LocalDate.of(2026, 10, 2), "PHP", BigDecimal.TEN,
            null, null, null, null, "API", Map.of("version", 1));
        repo.save(owner, original);
        assertEquals(1L, revision(owner.organizationId(), shop, "repo-order"));
        repo.save(owner, new Entry(shop, original.platform(), original.kind(), original.externalId(), original.orderId(),
            original.statementId(), original.category(), original.status(), original.date(), "USD", original.amount(),
            original.revenue(), original.impressions(), original.clicks(), original.conversions(), original.source(), Map.of("version", 2)));
        assertEquals(2L, revision(owner.organizationId(), shop, "repo-order"));
        repo.save(owner, new Entry(shop, original.platform(), original.kind(), original.externalId(), original.orderId(),
            original.statementId(), original.category(), original.status(), original.date(), "USD", BigDecimal.ONE,
            original.revenue(), original.impressions(), original.clicks(), original.conversions(), original.source(), Map.of("version", 3)));
        assertEquals(2L, revision(owner.organizationId(), shop, "repo-order"));
    }

    @Test
    void organizationShopAndOrderMovesDirtyOldAndNewOwnersInStableOrder() {
        insertSettlement("move", "org-a", "shop-a", "order-a", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        db.update("UPDATE finance_records SET organization_id='org-b' WHERE id='move'");
        assertEquals(2L, revision("org-a", "shop-a", "order-a"));
        assertEquals(1L, revision("org-b", "shop-a", "order-a"));
        db.update("UPDATE finance_records SET shop_id='shop-b' WHERE id='move'");
        assertEquals(2L, revision("org-b", "shop-a", "order-a"));
        assertEquals(1L, revision("org-b", "shop-b", "order-a"));
        db.update("UPDATE finance_records SET order_id='order-b' WHERE id='move'");
        assertEquals(2L, revision("org-b", "shop-b", "order-a"));
        assertEquals(1L, revision("org-b", "shop-b", "order-b"));
    }

    @Test
    void failedOwnerMoveRollsBackBothRegistrationSides() {
        insertSettlement("move-fail", "org", "shop", "old", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        assertThrows(DataIntegrityViolationException.class, () -> new TransactionTemplate(tx).execute(status -> {
            db.update("UPDATE finance_records SET order_id='new' WHERE id='move-fail'");
            db.update("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,currency,source,raw_json) VALUES('move-fail','org','u','shop','SHOPEE','FEE','duplicate','2026-10-02','PHP','API','{}')");
            return null;
        }));
        assertEquals(1L, revision("org", "shop", "old"));
        assertEquals(0, ownerRows("org", "shop", "new"));
        assertEquals("old", db.queryForObject("SELECT order_id FROM finance_records WHERE id='move-fail'", String.class));
    }

    @Test
    void sourceCollationControlsCaseAccentAndTrailingSpaceOwnerEquality() {
        assertEquals(1, db.queryForObject("SELECT _utf8mb4'Café' COLLATE utf8mb4_0900_ai_ci = _utf8mb4'cafe' COLLATE utf8mb4_0900_ai_ci", Integer.class));
        assertEquals(0, db.queryForObject("SELECT _utf8mb4'cafe' COLLATE utf8mb4_0900_ai_ci = _utf8mb4'cafe ' COLLATE utf8mb4_0900_ai_ci", Integer.class));
        insertSettlement("collation", "ORG", "SHOP", "Café", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        db.update("""
            UPDATE finance_records
            SET organization_id='org',shop_id='shop',order_id='cafe',record_date='2026-10-03'
            WHERE id='collation'
            """);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts", Integer.class));
        assertEquals(2L, revision("org", "shop", "cafe"),
            "A relevant change to a collation-equal owner increments exactly once");
        db.update("UPDATE finance_records SET order_id='cafe ',record_date='2026-10-04' WHERE id='collation'");
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts", Integer.class));
        assertEquals(3L, revision("org", "shop", "cafe"));
        assertEquals(1L, revision("org", "shop", "cafe "));
    }

    @Test
    void concurrentCrossOwnerWritersCommitAtomicallyWithoutPartialRegistration() throws Exception {
        insertSettlement("writer-a", "org", "shop", "a", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        insertSettlement("writer-b", "org", "shop", "b", "SHOPEE", "settlement", "SETTLED", "API",
            LocalDate.of(2026, 10, 2), "PHP");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            var left = executor.submit(() -> {
                start.await();
                updateAndCommit(first, "UPDATE finance_records SET order_id='b' WHERE id='writer-a'");
                return null;
            });
            var right = executor.submit(() -> {
                start.await();
                updateAndCommit(second, "UPDATE finance_records SET order_id='a' WHERE id='writer-b'");
                return null;
            });
            start.countDown();
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(3L, revision("org", "shop", "a"));
        assertEquals(3L, revision("org", "shop", "b"));
        assertEquals(Set.of("a", "b"), Set.copyOf(db.queryForList(
            "SELECT order_id FROM finance_records WHERE id IN ('writer-a','writer-b')", String.class)));
    }

    @Test
    void existingEventTriggersRemainByteStableAndActive() {
        assertFalse(existingTriggerDefinitionsBeforeInstall.isEmpty());
        assertEquals(existingTriggerDefinitionsBeforeInstall, existingTriggerDefinitionsAfterInstall);
        assertEquals(Set.of("fin_fsf_records_ai", "fin_fsf_records_au", "fin_fsf_records_ad"),
            triggerDefinitions(true).keySet());
    }

    @Test
    void factsTypeExposesTheApprovedRecordsConstructorAndEligibilityPredicate() throws Exception {
        var type = Class.forName("com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts");
        assertNotNull(type.getDeclaredConstructor(FinanceRepository.class));
        var eligible = type.getDeclaredMethod("eligible", String.class);
        eligible.setAccessible(true);
        assertEquals(FinanceCostReport.confirmed("candidate")
                + " AND (candidate.platform<>'TIKTOK_SHOP' OR candidate.category='ORDER')",
            eligible.invoke(null, "candidate"));
        var nested = java.util.Arrays.stream(type.getDeclaredClasses()).map(Class::getSimpleName)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(nested.containsAll(Set.of("Owner", "Registration", "Guard", "MaintenanceCompanyState",
            "AdvanceResult", "Lease", "Candidate", "Computed", "Tick", "Batch", "Period", "Mode",
            "ApprovedRange", "CandidateBudget", "Route", "Resolution")));
    }

    private List<String> indexColumns(String index) {
        return db.queryForList("""
            SELECT column_name FROM information_schema.statistics
            WHERE table_schema=DATABASE() AND table_name='finance_first_settlement_facts' AND index_name=?
            ORDER BY seq_in_index
            """, String.class, index);
    }

    private List<String> tableColumns(String table) {
        return db.queryForList("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name=?
            ORDER BY ordinal_position
            """, String.class, table);
    }
}
