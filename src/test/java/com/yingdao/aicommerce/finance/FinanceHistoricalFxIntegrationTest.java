package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only the disposable QA schema and a synthetic public-rate transport, never the internet. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceHistoricalFxIntegrationTest {
    @Test void v151FreezesExistingDaysAppendsOlderCoverageAndPreservesLegacySnapshots()throws Exception{
        String url=System.getenv("FINANCE_QA_JDBC");
        assertTrue(url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"));
        var db=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD")));
        db.execute("CREATE TABLE finance_fx_snapshots(id VARCHAR(32) PRIMARY KEY,provider VARCHAR(100),rate_time DATETIME(6),rates_json JSON)");
        db.update("INSERT INTO finance_fx_snapshots VALUES('17','legacy','2026-10-01','{\"USD\":7}')");
        String migration=Files.readString(Path.of("src/main/resources/db/migration/V151__finance_historical_fx.sql")).replaceAll("(?m)^--.*$","");
        for(String statement:migration.split(";"))if(!statement.isBlank())db.execute(statement);
        var repo=new FinanceRepository(db,new ObjectMapper());var provider=new ConcurrentSkipListMap<LocalDate,String>();
        provider.put(LocalDate.of(2026,9,18),"6");provider.put(LocalDate.of(2026,10,9),"7");provider.put(LocalDate.of(2026,10,12),"8");
        provider.put(LocalDate.of(2026,11,10),"9");
        var calls=new AtomicInteger();
        Function<URI,String> transport=uri->{
            calls.incrementAndGet();assertEquals("api.frankfurter.dev",uri.getHost());assertTrue(uri.getRawQuery().contains("providers=ecb"));
            var query=new HashMap<String,String>();for(String part:uri.getRawQuery().split("&")){var bits=part.split("=",2);query.put(bits[0],bits[1]);}
            var dates=query.containsKey("from")?provider.subMap(LocalDate.parse(query.get("from")),true,LocalDate.parse(query.get("to")),true):provider.tailMap(provider.lastKey(),true);
            return "["+String.join(",",dates.entrySet().stream().map(row->{
                String raw=FinanceHistoricalFxTest.providerRows(row.getKey().toString(),row.getValue()).replace("\"quote\":\"PHP\",\"rate\":8","\"quote\":\"PHP\",\"rate\":3");return raw.substring(1,raw.length()-1);
            }).toList())+"]";
        };
        var history=new FinanceFxHistory(repo,new ObjectMapper(),transport);
        FinanceFxAsyncIntegrationTest.eventually(history::market,m->"READY".equals(m.get("status")));
        var sunday=LocalDate.of(2026,10,11);var first=ready(history,null,List.of(sunday));
        assertEquals(decimal("70.000000"),first.convert("10","USD",sunday));String id=first.id();
        assertEquals(decimal("2333333333333.333300"),first.convert("1000000000000","PHP",sunday));
        first.preload(List.of(LocalDate.of(2026,9,20)));
        var earlier=ready(history,id,List.of(LocalDate.of(2026,9,20)));
        assertEquals(decimal("60.000000"),earlier.convert("10","USD",LocalDate.of(2026,9,20)));
        assertEquals(decimal("2333333333333.333300"),first.convert("1000000000000","PHP",sunday),"Appending earlier order dates preserves exact previously loaded decimal rates");
        provider.put(LocalDate.of(2026,10,9),"100");
        var extended=ready(history,id,List.of(LocalDate.of(2026,9,20),LocalDate.of(2026,10,12)));
        assertEquals(decimal("70.000000"),extended.convert("10","USD",sunday));
        assertEquals(decimal("2333333333333.333300"),extended.convert("1000000000000","PHP",sunday),"Reloading the persisted snapshot does not round through binary floating point");
        assertEquals(decimal("60.000000"),extended.convert("10","USD",LocalDate.of(2026,9,20)));
        assertEquals(decimal("80.000000"),extended.convert("10","USD",LocalDate.of(2026,10,12)));
        var future=ready(history,id,List.of(LocalDate.of(2026,11,11)));
        provider.put(LocalDate.of(2026,11,11),"200");
        assertEquals(decimal("90.000000"),future.convert("10","USD",LocalDate.of(2026,11,11)),"Snapshot cutoff prevents later publications entering old pages");
        int warmed=calls.get();
        var another=history.quote(null,List.of(sunday,LocalDate.of(2026,10,12)));
        assertEquals(decimal("70.000000"),another.convert("10","USD",sunday));
        assertEquals(warmed,calls.get(),"A fresh snapshot reuses persisted, verified business-day coverage");
        var reordered=history.quote(null,List.of(LocalDate.of(2026,10,12),sunday,sunday));
        assertEquals(another.id(),reordered.id(),"Order and duplicate dates cannot create duplicate warmer snapshots");
        var offline=new FinanceFxHistory(repo,new ObjectMapper(),uri->{throw new IllegalStateException("latest and history unavailable");});
        var cached=offline.quote(null,List.of(sunday,LocalDate.of(2026,10,12),LocalDate.now(ZoneOffset.UTC)));
        assertEquals(decimal("70.000000"),cached.convert("10","USD",sunday));
        assertEquals(decimal("80.000000"),cached.convert("10","USD",LocalDate.of(2026,10,12)));
        assertNull(cached.convert("10","USD",LocalDate.now(ZoneOffset.UTC)),"An unverified recent workday is not inferred to be a holiday");
        assertTrue(calls.get()<=4,"Required dates are fetched in bulk, not per amount");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_fx_snapshots",Integer.class));
        assertEquals("legacy",db.queryForObject("SELECT provider FROM finance_fx_snapshots WHERE id='17'",String.class));
        assertEquals(0,decimal("7").compareTo(db.queryForObject("SELECT JSON_EXTRACT(rates_json,'$.USD') FROM finance_fx_snapshots WHERE id='17'",BigDecimal.class)));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM finance_fx_historical_snapshots",Integer.class));
        var newerHistory=new FinanceFxHistory(repo,new ObjectMapper(),transport);
        FinanceFxAsyncIntegrationTest.eventually(newerHistory::market,m->"READY".equals(m.get("status")));
        var newer=newerHistory.quote(null,List.of(sunday,LocalDate.of(2026,10,12)));
        assertNotEquals(another.id(),newer.id(),"A genuinely newer publication cutoff creates a distinct frozen snapshot");
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM finance_fx_historical_snapshots",Integer.class));
        var barrier=new CyclicBarrier(2);var ids=new ArrayList<Future<String>>();
        try(var pool=Executors.newFixedThreadPool(2)){
            for(var extra:List.of(LocalDate.of(2026,9,18),LocalDate.of(2026,9,19)))ids.add(pool.submit(()->{
                try(var instance=new FinanceFxHistory(repo,new ObjectMapper(),transport)){
                FinanceFxAsyncIntegrationTest.eventually(instance::market,m->"READY".equals(m.get("status")));
                var shared=instance.quote(null,List.of(sunday,LocalDate.of(2026,10,12),LocalDate.of(2026,9,20)));
                barrier.await(10,TimeUnit.SECONDS);ready(instance,shared.id(),List.of(extra));return shared.id();
                }
            }));
            assertEquals(ids.get(0).get(15,TimeUnit.SECONDS),ids.get(1).get(15,TimeUnit.SECONDS));
        }
        var merged=ready(history,ids.getFirst().get(),List.of(LocalDate.of(2026,9,18),LocalDate.of(2026,9,19)));
        assertEquals(decimal("60.000000"),merged.convert("10","USD",LocalDate.of(2026,9,18)));
        assertEquals(decimal("60.000000"),merged.convert("10","USD",LocalDate.of(2026,9,19)));
        assertEquals(decimal("70.000000"),merged.convert("10","USD",sunday));
        history.close();offline.close();newerHistory.close();
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM finance_fx_historical_snapshots",Integer.class),"Concurrent initialization and append retain one shared identity and both additions");
    }
    private static FinanceFx.Quote ready(FinanceFxHistory history,String id,List<LocalDate> dates)throws Exception{
        var initial=history.quote(id,dates);
        return FinanceFxAsyncIntegrationTest.eventually(()->history.quote(initial.id(),dates),q->dates.stream().allMatch(d->q.rate("USD",d)!=null));
    }
}
