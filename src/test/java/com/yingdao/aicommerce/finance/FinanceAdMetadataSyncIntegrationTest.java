package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceAdMetadataSyncIntegrationTest extends FinanceConfigurationIntegrationTest {
    FinanceAds ads;FinanceAdMetadataSync sync;MarketplaceHttp http;
    List<String> paths=new ArrayList<>();boolean denied,paused,twoPages,pauseResume,noCampaign;
    Map<String,Object> source(boolean gmv){return new LinkedHashMap<>(Map.of("platform","TIKTOK_SHOP","account_id","100001","campaign_id","800001","source",gmv?"API_GMV":"API_CAMPAIGN","shop_id","UNASSIGNED","record_date","2026-10-01","currency","USD","amount",decimal("12.34")));}
    @BeforeEach void metadataProvider(TestInfo test){
        db.update("DELETE FROM finance_ad_campaign_metadata");db.update("DELETE FROM finance_ad_campaign_jobs");
        if(!test.getTestMethod().orElseThrow().getName().startsWith("metadata"))return;
        paths.clear();denied=false;paused=false;twoPages=false;pauseResume=false;noCampaign=false;http=mock(MarketplaceHttp.class);
        ads=new FinanceAds(repo,service,http,cipher);sync=new FinanceAdMetadataSync(repo,ads);
        ads.connectVerified(owner,"100001","","unit-only-ad-access-token","",Map.of("advertiser_id","100001","name","Account","currency","USD","timezone","Asia/Manila"));
        when(http.get(any(),anyMap())).thenAnswer(i->{
            URI uri=i.getArgument(0);paths.add(uri.getPath());
            if(paused)db.update("UPDATE finance_ad_accounts SET enabled=FALSE");
            if(pauseResume){ads.pause(owner,"100001",false);ads.pause(owner,"100001",true);}
            if(denied)return Map.of("code",40001,"message","never-output-this-secret");
            if(uri.getPath().endsWith("/campaign/gmv_max/info/"))return Map.of("code",0,"data",Map.of("campaign_id","800001","campaign_name","Official GMV","store_id","700001","budget",900));
            Map<String,String> q=new HashMap<>();for(String part:uri.getRawQuery().split("&")){var pair=part.split("=",2);q.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}
            if(uri.getPath().endsWith("/campaign/get/"))return Map.of("code",0,"data",Map.of("list",noCampaign?List.of():List.of(Map.of("campaign_id","800001","campaign_name","Official ordinary","objective_type","PRODUCT_SALES")),"page_info",Map.of("page",1,"total_page",noCampaign?0:1,"total_number",noCampaign?0:1)));
            boolean deleted=q.get("filtering").contains("STATUS_DELETE");int page=Integer.parseInt(q.get("page"));
            return Map.of("code",0,"data",Map.of("list",deleted?List.of():List.of(Map.of("campaign_id","800001","advertiser_id","100001","adgroup_id",page==1?"900001":"900002","store_id",page==1?"700001":"700002")),"page_info",Map.of("page",page,"total_page",deleted?0:twoPages?2:1,"total_number",deleted?0:twoPages?2:1)));
        });
    }
    @AfterEach void stopMetadata(){if(sync!=null)sync.stop();if(ads!=null)ads.stop();sync=null;ads=null;}
    void runMetadata(){db.update("UPDATE finance_ad_campaign_jobs SET next_at=UTC_TIMESTAMP(6)");sync.tick();}
    Map<String,Object> saved(){return repo.read(db.queryForObject("SELECT data_json FROM finance_ad_campaign_metadata WHERE organization_id='org'",String.class));}
    @Test void metadataReportDefersMinimalRequestUntilReadOnlyCommitAndDropsRollback(){
        repo.save(owner,new Entry("UNASSIGNED","TIKTOK_SHOP","AD_GMV","deferred-ad","","","GMV","SUCCESS",LocalDate.of(2026,10,1),"USD",decimal("12.34"),null,null,null,null,"API",Map.of("advertiser_id","100001","dimensions",Map.of("campaign_id","800001"),"private_funding_payload","must-not-be-queued")));
        var queryOnly=new FinanceAdSpendService(repo,service);
        var scope=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
        var snapshot=queryOnly.buildNativeSpendFacts(owner,scope);
        assertDoesNotThrow(()->repo.readSnapshot(()->{
            queryOnly.reportFromNative(owner,snapshot,scope,false,"","","");
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs",Integer.class));
            assertTrue(paths.isEmpty());return null;
        }));
        var spend=new FinanceAdSpendService(repo,service);ReflectionTestUtils.setField(spend,"metadataSync",sync);
        assertDoesNotThrow(()->repo.readSnapshot(()->{
            spend.reportFromNative(owner,snapshot,scope,false,"","","");
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs",Integer.class));
            assertTrue(paths.isEmpty());return null;
        }));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs",Integer.class));
        assertTrue(paths.isEmpty());
        sync.tick();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs",Integer.class));
        assertEquals(1,paths.size());

        repo.save(owner,new Entry("UNASSIGNED","TIKTOK_SHOP","AD_GMV","rolled-back-ad","","","GMV","SUCCESS",LocalDate.of(2026,10,2),"USD",decimal("9.87"),null,null,null,null,"API",Map.of("advertiser_id","100001","dimensions",Map.of("campaign_id","800002"))));
        var rollbackSnapshot=spend.buildNativeSpendFacts(owner,scope);
        assertThrows(IllegalStateException.class,()->repo.transaction(()->{
            spend.reportFromNative(owner,rollbackSnapshot,scope,false,"","","");
            throw new IllegalStateException("rollback");
        }));
        sync.tick();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs WHERE campaign_id='800002'",Integer.class));
        assertEquals(1,paths.size());
    }
    @Test void metadataGmvPublishesNamesAndTargetsWithoutChangingFinancialFacts(){
        repo.save(owner,new Entry("UNASSIGNED","TIKTOK_SHOP","AD_GMV","original-ad","","","GMV","SUCCESS",LocalDate.of(2026,10,1),"USD",decimal("12.34"),null,null,null,null,"API",Map.of("advertiser_id","100001","dimensions",Map.of("campaign_id","800001"))));
        String before=db.queryForObject("SELECT raw_json FROM finance_records WHERE external_id='original-ad'",String.class);
        FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));runMetadata();
        assertEquals("Official GMV",saved().get("campaign_name"));assertEquals(List.of("700001"),saved().get("official_store_ids"));
        var row=FinanceAdMetadataSync.enrich(repo,owner,List.of(source(true))).getFirst();
        assertEquals("Official GMV",row.get("campaign_name"));assertEquals("UNASSIGNED",row.get("shop_id"));assertEquals(decimal("12.34"),row.get("amount"));assertNull(row.get("credit_amount"));
        assertEquals(before,db.queryForObject("SELECT raw_json FROM finance_records WHERE external_id='original-ad'",String.class));assertEquals(1,paths.size());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE event_scope='DAY' AND record_date='2026-10-01' AND source_table='finance_ad_campaign_metadata'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE source_table='finance_ad_campaign_jobs'",Integer.class));
        runMetadata();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE source_table='finance_ad_campaign_metadata'",Integer.class),"unchanged metadata must not invalidate cached amounts again");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE source_table='finance_ad_campaign_jobs'",Integer.class),"unchanged status must not invalidate cached amounts again");
    }
    @Test void metadataOrdinaryStagesAllAdgroupPagesAndDeletedTargetsBeforePublishing(){
        twoPages=true;FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(false)));runMetadata();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_metadata",Integer.class));
        runMetadata();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_metadata",Integer.class));
        runMetadata();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_metadata",Integer.class));
        runMetadata();assertEquals(List.of("700001","700002"),saved().get("official_store_ids"));
        assertEquals("Official ordinary",saved().get("campaign_name"));assertEquals(4,paths.size());
        assertFalse(saved().containsKey("report_type"));
    }
    @Test void metadataPermissionErrorsPreserveUnknownAmountsAndOldData(){
        FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));runMetadata();var old=saved();
        denied=true;runMetadata();assertEquals(old,saved());
        assertEquals("NEEDS_AUTHORIZATION",db.queryForObject("SELECT status FROM finance_ad_campaign_jobs",String.class));
        String message=db.queryForObject("SELECT error_message FROM finance_ad_campaign_jobs",String.class);assertTrue(message.contains("40001"));assertFalse(message.contains("secret"));
    }
    @Test void metadataCannotPublishAfterAccountPausedWhileRequestIsInFlight(){
        FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));paused=true;runMetadata();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_metadata",Integer.class));
    }
    @Test void metadataLateResponseAfterPauseResumeCannotPublish(){
        FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));pauseResume=true;runMetadata();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_metadata",Integer.class));
        assertNull(db.queryForObject("SELECT lease_token FROM finance_ad_campaign_jobs",String.class));
    }
    @Test void metadataProviderFailureKeepsTheCompletedAdgroupPage(){
        twoPages=true;FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(false)));runMetadata();runMetadata();
        String checkpoint=db.queryForObject("SELECT checkpoint_json FROM finance_ad_campaign_jobs",String.class);
        denied=true;runMetadata();assertEquals(checkpoint,db.queryForObject("SELECT checkpoint_json FROM finance_ad_campaign_jobs",String.class));
        denied=false;runMetadata();runMetadata();assertEquals(List.of("700001","700002"),saved().get("official_store_ids"));
    }
    @Test void metadataStaleSelectionCannotBypassAnotherDispatchersBackoff(){
        FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));var raced=spy(db);
        doAnswer(invocation->{Object result=invocation.callRealMethod();db.update("UPDATE finance_ad_campaign_jobs SET next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 DAY)");return result;})
                .when(raced).queryForList(argThat(sql->sql!=null&&sql.contains("SELECT j.*,a.user_id")));
        sync.stop();sync=new FinanceAdMetadataSync(new FinanceRepository(raced,new tools.jackson.databind.ObjectMapper()),ads);runMetadata();
        assertEquals(0,paths.size());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs WHERE next_at>DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 4 DAY)",Integer.class));
    }
    @Test void metadataShrinkingPaginationRestartsInsteadOfKeepingAnImpossiblePage(){
        twoPages=true;FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(false)));runMetadata();runMetadata();
        assertEquals(2,number(repo.read(db.queryForObject("SELECT checkpoint_json FROM finance_ad_campaign_jobs",String.class)).get("page")));
        twoPages=false;runMetadata();assertNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_campaign_jobs",String.class));
        assertEquals("ERROR",db.queryForObject("SELECT status FROM finance_ad_campaign_jobs",String.class));
        runMetadata();assertTrue(paths.getLast().endsWith("/campaign/get/"));
    }
    @Test void metadataDedicatedShopRemovalDeletesDerivedJobsAndMetadata(){
        String shop=groupedShop(owner,"700001",service.saveGroup(owner,null,"Target"));db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        db.update("UPDATE finance_ad_accounts SET shop_id=?",shop);FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));runMetadata();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_metadata",Integer.class));
        service.removeShop(owner,shop);finishDeletion(shop);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_metadata",Integer.class));
    }
    @Test void metadataSharedAccountTargetsSurviveRemovalOfOneFinanceShop(){
        String shop=groupedShop(owner,"700001",service.saveGroup(owner,null,"Target"));db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));runMetadata();service.removeShop(owner,shop);finishDeletion(shop);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_metadata",Integer.class));
    }
    @Test void metadataEnqueueIsScopedAndIdempotentForConnectedAccountOnly(){
        var wrong=source(true);wrong.put("account_id","100002");
        FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true),source(true),wrong));
        FinanceAdMetadataSync.enqueue(repo,other,List.of(source(true)));FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs",Integer.class));
        assertEquals("",FinanceAdMetadataSync.enrich(repo,other,List.of(source(true))).getFirst().getOrDefault("campaign_name",""));
    }
    @Test void metadataFirstQueueForAnAlreadyPausedAccountIsShownAsPaused(){
        ads.pause(owner,"100001",false);FinanceAdMetadataSync.enqueue(repo,owner,List.of(source(true)));runMetadata();
        assertEquals("PAUSED",FinanceAdMetadataSync.enrich(repo,owner,List.of(source(true))).getFirst().get("metadata_status"));
        assertEquals(0,paths.size());
    }
    @Test void metadataImportedSeriesCanResolveThroughDedicatedGmvInfoWhenOrdinaryListsAreEmpty(){
        noCampaign=true;var imported=source(false);imported.put("source","IMPORT_FUNDING");FinanceAdMetadataSync.enqueue(repo,owner,List.of(imported));
        runMetadata();runMetadata();runMetadata();assertTrue(paths.getLast().endsWith("/campaign/gmv_max/info/"));
        assertEquals("Official GMV",saved().get("campaign_name"));assertEquals(List.of("700001"),saved().get("official_store_ids"));
        assertEquals(1,db.queryForObject("SELECT is_gmv FROM finance_ad_campaign_jobs",Integer.class));
        assertEquals("UNASSIGNED",FinanceAdMetadataSync.enrich(repo,owner,List.of(imported)).getFirst().get("shop_id"));
    }
    @Test void metadataFullReportQueuesWithoutCallingProviderAndRetainsAuthorizationStatus(){
        repo.save(owner,new Entry("UNASSIGNED","TIKTOK_SHOP","AD_GMV","ad-source","","","GMV","SUCCESS",LocalDate.of(2026,10,1),"USD",decimal("12.34"),null,null,null,null,"API",Map.of("advertiser_id","100001","dimensions",Map.of("campaign_id","800001"))));
        var spend=new FinanceAdSpendService(repo,service);ReflectionTestUtils.setField(spend,"metadataSync",sync);var scope=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
        spend.report(owner,scope,false,"","","");assertEquals(0,paths.size());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_campaign_jobs",Integer.class));
        denied=true;runMetadata();var row=rows(spend.report(owner,scope,false,"","","").get("items")).getFirst();
        assertEquals("NEEDS_AUTHORIZATION",row.get("metadata_status"));assertTrue(text(row.get("metadata_error")).contains("40001"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE source_table='finance_ad_campaign_jobs' AND event_scope='DAY' AND record_date='2026-10-01'",Integer.class));
        assertEquals("UNASSIGNED",row.get("shop_id"));assertNull(row.get("credit_amount"));
        denied=false;runMetadata();row=rows(spend.report(owner,scope,false,"","","").get("items")).getFirst();
        assertEquals("Official GMV",row.get("campaign_name"));assertEquals("SUCCESS",row.get("metadata_status"));
        assertEquals(false,map(row.get("target_shop_evidence")).get("historical_allocation_verified"));
        var sourceDetails=spend.sourceDetails(owner,text(row.get("source_key")));
        assertEquals("Official GMV",sourceDetails.get("campaign_name"));assertEquals(row.get("target_shop_evidence"),sourceDetails.get("target_shop_evidence"));
        assertEquals(FinanceAdSpendReport.fingerprint(row),sourceDetails.get("fingerprint"));
        byte[] exported=new FinanceAdSpendController(spend,controller()).export(Map.of("start","2026-10-01","end","2026-10-31","currency","USD"),principal()).getBody();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().equals("xl/worksheets/sheet3.xml")){
            String xml=new String(zip.readAllBytes(),StandardCharsets.UTF_8);assertTrue(xml.contains("Official GMV"));assertTrue(xml.contains("700001"));assertTrue(xml.contains("非历史资金归属"));assertTrue(xml.contains("/campaign/gmv_max/info/"));
        }}catch(java.io.IOException failure){throw new AssertionError(failure);}
    }
}
