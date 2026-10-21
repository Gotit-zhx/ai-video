package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.web.ShopAuthorizationController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs only against a disposable, empty MySQL schema provisioned by the release QA runner. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceConfigurationIntegrationTest {
    JdbcTemplate db;FinanceRepository repo;DataSourceTransactionManager tx;FinanceShopAuthorization auth;
    MarketplaceAccess.Client client;FinanceService service;FinanceFx fx;
    Scope owner=new Scope("org","u"),other=new Scope("org","other");
    final ShopCredentialCipher cipher=new ShopCredentialCipher("finance-test-key-not-a-production-secret-12345");
    final Filter filter=new Filter(LocalDate.of(2026,10,21),LocalDate.of(2026,10,21),"","","PHP","day");
    PlatformPrincipal principal(){return new PlatformPrincipal("u","org","heyi","admin","admin","",true,List.of("ADMIN"),List.of());}
    @BeforeAll void migrate()throws Exception{
        assertTrue(System.getenv("FINANCE_QA_JDBC").matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"),"Only disposable finance_qa schemas are permitted");
        var ds=new SingleConnectionDataSource(System.getenv("FINANCE_QA_JDBC"),System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD"),true);
        db=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);repo=new FinanceRepository(db,new ObjectMapper());
        db.execute("CREATE TABLE shops(id CHAR(36) PRIMARY KEY,marker VARCHAR(40))");
        db.execute("CREATE TABLE shop_credentials(id VARCHAR(36),secret VARCHAR(100))");
        db.update("INSERT INTO shops VALUES('legacy','commerce-unchanged')");db.update("INSERT INTO shop_credentials VALUES('legacy','commerce-secret-unchanged')");
        migration("V117__finance_bi.sql");
        db.update("INSERT INTO finance_shops(organization_id,user_id,shop_id,enabled,currency,timezone) VALUES('org','u','legacy',TRUE,'PHP','Asia/Manila')");
        migration("V118__finance_independent_configuration.sql");
        migration("V119__finance_ad_authorization.sql");
        assertEquals(0,db.queryForObject("SELECT enabled FROM finance_shops WHERE shop_id='legacy'",Integer.class));
        assertEquals("commerce-unchanged",db.queryForObject("SELECT marker FROM shops WHERE id='legacy'",String.class));
        assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials WHERE id='legacy'",String.class));
    }
    void migration(String name)throws Exception{
        String sql=Files.readString(Path.of("src/main/resources/db/migration/"+name)).replaceAll("(?m)^--.*$","");
        for(String statement:sql.split(";"))if(!statement.isBlank())db.execute(statement);
    }
    @BeforeEach void setup(){
        for(String table:List.of("finance_connections","finance_authorization_sessions","finance_records","finance_sync_states","finance_view_preferences","finance_ad_accounts","finance_ad_applications"))db.update("DELETE FROM "+table);
        client=mock(MarketplaceAccess.Client.class);when(client.platform()).thenReturn(MarketplacePlatform.SHOPEE);when(client.configured()).thenReturn(true);
        var settings=new ShopIntegrationSettings(Duration.ofMinutes(30),Duration.ofMinutes(5),URI.create("https://example.test"),URI.create("https://example.test"),false,"DRY_RUN");
        auth=new FinanceShopAuthorization(repo,new MarketplaceClientRegistry(List.of(client)),cipher,settings,tx);
        var policy=mock(OrganizationAccessPolicy.class);when(policy.isPlatformAdmin(any())).thenReturn(true);
        fx=spy(new FinanceFx(repo,new ObjectMapper()));doReturn(new FinanceFx.Quote("fixed",Instant.EPOCH,Map.of("PHP",new BigDecimal("0.125")))).when(fx).quote(any());
        service=FinanceTest.classProxy(new FinanceService(FinanceTest.classProxy(repo),policy,FinanceTest.classProxy(auth),fx));
    }
    MarketplaceAccess.TokenSet tokens(boolean expired){return new MarketplaceAccess.TokenSet("finance-access","finance-refresh",Instant.now().plusSeconds(expired?-1:3600),Instant.now().plusSeconds(86400),List.of("order","finance"));}
    String connect(Scope s,boolean expired){
        auth.persist(s,MarketplacePlatform.SHOPEE,new MarketplaceAccess.AuthorizationGrant(tokens(expired),List.of(new MarketplaceAccess.RemoteShop("10001",null,null,"LOCAL","测试财务店铺","PH","SHOP","10001"))));
        return text(service.shops(s).getFirst().get("id"));
    }
    void record(Scope s,String shop,String kind,String external,BigDecimal amount,Map<String,Object> raw){repo.save(s,new Entry(shop,"SHOPEE",kind,external,"order1","bill1","测试","SETTLED",filter.start(),"PHP",amount,null,null,null,null,"API",raw));}
    @Test void authorizationIsIndependentEncryptedScopedAndPausedUntilEnabled(){
        String id=connect(owner,false),second=connect(other,false);assertNotEquals(id,second);
        assertEquals(1,service.shops(owner).size());assertEquals(false,service.shops(owner).getFirst().get("enabled"));
        assertThrows(BusinessException.class,()->service.ownedShop(other,id));
        String secret=db.queryForObject("SELECT access_token_encrypted FROM finance_connections WHERE id=?",String.class,id);assertNotEquals("finance-access",secret);
        assertEquals("finance-access",cipher.decrypt(secret,"org","finance-shop:u:"+id));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(secret,"org","legacy"));
        service.configure(owner,id,true,"PHP","Asia/Manila");assertEquals(3,service.syncStates(owner).size());
        assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials WHERE id='legacy'",String.class));
    }
    @Test void legacyAndOtherUsersRecordsAreHiddenFromEveryReadPath(){
        String id=connect(owner,false);connect(other,false);
        for(String kind:List.of("ORDER","SETTLEMENT","BILL")){
            record(owner,id,kind,kind.equals("BILL")?"bill1":kind,BigDecimal.TEN,Map.of());
            record(owner,"legacy",kind,"old-"+kind,new BigDecimal("999"),Map.of());
            record(other,id,kind,"foreign-"+kind,new BigDecimal("999"),Map.of());
        }
        assertEquals(3,number(repo.details(owner,filter,null,1,50).get("total")));
        assertEquals(3,repo.grouped(owner,filter,"kind").size());
        var bills=repo.reconciliation(owner,filter);assertEquals(1,bills.size());assertEquals("MATCHED",bills.getFirst().get("result"));
        String hidden=db.queryForObject("SELECT id FROM finance_records WHERE shop_id='legacy' LIMIT 1",String.class);
        assertThrows(BusinessException.class,()->repo.detail(owner,hidden));
        service.removeShop(owner,id);assertEquals(0,number(repo.details(owner,filter,null,1,50).get("total")));assertTrue(repo.reconciliation(owner,filter).isEmpty());
        assertEquals(9,db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
    }
    @Test void safeExtraFieldsStayTypedAndRawBuyerDataNeverEscapes(){
        String id=connect(owner,false);
        record(owner,id,"ORDER","order",new BigDecimal("100"),Map.of("payment_method","CARD","create_time",1792533600L,"item_list",List.of(Map.of("item_name","Dress")),"buyer_username","private","recipient_address",Map.of("phone","private"),"access_token","private"));
        var row=rows(repo.details(owner,filter,"ORDER",1,50).get("items")).getFirst();
        assertEquals("CARD",row.get("payment_method"));assertEquals("Dress",row.get("item_names"));assertEquals(1,row.get("item_count"));assertNotNull(row.get("create_time"));
        assertFalse(repo.write(row).contains("private"));assertFalse(row.containsKey("raw_json"));assertFalse(row.containsKey("estimated_shipping"));
        assertFalse(repo.write(repo.detail(owner,text(row.get("id")))).contains("private"));
    }
    @Test void preferencesArePerUserPerTypeAndRejectUnknownPaths(){
        var fields=new FinanceFields(repo);
        fields.save(owner,"ORDER",List.of("external_id","amount"),List.of("external_id","payment_method"));
        assertEquals(List.of("external_id","amount"),fields.get(owner,"ORDER").visible());
        assertTrue(fields.get(other,"ORDER").visible().contains("record_date"));assertTrue(fields.get(owner,"REFUND").exported().contains("amount"));
        assertThrows(BusinessException.class,()->fields.save(owner,"ORDER",List.of("raw_json"),List.of("amount")));
        assertThrows(BusinessException.class,()->fields.save(owner,"ORDER",List.of("amount"),List.of("shopee_commission")));
        assertThrows(BusinessException.class,()->fields.save(owner,"ORDER",List.of("cny_amount"),List.of("amount")));
    }
    FinanceController controller(){return new FinanceController(service,repo,mock(FinanceImport.class),mock(FinanceAds.class));}
    @Test void csvAndXlsxHonorIndependentExportFieldsAndReconciliation(){
        String id=connect(owner,false);record(owner,id,"ORDER","00123456789012345678",new BigDecimal("100"),Map.of("payment_method","CARD"));
        record(owner,id,"BILL","bill1",BigDecimal.TEN,Map.of());record(owner,id,"SETTLEMENT","tx1",BigDecimal.TEN,Map.of());
        var fields=new FinanceFields(repo);fields.save(owner,"ORDER",List.of("external_id","amount"),List.of("external_id","payment_method","cny_amount"));
        fields.save(owner,"RECONCILIATION",List.of("external_id","result"),List.of("external_id","detail_amount","difference","result"));
        for(String format:List.of("csv","xlsx")){
            var q=Map.of("start","2026-10-21","end","2026-10-21","view","ORDER","format",format,"displayCurrency","CNY","fxSnapshot","fixed");
            var content=controller().export(q,principal()).getBody();var table=FinanceWorkbook.read(content,"export."+format);
            assertEquals(List.of("记录编号","支付方式","折算人民币"),table.getFirst());assertEquals(List.of("00123456789012345678","CARD","12.500000"),table.get(1));
        }
        var q=Map.of("start","2026-10-21","end","2026-10-21","view","RECONCILIATION","format","csv");
        var table=FinanceWorkbook.read(controller().export(q,principal()).getBody(),"rec.csv");
        assertEquals(List.of("记录编号","结算明细金额","差异","核对结果"),table.getFirst());assertEquals("MATCHED",table.get(1).getLast());assertEquals(2,table.size());
    }
    @Test void callbackConsumesOnlyItsOwnStateOnceAndRedirectsToFinance(){
        final String[] rawState={null};when(client.authorizationUrl(anyString(),anyString())).thenAnswer(i->{rawState[0]=i.getArgument(1);return "https://official.test/?state="+rawState[0];});
        auth.start("SHOPEE",null,principal());
        assertNotEquals(rawState[0],db.queryForObject("SELECT state_hash FROM finance_authorization_sessions",String.class));
        when(client.exchangeAuthorizationCode(eq("code"),anyMap())).thenReturn(new MarketplaceAccess.AuthorizationGrant(tokens(false),List.of(new MarketplaceAccess.RemoteShop("10001",null,null,"LOCAL","New finance","PH"))));
        var commerce=mock(ShopAuthorizationService.class);var callback=new ShopAuthorizationController(commerce,auth);
        var result=callback.callback("SHOPEE",Map.of("state",rawState[0],"code","code"));
        assertTrue(result.getHeaders().getLocation().toString().contains("financeAuth=success"));assertTrue(result.getHeaders().getLocation().toString().endsWith("#finance-shops"));
        assertTrue(callback.callback("SHOPEE",Map.of("state",rawState[0],"code","code")).getHeaders().getLocation().toString().contains("financeAuth=error"));
        verify(client,times(1)).exchangeAuthorizationCode(eq("code"),anyMap());verifyNoInteractions(commerce);
        assertEquals(1,service.shops(owner).size());assertTrue(service.shops(other).isEmpty());
    }
    @Test void expiredOrDifferentPlatformStateCannotExchangeCode(){
        final String[] state={null};when(client.authorizationUrl(anyString(),anyString())).thenAnswer(i->{state[0]=i.getArgument(1);return "https://official.test/";});auth.start("SHOPEE",null,principal());
        assertThrows(BusinessException.class,()->auth.complete("TIKTOK_SHOP",state[0],"code",Map.of(),null));
        db.update("UPDATE finance_authorization_sessions SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");
        assertThrows(BusinessException.class,()->auth.complete("SHOPEE",state[0],"code",Map.of(),null));verify(client,never()).exchangeAuthorizationCode(any(),anyMap());
    }
    @Test void tokenRefreshLocksAndRotatesFinanceGroupOnly(){
        auth.persist(owner,MarketplacePlatform.SHOPEE,new MarketplaceAccess.AuthorizationGrant(tokens(true),List.of(new MarketplaceAccess.RemoteShop("1","merchant",null,"LOCAL","One","PH","MERCHANT","merchant"),new MarketplaceAccess.RemoteShop("2","merchant",null,"LOCAL","Two","PH","MERCHANT","merchant"))));
        String id=text(service.shops(owner).getFirst().get("id"));
        var fresh=new MarketplaceAccess.TokenSet("rotated-access","rotated-refresh",Instant.now().plusSeconds(3600),Instant.now().plusSeconds(86400),List.of());when(client.refresh(any(),any())).thenReturn(fresh);
        var connection=new TransactionTemplate(tx).execute(status->auth.connection(owner,id));assertEquals(id,connection.shop().getId());assertEquals("rotated-access",connection.tokens().accessToken());
        for(var shop:service.shops(owner))assertEquals("rotated-access",new TransactionTemplate(tx).execute(status->auth.connection(owner,text(shop.get("id")))).tokens().accessToken());
        verify(client,times(1)).refresh(any(),any());assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials",String.class));
    }
    @Test void liveScreenRefreshesOrdersWithoutBypassingFailuresOrCheckpoints(){
        String id=connect(owner,false);service.configure(owner,id,true,"PHP","Asia/Manila");
        db.update("UPDATE finance_sync_states SET status='SUCCESS',last_success_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 MINUTE),next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE) WHERE shop_id=?",id);
        record(owner,id,"ORDER","o1",new BigDecimal("100"),Map.of("create_time",LocalDateTime.of(2026,10,21,10,0).atZone(ZoneId.of("Asia/Manila")).toEpochSecond()));
        var screen=service.screen(owner,filter,true,"fixed");
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_sync_states WHERE shop_id=? AND channel='ORDER'",String.class,id));
        assertEquals("SUCCESS",db.queryForObject("SELECT status FROM finance_sync_states WHERE shop_id=? AND channel='SETTLEMENT'",String.class,id));
        var hour=rows(screen.get("hourly")).get(10);assertEquals(new BigDecimal("12.500000"),hour.get("amount"));assertEquals(1L,hour.get("count"));assertEquals(1,rows(screen.get("recent")).size());
        db.update("UPDATE finance_sync_states SET status='ERROR',next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 20 MINUTE) WHERE channel='ORDER'");
        service.screen(owner,filter,false,null);assertEquals("ERROR",db.queryForObject("SELECT status FROM finance_sync_states WHERE channel='ORDER'",String.class));
        db.update("UPDATE finance_sync_states SET status='SYNCING',checkpoint_json='{\"cursor\":\"retain\"}' WHERE channel='ORDER'");service.screen(owner,filter,false,null);
        assertTrue(db.queryForObject("SELECT checkpoint_json FROM finance_sync_states WHERE channel='ORDER'",String.class).contains("retain"));
    }
    com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp adHttp;
    FinanceAdAuthorization adAuth(){
        adHttp=mock(com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp.class);
        var ads=new FinanceAds(FinanceTest.classProxy(repo),service,adHttp,cipher);
        var settings=new ShopIntegrationSettings(Duration.ofMinutes(30),Duration.ofMinutes(5),URI.create("https://example.test"),URI.create("https://example.test"),false,"DRY_RUN");
        return new FinanceAdAuthorization(FinanceTest.classProxy(repo),FinanceTest.classProxy(ads),adHttp,cipher,settings,auth,tx);
    }
    String adState(Map<String,Object> start){
        var query=URI.create(text(start.get("authorizationUrl"))).getRawQuery();
        return Arrays.stream(query.split("&")).filter(p->p.startsWith("state=")).map(p->java.net.URLDecoder.decode(p.substring(6),java.nio.charset.StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    void adGrant(List<String> ids,List<Map<String,Object>> accounts){
        when(adHttp.postJson(any(),anyMap(),anyString())).thenReturn(Map.of("code",0,"data",Map.of("access_token","verified-ad-access","advertiser_ids",ids)));
        when(adHttp.get(any(),anyMap())).thenReturn(Map.of("code",0,"data",Map.of("list",accounts)));
    }
    Map<String,Object> advertiser(String id){return Map.of("advertiser_id",id,"advertiser_name","Test ads "+id,"currency","PHP","timezone","Asia/Manila");}
    @Test void advertisingApplicationIsPrivateEncryptedAndDoesNotReuseShopCredentials(){
        var a=adAuth();assertThrows(BusinessException.class,()->a.start(owner,"","",""));
        a.configure(owner,"12345678","private-ad-application-secret");
        assertEquals(true,a.configuration(owner).get("configured"));assertEquals(false,a.configuration(other).get("configured"));
        assertFalse(repo.write(a.configuration(owner)).contains("private-ad"));
        String secret=db.queryForObject("SELECT secret_encrypted FROM finance_ad_applications",String.class);
        assertNotEquals("private-ad-application-secret",secret);
        assertEquals("private-ad-application-secret",cipher.decrypt(secret,"org","finance-ads-app:u"));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(secret,"org","finance-ads-app:other"));
        a.configure(owner,"12345678","");assertEquals(true,a.configuration(owner).get("configured"));
        assertThrows(BusinessException.class,()->a.configure(owner,"87654321",""));
        assertThrows(BusinessException.class,()->a.start(other,"","",""));
        assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials",String.class));
    }
    @Test void copiedAdAuthorizationLinkCompletesExactlyOnceAndConnectsScopedAccounts(){
        var a=adAuth();a.configure(owner,"12345678","private-ad-application-secret");
        var started=a.start(owner,"","","");String state=adState(started);
        assertTrue(text(started.get("authorizationUrl")).startsWith("https://business-api.tiktok.com/portal/auth?"));
        assertFalse(text(started.get("authorizationUrl")).contains("private-ad"));
        assertNotEquals(state,db.queryForObject("SELECT state_hash FROM finance_authorization_sessions",String.class));
        adGrant(List.of("100001","100002"),List.of(advertiser("100001"),advertiser("100002")));
        var callback=new FinanceAdAuthorizationCallback(a);
        var result=callback.callback(Map.of("state",state,"auth_code","one-time-code"));
        assertTrue(result.getHeaders().getLocation().toString().contains("financeAuth=success"));
        assertTrue(result.getHeaders().getLocation().toString().endsWith("#finance-shops"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts WHERE user_id='u'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts WHERE user_id='other'",Integer.class));
        String token=db.queryForObject("SELECT access_token_encrypted FROM finance_ad_accounts WHERE advertiser_id='100001'",String.class);
        assertEquals("verified-ad-access",cipher.decrypt(token,"org","finance:u:100001"));
        assertTrue(callback.callback(Map.of("state",state,"auth_code","one-time-code")).getHeaders().getLocation().toString().contains("financeAuth=error"));
        verify(adHttp,times(1)).postJson(any(),anyMap(),argThat(body->body.contains("\"app_id\":\"12345678\"")&&body.contains("\"auth_code\":\"one-time-code\"")&&body.contains("\"secret\":\"private-ad-application-secret\"")));
    }
    @Test void expiredCancelledAndShopStatesCannotExchangeAdvertisingCodes(){
        var a=adAuth();a.configure(owner,"12345678","private-ad-application-secret");
        String expired=adState(a.start(owner,"","",""));
        db.update("UPDATE finance_authorization_sessions SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");
        assertThrows(BusinessException.class,()->a.complete(expired,"code",null));
        String cancelled=adState(a.start(owner,"","",""));a.configure(owner,"12345678","");
        assertThrows(BusinessException.class,()->a.complete(cancelled,"code",null));
        assertThrows(BusinessException.class,()->a.complete("finance_other-shop-state","code",null));
        verifyNoInteractions(adHttp);
    }
    @Test void incompleteAdGrantRollsBackAllAccountsAndDoesNotLeakErrors(){
        var a=adAuth();a.configure(owner,"12345678","private-ad-application-secret");
        String state=adState(a.start(owner,"","",""));
        adGrant(List.of("100001","100002"),List.of(advertiser("100001"),Map.of("advertiser_id","100002","currency","invalid")));
        String result=a.complete(state,"code",null);
        assertTrue(result.contains("financeAuth=error"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts",Integer.class));
        assertEquals("FAILED",db.queryForObject("SELECT status FROM finance_authorization_sessions",String.class));
        String second=adState(a.start(owner,"","",""));
        String rejected=a.complete(second,null,"private-provider-secret");
        assertFalse(rejected.contains("private-provider-secret"));
        assertFalse(db.queryForObject("SELECT error_message FROM finance_authorization_sessions WHERE state_hash=?",String.class,FinanceShopAuthorization.hash(second)).contains("private"));
    }
    @Test void advertisingGrantMustIncludeRequestedAccount(){
        var a=adAuth();a.configure(owner,"12345678","private-ad-application-secret");
        String state=adState(a.start(owner,"100003","",""));
        adGrant(List.of("100001"),List.of(advertiser("100001")));
        assertTrue(a.complete(state,"code",null).contains("financeAuth=error"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts",Integer.class));
        assertThrows(BusinessException.class,()->a.start(owner,"","foreign-shop",""));
    }
}
