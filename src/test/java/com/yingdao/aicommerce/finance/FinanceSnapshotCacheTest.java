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
