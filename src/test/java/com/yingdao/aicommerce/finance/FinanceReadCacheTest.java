package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceReadCacheTest {
    final Map<String,String> values=new ConcurrentHashMap<>();
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final ValueOperations<String,String> ops=mock(ValueOperations.class);
    final AtomicLong now=new AtomicLong(1_000);
    final Queue<Runnable> tasks=new ConcurrentLinkedQueue<>();
    final FinanceReadCache cache;
    FinanceReadCacheTest(){
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));
        when(ops.increment(anyString())).thenAnswer(i->{
            String key=i.getArgument(0);long v=Long.parseLong(values.getOrDefault(key,"0"))+1;values.put(key,Long.toString(v));return v;
        });
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(i->{
            List<String> keys=i.getArgument(1);values.put(keys.getFirst(),i.getArgument(2));return 1L;
        });
        cache=new FinanceReadCache(redis,new ObjectMapper(),now::get,tasks::add,false);
    }
    FinanceSnapshotCache.Key key(String user,String currency,boolean live){
        return new FinanceSnapshotCache.Key(new Scope("org",user),new Filter(LocalDate.of(2026,10,21),LocalDate.of(2026,10,21),"","",currency,"day"),false,"",live);
    }
    Map<String,Object> data(String amount){return Map.of("amount",new BigDecimal(amount),"filters",Map.of("currency","PHP"));}
    @Test void sharedHitPreservesMoneyAndResolvedCurrency(){
        cache.get(key("one","",false),()->data("123456789012345678.123456"));
        var hit=cache.get(key("one","PHP",false),()->{fail("resolved currency should reuse snapshot");return null;});
        assertEquals(new BigDecimal("123456789012345678.123456"),hit.get("amount"));
        assertEquals(false,map(hit.get("cache")).get("refreshing"));
    }
    @Test void staleReturnsImmediatelyAndSchedulesOnlyOneRefresh(){
        cache.get(key("one","PHP",false),()->data("1"));now.addAndGet(16_000);
        var calls=new AtomicInteger();
        for(int i=0;i<10;i++){
            var stale=cache.get(key("one","PHP",false),()->{calls.incrementAndGet();return data("2");});
            assertEquals(new BigDecimal("1"),decimal(stale.get("amount")));
            assertEquals(true,map(stale.get("cache")).get("refreshing"));
        }
        assertEquals(0,calls.get());assertEquals(1,tasks.size());tasks.remove().run();
        assertEquals(new BigDecimal("2"),decimal(cache.get(key("one","PHP",false),()->data("3")).get("amount")));
        assertEquals(1,calls.get());
    }
    @Test void expiredSnapshotsCannotMasqueradeAsCurrent(){
        cache.get(key("one","PHP",false),()->data("1"));now.addAndGet(61_000);
        assertThrows(IllegalStateException.class,()->cache.get(key("one","PHP",false),()->{throw new IllegalStateException("database unavailable");}));
        assertEquals(new BigDecimal("2"),decimal(cache.get(key("one","PHP",false),()->data("2")).get("amount")));
    }
    @Test void invalidationFencesAQueuedRefreshAndSeparatesUsersAndScreens(){
        cache.get(key("one","PHP",false),()->data("1"));
        cache.get(key("two","PHP",false),()->data("20"));
        cache.get(key("one","PHP",true),()->data("30"));
        now.addAndGet(16_000);
        cache.get(key("one","PHP",false),()->data("2"));
        cache.invalidate(new Scope("org","one"));tasks.remove().run();
        assertEquals(new BigDecimal("3"),decimal(cache.get(key("one","PHP",false),()->data("3")).get("amount")));
        assertEquals(new BigDecimal("20"),decimal(cache.get(key("two","PHP",false),()->data("21")).get("amount")));
        assertEquals(new BigDecimal("31"),decimal(cache.get(key("one","PHP",true),()->data("31")).get("amount")));
    }
    @Test void prewarmUsesBackgroundCapacityAndDoesNotBlockRequestThread(){
        var calls=new AtomicInteger();cache.prime(key("one","PHP",false),()->{calls.incrementAndGet();return data("4");});
        assertEquals(0,calls.get());tasks.remove().run();
        assertEquals(new BigDecimal("4"),decimal(cache.get(key("one","PHP",false),()->data("5")).get("amount")));
        cache.prime(key("one","PHP",false),()->data("6"));assertTrue(tasks.isEmpty());
    }
    @Test void redisUnavailableFallsBackToDatabaseWithoutInventingData(){
        when(ops.get(anyString())).thenThrow(new IllegalStateException("offline"));
        assertEquals(new BigDecimal("7"),cache.get(key("one","PHP",false),()->data("7")).get("amount"));
    }
    @Test void invalidationOutageBypassesOldSnapshotAndRetriesAfterRecovery(){
        cache.get(key("one","PHP",false),()->data("1"));
        var down=new AtomicBoolean(true);
        when(ops.increment(anyString())).thenAnswer(i->{
            if(down.get())throw new IllegalStateException("offline");
            String address=i.getArgument(0);values.put(address,"1");return 1L;
        });
        assertDoesNotThrow(()->cache.invalidate(new Scope("org","one")));
        assertEquals(new BigDecimal("2"),cache.get(key("one","PHP",false),()->data("2")).get("amount"));
        down.set(false);
        assertEquals(new BigDecimal("3"),cache.get(key("one","PHP",false),()->data("3")).get("amount"));
        assertEquals(new BigDecimal("3"),decimal(cache.get(key("one","PHP",false),()->data("4")).get("amount")));
    }
}
