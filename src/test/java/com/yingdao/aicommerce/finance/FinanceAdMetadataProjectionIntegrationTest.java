package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies the real V155 projection and Redis page key after V156 metadata DAY events. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceAdMetadataProjectionIntegrationTest extends FinanceConfigurationIntegrationTest {
    FinanceAds ads;FinanceAdMetadataSync sync;MarketplaceHttp http;boolean denied;
    @BeforeEach void provider(){
        denied=false;http=mock(MarketplaceHttp.class);ads=new FinanceAds(repo,service,http,cipher);sync=new FinanceAdMetadataSync(repo,ads);
        when(http.get(any(),anyMap())).thenAnswer(call->denied?Map.of("code",40001,"message","redacted"):
            Map.of("code",0,"data",Map.of("campaign_id","800001","campaign_name","Official GMV","store_id","700001","budget",900)));
    }
    @AfterEach void stopWorkers(){if(sync!=null)sync.stop();if(ads!=null)ads.stop();}

    @Test void metadataDayEventsRebuildOnlyTheirMonthAndChangeRedisProjectionKeys(){
        String port=System.getenv("HEYI_DESIGN_HTTP_REDIS_PORT");Assumptions.assumeTrue(port!=null&&!port.isBlank());
        var scope=new Scope("t1"+UUID.randomUUID().toString().replace("-",""),"task1");
        ads.connectVerified(scope,"100001","","unit-only-ad-access-token","",Map.of("advertiser_id","100001","name","Account","currency","USD","timezone","Asia/Manila"));
        repo.save(scope,new Entry("UNASSIGNED","TIKTOK_SHOP","AD_GMV","projection-ad","","","GMV","SUCCESS",LocalDate.of(2026,10,1),"USD",decimal("12.34"),null,null,null,null,"API",Map.of("advertiser_id","100001","dimensions",Map.of("campaign_id","800001"))));
        var spend=new FinanceAdSpendService(repo,service);ReflectionTestUtils.setField(spend,"metadataSync",sync);
        var projections=new FinanceQueryProjections(repo,service,spend);
        var october=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
        var november=new Filter(LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),"","","USD","day");
        projections.request(scope,october);projections.request(scope,november);drainAndBuild(projections);
        var initialOctober=projections.find(scope,october).orElseThrow();var initialNovember=projections.find(scope,november).orElseThrow();

        var redisConfig=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(port));String password=System.getenv("HEYI_DESIGN_HTTP_REDIS_PASSWORD");
        if(password!=null&&!password.isBlank())redisConfig.setPassword(RedisPassword.of(password));
        var factory=new LettuceConnectionFactory(redisConfig);factory.afterPropertiesSet();factory.start();
        var redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();var cache=new FinanceReadCache(redis,new ObjectMapper());
        String pattern=FinanceReadCache.prefix(companyScope(scope.organizationId()))+"*";
        try{
            delete(redis,pattern);
            var queries=new FinanceWorkspaceQueryService(repo,service,spend,projections,ads,cache);
            var selection=new FinanceWorkspaceQueryService.Selection("ads","AD",false,false,"",1,1,"","","");
            var initial=queries.query(scope,scope,october,selection);var original=item(initial);var initialKeys=keys(redis,pattern);

            denied=true;sync.tick();
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE organization_id=? AND source_table='finance_ad_campaign_jobs' AND record_date='2026-10-01'",Integer.class,scope.organizationId()));
            assertTrue(projections.find(scope,october).isEmpty());assertEquals(initialNovember.revision(),projections.find(scope,november).orElseThrow().revision());
            drainAndBuild(projections);var deniedOctober=projections.find(scope,october).orElseThrow();assertNotEquals(initialOctober.revision(),deniedOctober.revision());
            var authorization=queries.query(scope,scope,october,selection);var rejected=item(authorization);var deniedKeys=keys(redis,pattern);
            assertEquals("NEEDS_AUTHORIZATION",rejected.get("metadata_status"));assertNotEquals(initialKeys,deniedKeys,"Projection revision must select a different Redis page key");

            denied=false;db.update("UPDATE finance_ad_campaign_jobs SET next_at=UTC_TIMESTAMP(6) WHERE organization_id=?",scope.organizationId());sync.tick();
            assertTrue(db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE organization_id=? AND source_table='finance_ad_campaign_metadata' AND record_date='2026-10-01'",Integer.class,scope.organizationId())>0);
            assertTrue(projections.find(scope,october).isEmpty());assertEquals(initialNovember.revision(),projections.find(scope,november).orElseThrow().revision());
            drainAndBuild(projections);var successfulOctober=projections.find(scope,october).orElseThrow();assertNotEquals(deniedOctober.revision(),successfulOctober.revision());
            var success=queries.query(scope,scope,october,selection);var enriched=item(success);var successKeys=keys(redis,pattern);
            assertEquals("Official GMV",enriched.get("campaign_name"));assertEquals("SUCCESS",enriched.get("metadata_status"));assertNotEquals(deniedKeys,successKeys,"The successful metadata revision must select another Redis page key");
            for(String key:List.of("amount","fingerprint","shop_id","source_key")){assertEquals(original.get(key),rejected.get(key),key);assertEquals(original.get(key),enriched.get(key),key);}
        }finally{delete(redis,pattern);cache.stop();factory.destroy();}
    }
    private static Map<String,Object> item(Map<String,Object> result){return rows(map(result.get("adSpend")).get("items")).getFirst();}
    private static Set<String> keys(StringRedisTemplate redis,String pattern){var found=redis.keys(pattern);return found==null?Set.of():Set.copyOf(found);}
    private static void delete(StringRedisTemplate redis,String pattern){var found=redis.keys(pattern);if(found!=null&&!found.isEmpty())redis.delete(found);}
    private static void drainAndBuild(FinanceQueryProjections projections){for(int i=0;i<20&&projections.drainEvents()>0;i++){}for(int i=0;i<20&&projections.buildNext();i++){} }
}
