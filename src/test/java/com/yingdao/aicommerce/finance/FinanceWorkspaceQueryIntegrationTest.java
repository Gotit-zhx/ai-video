package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceWorkspaceQueryIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    FinanceQueryProjections projections;FinanceWorkspaceQueryService queries;String shop;
    final Filter month=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");
    @BeforeAll void schema()throws Exception{
        fixture.migrate();
    }
    @AfterAll void close(){fixture.closeDataSource();}
    @BeforeEach void setup(){
        fixture.setup();for(String t:List.of("finance_query_dirty_days","finance_query_partitions","finance_query_events"))fixture.db.update("DELETE FROM "+t);
        shop=fixture.connect(fixture.owner,false);
        var spend=new FinanceAdSpendService(fixture.repo,fixture.service);
        projections=new FinanceQueryProjections(fixture.repo,fixture.service,spend);
        queries=new FinanceWorkspaceQueryService(fixture.repo,fixture.service,spend,projections,mock(FinanceAds.class),null);
    }
    FinanceWorkspaceQueryService.Selection select(String view,String kind,boolean all,int page,String numbers){return new FinanceWorkspaceQueryService.Selection(view,kind,all,false,"",page,1,numbers,"","");}
    void fee(String id,String currency){fixture.repo.save(fixture.owner,new Entry(shop,"SHOPEE","FEE",id,"","","pay_per_sale","SETTLED",LocalDate.of(2026,10,3),currency,decimal("12.123456"),null,null,null,null,"API",Map.of()));}
    void build(){projections.request(fixture.owner,month);while(projections.drainEvents()>0){}for(int i=0;i<12&&projections.buildNext();i++){}assertTrue(projections.find(fixture.owner,month).isPresent());}
    @Test void readyFeePageReusesOriginalFinancialAlgorithmAndExactCount(){
        for(int i=0;i<65;i++)fee("fee-"+i,"PHP");fee("usd","USD");build();
        var expected=fixture.service.buildDashboard(fixture.owner,month,false,"",false);
        var result=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));
        for(String key:List.of("totals","fees","trend","profit","advertising","filters"))assertEquals(fixture.repo.write(expected.get(key)),fixture.repo.write(result.get(key)),key);
        var page=map(result.get("records"));assertEquals(65,number(page.get("total")));assertEquals(50,rows(page.get("items")).size());
        assertEquals("PHP",map(result.get("filters")).get("currency"));assertEquals("READY",map(result.get("readModel")).get("status"));
        var next=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,2,""));
        assertEquals(15,rows(map(next.get("records")).get("items")).size());assertEquals(65,number(map(next.get("records")).get("total")));
        assertTrue(Collections.disjoint(rows(page.get("items")).stream().map(r->r.get("id")).toList(),rows(map(next.get("records")).get("items")).stream().map(r->r.get("id")).toList()));
    }
    @Test void dirtyProjectionFallsBackToCurrentDataAndNumberSearchHasItsOwnTotal(){
        fee("before","PHP");build();fee("after","PHP");
        var dirty=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));
        assertEquals(2,number(map(dirty.get("records")).get("total")));assertEquals("PENDING",map(dirty.get("readModel")).get("status"));
        build();var searched=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,"after"));
        assertEquals(1,number(map(searched.get("records")).get("total")));
    }
    @Test void pendingProjectionWaitsForThePublishedRevisionInsteadOfStartingTheNativeFallback(){
        fee("published","PHP");build();
        fixture.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1 WHERE organization_id=?",fixture.owner.organizationId());
        assertEquals("PENDING",projections.version(fixture.owner,month).get("status"));
        var published=spy(projections);var checks=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            var version=call.callRealMethod();
            if(checks.getAndIncrement()==0)fixture.db.update("UPDATE finance_query_partitions SET built_revision=requested_revision WHERE organization_id=?",fixture.owner.organizationId());
            return version;
        }).when(published).version(any(),any());
        var nativeFallback=new FinanceService(fixture.repo,mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class),fixture.auth,fixture.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                throw new AssertionError("A projection published inside the wait budget must avoid the authoritative native fallback");
            }
        };
        queries=new FinanceWorkspaceQueryService(fixture.repo,nativeFallback,new FinanceAdSpendService(fixture.repo,nativeFallback),published,mock(FinanceAds.class),null);

        var result=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));

        assertEquals("READY",map(result.get("readModel")).get("status"));
        assertEquals(1,number(map(result.get("records")).get("total")));
        assertEquals(1,checks.get());
        verify(published,atLeastOnce()).version(eq(fixture.owner),eq(month),anyLong());
    }
    @Test void pendingProjectionStopsAtTheTwoSecondMonotonicBudgetAndKeepsTenantScope(){
        fee("owner-only","PHP");build();
        projections.request(fixture.other,month);for(int i=0;i<12&&projections.buildNext();i++){}
        assertEquals("READY",projections.version(fixture.other,month).get("status"));
        fixture.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1 WHERE organization_id=?",fixture.owner.organizationId());
        var elapsed=new java.util.concurrent.atomic.AtomicLong();var waits=new java.util.concurrent.atomic.AtomicInteger();
        var spend=new FinanceAdSpendService(fixture.repo,fixture.service);
        var scoped=spy(projections);
        queries=new FinanceWorkspaceQueryService(fixture.repo,fixture.service,spend,scoped,mock(FinanceAds.class),null,elapsed::get,nanos->{
            assertEquals(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100),nanos);
            waits.incrementAndGet();elapsed.addAndGet(nanos);
        });

        var result=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));

        assertEquals(java.util.concurrent.TimeUnit.SECONDS.toNanos(2),elapsed.get());assertEquals(20,waits.get());
        assertEquals("PENDING",map(result.get("readModel")).get("status"));
        assertEquals(1,number(map(result.get("records")).get("total")));
        verify(scoped,times(1)).version(eq(fixture.owner),eq(month));
        verify(scoped,times(19)).version(eq(fixture.owner),eq(month),anyLong());
        verify(scoped,never()).version(eq(fixture.other),any());
        verify(scoped,never()).version(eq(fixture.other),any(),anyLong());
    }
    @Test void interruptedProjectionWaitRestoresTheFlagAndNeverRunsTheNativeFallback(){
        fee("interrupted","PHP");build();
        fixture.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1 WHERE organization_id=?",fixture.owner.organizationId());
        var nativeFallback=new FinanceService(fixture.repo,mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class),fixture.auth,fixture.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                throw new AssertionError("An interrupted wait must exit before the authoritative native fallback");
            }
        };
        var spend=new FinanceAdSpendService(fixture.repo,nativeFallback);
        queries=new FinanceWorkspaceQueryService(fixture.repo,nativeFallback,spend,projections,mock(FinanceAds.class),null,System::nanoTime,nanos->{
            throw new InterruptedException("request cancelled");
        });

        try{
            var failure=assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,"")));
            assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,failure.getStatus());
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
    @Test void interruptionObservedAfterMetadataPollingExitsBeforeTheNativeFallback(){
        fee("metadata-interrupted","PHP");build();
        fixture.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1 WHERE organization_id=?",fixture.owner.organizationId());
        var interrupted=spy(projections);
        doAnswer(call->{
            var version=call.callRealMethod();
            Thread.currentThread().interrupt();
            return version;
        }).when(interrupted).version(any(),any(),anyLong());
        var nativeFallback=new FinanceService(fixture.repo,mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class),fixture.auth,fixture.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                throw new AssertionError("A cancellation observed after metadata polling must exit before native fallback");
            }
        };
        var elapsed=new java.util.concurrent.atomic.AtomicLong();var spend=new FinanceAdSpendService(fixture.repo,nativeFallback);
        queries=new FinanceWorkspaceQueryService(fixture.repo,nativeFallback,spend,interrupted,mock(FinanceAds.class),null,elapsed::get,elapsed::addAndGet);

        try{
            var failure=assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,"")));
            assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,failure.getStatus());
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
    @Test void readyMetadataReturnedAfterTheDeadlineIsDiscardedBeforePayloadLoading(){
        fee("late-ready","PHP");build();var ready=projections.version(fixture.owner,month);
        fixture.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1 WHERE organization_id=?",fixture.owner.organizationId());
        var elapsed=new java.util.concurrent.atomic.AtomicLong();var timeout=new java.util.concurrent.atomic.AtomicLong();var late=spy(projections);
        doAnswer(call->{
            timeout.set(call.getArgument(2));
            elapsed.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));return ready;
        }).when(late).version(any(),any(),anyLong());
        doThrow(new AssertionError("Metadata returned after the wait deadline must not start projection payload loading"))
            .when(late).findForQuery(any(),any(),anyBoolean());
        var spend=new FinanceAdSpendService(fixture.repo,fixture.service);
        queries=new FinanceWorkspaceQueryService(fixture.repo,fixture.service,spend,late,mock(FinanceAds.class),null,elapsed::get,elapsed::addAndGet);

        var result=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));

        assertEquals("PENDING",map(result.get("readModel")).get("status"));
        assertEquals(1,number(map(result.get("records")).get("total")));
        assertEquals(1900,timeout.get());
    }
    @Test void logicalMetadataDeadlineUsesTheOriginalAuthoritativeFallback(){
        fee("logical-deadline","PHP");build();
        fixture.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1 WHERE organization_id=?",fixture.owner.organizationId());
        var expired=spy(projections);
        doThrow(new FinanceQueryProjections.PollDeadlineElapsed())
            .when(expired).version(any(),any(),anyLong());
        var elapsed=new java.util.concurrent.atomic.AtomicLong();var spend=new FinanceAdSpendService(fixture.repo,fixture.service);
        queries=new FinanceWorkspaceQueryService(fixture.repo,fixture.service,spend,expired,mock(FinanceAds.class),null,elapsed::get,elapsed::addAndGet);

        var result=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));

        assertEquals("PENDING",map(result.get("readModel")).get("status"));
        assertEquals(1,number(map(result.get("records")).get("total")));
    }
    @Test void interruptionWinsWhenTheLogicalMetadataDeadlineSignalArrives(){
        fee("deadline-interrupted","PHP");build();
        fixture.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1 WHERE organization_id=?",fixture.owner.organizationId());
        var expired=spy(projections);
        doAnswer(call->{
            Thread.currentThread().interrupt();
            throw new FinanceQueryProjections.PollDeadlineElapsed();
        }).when(expired).version(any(),any(),anyLong());
        var nativeFallback=new FinanceService(fixture.repo,mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class),fixture.auth,fixture.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                throw new AssertionError("Interrupted logical deadline must exit before native fallback");
            }
        };
        var elapsed=new java.util.concurrent.atomic.AtomicLong();var spend=new FinanceAdSpendService(fixture.repo,nativeFallback);
        queries=new FinanceWorkspaceQueryService(fixture.repo,nativeFallback,spend,expired,mock(FinanceAds.class),null,elapsed::get,elapsed::addAndGet);

        try{
            var failure=assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,"")));
            assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,failure.getStatus());
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
    @Test void rawOrdersRetainApiOnlyAndCrossDateSearch(){
        var past=LocalDate.of(2026,9,2);
        for(String source:List.of("API","IMPORT"))fixture.repo.save(fixture.owner,new Entry(shop,"SHOPEE","ORDER","order-"+source,"exact-order","","","COMPLETED",past,"PHP",decimal("10"),null,null,null,null,source,Map.of()));
        build();var result=queries.query(fixture.owner,fixture.owner,month,select("orders","ORDER",true,1,"exact-order"));
        assertEquals(1,number(map(result.get("records")).get("total")));assertEquals("ALL",result.get("orderScope"));
        assertEquals("",map(result.get("filters")).get("currency"));
    }
    @Test void personalFieldPreferencesAndTenantScopeAreNotShared(){
        fee("private","PHP");build();
        var result=queries.query(fixture.other,fixture.other,month,select("fees","FEE",false,1,""));
        assertEquals(0,number(map(result.get("records")).get("total")));assertTrue(rows(result.get("shops")).isEmpty());
        assertNotNull(map(result.get("records")).get("fieldSettings"));
    }
    @Test void advertisingPrimaryDoesNotBuildUnrelatedDashboardOrFetchRawLedgerPages(){
        fee("ledger","PHP");build();
        var guarded=spy(new FinanceService(fixture.repo,mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class),fixture.auth,fixture.fx));doThrow(new AssertionError("Primary ad spend must not build dashboard")).when(guarded).buildDashboard(any(),any(),anyBoolean(),any(),anyBoolean());
        var repo=spy(fixture.repo);doThrow(new AssertionError("Primary ad spend must not fetch raw ledger pages")).when(repo).details(any(),any(),any(),anyInt(),anyInt(),any(),anyBoolean());
        queries=new FinanceWorkspaceQueryService(repo,guarded,new FinanceAdSpendService(repo,guarded),projections,mock(FinanceAds.class),null);
        var result=queries.query(fixture.owner,fixture.owner,month,select("ads","AD",false,1,""));
        assertNotNull(result.get("adSpend"));assertFalse(result.containsKey("records"));assertFalse(result.containsKey("profit"));assertEquals("",map(result.get("filters")).get("currency"));
    }
    @Test void advertisingColdPageDoesNotReadUnrelatedDashboardPayloads(){
        fee("unrelated-ledger","PHP");
        fixture.db.update("INSERT INTO finance_shopee_ad_daily(organization_id,external_shop_id,source_key,record_date,currency,amount,source_json) VALUES(?,'10001',?,'2026-10-03','PHP',9,'{}')",fixture.owner.organizationId(),hash("cold-ad-source"));
        build();
        var result=budgetedWorkspace(false,true).query(fixture.owner,fixture.owner,month,select("ads","AD",false,1,""));
        assertEquals("READY",map(result.get("readModel")).get("status"));
        var items=rows(map(result.get("adSpend")).get("items"));assertEquals(1,items.size());
        assertEquals(0,decimal("9").compareTo(decimal(items.getFirst().get("amount"))));
    }
    @Test void financialColdPageDoesNotReadTheIndependentAdvertisingPayload(){
        fee("cold-financial","PHP");build();
        var result=budgetedWorkspace(true,false).query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));
        assertEquals("READY",map(result.get("readModel")).get("status"));
        assertEquals(1,number(map(result.get("records")).get("total")));
        assertEquals(0,decimal("12.123456").compareTo(decimal(rows(map(result.get("records")).get("items")).getFirst().get("amount"))));
    }
    private FinanceWorkspaceQueryService budgetedWorkspace(boolean dashboard,boolean advertising){
        var db=new org.springframework.jdbc.core.JdbcTemplate(fixture.dataSource){
            @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
                var result=super.queryForList(sql,args);
                for(var row:result){
                    if(!dashboard&&(row.get("full_month_json")!=null||row.get("partial_first_day_json")!=null))throw new AssertionError("An advertising page must not transport or decode unrelated dashboard facts");
                    if(!advertising&&row.get("spend_json")!=null)throw new AssertionError("A financial page must not transport or decode unrelated independent advertising facts");
                }
                return result;
            }
        };
        var repo=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper());var spend=new FinanceAdSpendService(repo,fixture.service);
        return new FinanceWorkspaceQueryService(repo,fixture.service,spend,new FinanceQueryProjections(repo,fixture.service,spend),mock(FinanceAds.class),null);
    }
    @Test void reconciliationResolvesBillCurrenciesAndRetainsFirstSettlementCostBasis(){
        fee("php-fee","PHP");
        fixture.repo.save(fixture.owner,new Entry(shop,"SHOPEE","BILL","usd-bill","","usd-bill","","SETTLED",LocalDate.of(2026,10,12),"USD",decimal("20"),null,null,null,null,"API",Map.of()));
        build();
        var expected=fixture.service.reconciliationContext(fixture.owner,month,false,"");
        var result=queries.query(fixture.owner,fixture.owner,month,select("reconciliation","",false,1,""));
        assertEquals(map(expected.get("filters")).get("currency"),map(result.get("filters")).get("currency"));
        assertEquals(expected.get("currencies"),result.get("currencies"));
        assertEquals(1,number(map(result.get("records")).get("total")));
        for(var entry:map(map(expected.get("productCosts")).get("total")).entrySet())assertEquals(entry.getValue(),map(map(result.get("productCosts")).get("total")).get(entry.getKey()),entry.getKey());
    }
    @Test void lightweightStatusDetectsNewPublishedRevisionAndNeverReadsFinancialPayloads(){
        fee("first","PHP");build();var first=queries.status(fixture.owner,month);
        var ready=projections.find(fixture.owner,month).orElseThrow();
        assertEquals("READY",first.get("status"));assertEquals(ready.revision(),first.get("revision"));
        fee("second","PHP");assertEquals("PENDING",queries.status(fixture.owner,month).get("status"));
        build();var next=queries.status(fixture.owner,month);assertEquals("READY",next.get("status"));assertNotEquals(first.get("revision"),next.get("revision"));
        assertFalse(next.containsKey("facts"));assertFalse(next.containsKey("items"));
    }
    @Test void realJdbcMetadataTimeoutReturns503WithoutStartingTheNativeFallback()throws Exception{
        projections.request(fixture.owner,month);
        try(var connection=fixture.dataSource.getConnection();var lock=connection.createStatement()){
            connection.setAutoCommit(false);
            lock.execute("LOCK TABLES finance_query_partitions WRITE, finance_query_events WRITE");
            var bounded=spy(projections);
            doReturn(Map.of("status","PENDING","revision","")).when(bounded).version(any(),any());
            doNothing().when(bounded).request(any(),any());
            var nativeFallback=new FinanceService(fixture.repo,mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class),fixture.auth,fixture.fx){
                @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                    throw new AssertionError("A real JDBC metadata timeout must not start native fallback");
                }
            };
            var elapsedBudget=new java.util.concurrent.atomic.AtomicLong();var spend=new FinanceAdSpendService(fixture.repo,nativeFallback);
            queries=new FinanceWorkspaceQueryService(fixture.repo,nativeFallback,spend,bounded,mock(FinanceAds.class),null,elapsedBudget::get,
                nanos->elapsedBudget.set(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1850)));
            long started=System.nanoTime();
            try{
                var failure=assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                    ()->queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,"")));
                assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,failure.getStatus());
                assertInstanceOf(org.springframework.dao.QueryTimeoutException.class,failure.getCause());
                long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                System.out.println("boundedMetadataCancelMillis="+elapsed);
                assertTrue(elapsed<1000,"Blocked metadata exceeded its 150 ms JDBC deadline: "+elapsed+" ms");
            }finally{lock.execute("UNLOCK TABLES");}
        }
    }
    @Test void aWriteAfterMetadataSelectionCannotLabelOldFactsAsCurrent(){
        fee("before-metadata","PHP");build();
        var racing=spy(projections);
        doAnswer(call->{var version=call.callRealMethod();fee("during-metadata","PHP");return version;}).when(racing).version(any(),any());
        queries=new FinanceWorkspaceQueryService(fixture.repo,fixture.service,new FinanceAdSpendService(fixture.repo,fixture.service),racing,mock(FinanceAds.class),null);
        var result=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));
        assertEquals(2,number(map(result.get("records")).get("total")));
        assertEquals("PENDING",map(result.get("readModel")).get("status"));
    }
    @Test void metadataCannotSelectAnIncompletePublishedPayload(){
        fee("missing-payload","PHP");build();
        fixture.db.update("UPDATE finance_query_partitions SET spend_json=NULL");
        assertEquals("PENDING",projections.version(fixture.owner,month).get("status"));
        var result=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));
        assertEquals("PENDING",map(result.get("readModel")).get("status"));
        assertEquals(1,number(map(result.get("records")).get("total")));
    }

    @Test void oneResponseUsesOneFrozenFxQuoteForSummaryAndRows(){
        fee("fx","PHP");build();
        var first=new FinanceFx.Quote("same-id",Instant.EPOCH,Map.of("PHP",decimal("0.1")));
        var later=new FinanceFx.Quote("same-id",Instant.EPOCH,Map.of("PHP",decimal("0.2")));
        doReturn(first,later).when(fixture.fx).historical(any(),any(LocalDate.class),any(LocalDate.class));
        var selected=new FinanceWorkspaceQueryService.Selection("fees","FEE",false,true,"",1,1,"","","");
        var result=queries.query(fixture.owner,fixture.owner,month,selected);
        var total=rows(result.get("totals")).stream().filter(row->"FEE".equals(row.get("kind"))).findFirst().orElseThrow();
        var item=rows(map(result.get("records")).get("items")).getFirst();
        assertEquals(decimal(total.get("amount")),decimal(item.get("cny_amount")),"Background rate completion cannot change coverage halfway through one response");
    }

    @Test void actualRedisReusesPageAmountsWhilePersonalPreferencesRemainCurrent(){
        fee("redis","PHP");build();
        var factory=redisFactory();
        factory.afterPropertiesSet();factory.start();
        var template=new org.springframework.data.redis.core.StringRedisTemplate(factory);template.afterPropertiesSet();
        var shared=new FinanceReadCache(template,new tools.jackson.databind.ObjectMapper());
        try{
            shared.invalidate(companyScope(fixture.owner.organizationId()));
            var guarded=spy(fixture.repo);
            var guardedProjections=spy(projections);
            queries=new FinanceWorkspaceQueryService(guarded,fixture.service,new FinanceAdSpendService(fixture.repo,fixture.service),guardedProjections,mock(FinanceAds.class),shared,System::nanoTime,nanos->{
                throw new AssertionError("An already READY Redis page must not enter the PENDING wait path");
            });
            String heartbeatKey="heyi:finance:calculation:{"+fixture.owner.organizationId()+"}:settlements";
            template.opsForValue().set(heartbeatKey,new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("heartbeat",System.currentTimeMillis(),"status","RUNNING","shops",List.of(Map.of("shopId",shop,"complete",false,"records",1,"total",5)))));
            var first=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));
            assertEquals("RUNNING",map(first.get("settlementCalculation")).get("status"));
            template.opsForValue().set(heartbeatKey,new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("heartbeat",System.currentTimeMillis(),"status","COMPLETE","shops",List.of(Map.of("shopId",shop,"complete",true,"records",5,"total",5)))));
            doThrow(new AssertionError("A warm identical Redis page must not reread raw rows")).when(guarded).details(any(),any(),eq("FEE"),anyInt(),anyInt(),any(),anyBoolean());
            doThrow(new AssertionError("A warm Redis page must select its revision without reading or decoding financial payloads")).when(guardedProjections).find(any(),any());
            doThrow(new AssertionError("A warm Redis page must not read either purpose-specific financial payload")).when(guardedProjections).findForQuery(any(),any(),anyBoolean());
            new FinanceFields(fixture.repo).save(fixture.owner,"FEE",List.of("external_id"),List.of("external_id"));
            var cached=queries.query(fixture.owner,fixture.owner,month,select("fees","FEE",false,1,""));
            assertEquals("COMPLETE",map(cached.get("settlementCalculation")).get("status"));template.delete(heartbeatKey);
            var item=rows(map(cached.get("records")).get("items")).getFirst();
            assertEquals(decimal("12.123456"),decimal(item.get("amount")));assertNotNull(cached.get("cache"));
            assertEquals(map(first.get("cache")).get("generatedAt"),map(cached.get("cache")).get("generatedAt"));
            assertTrue(((List<?>)map(map(cached.get("records")).get("fieldSettings")).get("visible")).contains("external_id"));
            assertFalse(((List<?>)map(map(cached.get("records")).get("fieldSettings")).get("visible")).contains("amount"));
        }finally{shared.stop();factory.destroy();}
    }

    @Test void spendingPaginationReusesThePeriodReportWithinItsPublishedRevision(){
        String group=new FinanceGroups(fixture.repo).save(fixture.owner,null,"Redis group");
        fixture.groupedShop(fixture.owner,"10002",group);
        for(String external:List.of("10001","10002"))for(int day=1;day<=31;day++)fixture.db.update("INSERT INTO finance_shopee_ad_daily(organization_id,external_shop_id,source_key,record_date,currency,amount,source_json) VALUES(?,?,?,?,'PHP',1,'{}')",
            fixture.owner.organizationId(),external,hash(external+"/"+day),LocalDate.of(2026,10,day));
        build();
        var factory=redisFactory();factory.afterPropertiesSet();factory.start();
        var template=new org.springframework.data.redis.core.StringRedisTemplate(factory);template.afterPropertiesSet();
        var shared=new FinanceReadCache(template,new tools.jackson.databind.ObjectMapper());
        try{
            shared.invalidate(companyScope(fixture.owner.organizationId()));
            var source=spy(new FinanceAdSpendService(fixture.repo,fixture.service));
            queries=new FinanceWorkspaceQueryService(fixture.repo,fixture.service,source,projections,mock(FinanceAds.class),shared);
            var first=queries.query(fixture.owner,fixture.owner,month,new FinanceWorkspaceQueryService.Selection("ads","AD",false,false,"",1,1,"","",""));
            var second=queries.query(fixture.owner,fixture.owner,month,new FinanceWorkspaceQueryService.Selection("ads","FEE",false,false,"",2,2,"","",""));
            assertEquals(62,number(map(first.get("adSpend")).get("total")));assertEquals(50,rows(map(first.get("adSpend")).get("items")).size());
            assertEquals(12,rows(map(second.get("adSpend")).get("items")).size());
            assertEquals(map(first.get("cache")).get("generatedAt"),map(second.get("cache")).get("generatedAt"),"Paging must retain the period calculation's original generation time");
            verify(source,times(1)).reportFromNative(any(),any(),any(),anyBoolean(),any(),any(),any());
        }finally{shared.stop();factory.destroy();}
    }

    @Test void operationalLifecycleChangesWithoutRelabellingTheFinancialRevision(){
        fee("stable","PHP");build();var version=queries.status(fixture.owner,month);
        fixture.db.update("INSERT INTO finance_cost_rebuilds(organization_id,shop_id,status) VALUES(?,?,'READY')",fixture.owner.organizationId(),shop);
        var running=queries.status(fixture.owner,month);assertEquals(version.get("revision"),running.get("revision"));
        assertEquals(true,map(map(running.get("operations")).get("costState")).get("rebuilding"));
        fixture.db.update("UPDATE finance_cost_rebuilds SET status='DONE' WHERE organization_id=? AND shop_id=?",fixture.owner.organizationId(),shop);
        var idle=queries.status(fixture.owner,month);assertEquals(version.get("revision"),idle.get("revision"));
        assertEquals(false,map(map(idle.get("operations")).get("costState")).get("rebuilding"));
        assertNotEquals(map(running.get("operations")).get("revision"),map(idle.get("operations")).get("revision"));
        assertFalse(idle.containsKey("facts"));assertFalse(idle.containsKey("items"));
    }

    private static org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisFactory(){
        var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getenv("HEYI_DESIGN_HTTP_REDIS_PORT")));
        String password=System.getenv("HEYI_DESIGN_HTTP_REDIS_PASSWORD");
        if(password!=null&&!password.isBlank())config.setPassword(org.springframework.data.redis.connection.RedisPassword.of(password));
        return new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config);
    }

}
