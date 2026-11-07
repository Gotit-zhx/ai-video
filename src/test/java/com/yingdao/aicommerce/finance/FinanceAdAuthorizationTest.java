package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceAdAuthorizationTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final ObjectMapper json=new ObjectMapper();
    final FinanceRepository repo=new FinanceRepository(db,json);
    final FinanceAds ads=mock(FinanceAds.class);
    final MarketplaceHttp http=mock(MarketplaceHttp.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final SimpleTransactionStatus transaction=new SimpleTransactionStatus();
    final FinanceShopAuthorization shopAuth=mock(FinanceShopAuthorization.class);
    final FinanceAdApplicationSettings app=new FinanceAdApplicationSettings("12345678","unit-only-private-secret","");
    final String state="finance_ads_unit-only-state";

    FinanceAdAuthorization authorization(){
        var settings=new ShopIntegrationSettings(Duration.ofHours(1),Duration.ofMinutes(5),URI.create("https://example.test"),URI.create("https://example.test"),false,"DRY_RUN");
        var context=Map.of("applicationSource","SERVER","revision",app.current().revision(),"advertiserId","","shopId","","gmvStoreId","");
        var session=Map.<String,Object>of("id","session","organization_id","org","user_id","owner","context_json",repo.write(context),"created_at",java.sql.Timestamp.from(Instant.now()));
        when(db.queryForList(startsWith("SELECT * FROM finance_authorization_sessions"),any(Object[].class))).thenReturn(List.of(session));
        when(db.update(startsWith("UPDATE finance_authorization_sessions SET status='PROCESSING'"),eq("session"))).thenReturn(1);
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(shopAuth.redirect(anyBoolean(),anyString())).thenAnswer(call->call.getArgument(0)+":"+call.getArgument(1));
        return new FinanceAdAuthorization(repo,ads,http,mock(ShopCredentialCipher.class),settings,shopAuth,manager,app);
    }
    void grant(){
        when(http.postJson(any(),anyMap(),anyString())).thenReturn(Map.of("code",0,"data",Map.of("access_token","unit-only-access-token","advertiser_ids",List.of("100001"))));
        when(ads.get(eq("/advertiser/info/"),anyString(),anyMap())).thenReturn(Map.of("list",List.of(Map.of("advertiser_id","100001","name","Unit","currency","PHP","timezone","Asia/Manila"))));
    }
    @Test void connectionFailureHasActionableRedactedReceiptAndNeverPersistsAnAccountOrReplaysGrant(){
        var auth=authorization();
        when(http.postJson(any(),anyMap(),anyString())).thenThrow(new MarketplaceApiException("secret=unit-only-private-secret auth_code=unit-only-code",new HttpTimeoutException("unit-only-private-token")));
        String result=auth.complete(state,"unit-only-code",null);
        assertTrue(result.startsWith("false:"));assertTrue(result.contains("[TOKEN_EXCHANGE][TIMEOUT]"));assertFalse(result.contains("unit-only"));
        verify(http,times(1)).postJson(any(),anyMap(),anyString());verifyNoInteractions(ads,manager);
        verify(db).update(contains("status='FAILED'"),argThat((String message)->message.contains("[TIMEOUT]")&&!message.contains("unit-only")),eq("session"));
    }
    @Test void successfulGrantPersistsBeforeReportingSuccess(){
        var auth=authorization();grant();
        assertTrue(auth.complete(state,"unit-only-code",null).startsWith("true:已连接 1 个广告账户"));
        var order=inOrder(ads,db,manager);
        order.verify(ads).connectVerified(any(),eq("100001"),eq(""),eq("unit-only-access-token"),eq(""),anyMap(),any());
        order.verify(db).update(contains("status='SUCCESS'"),eq("session"));order.verify(manager).commit(transaction);
        verify(db,never()).update(contains("status='FAILED'"),anyString(),anyString());
    }
    @Test void providerRejectionExposesOnlyNumericCodeAndNoProviderBody(){
        var auth=authorization();
        when(http.postJson(any(),anyMap(),anyString())).thenReturn(Map.of("code",40001,"message","private code=unit-only-code","data",Map.of("access_token","unit-only-token")));
        String result=auth.complete(state,"unit-only-code",null);
        assertTrue(result.contains("[PLATFORM]"));assertTrue(result.contains("40001"));assertFalse(result.contains("unit-only"));verifyNoInteractions(ads,manager);
    }
    @Test void missingAccountInformationNeverLooksLikeSuccessOrSavesPartialGrant(){
        var auth=authorization();grant();when(ads.get(anyString(),anyString(),anyMap())).thenReturn(Map.of("list",List.of()));
        assertTrue(auth.complete(state,"unit-only-code",null).contains("[ACCOUNT_INFO]"));
        verify(ads,never()).connectVerified(any(),anyString(),anyString(),anyString(),anyString(),anyMap(),any());verifyNoInteractions(manager);
    }
    @Test void accountLookupTimeoutRetainsItsStageAndNeverReplaysTheTokenExchange(){
        var auth=authorization();grant();
        when(ads.get(anyString(),anyString(),anyMap())).thenThrow(new MarketplaceApiException("unit-only-token",new HttpTimeoutException("unit-only-url")));
        String result=auth.complete(state,"unit-only-code",null);
        assertTrue(result.contains("[ACCOUNT_INFO][TIMEOUT]"));assertFalse(result.contains("unit-only"));
        verify(http,times(1)).postJson(any(),anyMap(),anyString());
        verify(ads,never()).connectVerified(any(),anyString(),anyString(),anyString(),anyString(),anyMap(),any());
        verifyNoInteractions(manager);
    }
    @Test void invalidCallbackDoesNotExchangeCodeAndReusedStateCannotBeRetried(){
        var auth=authorization();
        assertTrue(auth.complete(state,null,"unit-only-provider-secret").contains("[CALLBACK]"));verifyNoInteractions(http,ads,manager);
        when(db.update(startsWith("UPDATE finance_authorization_sessions SET status='PROCESSING'"),eq("session"))).thenReturn(0);
        assertThrows(RuntimeException.class,()->auth.complete(state,"unit-only-code",null));verifyNoInteractions(http,ads,manager);
    }
    @Test void persistenceFailureRollsBackBeforeReturningSafeReason(){
        var auth=authorization();grant();
        doThrow(new IllegalStateException("unit-only-jdbc-password")).when(ads).connectVerified(any(),anyString(),anyString(),anyString(),anyString(),anyMap(),any());
        String result=auth.complete(state,"unit-only-code",null);
        assertTrue(result.contains("[ACCOUNT_SAVE]"));assertFalse(result.contains("unit-only"));
        verify(manager).rollback(transaction);verify(manager,never()).commit(any());
        verify(db,never()).update(contains("status='SUCCESS'"),anyString());
    }
    @Test void diagnosticReasonsNeverEchoExceptionTextOrLoopOverCyclicCauses(){
        var causes=Map.<Throwable,String>of(new UnknownHostException("unit-only"),"DNS",new ConnectException("unit-only"),"NETWORK",
                new javax.net.ssl.SSLHandshakeException("unit-only"),"TLS",new HttpTimeoutException("unit-only"),"TIMEOUT");
        causes.forEach((cause,code)->{
            String message=FinanceAdFailures.message(FinanceAdFailures.Stage.TOKEN_EXCHANGE,new MarketplaceApiException("unit-only-private-body",cause));
            assertTrue(message.contains("["+code+"]"));assertFalse(message.contains("unit-only"));
        });
        var a=new RuntimeException("unit-only-a");var b=new RuntimeException("unit-only-b",a);a.initCause(b);
        assertTrue(FinanceAdFailures.message(FinanceAdFailures.Stage.ACCOUNT_INFO,a).contains("[ACCOUNT_INFO]"));
        assertFalse(new FinanceAdFailures.ProviderFailure("secret=unit-only").getMessage().contains("unit-only"));
    }
}
