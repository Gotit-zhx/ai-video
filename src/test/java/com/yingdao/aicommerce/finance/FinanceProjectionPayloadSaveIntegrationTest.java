package com.yingdao.aicommerce.finance;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real limited-packet writes against one disposable FINANCE_QA_JDBC schema. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceProjectionPayloadSaveIntegrationTest {
    static final int CONNECTION_PACKET=2*1024*1024;
    static final String FULL_PADDING="f".repeat(1_250_000);
    static final String FIRST_PADDING="d".repeat(80_000);
    static final String SPEND_PADDING="s".repeat(850_000);
    static final Filter MONTH=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    HikariDataSource limitedDataSource;
    String limitedUrl;
    String shop;
    BigDecimal expectedFee,expectedAd;

    @BeforeAll void schema()throws Exception{
        fixture.migrate();
        limitedDataSource=new HikariDataSource();
        limitedUrl=System.getenv("FINANCE_QA_JDBC")+"&maxAllowedPacket="+CONNECTION_PACKET+"&useAffectedRows=true";
        limitedDataSource.setJdbcUrl(limitedUrl);
        limitedDataSource.setUsername(System.getenv("FINANCE_QA_USER"));
        limitedDataSource.setPassword(System.getenv("FINANCE_QA_PASSWORD"));
        limitedDataSource.setMaximumPoolSize(6);limitedDataSource.setMinimumIdle(1);
    }
    @AfterAll void close(){if(limitedDataSource!=null)limitedDataSource.close();fixture.closeDataSource();}
    @BeforeEach void setup(){
        fixture.setup();shop=fixture.connect(fixture.owner,false);
        expectedFee=new BigDecimal("12.123456");expectedAd=new BigDecimal("0.123456");
        fixture.repo.save(fixture.owner,new Entry(shop,"SHOPEE","FEE","payload-fee","","","PLATFORM_FEE","SETTLED",MONTH.start(),"USD",expectedFee,null,null,null,null,"API",Map.of("fixture","real-fee")));
        fixture.repo.save(fixture.owner,new Entry(shop,"SHOPEE","AD","payload-ad","","","ADS","ACTIVE",MONTH.start(),"USD",expectedAd,null,null,null,null,"API",Map.of("shop_id","10001","dimensions",Map.of("campaign_id","real-campaign"))));
        fixture.db.update("DELETE FROM finance_query_events");
    }

    @Test void aggregateBeyondOneRequestRoundTripsThroughInitialAndIncrementalClaims(){
        var jdbc=new ObservingJdbc(limitedDataSource);var projections=projections(jdbc);
        projections.request(fixture.owner,MONTH);

        assertTrue(projections.buildNext());

        assertPacketShape(jdbc);
        assertReadyAndExact(projections,"stable");
        var stored=storedPayloads();
        var oldClaim=assertThrows(SQLException.class,()->{
            try(var connection=java.sql.DriverManager.getConnection(limitedUrl,System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD"));
                var statement=connection.prepareStatement("SELECT p.* FROM finance_query_partitions p WHERE p.organization_id=? AND p.platform_filter=? AND p.partition_month=?")){
                statement.setString(1,fixture.owner.organizationId());statement.setString(2,"");statement.setObject(3,MONTH.start());statement.executeQuery();
            }
        });
        assertEquals("S1000",oldClaim.getSQLState());
        System.out.println("projectionPayloadBytes="+jdbc.payloadBytes+" aggregate="+jdbc.payloadBytes.stream().mapToLong(Long::longValue).sum()
            +" connectionMaxAllowedPacket="+CONNECTION_PACKET+" oldClaim="+oldClaim.getClass().getSimpleName()+" oldClaimSqlState="+oldClaim.getSQLState());
        assertEquals(stored,storedPayloads());
    }

    @Test void unchangedLaterSegmentsAcceptZeroAffectedRows(){
        var jdbc=new ObservingJdbc(limitedDataSource);var projections=smallProjections(jdbc);
        projections.request(fixture.owner,MONTH);assertTrue(projections.buildNext());var stored=storedPayloads();
        enqueueNoopIncremental();assertTrue(projections.buildNext());assertReadyAndExact(projections,"stable");
        assertEquals(stored,storedPayloads(),"A deterministic no-op incremental build must preserve all serialized bytes");
        assertEquals(List.of(1,1,0,0),jdbc.laterAffected,"useAffectedRows=true legitimately reports zero for unchanged later segments");
    }

    @Test void serverPacketBudgetRoundTripsPublicCombinedFindAndIncrementalClaim()throws Exception{
        long serverPacket=fixture.db.queryForObject("SELECT @@max_allowed_packet",Long.class);
        assertEquals(64L*1024*1024,serverPacket,"The disposable server must retain its production-sized packet budget");
        String padding="p".repeat(23_000_000);
        var jdbc=new ObservingJdbc(fixture.dataSource);var projections=projections(jdbc,padding,padding,padding,"stable");
        projections.request(fixture.owner,MONTH);assertTrue(projections.buildNext());
        assertEquals(3,jdbc.payloadBytes.size());assertTrue(jdbc.payloadBytes.stream().allMatch(size->size<serverPacket));
        long aggregate=jdbc.payloadBytes.stream().mapToLong(Long::longValue).sum();assertTrue(aggregate>serverPacket);

        try(var connection=java.sql.DriverManager.getConnection(System.getenv("FINANCE_QA_JDBC"),System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD"));
            var statement=connection.prepareStatement("SELECT p.* FROM finance_query_partitions p WHERE p.organization_id=? AND p.platform_filter=? AND p.partition_month=?")){
            statement.setString(1,fixture.owner.organizationId());statement.setString(2,"");statement.setObject(3,MONTH.start());
            try(var rows=statement.executeQuery()){assertTrue(rows.next());assertFalse(rows.next());}
        }
        assertTrue(projections.find(fixture.owner,MONTH).isPresent(),"The public combined reader must roundtrip all three committed bodies");
        assertReadyAndExact(projections,"stable");
        enqueueNoopIncremental();assertTrue(projections.buildNext());assertTrue(projections.find(fixture.owner,MONTH).isPresent());
        System.out.println("serverProjectionPayloadBytes="+jdbc.payloadBytes.subList(0,3)+" aggregate="+aggregate+" serverMaxAllowedPacket="+serverPacket
            +" oldClaim=success combinedFind=success incrementalClaim=success");
    }

    @ParameterizedTest @ValueSource(strings={"full","first","spend","delete"})
    void everySaveStepFailureRollsBackBodiesMetadataAndDirtyDays(String stage){
        var baseline=smallProjections(new ObservingJdbc(limitedDataSource),"old");baseline.request(fixture.owner,MONTH);
        assertTrue(baseline.buildNext());var before=publishedState();var oldBodies=storedPayloads();changeRealFacts();var dirty=dirtyDays();
        var failed=new FaultingJdbc(limitedDataSource,stage);

        assertTrue(smallProjections(failed,"new").buildNext());

        assertTrue(failed.triggered.get(),stage);
        assertEquals(before,publishedState(),stage+" must roll back every published body and metadata field");
        assertEquals(oldBodies,storedPayloads(),stage+" must retain all three old bodies byte-for-byte");
        assertEquals(dirty,dirtyDays(),stage+" must not acknowledge dirty revisions");
        assertEquals("PENDING",baseline.version(fixture.owner,MONTH).get("status"));
        fixture.db.update("UPDATE finance_query_partitions SET next_attempt_at=UTC_TIMESTAMP(6)");
        assertTrue(smallProjections(new ObservingJdbc(limitedDataSource),"new").buildNext());
        assertAllBodiesChanged(oldBodies);assertReadyAndExact(baseline,"new");
    }

    @Test void separatelyCommittedReaderNeverSeesIntermediateSegments()throws Exception{
        var baseline=smallProjections(new ObservingJdbc(limitedDataSource),"old");baseline.request(fixture.owner,MONTH);
        assertTrue(baseline.buildNext());var before=publishedState();var oldBodies=storedPayloads();changeRealFacts();
        var paused=new PausingJdbc(limitedDataSource);var worker=CompletableFuture.runAsync(()->assertTrue(smallProjections(paused,"new").buildNext()));
        assertTrue(paused.written.await(10,TimeUnit.SECONDS),"The save did not reach its second segment");
        try{
            assertEquals(before,publishedState(),"An independent committed reader must see the old complete publication");
            assertEquals(oldBodies,storedPayloads(),"The reader must see all three old bodies while two new segments are uncommitted");
            assertEquals("PENDING",baseline.version(fixture.owner,MONTH).get("status"));
        }finally{paused.release.countDown();}
        worker.get(20,TimeUnit.SECONDS);
        assertAllBodiesChanged(oldBodies);assertReadyAndExact(baseline,"new");
    }

    private FinanceQueryProjections projections(JdbcTemplate jdbc){
        return projections(jdbc,FULL_PADDING,FIRST_PADDING,SPEND_PADDING,"stable");
    }
    private FinanceQueryProjections smallProjections(JdbcTemplate jdbc){
        return smallProjections(jdbc,"stable");
    }
    private FinanceQueryProjections smallProjections(JdbcTemplate jdbc,String marker){
        return projections(jdbc,"f".repeat(300_000),"d".repeat(100_000),"s".repeat(300_000),marker);
    }
    private FinanceQueryProjections projections(JdbcTemplate jdbc,String fullPadding,String firstPadding,String spendPadding,String marker){
        var repo=new FinanceRepository(jdbc,new ObjectMapper());
        var finance=new FinanceService(repo,null,fixture.auth,fixture.fx){
            @Override Map<String,Object> buildNativeDashboardFacts(Scope scope,Filter filter,boolean live){
                var result=new LinkedHashMap<>(super.buildNativeDashboardFacts(scope,filter,live));
                var buckets=new ArrayList<Map<String,Object>>();for(var original:rows(result.get("buckets"))){var bucket=new LinkedHashMap<>(original);
                    bucket.put("transport_decimal",new BigDecimal("12.123456789012345678"));bucket.put("unknown_amount",null);bucket.put("opaque","dashboard-"+marker);buckets.add(bucket);}
                if(buckets.isEmpty())throw new AssertionError("Real dashboard query returned no seeded fee facts");result.put("buckets",buckets);
                result.put("padding",filter.start().equals(filter.end())?firstPadding:fullPadding);return result;
            }
        };
        var spend=new FinanceAdSpendService(repo,finance){
            @Override Map<String,Object> buildNativeSpendFacts(Scope scope,Filter filter){
                var result=new LinkedHashMap<>(super.buildNativeSpendFacts(scope,filter));var sources=new ArrayList<Map<String,Object>>();
                for(var original:rows(result.get("sources"))){var source=new LinkedHashMap<>(original);source.put("transport_decimal",new BigDecimal("0.000000000000000123"));
                    source.put("unknown_amount",null);source.put("opaque","spend-"+marker);sources.add(source);}
                if(sources.isEmpty())throw new AssertionError("Real spend query returned no seeded ad facts");result.put("sources",sources);result.put("padding",spendPadding);return result;
            }
        };
        return new FinanceQueryProjections(repo,finance,spend);
    }

    private void enqueueNoopIncremental(){
        fixture.db.update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1,requested_at=UTC_TIMESTAMP(6),next_attempt_at=UTC_TIMESTAMP(6)");
        fixture.db.update("INSERT INTO finance_query_dirty_days(organization_id,platform_filter,partition_month,record_date,requested_revision) SELECT organization_id,platform_filter,partition_month,partition_month,requested_revision FROM finance_query_partitions");
    }
    private void changeRealFacts(){
        expectedFee=new BigDecimal("21.654321");expectedAd=new BigDecimal("0.654321");
        fixture.db.update("UPDATE finance_records SET amount=? WHERE organization_id=? AND external_id='payload-fee'",expectedFee,fixture.owner.organizationId());
        fixture.db.update("UPDATE finance_records SET amount=? WHERE organization_id=? AND external_id='payload-ad'",expectedAd,fixture.owner.organizationId());
        fixture.db.update("DELETE FROM finance_query_events");enqueueNoopIncremental();
    }
    private void assertPacketShape(ObservingJdbc jdbc){
        assertEquals(3,jdbc.payloadBytes.size());
        assertTrue(jdbc.payloadBytes.stream().allMatch(size->size<CONNECTION_PACKET),jdbc.payloadBytes.toString());
        assertTrue(jdbc.payloadBytes.stream().mapToLong(Long::longValue).sum()>CONNECTION_PACKET,jdbc.payloadBytes.toString());
    }
    private void assertReadyAndExact(FinanceQueryProjections projections,String marker){
        assertEquals("READY",projections.version(fixture.owner,MONTH).get("status"));
        var dashboard=projections.findForQuery(fixture.owner,MONTH,false).orElseThrow();
        var bucket=rows(dashboard.facts().get("buckets")).stream().filter(row->"FEE".equals(row.get("kind"))&&"USD".equals(row.get("currency"))).findFirst().orElseThrow();
        assertEquals(0,expectedFee.compareTo(decimal(bucket.get("amount"))));assertEquals(new BigDecimal("12.123456789012345678"),decimal(bucket.get("transport_decimal")));
        assertTrue(bucket.containsKey("unknown_amount"));assertNull(bucket.get("unknown_amount"));
        assertEquals("dashboard-"+marker,bucket.get("opaque"));
        var advertising=projections.findForQuery(fixture.owner,MONTH,true).orElseThrow();
        var source=rows(advertising.spendFacts().get("sources")).stream().filter(row->"USD".equals(row.get("currency"))).findFirst().orElseThrow();
        assertEquals(0,expectedAd.compareTo(decimal(source.get("amount"))));assertEquals(new BigDecimal("0.000000000000000123"),decimal(source.get("transport_decimal")));
        assertTrue(source.containsKey("unknown_amount"));assertNull(source.get("unknown_amount"));assertEquals("spend-"+marker,source.get("opaque"));
    }
    private void assertAllBodiesChanged(List<String> oldBodies){var current=storedPayloads();assertEquals(3,current.size());for(int i=0;i<3;i++)assertNotEquals(oldBodies.get(i),current.get(i),"payload segment "+i+" must publish its distinct new body");}
    private List<String> storedPayloads(){return List.of(
        fixture.db.queryForObject("SELECT full_month_json FROM finance_query_partitions",String.class),
        fixture.db.queryForObject("SELECT partial_first_day_json FROM finance_query_partitions",String.class),
        fixture.db.queryForObject("SELECT spend_json FROM finance_query_partitions",String.class));}
    private Map<String,Object> publishedState(){return fixture.db.queryForMap("SELECT full_month_json,partial_first_day_json,spend_json,built_revision,schema_version,generated_at,full_rebuild FROM finance_query_partitions");}
    private List<Map<String,Object>> dirtyDays(){return fixture.db.queryForList("SELECT * FROM finance_query_dirty_days ORDER BY record_date");}
    private static String sqlState(Throwable failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql)return sql.getSQLState();
        return "";
    }
    private static long bytes(Object value){return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;}

    static class ObservingJdbc extends JdbcTemplate {
        final List<Long> payloadBytes=new CopyOnWriteArrayList<>();final List<Integer> laterAffected=new CopyOnWriteArrayList<>();
        final AtomicReference<RuntimeException> failure=new AtomicReference<>();
        ObservingJdbc(HikariDataSource source){super(source);}
        @Override public int update(String sql,Object...args){
            if(sql.startsWith("UPDATE finance_query_partitions SET full_month_json=?")){
                payloadBytes.add(bytes(args[0]));
                if(sql.contains("partial_first_day_json=?")){payloadBytes.add(bytes(args[1]));payloadBytes.add(bytes(args[2]));}
            }
            else if(sql.startsWith("UPDATE finance_query_partitions SET partial_first_day_json=?"))payloadBytes.add(bytes(args[0]));
            else if(sql.startsWith("UPDATE finance_query_partitions SET spend_json=?"))payloadBytes.add(bytes(args[0]));
            try{
                int changed=super.update(sql,args);
                if(sql.startsWith("UPDATE finance_query_partitions SET partial_first_day_json=?")||sql.startsWith("UPDATE finance_query_partitions SET spend_json=?"))laterAffected.add(changed);
                return changed;
            }catch(RuntimeException error){
                failure.compareAndSet(null,error);
                if(sql.startsWith("UPDATE finance_query_partitions SET full_month_json=?"))
                    System.out.println("projectionSaveRejected payloadBytes="+payloadBytes+" aggregate="+payloadBytes.stream().mapToLong(Long::longValue).sum()
                        +" connectionMaxAllowedPacket="+CONNECTION_PACKET+" translated="+error.getClass().getSimpleName()+" sqlState="+sqlState(error));
                throw error;
            }
        }
    }
    static final class FaultingJdbc extends ObservingJdbc {
        final String stage;final AtomicBoolean triggered=new AtomicBoolean();
        FaultingJdbc(HikariDataSource source,String stage){super(source);this.stage=stage;}
        @Override public int update(String sql,Object...args){
            int changed=super.update(sql,args);
            boolean matches=stage.equals("full")&&sql.startsWith("UPDATE finance_query_partitions SET full_month_json=?")
                ||stage.equals("first")&&sql.startsWith("UPDATE finance_query_partitions SET partial_first_day_json=?")
                ||stage.equals("spend")&&sql.startsWith("UPDATE finance_query_partitions SET spend_json=?")
                ||stage.equals("delete")&&sql.startsWith("DELETE FROM finance_query_dirty_days");
            if(matches&&triggered.compareAndSet(false,true))throw new IllegalStateException("injected "+stage+" save failure");
            return changed;
        }
    }
    static final class PausingJdbc extends ObservingJdbc {
        final CountDownLatch written=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicBoolean paused=new AtomicBoolean();
        PausingJdbc(HikariDataSource source){super(source);}
        @Override public int update(String sql,Object...args){
            int changed=super.update(sql,args);
            if(sql.startsWith("UPDATE finance_query_partitions SET partial_first_day_json=?")&&paused.compareAndSet(false,true)){
                written.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("reader did not release save");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            }
            return changed;
        }
    }
}
