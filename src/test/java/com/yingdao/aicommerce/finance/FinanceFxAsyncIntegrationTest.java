package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transport and a disposable local schema; never calls a public provider. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceFxAsyncIntegrationTest {
    static final LocalDate KNOWN=LocalDate.of(2026,10,9), MISSING=LocalDate.of(2026,10,12);
    static final String ID="1234567890abcdef1234567890abcdef";
    JdbcTemplate db;FinanceRepository repo;
    @BeforeEach void schema()throws Exception{
        String url=System.getenv("FINANCE_QA_JDBC");assertTrue(url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"));
        db=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD")));
        db.execute("DROP TABLE IF EXISTS finance_fx_historical_snapshots");db.execute("DROP TABLE IF EXISTS finance_fx_historical_rates");
        String migration=Files.readString(Path.of("src/main/resources/db/migration/V151__finance_historical_fx.sql")).replaceAll("(?m)^--.*$","");
        for(String statement:migration.split(";"))if(!statement.isBlank())db.execute(statement);
        repo=new FinanceRepository(db,new ObjectMapper());
        var frozen=Map.of(KNOWN.toString(),Map.of("rateDate",KNOWN.toString(),"rates",Map.of("USD","7")));
        db.update("INSERT INTO finance_fx_historical_snapshots(id,provider,cutoff_date,created_at,payload_json) VALUES(?,'ECB',?,'2026-10-12',?)",ID,MISSING,repo.write(Map.of("days",frozen,"requestedDates",List.of(KNOWN.toString()))));
    }
    @Test void slowMissingDateDoesNotBlockColdReadOrConfirmedDateAndDoesNotMutateReturnedQuote()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{entered.countDown();await(release);return FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8");});
        try(history;var callers=Executors.newFixedThreadPool(2)){
            try{
                var pending=callers.submit(()->history.quote(ID,List.of(MISSING))).get(1,TimeUnit.SECONDS);
                assertNull(pending.convert("10","USD",MISSING));assertEquals("PARTIAL",pending.metadata().get("status"));
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                var known=callers.submit(()->history.quote(ID,List.of(KNOWN))).get(1,TimeUnit.SECONDS);
                assertEquals(decimal("70.000000"),known.convert("10","USD",KNOWN));
                release.countDown();
                var completed=eventually(()->history.quote(ID,List.of(MISSING)),q->q.rate("USD",MISSING)!=null);
                assertEquals(decimal("80.000000"),completed.convert("10","USD",MISSING));
                assertNull(pending.convert("10","USD",MISSING),"A response must retain one consistent known/unknown view while it is being calculated");
                pending.preload(List.of(MISSING));
                assertNull(pending.convert("10","USD",MISSING),"Repeated calculation preloads must retain the response's original missing view; a new Quote observes backfill");
                pending.preload(List.of(KNOWN.minusDays(1)));
                assertNull(pending.convert("10","USD",MISSING),"Loading an additional date must not refresh a previously attempted missing date halfway through a response");
            }finally{release.countDown();}
        }
    }
    @Test void coldMarketReturnsUnavailableWhileProviderRunsAndLaterReturnsRealTimestamp()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{entered.countDown();await(release);return FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8");});
        try(history;var callers=Executors.newSingleThreadExecutor()){
            try{
                var cold=callers.submit(history::market).get(1,TimeUnit.SECONDS);
                assertEquals("UNAVAILABLE",cold.get("status"));assertNull(cold.get("fetchedAt"));assertEquals(Map.of(),cold.get("rates"));
                assertTrue(entered.await(2,TimeUnit.SECONDS));release.countDown();
                var ready=eventually(history::market,m->"READY".equals(m.get("status")));
                assertEquals(MISSING.toString(),ready.get("rateDate"));assertNotNull(Instant.parse(text(ready.get("fetchedAt"))));
                assertEquals(BigDecimal.valueOf(8),map(ready.get("rates")).get("USD"));
            }finally{release.countDown();}
        }
    }
    @Test void rollbackDoesNotPublishRequestedCoverageOrLaunchProviderWork()throws Exception{
        var entered=new CountDownLatch(1);
        try(var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{entered.countDown();return FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8");})){
            var transaction=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
            transaction.execute(status->{history.quote(ID,List.of(MISSING));status.setRollbackOnly();return null;});
            assertFalse(entered.await(250,TimeUnit.MILLISECONDS),"Rolled back reads must not leave background provider writes");
            assertNull(history.quote(ID,List.of(KNOWN)).rate("USD",MISSING));
            assertFalse(text(db.queryForObject("SELECT payload_json FROM finance_fx_historical_snapshots WHERE id=?",String.class,ID)).contains(MISSING.toString()));
            transaction.execute(status->history.quote(ID,List.of(MISSING)));
            var completed=eventually(()->history.quote(ID,List.of(MISSING)),q->q.rate("USD",MISSING)!=null);
            assertEquals(decimal("80.000000"),completed.convert("10","USD",MISSING));
        }
    }
    @Test void independentDateRangesRunConcurrentlyAndNeverShareFrozenMappings()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        String other="abcdef1234567890abcdef1234567890";var older=LocalDate.of(2026,9,20);
        db.update("INSERT INTO finance_fx_historical_snapshots(id,provider,cutoff_date,created_at,payload_json) VALUES(?,'ECB',?,'2026-10-12',?)",other,MISSING,repo.write(Map.of("days",Map.of(),"requestedDates",List.of())));
        try(var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{
            calls.incrementAndGet();
            if(uri.getRawQuery().contains("to="+MISSING)){entered.countDown();await(release);return FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8");}
            return FinanceHistoricalFxTest.providerRows(older.minusDays(2).toString(),"6");
        })){
            try{
                history.quote(ID,List.of(MISSING));assertTrue(entered.await(2,TimeUnit.SECONDS));
                for(int i=0;i<25;i++)assertNull(history.quote(ID,List.of(MISSING)).rate("USD",MISSING));
                var independent=eventually(()->history.quote(other,List.of(older)),q->q.rate("USD",older)!=null);
                assertEquals(decimal("60.000000"),independent.convert("10","USD",older));assertNull(independent.rate("USD",MISSING));
                assertEquals(2,calls.get(),"Repeated missing reads deduplicate while a different range uses the other worker");
            }finally{release.countDown();}
            eventually(()->history.quote(ID,List.of(MISSING)),q->q.rate("USD",MISSING)!=null);
        }
    }
    @Test void sharedStoredRateRequiresItsVerifiedThroughBoundaryEvenWhileLatestIsSlow()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var sunday=KNOWN.plusDays(2);
        for(String currency:List.of("CNY","USD","PHP","THB","MYR"))db.update("INSERT INTO finance_fx_historical_rates VALUES('ECB',?,?,?,?,'2026-10-12',?)",KNOWN,currency,currency.equals("CNY")?BigDecimal.ONE:decimal("7"),KNOWN,"a".repeat(64));
        try(var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{entered.countDown();await(release);throw new IllegalStateException("offline");})){
            try{
                var unverified=history.quote(null,List.of(sunday));assertNull(unverified.rate("USD",sunday));assertTrue(entered.await(2,TimeUnit.SECONDS));
                db.update("UPDATE finance_fx_historical_rates SET valid_through_date=?",sunday);
                var verified=history.quote(null,List.of(sunday));assertEquals(decimal("70.000000"),verified.convert("10","USD",sunday));
                assertNull(unverified.rate("USD",sunday),"Updating shared coverage does not mutate an earlier response");
            }finally{release.countDown();}
        }
    }
    @Test void missingAndUnavailableResponsesRetryAfterCooldownWithoutInventingZeroRates()throws Exception{
        var clock=new MutableClock();var calls=new AtomicInteger();
        try(var jobs=new FinanceFxRefreshes(1,4,clock,Duration.ofSeconds(30));var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{
            int attempt=calls.incrementAndGet();if(attempt==1)throw new IllegalStateException("offline");
            return attempt==2?"[]":FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8");
        },clock,jobs)){
            for(int attempt=1;attempt<=3;attempt++){
                var quote=history.quote(ID,List.of(MISSING));drain(jobs,"history-"+attempt);
                assertNull(quote.convert("10","USD",MISSING));assertEquals(decimal("0.000000"),quote.convert("0","USD",MISSING));
                if(attempt<3){
                    assertNull(history.quote(ID,List.of(MISSING)).rate("USD",MISSING));assertEquals(attempt,calls.get());
                    clock.advance(30);
                }
            }
            var ready=history.quote(ID,List.of(MISSING));assertEquals(decimal("80.000000"),ready.convert("10","USD",MISSING));assertEquals(3,calls.get());
        }
    }
    @Test void marketFreshnessExpiryAndFailedRefreshKeepTheActualFetchTime()throws Exception{
        var clock=new MutableClock();var calls=new AtomicInteger();
        try(var jobs=new FinanceFxRefreshes(1,4,clock,Duration.ofSeconds(30));var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{
            int attempt=calls.incrementAndGet();if(attempt==2)throw new IllegalStateException("offline");
            return FinanceHistoricalFxTest.providerRows(MISSING.toString(),attempt==1?"8":"9");
        },clock,jobs)){
            assertEquals("UNAVAILABLE",history.market().get("status"));drain(jobs,"market-1");
            var first=history.market();assertEquals("READY",first.get("status"));String fetched=text(first.get("fetchedAt"));
            clock.advance(899);assertEquals("READY",history.market().get("status"));assertEquals(1,calls.get());
            clock.advance(1);var expired=history.market();assertEquals("STALE",expired.get("status"));assertEquals(fetched,expired.get("fetchedAt"));
            drain(jobs,"market-2");assertEquals("STALE",history.market().get("status"));assertEquals(fetched,history.market().get("fetchedAt"));assertEquals(2,calls.get());
            clock.advance(30);assertEquals("STALE",history.market().get("status"));drain(jobs,"market-3");
            var refreshed=history.market();assertEquals("READY",refreshed.get("status"));assertNotEquals(fetched,refreshed.get("fetchedAt"));assertEquals(decimal("9"),map(refreshed.get("rates")).get("USD"));
            assertEquals(0,decimal("8").compareTo(db.queryForObject("SELECT rate_cny FROM finance_fx_historical_rates WHERE rate_date=? AND currency='USD'",BigDecimal.class,MISSING)),"Revised current market cannot overwrite an immutable historical publication");
        }
    }
    static void drain(FinanceFxRefreshes jobs,String key)throws Exception{
        var done=new CountDownLatch(1);assertTrue(jobs.submit("test-barrier-"+key,done::countDown));assertTrue(done.await(3,TimeUnit.SECONDS));
    }
    static final class MutableClock extends Clock{
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-12T00:00:00Z"));
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}
        void advance(long seconds){now.updateAndGet(value->value.plusSeconds(seconds));}
    }
    static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test release timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    static <T>T eventually(java.util.function.Supplier<T> read,java.util.function.Predicate<T> ready)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);T value;
        do{value=read.get();if(ready.test(value))return value;Thread.sleep(10);}while(System.nanoTime()<end);
        fail("Background FX result did not become visible: "+value);return value;
    }
}
