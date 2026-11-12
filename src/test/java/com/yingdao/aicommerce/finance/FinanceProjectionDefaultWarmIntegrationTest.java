package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real projection writes are restricted to the disposable FINANCE_QA_JDBC schema. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceProjectionDefaultWarmIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    FinanceQueryProjections projections;
    TracedJdbc db;
    FinanceQueryProjectionWorker worker;
    final MutableClock clock=new MutableClock();
    @TempDir Path temp;
    @BeforeAll void schema()throws Exception{
        fixture.migrate();fixture.migration("V150__finance_ad_spend_workspace.sql");
        fixture.migration("V155__finance_query_projections.sql");
    }
    @AfterAll void close(){fixture.closeDataSource();}
    @BeforeEach void setup(){
        fixture.setup();
        for(String table:List.of("finance_query_dirty_days","finance_query_partitions","finance_query_events"))fixture.db.update("DELETE FROM "+table);
        db=new TracedJdbc(fixture.db);var repo=new FinanceRepository(db,new ObjectMapper());
        projections=new FinanceQueryProjections(repo,fixture.service,new FinanceAdSpendService(repo,fixture.service));
        clock.now=Instant.parse("2026-11-12T00:00:00Z");worker=new FinanceQueryProjectionWorker(projections,clock);
    }
    @AfterEach void stop(){if(worker!=null)worker.stop();}
    @Test void createsSixDefaultPartitionsPerVisibleCompanyWithoutHistoricalScanning(){
        fixture.connect(fixture.owner,false);fixture.connect(new Scope("org","another-user"),false);
        fixture.connect(fixture.other,false);
        var removed=new Scope("removed-org","removed-user");String removedShop=fixture.connect(removed,false);
        fixture.db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",removedShop);
        projections.warmDefaults(clock.instant());
        var rows=fixture.db.queryForList("SELECT organization_id,platform_filter,partition_month FROM finance_query_partitions");
        assertEquals(12,rows.size());
        for(String org:List.of("org","other-org"))assertEquals(6,rows.stream().filter(row->org.equals(row.get("organization_id"))).count());
        assertEquals(Set.of("2026-10-01","2026-11-01"),rows.stream().map(row->text(row.get("partition_month"))).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("","SHOPEE","TIKTOK_SHOP"),rows.stream().map(row->text(row.get("platform_filter"))).collect(java.util.stream.Collectors.toSet()));
        assertTrue(rows.stream().noneMatch(row->"removed-org".equals(row.get("organization_id"))));
        assertTrue(db.requests.subList(0,6).stream().allMatch(row->"2026-10-01".equals(row.get(1))));
        assertTrue(db.requests.subList(6,12).stream().allMatch(row->"2026-11-01".equals(row.get(1))));
        assertEquals(List.of("","SHOPEE","TIKTOK_SHOP"),db.requests.subList(0,3).stream().map(row->row.get(2)).toList());
    }
    @Test void repeatedWarmupPreservesReadyPayloadsRevisionsAndRequestTimes(){
        fixture.connect(fixture.owner,false);projections.drainEvents();projections.warmDefaults(clock.instant());
        for(int n=0;n<6;n++)assertTrue(projections.buildNext());assertFalse(projections.buildNext());
        var before=fixture.db.queryForList("SELECT * FROM finance_query_partitions ORDER BY partition_month,platform_filter");
        assertTrue(before.stream().allMatch(row->number(row.get("built_revision"))>0&&number(row.get("built_revision"))==number(row.get("requested_revision"))));
        projections.warmDefaults(clock.instant().plusSeconds(3600));
        assertEquals(before,fixture.db.queryForList("SELECT * FROM finance_query_partitions ORDER BY partition_month,platform_filter"));
        assertFalse(projections.buildNext());
    }
    @Test void maintenanceGatePreventsWritesAndRetainsPermitForTheWholeWarmup()throws Exception{
        fixture.connect(fixture.owner,false);var marker=temp.resolve("maintenance.json");var gate=gate(marker);db.gate=gate;
        long events=fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Long.class);
        try(var ignored=redirectGate(gate)){
            Files.writeString(marker,"{}");worker.tickEvents();
            assertEquals(0,partitionCount());assertEquals(0,db.warmScans);
            assertEquals(events,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events",Long.class));
            Files.delete(marker);worker.tickEvents();
            assertEquals(6,partitionCount());assertEquals(1,db.warmScans);assertEquals(0,gate.activeWorkers());
        }
    }
    @Test void hourlySchedulingDoesNotRepeatWithinTheHourAndPicksUpNewCompanies()throws Exception{
        fixture.connect(fixture.owner,false);var gate=gate(temp.resolve("gate"));db.gate=gate;
        try(var ignored=redirectGate(gate)){
            worker.tickEvents();assertEquals(6,partitionCount());assertEquals(1,db.warmScans);
            fixture.connect(fixture.other,false);clock.now=clock.now.plusSeconds(3599);worker.tickEvents();
            assertEquals(6,partitionCount());assertEquals(1,db.warmScans);
            clock.now=clock.now.plusSeconds(1);worker.tickEvents();
            assertEquals(12,partitionCount());assertEquals(2,db.warmScans);assertEquals(18,db.requests.size());
            worker.tickEvents();assertEquals(2,db.warmScans);
        }
    }
    @Test void businessMonthRolloverWarmsImmediatelyEvenBeforeTheHour()throws Exception{
        fixture.connect(fixture.owner,false);var gate=gate(temp.resolve("gate"));db.gate=gate;
        try(var ignored=redirectGate(gate)){
            clock.now=Instant.parse("2026-11-30T15:50:00Z");worker.tickEvents();assertEquals(6,partitionCount());
            clock.now=Instant.parse("2026-11-30T15:59:59Z");worker.tickEvents();assertEquals(1,db.warmScans);
            clock.now=Instant.parse("2026-11-30T16:00:00Z");worker.tickEvents();
            assertEquals(9,partitionCount());assertEquals(2,db.warmScans);
            assertTrue(db.requests.subList(6,9).stream().allMatch(row->"2026-11-01".equals(row.get(1))));
            assertTrue(db.requests.subList(9,12).stream().allMatch(row->"2026-12-01".equals(row.get(1))));
        }
    }
    @Test void partialWarmupFailureRetriesWithoutAdvancingTheHourlyClock()throws Exception{
        fixture.connect(fixture.owner,false);var gate=gate(temp.resolve("gate"));db.gate=gate;db.failRequest=4;
        try(var ignored=redirectGate(gate)){
            worker.tickEvents();assertEquals(3,partitionCount());assertEquals(1,db.warmScans);assertEquals(0,gate.activeWorkers());
            worker.tickEvents();assertEquals(6,partitionCount());assertEquals(2,db.warmScans);assertEquals(10,db.requests.size());
            worker.tickEvents();assertEquals(2,db.warmScans);
        }
    }
    private int partitionCount(){return fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_partitions",Integer.class);}
    private static MaintenanceGate gate(Path path)throws Exception{
        var constructor=MaintenanceGate.class.getDeclaredConstructor(Path.class);constructor.setAccessible(true);return constructor.newInstance(path);
    }
    /** Redirect only the singleton environment boundary to a real gate with a test-owned marker. */
    private static org.mockito.MockedStatic<MaintenanceGate> redirectGate(MaintenanceGate gate){
        var mocked=mockStatic(MaintenanceGate.class,CALLS_REAL_METHODS);
        mocked.when(()->MaintenanceGate.runIfOpen(any(Runnable.class))).thenAnswer(call->{
            try(var permit=gate.enter(true)){if(permit!=null)call.<Runnable>getArgument(0).run();}return null;
        });return mocked;
    }
    private static final class MutableClock extends Clock {
        Instant now;
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        @Override public Instant instant(){return now;}
    }
    private static final class TracedJdbc extends JdbcTemplate {
        final List<List<String>> requests=new ArrayList<>();int warmScans;int failRequest;MaintenanceGate gate;
        TracedJdbc(JdbcTemplate db){super(Objects.requireNonNull(db.getDataSource()));}
        @Override public <T>List<T> queryForList(String sql,Class<T> type){
            if(sql.startsWith("SELECT DISTINCT organization_id FROM finance_connections")){warmScans++;checkPermit();}
            return super.queryForList(sql,type);
        }
        @Override public <T>List<T> queryForList(String sql,Class<T> type,Object... args){
            if(sql.startsWith("SELECT DISTINCT organization_id FROM finance_connections")){warmScans++;checkPermit();}
            return super.queryForList(sql,type,args);
        }
        @Override public int update(String sql,Object... args){
            if(sql.startsWith("INSERT IGNORE INTO finance_query_partitions")){
                checkPermit();requests.add(List.of(text(args[0]),text(args[2]),text(args[1])));
                if(requests.size()==failRequest)throw new org.springframework.dao.TransientDataAccessResourceException("simulated warmup interruption");
            }
            return super.update(sql,args);
        }
        private void checkPermit(){if(gate!=null)assertTrue(gate.activeWorkers()>0,"The maintenance permit must cover every prewarm read and write");}
    }
}
