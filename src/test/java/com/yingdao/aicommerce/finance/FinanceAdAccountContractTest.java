package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The simulated provider enforces TikTok's documented /advertiser/info/ contract. */
class FinanceAdAccountContractTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final ObjectMapper json=new ObjectMapper();
    final FinanceRepository repo=spy(new FinanceRepository(db,json));
    final MarketplaceHttp http=mock(MarketplaceHttp.class);
    final FinanceService service=mock(FinanceService.class);
    final ShopCredentialCipher cipher=new ShopCredentialCipher("unit-only-key-not-production-1234567890");
    final FinanceAds ads=new FinanceAds(repo,service,http,cipher);
    final List<Map<String,Object>> saved=new ArrayList<>();
    final List<List<String>> requestedFields=new ArrayList<>();
    final String accountId="1234567890123456789",accountName="广告账户 · 官方名称";

    void providerAndStorage() {
        doAnswer(call->((Supplier<?>)call.getArgument(0)).get()).when(repo).transaction(any());
        when(http.get(any(),anyMap())).thenAnswer(call->{
            URI uri=call.getArgument(0);assertEquals("/open_api/v1.3/advertiser/info/",uri.getPath());
            var params=new HashMap<String,String>();
            for(String part:uri.getRawQuery().split("&")){String[] pair=part.split("=",2);params.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}
            List<String> fields=json.readValue(params.get("fields"),List.class);requestedFields.add(fields);
            // Independent literal contract from the official Get ad account details page.
            // advertiser_name belongs to other responses and is not a valid detail field.
            if(!Set.of("advertiser_id","name","currency","timezone").containsAll(fields))
                return Map.of("code",40002,"message","Unsupported advertiser detail field");
            assertEquals(List.of(accountId),json.readValue(params.get("advertiser_ids"),List.class));
            return Map.of("code",0,"data",Map.of("list",List.of(Map.of("advertiser_id",accountId,"name",accountName,"currency","PHP","timezone","Asia/Manila"))));
        });
        when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);
            if(sql.startsWith("SELECT checkpoint_json FROM finance_ad_bc_sync"))return List.of(new LinkedHashMap<String,Object>());
            return sql.startsWith("SELECT advertiser_id,name,shop_id,currency")?new ArrayList<>(saved):List.of();
        });
        when(db.update(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);
            if(sql.stripLeading().startsWith("INSERT INTO finance_ad_accounts")){
                Object[] args=Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);
                assertEquals("org",args[0]);assertEquals(accountId,args[2]);
                assertNotEquals("unit-only-access-token",args[6]);
                saved.add(Map.of("advertiser_id",args[2],"name",args[3],"shop_id",args[4],"currency",args[5],"timezone",args[9]));
            }
            return 1;
        });
    }
    @Test void directConnectionUsesSupportedFieldsAndPersistsTheOfficialName(){
        providerAndStorage();
        ads.connect(new Scope("org","owner"),accountId,"","unit-only-access-token","");
        assertEquals(1,saved.size());assertEquals(accountName,ads.list(companyScope("org")).getFirst().get("name"));
        assertEquals(List.of("advertiser_id","name","currency","timezone"),requestedFields.getFirst());
    }
    @Test void authorizationCallbackWithOfficialResponsePersistsAnAccountVisibleInTheListExactlyOnce(){
        providerAndStorage();
        var application=new FinanceAdApplicationSettings("12345678","unit-only-app-secret","");
        var context=Map.of("applicationSource","SERVER","revision",application.current().revision(),"advertiserId","","shopId","","gmvStoreId","");
        var session=Map.<String,Object>of("id","session","organization_id","org","user_id","owner","context_json",repo.write(context),"created_at",java.sql.Timestamp.from(Instant.now()));
        when(db.queryForList(startsWith("SELECT * FROM finance_authorization_sessions"),any(Object[].class))).thenReturn(List.of(session));
        var claimed=new AtomicBoolean();
        when(db.update(startsWith("UPDATE finance_authorization_sessions SET status='PROCESSING'"),eq("session"))).thenAnswer(call->claimed.compareAndSet(false,true)?1:0);
        when(http.postJson(any(),anyMap(),anyString())).thenReturn(Map.of("code",0,"data",Map.of("access_token","unit-only-access-token","advertiser_ids",List.of(accountId))));
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var settings=new ShopIntegrationSettings(Duration.ofHours(1),Duration.ofMinutes(5),URI.create("https://example.test"),URI.create("https://example.test"),false,"DRY_RUN");
        var redirect=mock(FinanceShopAuthorization.class);when(redirect.redirect(anyBoolean(),anyString())).thenAnswer(call->"https://example.test/?financeAuth="+((boolean)call.getArgument(0)?"success":"error")+"#finance-shops");
        var authorization=new FinanceAdAuthorization(repo,FinanceTest.classProxy(ads),http,cipher,settings,redirect,manager,application);
        var callback=new FinanceAdAuthorizationCallback(authorization);
        assertTrue(callback.callback(Map.of("state","finance_ads_unit-only-state","auth_code","unit-only-code")).getHeaders().getLocation().toString().contains("financeAuth=success"));
        assertEquals(1,saved.size());assertEquals(accountName,ads.list(companyScope("org")).getFirst().get("name"));
        assertTrue(callback.callback(Map.of("state","finance_ads_unit-only-state","auth_code","unit-only-code")).getHeaders().getLocation().toString().contains("financeAuth=error"));
        assertEquals(1,saved.size());verify(http,times(1)).postJson(any(),anyMap(),anyString());
    }
}
