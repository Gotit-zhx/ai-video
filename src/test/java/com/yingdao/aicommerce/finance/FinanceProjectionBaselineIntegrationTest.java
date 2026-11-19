package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real repeatable-read races, with independently committed source writes and event draining. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceProjectionBaselineIntegrationTest {
    final FinanceConfigurationIntegrationTest f=new FinanceConfigurationIntegrationTest();
    final Filter month=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");
    FinanceQueryProjections projections;String shop;
    @BeforeAll void schema()throws Exception{f.migrate();}
    @AfterAll void close(){f.closeDataSource();}
    @BeforeEach void setup(){
        f.setup();shop=f.connect(f.owner,false);
        projections=new FinanceQueryProjections(f.repo,f.service,new FinanceAdSpendService(f.repo,f.service));
        fee("first","2026-10-01","11.123456");fee("other","2026-10-07","7");
        projections.request(f.owner,month);drain();
    }
    @Test void projectedSettlementCostsPreserveCurrencyDeduplicationAndCrossMonthMoves(){
        String first=settlement("old-php","order-cross","2026-10-02","PHP");
        settlement("old-thb","order-cross","2026-10-02","THB");
        settlement("duplicate","order-cross","2026-10-02","PHP");
        settlement("later","order-cross","2026-10-14","PHP");
        settlement("only-last","order-second","2026-10-29","PHP");
        var september=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"","","","day");
        projections.request(f.owner,september);
        projections.request(f.owner,new Filter(month.start(),month.end(),"","SHOPEE","","day"));
        drain();
        for(int i=0;i<3;i++)assertTrue(projections.buildNext());

        var report=new FinanceCostReport(f.repo);
        for(var filter:List.of(month,new Filter(month.start(),month.end(),shop,"SHOPEE","PHP","day"),
                new Filter(month.start(),month.end(),shop,"","THB","day"))){
            var expected=report.summary(f.owner,filter,"SETTLEMENT");
            var actual=projectedCosts(filter);
            assertEquals(expected,actual);
        }
        assertEquals(2,number(map(projectedCosts(month).get("total")).get("order_count")));

        // Replacing one of several ties changes the first day for every other
        // transaction on that order, including its other currency.
        f.db.update("UPDATE finance_records SET record_date='2026-09-30' WHERE id=?",first);
        for(var filter:List.of(month,september))
            assertEquals(report.summary(f.owner,filter,"SETTLEMENT"),projectedCosts(filter));
        assertEquals(1,number(map(projectedCosts(month).get("total")).get("order_count")));
        drain();
        assertEquals(report.summary(f.owner,month,"SETTLEMENT"),projectedCosts(month));
        assertEquals(report.summary(f.owner,september,"SETTLEMENT"),projectedCosts(september));
        assertTrue(projections.buildNext());assertTrue(projections.buildNext());
        assertEquals(report.summary(f.owner,month,"SETTLEMENT"),projectedCosts(month));
        assertEquals(report.summary(f.owner,september,"SETTLEMENT"),projectedCosts(september));
    }

    @Test void settlementDayOneRespectsConfirmedMonthAndLegacyBaselineBackfill(){
        f.db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        f.db.update("""
            INSERT INTO finance_reporting_months
              (organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by)
            VALUES(?,?, 'corrected-order','archive-statement','2026-10-01','TEST','fixture','qa')
            """,f.owner.organizationId(),shop);
        f.repo.save(f.owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","corrected","corrected-order","archive-statement",
            "ORDER","SETTLED",LocalDate.of(2026,9,28),"PHP",decimal("23.187613"),null,null,null,null,"API",Map.of()));
        f.repo.save(f.owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","ordinary","ordinary-order","ordinary-statement",
            "ORDER","SETTLED",month.start(),"PHP",decimal("3.187613"),null,null,null,null,"API",Map.of()));
        drain();assertTrue(projections.buildNext());
        var stableLedger=payload();
        f.db.update("UPDATE finance_query_partitions SET settlement_cost_revision=0");
        f.db.update("DELETE FROM finance_query_settlement_cost_days");
        assertTrue(f.repo.readSnapshot(()->projections.settlementCosts(f.owner,month)).isEmpty());

        projections.requestSettlementCosts(f.owner,month);assertTrue(projections.buildNext());

        assertEquals(stableLedger,payload(),"Legacy upgrade backfills only the missing settlement projection");
        var partial=new Filter(month.start(),month.start().plusDays(10),"","","","day");
        var costs=new FinanceCostReport(f.repo);
        assertEquals(costs.summary(f.owner,month,"SETTLEMENT"),projectedCosts(month));
        assertEquals(costs.summary(f.owner,partial,"SETTLEMENT"),projectedCosts(partial));
        assertEquals(2,number(map(projectedCosts(month).get("total")).get("order_count")));
        assertEquals(1,number(map(projectedCosts(partial).get("total")).get("order_count")));
        assertTrue(f.repo.readSnapshot(()->projections.settlementCosts(f.other,month)).isEmpty());
    }

    @Test void unsettledCostJobAndLabelGenerationChangesAreResolvedInTheReadingSnapshot(){
        var order=new Entry(shop,"SHOPEE","ORDER","historical-order","job-order","","","COMPLETED",
            LocalDate.of(2026,9,15),"PHP",decimal("10.123456"),null,null,null,null,"API",Map.of());
        f.repo.save(f.owner,order);
        String record=FinanceData.id(f.owner,order);
        settlement("cost-settlement","job-order","2026-10-07","PHP");
        f.db.update("""
            INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,
                status,amount_cny,known_amount_cny,line_count,matched_lines,source_hash,data_json)
            VALUES(?,?,?,'job-order','2026-09-15','READY',999999999999.123456,999999999999.123456,1,1,REPEAT('9',64),JSON_OBJECT())
            """,record,f.owner.organizationId(),shop);
        f.db.update("DELETE FROM finance_order_cost_jobs WHERE order_record_id=?",record);
        drain();assertTrue(projections.buildNext());

        var report=new FinanceCostReport(f.repo);
        assertEquals(report.summary(f.owner,month,"SETTLEMENT"),projectedCosts(month));
        assertEquals(decimal("999999999999.123456"),decimal(map(projectedCosts(month).get("total")).get("amount_cny")));

        f.db.update("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id,priority) VALUES(?,?,?,1)",
            record,f.owner.organizationId(),shop);
        assertEquals(report.summary(f.owner,month,"SETTLEMENT"),projectedCosts(month));
        assertNull(map(projectedCosts(month).get("total")).get("amount_cny"));
        drain();assertTrue(projections.buildNext());
        assertEquals(report.summary(f.owner,month,"SETTLEMENT"),projectedCosts(month));

        f.db.update("INSERT INTO finance_label_state(organization_id,active_generation) VALUES(?, 'new-generation')",f.owner.organizationId());
        assertTrue(f.repo.readSnapshot(()->projections.settlementCosts(f.owner,month)).isEmpty(),"A full label generation invalidation requires exact native fallback");
    }

    @Test void repairedLedgerMatchesNativeDuringAndAfterEventDrain(){
        assertTrue(projections.buildNext());
        change("other","18.123456");
        fee("new","2026-10-08","7.654321");
        assertHidden();
        for(boolean drained:List.of(false,true)){
            if(drained)drain();
            var read=projections.repairedLedger(f.owner,month,true).orElseThrow();
            assertTrue(read.refreshing());
            assertEquals(canonical(f.service.buildNativeDashboardFacts(f.owner,month,false)),canonical(read.facts()));
            long fees=read.rawCounts().stream().filter(row->"FEE".equals(row.get("kind")))
                .mapToLong(row->number(row.get("records"))).sum();
            assertEquals(3,fees);
        }
    }

    @Test void feeChangesIdentifyOnlyTheirShopAndDateAndLeaveSettlementDaysIntact(){
        settlement("first-settlement","settlement-order","2026-10-07","PHP");
        drain();assertTrue(projections.buildNext());
        var costs=f.db.queryForList("SELECT * FROM finance_query_settlement_cost_days ORDER BY record_date,shop_id,filter_currency");
        assertFalse(costs.isEmpty());
        change("other","20");drain();
        var dirty=f.db.queryForMap("SELECT shop_id,record_date,cost_revision FROM finance_query_dirty_shop_days");
        assertEquals(shop,dirty.get("shop_id"));
        assertEquals("2026-10-07",text(dirty.get("record_date")));
        assertEquals(0,number(dirty.get("cost_revision")));
        assertTrue(projections.buildNext());
        assertEquals(costs,f.db.queryForList("SELECT * FROM finance_query_settlement_cost_days ORDER BY record_date,shop_id,filter_currency"));
    }

    private Map<String,Object> projectedCosts(Filter filter){
        return f.repo.readSnapshot(()->projections.settlementCosts(f.owner,filter)).orElseThrow();
    }

    private String settlement(String id,String order,String day,String currency){
        var entry=new Entry(shop,"SHOPEE","SETTLEMENT",id,order,"","ORDER","SETTLED",
            LocalDate.parse(day),currency,decimal("16.124321"),null,null,null,null,"API",Map.of());
        f.repo.save(f.owner,entry);
        return FinanceData.id(f.owner,entry);
    }

    @Test void adjacentUpdatesPreserveTheCleanPartialFirstDayBody(){
        assertTrue(projections.buildNext());
        String first=f.db.queryForObject("SELECT partial_first_day_json FROM finance_query_partitions",String.class);
        change("other","8.123456");
        fee("adjacent","2026-10-08","13.123456");
        drain();
        assertEquals(2,dirtyCount());
        assertHidden();

        assertTrue(projections.buildNext());
        assertEquals(first,f.db.queryForObject("SELECT partial_first_day_json FROM finance_query_partitions",String.class));
        assertReadyAndNative();
        assertEquals(0,dirtyCount());
    }
    @Test void fullBuildSavesHiddenBaselineAndSnapshotStartWhenDayChangesDuringRead(){
        long revision=revision("requested_revision");var observed=new AtomicReference<Instant>();
        race(()->{observed.set(Instant.now());change("first","20");drain();});
        assertEquals(revision,revision("built_revision"),"Completed baseline must survive a newer DAY request");
        assertEquals(revision+1,revision("requested_revision"));assertEquals(0,revision("full_rebuild"));
        assertEquals(revision+1,dirtyRevision("2026-10-01"));assertHidden();
        Instant generated=f.db.queryForObject("SELECT generated_at FROM finance_query_partitions",java.sql.Timestamp.class).toInstant();
        assertFalse(generated.isAfter(observed.get()),"Freshness starts before reading facts, not when the long build finishes");
        assertEquals(0,decimal("11.123456").compareTo(storedAmount("2026-10-01")));
        assertTrue(projections.buildNext());assertReadyAndNative();assertEquals(0,dirtyCount());
    }
    @Test void oldestSavedSnapshotBuildsBeforeAnEarlierRequestWithARecentSnapshot(){
        var september=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"","","","day");
        projections.request(f.owner,september);assertTrue(projections.buildNext());assertTrue(projections.buildNext());
        f.db.update("UPDATE finance_query_partitions SET requested_revision=built_revision+1,full_rebuild=TRUE,generated_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),requested_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE),next_attempt_at=UTC_TIMESTAMP(6) WHERE partition_month='2026-10-01'");
        f.db.update("UPDATE finance_query_partitions SET requested_revision=built_revision+1,full_rebuild=TRUE,generated_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 DAY),requested_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),next_attempt_at=UTC_TIMESTAMP(6) WHERE partition_month='2026-09-01'");

        assertTrue(projections.buildNext());

        assertEquals(0,pending("2026-09-01"),"The stalest saved snapshot gets the next lease");
        assertEquals(1,pending("2026-10-01"),"An older request with a recent snapshot waits its fair turn");
    }
    @Test void hiddenBaselineMadeDirtyDuringItsBuildYieldsToAnOlderPendingSnapshot(){
        var september=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"","","","day");
        projections.request(f.owner,september);assertTrue(projections.buildNext());assertTrue(projections.buildNext());
        change("first","15");drain();
        race(()->{change("first","21");drain();});
        assertEquals(1,pending("2026-10-01"),"The concurrent DAY event keeps the completed baseline hidden");
        f.db.update("UPDATE finance_query_partitions SET requested_revision=built_revision+1,full_rebuild=TRUE,generated_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 DAY),requested_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),next_attempt_at=UTC_TIMESTAMP(6) WHERE partition_month='2026-09-01'");

        assertTrue(projections.buildNext());

        assertEquals(0,pending("2026-09-01"),"The older snapshot gets a turn after the hidden baseline advances");
        assertEquals(1,pending("2026-10-01"),"New arrivals cannot immediately reclaim the worker");
    }
    @Test void missingBaselinesPrecedeSavedSnapshotsAndUseTheFullPartitionIdentityForTies(){
        assertTrue(projections.buildNext());
        f.db.update("UPDATE finance_query_partitions SET requested_revision=built_revision+1,full_rebuild=TRUE,generated_at='2026-01-01 00:00:00',requested_at='2026-01-01 00:00:00',next_attempt_at=UTC_TIMESTAMP(6)");
        f.db.batchUpdate("INSERT INTO finance_query_partitions(organization_id,platform_filter,partition_month,requested_at,next_attempt_at) VALUES(?,?,?,'2026-11-13 01:00:00','2026-01-01 00:00:00')",List.of(
            new Object[]{"z-org","",LocalDate.of(2026,8,1)},
            new Object[]{"a-org","",LocalDate.of(2026,10,1)},
            new Object[]{"a-org","SHOPEE",LocalDate.of(2026,9,1)},
            new Object[]{"a-org","",LocalDate.of(2026,9,1)}));

        assertTrue(projections.buildNext());

        assertEquals(1,f.db.queryForObject("SELECT built_revision FROM finance_query_partitions WHERE organization_id='a-org' AND platform_filter='' AND partition_month='2026-09-01'",Long.class));
        assertEquals(3,f.db.queryForObject("SELECT COUNT(*) FROM finance_query_partitions WHERE built_revision=0",Integer.class));
        assertEquals(1,pending("2026-10-01"),"A saved snapshot remains behind every missing baseline");
    }
    @Test void repeatedSameDayKeepsItsNewerRevisionWhileOlderDirtyDaysAreCleared(){
        assertTrue(projections.buildNext());change("first","15");change("other","8");drain();
        long revision=revision("requested_revision");assertEquals(revision,dirtyRevision("2026-10-01"));
        race(()->{change("first","21");drain();});
        assertEquals(revision,revision("built_revision"));assertEquals(revision+1,dirtyRevision("2026-10-01"));
        assertEquals(1,dirtyCount(),"Only the day invalidated after R survives the successful save");assertHidden();
        assertEquals(0,decimal("15").compareTo(storedAmount("2026-10-01")));
        assertTrue(projections.buildNext());assertReadyAndNative();assertEquals(0,dirtyCount());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void newerAllInvalidationRetainsFullModeAfterFullOrIncrementalBaseline(boolean incremental){
        if(incremental){assertTrue(projections.buildNext());change("first","15");drain();}
        long revision=revision("requested_revision");
        race(()->{change("other","99");f.db.update("UPDATE finance_connections SET display_name='new full facts' WHERE id=?",shop);drain();});
        assertEquals(revision,revision("built_revision"));assertEquals(revision+1,revision("full_rebuild_revision"));
        assertEquals(1,revision("full_rebuild"));assertHidden();
        assertTrue(projections.buildNext());assertReadyAndNative();assertEquals(0,revision("full_rebuild"));
        assertEquals(0,decimal("99").compareTo(storedAmount("2026-10-07")));
    }
    @ParameterizedTest @ValueSource(strings={"stolen","expired","newer-built"})
    void staleWorkerCannotSaveOrClearDirtyDays(String reason){
        assertTrue(projections.buildNext());change("first","15");drain();
        var before=payload();var days=f.db.queryForList("SELECT * FROM finance_query_dirty_days");
        race(()->{
            switch(reason){
                case "stolen" -> f.db.update("UPDATE finance_query_partitions SET lease_token='replacement',lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)");
                case "expired" -> f.db.update("UPDATE finance_query_partitions SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
                case "newer-built" -> f.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1,built_revision=requested_revision");
                default -> throw new AssertionError(reason);
            }
        });
        assertEquals(before,payload());assertEquals(days,f.db.queryForList("SELECT * FROM finance_query_dirty_days"));
        if(reason.equals("stolen"))assertEquals("replacement",f.db.queryForObject("SELECT lease_token FROM finance_query_partitions",String.class));
        if(!reason.equals("newer-built"))assertHidden();
    }
    @Test void failedSaveTransactionRollsBackBaselineAndDirtyDayDeletion(){
        assertTrue(projections.buildNext());change("first","15");drain();
        var before=f.db.queryForMap("SELECT full_month_json,partial_first_day_json,spend_json,built_revision,generated_at FROM finance_query_partitions");
        var days=f.db.queryForList("SELECT * FROM finance_query_dirty_days");var deleted=new AtomicBoolean();
        var jdbc=new JdbcTemplate(f.dataSource){
            @Override public int update(String sql,Object...args){
                int result=super.update(sql,args);
                if(sql.startsWith("DELETE FROM finance_query_dirty_days")){deleted.set(true);throw new IllegalStateException("test save transaction rollback");}
                return result;
            }
        };
        var repo=new FinanceRepository(jdbc,new ObjectMapper());
        assertTrue(new FinanceQueryProjections(repo,f.service,new FinanceAdSpendService(repo,f.service)).buildNext());
        assertTrue(deleted.get());assertEquals(before,f.db.queryForMap("SELECT full_month_json,partial_first_day_json,spend_json,built_revision,generated_at FROM finance_query_partitions"));
        assertEquals(days,f.db.queryForList("SELECT * FROM finance_query_dirty_days"));assertHidden();
        f.db.update("UPDATE finance_query_partitions SET next_attempt_at=UTC_TIMESTAMP(6)");assertTrue(projections.buildNext());assertReadyAndNative();
    }
    @Test void lateCommitEventFencesAnOtherwiseCaughtUpBaselineAndEventuallyConverges()throws Exception{
        try(var connection=f.dataSource.getConnection()){
            connection.setAutoCommit(false);
            try(var update=connection.prepareStatement("UPDATE finance_records SET amount=31 WHERE external_id='first'")){update.executeUpdate();}
            race(()->{try{connection.commit();}catch(Exception failure){throw new IllegalStateException(failure);}});
        }
        assertEquals(revision("requested_revision"),revision("built_revision"));assertHidden();
        assertTrue(f.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class)>0);
        drain();assertTrue(projections.buildNext());assertReadyAndNative();
    }
    @Test void ledgerCanAppendAnEntireBatchWhileVisibleEventIdsAreLocked(){
        assertTrue(projections.buildNext());
        change("first","15");
        int visible=f.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class);
        var appended=new AtomicBoolean();
        var jdbc=new JdbcTemplate(f.dataSource){
            @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
                var rows=super.queryForList(sql,args);
                if(sql.startsWith("SELECT * FROM finance_query_events")&&appended.compareAndSet(false,true)){
                    try{
                        CompletableFuture.runAsync(()->f.repo.transaction(()->{
                            change("first","21");change("other","81");return null;
                        })).get(4,TimeUnit.SECONDS);
                    }catch(Exception failure){throw new AssertionError("Event claim blocked a ledger commit",failure);}
                }
                return rows;
            }
        };
        var repo=new FinanceRepository(jdbc,new ObjectMapper());

        assertEquals(visible,new FinanceQueryProjections(repo,f.service,new FinanceAdSpendService(repo,f.service)).drainEvents());
        assertTrue(appended.get());
        assertEquals(4,f.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class));
        assertHidden();drain();assertTrue(projections.buildNext());assertReadyAndNative();
    }

    @Test void eventDrainRollbackRestoresRevisionsAndDoesNotAcknowledgeVisibleIds(){
        assertTrue(projections.buildNext());change("first","15");
        var before=f.db.queryForList("SELECT * FROM finance_query_partitions");var events=f.db.queryForList("SELECT * FROM finance_query_events ORDER BY event_id");
        assertThrows(IllegalStateException.class,()->f.repo.transaction(()->{projections.drainEvents();throw new IllegalStateException("rollback drain");}));
        assertEquals(before,f.db.queryForList("SELECT * FROM finance_query_partitions"));assertEquals(events,f.db.queryForList("SELECT * FROM finance_query_events ORDER BY event_id"));
        assertEquals(0,dirtyCount());assertHidden();drain();assertTrue(projections.buildNext());assertReadyAndNative();
    }
    @Test void legacyZeroRevisionDirtyDayIsConsumedByTheNextSuccessfulLease(){
        assertTrue(projections.buildNext());
        f.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1");
        f.db.update("INSERT INTO finance_query_dirty_days(organization_id,platform_filter,partition_month,record_date) VALUES('org','','2026-10-01','2026-10-01')");
        assertEquals(0,dirtyRevision("2026-10-01"));assertTrue(projections.buildNext());assertEquals(0,dirtyCount());assertReadyAndNative();
    }
    private void race(Runnable mutation){
        var changed=new AtomicBoolean();
        var racing=new FinanceService(f.repo,null,null,f.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                var result=super.buildNativeDashboardFacts(scope,filter,live);
                if(changed.compareAndSet(false,true))try{CompletableFuture.runAsync(mutation).get(10,TimeUnit.SECONDS);}
                catch(Exception failure){throw new IllegalStateException(failure);}
                return result;
            }
        };
        assertTrue(new FinanceQueryProjections(f.repo,racing,new FinanceAdSpendService(f.repo,racing)).buildNext());assertTrue(changed.get());
    }
    private void assertHidden(){
        assertTrue(projections.find(f.owner,month).isEmpty());assertTrue(projections.findForQuery(f.owner,month,false).isEmpty());
        assertTrue(projections.findForQuery(f.owner,month,true).isEmpty());assertEquals("PENDING",projections.version(f.owner,month).get("status"));
    }
    private void assertReadyAndNative(){
        assertEquals("READY",projections.version(f.owner,month).get("status"));
        for(var filter:List.of(month,new Filter(month.start(),month.start().plusDays(14),"","","","day"),new Filter(month.start().plusDays(1),month.end(),"","","","day"))){
            var actual=projections.find(f.owner,filter).orElseThrow();
            assertEquals(canonical(f.service.buildNativeDashboardFacts(f.owner,filter,false)),canonical(actual.facts()));
            var spend=new FinanceAdSpendService(f.repo,f.service).buildNativeSpendFacts(f.owner,filter);
            assertEquals(rows(spend.get("sources")),rows(actual.spendFacts().get("sources")));
            long count=actual.rawCounts().stream().filter(row->"FEE".equals(row.get("kind"))&&"RECORD".equals(row.get("basis"))).mapToLong(row->number(row.get("records"))).sum();
            assertEquals(number(f.repo.details(f.owner,filter,"FEE",1,50,"",true).get("total")),count);
        }
    }
    private Map<String,Object> canonical(Map<String,Object> facts){
        var result=new TreeMap<String,Object>();
        for(String name:List.of("buckets","orders","advertising"))result.put(name,rows(facts.get(name)).stream().map(row->{
            var normalized=new TreeMap<String,String>();row.forEach((key,value)->normalized.put(key,value instanceof Number?decimal(value).stripTrailingZeros().toPlainString():text(value)));return normalized.toString();
        }).sorted().toList());return result;
    }
    private void fee(String id,String day,String amount){f.repo.save(f.owner,new Entry(shop,"SHOPEE","FEE",id,"","","PLATFORM_FEE","SETTLED",LocalDate.parse(day),"PHP",decimal(amount),null,null,null,null,"API",Map.of()));}
    private void change(String id,String amount){f.db.update("UPDATE finance_records SET amount=? WHERE external_id=?",decimal(amount),id);}
    private void drain(){while(projections.drainEvents()>0){}}
    private long revision(String column){var value=f.db.queryForMap("SELECT * FROM finance_query_partitions").get(column);return Boolean.TRUE.equals(value)?1:number(value);}
    private long dirtyRevision(String day){return f.db.queryForObject("SELECT requested_revision FROM finance_query_dirty_days WHERE record_date=?",Long.class,LocalDate.parse(day));}
    private int pending(String month){return f.db.queryForObject("SELECT built_revision<>requested_revision FROM finance_query_partitions WHERE organization_id=? AND platform_filter='' AND partition_month=?",Integer.class,f.owner.organizationId(),LocalDate.parse(month));}
    private int dirtyCount(){return f.db.queryForObject("SELECT COUNT(*) FROM finance_query_dirty_days",Integer.class);}
    private Map<String,Object> payload(){return f.db.queryForMap("SELECT full_month_json,partial_first_day_json,spend_json,generated_at FROM finance_query_partitions");}
    private java.math.BigDecimal storedAmount(String day){return rows(f.repo.read(f.db.queryForObject("SELECT full_month_json FROM finance_query_partitions",String.class)).get("buckets")).stream().filter(row->day.equals(text(row.get("record_date")))).map(row->decimal(row.get("amount"))).findFirst().orElseThrow();}
}
