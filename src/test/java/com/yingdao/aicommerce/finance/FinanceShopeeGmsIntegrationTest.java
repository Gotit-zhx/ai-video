package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceShopeeGmsIntegrationTest {
    final FinanceShopeeAdsIntegrationTest ads=new FinanceShopeeAdsIntegrationTest();
    FinanceShopeeGms gms;
    @BeforeAll void migrate()throws Exception{ads.migrate();}
    @AfterAll void close(){ads.close();}
    @BeforeEach void setup(){
        ads.setup();ads.authorize();gms=new FinanceShopeeGms(ads.fixture.repo,ads.config,ads.service);
        ads.fixture.db.update("INSERT INTO finance_shopee_gms_sync(organization_id,external_shop_id,checkpoint_json) VALUES('org','10001',?)",ads.fixture.repo.write(Map.of("from","2026-10-01","until","2026-10-03")));
        when(ads.client.shopPost(eq(FinanceShopeeGmsApi.DISCOVERY),any(),anyMap())).thenReturn(Map.of("response",Map.of("campaign_id",9001,"report",Map.of("expense",999))));
        report(List.of("9001"),Map.of("2026-10-01",2,"2026-10-02",0,"2026-10-03",3));
    }
    void due(){ads.fixture.db.update("UPDATE finance_api_pacing SET next_allowed_at=UTC_TIMESTAMP(6) WHERE platform='SHOPEE_ADS'");ads.fixture.db.update("UPDATE finance_shopee_gms_sync SET next_at=UTC_TIMESTAMP(6)");}
    void report(List<String> ids,Map<String,Object> amounts){
        doAnswer(call->{Map<String,Object> q=call.getArgument(2);var format=java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy");LocalDate start=LocalDate.parse(text(q.get("start_date")),format),end=LocalDate.parse(text(q.get("end_date")),format);
            return Map.of("response",Map.of("shop_id","10001","campaign_list",ids.stream().map(id->Map.of("campaign_id",id,"metrics_list",start.datesUntil(end.plusDays(1)).map(day->Map.of("date",format.format(day),"expense",amounts.getOrDefault(day.toString(),0))).toList())).toList()));
        }).when(ads.client).shopGetExact(eq(FinanceShopeeGmsApi.DAILY),any(),anyMap());
    }
    @Test void discoveryAndDailyAreSeparatePacedCallsAndOnlyDailyAmountsArePublished(){
        gms.tick();assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily",Integer.class));
        assertTrue(ads.fixture.db.queryForObject("SELECT next_allowed_at>UTC_TIMESTAMP(6) FROM finance_api_pacing WHERE platform='SHOPEE_ADS'",Boolean.class));
        gms.tick();verify(ads.client,never()).shopGetExact(eq(FinanceShopeeGmsApi.DAILY),any(),anyMap());
        due();gms.tick();
        assertEquals(new BigDecimal("5.000000"),ads.fixture.db.queryForObject("SELECT SUM(amount) FROM finance_shopee_gms_daily",BigDecimal.class));
        assertEquals("SUCCESS",ads.fixture.db.queryForObject("SELECT status FROM finance_shopee_gms_sync",String.class));
        assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
        assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_ad_daily",Integer.class));
        assertEquals(3,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE event_scope='DAY'",Integer.class));
        var original=ads.fixture.db.queryForMap("SELECT status,checkpoint_json,last_success_at,failures FROM finance_shopee_ad_grants");assertEquals("READY",original.get("status"));assertNull(original.get("checkpoint_json"));assertNull(original.get("last_success_at"));assertEquals(0,number(original.get("failures")));
    }
    @Test void incompleteRefreshDoesNotChangeFactsOrAdvanceCheckpoint(){
        gms.tick();due();gms.tick();var facts=ads.fixture.db.queryForList("SELECT * FROM finance_shopee_gms_daily ORDER BY record_date");
        ads.fixture.db.update("UPDATE finance_shopee_gms_sync SET checkpoint_json=?,next_at=UTC_TIMESTAMP(6)",ads.fixture.repo.write(Map.of("from","2026-10-01","until","2026-10-03")));
        due();gms.tick();String checkpoint=ads.fixture.db.queryForObject("SELECT checkpoint_json FROM finance_shopee_gms_sync",String.class);
        doReturn(Map.of("response",Map.of("shop_id","10001","campaign_list",List.of(Map.of("campaign_id",9001,"metrics_list",List.of(Map.of("date","01-10-2026","expense",777))))))).when(ads.client).shopGetExact(eq(FinanceShopeeGmsApi.DAILY),any(),anyMap());
        due();gms.tick();
        assertEquals(facts,ads.fixture.db.queryForList("SELECT * FROM finance_shopee_gms_daily ORDER BY record_date"));
        assertEquals(checkpoint,ads.fixture.db.queryForObject("SELECT checkpoint_json FROM finance_shopee_gms_sync",String.class));
        assertEquals("ERROR",ads.fixture.db.queryForObject("SELECT status FROM finance_shopee_gms_sync",String.class));
        assertNull(ads.fixture.db.queryForObject("SELECT lease_token FROM finance_shopee_ad_grants",String.class));
        assertNull(ads.fixture.db.queryForObject("SELECT lease_token FROM finance_shopee_ad_groups",String.class));
    }
    @Test void pausingDuringReadCannotPublishALateReply(){
        gms.tick();due();String cp=ads.fixture.db.queryForObject("SELECT checkpoint_json FROM finance_shopee_gms_sync",String.class);
        doAnswer(call->{ads.service.pause(ads.fixture.owner,"10001",false);return Map.of("response",Map.of("shop_id","10001","campaign_list",List.of(Map.of("campaign_id","9001","metrics_list",List.of(Map.of("date","01-10-2026","expense",1),Map.of("date","02-10-2026","expense",1),Map.of("date","03-10-2026","expense",1))))));}).when(ads.client).shopGetExact(eq(FinanceShopeeGmsApi.DAILY),any(),anyMap());
        gms.tick();assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily",Integer.class));assertEquals(cp,ads.fixture.db.queryForObject("SELECT checkpoint_json FROM finance_shopee_gms_sync",String.class));
        assertNull(ads.fixture.db.queryForObject("SELECT lease_token FROM finance_shopee_ad_grants",String.class));assertNull(ads.fixture.db.queryForObject("SELECT lease_token FROM finance_shopee_ad_groups",String.class));
    }
    @Test void newIdentifiedCampaignInvalidatesTheOldScopeAndRequestsHistoricalRecheck(){
        gms.tick();due();gms.tick();String oldRevision=ads.fixture.db.queryForObject("SELECT campaign_revision FROM finance_shopee_gms_sync",String.class);
        ads.fixture.db.update("DELETE FROM finance_query_events");
        ads.fixture.db.update("UPDATE finance_shopee_gms_sync SET checkpoint_json=?,next_at=UTC_TIMESTAMP(6)",ads.fixture.repo.write(Map.of("from","2026-11-01","until","2026-11-03")));
        when(ads.client.shopPost(eq(FinanceShopeeGmsApi.DISCOVERY),any(),anyMap())).thenReturn(Map.of("response",Map.of("campaign_id",9002,"report",Map.of("expense",0))));
        due();gms.tick();
        String current=ads.fixture.db.queryForObject("SELECT campaign_revision FROM finance_shopee_gms_sync",String.class);assertNotEquals(oldRevision,current);
        assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily d JOIN finance_shopee_gms_sync s USING(organization_id,external_shop_id) WHERE d.campaign_revision=s.campaign_revision",Integer.class));
        assertEquals(3,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE event_scope='DAY'",Integer.class));
        var cp=ads.fixture.repo.read(ads.fixture.db.queryForObject("SELECT checkpoint_json FROM finance_shopee_gms_sync",String.class));assertEquals("2026-10-01",cp.get("from"));
        assertEquals(Set.of("9001","9002"),new HashSet<>((List<?>)cp.get("campaignIds")));
        report(List.of("9001","9002"),Map.of("2026-10-01",2,"2026-10-02",0,"2026-10-03",3));
        for(int i=0;i<3;i++){due();gms.tick();}
        assertEquals(new BigDecimal("10.000000"),ads.fixture.db.queryForObject("SELECT SUM(amount) FROM finance_shopee_gms_daily WHERE campaign_revision=?",BigDecimal.class,current));
    }
    @Test void deletingTheGrantClearsGmsFactsAndInvalidatesCascadedDates(){
        gms.tick();due();gms.tick();ads.fixture.db.update("DELETE FROM finance_query_events");
        ads.fixture.db.update("DELETE FROM finance_shopee_ad_grants");
        assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily",Integer.class));
        assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_sync",Integer.class));
        assertEquals(3,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class));
    }
    @Test void newlyAuthorizedShopBootstrapsItsOwnCheckpointAndResumeMakesFailedGmsDue(){
        ads.fixture.db.update("DELETE FROM finance_shopee_gms_sync");gms.tick();
        var sync=ads.fixture.db.queryForMap("SELECT status,checkpoint_json FROM finance_shopee_gms_sync");
        assertEquals("SYNCING",sync.get("status"));assertEquals("DAILY",ads.fixture.repo.read(text(sync.get("checkpoint_json"))).get("phase"));
        ads.fixture.db.update("UPDATE finance_shopee_gms_sync SET status='NEEDS_AUTHORIZATION',next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY)");
        ads.service.pause(ads.fixture.owner,"10001",false);ads.service.pause(ads.fixture.owner,"10001",true);
        assertEquals("READY",ads.fixture.db.queryForObject("SELECT status FROM finance_shopee_gms_sync",String.class));
        assertTrue(ads.fixture.db.queryForObject("SELECT next_at<=UTC_TIMESTAMP(6) FROM finance_shopee_gms_sync",Boolean.class));
    }
    @Test void thirtyOneDaysAreReadWithoutLosingEdgesAndNewScopeAmountsReachAllTablesAndExport()throws Exception{
        ads.fixture.db.update("UPDATE finance_shopee_gms_sync SET checkpoint_json=?",ads.fixture.repo.write(Map.of("from","2026-10-01","until","2026-10-31")));
        var amounts=new HashMap<String,Object>();for(int i=1;i<=31;i++)amounts.put("2026-10-%02d".formatted(i),i);
        report(List.of("9001"),amounts);for(int i=0;i<4;i++){due();gms.tick();}
        assertEquals(31,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily",Integer.class));
        assertEquals(new BigDecimal("496.000000"),ads.fixture.db.queryForObject("SELECT SUM(amount) FROM finance_shopee_gms_daily",BigDecimal.class));
        for(int i=1;i<=31;i++)ads.fixture.db.update("INSERT INTO finance_shopee_ad_daily(organization_id,external_shop_id,source_key,record_date,currency,amount,source_json) VALUES('org','10001',?,?,'PHP',100,'{}')",hash("fixture"+i),"2026-10-%02d".formatted(i));
        var f=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","SHOPEE","PHP","day");var spend=new FinanceAdSpendService(ads.fixture.repo,ads.fixture.service);
        var report=spend.report(ads.fixture.owner,f,false,"","","");
        for(String table:List.of("totals","groups","shops")){
            var row=rows(report.get(table)).getFirst();assertEquals(new BigDecimal("2604.000000"),row.get("amount"));assertEquals(new BigDecimal("3100.000000"),row.get("all_shop_amount"));assertEquals(new BigDecimal("496.000000"),row.get("gms_amount"));
        }
        var cny=spend.report(ads.fixture.owner,f,true,"","","");assertEquals(new BigDecimal("325.500000"),rows(cny.get("totals")).getFirst().get("amount"));
        byte[] exported=new FinanceAdSpendController(spend,ads.fixture.controller()).export(Map.of("platform","SHOPEE","currency","PHP","start","2026-10-01","end","2026-10-31"),ads.fixture.principal()).getBody();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().matches("xl/worksheets/sheet[12]\\.xml")){String xml=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(xml.contains("2604"));assertTrue(xml.contains("3100"));assertTrue(xml.contains("496"));assertTrue(xml.contains("账单参考"));}}
        assertTrue(rows(spend.report(ads.fixture.other,f,false,"","","").get("items")).isEmpty());
        ads.fixture.db.update("UPDATE finance_shopee_gms_sync SET campaign_revision=?",hash("new-scope"));
        assertNull(rows(spend.report(ads.fixture.owner,f,false,"","","").get("totals")).getFirst().get("amount"));
        assertEquals(new BigDecimal("3100.000000"),ads.fixture.db.queryForObject("SELECT SUM(amount) FROM finance_shopee_ad_daily",BigDecimal.class));
    }
    @Test void noPermissionKeepsTheCheckpointAndUnknownFactsAndMasksProviderDetails(){
        String checkpoint=ads.fixture.db.queryForObject("SELECT checkpoint_json FROM finance_shopee_gms_sync",String.class);
        when(ads.client.shopPost(anyString(),any(),anyMap())).thenThrow(new com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException("ads.performance.error_ads_not_whitelisted private-token=synthetic"));
        gms.tick();var sync=ads.fixture.db.queryForMap("SELECT * FROM finance_shopee_gms_sync");assertEquals("NEEDS_PERMISSION",sync.get("status"));assertFalse(text(sync.get("error_message")).contains("synthetic"));assertEquals(checkpoint,sync.get("checkpoint_json"));assertNull(sync.get("last_success_at"));assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily",Integer.class));
    }
    @Test void discoveringTheHundredAndFirstCampaignInvalidatesOlderIncompleteScopeBeforeRejectingIt(){
        var ids=java.util.stream.IntStream.rangeClosed(1,100).mapToObj(Integer::toString).sorted().toList();String old=hash(String.join("\0",ids));
        ads.fixture.db.update("UPDATE finance_shopee_gms_sync SET campaign_ids_json=?,campaign_revision=?",ads.fixture.repo.write(ids),old);
        ads.fixture.db.update("INSERT INTO finance_shopee_gms_daily(organization_id,external_shop_id,record_date,currency,amount,campaign_revision,source_json) VALUES('org','10001','2026-10-01','PHP',100,?,'{}')",old);ads.fixture.db.update("DELETE FROM finance_query_events");
        gms.tick();var sync=ads.fixture.db.queryForMap("SELECT * FROM finance_shopee_gms_sync");assertNotEquals(old,sync.get("campaign_revision"));assertEquals("ERROR",sync.get("status"));assertTrue(text(sync.get("error_message")).contains("100"));
        assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily d JOIN finance_shopee_gms_sync s USING(organization_id,external_shop_id) WHERE d.campaign_revision=s.campaign_revision",Integer.class));
        assertEquals(1,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE event_scope='DAY'",Integer.class));
        verify(ads.client,never()).shopGetExact(eq(FinanceShopeeGmsApi.DAILY),any(),anyMap());
    }
    @Test void changedApplicationDuringDailyHttpCannotPublish(){
        gms.tick();due();doAnswer(call->{when(ads.config.current()).thenReturn(new FinanceShopeeAdsSettings.Application("1234567","changed",ads.client));return Map.of("response",Map.of("shop_id","10001","campaign_list",List.of(Map.of("campaign_id","9001","metrics_list",List.of(Map.of("date","01-10-2026","expense",1),Map.of("date","02-10-2026","expense",1),Map.of("date","03-10-2026","expense",1))))));}).when(ads.client).shopGetExact(eq(FinanceShopeeGmsApi.DAILY),any(),anyMap());
        gms.tick();assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily",Integer.class));assertNull(ads.fixture.db.queryForObject("SELECT lease_token FROM finance_shopee_ad_grants",String.class));
    }
    @Test void gmsRefreshSharesTheTokenGroupWithCpcAndPreservesSiblingRotationAfterPause(){
        ads.fixture.groupedShop(ads.fixture.owner,"10002",ads.fixture.db.queryForObject("SELECT group_id FROM finance_connections WHERE id=?",String.class,ads.shop));
        ads.fixture.db.update("INSERT INTO finance_shopee_ad_grants(organization_id,external_shop_id,user_id,partner_id,token_group_id,name,currency,timezone,authorization_subject_type,authorization_subject_id,access_token_encrypted) SELECT organization_id,'10002',user_id,partner_id,token_group_id,'Sibling','PHP',timezone,'SHOP','10002','old-sibling' FROM finance_shopee_ad_grants");
        ads.fixture.db.update("UPDATE finance_shopee_ad_grants SET access_token_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)");
        when(ads.client.refresh(any(),any())).thenAnswer(call->{ads.service.pause(ads.fixture.owner,"10001",false);ads.fixture.db.update("UPDATE finance_api_pacing SET next_allowed_at=UTC_TIMESTAMP(6) WHERE platform='SHOPEE_ADS'");ads.service.tick();return new com.yingdao.aicommerce.shop.application.MarketplaceAccess.TokenSet("new","rotated",Instant.now().plusSeconds(3600),null,List.of());});
        gms.tick();verify(ads.client,times(1)).refresh(any(),any());verify(ads.client,never()).shopPost(eq(FinanceShopeeGmsApi.DISCOVERY),any(),anyMap());
        var sibling=ads.fixture.db.queryForMap("SELECT user_id,refresh_token_encrypted FROM finance_shopee_ad_grants WHERE external_shop_id='10002'");assertEquals("rotated",ads.fixture.cipher.decrypt(text(sibling.get("refresh_token_encrypted")),"org","finance-shopee-ads:"+sibling.get("user_id")+":10002"));
        assertEquals(0,ads.fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_gms_daily",Integer.class));assertNull(ads.fixture.db.queryForObject("SELECT lease_token FROM finance_shopee_ad_groups",String.class));
    }
}
