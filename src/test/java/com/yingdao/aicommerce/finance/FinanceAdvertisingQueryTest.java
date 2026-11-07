package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceAdvertisingQueryTest {
    @Test void advertisingFactsAreRetainedBeforeOperatingMarketingExclusions(){
        var fee=new LinkedHashMap<String,Object>(Map.of("shop_id","a","record_date","2026-10-09","kind","FEE","currency","PHP", "category","成交广告费","status","","records",1,"known_amounts",1,"amount",decimal("30")));
        var independent=new LinkedHashMap<>(fee);independent.put("kind","AD");independent.put("category","");independent.put("amount",decimal("100"));
        var removed=new LinkedHashMap<>(fee);var db=mock(JdbcTemplate.class);
        when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(call->
                ((String)call.getArgument(0)).contains("FROM finance_marketing_orders")?List.of(removed):List.of(fee,independent));
        var repo=spy(new FinanceRepository(db,new ObjectMapper()));var advertising=new ArrayList<Map<String,Object>>();
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get()).when(repo).readSnapshot(eq(180),any());
        var f=new Filter(LocalDate.of(2026,10,9),LocalDate.of(2026,10,9),"","","PHP","day");
        var operating=repo.dashboardBuckets(new Scope("org","reader"),f,false,advertising);
        assertEquals(1,operating.size());assertEquals("AD",operating.getFirst().get("kind"));
        var report=FinanceAdvertisingReport.build(advertising,List.of(Map.of("id","a","group_id","baby")),f,false,null,"day");
        assertEquals(0,decimal("130").compareTo(decimal(map(report.get("total")).get("amount"))));
        verify(db,times(2)).queryForList(anyString(),any(Object[].class));
        verify(repo).readSnapshot(eq(180),any());
    }
    @Test void sharedAdvertisingFactsFollowShopAndGroupProjection(){
        Map<String,Object> a=Map.of("shop_id","a","amount",10),b=Map.of("shop_id","b","amount",20),unassigned=Map.of("shop_id","UNASSIGNED","amount",30);
        var facts=Map.<String,Object>of("buckets",List.of(a,b,unassigned),"orders",List.of(),"advertising",List.of(a,b,unassigned));
        var shops=List.of(Map.<String,Object>of("id","a","group_id","baby"),Map.<String,Object>of("id","b","group_id","clothes"));
        var f=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day","baby");
        assertEquals(List.of(a),FinanceService.narrowDashboardFacts(facts,f,shops,List.of()).get("advertising"));
        var ungrouped=new Filter(f.start(),f.end(),"","","","day","UNGROUPED");
        assertEquals(List.of(unassigned),FinanceService.narrowDashboardFacts(facts,ungrouped,shops,List.of()).get("advertising"));
    }
    @Test void ledgerAdPagingUsesOneBoundedTenantScopeForCountAndRows(){
        var db=mock(JdbcTemplate.class);var queries=new ArrayList<String>();var args=new ArrayList<List<Object>>();
        when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenAnswer(call->{
            queries.add(call.getArgument(0));args.add(Arrays.asList(Arrays.copyOfRange(call.getArguments(),2,call.getArguments().length)));return 51L;
        });
        when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
            queries.add(call.getArgument(0));args.add(Arrays.asList(Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length)));return List.of();
        });
        var repo=new FinanceRepository(db,new ObjectMapper());
        var f=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"shop-a","SHOPEE","PHP","day","baby");
        var result=repo.advertisingFeeDetails(new Scope("company-a","reader"),f,2,50,"00123456789012345678");
        assertEquals(51L,result.get("total"));assertEquals(2,result.get("page"));assertEquals(2,queries.size());
        for(int i=0;i<queries.size();i++){
            assertTrue(queries.get(i).contains(FinanceAdvertisingReport.ledgerPredicate("finance_records")));
            for(String commission:List.of("affiliate_ads_commission_amount","tap_shop_ads_commission","达人广告佣金","合作伙伴店铺广告佣金"))
                assertTrue(queries.get(i).contains(commission),"count and paged rows must both include advertising commissions");
            assertTrue(queries.get(i).contains("organization_id=?"));assertFalse(queries.get(i).contains("company-a"));
            assertTrue(args.get(i).containsAll(List.of("company-a","shop-a","SHOPEE","PHP","baby","FEE","00123456789012345678")));
            assertTrue(args.get(i).contains(java.sql.Date.valueOf(f.start())));assertTrue(args.get(i).contains(java.sql.Date.valueOf(f.end())));
        }
        assertTrue(queries.get(1).contains("LIMIT ? OFFSET ?"));assertEquals(50L,args.get(1).getLast());
    }
    @Test void sourceSelectionCannotTurnOtherExportsIntoUnrestrictedFeeExports(){
        assertTrue(FinanceController.advertisingOnly(Map.of("advertisingOnly","true"),"FEE"));
        assertFalse(FinanceController.advertisingOnly(Map.of(),"FEE"));
        for(String kind:List.of("AD","AD_GMV","ALL","ORDER"))
            assertThrows(RuntimeException.class,()->FinanceController.advertisingOnly(Map.of("advertisingOnly","true"),kind));
    }
    @Test void unreportedAdMetricsStayUnknownWhileActualZeroRemainsZero(){
        assertNull(FinanceAds.metricCount(Map.of(),"clicks"));
        assertNull(FinanceAds.metricCount(Map.of("clicks",""),"clicks"));
        assertEquals(0L,FinanceAds.metricCount(Map.of("clicks","0"),"clicks"));
        assertEquals(123L,FinanceAds.metricCount(Map.of("clicks","123"),"clicks"));
        assertThrows(RuntimeException.class,()->FinanceAds.metricCount(Map.of("clicks","1.5"),"clicks"));
        assertThrows(RuntimeException.class,()->FinanceAds.metricCount(Map.of("clicks","-1"),"clicks"));
    }
}
