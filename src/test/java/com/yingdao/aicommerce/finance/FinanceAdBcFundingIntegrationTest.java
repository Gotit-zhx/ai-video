package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceAdBcFundingIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    final LocalDate day=LocalDate.of(2026,10,1);
    FinanceAds ads;MarketplaceHttp http;boolean denied,noFinanceRole,failSecondPage,twoBcs,conflictingBc,emptyCost;
    int pages;String costAmount;final List<String> paths=new ArrayList<>();
    @BeforeAll void schema()throws Exception{fixture.migrate();}
    @AfterAll void close(){fixture.closeDataSource();}
    @BeforeEach void setup(){
        fixture.setup();denied=false;noFinanceRole=false;failSecondPage=false;twoBcs=false;conflictingBc=false;emptyCost=false;pages=1;costAmount="-7";paths.clear();
        http=mock(MarketplaceHttp.class);ads=new FinanceAds(fixture.repo,fixture.service,http,fixture.cipher);
        for(String id:List.of("100001","100002"))connect(id,"same-unit-ad-token");
        when(http.getExact(any(),anyMap())).thenAnswer(i->response(i.getArgument(0)));
    }
    @AfterEach void stop(){ads.stop();}
    void connect(String id,String token){ads.connectVerified(fixture.owner,id,"",token,"",Map.of("advertiser_id",id,"name","Ad "+id,"currency","PHP","timezone","Asia/Manila"));}
    Map<String,String> params(URI uri){var out=new HashMap<String,String>();for(String part:uri.getRawQuery().split("&")){var pair=part.split("=",2);out.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return out;}
    Map<String,Object> response(URI uri){
        paths.add(uri.getPath());var q=params(uri);
        if(denied)return Map.of("code",40001,"message","unit-secret-must-not-persist");
        if(uri.getPath().endsWith("/bc/get/")){
            var bcs=new ArrayList<Map<String,Object>>();
            for(String bc:twoBcs?List.of("700001","700002"):List.of("700001"))bcs.add(Map.of("bc_info",Map.of("bc_id",bc),"user_role","ADMIN","ext_user_role",Map.of("finance_role",noFinanceRole?"":"ANALYST")));
            return Map.of("code",0,"data",Map.of("list",bcs,"page_info",Map.of("page",1,"total_page",1,"total_number",bcs.size())));
        }
        assertTrue(uri.getPath().endsWith("/bc/account/cost/get/"));assertFalse(q.get("filtering").contains("keyword"));
        if(emptyCost)return Map.of("code",0,"data",Map.of("cost_list",List.of(),"page_info",Map.of("page",1,"total_page",0,"total_number",0)));
        int page=Integer.parseInt(q.get("page"));if(failSecondPage&&page==2)return Map.of("code",40002,"message","unit-secret-must-not-persist");
        var list=new ArrayList<Map<String,Object>>();
        for(String id:pages==1?List.of("100001","100002"):List.of(page==1?"100001":"100002"))list.add(Map.of("advertiser_id",id,"advertiser_name","Ad "+id,"currency","PHP","amount",conflictingBc&&"700002".equals(q.get("bc_id"))?"-8":costAmount,"cash_amount","-6","grant_amount","-1","tax_amount","-0.35"));
        return Map.of("code",0,"data",Map.of("cost_list",list,"transaction_summary",Map.of("amount","-999"),"page_info",Map.of("page",page,"total_page",pages,"total_number",2)));
    }
    void run(){fixture.db.update("UPDATE finance_ad_bc_sync SET next_at=UTC_TIMESTAMP(6)");ads.funding.tick();}
    void discover(){ads.funding.tick();assertEquals(1,paths.size());assertTrue(paths.getFirst().endsWith("/bc/get/"));
        fixture.db.update("UPDATE finance_ad_bc_sync SET checkpoint_json=?",fixture.repo.write(Map.of("from",day.toString(),"until",day.toString())));}
    int count(String table){return fixture.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    String state(){return fixture.db.queryForObject("SELECT status FROM finance_ad_bc_sync",String.class);}
    @Test void stagesAllPagesAndDeduplicatesTheSameCredentialAndCrossBcAccountCosts(){
        twoBcs=true;pages=2;discover();assertEquals(1,count("finance_ad_bc_sync"));
        run();assertEquals(0,count("finance_ad_bc_costs"));run();assertEquals(0,count("finance_ad_bc_costs"));run();assertEquals(0,count("finance_ad_bc_costs"));run();
        assertEquals(2,count("finance_ad_bc_costs"));assertEquals(0,count("finance_ad_bc_stage"));assertEquals(0,count("finance_records"));
        assertEquals(0,decimal(fixture.db.queryForObject("SELECT SUM(amount) FROM finance_ad_bc_costs",String.class)).compareTo(decimal("14")));
        var f=new Filter(day,day,"","","PHP","day");var rows=FinanceAdBcFunding.sources(fixture.repo,fixture.owner,f);
        assertEquals(2,rows.size());assertEquals("API_BC_COST",rows.getFirst().get("source"));assertNull(rows.getFirst().get("credit_amount"));assertNull(rows.getFirst().get("net_amount"));assertEquals("UNASSIGNED",rows.getFirst().get("shop_id"));
        assertTrue(FinanceAdBcFunding.sources(fixture.repo,fixture.other,f).isEmpty());
    }
    @Test void permissionDenialBacksOffAndReauthorizationAutomaticallyRestartsDiscovery(){
        denied=true;ads.funding.tick();assertEquals("NEEDS_PERMISSION",state());
        assertTrue(fixture.db.queryForObject("SELECT next_at>DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 23 HOUR) FROM finance_ad_bc_sync",Boolean.class));
        ads.funding.tick();assertEquals(1,paths.size());assertFalse(fixture.db.queryForObject("SELECT error_message FROM finance_ad_bc_sync",String.class).contains("unit-secret"));
        denied=false;connect("100001","same-unit-ad-token");ads.funding.tick();assertEquals(2,paths.size());assertEquals("SYNCING",state());
    }
    @Test void basicAdminRoleDoesNotSubstituteForFinanceRole(){
        noFinanceRole=true;ads.funding.tick();assertEquals("NEEDS_FINANCE_ROLE",state());assertEquals(0,count("finance_ad_bc_costs"));
        var account=ads.list(fixture.owner).getFirst();assertTrue(Boolean.TRUE.equals(map(account.get("funding")).get("permissionRequired")));
    }
    @Test void lateResponseCannotPublishAfterPauseAndResumeRevokesItsFundingLease(){
        discover();doAnswer(i->{ads.pause(fixture.owner,"100001",false);ads.pause(fixture.owner,"100001",true);return response(i.getArgument(0));}).when(http).getExact(any(),anyMap());
        run();assertEquals(0,count("finance_ad_bc_costs"));assertEquals(0,count("finance_ad_bc_stage"));
    }
    @Test void failedPageKeepsProgressAndOtherAdvertisingStreamsUntouched(){
        pages=2;discover();run();String cp=fixture.db.queryForObject("SELECT checkpoint_json FROM finance_ad_bc_sync",String.class);
        failSecondPage=true;run();assertEquals("ERROR",state());assertEquals(cp,fixture.db.queryForObject("SELECT checkpoint_json FROM finance_ad_bc_sync",String.class));
        assertEquals(1,count("finance_ad_bc_stage"));assertEquals(0,count("finance_ad_bc_costs"));assertEquals(0,count("finance_ad_streams"));
        failSecondPage=false;run();assertEquals(2,count("finance_ad_bc_costs"));
    }
    @Test void conflictingCrossBcValuesNeverPublishOrMultiplyTheAccountTotal(){
        twoBcs=true;conflictingBc=true;discover();run();run();assertEquals("ERROR",state());assertEquals(0,count("finance_ad_bc_costs"));
    }
    @Test void periodicDiscoveryWaitsForTheCurrentDaysCompletePagination(){
        pages=2;discover();run();fixture.db.update("UPDATE finance_ad_bc_sync SET discovery_next_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR)");
        run();assertEquals(2,count("finance_ad_bc_costs"));assertTrue(paths.getLast().endsWith("/bc/account/cost/get/"));
    }
    @Test void unreadableAccountCredentialsDoNotBlockAnotherAuthorizedAccount(){
        fixture.db.update("UPDATE finance_ad_accounts SET access_token_encrypted='invalid' WHERE advertiser_id='100001'");
        ads.funding.tick();assertEquals(1,paths.size());
        assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_ad_bc_sync WHERE status='NEEDS_AUTHORIZATION'",Integer.class));
        fixture.db.update("UPDATE finance_ad_bc_sync SET checkpoint_json=? WHERE status='SYNCING'",fixture.repo.write(Map.of("from",day.toString(),"until",day.toString())));
        run();assertEquals(1,count("finance_ad_bc_costs"));assertEquals("100002",fixture.db.queryForObject("SELECT advertiser_id FROM finance_ad_bc_costs",String.class));
    }
    @Test void anEmptyRefreshKeepsPreviouslyPublishedAccountCosts(){
        discover();run();assertEquals(2,count("finance_ad_bc_costs"));
        fixture.db.update("UPDATE finance_ad_bc_sync SET checkpoint_json=?",fixture.repo.write(Map.of("from",day.toString(),"until",day.toString())));
        emptyCost=true;run();assertEquals("ERROR",state());assertEquals(2,count("finance_ad_bc_costs"));
        assertEquals(0,decimal(fixture.db.queryForObject("SELECT SUM(amount) FROM finance_ad_bc_costs",String.class)).compareTo(decimal("14")));
        assertTrue(fixture.db.queryForObject("SELECT error_message FROM finance_ad_bc_sync",String.class).contains("保留"));
    }
    @Test void costPermissionDenialHasItsOwnActionableStateAndDailyRetry(){
        discover();denied=true;run();assertEquals("NEEDS_PERMISSION",state());
        assertTrue(fixture.db.queryForObject("SELECT error_message FROM finance_ad_bc_sync",String.class).contains("Ad Account Balance and Transaction"));
        assertTrue(fixture.db.queryForObject("SELECT next_at>DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 23 HOUR) FROM finance_ad_bc_sync",Boolean.class));
        assertEquals(0,count("finance_ad_bc_costs"));
    }
    @Test void sameTokenReauthorizationBetweenSelectionAndClaimCannotPublishOnlyTheSecondPage(){
        pages=2;discover();run();assertEquals(1,count("finance_ad_bc_stage"));
        var db=spy(fixture.db);var guardedRepo=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper());
        ads.stop();ads=new FinanceAds(guardedRepo,fixture.service,http,fixture.cipher);
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            var selected=call.callRealMethod();
            if(changed.compareAndSet(false,true))connect("100001","same-unit-ad-token");
            return selected;
        }).when(db).queryForList(startsWith("SELECT * FROM finance_ad_bc_sync WHERE organization_id=? AND credential_key=? AND next_at"),any(Object[].class));
        run();assertTrue(changed.get());assertTrue(paths.getLast().endsWith("/bc/get/"));
        assertEquals(0,count("finance_ad_bc_costs"));assertEquals(0,count("finance_ad_bc_stage"));
        run();assertEquals(0,count("finance_ad_bc_costs"));run();assertEquals(2,count("finance_ad_bc_costs"));
    }
    @Test void stagedFinancialNumbersPreserveAllTwentyFourDecimalDigits(){
        pages=2;costAmount="-123456789012345678.123456";discover();run();
        assertEquals("STRING",fixture.db.queryForObject("SELECT JSON_TYPE(JSON_EXTRACT(source_json,'$.amount')) FROM finance_ad_bc_stage",String.class));
        run();assertEquals(2,count("finance_ad_bc_costs"));
        assertEquals(decimal("123456789012345678.123456"),fixture.db.queryForObject("SELECT amount FROM finance_ad_bc_costs WHERE advertiser_id='100001'",java.math.BigDecimal.class));
    }
    @Test void permanentDedicatedShopRemovalCannotResurrectOldFundingOrDeleteAnotherAccountsSharedJob(){
        String shop=fixture.groupedShop(fixture.owner,"700101",fixture.service.saveGroup(fixture.owner,null,"Dedicated"));
        fixture.db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        fixture.db.update("UPDATE finance_ad_accounts SET shop_id=? WHERE advertiser_id='100001'",shop);
        discover();run();assertEquals(2,count("finance_ad_bc_costs"));
        String key=fixture.db.queryForObject("SELECT funding_credential_key FROM finance_ad_accounts WHERE advertiser_id='100002'",String.class);
        String run=UUID.randomUUID().toString();
        for(String id:List.of("100001","100002"))fixture.db.update("INSERT INTO finance_ad_bc_stage(organization_id,credential_key,run_id,bc_id,advertiser_id,record_date,currency,page_number,source_json) VALUES('org',?,?,'700001',?,'2026-10-01','PHP',1,'{}')",key,run,id);
        var f=new Filter(day,day,"","","PHP","day");
        fixture.service.removeShop(fixture.owner,shop);
        assertEquals(List.of("100002"),FinanceAdBcFunding.sources(fixture.repo,fixture.owner,f).stream().map(r->text(r.get("account_id"))).toList());
        fixture.finishDeletion(shop);
        assertEquals(1,count("finance_ad_bc_costs"));assertEquals(1,count("finance_ad_bc_stage"));assertEquals(1,count("finance_ad_bc_sync"));
        assertEquals("100002",fixture.db.queryForObject("SELECT advertiser_id FROM finance_ad_bc_stage",String.class));
        connect("100001","same-unit-ad-token");
        assertEquals(List.of("100002"),FinanceAdBcFunding.sources(fixture.repo,fixture.owner,f).stream().map(r->text(r.get("account_id"))).toList());
        assertEquals(1,count("finance_ad_bc_sync"));
    }
    @Test void pausingTheCredentialGroupBetweenSelectionAndClaimPreventsTheDiscoveryRequest(){
        var db=spy(fixture.db);var guardedRepo=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper());
        ads.stop();ads=new FinanceAds(guardedRepo,fixture.service,http,fixture.cipher);
        var paused=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            var selected=call.callRealMethod();
            if(paused.compareAndSet(false,true)){ads.pause(fixture.owner,"100001",false);ads.pause(fixture.owner,"100002",false);}
            return selected;
        }).when(db).queryForList(startsWith("SELECT * FROM finance_ad_bc_sync WHERE organization_id=? AND credential_key=? AND next_at"),any(Object[].class));
        ads.funding.tick();assertTrue(paused.get());assertTrue(paths.isEmpty());assertEquals(0,count("finance_ad_bc_costs"));
        assertTrue(ads.list(fixture.owner).stream().allMatch(a->"PAUSED".equals(map(a.get("funding")).get("status"))));
    }
}
