package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import static com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC", matches=".+")
class FinanceFirstSettlementInitializationIntegrationTest extends FinanceFirstSettlementTestSupport {
    private FinanceFirstSettlementInitialization initialization() { return new FinanceFirstSettlementInitialization(repo); }
    private Optional<Registration> registration() { return new FinanceFirstSettlementFacts(repo).registration(); }
    private Optional<Guard> guard(String org) { return new FinanceFirstSettlementFacts(repo).guard(org); }
    private Batch discover(FinanceFirstSettlementInitialization init, int limit) { return init.discoverCompanies(limit); }
    private Batch seed(FinanceFirstSettlementInitialization init, Guard guard, int limit) { return init.seed(guard,limit); }
    private Batch requeue(FinanceFirstSettlementInitialization init, Guard guard, int limit) { return init.requeue(guard,limit); }
    private boolean complete(FinanceFirstSettlementInitialization init, Guard guard) { return init.complete(guard); }
    private Optional<MaintenanceCompanyState> state(FinanceFirstSettlementInitialization init,String org) { return init.maintenanceState(org); }
    private AdvanceResult advance(FinanceFirstSettlementInitialization init,MaintenanceCompanyState expected,Registration target) { return init.advance(expected,target); }
    private void settlement(String id,String org,String shop,String order) {
        insertSettlement(id,org,shop,order,"SHOPEE","settlement","SETTLED","API",LocalDate.of(2020,1,1),"PHP");
    }
    private Map<String,Object> row(String org) { return db.queryForMap("SELECT * FROM finance_first_settlement_initialization WHERE organization_id=?",org); }
    private long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table,Long.class); }
    private void scan(FinanceFirstSettlementInitialization init,Guard guard) {
        boolean done=false;
        for(int i=0;i<30;i++) if(seed(init,guard,1).complete()) { done=true; break; }
        assertTrue(done,"bounded seed finishes");
        done=false;
        for(int i=0;i<30;i++) if(requeue(init,guard,1).complete()) { done=true; break; }
        assertTrue(done,"bounded requeue finishes");
    }
    private Registration publish(int version) {
        db.update("UPDATE finance_first_settlement_registration SET registration_version=?,installation_boundary=? WHERE id=1",version,"boundary-"+version);
        return registration().orElseThrow();
    }
    private Guard initial(String org,String shop) {
        settlement("initial-"+org,org,shop,"B");
        discover(initialization(),100);
        return guard(org).orElseThrow();
    }

    @Test void supportedDefinitionMatchesActualReviewedTriggers() {
        assertEquals(FinanceFirstSettlementFacts.SUPPORTED_DEFINITION_SHA,triggerDefinitionSha());
        assertTrue(registration().isPresent());
    }

    @Test void missingCompanyAndGenerationZeroReadsNeverCreateCoverage() {
        assertTrue(guard("new-company").isEmpty());
        assertEquals(0,count("finance_first_settlement_initialization"));
        db.update("INSERT INTO finance_first_settlement_initialization(organization_id) VALUES('new-company')");
        assertTrue(guard("new-company").isEmpty());
        assertEquals(0L,((Number)row("new-company").get("generation")).longValue());
    }

    @Test void proofRejectsMissingDisabledUnknownDefinitionSchemaAndBoundary() {
        initial("org","shop");
        for(String assignment:List.of("enabled=FALSE","definition_sha='unreviewed'","schema_version=99","registration_version=0","installation_boundary=''")) {
            db.update("UPDATE finance_first_settlement_registration SET "+assignment+" WHERE id=1");
            assertTrue(registration().isEmpty(),assignment);
            assertTrue(guard("org").isEmpty(),assignment);
            db.update("UPDATE finance_first_settlement_registration SET enabled=TRUE,definition_sha=?,schema_version=1,registration_version=1,installation_boundary='disposable-runner-install' WHERE id=1",triggerDefinitionSha());
        }
        db.update("DELETE FROM finance_first_settlement_registration");
        assertTrue(registration().isEmpty()); assertTrue(guard("org").isEmpty());
    }

    @Test void discoveryCyclesOverSourceAndConnectionsAndInitializesOnlyMissingOrZero() {
        var init=initialization();
        settlement("source","Z-source","s","o");
        groupedShop(new FinanceData.Scope("a-connection","u"),"discovery",null);
        db.update("DELETE FROM finance_first_settlement_registration");
        assertEquals(1,discover(init,1).visited());
        assertEquals(1,discover(init,1).visited());
        assertTrue(discover(init,1).complete());
        assertEquals(2,count("finance_first_settlement_initialization"));
        assertEquals(0L,((Number)row("Z-source").get("generation")).longValue());
        db.update("INSERT INTO finance_first_settlement_registration VALUES(1,1,1,?,'disposable-runner-install',TRUE,CURRENT_TIMESTAMP(6))",triggerDefinitionSha());
        discover(init,1); discover(init,1);
        assertEquals(1L,guard("Z-source").orElseThrow().generation());
        var old=guard("Z-source").orElseThrow(); scan(init,old); assertTrue(complete(init,old));
        publish(2);
        discover(init,100); discover(init,100);
        assertEquals(1,((Number)row("Z-source").get("registration_version")).intValue());
        settlement("later","0-new-before-cursor","s","o");
        discover(init,100); discover(init,100);
        var fresh=guard("0-new-before-cursor").orElseThrow();
        assertEquals(1,fresh.generation()); assertEquals(2,fresh.registrationVersion()); assertFalse(fresh.seedComplete());
    }

    @Test void seedOnlyInsertsMissingPreservingRevisionLeaseAndPublishedFact() {
        var guard=initial("org","shop"); var init=initialization();
        settlement("other","org","shop","C");
        db.update("DELETE FROM finance_first_settlement_facts WHERE order_id='C'");
        db.update("UPDATE finance_first_settlement_facts SET requested_revision=7,applied_revision=6,lease_token='lease',lease_until='2099-01-01',has_settlement=TRUE,first_record_id='first',first_record_date='2020-01-01',first_platform='SHOPEE',first_currency='PHP' WHERE order_id='B'");
        var before=db.queryForMap("SELECT * FROM finance_first_settlement_facts WHERE order_id='B'");
        assertFalse(complete(init,guard));
        assertEquals(1,seed(init,guard,1).visited());
        assertEquals(1,seed(initialization(),guard,1).visited());
        assertEquals(before,db.queryForMap("SELECT * FROM finance_first_settlement_facts WHERE order_id='B'"));
        assertEquals(1,revision("org","shop","C"));
        assertEquals(0,db.queryForObject("SELECT applied_revision FROM finance_first_settlement_facts WHERE order_id='C'",Integer.class));
        assertFalse(complete(init,guard));
    }

    @Test void seedCursorAndInsertedOwnersRollbackTogetherOnDatabaseFailure() {
        var guard=initial("org","shop"); var init=initialization();
        db.update("DELETE FROM finance_first_settlement_facts");
        db.execute("CREATE TRIGGER fsf_test_seed_fail BEFORE UPDATE ON finance_first_settlement_initialization FOR EACH ROW BEGIN IF NEW.order_after='B' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected cursor failure'; END IF; END");
        var before=row("org");
        try {
            assertThrows(DataAccessException.class,()->seed(init,guard,1));
            assertEquals(before,row("org")); assertEquals(0,count("finance_first_settlement_facts"));
        } finally { db.execute("DROP TRIGGER fsf_test_seed_fail"); }
        assertEquals(1,seed(initialization(),guard,1).visited());
        assertEquals(1,count("finance_first_settlement_facts"));
        assertEquals("B",row("org").get("order_after"));
    }

    @Test void completeRequiresBothCursorsButAllowsPendingAndEmptyCompanies() {
        var init=initialization(); groupedShop(owner,"empty",null); discover(init,100);
        var empty=guard("org").orElseThrow();
        assertFalse(complete(init,empty));
        assertTrue(seed(init,empty,1).complete()); assertFalse(complete(init,empty));
        assertTrue(requeue(init,empty,1).complete()); assertTrue(complete(init,empty));
        var occupied=initial("other","shop"); scan(init,occupied); assertTrue(complete(init,occupied));
        assertEquals(1,db.queryForObject("SELECT pending FROM finance_first_settlement_facts WHERE organization_id='other'",Integer.class));
        assertFalse(complete(init,new Guard("other",occupied.generation()+1,1,1,false)));
    }

    @Test void seedUsesFullEligibilityBeforeDistinctAndDatabaseCollation() {
        var init=initialization();
        insertSettlement("mixed-fee","org","shop","mixed","TIKTOK_SHOP","FEE","SETTLED","API",LocalDate.of(2026,1,1),"USD");
        insertSettlement("mixed-order","org","shop","mixed","TIKTOK_SHOP","ORDER","SETTLED","API",LocalDate.of(2020,1,1),"PHP");
        insertSettlement("fee-only","org","shop","fee-only","TIKTOK_SHOP","FEE","SETTLED","API",LocalDate.now(),"PHP");
        insertSettlement("cancelled","org","shop","cancelled","SHOPEE","settlement","CANCELLED","API",LocalDate.now(),"PHP");
        insertSettlement("manual","org","shop","manual","SHOPEE","settlement","SETTLED","MANUAL",LocalDate.now(),"PHP");
        settlement("blank","org","shop",""); settlement("space","org","shop"," ");
        settlement("accent","org","shop","éclair"); settlement("case","org","shop","ECLAIR");
        settlement("last","org","shop","z-last"); settlement("foreign","foreign","shop","foreign");
        db.update("DELETE FROM finance_first_settlement_facts"); discover(init,100);
        var guard=guard("org").orElseThrow(); scan(init,guard);
        assertEquals(List.of(" ","éclair","mixed","z-last"),db.queryForList("SELECT order_id FROM finance_first_settlement_facts WHERE organization_id='org' ORDER BY order_id",String.class));
        assertEquals(0,ownerRows("foreign","shop","foreign"));
    }

    @Test void postCursorInsertDeleteMoveAndNewShopStayRegistered() {
        var guard=initial("org","m-shop"); var init=initialization();
        assertEquals(1,seed(init,guard,1).visited());
        settlement("before","org","m-shop","A"); settlement("after","org","m-shop","Z");
        settlement("new-shop","org","a-shop","new");
        db.update("DELETE FROM finance_records WHERE id='initial-org'");
        db.update("UPDATE finance_records SET organization_id='moved',shop_id='new-shop',order_id='moved-order' WHERE id='after'");
        assertEquals(2,revision("org","m-shop","B"));
        scan(init,guard); assertTrue(complete(init,guard));
        for(String order:List.of("A","B","Z")) assertEquals(1,ownerRows("org","m-shop",order));
        assertEquals(3,revision("org","m-shop","B"));
        assertEquals(1,ownerRows("org","a-shop","new"));
        assertEquals(1,ownerRows("moved","new-shop","moved-order"));
        discover(init,100); discover(init,100); assertFalse(guard("moved").orElseThrow().seedComplete());
    }

    @Test void durableMaintenanceRecoveryAndReplayAdvanceExactlyOnce() {
        var old=initial("org","shop"); var init=initialization(); scan(init,old); assertTrue(complete(init,old));
        db.update("UPDATE finance_first_settlement_facts SET requested_revision=7,applied_revision=7,schema_version=1,applied_generation=1,applied_registration_version=1,lease_token='old-lease',lease_until='2099-01-01'");
        var fact=db.queryForMap("SELECT * FROM finance_first_settlement_facts");
        var target=publish(2); assertTrue(guard("org").isEmpty());
        var restarted=initialization(); var persisted=state(restarted,"org").orElseThrow();
        assertEquals(1,persisted.registrationVersion()); assertEquals(AdvanceResult.ADVANCED,advance(restarted,persisted,target));
        var newGuard=guard("org").orElseThrow(); assertEquals(2,newGuard.generation()); assertFalse(newGuard.seedComplete());
        assertEquals(fact,db.queryForMap("SELECT * FROM finance_first_settlement_facts"));
        assertFalse(complete(init,old)); assertEquals(0,seed(init,old,10).visited()); assertEquals(0,requeue(init,old,10).visited());
        seed(restarted,newGuard,1); var after=row("org");
        assertEquals(AdvanceResult.ALREADY_TARGET,advance(initialization(),persisted,target));
        assertEquals(after,row("org"));
        assertEquals(AdvanceResult.ALREADY_TARGET,advance(initialization(),state(initialization(),"org").orElseThrow(),target));
        publish(3); assertEquals(AdvanceResult.REJECTED,advance(initialization(),persisted,target));
        assertEquals(after,row("org"));
    }

    @Test void maintenanceRejectsWrongExpectedMissingCompanyAndForgedTarget() {
        initial("org","shop"); var init=initialization(); var old=state(init,"org").orElseThrow(); var target=publish(2);
        assertEquals(AdvanceResult.STALE,advance(init,new MaintenanceCompanyState("org",99,1,1),target));
        assertEquals(AdvanceResult.REJECTED,advance(init,new MaintenanceCompanyState("missing",1,1,1),target));
        assertEquals(AdvanceResult.REJECTED,advance(init,old,new Registration(1,2,target.definitionSha(),"forged",true)));
        db.update("UPDATE finance_first_settlement_initialization SET registration_version=3 WHERE organization_id='org'");
        assertEquals(AdvanceResult.REJECTED,advance(init,state(init,"org").orElseThrow(),target));
        assertEquals(1L,((Number)row("org").get("generation")).longValue());
    }

    @Test void requeueVisitsAllOwnersOnceAndOldAppliedModeCannotBecomeCurrentByEquality() {
        initial("org","a"); settlement("z","org","z","z"); var init=initialization();
        db.update("UPDATE finance_first_settlement_facts SET requested_revision=7,applied_revision=7,schema_version=1,applied_generation=1,applied_registration_version=1");
        var old=state(init,"org").orElseThrow(); var target=publish(2); assertEquals(AdvanceResult.ADVANCED,advance(init,old,target));
        var guard=guard("org").orElseThrow();
        assertEquals(1,requeue(init,guard,1).visited());
        assertEquals(8,revision("org","a","B")); assertEquals(7,revision("org","z","z"));
        assertEquals(1,db.queryForObject("SELECT applied_registration_version FROM finance_first_settlement_facts WHERE shop_id='z'",Integer.class));
        assertEquals(1,requeue(initialization(),guard,1).visited()); assertEquals(8,revision("org","z","z"));
        db.update("UPDATE finance_first_settlement_initialization SET requeue_shop_after=NULL,requeue_order_after=NULL,requeue_complete=FALSE");
        requeue(init,guard,10); assertEquals(8,revision("org","a","B")); assertEquals(8,revision("org","z","z"));
        scan(init,guard); assertTrue(complete(init,guard));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE pending=1 AND queued_generation=2",Integer.class));
    }

    @Test void requeueRollsBackRevisionAndCursorTogether() {
        var guard=initial("org","shop"); var init=initialization();
        db.execute("CREATE TRIGGER fsf_test_requeue_fail BEFORE UPDATE ON finance_first_settlement_initialization FOR EACH ROW BEGIN IF NEW.requeue_order_after='B' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected requeue failure'; END IF; END");
        try { assertThrows(DataAccessException.class,()->requeue(init,guard,1)); assertEquals(1,revision("org","shop","B")); assertNull(row("org").get("requeue_order_after")); }
        finally { db.execute("DROP TRIGGER fsf_test_requeue_fail"); }
        assertEquals(1,requeue(init,guard,1).visited()); assertEquals(2,revision("org","shop","B"));
    }

    @Test void totalsLongIdentitiesRemainCompleteAndNeverEnterSourceSizedFacts() {
        var guard=initial("org","shop"); var init=initialization(); String longOrder="L".repeat(255);
        db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES('org','active','shop',?,10,0,1)",longOrder);
        scan(init,guard); assertTrue(complete(init,guard));
        assertEquals(longOrder,db.queryForObject("SELECT order_id FROM finance_label_order_totals",String.class));
        assertEquals(0,ownerRows("org","shop",longOrder)); assertEquals(0,ownerRows("org","shop",longOrder.substring(0,128)));
        assertEquals(longOrder,new Owner("org","shop",longOrder).orderId());
    }

    @Test void schemaUpgradeRequiresSupportedProcessAndIncreasingRegistration() {
        var old=initial("org","shop"); var oldInit=initialization();
        var persisted=state(oldInit,"org").orElseThrow();
        db.update("UPDATE finance_first_settlement_registration SET schema_version=2 WHERE id=1");
        var nextFacts=new FinanceFirstSettlementFacts(repo,2);
        var nextInit=new FinanceFirstSettlementInitialization(repo,2);
        var sameRegistration=nextFacts.registration().orElseThrow();
        assertTrue(registration().isEmpty()); assertTrue(guard("org").isEmpty());
        assertEquals(AdvanceResult.REJECTED,advance(oldInit,persisted,sameRegistration));
        assertEquals(AdvanceResult.REJECTED,nextInit.advance(persisted,sameRegistration));
        db.update("UPDATE finance_first_settlement_registration SET registration_version=2,installation_boundary='schema-two' WHERE id=1");
        var target=nextFacts.registration().orElseThrow();
        assertEquals(AdvanceResult.ADVANCED,nextInit.advance(persisted,target));
        assertTrue(guard("org").isEmpty());
        var upgraded=nextFacts.guard("org").orElseThrow();
        assertEquals(2,upgraded.targetSchemaVersion()); assertEquals(2,upgraded.generation());
        assertFalse(complete(oldInit,old)); scan(nextInit,upgraded); assertTrue(complete(nextInit,upgraded));
        db.update("UPDATE finance_first_settlement_registration SET schema_version=1,registration_version=3,installation_boundary='attempted-downgrade' WHERE id=1");
        assertEquals(AdvanceResult.REJECTED,advance(oldInit,state(oldInit,"org").orElseThrow(),registration().orElseThrow()));
        assertEquals(2,((Number)row("org").get("target_schema_version")).intValue());
    }

    @Test void companyProofMismatchAndCurrentAppliedOwnerDoNotGainFalseCoverageOrRevision() {
        var current=initial("org","shop"); var init=initialization();
        for(String assignment:List.of("registration_definition_sha='different'","registration_boundary='different'","target_schema_version=2","registration_version=2")) {
            db.update("UPDATE finance_first_settlement_initialization SET "+assignment+" WHERE organization_id='org'");
            assertTrue(guard("org").isEmpty(),assignment);
            assertEquals(0,seed(init,current,1).visited()); assertEquals(0,requeue(init,current,1).visited()); assertFalse(complete(init,current));
            db.update("UPDATE finance_first_settlement_initialization SET registration_definition_sha=?,registration_boundary='disposable-runner-install',target_schema_version=1,registration_version=1 WHERE organization_id='org'",triggerDefinitionSha());
        }
        db.update("UPDATE finance_first_settlement_facts SET requested_revision=7,applied_revision=7,schema_version=1,applied_generation=1,applied_registration_version=1,lease_token='retained',lease_until='2099-01-01'");
        requeue(init,current,1);
        assertEquals(7,revision("org","shop","B"));
        assertEquals(1,db.queryForObject("SELECT queued_generation FROM finance_first_settlement_facts",Integer.class));
        assertEquals("retained",db.queryForObject("SELECT lease_token FROM finance_first_settlement_facts",String.class));
    }

    @Test void proofShareLockPrecedesCompanyLockAndConcurrentMaintenanceCannotCrossIt() throws Exception {
        var guard=initial("org","shop"); var pool=Executors.newSingleThreadExecutor();
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) { statement.executeQuery("SELECT id FROM finance_first_settlement_registration WHERE id=1 FOR UPDATE").close(); }
            var started=new java.util.concurrent.CountDownLatch(1);
            var future=pool.submit(()->{started.countDown();return seed(initialization(),guard,1);});
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=db.queryForObject("""
                        SELECT COUNT(*) FROM information_schema.processlist
                        WHERE DB=DATABASE() AND ID<>CONNECTION_ID()
                          AND INFO LIKE '%finance_first_settlement_registration%FOR SHARE%'
                        """,Integer.class)>0;
                    if(waiting) break;
                    Thread.sleep(10);
                }
                assertTrue(waiting,"seeder must actually be waiting for the singleton proof lock");
                // A separate current-read transaction can still lock company while the seeder waits on proof.
                try(var observer=dataSource.getConnection();var statement=observer.createStatement()) {
                    observer.setAutoCommit(false); statement.execute("SET innodb_lock_wait_timeout=2");
                    statement.executeQuery("SELECT organization_id FROM finance_first_settlement_initialization WHERE organization_id='org' FOR UPDATE NOWAIT").close(); observer.rollback();
                }
                try(var statement=connection.createStatement()) { statement.executeUpdate("UPDATE finance_first_settlement_registration SET enabled=FALSE WHERE id=1"); }
                connection.commit(); assertEquals(0,future.get(8,TimeUnit.SECONDS).visited());
                assertNull(row("org").get("current_shop"));
            } finally { connection.rollback(); future.cancel(true); }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
    }
}
