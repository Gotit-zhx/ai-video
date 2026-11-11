package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.infrastructure.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceShopeeAdsIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    FinanceShopeeAds service;ShopeeOfficialClient client;FinanceShopeeAdsSettings config;String shop,lastState;
    @BeforeAll void migrate()throws Exception{fixture.migrate();fixture.migration("V150__finance_ad_spend_workspace.sql");}
    @AfterAll void close(){fixture.closeDataSource();}
    @BeforeEach void setup(){
        fixture.setup();client=mock(ShopeeOfficialClient.class);config=mock(FinanceShopeeAdsSettings.class);
        when(config.current()).thenReturn(new FinanceShopeeAdsSettings.Application("1234567","revision",client));
        var settings=new ShopIntegrationSettings(Duration.ofMinutes(30),Duration.ofMinutes(5),URI.create("https://example.test"),URI.create("https://example.test"),false,"DRY_RUN");
        service=new FinanceShopeeAds(fixture.repo,fixture.cipher,config,settings);
        shop=fixture.groupedShop(fixture.owner,"10001",fixture.service.saveGroup(fixture.owner,null,"ShopeeGroup"));
        when(client.exchangeAuthorizationCode(eq("code"),anyMap())).thenReturn(new MarketplaceAccess.AuthorizationGrant(
            new MarketplaceAccess.TokenSet("ads-only-access","ads-only-refresh",Instant.now().plusSeconds(3600),null,List.of()),
            List.of(new MarketplaceAccess.RemoteShop("10001",null,null,"SELLER","Shop","PH","SHOP","10001"))));
    }
    void authorize(){
        when(client.authorizationUrl(anyString(),anyString())).thenAnswer(c->"https://open.shopee.com/auth?state="+c.getArgument(1));
        String url=text(service.start(fixture.owner).get("authorizationUrl"));String state=url.substring(url.indexOf("state=")+6);lastState=state;
        assertTrue(service.complete(state,"code",Map.of("shop_id","10001"),null).contains("financeAuth=success"));
    }
    Map<String,Object> daily(String day,Object expense){return Map.of("date",day,"expense",expense);}
    void window(){fixture.db.update("UPDATE finance_api_pacing SET next_allowed_at=UTC_TIMESTAMP(6) WHERE platform='SHOPEE_ADS'");fixture.db.update("UPDATE finance_shopee_ad_grants SET checkpoint_json=?,next_at=UTC_TIMESTAMP(6)",fixture.repo.write(Map.of("from","2026-10-01","until","2026-10-07")));}
    @Test void independentGrantProducesShopAndGroupFactsWithoutChangingErpOrProfitLedger()throws Exception{
        String erp=fixture.db.queryForObject("SELECT access_token_encrypted FROM finance_connections WHERE id=?",String.class,shop);
        authorize();window();when(client.shopGetExact(eq(FinanceShopeeAdsApi.PATH),any(),anyMap())).thenReturn(Map.of("response",List.of(daily("01-10-2026","12.340001"),daily("02-10-2026",0))));service.tick();
        assertEquals(2,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_ad_daily",Integer.class));
        assertEquals(erp,fixture.db.queryForObject("SELECT access_token_encrypted FROM finance_connections WHERE id=?",String.class,shop));
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
        var filter=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","SHOPEE","PHP","day");
        var spend=new FinanceAdSpendService(fixture.repo,fixture.service);var report=spend.report(fixture.owner,filter,false,"","","");
        for(String key:List.of("groups","shops","totals")){var row=rows(report.get(key)).getFirst();assertEquals(0,new BigDecimal("12.340001").compareTo(decimal(row.get("amount"))));assertNull(row.get("cash_amount"));assertNull(row.get("net_amount"));}
        assertEquals(shop,rows(report.get("items")).getFirst().get("shop_id"));assertTrue(rows(spend.report(fixture.other,filter,false,"","","").get("items")).isEmpty());
        var controller=new FinanceAdSpendController(spend,fixture.controller());
        byte[] exported=controller.export(Map.of("platform","SHOPEE","currency","PHP","start","2026-10-01","end","2026-10-31"),fixture.principal()).getBody();
        var sheets=new HashMap<String,String>();try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().startsWith("xl/worksheets/sheet"))sheets.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        for(int i=1;i<=3;i++)assertTrue(sheets.get("xl/worksheets/sheet"+i+".xml").contains("12.340001"));
        assertTrue(sheets.get("xl/worksheets/sheet3.xml").contains("API_SHOPEE_CPC"));
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));

    }
    @Test void incompleteRefreshKeepsExistingWindowAndExplicitZeroCanUpdate(){
        authorize();window();when(client.shopGetExact(anyString(),any(),anyMap())).thenReturn(Map.of("response",List.of(daily("01-10-2026",5))));service.tick();
        window();when(client.shopGetExact(anyString(),any(),anyMap())).thenReturn(Map.of("response",List.of()));service.tick();
        assertEquals(new BigDecimal("5.000000"),fixture.db.queryForObject("SELECT amount FROM finance_shopee_ad_daily",BigDecimal.class));
        assertEquals("ERROR",fixture.db.queryForObject("SELECT status FROM finance_shopee_ad_grants",String.class));
        window();when(client.shopGetExact(anyString(),any(),anyMap())).thenReturn(Map.of("response",List.of(daily("01-10-2026",0))));service.tick();
        assertEquals(0,fixture.db.queryForObject("SELECT amount FROM finance_shopee_ad_daily",BigDecimal.class).signum());
    }
    @Test void pausedOrRemovedShopCannotPublishLateProviderReply(){
        authorize();window();when(client.shopGetExact(anyString(),any(),anyMap())).thenAnswer(c->{service.pause(fixture.owner,"10001",false);return Map.of("response",List.of(daily("01-10-2026",7)));});service.tick();
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_ad_daily",Integer.class));
        assertEquals(0,fixture.db.queryForObject("SELECT enabled FROM finance_shopee_ad_grants",Integer.class));
    }

    @Test void refreshDoesNotRestoreCredentialsRevokedByShopDeletionWhileHttpWasRunning(){
        authorize();window();String group=fixture.db.queryForObject("SELECT token_group_id FROM finance_shopee_ad_grants",String.class);
        fixture.db.update("INSERT INTO finance_shopee_ad_grants(organization_id,external_shop_id,user_id,partner_id,token_group_id,name,currency,timezone,authorization_subject_type,authorization_subject_id,access_token_encrypted) SELECT organization_id,'10002',user_id,partner_id,token_group_id,'Sibling','PHP',timezone,'SHOP','10002','old-sibling' FROM finance_shopee_ad_grants");
        fixture.db.update("UPDATE finance_shopee_ad_grants SET access_token_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE external_shop_id='10001'");
        when(client.refresh(any(),any())).thenAnswer(c->{fixture.db.update("UPDATE finance_shopee_ad_grants SET status='REMOVED',enabled=FALSE,access_token_encrypted='',refresh_token_encrypted=NULL WHERE external_shop_id='10002'");return new MarketplaceAccess.TokenSet("refreshed","rotated",Instant.now().plusSeconds(3600),null,List.of());});
        when(client.shopGetExact(anyString(),any(),anyMap())).thenReturn(Map.of("response",List.of()));service.tick();
        assertEquals("",fixture.db.queryForObject("SELECT access_token_encrypted FROM finance_shopee_ad_grants WHERE external_shop_id='10002'",String.class));
        assertNull(fixture.db.queryForObject("SELECT refresh_token_encrypted FROM finance_shopee_ad_grants WHERE external_shop_id='10002'",String.class));
    }

    @Test void pausingCurrentShopDuringRotatingRefreshDoesNotLoseSiblingCredentials(){
        authorize();window();
        fixture.db.update("INSERT INTO finance_shopee_ad_grants(organization_id,external_shop_id,user_id,partner_id,token_group_id,name,currency,timezone,authorization_subject_type,authorization_subject_id,access_token_encrypted) SELECT organization_id,'10002',user_id,partner_id,token_group_id,'Sibling','PHP',timezone,'SHOP','10002','old-sibling' FROM finance_shopee_ad_grants");
        fixture.db.update("UPDATE finance_shopee_ad_grants SET access_token_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE external_shop_id='10001'");
        when(client.refresh(any(),any())).thenAnswer(c->{service.pause(fixture.owner,"10001",false);return new MarketplaceAccess.TokenSet("refreshed","rotated",Instant.now().plusSeconds(3600),null,List.of());});
        when(client.shopGetExact(anyString(),any(),anyMap())).thenReturn(Map.of("response",List.of(daily("01-10-2026",7))));service.tick();
        String user=fixture.db.queryForObject("SELECT user_id FROM finance_shopee_ad_grants WHERE external_shop_id='10002'",String.class);
        String encrypted=fixture.db.queryForObject("SELECT refresh_token_encrypted FROM finance_shopee_ad_grants WHERE external_shop_id='10002'",String.class);
        assertEquals("rotated",fixture.cipher.decrypt(encrypted,"org","finance-shopee-ads:"+user+":10002"));
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_ad_daily",Integer.class));
        assertNull(fixture.db.queryForObject("SELECT lease_token FROM finance_shopee_ad_groups",String.class));
    }


    @Test void siblingCannotRefreshWhileTheOriginalGroupRefreshIsStillRunningAfterPause(){
        authorize();window();fixture.groupedShop(fixture.owner,"10002",fixture.db.queryForObject("SELECT group_id FROM finance_connections WHERE id=?",String.class,shop));
        fixture.db.update("INSERT INTO finance_shopee_ad_grants(organization_id,external_shop_id,user_id,partner_id,token_group_id,name,currency,timezone,authorization_subject_type,authorization_subject_id,access_token_encrypted,access_token_expires_at) SELECT organization_id,'10002',user_id,partner_id,token_group_id,'Sibling','PHP',timezone,'SHOP','10002','old-sibling',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) FROM finance_shopee_ad_grants");
        fixture.db.update("UPDATE finance_shopee_ad_grants SET access_token_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)");
        when(client.refresh(any(),any())).thenAnswer(c->{service.pause(fixture.owner,"10001",false);fixture.db.update("UPDATE finance_api_pacing SET next_allowed_at=UTC_TIMESTAMP(6) WHERE platform='SHOPEE_ADS'");service.tick();return new MarketplaceAccess.TokenSet("new","rotated",Instant.now().plusSeconds(3600),null,List.of());});
        service.tick();verify(client,times(1)).refresh(any(),any());
    }
    @Test void transientPersistenceFailureRetriesTheRotatedTokensWithoutReplayingProviderRefresh(){
        authorize();window();fixture.db.update("UPDATE finance_shopee_ad_grants SET access_token_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)");
        var pending=new java.util.concurrent.atomic.AtomicBoolean(false);var attempted=new java.util.concurrent.atomic.AtomicBoolean(false);var db=spy(fixture.repo.jdbc());
        doAnswer(call->{String sql=call.getArgument(0);if(sql.startsWith("UPDATE finance_shopee_ad_grants SET access_token_encrypted")&&pending.get()&&attempted.compareAndSet(false,true))throw new org.springframework.dao.CannotAcquireLockException("synthetic transient lock");return call.callRealMethod();}).when(db).update(anyString(),any(Object[].class));
        var repository=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper());
        var urls=new ShopIntegrationSettings(Duration.ofMinutes(30),Duration.ofMinutes(5),URI.create("https://example.test"),URI.create("https://example.test"),false,"DRY_RUN");
        var retrying=new FinanceShopeeAds(repository,fixture.cipher,config,urls);
        when(client.refresh(any(),any())).thenAnswer(call->{pending.set(true);return new MarketplaceAccess.TokenSet("new","rotated",Instant.now().plusSeconds(3600),null,List.of());});
        when(client.shopGetExact(anyString(),any(),anyMap())).thenReturn(Map.of("response",List.of()));retrying.tick();
        assertTrue(attempted.get());verify(client,times(1)).refresh(any(),any());assertEquals("SUCCESS",fixture.db.queryForObject("SELECT status FROM finance_shopee_ad_grants",String.class));
    }
    @Test void consumedExpiredAndApplicationChangedStatesNeverExchangeAnotherCode(){
        authorize();assertThrows(RuntimeException.class,()->service.complete(lastState,"code",Map.of("shop_id","10001"),null));
        when(client.authorizationUrl(anyString(),anyString())).thenAnswer(c->"https://open.shopee.com/auth?state="+c.getArgument(1));
        String link=text(service.start(fixture.owner).get("authorizationUrl")),expired=link.substring(link.indexOf("state=")+6);
        fixture.db.update("UPDATE finance_authorization_sessions SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE state_hash=?",hash(expired));
        assertThrows(RuntimeException.class,()->service.complete(expired,"code",Map.of("shop_id","10001"),null));
        link=text(service.start(fixture.owner).get("authorizationUrl"));String changed=link.substring(link.indexOf("state=")+6);
        when(config.current()).thenReturn(new FinanceShopeeAdsSettings.Application("7654321","changed",client));
        assertTrue(service.complete(changed,"code",Map.of("shop_id","10001"),null).contains("financeAuth=error"));
        verify(client,times(1)).exchangeAuthorizationCode(anyString(),anyMap());
    }
    @Test void providerPermissionAndRateLimitsKeepFactsAndBackOffWithoutLeakingProviderBodies(){
        authorize();window();when(client.shopGetExact(anyString(),any(),anyMap())).thenThrow(new MarketplaceApiException("HTTP 403 error_api_permission private-token=synthetic"));service.tick();
        var denied=fixture.db.queryForMap("SELECT status,error_message,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(6),next_at) wait FROM finance_shopee_ad_grants");
        assertEquals("NEEDS_PERMISSION",denied.get("status"));assertTrue(number(denied.get("wait"))>86000);assertFalse(text(denied.get("error_message")).contains("synthetic"));
        window();doThrow(new MarketplaceApiException("HTTP 429 ads.rate_limit.exceed_partner_api")).when(client).shopGetExact(anyString(),any(),anyMap());service.tick();
        assertEquals("RATE_LIMITED",fixture.db.queryForObject("SELECT status FROM finance_shopee_ad_grants",String.class));
        assertTrue(fixture.db.queryForObject("SELECT next_allowed_at>UTC_TIMESTAMP(6) FROM finance_api_pacing WHERE platform='SHOPEE_ADS'",Boolean.class));
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_ad_daily",Integer.class));
    }
    @Test void deletionClearsSeparateAdsGrantAndFactsWithoutRemovingSiblingOrErpData(){
        authorize();window();when(client.shopGetExact(anyString(),any(),anyMap())).thenReturn(Map.of("response",List.of(daily("01-10-2026",3))));service.tick();
        fixture.service.removeShop(fixture.owner,shop,"actor");
        var stopped=fixture.db.queryForMap("SELECT enabled,access_token_encrypted,refresh_token_encrypted,status FROM finance_shopee_ad_grants");
        assertEquals("REMOVED",stopped.get("status"));assertEquals("",stopped.get("access_token_encrypted"));assertNull(stopped.get("refresh_token_encrypted"));
        fixture.finishDeletion(shop);assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_ad_daily",Integer.class));assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_ad_grants",Integer.class));
        assertEquals("commerce-unchanged",fixture.db.queryForObject("SELECT marker FROM shops",String.class));
    }

    @Test void controllerRetainsReadOnlyOriginAndCompanyWriteGuards(){
        var policy=mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class);
        var financial=new FinanceService(fixture.repo,policy,fixture.auth,fixture.fx);
        var guard=new FinanceController(financial,fixture.repo,mock(FinanceImport.class),mock(FinanceAds.class));
        var controller=new FinanceShopeeAdsController(service,guard);
        var viewer=new com.yingdao.aicommerce.auth.PlatformPrincipal("u","org","viewer","viewer","viewer","",true,List.of(),List.of("finance:view"));
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/finance/shopee-ads/authorize");
        assertThrows(RuntimeException.class,()->controller.authorize(viewer,request));
        request.addHeader("Sec-Fetch-Site","cross-site");assertThrows(RuntimeException.class,()->controller.authorize(fixture.principal(),request));
        assertThrows(RuntimeException.class,()->controller.start(new FinanceShopeeAdsController.Company("other-org"),fixture.principal()));
        verify(client,never()).authorizationUrl(anyString(),anyString());
    }
    @Test void callbackIsOneUseAndNeverSavesTokensForAnotherCompany(){
        authorize();assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_shopee_ad_grants WHERE organization_id='org'",Integer.class));
        assertTrue(rows(service.status(fixture.other).get("items")).isEmpty());
        String state="finance_shopee_ads_"+"a".repeat(43);assertThrows(RuntimeException.class,()->service.complete(state,"code",Map.of(),null));
        verify(client,times(1)).exchangeAuthorizationCode(anyString(),anyMap());
    }
}
