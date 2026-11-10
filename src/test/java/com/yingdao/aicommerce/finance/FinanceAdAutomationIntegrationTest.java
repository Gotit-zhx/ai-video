package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceAdAutomationIntegrationTest extends FinanceConfigurationIntegrationTest {
    FinanceAds ads;MarketplaceHttp http;
    final String advertiser="100001",storeA="700001",storeB="700002";
    List<Map<String,Object>> remoteStores=new ArrayList<>();
    boolean failDiscovery,failGmv,empty,failSecondPage;
    int pages=1;final List<Map<String,String>> calls=new ArrayList<>();
    @BeforeEach void provider(TestInfo test){
        // CI also runs the inherited finance contracts; keep their original empty-account fixture.
        if(test.getTestMethod().map(method->!method.getName().startsWith("automation")).orElse(true)){ads=null;return;}
        remoteStores=new ArrayList<>();failDiscovery=false;failGmv=false;empty=false;failSecondPage=false;pages=1;calls.clear();
        http=mock(MarketplaceHttp.class);ads=new FinanceAds(repo,service,http,cipher);
        ads.connectVerified(owner,advertiser,"","unit-only-ad-access-token","",Map.of("advertiser_id",advertiser,"name","Ads","currency","PHP","timezone","Asia/Manila"));
        when(http.get(any(),anyMap())).thenAnswer(invocation->{
            URI uri=invocation.getArgument(0);var query=params(uri);query.put("path",uri.getPath());calls.add(query);
            assertTrue(uri.toString().startsWith("https://business-api.tiktok.com/open_api/v1.3/"));
            if(uri.getPath().endsWith("/gmv_max/store/list/")){
                if(failDiscovery)return Map.of("code",40001,"message","unit-only-secret");
                return Map.of("code",0,"data",Map.of("store_list",remoteStores));
            }
            boolean gmv=uri.getPath().endsWith("/gmv_max/report/get/");
            if(gmv&&failGmv||failSecondPage&&"2".equals(query.get("page")))return Map.of("code",40002,"message","unit-only-secret");
            var dimensions=new LinkedHashMap<String,Object>();dimensions.put("stat_time_day",query.get("start_date"));
            if(gmv)dimensions.put("campaign_id","80000"+query.get("page"));
            var metrics=gmv?Map.of("cost","13","net_cost","10","orders","2","gross_revenue","40"):
                    Map.of("spend","7","impressions","100","clicks","2","conversion","1");
            return Map.of("code",0,"data",Map.of("list",empty?List.of():List.of(Map.of("dimensions",dimensions,"metrics",metrics)),
                    "page_info",Map.of("page",Integer.parseInt(query.get("page")),"total_page",empty?0:pages,"total_number",empty?0:pages)));
        });
    }
    @AfterEach void stopAds(){if(ads!=null)ads.stop();}
    Map<String,String> params(URI uri){var values=new HashMap<String,String>();for(String part:uri.getRawQuery().split("&")){var pair=part.split("=",2);values.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return values;}
    Map<String,Object> store(String id){return Map.of("store_id",id,"store_name","Same name","store_code","CODE-"+id,"is_gmv_max_available",true);}
    String shop(Scope scope,String external){String id=groupedShop(scope,external,service.saveGroup(scope,null,"Group "+external));db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP',name='Same name' WHERE id=?",id);return id;}
    void tick(){db.update("UPDATE finance_ad_accounts SET next_at=UTC_TIMESTAMP(6)");ads.tick();}
    void discover(){db.update("UPDATE finance_ad_accounts SET discovery_next_at=UTC_TIMESTAMP(6)");tick();}
    void run(String channel,String store){
        db.update("UPDATE finance_ad_streams SET next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY)");
        db.update("UPDATE finance_ad_streams SET next_at=UTC_TIMESTAMP(6) WHERE channel=? AND store_id=?",channel,store);tick();
    }
    int count(String sql,Object...args){return db.queryForObject(sql,Integer.class,args);}
    String state(String channel,String store){return db.queryForObject("SELECT status FROM finance_ad_streams WHERE channel=? AND store_id=?",String.class,channel,store);}

    @Test void automationDiscoversMultipleStoresByOfficialIdWithoutAssigningTheWholeAccount(){
        String a=shop(owner,storeA);shop(other,storeB);shop(owner,"700003");
        remoteStores.addAll(List.of(store(storeA),store(storeB)));discover();
        assertEquals(a,db.queryForObject("SELECT shop_id FROM finance_ad_stores WHERE store_id=?",String.class,storeA));
        assertEquals("UNMATCHED",db.queryForObject("SELECT match_status FROM finance_ad_stores WHERE store_id=?",String.class,storeB));
        assertEquals("",db.queryForObject("SELECT shop_id FROM finance_ad_accounts",String.class));
        assertEquals(3,count("SELECT COUNT(*) FROM finance_ad_streams"));
        assertFalse(ads.list(other).stream().anyMatch(r->advertiser.equals(r.get("advertiser_id"))));
        run("GMV",storeA);run("GMV",storeB);run("BASIC","");
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE shop_id=? AND kind='AD_GMV'",a));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE shop_id='UNASSIGNED' AND kind='AD_GMV'"));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE shop_id='UNASSIGNED' AND kind='AD'"));
        for(var call:calls)if(call.get("path").endsWith("/gmv_max/report/get/")){
            assertEquals(List.of("campaign_id","stat_time_day"),repo.read("{\"list\":"+call.get("dimensions")+"}").get("list"));
            assertTrue(call.get("filtering").contains("STATUS_DELETE"));assertTrue(call.get("filtering").contains("PRODUCT"));assertTrue(call.get("filtering").contains("LIVE"));
            assertFalse(call.get("store_ids").contains(","));
        }
    }
    @Test void automationRematchesExistingSpendWithoutNewRecordsOrTokenChanges(){
        remoteStores.add(store(storeA));discover();run("GMV",storeA);
        String identity=db.queryForObject("SELECT id FROM finance_records",String.class),encrypted=db.queryForObject("SELECT access_token_encrypted FROM finance_ad_accounts",String.class);
        String a=shop(owner,storeA);discover();
        assertEquals(a,db.queryForObject("SELECT shop_id FROM finance_records WHERE id=?",String.class,identity));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records"));
        assertEquals(encrypted,db.queryForObject("SELECT access_token_encrypted FROM finance_ad_accounts",String.class));
        assertNotNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE channel='GMV'",String.class));
    }
    @Test void automationStagesPagesAndRetriesWithoutPartialPublicationOrDeletingPreviousData(){
        remoteStores.add(store(storeA));discover();pages=2;
        run("GMV",storeA);assertEquals(0,count("SELECT COUNT(*) FROM finance_records"));assertEquals(1,count("SELECT COUNT(*) FROM finance_ad_report_stage"));
        failSecondPage=true;run("GMV",storeA);assertEquals("ERROR",state("GMV",storeA));
        assertEquals(0,count("SELECT COUNT(*) FROM finance_ad_coverage"));assertEquals(1,count("SELECT COUNT(*) FROM finance_ad_report_stage"));
        assertFalse(db.queryForObject("SELECT error_message FROM finance_ad_streams WHERE channel='GMV'",String.class).contains("unit-only"));
        failSecondPage=false;run("GMV",storeA);assertEquals(2,count("SELECT COUNT(*) FROM finance_records"));assertEquals(0,count("SELECT COUNT(*) FROM finance_ad_report_stage"));
        assertEquals(7,count("SELECT COUNT(*) FROM finance_ad_coverage"));
        assertEquals(0,decimal(db.queryForObject("SELECT SUM(amount) FROM finance_records",String.class)).compareTo(decimal("26")));
    }
    @Test void automationDiscoveryAndGmvFailuresDoNotBlockOrdinaryReports(){
        failDiscovery=true;discover();assertEquals("ERROR",db.queryForObject("SELECT discovery_status FROM finance_ad_accounts",String.class));
        run("BASIC","");assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE kind='AD'"));
        failDiscovery=false;remoteStores.add(store(storeA));discover();failGmv=true;
        run("GMV",storeA);assertEquals("ERROR",state("GMV",storeA));run("BASIC","");
        assertEquals(2,count("SELECT COUNT(*) FROM finance_records WHERE kind='AD'"));
        assertNotNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE channel='GMV'",String.class));
    }
    @Test void automationEmptyReportIsCoveredButCreatesNoFabricatedFinancialRows(){
        remoteStores.add(store(storeA));discover();empty=true;run("GMV",storeA);
        assertEquals(7,count("SELECT COUNT(*) FROM finance_ad_coverage WHERE amount=0 AND row_count=0"));
        assertEquals(0,count("SELECT COUNT(*) FROM finance_records"));
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(89);
        var account=ads.list(owner,day,day.plusDays(6)).getFirst();var coverage=rows(account.get("coverage")).getFirst();
        assertEquals(7,number(coverage.get("covered_days")));assertEquals(7,number(account.get("requestedDays")));
    }
    @Test void automationReadsHistoricalReportsWithoutChangingExclusiveAuthorizationAndRespectsRemoval(){
        String a=shop(owner,storeA);var remote=new LinkedHashMap<>(store(storeB));remote.put("exclusive_authorized_advertiser_info",Map.of("advertiser_id","999999"));
        remoteStores.addAll(List.of(store(storeA),remote));discover();
        assertEquals(1,count("SELECT COUNT(*) FROM finance_ad_streams WHERE store_id=?",storeB),"Current primary advertiser does not determine historical read permission");
        assertEquals("999999",db.queryForObject("SELECT primary_advertiser_id FROM finance_ad_stores WHERE store_id=?",String.class,storeB));
        service.removeShop(owner,a);finishDeletion(a);discover();
        assertEquals("REMOVED",db.queryForObject("SELECT match_status FROM finance_ad_stores WHERE store_id=?",String.class,storeA));
        run("GMV",storeA);assertEquals(0,count("SELECT COUNT(*) FROM finance_records"));
        verify(http,never()).postJson(any(),anyMap(),anyString());
    }
    @Test void automationLateReportCannotPublishAfterAccountPausedOrLeaseRevoked(){
        remoteStores.add(store(storeA));discover();
        doAnswer(invocation->{db.update("UPDATE finance_ad_accounts SET enabled=FALSE,lease_token=NULL,lease_until=NULL");return Map.of("code",0,"data",Map.of("list",List.of(),"page_info",Map.of("total_page",0)));}).when(http).get(any(),anyMap());
        run("GMV",storeA);assertEquals(0,count("SELECT COUNT(*) FROM finance_ad_coverage"));assertEquals(0,count("SELECT COUNT(*) FROM finance_ad_report_stage"));
    }
    @Test void automationReauthorizationPreservesBothLegacyAndStreamCheckpoints(){
        remoteStores.add(store(storeA));discover();pages=2;run("GMV",storeA);
        String cp=db.queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE channel='GMV'",String.class);
        db.update("UPDATE finance_ad_accounts SET checkpoint_json='{}'");
        ads.connectVerified(owner,advertiser,"","new-unit-only-ad-access-token","",Map.of("advertiser_id",advertiser,"name","Ads updated","currency","PHP","timezone","Asia/Manila"));
        assertEquals(cp,db.queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE channel='GMV'",String.class));
        assertNotNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_accounts",String.class));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_ad_report_stage"));
    }
    @Test void automationChangingPaginationRestartsOnlyTheAffectedWindow(){
        remoteStores.add(store(storeA));discover();pages=2;run("GMV",storeA);
        doAnswer(invocation->{
            var q=params(invocation.getArgument(0));
            return Map.of("code",0,"data",Map.of("list",List.of(Map.of("dimensions",Map.of("stat_time_day",q.get("start_date"),"campaign_id","800001"),
                    "metrics",Map.of("cost","13"))),"page_info",Map.of("page",2,"total_page",2,"total_number",2)));
        }).when(http).get(any(),anyMap());
        run("GMV",storeA);
        var cp=repo.read(db.queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE channel='GMV'",String.class));
        assertEquals(1,number(cp.get("page")));assertFalse(cp.containsKey("run"));
        assertEquals(0,count("SELECT COUNT(*) FROM finance_ad_report_stage"));assertEquals(0,count("SELECT COUNT(*) FROM finance_ad_coverage"));
        assertEquals("READY",state("BASIC",""));
    }
    @Test void automationRefreshIsIdempotentAndDoesNotEraseImportsOrAnotherStore(){
        remoteStores.addAll(List.of(store(storeA),store(storeB)));discover();run("GMV",storeA);run("GMV",storeB);
        String first=db.queryForObject("SELECT id FROM finance_records WHERE JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_store_id'))=?",String.class,storeA);
        LocalDate from=LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(89);
        repo.save(owner,new Entry("UNASSIGNED","TIKTOK_SHOP","AD","imported-1","","","人工补录","IMPORTED",from,"PHP",decimal("9"),null,null,null,null,"IMPORT",Map.of()));
        String cp=repo.write(Map.of("from",from.toString(),"until",from.plusDays(6).toString(),"page",1));
        db.update("UPDATE finance_ad_streams SET checkpoint_json=? WHERE channel='GMV' AND store_id=?",cp,storeA);run("GMV",storeA);
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE id=?",first));assertEquals(3,count("SELECT COUNT(*) FROM finance_records"));
        empty=true;db.update("UPDATE finance_ad_streams SET checkpoint_json=? WHERE channel='GMV' AND store_id=?",cp,storeA);run("GMV",storeA);
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE id=?",first));
        assertEquals("ERROR",state("GMV",storeA));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE source='IMPORT'"));assertEquals(2,count("SELECT COUNT(*) FROM finance_records WHERE kind='AD_GMV'"));
    }
    @Test void automationAdoptsBlockedLegacyProgressWithoutBlockingDiscoveryOrSkippingOlderDates(){
        LocalDate from=LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(120);
        String legacy=repo.write(Map.of("from",from.toString(),"until",from.plusDays(100).toString(),"page",8,"run","legacy-run"));
        db.update("UPDATE finance_ad_accounts SET checkpoint_json=?",legacy);remoteStores.add(store(storeA));discover();
        assertEquals(2,count("SELECT COUNT(*) FROM finance_ad_streams"));run("BASIC","");
        assertEquals(from.toString(),calls.getLast().get("start_date"));assertEquals("1",calls.getLast().get("page"));
        assertNotNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_accounts",String.class));
        run("GMV",storeA);assertEquals(from.toString(),calls.getLast().get("start_date"));
        String cp=repo.write(Map.of("from",from.toString(),"until",from.plusDays(6).toString(),"page",1));
        db.update("UPDATE finance_ad_streams SET checkpoint_json=?",cp);run("BASIC","");
        assertNotNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_accounts",String.class));run("GMV",storeA);
        assertNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_accounts",String.class));assertEquals("SUCCESS",db.queryForObject("SELECT status FROM finance_ad_accounts",String.class));
    }
    @Test void automationValidEmptyDiscoveryCanCompleteTheAvailableOrdinaryReport(){
        discover();empty=true;String today=LocalDate.now(ZoneId.of("Asia/Manila")).toString();
        db.update("UPDATE finance_ad_streams SET checkpoint_json=?",repo.write(Map.of("from",today,"until",today,"page",1)));
        run("BASIC","");assertEquals("EMPTY",db.queryForObject("SELECT discovery_status FROM finance_ad_accounts",String.class));
        assertEquals("SUCCESS",db.queryForObject("SELECT status FROM finance_ad_accounts",String.class));assertEquals(0,count("SELECT COUNT(*) FROM finance_records"));
    }

    @Test void automationReauthorizationKeepsAnExplicitlyPausedAccountPaused(){
        remoteStores.add(store(storeA));discover();run("GMV",storeA);
        ads.pause(owner,advertiser,false);
        String checkpoint=db.queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE channel='GMV'",String.class);
        String oldToken=db.queryForObject("SELECT access_token_encrypted FROM finance_ad_accounts",String.class);
        ads.connectVerified(owner,advertiser,"","new-unit-only-ad-token","",Map.of("advertiser_id",advertiser,"name","Updated","currency","PHP","timezone","Asia/Manila"));
        assertFalse(db.queryForObject("SELECT enabled FROM finance_ad_accounts",Boolean.class),"credential refresh must preserve a manual pause");
        assertEquals("PAUSED",db.queryForObject("SELECT status FROM finance_ad_accounts",String.class));
        assertNotEquals(oldToken,db.queryForObject("SELECT access_token_encrypted FROM finance_ad_accounts",String.class));
        assertEquals(checkpoint,db.queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE channel='GMV'",String.class));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE kind='AD_GMV'"));
    }

    @Test void automationPauseAndResumeRevokesAnEarlierInFlightResponse(){
        remoteStores.add(store(storeA));discover();
        doAnswer(invocation->{
            ads.pause(owner,advertiser,false);ads.pause(owner,advertiser,true);
            var q=params(invocation.getArgument(0));
            return Map.of("code",0,"data",Map.of("list",List.of(Map.of("dimensions",Map.of("stat_time_day",q.get("start_date"),"campaign_id","800001"),"metrics",Map.of("cost","13"))),"page_info",Map.of("page",1,"total_page",1,"total_number",1)));
        }).when(http).get(any(),anyMap());
        run("GMV",storeA);
        assertEquals(0,count("SELECT COUNT(*) FROM finance_records"),"a response from the revoked lease cannot publish after resume");
        assertEquals(0,count("SELECT COUNT(*) FROM finance_ad_coverage"));
        assertNull(db.queryForObject("SELECT lease_token FROM finance_ad_accounts",String.class));
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_ad_accounts",String.class));
        assertNotNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE channel='GMV'",String.class));
    }

    @Test void automationPausedDedicatedAccountCannotImportASecondCopyOfItsReport(){
        String a=shop(owner,storeA);db.update("UPDATE finance_ad_accounts SET shop_id=?",a);discover();run("BASIC","");ads.pause(owner,advertiser,false);
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(89);
        byte[] bytes=("id,date,currency,amount\ncopy,"+day+",PHP,7\n").getBytes(StandardCharsets.UTF_8);
        var mapping=Map.<String,Object>of("external_id",0,"date",1,"currency",2,"amount",3);
        assertThrows(RuntimeException.class,()->new FinanceImport(service,repo).commit(owner,a,"AD",mapping,bytes,"duplicate.csv"));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE kind='AD'"));
        assertEquals(0,decimal(db.queryForObject("SELECT SUM(amount) FROM finance_records WHERE kind='AD'",String.class)).compareTo(decimal("7")));
        assertEquals(0,count("SELECT COUNT(*) FROM finance_imports WHERE kind='AD'"));
    }

    @Test void automationResumeRefusesAnExistingConflictingManualSource(){
        String a=shop(owner,storeA);db.update("UPDATE finance_ad_accounts SET shop_id=?",a);ads.pause(owner,advertiser,false);
        // Models a conflicting source saved by the old paused-account import path.
        repo.save(owner,new Entry(a,"TIKTOK_SHOP","AD","legacy-import","","","Manual","IMPORTED",LocalDate.now(),"PHP",decimal("7"),null,null,null,null,"IMPORT",Map.of()));
        assertThrows(RuntimeException.class,()->ads.pause(owner,advertiser,true));
        assertFalse(db.queryForObject("SELECT enabled FROM finance_ad_accounts",Boolean.class));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE source='IMPORT'"));
    }

    @Test void automationEmptyRefreshPreservesPreviouslyPublishedFinancialRecords(){
        remoteStores.add(store(storeA));discover();run("GMV",storeA);
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(89);
        String identity=db.queryForObject("SELECT id FROM finance_records",String.class);
        db.update("UPDATE finance_ad_streams SET checkpoint_json=? WHERE channel='GMV'",repo.write(Map.of("from",day.toString(),"until",day.plusDays(6).toString(),"page",1)));
        empty=true;run("GMV",storeA);
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE id=?",identity),"empty is unknown, not an explicit reversal of the previous spend");
        assertEquals(0,decimal(db.queryForObject("SELECT amount FROM finance_records WHERE id=?",String.class,identity)).compareTo(decimal("13")));
        assertEquals("ERROR",state("GMV",storeA));
        assertTrue(db.queryForObject("SELECT error_message FROM finance_ad_streams WHERE channel='GMV'",String.class).contains("空报表"));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_ad_coverage WHERE row_count=1"));
    }

    @Test void automationPartialDateRefreshDoesNotEraseAPreviouslyReportedDay(){
        remoteStores.add(store(storeA));discover();run("GMV",storeA);
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(89);
        String identity=db.queryForObject("SELECT id FROM finance_records",String.class);
        db.update("UPDATE finance_ad_streams SET checkpoint_json=? WHERE channel='GMV'",repo.write(Map.of("from",day.toString(),"until",day.plusDays(6).toString(),"page",1)));
        doAnswer(invocation->{var q=params(invocation.getArgument(0));return Map.of("code",0,"data",Map.of("list",List.of(Map.of("dimensions",Map.of("stat_time_day",LocalDate.parse(q.get("start_date")).plusDays(1).toString(),"campaign_id","800001"),"metrics",Map.of("cost","9"))),"page_info",Map.of("page",1,"total_page",1,"total_number",1)));}).when(http).get(any(),anyMap());
        run("GMV",storeA);
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE id=?",identity));
        assertEquals(0,decimal(db.queryForObject("SELECT amount FROM finance_records WHERE id=?",String.class,identity)).compareTo(decimal("13")));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records"),"do not publish a partial replacement");
        assertEquals("ERROR",state("GMV",storeA));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_ad_coverage WHERE row_count=1"));
    }

    @Test void automationAnExplicitZeroReportCanReplaceEarlierSpend(){
        remoteStores.add(store(storeA));discover();run("GMV",storeA);
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(89);
        db.update("UPDATE finance_ad_streams SET checkpoint_json=? WHERE channel='GMV'",repo.write(Map.of("from",day.toString(),"until",day.plusDays(6).toString(),"page",1)));
        doAnswer(invocation->{var q=params(invocation.getArgument(0));return Map.of("code",0,"data",Map.of("list",List.of(Map.of("dimensions",Map.of("stat_time_day",q.get("start_date"),"campaign_id","800001"),"metrics",Map.of("cost","0"))),"page_info",Map.of("page",1,"total_page",1,"total_number",1)));}).when(http).get(any(),anyMap());
        run("GMV",storeA);
        assertEquals(1,count("SELECT COUNT(*) FROM finance_records WHERE amount=0"));
        assertEquals(1,count("SELECT COUNT(*) FROM finance_ad_coverage WHERE amount=0 AND row_count=1"));
        assertEquals("SUCCESS",state("GMV",storeA));
    }

    @Test void automationTopupImportIsNotAcceptedAsAdvertisingSpend(){
        String a=shop(owner,storeA);
        byte[] bytes="id,date,currency,amount,category\ntopup,2026-10-31,PHP,100,广告充值\n".getBytes(StandardCharsets.UTF_8);
        var mapping=Map.<String,Object>of("external_id",0,"date",1,"currency",2,"amount",3,"category",4);
        assertThrows(RuntimeException.class,()->new FinanceImport(service,repo).commit(owner,a,"AD",mapping,bytes,"topup.csv"));
        assertEquals(0,count("SELECT COUNT(*) FROM finance_records WHERE kind='AD'"));
    }
}
