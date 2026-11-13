package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only the disposable FINANCE_QA_JDBC runner may execute these trigger/transaction checks. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceQueryProjectionIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    FinanceQueryProjections projections;
    String shop;
    @BeforeAll void schema()throws Exception{
        fixture.migrate();
        fixture.migration("V150__finance_ad_spend_workspace.sql");
        fixture.db.execute("DROP INDEX idx_finance_page ON finance_records");
        var path=Path.of("src/main/resources/db/migration/V155__finance_query_projections.sql");
        if(Files.exists(path))fixture.migration(path.getFileName().toString());
    }
    @AfterAll void close(){fixture.closeDataSource();}
    @BeforeEach void setup(){
        fixture.setup();
        fixture.db.update("DELETE FROM finance_query_dirty_days");
        fixture.db.update("DELETE FROM finance_query_partitions");
        fixture.db.update("DELETE FROM finance_ad_spend_allocations");
        fixture.db.update("DELETE FROM finance_ad_spend_allocation_sets");
        fixture.db.update("DELETE FROM finance_ad_spend_imports");
        fixture.db.update("DELETE FROM finance_query_events");
        shop=fixture.connect(fixture.owner,false);
        projections=new FinanceQueryProjections(fixture.repo,fixture.service,new FinanceAdSpendService(fixture.repo,fixture.service));
    }
    @Test void financialWritesHaveADurableProjectionInvalidationQueue(){
        assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
            +"WHERE table_schema=DATABASE() AND table_name='finance_query_events'",Integer.class),
            "Financial changes need a transaction-owned queue before persistent facts can be trusted");
        assertEquals(11,fixture.db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_page'",Integer.class));
        assertEquals(16,fixture.db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_company_kind_date'",Integer.class));
    }
    @Test void eventBatchResolvesHistoricalBillOwnersAndSourcesWithoutPerOrderReads(){
        for(int i=0;i<80;i++){
            order("batch-"+i,"2026-05-02","9");
            fixture.repo.save(fixture.owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","batch-settlement-"+i,"batch-"+i,"batch-statement-"+i,"ORDER","SETTLED",LocalDate.of(2026,6,3),"PHP",decimal("8"),null,null,null,null,"API",Map.of()));
            fixture.db.update("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,currency,amount,source,raw_json) VALUES(?,?,?,?,?,'BILL',?,'2026-10-04','PHP',8,?,'{}')",
                hash("historical-bill-"+i),fixture.owner.organizationId(),"previous-owner",shop,"TIKTOK_SHOP","batch-statement-"+i,"LEGACY_FEED");
        }
        build("2026-05-01","2026-10-31","");
        for(int i=0;i<80;i++)for(int duplicate=0;duplicate<3;duplicate++)fixture.db.update("INSERT INTO finance_query_events(organization_id,event_scope,shop_id,order_id,source_table) VALUES(?,'ORDER',?,?,'finance_order_costs')",fixture.owner.organizationId(),shop,"batch-"+i);
        assertTrue(projections.find(fixture.owner,filter("2026-10-01","2026-10-31","")).isEmpty(),"The previous user's nonstandard-source bill date must fence the historical month");
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var counted=new org.springframework.jdbc.core.JdbcTemplate(fixture.dataSource){
            @Override public List<Map<String,Object>> queryForList(String sql,Object...args){reads.incrementAndGet();return super.queryForList(sql,args);}
            @Override public <T>List<T> queryForList(String sql,Class<T> type,Object...args){reads.incrementAndGet();return super.queryForList(sql,type,args);}
        };
        var repo=new FinanceRepository(counted,new tools.jackson.databind.ObjectMapper());
        var batched=new FinanceQueryProjections(repo,fixture.service,new FinanceAdSpendService(repo,fixture.service));
        assertEquals(240,batched.drainEvents());
        assertTrue(reads.get()<=12,"One shop's 80 orders must resolve in a bounded batch, actual SELECTs="+reads.get());
        assertEquals(List.of(java.sql.Date.valueOf("2026-05-02"),java.sql.Date.valueOf("2026-06-03"),java.sql.Date.valueOf("2026-10-04")),fixture.db.queryForList("SELECT DISTINCT record_date FROM finance_query_dirty_days ORDER BY record_date",java.sql.Date.class));
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class));
    }
    @Test void aLargeBurstMakesProgressInBoundedTransactionsWithoutDroppingTheTail(){
        projections.request(fixture.owner,filter("2026-10-01","2026-10-31",""));
        while(projections.drainEvents()>0){}
        var events=new ArrayList<Object[]>();
        for(int i=0;i<2001;i++)events.add(new Object[]{fixture.owner.organizationId()});
        fixture.db.batchUpdate("INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table) VALUES(?,'DAY','2026-10-05','finance_records')",events);
        assertEquals(2000,projections.drainEvents(),"A backlog must advance by 2,000 visible identities without exceeding the bounded transaction");
        assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class));
        assertEquals(1,projections.drainEvents());
        assertEquals(0,projections.drainEvents());
        assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_dirty_days WHERE record_date='2026-10-05'",Integer.class));
    }
    @Test void aLargeCommittedBacklogDefersRebuildUntilItsInvalidationsAreResolved(){
        fee("catch-up","2026-10-05","12","SHOPEE",shop);
        while(projections.drainEvents()>0){}
        projections.request(fixture.owner,filter("2026-10-01","2026-10-31",""));
        var events=new ArrayList<Object[]>();
        for(int i=0;i<2001;i++)events.add(new Object[]{fixture.owner.organizationId()});
        fixture.db.batchUpdate("INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table) VALUES(?,'DAY','2026-10-05','finance_records')",events);
        assertFalse(projections.buildNext(),"A large unresolved burst must not repeatedly rebuild facts that cannot yet be read");
        assertEquals(2001,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class));
        while(projections.drainEvents()>0){}
        assertTrue(projections.buildNext());
        var ready=projections.find(fixture.owner,filter("2026-10-01","2026-10-31","")).orElseThrow();
        assertEquals(0,decimal("12").compareTo(decimal(rows(ready.facts().get("buckets")).getFirst().get("amount"))));
    }
    @Test void anotherCompanyCanBuildWhileOneCompanyIsCatchingUp(){
        fixture.connect(fixture.other,false);while(projections.drainEvents()>0){}
        var month=filter("2026-10-01","2026-10-31","");
        projections.request(fixture.owner,month);projections.request(fixture.other,month);
        var events=new ArrayList<Object[]>();
        for(int i=0;i<2001;i++)events.add(new Object[]{fixture.owner.organizationId()});
        fixture.db.batchUpdate("INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table) VALUES(?,'DAY','2026-10-05','finance_records')",events);
        assertTrue(projections.buildNext());
        assertTrue(projections.find(fixture.other,month).isPresent(),"A different company's empty queue must remain eligible");
        assertEquals(0,fixture.db.queryForObject("SELECT built_revision FROM finance_query_partitions WHERE organization_id=?",Integer.class,fixture.owner.organizationId()));
        assertEquals(2001,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class));
    }
    @Test void oneNormalBatchRemainsEligibleButPendingEventsStillFenceItsFacts(){
        fee("normal-burst","2026-10-05","12","SHOPEE",shop);while(projections.drainEvents()>0){}
        var month=filter("2026-10-01","2026-10-31","");projections.request(fixture.owner,month);
        var events=new ArrayList<Object[]>();for(int i=0;i<2000;i++)events.add(new Object[]{fixture.owner.organizationId()});
        fixture.db.batchUpdate("INSERT INTO finance_query_events(organization_id,event_scope,record_date,source_table) VALUES(?,'DAY','2026-10-05','finance_records')",events);
        assertTrue(projections.buildNext(),"Exactly one batch must retain normal build scheduling");
        assertTrue(projections.find(fixture.owner,month).isEmpty(),"Scheduling never replaces relevant-event correctness checks");
        assertEquals(2000,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class));
        while(projections.drainEvents()>0){}assertTrue(projections.buildNext());
        assertTrue(projections.find(fixture.owner,month).isPresent());
    }
    @Test void fullAndPartialMonthsRetainCorrectionUnknownsAndExactDecimals(){
        fixture.db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        fixture.db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by)"
            +" VALUES(?,?,?,?,?,'TEST','month only','qa')",fixture.owner.organizationId(),shop,"corrected","bill-corrected",LocalDate.of(2026,10,1));
        order("corrected","2026-09-20","123456789012345.123456");
        settle("corrected","2026-09-22","10");
        order("ordinary","2026-09-21","70");
        settle("ordinary","2026-10-01","20");
        fixture.repo.save(fixture.owner,new Entry(shop,"TIKTOK_SHOP","FEE","unknown","","","PLATFORM_FEE","SETTLED",LocalDate.of(2026,10,9),"PHP",null,null,null,null,null,"API",Map.of()));
        build("2026-09-01","2026-11-30","");
        for(var range:List.of(List.of("2026-10-01","2026-10-31"),List.of("2026-10-01","2026-10-15"),
                List.of("2026-10-02","2026-10-31"),List.of("2026-09-15","2026-11-15"))){
            var f=filter(range.getFirst(),range.getLast(),"");
            var actual=projections.find(fixture.owner,f).orElseThrow();
            assertEquals(canonical(fixture.service.buildNativeDashboardFacts(fixture.owner,f,false)),canonical(actual.facts()));
        }
        var month=projections.find(fixture.owner,filter("2026-10-01","2026-10-31","")).orElseThrow();
        var orderRows=rows(month.facts().get("orders"));
        assertTrue(orderRows.stream().anyMatch(r->new BigDecimal("123456789012345.123456").equals(decimal(r.get("amount")))));
        assertTrue(rows(month.facts().get("buckets")).stream().anyMatch(r->r.containsKey("fx_date")&&r.get("fx_date")==null));
        assertTrue(rows(month.facts().get("buckets")).stream().anyMatch(r->"FEE".equals(r.get("kind"))&&r.get("amount")==null));
        var usd=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
        assertEquals(month.facts().get("reportingMonths"),projections.find(fixture.owner,usd).orElseThrow().facts().get("reportingMonths"),
            "Native dashboard coverage precedes the selected display currency, like the original fact builder");
    }
    @Test void rawCountsIncludeMarketingAndUnknownWhereOperatingFactsExcludeThem(){
        order("tagged","2026-10-02","9");settle("tagged","2026-10-03","8");
        fixture.db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,service_fee_cny,source_count) VALUES(?,?,?,0,1)",fixture.owner.organizationId(),shop,"tagged");
        build("2026-10-01","2026-10-31","");
        var snap=projections.find(fixture.owner,filter("2026-10-01","2026-10-31","")).orElseThrow();
        assertTrue(rows(snap.facts().get("buckets")).isEmpty());
        assertEquals(1,snap.rawCounts().stream().filter(r->"SETTLED".equals(r.get("basis"))&&"ORDER".equals(r.get("kind"))).mapToLong(r->number(r.get("records"))).sum());
    }
    @Test void rawCountFacetsMatchApiOnlyOrdersAndAdvertisingFeePages(){
        var day=LocalDate.of(2026,10,3);
        for(String source:List.of("API","IMPORT"))fixture.repo.save(fixture.owner,new Entry(shop,"TIKTOK_SHOP","ORDER","count-order-"+source,
            "count-order","","PAY_PER_SALE","COMPLETED",day,"PHP",decimal("10"),null,null,null,null,source,Map.of()));
        settle("count-order","2026-10-04","8");
        fixture.db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,service_fee_cny,source_count) VALUES(?,?,?,0,1)",fixture.owner.organizationId(),shop,"count-order");
        var fees=List.of(List.of("ordinary","API","platform_service_fee"),List.of("field-ad","API"," statement.GMV_MAX_AD_FEE_AMOUNT "),List.of("label-ad","IMPORT","成交广告费"));
        for(var fee:fees)fixture.repo.save(fixture.owner,new Entry(shop,"TIKTOK_SHOP","FEE",fee.get(0),"count-order","",fee.get(2),"SETTLED",day,"PHP",decimal("1"),null,null,null,null,fee.get(1),Map.of()));
        build("2026-10-01","2026-10-31","");var f=filter("2026-10-01","2026-10-31","");
        var facets=projections.find(fixture.owner,f).orElseThrow().rawCounts();
        long apiOrders=facets.stream().filter(r->"RECORD".equals(r.get("basis"))&&"ORDER".equals(r.get("kind"))&&"API".equals(r.get("source"))).mapToLong(r->number(r.get("records"))).sum();
        long importedOrders=facets.stream().filter(r->"RECORD".equals(r.get("basis"))&&"ORDER".equals(r.get("kind"))&&"IMPORT".equals(r.get("source"))).mapToLong(r->number(r.get("records"))).sum();
        assertEquals(1,apiOrders);assertEquals(1,importedOrders);
        assertEquals(number(fixture.repo.details(fixture.owner,f,"ORDER",1,50,"",true).get("total")),apiOrders);
        long advertisingFees=facets.stream().filter(r->"RECORD".equals(r.get("basis"))&&"FEE".equals(r.get("kind"))&&number(r.get("advertising_fee"))==1).mapToLong(r->number(r.get("records"))).sum();
        assertEquals(2,advertisingFees);assertEquals(number(fixture.repo.advertisingFeeDetails(fixture.owner,f,1,50,"").get("total")),advertisingFees);
        assertTrue(facets.stream().filter(r->!"FEE".equals(r.get("kind"))).allMatch(r->r.containsKey("advertising_fee")&&number(r.get("advertising_fee"))==0));
        assertTrue(facets.stream().filter(r->"SETTLED".equals(r.get("basis"))).allMatch(r->"API".equals(r.get("source"))));
        fixture.db.update("UPDATE finance_records SET category='pay_per_sale' WHERE external_id='ordinary'");assertTrue(projections.find(fixture.owner,f).isEmpty());drainAndBuild();
        long refreshed=projections.find(fixture.owner,f).orElseThrow().rawCounts().stream().filter(r->"RECORD".equals(r.get("basis"))&&"FEE".equals(r.get("kind"))&&number(r.get("advertising_fee"))==1).mapToLong(r->number(r.get("records"))).sum();
        assertEquals(3,refreshed);assertEquals(number(fixture.repo.advertisingFeeDetails(fixture.owner,f,1,50,"").get("total")),refreshed);
    }
    @Test void shopeeCpcSourceChangesInvalidateBothDatesWithoutDirtyingObservationOnlyUpdates(){
        build("2026-10-01","2026-11-30","");
        var october=filter("2026-10-01","2026-10-31","");var november=filter("2026-11-01","2026-11-30","");
        String key=hash("shopee-cpc-source");
        fixture.db.update("INSERT INTO finance_shopee_ad_daily(organization_id,external_shop_id,source_key,record_date,currency,amount,source_json) VALUES(?,'10001',?,'2026-10-03','PHP',9.123456,'{}')",fixture.owner.organizationId(),key);
        assertTrue(projections.find(fixture.owner,october).isEmpty(),"New Shopee daily source must fence the old empty projection");
        drainAndBuild();var ready=projections.find(fixture.owner,october).orElseThrow();
        var row=rows(ready.spendFacts().get("sources")).getFirst();assertEquals("API_SHOPEE_CPC",row.get("source"));assertEquals(decimal("9.123456"),decimal(row.get("amount")));
        fixture.db.update("UPDATE finance_shopee_ad_daily SET observed_at=DATE_ADD(observed_at,INTERVAL 1 SECOND) WHERE source_key=?",key);
        assertEquals(ready.revision(),projections.find(fixture.owner,october).orElseThrow().revision());
        fixture.db.update("UPDATE finance_shopee_ad_daily SET record_date='2026-11-02',amount=11 WHERE source_key=?",key);
        assertTrue(projections.find(fixture.owner,october).isEmpty());assertTrue(projections.find(fixture.owner,november).isEmpty());
        drainAndBuild();assertTrue(rows(projections.find(fixture.owner,october).orElseThrow().spendFacts().get("sources")).isEmpty());
        assertEquals(0,decimal("11").compareTo(decimal(rows(projections.find(fixture.owner,november).orElseThrow().spendFacts().get("sources")).getFirst().get("amount"))));
        fixture.db.update("DELETE FROM finance_shopee_ad_daily WHERE source_key=?",key);assertTrue(projections.find(fixture.owner,november).isEmpty());
        drainAndBuild();assertTrue(rows(projections.find(fixture.owner,november).orElseThrow().spendFacts().get("sources")).isEmpty());
    }
    @Test void directSqlMovesDirtyBothDatesAndRollbackDoesNotInvalidate(){
        fee("moving","2026-10-02","12","SHOPEE","UNASSIGNED");build("2026-10-01","2026-11-30","");
        var october=filter("2026-10-01","2026-10-31","");var before=projections.find(fixture.owner,october).orElseThrow();
        assertThrows(IllegalStateException.class,()->fixture.repo.transaction(()->{
            fixture.db.update("UPDATE finance_records SET amount=19 WHERE external_id='moving'");throw new IllegalStateException("rollback");
        }));
        assertEquals(before.revision(),projections.find(fixture.owner,october).orElseThrow().revision());
        fixture.db.update("UPDATE finance_records SET record_date='2026-11-04',amount=13 WHERE external_id='moving'");
        assertTrue(projections.find(fixture.owner,october).isEmpty());
        drainAndBuild();
        assertTrue(rows(projections.find(fixture.owner,october).orElseThrow().facts().get("buckets")).isEmpty());
        assertEquals(0,new BigDecimal("13").compareTo(decimal(rows(projections.find(fixture.owner,filter("2026-11-01","2026-11-30","")).orElseThrow().facts().get("buckets")).getFirst().get("amount"))));
    }
    @Test void insertedEarlierSettlementAndCostChangesInvalidateOriginalFirstMonth(){
        order("reassigned","2026-08-02","9");settle("reassigned","2026-10-06","8");build("2026-09-01","2026-10-31","");
        fixture.repo.save(fixture.owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","earlier","reassigned","another","ORDER","SETTLED",LocalDate.of(2026,9,6),"PHP",decimal("7"),null,null,null,null,"API",Map.of()));
        assertTrue(projections.find(fixture.owner,filter("2026-10-01","2026-10-31","")).isEmpty());
        drainAndBuild();
        assertTrue(rows(projections.find(fixture.owner,filter("2026-10-01","2026-10-31","")).orElseThrow().facts().get("orders")).isEmpty());
        assertEquals(1,rows(projections.find(fixture.owner,filter("2026-09-01","2026-09-30","")).orElseThrow().facts().get("orders")).size());
    }
    @Test void platformAndTenantVariantsDoNotMixUnassignedFacts(){
        fee("shopee","2026-10-02","12","SHOPEE","UNASSIGNED");fee("tiktok","2026-10-02","25","TIKTOK_SHOP","UNASSIGNED");
        build("2026-10-01","2026-10-31","");build("2026-10-01","2026-10-31","SHOPEE");build("2026-10-01","2026-10-31","TIKTOK_SHOP");
        assertEquals(0,new BigDecimal("12").compareTo(decimal(rows(projections.find(fixture.owner,filter("2026-10-01","2026-10-31","SHOPEE")).orElseThrow().facts().get("buckets")).getFirst().get("amount"))));
        assertTrue(projections.find(fixture.other,filter("2026-10-01","2026-10-31","")).isEmpty());
        fee("unrelated","2026-12-01","3","SHOPEE","UNASSIGNED");
        assertTrue(projections.find(fixture.owner,filter("2026-10-01","2026-10-31","")).isPresent(),"An unrelated pending day must not block a ready month");
    }
    @Test void configChangesAndLateCommitCannotPublishOldFactsAsFresh()throws Exception{
        fee("present","2026-10-02","12","SHOPEE","UNASSIGNED");build("2026-10-01","2026-10-31","");
        var f=filter("2026-10-01","2026-10-31","");
        String record=fixture.db.queryForObject("SELECT id FROM finance_records WHERE external_id='present'",String.class);
        try(var connection=fixture.dataSource.getConnection()){
            connection.setAutoCommit(false);
            try(var update=connection.prepareStatement("UPDATE finance_records SET amount=14 WHERE id=?")){update.setString(1,record);update.executeUpdate();}
            fee("later-event","2026-11-01","1","SHOPEE","UNASSIGNED");
            projections.drainEvents();connection.commit();
        }
        assertTrue(projections.find(fixture.owner,f).isEmpty(),"An earlier auto-increment ID that commits late must remain discoverable");
        drainAndBuild();assertTrue(projections.find(fixture.owner,f).isPresent());
        fixture.db.update("UPDATE finance_connections SET display_name='renamed' WHERE id=?",shop);
        assertTrue(projections.find(fixture.owner,f).isEmpty());
    }
    @Test void failedFirstDayRefreshKeepsBothPreviousPayloadsAndCanResume(){
        fee("first","2026-10-01","12","SHOPEE","UNASSIGNED");build("2026-10-01","2026-10-31","");
        var old=fixture.db.queryForMap("SELECT full_month_json,partial_first_day_json,built_revision FROM finance_query_partitions");
        fixture.db.update("UPDATE finance_records SET amount=15 WHERE external_id='first'");projections.drainEvents();
        var failing=new FinanceService(fixture.repo,null,null,fixture.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                if(filter.start().equals(filter.end()))throw new IllegalStateException("interrupted after full-mode day");
                return super.buildNativeDashboardFacts(scope,filter,live);
            }
        };
        new FinanceQueryProjections(fixture.repo,failing,new FinanceAdSpendService(fixture.repo,failing)).buildNext();
        var after=fixture.db.queryForMap("SELECT full_month_json,partial_first_day_json,built_revision FROM finance_query_partitions");
        assertEquals(old,after);assertTrue(projections.find(fixture.owner,filter("2026-10-01","2026-10-31","")).isEmpty());
        fixture.db.update("UPDATE finance_query_partitions SET next_attempt_at=UTC_TIMESTAMP(6)");drainAndBuild();
        assertEquals(0,new BigDecimal("15").compareTo(decimal(rows(projections.find(fixture.owner,filter("2026-10-01","2026-10-15","")).orElseThrow().facts().get("buckets")).getFirst().get("amount"))));
    }
    @Test void advertisingAssignmentsAndDateRefreshReuseOriginalAttribution(){
        fixture.db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        var ads=new FinanceAdSpendService(fixture.repo,fixture.service);
        var file=List.of(List.of("日期","推广系列ID","现金消耗","信用额度消耗","广告费用赠款消耗","金额","币种"),List.of("2026-10-02","9012345678901234","0","-6.60","-1.21","-7.81","USD"));
        var imported=FinanceAdSpendImport.parse(file,"TIKTOK_SHOP","123","共享账户",true);ads.saveImport(fixture.owner,"actor",imported,"report.xlsx");
        build("2026-10-01","2026-10-31","");
        var source=imported.getFirst();ads.assign(fixture.owner,"actor",text(source.get("source_key")),FinanceAdSpendReport.fingerprint(source),
            List.of(Map.of("shop_id",shop,"amount",decimal("7.81"),"cash_amount",decimal("0"),"credit_amount",decimal("6.60"),"grant_amount",decimal("1.21"))));
        var f=filter("2026-10-01","2026-10-31","");assertTrue(projections.find(fixture.owner,f).isEmpty());drainAndBuild();
        var facts=projections.find(fixture.owner,f).orElseThrow().spendFacts();
        var projected=FinanceAdSpendReport.build(rows(facts.get("sources")),rows(facts.get("assignments")),rows(facts.get("shops")),f,null,"","");
        assertEquals(canonical(Map.of("buckets",rows(ads.report(fixture.owner,f,false,"","","").get("items")))),canonical(Map.of("buckets",rows(projected.get("items")))));
        fixture.db.update("UPDATE finance_ad_spend_imports SET record_date='2026-10-03' WHERE organization_id=?",fixture.owner.organizationId());drainAndBuild();
        assertEquals("2026-10-03",text(rows(projections.find(fixture.owner,f).orElseThrow().spendFacts().get("sources")).getFirst().get("record_date")));
    }
    @Test void aChangedCostSnapshotDirtiesItsSettlementDayRatherThanItsOrderDay(){
        order("cost","2026-09-02","100");settle("cost","2026-10-06","90");
        String id=fixture.db.queryForObject("SELECT id FROM finance_records WHERE kind='ORDER' AND external_id='cost'",String.class);
        fixture.db.update("INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,amount_cny,known_amount_cny,source_hash,data_json) VALUES(?,?,?,?,?,'READY',10,10,'test','{}')",id,fixture.owner.organizationId(),shop,"cost",LocalDate.of(2026,9,2));
        fixture.db.update("DELETE FROM finance_order_cost_jobs WHERE order_record_id=?",id);build("2026-10-01","2026-10-31","");
        fixture.db.update("UPDATE finance_order_costs SET amount_cny=19,known_amount_cny=19 WHERE order_record_id=?",id);
        var f=filter("2026-10-01","2026-10-31","");assertTrue(projections.find(fixture.owner,f).isEmpty());drainAndBuild();
        assertEquals(0,decimal("19").compareTo(decimal(rows(projections.find(fixture.owner,f).orElseThrow().facts().get("orders")).getFirst().get("known_amount_cny"))));
    }
    @Test void aNewDirtyRevisionDuringBuildCannotBePublishedOrCleared(){
        fee("cas","2026-10-01","12","SHOPEE","UNASSIGNED");fee("unchanged","2026-10-02","7","SHOPEE","UNASSIGNED");build("2026-10-01","2026-10-31","");
        var old=fixture.db.queryForMap("SELECT full_month_json,built_revision FROM finance_query_partitions");
        String id=fixture.db.queryForObject("SELECT id FROM finance_records WHERE external_id='cas'",String.class);
        fixture.db.update("UPDATE finance_records SET amount=15 WHERE id=?",id);projections.drainEvents();
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        var racing=new FinanceService(fixture.repo,null,null,fixture.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                var result=super.buildNativeDashboardFacts(scope,filter,live);
                if(changed.compareAndSet(false,true)){
                    try{java.util.concurrent.CompletableFuture.runAsync(()->{fixture.db.update("UPDATE finance_records SET amount=20 WHERE id=?",id);projections.drainEvents();}).get(5,java.util.concurrent.TimeUnit.SECONDS);}
                    catch(Exception failure){throw new IllegalStateException(failure);}
                }
                return result;
            }
        };
        new FinanceQueryProjections(fixture.repo,racing,new FinanceAdSpendService(fixture.repo,racing)).buildNext();
        assertTrue(changed.get());assertEquals(old,fixture.db.queryForMap("SELECT full_month_json,built_revision FROM finance_query_partitions"));
        assertTrue(fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_dirty_days",Long.class)>0);
        drainAndBuild();var f=filter("2026-10-01","2026-10-31","");
        assertEquals(canonical(fixture.service.buildNativeDashboardFacts(fixture.owner,f,false)),canonical(projections.find(fixture.owner,f).orElseThrow().facts()));
    }
    @Test void groupMoveAndHardDeleteRemoveFactsFromTheOldScope(){
        String group=fixture.service.saveGroup(fixture.owner,null,"original");
        fixture.db.update("UPDATE finance_connections SET group_id=? WHERE id=?",group,shop);
        fee("owned","2026-10-05","9","SHOPEE",shop);build("2026-10-01","2026-10-31","");
        var all=filter("2026-10-01","2026-10-31","");var grouped=new Filter(all.start(),all.end(),"","","","day",group);
        assertEquals(1,rows(projections.find(fixture.owner,grouped).orElseThrow().facts().get("buckets")).size());
        fixture.db.update("UPDATE finance_connections SET group_id=NULL WHERE id=?",shop);assertTrue(projections.find(fixture.owner,grouped).isEmpty());drainAndBuild();
        assertTrue(rows(projections.find(fixture.owner,grouped).orElseThrow().facts().get("buckets")).isEmpty());
        fixture.db.update("DELETE FROM finance_records WHERE organization_id=?",fixture.owner.organizationId());assertTrue(projections.find(fixture.owner,all).isEmpty());drainAndBuild();
        assertTrue(rows(projections.find(fixture.owner,all).orElseThrow().facts().get("buckets")).isEmpty());
    }
    private void build(String start,String end,String platform){projections.request(fixture.owner,filter(start,end,platform));drainAndBuild();}
    private void drainAndBuild(){for(int i=0;i<20&&projections.drainEvents()>0;i++){}for(int i=0;i<20&&projections.buildNext();i++){} }
    private Filter filter(String start,String end,String platform){return new Filter(LocalDate.parse(start),LocalDate.parse(end),"",platform,"","day");}
    private void order(String order,String day,String amount){fixture.repo.save(fixture.owner,new Entry(shop,"TIKTOK_SHOP","ORDER",order,order,"","ORDER","COMPLETED",LocalDate.parse(day),"PHP",decimal(amount),null,null,null,null,"API",Map.of()));}
    private void settle(String order,String day,String amount){fixture.repo.save(fixture.owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","settled-"+order,order,"bill-"+order,"ORDER","SETTLED",LocalDate.parse(day),"PHP",decimal(amount),null,null,null,null,"API",Map.of()));}
    private void fee(String external,String day,String amount,String platform,String selectedShop){fixture.repo.save(fixture.owner,new Entry(selectedShop,platform,"FEE",external,"","","PLATFORM_FEE","SETTLED",LocalDate.parse(day),"PHP",decimal(amount),null,null,null,null,"API",Map.of()));}
    private Map<String,Object> canonical(Map<String,Object> facts){
        var result=new TreeMap<String,Object>();
        for(String name:List.of("buckets","orders","advertising"))result.put(name,rows(facts.get(name)).stream().map(row->{
            var normalized=new TreeMap<String,String>();row.forEach((key,value)->normalized.put(key,value instanceof Number?decimal(value).stripTrailingZeros().toPlainString():text(value)));return normalized.toString();
        }).sorted().toList());
        return result;
    }
}
