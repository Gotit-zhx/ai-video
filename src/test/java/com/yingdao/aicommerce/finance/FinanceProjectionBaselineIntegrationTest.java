package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real repeatable-read races, with independently committed source writes and event draining. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceProjectionBaselineIntegrationTest {
    final FinanceConfigurationIntegrationTest f=new FinanceConfigurationIntegrationTest();
    final Filter month=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");
    FinanceQueryProjections projections;String shop;
    @BeforeAll void schema()throws Exception{f.migrate();}
    @AfterAll void close(){f.closeDataSource();}
    @BeforeEach void setup(){
        f.setup();shop=f.connect(f.owner,false);
        projections=new FinanceQueryProjections(f.repo,f.service,new FinanceAdSpendService(f.repo,f.service));
        fee("first","2026-10-01","11.123456");fee("other","2026-10-07","7");
        projections.request(f.owner,month);drain();
    }
    @Test void fullBuildSavesHiddenBaselineAndSnapshotStartWhenDayChangesDuringRead(){
        long revision=revision("requested_revision");var observed=new AtomicReference<Instant>();
        race(()->{observed.set(Instant.now());change("first","20");drain();});
        assertEquals(revision,revision("built_revision"),"Completed baseline must survive a newer DAY request");
        assertEquals(revision+1,revision("requested_revision"));assertEquals(0,revision("full_rebuild"));
        assertEquals(revision+1,dirtyRevision("2026-10-01"));assertHidden();
        Instant generated=f.db.queryForObject("SELECT generated_at FROM finance_query_partitions",java.sql.Timestamp.class).toInstant();
        assertFalse(generated.isAfter(observed.get()),"Freshness starts before reading facts, not when the long build finishes");
        assertEquals(0,decimal("11.123456").compareTo(storedAmount("2026-10-01")));
        assertTrue(projections.buildNext());assertReadyAndNative();assertEquals(0,dirtyCount());
    }
    @Test void repeatedSameDayKeepsItsNewerRevisionWhileOlderDirtyDaysAreCleared(){
        assertTrue(projections.buildNext());change("first","15");change("other","8");drain();
        long revision=revision("requested_revision");assertEquals(revision,dirtyRevision("2026-10-01"));
        race(()->{change("first","21");drain();});
        assertEquals(revision,revision("built_revision"));assertEquals(revision+1,dirtyRevision("2026-10-01"));
        assertEquals(1,dirtyCount(),"Only the day invalidated after R survives the successful save");assertHidden();
        assertEquals(0,decimal("15").compareTo(storedAmount("2026-10-01")));
        assertTrue(projections.buildNext());assertReadyAndNative();assertEquals(0,dirtyCount());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void newerAllInvalidationRetainsFullModeAfterFullOrIncrementalBaseline(boolean incremental){
        if(incremental){assertTrue(projections.buildNext());change("first","15");drain();}
        long revision=revision("requested_revision");
        race(()->{change("other","99");f.db.update("UPDATE finance_connections SET display_name='new full facts' WHERE id=?",shop);drain();});
        assertEquals(revision,revision("built_revision"));assertEquals(revision+1,revision("full_rebuild_revision"));
        assertEquals(1,revision("full_rebuild"));assertHidden();
        assertTrue(projections.buildNext());assertReadyAndNative();assertEquals(0,revision("full_rebuild"));
        assertEquals(0,decimal("99").compareTo(storedAmount("2026-10-07")));
    }
    @ParameterizedTest @ValueSource(strings={"stolen","expired","newer-built"})
    void staleWorkerCannotSaveOrClearDirtyDays(String reason){
        assertTrue(projections.buildNext());change("first","15");drain();
        var before=payload();var days=f.db.queryForList("SELECT * FROM finance_query_dirty_days");
        race(()->{
            switch(reason){
                case "stolen" -> f.db.update("UPDATE finance_query_partitions SET lease_token='replacement',lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)");
                case "expired" -> f.db.update("UPDATE finance_query_partitions SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
                case "newer-built" -> f.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1,built_revision=requested_revision");
                default -> throw new AssertionError(reason);
            }
        });
        assertEquals(before,payload());assertEquals(days,f.db.queryForList("SELECT * FROM finance_query_dirty_days"));
        if(reason.equals("stolen"))assertEquals("replacement",f.db.queryForObject("SELECT lease_token FROM finance_query_partitions",String.class));
        if(!reason.equals("newer-built"))assertHidden();
    }
    @Test void failedSaveTransactionRollsBackBaselineAndDirtyDayDeletion(){
        assertTrue(projections.buildNext());change("first","15");drain();
        var before=f.db.queryForMap("SELECT full_month_json,partial_first_day_json,spend_json,built_revision,generated_at FROM finance_query_partitions");
        var days=f.db.queryForList("SELECT * FROM finance_query_dirty_days");var deleted=new AtomicBoolean();
        var jdbc=new JdbcTemplate(f.dataSource){
            @Override public int update(String sql,Object...args){
                int result=super.update(sql,args);
                if(sql.startsWith("DELETE FROM finance_query_dirty_days")){deleted.set(true);throw new IllegalStateException("test save transaction rollback");}
                return result;
            }
        };
        var repo=new FinanceRepository(jdbc,new ObjectMapper());
        assertTrue(new FinanceQueryProjections(repo,f.service,new FinanceAdSpendService(repo,f.service)).buildNext());
        assertTrue(deleted.get());assertEquals(before,f.db.queryForMap("SELECT full_month_json,partial_first_day_json,spend_json,built_revision,generated_at FROM finance_query_partitions"));
        assertEquals(days,f.db.queryForList("SELECT * FROM finance_query_dirty_days"));assertHidden();
        f.db.update("UPDATE finance_query_partitions SET next_attempt_at=UTC_TIMESTAMP(6)");assertTrue(projections.buildNext());assertReadyAndNative();
    }
    @Test void lateCommitEventFencesAnOtherwiseCaughtUpBaselineAndEventuallyConverges()throws Exception{
        try(var connection=f.dataSource.getConnection()){
            connection.setAutoCommit(false);
            try(var update=connection.prepareStatement("UPDATE finance_records SET amount=31 WHERE external_id='first'")){update.executeUpdate();}
            race(()->{try{connection.commit();}catch(Exception failure){throw new IllegalStateException(failure);}});
        }
        assertEquals(revision("requested_revision"),revision("built_revision"));assertHidden();
        assertTrue(f.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Integer.class)>0);
        drain();assertTrue(projections.buildNext());assertReadyAndNative();
    }
    @Test void eventDrainRollbackRestoresRevisionsAndDoesNotAcknowledgeVisibleIds(){
        assertTrue(projections.buildNext());change("first","15");
        var before=f.db.queryForList("SELECT * FROM finance_query_partitions");var events=f.db.queryForList("SELECT * FROM finance_query_events ORDER BY event_id");
        assertThrows(IllegalStateException.class,()->f.repo.transaction(()->{projections.drainEvents();throw new IllegalStateException("rollback drain");}));
        assertEquals(before,f.db.queryForList("SELECT * FROM finance_query_partitions"));assertEquals(events,f.db.queryForList("SELECT * FROM finance_query_events ORDER BY event_id"));
        assertEquals(0,dirtyCount());assertHidden();drain();assertTrue(projections.buildNext());assertReadyAndNative();
    }
    @Test void legacyZeroRevisionDirtyDayIsConsumedByTheNextSuccessfulLease(){
        assertTrue(projections.buildNext());
        f.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1");
        f.db.update("INSERT INTO finance_query_dirty_days(organization_id,platform_filter,partition_month,record_date) VALUES('org','','2026-10-01','2026-10-01')");
        assertEquals(0,dirtyRevision("2026-10-01"));assertTrue(projections.buildNext());assertEquals(0,dirtyCount());assertReadyAndNative();
    }
    private void race(Runnable mutation){
        var changed=new AtomicBoolean();
        var racing=new FinanceService(f.repo,null,null,f.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                var result=super.buildNativeDashboardFacts(scope,filter,live);
                if(changed.compareAndSet(false,true))try{CompletableFuture.runAsync(mutation).get(10,TimeUnit.SECONDS);}
                catch(Exception failure){throw new IllegalStateException(failure);}
                return result;
            }
        };
        assertTrue(new FinanceQueryProjections(f.repo,racing,new FinanceAdSpendService(f.repo,racing)).buildNext());assertTrue(changed.get());
    }
    private void assertHidden(){
        assertTrue(projections.find(f.owner,month).isEmpty());assertTrue(projections.findForQuery(f.owner,month,false).isEmpty());
        assertTrue(projections.findForQuery(f.owner,month,true).isEmpty());assertEquals("PENDING",projections.version(f.owner,month).get("status"));
    }
    private void assertReadyAndNative(){
        assertEquals("READY",projections.version(f.owner,month).get("status"));
        for(var filter:List.of(month,new Filter(month.start(),month.start().plusDays(14),"","","","day"),new Filter(month.start().plusDays(1),month.end(),"","","","day"))){
            var actual=projections.find(f.owner,filter).orElseThrow();
            assertEquals(canonical(f.service.buildNativeDashboardFacts(f.owner,filter,false)),canonical(actual.facts()));
            var spend=new FinanceAdSpendService(f.repo,f.service).buildNativeSpendFacts(f.owner,filter);
            assertEquals(rows(spend.get("sources")),rows(actual.spendFacts().get("sources")));
            long count=actual.rawCounts().stream().filter(row->"FEE".equals(row.get("kind"))&&"RECORD".equals(row.get("basis"))).mapToLong(row->number(row.get("records"))).sum();
            assertEquals(number(f.repo.details(f.owner,filter,"FEE",1,50,"",true).get("total")),count);
        }
    }
    private Map<String,Object> canonical(Map<String,Object> facts){
        var result=new TreeMap<String,Object>();
        for(String name:List.of("buckets","orders","advertising"))result.put(name,rows(facts.get(name)).stream().map(row->{
            var normalized=new TreeMap<String,String>();row.forEach((key,value)->normalized.put(key,value instanceof Number?decimal(value).stripTrailingZeros().toPlainString():text(value)));return normalized.toString();
        }).sorted().toList());return result;
    }
    private void fee(String id,String day,String amount){f.repo.save(f.owner,new Entry(shop,"SHOPEE","FEE",id,"","","PLATFORM_FEE","SETTLED",LocalDate.parse(day),"PHP",decimal(amount),null,null,null,null,"API",Map.of()));}
    private void change(String id,String amount){f.db.update("UPDATE finance_records SET amount=? WHERE external_id=?",decimal(amount),id);}
    private void drain(){while(projections.drainEvents()>0){}}
    private long revision(String column){var value=f.db.queryForMap("SELECT * FROM finance_query_partitions").get(column);return Boolean.TRUE.equals(value)?1:number(value);}
    private long dirtyRevision(String day){return f.db.queryForObject("SELECT requested_revision FROM finance_query_dirty_days WHERE record_date=?",Long.class,LocalDate.parse(day));}
    private int dirtyCount(){return f.db.queryForObject("SELECT COUNT(*) FROM finance_query_dirty_days",Integer.class);}
    private Map<String,Object> payload(){return f.db.queryForMap("SELECT full_month_json,partial_first_day_json,spend_json,generated_at FROM finance_query_partitions");}
    private java.math.BigDecimal storedAmount(String day){return rows(f.repo.read(f.db.queryForObject("SELECT full_month_json FROM finance_query_partitions",String.class)).get("buckets")).stream().filter(row->day.equals(text(row.get("record_date")))).map(row->decimal(row.get("amount"))).findFirst().orElseThrow();}
}
