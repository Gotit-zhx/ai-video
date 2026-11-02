package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSnapshotCacheTest {
    FinanceSnapshotCache.Key key(String user,boolean live){
        return new FinanceSnapshotCache.Key(new Scope("org",user),new Filter(LocalDate.of(2026,10,21),LocalDate.of(2026,10,21),"","","PHP","day"),false,"",live);
    }
    @Test void concurrentReadsShareOneComputation()throws Exception{
        var cache=new FinanceSnapshotCache();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var count=new AtomicInteger();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->cache.get(key("one",true),()->{count.incrementAndGet();started.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return Map.of("amount",123);}));
            assertTrue(started.await(2,TimeUnit.SECONDS));
            var others=new ArrayList<Future<Map<String,Object>>>();
            for(int i=0;i<12;i++)others.add(executor.submit(()->cache.get(key("one",true),()->{count.incrementAndGet();return Map.of("amount",999);})));
            release.countDown();assertEquals(123,first.get(3,TimeUnit.SECONDS).get("amount"));
            for(var f:others)assertEquals(123,f.get(3,TimeUnit.SECONDS).get("amount"));
            assertEquals(1,count.get());
        }
    }
    @Test void expiryIsolationAndInvalidationRetainFreshData(){
        var now=new AtomicLong(0);var cache=new FinanceSnapshotCache(now::get,5,4);var calls=new AtomicInteger();
        java.util.function.Supplier<Map<String,Object>> load=()->Map.of("call",calls.incrementAndGet());
        assertEquals(1,cache.get(key("one",false),load).get("call"));
        assertEquals(1,cache.get(key("one",false),load).get("call"));
        assertEquals(2,cache.get(key("two",false),load).get("call"));
        assertEquals(3,cache.get(key("one",true),load).get("call"));
        cache.invalidate(new Scope("org","one"));
        assertEquals(2,cache.get(key("two",false),load).get("call"));
        assertEquals(4,cache.get(key("one",false),load).get("call"));
        now.set(6);assertEquals(5,cache.get(key("two",false),load).get("call"));
    }
    @Test void findNeverStartsAnAbsentReadAndHonorsExpiryAndInvalidation(){
        var now=new AtomicLong();var cache=new FinanceSnapshotCache(now::get,5,4);
        assertNull(cache.find(key("one",false)));
        cache.get(key("one",false),()->Map.of("amount",1));
        assertEquals(1,cache.find(key("one",false)).get("amount"));
        assertNull(cache.find(key("two",false)));assertNull(cache.find(key("one",true)));
        now.set(6);assertNull(cache.find(key("one",false)));
        cache.get(key("one",false),()->Map.of("amount",2));cache.invalidateCompany("org");
        assertNull(cache.find(key("one",false)));
    }
    @Test void narrowingCompanyFactsPreservesUnknownCostsAndExactShopGroupMembership(){
        var day=LocalDate.of(2026,10,1);
        var shops=List.<Map<String,Object>>of(
                Map.of("id","ph","platform","TIKTOK_SHOP","group_id","baby"),
                Map.of("id","my","platform","SHOPEE","group_id","clothes"),
                Map.of("id","other","platform","SHOPEE"));
        var unknown=new HashMap<String,Object>(Map.of("shop_id","ph","order_count",2,"pending_count",1));unknown.put("amount_cny",null);
        var facts=Map.<String,Object>of("buckets",List.of(
                Map.of("shop_id","ph","amount",new BigDecimal("10.123456")),
                Map.of("shop_id","my","amount",99),Map.of("shop_id","other","amount",7),
                Map.of("shop_id","UNASSIGNED","amount",3)),
                "orders",List.of(unknown,Map.of("shop_id","my","amount_cny",50)),
                "reportingMonths",List.of(Map.of("orders",99)));
        var baby=FinanceService.narrowDashboardFacts(facts,new Filter(day,day,"","","","day","baby"),shops,List.of());
        assertEquals(1,rows(baby.get("buckets")).size());
        assertEquals(new BigDecimal("10.123456"),rows(baby.get("buckets")).getFirst().get("amount"));
        assertNull(rows(baby.get("orders")).getFirst().get("amount_cny"));
        assertEquals(1,rows(baby.get("orders")).getFirst().get("pending_count"));
        assertTrue(rows(baby.get("reportingMonths")).isEmpty());
        var mismatch=FinanceService.narrowDashboardFacts(facts,new Filter(day,day,"ph","","","day","clothes"),shops,List.of());
        assertTrue(rows(mismatch.get("buckets")).isEmpty());
        var ungrouped=FinanceService.narrowDashboardFacts(facts,new Filter(day,day,"","","","day","UNGROUPED"),shops,List.of());
        assertEquals(Set.of("other","UNASSIGNED"),rows(ungrouped.get("buckets")).stream().map(r->text(r.get("shop_id"))).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4,rows(facts.get("buckets")).size(),"projection must not change the company snapshot");
    }
    @Test void failedReadsDoNotPoisonRetriesAndCapacityIsBounded(){
        var cache=new FinanceSnapshotCache(()->0,10,2);
        assertThrows(IllegalStateException.class,()->cache.get(key("one",true),()->{throw new IllegalStateException("unavailable");}));
        assertEquals(1,cache.get(key("one",true),()->Map.of("value",1)).get("value"));
        cache.get(key("two",true),()->Map.of());cache.get(key("three",true),()->Map.of());
        assertEquals(2,cache.get(key("one",true),()->Map.of("value",2)).get("value"));
    }
    @Test void derivingTotalsRetainsCurrenciesAndUnknownAmounts(){
        var unknown=new HashMap<String,Object>(Map.of("kind","ORDER","currency","PHP","records",2L,"known_amounts",0L));unknown.put("amount",null);
        var totals=FinanceService.nativeTotals(List.of(
                Map.of("shop_id","one","kind","ORDER","currency","PHP","amount",new BigDecimal("100"),"records",1L,"known_amounts",1L),
                unknown,Map.of("shop_id","two","kind","ORDER","currency","THB","amount",new BigDecimal("200"),"records",1L,"known_amounts",1L),
                Map.of("kind","REFUND","currency","PHP","records",1L,"known_amounts",0L)));
        assertEquals(3,totals.size());
        assertEquals(new BigDecimal("100"),totals.getFirst().get("amount"));
        assertEquals(new BigDecimal("3"),totals.getFirst().get("records"));
        assertEquals(new BigDecimal("1"),totals.getFirst().get("known_amounts"));
        assertEquals("THB",totals.get(1).get("currency"));assertNull(totals.get(2).get("amount"));
    }
}
