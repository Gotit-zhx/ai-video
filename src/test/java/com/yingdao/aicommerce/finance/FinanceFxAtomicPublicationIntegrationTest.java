package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static com.yingdao.aicommerce.finance.FinanceFxAsyncIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic per-statement JDBC transport models drivers without rewritten atomic batches. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceFxAtomicPublicationIntegrationTest {
    JdbcTemplate db;
    @BeforeEach void schema()throws Exception{
        var fixture=new FinanceFxAsyncIntegrationTest();fixture.schema();db=fixture.db;
    }
    @Test void firstCurrencyIsInvisibleUntilTheWholePublicationCommits()throws Exception{
        var first=new CountDownLatch(1);var release=new CountDownLatch(1);var order=new CopyOnWriteArrayList<String>();
        var intercepted=new JdbcTemplate(db.getDataSource()){
            @Override public int[] batchUpdate(String sql,List<Object[]> rows){
                var result=new int[rows.size()];int at=0;
                for(var row:rows){order.add(text(row[1]));result[at]=super.update(sql,row);if(at++==0){first.countDown();await(release);}}
                return result;
            }
        };
        var repo=new FinanceRepository(intercepted,new ObjectMapper());
        try(var jobs=new FinanceFxRefreshes(1,4,Clock.systemUTC(),Duration.ZERO);var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Provider fetch must occur before the publication transaction");
            return FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8");
        },Clock.systemUTC(),jobs)){
            try{
                history.market();assertTrue(first.await(3,TimeUnit.SECONDS));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_fx_historical_rates",Integer.class),"Other connections must not see an incomplete publication");
            }finally{release.countDown();}
            drain(jobs,"atomic-visible");
            assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM finance_fx_historical_rates",Integer.class));
            assertEquals(List.of("CNY","MYR","PHP","THB","USD"),order,"All writers must acquire currency keys in the same order");
        }
    }
    @Test void midBatchFailureRollsBackNewCurrenciesAndExistingCoverageBeforeRetry()throws Exception{
        String original=FinanceHistoricalFxTest.providerRows(KNOWN.toString(),"7");
        FinanceFxHistory.parse(original,KNOWN).get(KNOWN).rates().forEach((currency,rate)->db.update("INSERT INTO finance_fx_historical_rates VALUES('ECB',?,?,?,?,CURRENT_TIMESTAMP,?)",KNOWN,currency,rate,KNOWN,hash(original)));
        var batches=new AtomicInteger();var attempts=new AtomicInteger();
        var intercepted=new JdbcTemplate(db.getDataSource()){
            @Override public int[] batchUpdate(String sql,List<Object[]> rows){
                int batch=batches.incrementAndGet(),at=0;var result=new int[rows.size()];
                for(var row:rows){result[at]=super.update(sql,row);if(++at==6&&batch==1)throw new DataAccessResourceFailureException("synthetic mid-batch failure");}
                return result;
            }
        };
        var clock=new MutableClock();var repo=new FinanceRepository(intercepted,new ObjectMapper());
        try(var jobs=new FinanceFxRefreshes(1,4,clock,Duration.ofSeconds(30));var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return combined(original,FinanceHistoricalFxTest.providerRows(MISSING.toString(),attempts.incrementAndGet()==1?"8":"9"));
        },clock,jobs)){
            history.market();drain(jobs,"atomic-failure");
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_fx_historical_rates WHERE rate_date=?",Integer.class,MISSING),"Failed batches must leave no new partial publication");
            assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM finance_fx_historical_rates WHERE rate_date=? AND valid_through_date=?",Integer.class,KNOWN,KNOWN),"Coverage updates must roll back with the failed publication");
            clock.advance(30);history.market();drain(jobs,"atomic-retry");
            assertPublication(MISSING,FinanceHistoricalFxTest.providerRows(MISSING.toString(),"9"));
            assertEquals(1,db.queryForObject("SELECT COUNT(DISTINCT source_sha256) FROM finance_fx_historical_rates WHERE rate_date=?",Integer.class,MISSING));
        }
    }
    @Test void concurrentMarketAndHistoryRevisionsFreezeOneCompletePayload()throws Exception{
        var first=new CountDownLatch(1);var release=new CountDownLatch(1);var second=new CountDownLatch(1);var secondDone=new CountDownLatch(1);var batches=new AtomicInteger();
        var intercepted=new JdbcTemplate(db.getDataSource()){
            @Override public int[] batchUpdate(String sql,List<Object[]> rows){
                int batch=batches.incrementAndGet(),at=0;var result=new int[rows.size()];
                try{
                    for(var row:rows){result[at]=super.update(sql,row);if(at++==0&&batch==1){first.countDown();await(release);}}
                    return result;
                }finally{if(batch==2)secondDone.countDown();}
            }
        };
        var repo=new FinanceRepository(intercepted,new ObjectMapper());
        try(var jobs=new FinanceFxRefreshes(2,4,Clock.systemUTC(),Duration.ofSeconds(30));var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if(uri.getRawQuery().contains("from="))second.countDown();
            return FinanceHistoricalFxTest.providerRows(MISSING.toString(),uri.getRawQuery().contains("from=")?"9":"8");
        },Clock.systemUTC(),jobs)){
            try{
                history.market();assertTrue(first.await(3,TimeUnit.SECONDS));
                history.quote(ID,List.of(MISSING));assertTrue(second.await(3,TimeUnit.SECONDS));
                // The old autocommit writer finishes and mixes sources; an atomic writer waits for the first publication's row lock.
                secondDone.await(250,TimeUnit.MILLISECONDS);
            }finally{release.countDown();}
            assertTrue(secondDone.await(3,TimeUnit.SECONDS));
            eventually(()->history.quote(ID,List.of(MISSING)),quote->quote.rate("USD",MISSING)!=null);
            assertEquals(1,db.queryForObject("SELECT COUNT(DISTINCT source_sha256) FROM finance_fx_historical_rates WHERE rate_date=?",Integer.class,MISSING),"A publication cannot permanently combine currencies from different responses");
            assertPublication(MISSING,FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8"));
        }
    }
    @Test void preexistingPartialPublicationCannotBeCompletedFromAnotherPayload()throws Exception{
        db.update("INSERT INTO finance_fx_historical_rates VALUES('ECB',?,'CNY',1,?,CURRENT_TIMESTAMP,?)",MISSING,MISSING,"a".repeat(64));
        var repo=new FinanceRepository(db,new ObjectMapper());
        try(var jobs=new FinanceFxRefreshes(1,4,Clock.systemUTC(),Duration.ofSeconds(30));var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8"),Clock.systemUTC(),jobs)){
            history.quote(ID,List.of(MISSING));drain(jobs,"legacy-partial");
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_fx_historical_rates WHERE rate_date=?",Integer.class,MISSING),"Legacy partial rows must not be completed using a different response source");
            assertNull(history.quote(ID,List.of(MISSING)).rate("USD",MISSING));
        }
    }
    @Test void preexistingMixedSourcePublicationRemainsUnknownInsteadOfFreezingAFalseRate()throws Exception{
        var expected=FinanceFxHistory.parse(FinanceHistoricalFxTest.providerRows(MISSING.toString(),"8"),MISSING).get(MISSING).rates();
        expected.forEach((currency,rate)->db.update("INSERT INTO finance_fx_historical_rates VALUES('ECB',?,?,?,?,CURRENT_TIMESTAMP,?)",MISSING,currency,rate,MISSING,(currency.equals("USD")?"a":"b").repeat(64)));
        var repo=new FinanceRepository(db,new ObjectMapper());
        try(var history=new FinanceFxHistory(repo,new ObjectMapper(),uri->{throw new IllegalStateException("offline");})){
            assertNull(history.quote(ID,List.of(MISSING)).rate("USD",MISSING),"Complete currency count is insufficient when the persisted publication combines response sources");
        }
    }
    private void assertPublication(LocalDate date,String raw){
        var expected=FinanceFxHistory.parse(raw,date).get(date).rates();
        var actual=new TreeMap<String,java.math.BigDecimal>();
        for(var row:db.queryForList("SELECT currency,rate_cny FROM finance_fx_historical_rates WHERE rate_date=?",date))actual.put(text(row.get("currency")),decimal(row.get("rate_cny")));
        assertEquals(expected.keySet(),actual.keySet());expected.forEach((currency,rate)->assertEquals(0,rate.compareTo(actual.get(currency)),currency));
    }
    private static String combined(String a,String b){return a.substring(0,a.length()-1)+","+b.substring(1);}
}
