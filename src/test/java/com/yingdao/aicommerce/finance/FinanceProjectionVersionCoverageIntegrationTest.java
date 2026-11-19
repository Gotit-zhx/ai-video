package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real disposable-MySQL and runner-Redis REDs for source identities omitted by the V155 UPDATE guard.
 * Each test names the durable trigger change that should make it pass: register the one changed identity.
 */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC", matches=".+")
@EnabledIfEnvironmentVariable(named="HEYI_DESIGN_HTTP_REDIS_PORT", matches="\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value=600,unit=TimeUnit.SECONDS)
class FinanceProjectionVersionCoverageIntegrationTest {
    private static final LocalDate START=LocalDate.of(2026,10,1),END=LocalDate.of(2026,10,31),DAY=LocalDate.of(2026,10,12);
    private static final String ONE="1".repeat(64),EIGHT="8".repeat(64),FIFTEEN="f".repeat(64);
    private static final long CACHE_TIME=Instant.parse("2026-10-31T00:00:00Z").toEpochMilli();
    private static final long CLEANUP_LIMIT_NANOS=TimeUnit.SECONDS.toNanos(5),CLEANUP_STEP_NANOS=TimeUnit.MILLISECONDS.toNanos(750);
    private static final long MUTATION_WAIT_MILLIS=4000;
    private static final String RECEIPT_RUN="run-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().replace("-","").substring(0,8);
    private static final Path RECEIPT_DIRECTORY=Path.of(".superpowers/sdd/2026-11-14-finance-first-settlement-facts-prototype/evidence/20261115-projection-version-red-fix1",RECEIPT_RUN);
    private static final Path TEST_SOURCE=Path.of("src/test/java/com/yingdao/aicommerce/finance/FinanceProjectionVersionCoverageIntegrationTest.java");
    private static final JsonMapper CANONICAL_JSON=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    private Scope scope;private String shop,caseName,cachePattern;
    private FinanceQueryProjections projections;private FinanceWorkspaceQueryService queries;
    private org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisFactory;
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    private FinanceReadCache cache;private ExecutorService cacheExecutor,writerExecutor;private Future<?> writerFuture;
    private OwnedMutation activeMutation;private MutationReceipt lastMutation;
    private CleanupResult cleanupResult;private boolean cleanupRan,expectCleanupFailure;
    private Map<String,Object> runEvidence=Map.of();private String declaredTest;private volatile Throwable testFailure;
    private final AtomicLong cacheClock=new AtomicLong(CACHE_TIME),pendingClock=new AtomicLong();

    @RegisterExtension final TestExecutionExceptionHandler failureCapture=(context,cause)->{testFailure=cause;throw cause;};

    @BeforeAll void schema()throws Exception{fixture.migrate();}
    @BeforeEach void reset(TestInfo info){
        scope=null;shop=null;caseName=null;cachePattern=null;projections=null;queries=null;redisFactory=null;redis=null;cache=null;cacheExecutor=null;writerExecutor=null;writerFuture=null;activeMutation=null;lastMutation=null;
        cleanupResult=null;cleanupRan=false;expectCleanupFailure=false;runEvidence=Map.of();testFailure=null;declaredTest=info.getTestMethod().map(java.lang.reflect.Method::getName).orElse(info.getDisplayName());
        fixture.setup();for(String table:List.of("finance_query_dirty_days","finance_query_partitions","finance_query_events"))fixture.db.update("DELETE FROM "+table);
    }
    @AfterEach void cleanup(){
        if(!cleanupRan){cleanupResult=cleanupOwnedResources();cleanupRan=true;}
        System.out.println("FINANCE_PROJECTION_VERSION_COVERAGE_CLEANUP "+fixture.repo.write(cleanupResult.receipt()));
        String status=testFailure!=null?"FAILED":!cleanupResult.success()&&!expectCleanupFailure?"FAILED_CLEANUP":"SUCCESS";writeRunReceipt(status,testFailure);
        if(expectCleanupFailure){assertFalse(cleanupResult.success(),"The injected cleanup failure must remain visible");assertFalse(cleanupResult.errors().isEmpty());}
        else if(!cleanupResult.success())throw cleanupResult.failure();
    }
    @AfterAll void close(){fixture.closeDataSource();}

    @Test void externalIdOnlyCommitMustFenceTheWarmOldNumberPage()throws Exception{
        startCase("external-id");
        var entry=new Entry(shop,"SHOPEE","FEE","old-number","","","pay_per_sale","SETTLED",DAY,"USD",decimal("12.123456"),null,null,null,null,"API",Map.of());
        fixture.repo.save(scope,entry);String recordId=id(scope,entry);
        buildInitial();
        Filter usd=month("USD");var oldSelection=selection("fees","FEE","old-number");var newSelection=selection("fees","FEE","new-number");
        State initial=readState(()->externalSource(recordId,usd),()->oracleInside(usd,oldSelection));
        Outcome warmFirst=capture(()->queries.query(scope,scope,usd,oldSelection));
        Outcome warmSecond=capture(()->queries.query(scope,scope,usd,oldSelection));
        String revision=readyRevision(initial);assertExternal(initial.source(),"old-number",1,0);requireOutcomeEquals(initial.oracle(),warmFirst,"initial old-number page");requireOutcomeEquals(initial.oracle(),warmSecond,"warm old-number page");assertWarmRedis(warmFirst,warmSecond);

        MutationReceipt rollback=mutate("rollback","UPDATE finance_records SET external_id=? WHERE id=?",List.of("new-number",recordId),false,null);
        SnapshotPage rollbackState=readSnapshotPage(()->externalSource(recordId,usd),()->oracleInside(usd,oldSelection),oldSelection);
        assertAll("external_id rollback remains invisible",
            ()->assertExternal(rollbackState.state().source(),"old-number",1,0),
            ()->assertVersion(rollbackState.state(),"READY",revision,0),
            ()->assertEquals(initial.oracle().hash(),rollbackState.state().oracle().hash()),
            ()->requireOutcomeEquals(initial.oracle(),rollbackState.page(),"rollback page"),
            ()->assertTrue(rollback.rolledBack()));

        OldSnapshot old=holdOldSnapshotAndCommit(
            ()->externalSource(recordId,usd),()->oracleInside(usd,oldSelection),oldSelection,
            "UPDATE finance_records SET external_id=? WHERE id=?",List.of("new-number",recordId));
        assertAll("external_id A retains one real old snapshot",
            ()->assertExternal(old.before().source(),"old-number",1,0),
            ()->assertExternal(old.after().source(),"old-number",1,0),
            ()->assertEquals(canonical(old.before().source()),canonical(old.after().source())),
            ()->assertVersion(old.before(),"READY",revision,0),
            ()->assertVersion(old.after(),"READY",revision,0),
            ()->assertEquals(initial.oracle().hash(),old.after().oracle().hash()),
            ()->requireOutcomeEquals(initial.oracle(),old.beforePage(),"A before B commit"),
            ()->requireOutcomeEquals(initial.oracle(),old.afterPage(),"A after B commit"),
            ()->assertNotEquals(old.after().snapshot().get("connectionId"),old.commit().connectionId()));

        DualState next=readDualState(()->externalSource(recordId,usd),()->oracleInside(usd,oldSelection),()->oracleInside(usd,newSelection));
        Outcome oldNumber=capture(()->queries.query(scope,scope,usd,oldSelection));
        Outcome newNumber=capture(()->queries.query(scope,scope,usd,newSelection));
        DualState stable=readDualState(()->externalSource(recordId,usd),()->oracleInside(usd,oldSelection),()->oracleInside(usd,newSelection));
        printReceipt("external_id",revision,rollback,rollbackState,old,next,oldNumber,newNumber,stable);

        assertAll("external_id-only must register a pending complete-page revision",
            ()->assertExternal(next.primary().source(),"new-number",0,1),
            ()->assertFrozen(next,stable),
            ()->assertTrue(next.primary().events()>0,"Committed external_id change must create a real finance_query_event"),
            ()->assertEquals("PENDING",next.primary().version().get("status")),
            ()->assertEquals(revision,next.primary().version().get("revision")),
            ()->requireOutcomeEquals(next.primary().oracle(),oldNumber,"next old-number page"),
            ()->requireOutcomeEquals(next.secondary(),newNumber,"next new-number page"),
            ()->requireOutcomeDifferent(initial.oracle(),oldNumber,"old Redis business page"));

        postBuild(revision,usd,oldSelection,next.primary().oracle());
    }

    @Test void idOnlyCommitMustFenceTheWarmFormerUsdWinnerPage()throws Exception{
        startCase("id-winner");String order="winner-order";
        settlement(ONE,"winner-usd",order,"USD");settlement(EIGHT,"winner-php",order,"PHP");
        var orderEntry=new Entry(shop,"SHOPEE","ORDER","api-order",order,"","","COMPLETED",DAY,"USD",decimal("100"),null,null,null,null,"API",Map.of());
        fixture.repo.save(scope,orderEntry);buildInitial();
        Filter usd=month("USD"),php=month("PHP");var usdSelection=selection("orders","ORDER","");var phpSelection=selection("orders","ORDER","");
        State initial=readState(()->winnerSource(order,usd,php),()->oracleInside(usd,usdSelection));
        Outcome warmFirst=capture(()->queries.query(scope,scope,usd,usdSelection));Outcome warmSecond=capture(()->queries.query(scope,scope,usd,usdSelection));
        String revision=readyRevision(initial);assertWinner(initial.source(),ONE,"USD",1,0);requireOutcomeEquals(initial.oracle(),warmFirst,"initial USD winner page");requireOutcomeEquals(initial.oracle(),warmSecond,"warm USD winner page");assertWarmRedis(warmFirst,warmSecond);

        MutationReceipt rollback=mutate("rollback","UPDATE finance_records SET id=? WHERE id=?",List.of(FIFTEEN,ONE),false,null);
        SnapshotPage rollbackState=readSnapshotPage(()->winnerSource(order,usd,php),()->oracleInside(usd,usdSelection),usdSelection);
        assertAll("id rollback retains the original true winner",
            ()->assertWinner(rollbackState.state().source(),ONE,"USD",1,0),
            ()->assertVersion(rollbackState.state(),"READY",revision,0),
            ()->assertEquals(initial.oracle().hash(),rollbackState.state().oracle().hash()),
            ()->requireOutcomeEquals(initial.oracle(),rollbackState.page(),"rollback USD page"),
            ()->assertTrue(rollback.rolledBack()));

        OldSnapshot old=holdOldSnapshotAndCommit(
            ()->winnerSource(order,usd,php),()->oracleInside(usd,usdSelection),usdSelection,
            "UPDATE finance_records SET id=? WHERE id=?",List.of(FIFTEEN,ONE));
        assertAll("id-only A retains the literal old winner",
            ()->assertWinner(old.before().source(),ONE,"USD",1,0),
            ()->assertWinner(old.after().source(),ONE,"USD",1,0),
            ()->assertEquals(canonical(old.before().source()),canonical(old.after().source())),
            ()->assertVersion(old.after(),"READY",revision,0),
            ()->assertEquals(initial.oracle().hash(),old.after().oracle().hash()),
            ()->requireOutcomeEquals(initial.oracle(),old.afterPage(),"A warm USD page after B commit"));

        DualState next=readDualState(()->winnerSource(order,usd,php),()->oracleInside(usd,usdSelection),()->oracleInside(php,phpSelection));
        Outcome usdPage=capture(()->queries.query(scope,scope,usd,usdSelection));
        Outcome phpPage=capture(()->queries.query(scope,scope,php,phpSelection));
        DualState stable=readDualState(()->winnerSource(order,usd,php),()->oracleInside(usd,usdSelection),()->oracleInside(php,phpSelection));
        printReceipt("id",revision,rollback,rollbackState,old,next,usdPage,phpPage,stable);

        assertAll("id-only must register the changed true-winner ordering",
            ()->assertWinner(next.primary().source(),EIGHT,"PHP",0,1),
            ()->assertFrozen(next,stable),
            ()->assertTrue(next.primary().events()>0,"Committed id change must create a real finance_query_event"),
            ()->assertEquals("PENDING",next.primary().version().get("status")),
            ()->assertEquals(revision,next.primary().version().get("revision")),
            ()->requireOutcomeEquals(next.primary().oracle(),usdPage,"next USD page"),
            ()->requireOutcomeEquals(next.secondary(),phpPage,"next PHP page"),
            ()->requireOutcomeDifferent(initial.oracle(),usdPage,"former winner Redis page"));

        postBuild(revision,usd,usdSelection,next.primary().oracle());
    }

    @Test void userIdOnlyCommitMustFenceTheWarmMatchedReconciliationPage()throws Exception{
        startCase("user-reconcile");String alternate="alternate-owner";
        var historical=new Entry(shop,"SHOPEE","FEE","alternate-history","","","history","SETTLED",LocalDate.of(2026,9,15),"USD",decimal("1"),null,null,null,null,"API",Map.of());
        fixture.repo.save(new Scope(scope.organizationId(),alternate),historical);
        var bill=new Entry(shop,"SHOPEE","BILL","bill-1","","","","SETTLED",DAY,"USD",decimal("10"),null,null,null,null,"API",Map.of());
        var settlement=new Entry(shop,"SHOPEE","SETTLEMENT","settlement-1","recon-order","bill-1","","SETTLED",DAY,"USD",decimal("7"),null,null,null,null,"API",Map.of());
        fixture.repo.save(scope,bill);fixture.repo.save(scope,settlement);String billId=id(scope,bill);buildInitial();
        Filter usd=month("USD");var selected=selection("reconciliation","BILL","");
        State initial=readState(()->reconciliationSource(billId,usd),()->oracleInside(usd,selected));
        Outcome warmFirst=capture(()->queries.query(scope,scope,usd,selected));Outcome warmSecond=capture(()->queries.query(scope,scope,usd,selected));
        String revision=readyRevision(initial);assertReconciliation(initial.source(),scope.userId(),1);assertMatched(initial.oracle());requireOutcomeEquals(initial.oracle(),warmFirst,"initial matched reconciliation");requireOutcomeEquals(initial.oracle(),warmSecond,"warm matched reconciliation");assertWarmRedis(warmFirst,warmSecond);

        MutationReceipt rollback=mutate("rollback","UPDATE finance_records SET user_id=? WHERE id=?",List.of(alternate,billId),false,null);
        SnapshotPage rollbackState=readSnapshotPage(()->reconciliationSource(billId,usd),()->oracleInside(usd,selected),selected);
        assertAll("user_id rollback retains the original reconciliation",
            ()->assertReconciliation(rollbackState.state().source(),scope.userId(),1),
            ()->assertMatched(rollbackState.state().oracle()),
            ()->assertVersion(rollbackState.state(),"READY",revision,0),
            ()->requireOutcomeEquals(initial.oracle(),rollbackState.page(),"rollback reconciliation"),
            ()->assertTrue(rollback.rolledBack()));

        OldSnapshot old=holdOldSnapshotAndCommit(
            ()->reconciliationSource(billId,usd),()->oracleInside(usd,selected),selected,
            "UPDATE finance_records SET user_id=? WHERE id=?",List.of(alternate,billId));
        assertAll("user_id-only A retains the matched owner",
            ()->assertReconciliation(old.before().source(),scope.userId(),1),
            ()->assertReconciliation(old.after().source(),scope.userId(),1),
            ()->assertEquals(canonical(old.before().source()),canonical(old.after().source())),
            ()->assertMatched(old.after().oracle()),
            ()->assertVersion(old.after(),"READY",revision,0),
            ()->requireOutcomeEquals(initial.oracle(),old.afterPage(),"A matched page after B commit"));

        State next=readState(()->reconciliationSource(billId,usd),()->oracleInside(usd,selected));
        Outcome page=capture(()->queries.query(scope,scope,usd,selected));
        State stable=readState(()->reconciliationSource(billId,usd),()->oracleInside(usd,selected));
        printReceipt("user_id",revision,rollback,rollbackState,old,new DualState(next,null),page,null,new DualState(stable,null));

        assertAll("user_id-only must register the lost reconciliation owner",
            ()->assertReconciliation(next.source(),alternate,1),
            ()->assertMissing(next.oracle()),
            ()->assertFrozen(new DualState(next,null),new DualState(stable,null)),
            ()->assertTrue(next.events()>0,"Committed user_id change must create a real finance_query_event"),
            ()->assertEquals("PENDING",next.version().get("status")),
            ()->assertEquals(revision,next.version().get("revision")),
            ()->requireOutcomeEquals(next.oracle(),page,"next missing reconciliation"),
            ()->requireOutcomeDifferent(initial.oracle(),page,"matched Redis reconciliation"));

        postBuild(revision,usd,selected,next.oracle());
    }

    @Test void currencyByteOnlyCommitMustFenceTheWarmUsdFeePage()throws Exception{
        startCase("currency-byte");
        var entry=new Entry(shop,"SHOPEE","FEE","currency-fee","","","pay_per_sale","SETTLED",DAY,"USD",decimal("12.123456"),null,null,null,null,"API",Map.of());
        fixture.repo.save(scope,entry);String recordId=id(scope,entry);buildInitial();
        Filter usd=month("USD");var selected=selection("fees","FEE","");
        var column=fixture.db.queryForMap("""
            SELECT column_type,character_set_name,collation_name
            FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='finance_records' AND column_name='currency'
            """);
        assertEquals("varchar(8)",String.valueOf(column.get("column_type")).toLowerCase(Locale.ROOT));
        assertFalse(text(column.get("character_set_name")).isBlank());assertFalse(text(column.get("collation_name")).isBlank());

        State initial=readState(()->currencySource(recordId,usd),()->oracleInside(usd,selected));
        Outcome warmFirst=capture(()->queries.query(scope,scope,usd,selected));Outcome warmSecond=capture(()->queries.query(scope,scope,usd,selected));
        String revision=readyRevision(initial);var baseline=currencyRow(initial.source());
        assertCurrencySource(initial.source(),"USD","555344",baseline);assertCurrencyBusiness(initial.oracle(),"USD");
        requireOutcomeEquals(initial.oracle(),warmFirst,"initial USD fee page");requireOutcomeEquals(initial.oracle(),warmSecond,"warm USD fee page");assertWarmRedis(warmFirst,warmSecond);

        MutationReceipt rollback=mutate("rollback","UPDATE finance_records SET currency=? WHERE id=?",List.of("usd",recordId),false,null);
        SnapshotPage rollbackState=readSnapshotPage(()->currencySource(recordId,usd),()->oracleInside(usd,selected),selected);
        assertAll("currency byte rollback remains invisible",
            ()->assertCurrencySource(rollbackState.state().source(),"USD","555344",baseline),
            ()->assertVersion(rollbackState.state(),"READY",revision,0),
            ()->assertEquals(initial.oracle().hash(),rollbackState.state().oracle().hash()),
            ()->requireOutcomeEquals(initial.oracle(),rollbackState.page(),"currency rollback page"),
            ()->assertTrue(rollback.rolledBack()));

        OldSnapshot old=holdOldSnapshotAndCommit(
            ()->currencySource(recordId,usd),()->oracleInside(usd,selected),selected,
            "UPDATE finance_records SET currency=? WHERE id=?",List.of("usd",recordId));
        assertAll("currency byte A retains one real old snapshot",
            ()->assertCurrencySource(old.before().source(),"USD","555344",baseline),
            ()->assertCurrencySource(old.after().source(),"USD","555344",baseline),
            ()->assertEquals(canonical(old.before().source()),canonical(old.after().source())),
            ()->assertVersion(old.before(),"READY",revision,0),
            ()->assertVersion(old.after(),"READY",revision,0),
            ()->assertEquals(initial.oracle().tree(),old.after().oracle().tree()),
            ()->requireOutcomeEquals(initial.oracle(),old.beforePage(),"A USD page before B commit"),
            ()->requireOutcomeEquals(initial.oracle(),old.afterPage(),"A warm USD page after B commit"),
            ()->assertNotEquals(old.after().snapshot().get("connectionId"),old.commit().connectionId()));

        State next=readState(()->currencySource(recordId,usd),()->oracleInside(usd,selected));
        Outcome page=capture(()->queries.query(scope,scope,usd,selected));
        State stable=readState(()->currencySource(recordId,usd),()->oracleInside(usd,selected));
        printCurrencyReceipt(column,revision,rollback,rollbackState,old,initial,warmFirst,warmSecond,next,page,stable);

        assertAll("currency SQL-equivalent byte change must fence the complete USD fee page",
            ()->assertCurrencySource(next.source(),"usd","757364",baseline),
            ()->assertCurrencyBusiness(next.oracle(),"usd"),
            ()->assertEquals(canonical(next.source()),canonical(stable.source())),
            ()->assertEquals(next.oracle().tree(),stable.oracle().tree()),
            ()->assertTrue(next.events()>0,"Committed currency byte change must create a real finance_query_event"),
            ()->assertEquals("PENDING",next.version().get("status")),
            ()->assertEquals(revision,next.version().get("revision")),
            ()->requireOutcomeEquals(next.oracle(),page,"next USD-filtered native page"),
            ()->requireOutcomeDifferent(initial.oracle(),page,"old Redis USD business page"));

        postBuild(revision,usd,selected,next.oracle());
    }

    @Test void redisCleanupFailureStillClosesEveryLaterOwnedResource()throws Exception{
        startCase("cleanup-fault");
        redis.opsForValue().set(FinanceReadCache.prefix(companyScope(scope.organizationId()))+"control","created");
        try(var connection=fixture.dataSource.getConnection();var statement=connection.createStatement();var row=statement.executeQuery("SELECT 1")){assertTrue(row.next());assertEquals(1,row.getInt(1));}
        doThrow(new IllegalStateException("injected redis namespace cleanup failure")).when(redis).keys(cachePattern);expectCleanupFailure=true;
        cleanupResult=cleanupOwnedResources();cleanupRan=true;
        runEvidence=Map.of("control","redis-cleanup-failure","injected","redis.keys","cleanup",cleanupResult.receipt(),"post","NOT_APPLICABLE");
        assertAll("cleanup failure remains visible while later resources close",
            ()->assertFalse(cleanupResult.success()),
            ()->assertTrue(cleanupResult.errors().stream().anyMatch(error->error.contains("injected redis namespace cleanup failure"))),
            ()->assertTrue(cleanupResult.cacheExecutorTerminated()),
            ()->assertTrue(cleanupResult.lettuceStopped()),
            ()->assertEquals(0,cleanupResult.activeHikariConnections()),
            ()->assertTrue(cleanupResult.withinDeadline()));
    }

    @Test void lockedUpdateCancellationClosesRealJdbcWorkAndCannotCommit()throws Exception{
        startCase("blocked-update");
        var entry=new Entry(shop,"SHOPEE","FEE","locked-old","","","control","SETTLED",DAY,"USD",decimal("1"),null,null,null,null,"API",Map.of());fixture.repo.save(scope,entry);String recordId=id(scope,entry);buildInitial();
        var released=new AtomicBoolean();MutationTimeoutException timeout;
        try(var locker=fixture.dataSource.getConnection()){
            try{
                locker.setAutoCommit(false);try(var statement=locker.prepareStatement("SELECT id FROM finance_records WHERE id=? FOR UPDATE")){statement.setString(1,recordId);try(var row=statement.executeQuery()){assertTrue(row.next());}}
                Runnable release=()->{if(released.compareAndSet(false,true))try{locker.rollback();}catch(SQLException failure){throw new CompletionException(failure);}};
                timeout=assertThrows(MutationTimeoutException.class,()->mutate("blocked","UPDATE finance_records SET external_id=? WHERE id=?",List.of("locked-new",recordId),true,null,100,true,release));
            }finally{if(released.compareAndSet(false,true))locker.rollback();}
        }
        var source=fixture.repo.readSnapshot(30,()->externalControlSource(recordId));var receipt=timeout.receipt();
        runEvidence=Map.of("control","blocked-update-cancel","mutation",receipt.receipt(),"source",canonical(source),"events",eventCount(),"post","NOT_APPLICABLE");
        assertAll("the timed-out real UPDATE is canceled rather than committed",
            ()->assertTrue(receipt.cancelRequested()),
            ()->assertTrue(receipt.updateStarted()),
            ()->assertFalse(receipt.committed()),
            ()->assertTrue(receipt.statementClosed()),
            ()->assertTrue(receipt.connectionClosed()),
            ()->assertTrue(receipt.executorTerminated()),
            ()->assertEquals("locked-old",source.get("external_id")),
            ()->assertEquals(0,eventCount()),
            ()->assertTrue(receipt.finishedNanos()<=receipt.hardDeadlineNanos()));
    }

    @Test void subMillisecondCleanupBudgetNeverBecomesAnUnboundedJoin()throws Exception{
        startCase("join-boundary");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);var releaseFinished=new CountDownLatch(1);
        var releaser=new Thread(()->{try{entered.await(20,TimeUnit.MILLISECONDS);LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));}catch(InterruptedException failure){Thread.currentThread().interrupt();}finally{release.countDown();releaseFinished.countDown();}},"pvc-join-boundary-release");releaser.setDaemon(true);releaser.start();
        long started=System.nanoTime(),deadline=started+TimeUnit.MILLISECONDS.toNanos(5),returned;var errors=new ArrayList<Throwable>();boolean actionResult;
        try{
            actionResult=boundedAction("join boundary",deadline,TimeUnit.MICROSECONDS.toNanos(4500),()->{entered.countDown();try{for(;;)try{release.await();break;}catch(InterruptedException ignored){/* The external bounded releaser owns final termination. */}}finally{exited.countDown();}},errors);returned=System.nanoTime();
        }finally{release.countDown();assertTrue(releaseFinished.await(200,TimeUnit.MILLISECONDS));releaser.join(200);assertFalse(releaser.isAlive());assertTrue(exited.await(200,TimeUnit.MILLISECONDS));}
        long elapsed=returned-started,allowed=TimeUnit.MILLISECONDS.toNanos(20);var evidence=new LinkedHashMap<String,Object>();evidence.put("control","join-boundary");evidence.put("startedNanos",started);evidence.put("deadlineNanos",deadline);evidence.put("returnedNanos",returned);evidence.put("elapsedNanos",elapsed);evidence.put("allowedNanos",allowed);evidence.put("actionResult",actionResult);evidence.put("errors",errors.stream().map(FinanceProjectionVersionCoverageIntegrationTest::failure).toList());evidence.put("externalReleaseFinished",releaseFinished.getCount()==0);evidence.put("actionTerminal",exited.getCount()==0);evidence.put("post","NOT_APPLICABLE");runEvidence=evidence;
        assertAll("expired or sub-millisecond cleanup budget remains finite",
            ()->assertFalse(actionResult),
            ()->assertFalse(errors.isEmpty()),
            ()->assertTrue(elapsed<=allowed,"The cleanup wait exceeded its finite deadline boundary: "+elapsed),
            ()->assertEquals(0,exited.getCount()),
            ()->assertEquals(0,releaseFinished.getCount()));
    }

    private void startCase(String name){
        caseName=name;String suffix=UUID.randomUUID().toString().replace("-","").substring(0,10);
        scope=new Scope("pvc-"+name+"-"+suffix,"u-"+suffix);shop=fixture.connect(scope,false);
        redisFactory=redisFactory();redisFactory.afterPropertiesSet();redisFactory.start();
        var template=new org.springframework.data.redis.core.StringRedisTemplate(redisFactory);template.afterPropertiesSet();redis=spy(template);
        cacheExecutor=Executors.newFixedThreadPool(2,r->{var thread=new Thread(r,"pvc-cache-"+name);thread.setDaemon(true);return thread;});
        cacheClock.set(CACHE_TIME);pendingClock.set(0);
        cache=new FinanceReadCache(redis,new tools.jackson.databind.ObjectMapper(),cacheClock::get,cacheExecutor,true);
        var spend=new FinanceAdSpendService(fixture.repo,fixture.service);projections=new FinanceQueryProjections(fixture.repo,fixture.service,spend);
        queries=new FinanceWorkspaceQueryService(fixture.repo,fixture.service,spend,projections,mock(FinanceAds.class),cache,pendingClock::get,pendingClock::addAndGet);
        cachePattern=FinanceReadCache.prefix(companyScope(scope.organizationId()))+"*";
    }
    private Filter month(String currency){return new Filter(START,END,"","",currency,"day");}
    private FinanceWorkspaceQueryService.Selection selection(String view,String kind,String numbers){return new FinanceWorkspaceQueryService.Selection(view,kind,false,false,"",1,1,numbers,"","");}
    private Map<String,Object> externalControlSource(String id){return fixture.db.queryForMap("SELECT external_id,user_id,shop_id,record_date,currency,amount FROM finance_records WHERE organization_id=? AND id=?",scope.organizationId(),id);}

    private void settlement(String recordId,String external,String order,String currency){
        fixture.db.update("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,
              statement_id,category,status,record_date,currency,source,raw_json)
            VALUES(?,?,?,?, 'SHOPEE','SETTLEMENT',?,?,'','','SETTLED',?,?,'API','{}')
            """,recordId,scope.organizationId(),scope.userId(),shop,external,order,java.sql.Date.valueOf(DAY),currency);
    }
    private void buildInitial(){
        projections.request(scope,month(""));
        int rounds=0;while(rounds++<20&&projections.drainEvents()>0){}
        rounds=0;while(rounds++<20&&projections.buildNext()){}
        assertEquals(0,eventCount());assertEquals("READY",projections.version(scope,month("")).get("status"));assertTrue(projections.find(scope,month("")).isPresent());
    }
    private String readyRevision(State state){assertVersion(state,"READY",text(state.version().get("revision")),0);assertFalse(text(state.version().get("revision")).isBlank());return text(state.version().get("revision"));}

    private State readState(Supplier<Map<String,Object>> source,Supplier<Business> oracle){return fixture.repo.readSnapshot(30,()->{var witness=new SnapshotWitness(fixture.dataSource);return observeInside(source,oracle,witness);});}
    private DualState readDualState(Supplier<Map<String,Object>> source,Supplier<Business> primary,Supplier<Business> secondary){
        return fixture.repo.readSnapshot(30,()->{var witness=new SnapshotWitness(fixture.dataSource);var state=observeInside(source,primary,witness);return new DualState(state,secondary.get());});
    }
    private SnapshotPage readSnapshotPage(Supplier<Map<String,Object>> source,Supplier<Business> oracle,FinanceWorkspaceQueryService.Selection selection){
        return fixture.repo.readSnapshot(30,()->{var witness=new SnapshotWitness(fixture.dataSource);var state=observeInside(source,oracle,witness);var page=capture(()->queries.query(scope,scope,requestedFilter(selection),selection));witness.verify();return new SnapshotPage(state,page);});
    }
    private OldSnapshot holdOldSnapshotAndCommit(Supplier<Map<String,Object>> source,Supplier<Business> oracle,FinanceWorkspaceQueryService.Selection selection,String sql,List<Object> args){
        return fixture.repo.readSnapshot(30,()->{
            var witness=new SnapshotWitness(fixture.dataSource);var before=observeInside(source,oracle,witness);var beforePage=capture(()->queries.query(scope,scope,requestedFilter(selection),selection));
            var commit=mutate("commit",sql,args,true,witness);witness.verify();var after=observeInside(source,oracle,witness);var afterPage=capture(()->queries.query(scope,scope,requestedFilter(selection),selection));witness.verify();
            return new OldSnapshot(before,after,beforePage,afterPage,commit);
        });
    }
    private Filter requestedFilter(FinanceWorkspaceQueryService.Selection selection){
        if(selection.view().equals("orders")&&caseName.equals("id-winner"))return month("USD");
        return month("USD");
    }
    private State observeInside(Supplier<Map<String,Object>> source,Supplier<Business> oracle,SnapshotWitness witness){
        witness.verify();var actualSource=source.get();var version=projections.version(scope,month(""));long events=eventCount();var eventRows=eventRows();var business=oracle.get();witness.verify();
        return new State(actualSource,version,events,eventRows,business,witness.receipt());
    }

    private MutationReceipt mutate(String mode,String sql,List<Object> args,boolean commit,SnapshotWitness reader){
        return mutate(mode,sql,args,commit,reader,MUTATION_WAIT_MILLIS,false,()->{});
    }
    private MutationReceipt mutate(String mode,String sql,List<Object> args,boolean commit,SnapshotWitness reader,long waitMillis,boolean expectTimeout,Runnable timeoutHook){
        if(writerExecutor!=null&&!writerExecutor.isTerminated())throw new IllegalStateException("A previous writer is still active");
        long dispatched=System.nanoTime(),holderRoom=reader==null?CLEANUP_LIMIT_NANOS:TimeUnit.MILLISECONDS.toNanos(Math.max(1,reader.holder.getTimeToLiveInMillis()));
        long hardDeadline=dispatched+Math.min(CLEANUP_LIMIT_NANOS,holderRoom);var work=new OwnedMutation(mode,commit,waitMillis,dispatched,hardDeadline);activeMutation=work;
        writerExecutor=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"pvc-"+caseName+"-"+mode);thread.setDaemon(true);return thread;});
        var task=new FutureTask<MutationReceipt>(()->mutation(work,sql,args));writerFuture=task;writerExecutor.execute(task);
        Throwable failure=null;MutationReceipt receipt=null;
        try{
            long enterRoom=Math.min(TimeUnit.SECONDS.toNanos(1),remaining(hardDeadline));
            if(enterRoom<=0||!work.updateEntered.await(enterRoom,TimeUnit.NANOSECONDS))throw new TimeoutException("B did not enter its registered UPDATE before the owned deadline");
            long operationRoom=remaining(work.operationDeadlineNanos);if(operationRoom<=0)throw new TimeoutException("B exhausted its statement deadline before waiting");
            receipt=task.get(operationRoom,TimeUnit.NANOSECONDS);
            if(expectTimeout)failure=new AssertionError("The locked B UPDATE unexpectedly completed before cancellation");
        }catch(ExecutionException execution){failure=execution.getCause();}
        catch(TimeoutException timeout){failure=timeout;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();failure=interrupted;}
        if(failure!=null||expectTimeout){
            var cancellationErrors=new ArrayList<Throwable>();work.cancelRequested.set(true);task.cancel(true);
            cancelStatement(work,hardDeadline,cancellationErrors);try{timeoutHook.run();}catch(Throwable hookFailure){cancellationErrors.add(hookFailure);}
            try{long settle=Math.min(remaining(hardDeadline),TimeUnit.MILLISECONDS.toNanos(250));if(settle>0)work.finishedLatch.await(settle,TimeUnit.NANOSECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();cancellationErrors.add(interrupted);}
            if(!work.finished.get())closeConnection(work,hardDeadline,cancellationErrors);
            writerExecutor.shutdownNow();awaitWriterTerminal(work,hardDeadline,cancellationErrors);receipt=work.receipt(writerExecutor.isTerminated());lastMutation=receipt;
            if(work.finished.get())activeMutation=null;
            if(expectTimeout){var timeout=new MutationTimeoutException(receipt,failure);cancellationErrors.forEach(timeout::addSuppressed);throw timeout;}
            var propagated=propagate(failure);cancellationErrors.forEach(propagated::addSuppressed);throw propagated;
        }
        writerExecutor.shutdown();var terminalErrors=new ArrayList<Throwable>();awaitWriterTerminal(work,hardDeadline,terminalErrors);receipt=work.receipt(writerExecutor.isTerminated());lastMutation=receipt;if(work.finished.get())activeMutation=null;
        if(reader!=null)assertNotEquals(reader.connectionId,receipt.connectionId(),"B must use another physical MySQL connection");
        if(!terminalErrors.isEmpty()){var incomplete=new IllegalStateException("B did not close inside its original owned deadline");terminalErrors.forEach(incomplete::addSuppressed);throw incomplete;}
        assertTrue(receipt.connectionClosed());assertTrue(receipt.statementClosed());assertTrue(receipt.executorTerminated());return receipt;
    }
    private MutationReceipt mutation(OwnedMutation work,String sql,List<Object> args)throws Exception{
        work.threadId=Thread.currentThread().threadId();work.threadName=Thread.currentThread().getName();Connection connection=null;PreparedStatement statement=null;Throwable failure=null;
        try{
            if(work.cancelRequested.get())throw new CancellationException("Cancelled before database borrow");
            connection=fixture.dataSource.getConnection();work.connection.set(connection);connection.setAutoCommit(false);connection.setReadOnly(false);
            try(var id=connection.createStatement();var row=id.executeQuery("SELECT CONNECTION_ID()")){assertTrue(row.next());work.connectionId=row.getLong(1);}
            if(work.cancelRequested.get())throw new CancellationException("Cancelled before statement creation");
            statement=connection.prepareStatement(sql);work.statement.set(statement);for(int i=0;i<args.size();i++)statement.setObject(i+1,args.get(i));work.operationDeadlineNanos=Math.min(work.hardDeadlineNanos,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(work.waitMillis));
            setStatementDeadline(statement,work.operationDeadlineNanos);work.updateStarted.set(true);work.updateEntered.countDown();
            if(work.cancelRequested.get())throw new CancellationException("Cancelled before UPDATE");
            work.updated.set(statement.executeUpdate());assertEquals(1,work.updated.get(),"B must change exactly one source row");
            if(work.cancelRequested.get()||System.nanoTime()>=work.operationDeadlineNanos){connection.rollback();work.rolledBack.set(true);throw new CancellationException("Cancelled before commit");}
            if(work.commitRequested){connection.commit();work.committed.set(true);}else{connection.rollback();work.rolledBack.set(true);}
        }catch(Throwable problem){failure=problem;work.failure=problem;if(connection!=null&&!work.committed.get())try{connection.rollback();work.rolledBack.set(true);}catch(Throwable rollbackFailure){problem.addSuppressed(rollbackFailure);}}
        finally{
            if(statement!=null)try{statement.close();work.statementClosed.set(statement.isClosed());}catch(Throwable closeFailure){if(failure!=null)failure.addSuppressed(closeFailure);else failure=closeFailure;}finally{work.statement.compareAndSet(statement,null);}
            if(connection!=null)try{connection.close();work.connectionClosed.set(connection.isClosed());}catch(Throwable closeFailure){if(failure!=null)failure.addSuppressed(closeFailure);else failure=closeFailure;}finally{work.connection.compareAndSet(connection,null);}
            work.finishedNanos=System.nanoTime();work.finished.set(true);work.finishedLatch.countDown();
        }
        if(failure!=null){if(failure instanceof Exception exception)throw exception;if(failure instanceof Error error)throw error;throw new IllegalStateException(failure);}
        return work.receipt(false);
    }
    private static RuntimeException propagate(Throwable failure){if(failure instanceof Error error)throw error;return failure instanceof RuntimeException runtime?runtime:new IllegalStateException(failure);}
    private static long remaining(long deadline){return Math.max(0,deadline-System.nanoTime());}
    private static void setStatementDeadline(PreparedStatement statement,long deadline)throws SQLException{
        long millis=Math.max(1,(remaining(deadline)+999_999L)/1_000_000L);statement.setQueryTimeout((int)Math.max(1,Math.min(Integer.MAX_VALUE,(millis+999L)/1000L)));
        try{
            Class<?> type=Class.forName("com.mysql.cj.jdbc.JdbcStatement");Object mysql=type.isInstance(statement)?statement:statement.unwrap(type);Object query=type.getMethod("getQuery").invoke(mysql);query.getClass().getMethod("setTimeoutInMillis",long.class).invoke(query,millis);
        }catch(ClassNotFoundException ignored){/* Rounded JDBC seconds remain as a portable fallback. */}
        catch(ReflectiveOperationException failure){throw new SQLException("Cannot apply Connector/J millisecond test deadline",failure);}
    }
    private static void cancelStatement(OwnedMutation work,long deadline,List<Throwable> errors){
        var statement=work.statement.get();if(statement!=null)boundedAction("cancel B statement",deadline,TimeUnit.MILLISECONDS.toNanos(250),()->{try{statement.cancel();}catch(SQLException failure){throw new CompletionException(failure);}},errors);
    }
    private static void closeConnection(OwnedMutation work,long deadline,List<Throwable> errors){
        var connection=work.connection.get();if(connection!=null)boundedAction("close B connection",deadline,TimeUnit.MILLISECONDS.toNanos(250),()->{try{connection.close();}catch(SQLException failure){throw new CompletionException(failure);}},errors);
    }
    private void awaitWriterTerminal(OwnedMutation work,long deadline,List<Throwable> errors){
        try{if(!work.finished.get()){long room=remaining(deadline);if(room<=0||!work.finishedLatch.await(room,TimeUnit.NANOSECONDS))errors.add(new IllegalStateException("B worker did not finish before its original hard deadline"));}}
        catch(InterruptedException failure){Thread.currentThread().interrupt();errors.add(failure);}
        try{if(!writerExecutor.isTerminated()){long room=remaining(deadline);if(room<=0||!writerExecutor.awaitTermination(room,TimeUnit.NANOSECONDS))errors.add(new IllegalStateException("B executor did not terminate before its original hard deadline"));}}
        catch(InterruptedException failure){Thread.currentThread().interrupt();errors.add(failure);}
        if(!work.statementClosed.get())errors.add(new IllegalStateException("B statement close was not confirmed by the JDBC delegate"));
        if(!work.connectionClosed.get())errors.add(new IllegalStateException("B connection close was not confirmed by the JDBC delegate"));
        if(work.statement.get()!=null||work.connection.get()!=null)errors.add(new IllegalStateException("B JDBC registry still owns a live delegate"));
    }
    private static boolean boundedAction(String name,long deadline,long cap,Runnable action,List<Throwable> errors){
        long room=Math.min(remaining(deadline),cap);var task=new FutureTask<Void>(()->{action.run();return null;});var thread=new Thread(task,"pvc-cleanup-"+name.replace(' ','-'));thread.setDaemon(true);thread.start();
        if(room<=0){task.cancel(true);thread.interrupt();errors.add(new TimeoutException(name+" had no remaining shared budget"));if(thread.isAlive())errors.add(new IllegalStateException(name+" cleanup thread could not be observed terminated"));return false;}
        try{task.get(room,TimeUnit.NANOSECONDS);return true;}
        catch(ExecutionException failure){errors.add(failure.getCause());return false;}
        catch(TimeoutException failure){task.cancel(true);errors.add(new TimeoutException(name+" exceeded the shared cleanup deadline slice"));return false;}
        catch(InterruptedException failure){Thread.currentThread().interrupt();task.cancel(true);errors.add(failure);return false;}
        finally{if(thread.isAlive()){thread.interrupt();if(!waitForThreadWithinDeadline(thread,deadline,errors,name))errors.add(new IllegalStateException(name+" cleanup thread is still alive"));}}
    }
    private static boolean waitForThreadWithinDeadline(Thread thread,long deadline,List<Throwable> errors,String name){
        if(!thread.isAlive())return true;long wait=Math.min(remaining(deadline),TimeUnit.MILLISECONDS.toNanos(100));if(wait<=0)return false;
        long millis=TimeUnit.NANOSECONDS.toMillis(wait);int nanos=(int)(wait-TimeUnit.MILLISECONDS.toNanos(millis));
        try{thread.join(millis,nanos);}catch(InterruptedException failure){Thread.currentThread().interrupt();errors.add(new IllegalStateException(name+" bounded join was interrupted",failure));}
        return !thread.isAlive();
    }

    private Map<String,Object> externalSource(String id,Filter filter){
        var rows=fixture.db.queryForList("SELECT id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,amount,revenue,impressions,clicks,conversions,source,SHA2(CAST(raw_json AS CHAR),256) raw_hash FROM finance_records WHERE organization_id=? AND id=?",scope.organizationId(),id);
        return Map.of("rows",rows,"oldCount",feeCount(filter,"old-number"),"newCount",feeCount(filter,"new-number"));
    }
    private Map<String,Object> currencySource(String id,Filter filter){
        var row=fixture.db.queryForMap("""
            SELECT id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,
              record_date,currency,HEX(currency) currency_hex,(currency <=> _utf8mb4'USD') sql_equal_usd,
              (currency <=> _utf8mb4'usd') sql_equal_lower,amount,revenue,impressions,clicks,conversions,source,
              SHA2(CAST(raw_json AS CHAR),256) raw_hash,updated_at
            FROM finance_records WHERE organization_id=? AND id=?
            """,scope.organizationId(),id);
        var query=fixture.repo.query(scope,filter);var args=new ArrayList<>(query.args());args.add("FEE");
        long count=number(fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE "+query.where()+" AND kind=?",Long.class,args.toArray()));
        var result=new LinkedHashMap<String,Object>();result.put("row",row);result.put("nativeCount",count);return result;
    }
    private long feeCount(Filter filter,String number){
        var query=FinanceRecordSearch.apply(fixture.repo.query(scope,filter),"finance_records",number,true);var args=new ArrayList<>(query.args());args.add("FEE");
        return number(fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE "+query.where()+" AND kind=?",Long.class,args.toArray()));
    }
    private Map<String,Object> winnerSource(String order,Filter usd,Filter php){
        var rows=fixture.db.queryForList("SELECT id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,amount,revenue,impressions,clicks,conversions,source,SHA2(CAST(raw_json AS CHAR),256) raw_hash FROM finance_records WHERE organization_id=? AND order_id=? AND kind='SETTLEMENT' ORDER BY id",scope.organizationId(),order);
        var neutral=month("");var query=fixture.repo.query(scope,neutral,"r");var args=new ArrayList<Object>();args.add(order);args.addAll(query.args());
        var winner=fixture.db.queryForList("SELECT r.id,r.currency FROM finance_records r WHERE r.order_id=? AND "+FinanceCostReport.firstSettlementIdentity("r",true)+" AND "+query.where()+" ORDER BY r.id",args.toArray());
        return Map.of("rows",rows,"winner",winner,"usdCount",settledOrderCount(usd),"phpCount",settledOrderCount(php));
    }
    private long settledOrderCount(Filter filter){var query=fixture.repo.query(scope,filter,"r");return number(fixture.db.queryForObject("SELECT COUNT(*) "+fixture.repo.settledOrderRelation(scope,filter,"ORDER",false),Long.class,query.args().toArray()));}
    private Map<String,Object> reconciliationSource(String billId,Filter filter){
        var rows=fixture.db.queryForList("SELECT id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,amount,revenue,impressions,clicks,conversions,source,SHA2(CAST(raw_json AS CHAR),256) raw_hash FROM finance_records WHERE organization_id=? AND id=?",scope.organizationId(),billId);
        var query=fixture.repo.query(scope,filter);var args=new ArrayList<>(query.args());args.add("BILL");
        long count=number(fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE "+query.where()+" AND kind=?",Long.class,args.toArray()));
        return Map.of("rows",rows,"billCount",count);
    }

    private Business oracleInside(Filter requested,FinanceWorkspaceQueryService.Selection selected){
        var neutral=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),"",requested.granularity(),requested.groupId());
        var facts=fixture.service.buildNativeDashboardFacts(scope,neutral,false);var result=new LinkedHashMap<>(fixture.service.dashboardFromFacts(scope,requested,false,"",false,facts));
        Map<String,Object> raw=selected.view().equals("reconciliation")?fixture.repo.reconciliationPage(scope,requested,1,50,selected.numbers()):fixture.repo.details(scope,requested,selected.kind(),1,50,selected.numbers(),false);
        var records=new LinkedHashMap<>(raw);records.put("dateBasis",FinanceCalendar.contract());records.put("fieldSettings",new FinanceFields(fixture.repo).settings(scope,selected.view().equals("reconciliation")?"RECONCILIATION":selected.kind()));result.put("records",records);
        if(selected.view().equals("reconciliation"))result.put("currencies",fixture.repo.billCurrencies(scope,neutral));
        return business(result);
    }
    private Outcome capture(Supplier<Map<String,Object>> action){
        try{
            var result=action.get();String generated=text(map(result.get("cache")).get("generatedAt")),status=text(map(result.get("readModel")).get("status"));
            return new Outcome(business(result),null,generated,status);
        }catch(RuntimeException|AssertionError failure){return new Outcome(null,failure,"","");}
    }
    private Business business(Map<String,Object> result){
        var copy=new LinkedHashMap<>(result);for(String key:List.of("updatedAt","cache","readModel","settlementCalculation"))copy.remove(key);
        Object tree=canonicalRoundTrip(copy);var records=map(result.get("records"));return new Business(tree,hash(CANONICAL_JSON.writeValueAsString(tree)),number(records.get("total")),itemSummary(rows(records.get("items"))));
    }
    private static Object canonicalRoundTrip(Object value){Object decoded=CANONICAL_JSON.readerFor(Map.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(CANONICAL_JSON.writeValueAsBytes(value));return canonical(decoded);}
    private static Object canonical(Object value){
        if(value==null||value instanceof String||value instanceof Boolean)return value;
        if(value instanceof Map<?,?> source){var result=new TreeMap<String,Object>();source.forEach((key,item)->result.put(String.valueOf(key),canonical(item)));return result;}
        if(value instanceof Collection<?> source){var result=new ArrayList<>();source.forEach(item->result.add(canonical(item)));return result;}
        if(value instanceof Number number){return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();}
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toInstant().toString();
        if(value instanceof java.sql.Date date)return date.toLocalDate().toString();
        if(value instanceof TemporalAccessor)return value.toString();
        return String.valueOf(value);
    }
    private static List<Map<String,Object>> itemSummary(List<Map<String,Object>> rows){
        var result=new ArrayList<Map<String,Object>>();for(var row:rows){var item=new LinkedHashMap<String,Object>();for(String key:List.of("id","external_id","currency","amount","detail_amount","detail_count","difference","result"))if(row.containsKey(key))item.put(key,canonical(row.get(key)));result.add(item);}return result;
    }

    private void assertExternal(Map<String,Object> source,String external,long oldCount,long newCount){var rows=rows(source.get("rows"));assertEquals(1,rows.size());assertEquals(external,rows.getFirst().get("external_id"));assertEquals(oldCount,number(source.get("oldCount")));assertEquals(newCount,number(source.get("newCount")));assertEquals("",rows.getFirst().get("order_id"));assertEquals("",rows.getFirst().get("statement_id"));assertEquals(0,decimal("12.123456").compareTo(decimal(rows.getFirst().get("amount"))));}
    private static Map<String,Object> currencyRow(Map<String,Object> source){return map(source.get("row"));}
    private void assertCurrencySource(Map<String,Object> source,String currency,String hex,Map<String,Object> baseline){
        var row=currencyRow(source);assertEquals(currency,row.get("currency"));assertEquals(hex,row.get("currency_hex"));assertEquals(1,number(row.get("sql_equal_usd")));assertEquals(1,number(row.get("sql_equal_lower")));assertEquals(1,number(source.get("nativeCount")));
        for(String key:List.of("id","user_id","shop_id","platform","kind","external_id","order_id","statement_id","category","status","record_date","source","raw_hash","updated_at"))assertEquals(canonical(baseline.get(key)),canonical(row.get(key)),key);
        assertEquals(0,decimal("12.123456").compareTo(decimal(row.get("amount"))));assertNull(row.get("revenue"));assertNull(row.get("impressions"));assertNull(row.get("clicks"));assertNull(row.get("conversions"));assertEquals("",row.get("order_id"));assertEquals("",row.get("statement_id"));
    }
    private void assertCurrencyBusiness(Business business,String currency){
        assertEquals(1,business.total());assertEquals(1,business.items().size());assertEquals(currency,business.items().getFirst().get("currency"));assertEquals("12.123456",business.items().getFirst().get("amount"));
        var records=map(map(business.tree()).get("records"));assertEquals(1,number(records.get("page")));assertEquals(50,number(records.get("pageSize")));assertEquals(1,number(records.get("total")));assertEquals(1,rows(records.get("items")).size());
    }
    private void assertWinner(Map<String,Object> source,String id,String currency,long usd,long php){var winner=rows(source.get("winner"));assertEquals(1,winner.size());assertEquals(id,winner.getFirst().get("id"));assertEquals(currency,winner.getFirst().get("currency"));assertEquals(usd,number(source.get("usdCount")));assertEquals(php,number(source.get("phpCount")));}
    private void assertReconciliation(Map<String,Object> source,String user,long count){var rows=rows(source.get("rows"));assertEquals(1,rows.size());assertEquals(user,rows.getFirst().get("user_id"));assertEquals(count,number(source.get("billCount")));assertEquals("bill-1",rows.getFirst().get("external_id"));assertEquals(0,decimal("10").compareTo(decimal(rows.getFirst().get("amount"))));}
    private void assertMatched(Business business){assertEquals(1,business.total());var item=business.items().getFirst();assertEquals("7",item.get("detail_amount"));assertEquals("1",item.get("detail_count"));assertEquals("3",item.get("difference"));assertEquals("DIFFERENCE",item.get("result"));}
    private void assertMissing(Business business){assertEquals(1,business.total());var item=business.items().getFirst();assertTrue(item.containsKey("detail_amount"));assertNull(item.get("detail_amount"));assertNull(item.get("detail_count"));assertNull(item.get("difference"));assertEquals("MISSING",item.get("result"));}
    private void assertVersion(State state,String status,String revision,long events){assertEquals(status,state.version().get("status"));assertEquals(revision,state.version().get("revision"));assertEquals(events,state.events());}
    private void assertWarmRedis(Outcome first,Outcome second){requireNoFailure(first,"first warm query");requireNoFailure(second,"second warm query");assertTrue(cacheKeys().stream().anyMatch(key->key.contains(":page:")),"The complete page must exist in real Redis");assertFalse(first.cacheGeneratedAt().isBlank());assertEquals(first.cacheGeneratedAt(),second.cacheGeneratedAt());assertEquals("READY",first.readModelStatus());assertEquals("READY",second.readModelStatus());assertEquals(first.business().hash(),second.business().hash());}
    private void assertFrozen(DualState before,DualState after){assertEquals(canonical(before.primary().source()),canonical(after.primary().source()));assertEquals(before.primary().version(),after.primary().version());assertEquals(before.primary().events(),after.primary().events());assertEquals(before.primary().eventRows(),after.primary().eventRows());assertEquals(before.primary().oracle().hash(),after.primary().oracle().hash());if(before.secondary()!=null)assertEquals(before.secondary().hash(),after.secondary().hash());}
    private void requireNoFailure(Outcome outcome,String label){if(outcome.failure()!=null)throw new AssertionError(label+" failed with "+failure(outcome.failure()),outcome.failure());assertNotNull(outcome.business(),label);}
    private void requireOutcomeEquals(Business expected,Outcome actual,String label){requireNoFailure(actual,label);if(!expected.tree().equals(actual.business().tree()))fail(label+" business mismatch expectedHash="+expected.hash()+" actualHash="+actual.business().hash()+" expectedTotal="+expected.total()+" actualTotal="+actual.business().total()+" expectedItems="+expected.items()+" actualItems="+actual.business().items());}
    private void requireOutcomeDifferent(Business previous,Outcome actual,String label){requireNoFailure(actual,label);if(previous.tree().equals(actual.business().tree()))fail(label+" unexpectedly reused businessHash="+previous.hash()+" total="+previous.total()+" items="+previous.items());}

    private void postBuild(String oldRevision,Filter filter,FinanceWorkspaceQueryService.Selection selected,Business expected){
        int drained=0;for(int i=0;i<20;i++){int count=projections.drainEvents();drained+=count;if(count==0)break;}assertTrue(drained>0);
        var pending=projections.version(scope,month(""));assertEquals("PENDING",pending.get("status"));
        var partition=fixture.db.queryForMap("SELECT built_revision,requested_revision FROM finance_query_partitions WHERE organization_id=? AND platform_filter='' AND partition_month=?",scope.organizationId(),java.sql.Date.valueOf(START));
        assertTrue(number(partition.get("requested_revision"))>number(partition.get("built_revision")));
        for(int i=0;i<20&&projections.buildNext();i++){}var ready=projections.version(scope,month(""));assertEquals("READY",ready.get("status"));assertNotEquals(oldRevision,ready.get("revision"));
        var actual=capture(()->queries.query(scope,scope,filter,selected));var warm=capture(()->queries.query(scope,scope,filter,selected));requireOutcomeEquals(expected,actual,"rebuilt page");requireOutcomeEquals(expected,warm,"rebuilt warm page");
        System.out.println("FINANCE_PROJECTION_VERSION_COVERAGE_POST "+fixture.repo.write(Map.of("case",caseName,"drained",drained,"oldRevision",oldRevision,"newRevision",ready.get("revision"),"status","EXECUTED")));
    }

    private void printReceipt(String field,String revision,MutationReceipt rollback,SnapshotPage rollbackState,OldSnapshot old,DualState next,Outcome primary,Outcome secondary,DualState stable){
        var receipt=new LinkedHashMap<String,Object>();receipt.put("case",field);receipt.put("initialRevision",revision);receipt.put("rollback",rollback.receipt());receipt.put("rollbackState",stateEvidence(rollbackState.state()));receipt.put("A_before",stateEvidence(old.before()));receipt.put("A_after",stateEvidence(old.after()));receipt.put("B_commit",old.commit().receipt());receipt.put("nextOracle",stateEvidence(next.primary()));if(next.secondary()!=null)receipt.put("nextSecondaryOracle",next.secondary().receipt());receipt.put("workspacePrimary",outcomeEvidence(primary));if(secondary!=null)receipt.put("workspaceSecondary",outcomeEvidence(secondary));receipt.put("postQuerySource",stateEvidence(stable.primary()));receipt.put("pendingWaitNanos",pendingClock.get());receipt.put("redisKeys",cacheKeys().size());receipt.put("postDrainBuild","NOT_REACHED_UNLESS_RED_GATE_PASSES");
        @SuppressWarnings("unchecked") Map<String,Object> frozen=(Map<String,Object>)canonical(receipt);runEvidence=frozen;System.out.println("FINANCE_PROJECTION_VERSION_COVERAGE_RECEIPT "+fixture.repo.write(frozen));
    }
    private void printCurrencyReceipt(Map<String,Object> column,String revision,MutationReceipt rollback,SnapshotPage rollbackState,OldSnapshot old,State initial,Outcome warmFirst,Outcome warmSecond,State next,Outcome page,State stable){
        var receipt=new LinkedHashMap<String,Object>();receipt.put("case","currency");receipt.put("column",canonical(column));receipt.put("initialRevision",revision);receipt.put("initial",fullStateEvidence(initial));receipt.put("warmFirst",fullOutcomeEvidence(warmFirst));receipt.put("warmSecond",fullOutcomeEvidence(warmSecond));receipt.put("rollback",rollback.receipt());receipt.put("rollbackState",fullStateEvidence(rollbackState.state()));receipt.put("rollbackPage",fullOutcomeEvidence(rollbackState.page()));receipt.put("A_before",fullStateEvidence(old.before()));receipt.put("A_after",fullStateEvidence(old.after()));receipt.put("A_beforePage",fullOutcomeEvidence(old.beforePage()));receipt.put("A_afterPage",fullOutcomeEvidence(old.afterPage()));receipt.put("B_commit",old.commit().receipt());receipt.put("nextOracle",fullStateEvidence(next));receipt.put("workspacePrimary",fullOutcomeEvidence(page));receipt.put("postQuerySource",fullStateEvidence(stable));receipt.put("pendingWaitNanos",pendingClock.get());receipt.put("redisKeys",cacheKeys().size());receipt.put("postDrainBuild","NOT_REACHED_UNLESS_RED_GATE_PASSES");
        @SuppressWarnings("unchecked") Map<String,Object> frozen=(Map<String,Object>)canonical(receipt);runEvidence=frozen;System.out.println("FINANCE_PROJECTION_CURRENCY_BYTE_RECEIPT "+fixture.repo.write(frozen));
    }
    private Map<String,Object> fullStateEvidence(State state){var result=new LinkedHashMap<>(stateEvidence(state));result.put("oracleTree",state.oracle().tree());return result;}
    private Map<String,Object> fullOutcomeEvidence(Outcome outcome){var result=new LinkedHashMap<>(outcomeEvidence(outcome));if(outcome!=null&&outcome.failure()==null)result.put("businessTree",outcome.business().tree());return result;}
    private Map<String,Object> stateEvidence(State state){return Map.of("source",canonical(state.source()),"version",state.version(),"events",state.events(),"eventRows",canonical(state.eventRows()),"oracle",state.oracle().receipt(),"snapshot",state.snapshot());}
    private Map<String,Object> outcomeEvidence(Outcome outcome){
        if(outcome==null)return Map.of();if(outcome.failure()!=null)return Map.of("failure",failure(outcome.failure()));
        var result=new LinkedHashMap<String,Object>(outcome.business().receipt());result.put("cacheGeneratedAt",outcome.cacheGeneratedAt());result.put("readModelStatus",outcome.readModelStatus());return result;
    }
    private CleanupResult cleanupOwnedResources(){
        long started=System.nanoTime(),deadline=started+CLEANUP_LIMIT_NANOS;var errors=new ArrayList<Throwable>();var deleted=new AtomicInteger(-1);boolean writerTerminated=true,cacheTerminated=true,lettuceStopped=true;
        var work=activeMutation;if(work!=null&&!work.finished.get()){
            work.cancelRequested.set(true);if(writerFuture!=null)writerFuture.cancel(true);long ownedDeadline=Math.min(deadline,work.hardDeadlineNanos);cancelStatement(work,ownedDeadline,errors);closeConnection(work,ownedDeadline,errors);
        }
        if(writerExecutor!=null){writerExecutor.shutdownNow();long room=Math.min(remaining(deadline),CLEANUP_STEP_NANOS);try{writerTerminated=writerExecutor.isTerminated()||room>0&&writerExecutor.awaitTermination(room,TimeUnit.NANOSECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();errors.add(failure);writerTerminated=false;}if(!writerTerminated)errors.add(new IllegalStateException("writer executor did not terminate inside the shared cleanup deadline"));}
        if(work!=null){if(!work.finished.get())errors.add(new IllegalStateException("owned B work is not finished"));if(!work.statementClosed.get())errors.add(new IllegalStateException("owned B statement close is unknown"));if(!work.connectionClosed.get())errors.add(new IllegalStateException("owned B connection close is unknown"));if(work.statement.get()!=null||work.connection.get()!=null)errors.add(new IllegalStateException("owned B JDBC registry is not empty"));}
        if(redis!=null&&cachePattern!=null)boundedAction("redis namespace",deadline,CLEANUP_STEP_NANOS,()->{var keys=redis.keys(cachePattern);int count=keys==null?0:keys.size();if(count>0)redis.delete(keys);deleted.set(count);},errors);
        if(cache!=null)boundedAction("cache stop",deadline,CLEANUP_STEP_NANOS,cache::stop,errors);
        if(cacheExecutor!=null){cacheExecutor.shutdownNow();long room=Math.min(remaining(deadline),CLEANUP_STEP_NANOS);try{cacheTerminated=cacheExecutor.isTerminated()||room>0&&cacheExecutor.awaitTermination(room,TimeUnit.NANOSECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();errors.add(failure);cacheTerminated=false;}if(!cacheTerminated)errors.add(new IllegalStateException("cache executor did not terminate inside the shared cleanup deadline"));}
        if(redisFactory!=null)lettuceStopped=boundedAction("lettuce stop",deadline,CLEANUP_STEP_NANOS,()->{redisFactory.stop();redisFactory.destroy();},errors);if(!lettuceStopped)errors.add(new IllegalStateException("Lettuce stop/destroy did not complete"));
        int active=fixture.dataSource==null?-1:fixture.dataSource.getHikariPoolMXBean().getActiveConnections();if(active!=0)errors.add(new IllegalStateException("Hikari still has "+active+" active connections"));
        long ended=System.nanoTime();boolean within=ended<=deadline;if(!within)errors.add(new TimeoutException("Cleanup exceeded its single five-second deadline"));
        return new CleanupResult(started,ended,deadline,CLEANUP_LIMIT_NANOS,within,errors.isEmpty(),writerTerminated,cacheTerminated,lettuceStopped,deleted.get(),active,work==null||work.finished.get(),work==null||work.statementClosed.get(),work==null||work.connectionClosed.get(),errors.stream().map(FinanceProjectionVersionCoverageIntegrationTest::failure).toList(),List.copyOf(errors));
    }
    private void writeRunReceipt(String status,Throwable cause){
        try{
            Files.createDirectories(RECEIPT_DIRECTORY);String name=caseName==null||caseName.isBlank()?declaredTest:caseName;Path path=RECEIPT_DIRECTORY.resolve(name+".json");
            var receipt=new LinkedHashMap<String,Object>();receipt.put("schema","FINANCE_PROJECTION_VERSION_COVERAGE_FIX1_V1");receipt.put("run",RECEIPT_RUN);receipt.put("case",name);receipt.put("declaredTest",declaredTest);receipt.put("status",status);receipt.put("exception",cause==null?"":failure(cause));receipt.put("sourceSha256",hash(Files.readString(TEST_SOURCE)));receipt.put("evidence",runEvidence);receipt.put("cleanup",cleanupResult==null?Map.of("status","NOT_REACHED"):cleanupResult.receipt());
            String value=CANONICAL_JSON.writeValueAsString(canonical(receipt))+"\n";Files.writeString(path,value,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);System.out.println("FINANCE_PROJECTION_VERSION_COVERAGE_CREATE_NEW "+fixture.repo.write(Map.of("path",path.toString(),"sha256",hash(value),"bytes",value.getBytes(StandardCharsets.UTF_8).length)));
        }catch(Throwable failure){throw new AssertionError("Cannot CREATE_NEW the per-test fix1 receipt",failure);}
    }
    private Set<String> cacheKeys(){var keys=redis.keys(cachePattern);return keys==null?Set.of():Set.copyOf(keys);}
    private long eventCount(){return number(fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE organization_id=?",Long.class,scope.organizationId()));}
    private List<Map<String,Object>> eventRows(){return fixture.db.queryForList("SELECT event_scope,record_date,shop_id,order_id,record_id,statement_id,source_table FROM finance_query_events WHERE organization_id=? ORDER BY event_id",scope.organizationId());}
    private static String failure(Throwable failure){String message=Objects.toString(failure.getMessage(),"").replaceAll("\\s+"," ");return failure.getClass().getSimpleName()+(message.isBlank()?"":":"+message.substring(0,Math.min(300,message.length())));}
    private static org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisFactory(){
        var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getenv("HEYI_DESIGN_HTTP_REDIS_PORT")));String password=System.getenv("HEYI_DESIGN_HTTP_REDIS_PASSWORD");if(password!=null&&!password.isBlank())config.setPassword(org.springframework.data.redis.connection.RedisPassword.of(password));
        var client=org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).shutdownQuietPeriod(Duration.ZERO).shutdownTimeout(Duration.ofMillis(250)).build();
        return new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config,client);
    }

    private record Business(Object tree,String hash,long total,List<Map<String,Object>> items){Map<String,Object> receipt(){return Map.of("hash",hash,"total",total,"items",items);}}
    private record Outcome(Business business,Throwable failure,String cacheGeneratedAt,String readModelStatus){}
    private record State(Map<String,Object> source,Map<String,Object> version,long events,List<Map<String,Object>> eventRows,Business oracle,Map<String,Object> snapshot){}
    private record DualState(State primary,Business secondary){}
    private record SnapshotPage(State state,Outcome page){}
    private record OldSnapshot(State before,State after,Outcome beforePage,Outcome afterPage,MutationReceipt commit){}
    private record MutationReceipt(String mode,long connectionId,long threadId,String threadName,long dispatchedNanos,long operationDeadlineNanos,long hardDeadlineNanos,long finishedNanos,int updated,boolean commitRequested,boolean committed,boolean rolledBack,boolean cancelRequested,boolean updateStarted,boolean connectionClosed,boolean statementClosed,boolean executorTerminated,String failure){
        Map<String,Object> receipt(){var result=new LinkedHashMap<String,Object>();result.put("mode",mode);result.put("connectionId",connectionId);result.put("threadId",threadId);result.put("threadName",threadName);result.put("dispatchedNanos",dispatchedNanos);result.put("operationDeadlineNanos",operationDeadlineNanos);result.put("hardDeadlineNanos",hardDeadlineNanos);result.put("finishedNanos",finishedNanos);result.put("updated",updated);result.put("commitRequested",commitRequested);result.put("committed",committed);result.put("rolledBack",rolledBack);result.put("cancelRequested",cancelRequested);result.put("updateStarted",updateStarted);result.put("connectionClosed",connectionClosed);result.put("statementClosed",statementClosed);result.put("executorTerminated",executorTerminated);result.put("failure",failure);return result;}
    }
    private static final class OwnedMutation {
        final String mode;final boolean commitRequested;final long waitMillis,dispatchedNanos,hardDeadlineNanos;volatile long operationDeadlineNanos,connectionId=-1,threadId=-1,finishedNanos;volatile String threadName="";volatile Throwable failure;
        final AtomicReference<Connection> connection=new AtomicReference<>();final AtomicReference<PreparedStatement> statement=new AtomicReference<>();
        final AtomicInteger updated=new AtomicInteger();final AtomicBoolean committed=new AtomicBoolean(),rolledBack=new AtomicBoolean(),cancelRequested=new AtomicBoolean(),updateStarted=new AtomicBoolean(),connectionClosed=new AtomicBoolean(),statementClosed=new AtomicBoolean(),finished=new AtomicBoolean();
        final CountDownLatch updateEntered=new CountDownLatch(1),finishedLatch=new CountDownLatch(1);
        OwnedMutation(String mode,boolean commitRequested,long waitMillis,long dispatchedNanos,long hardDeadlineNanos){this.mode=mode;this.commitRequested=commitRequested;this.waitMillis=waitMillis;this.dispatchedNanos=dispatchedNanos;this.hardDeadlineNanos=hardDeadlineNanos;}
        MutationReceipt receipt(boolean executorTerminated){return new MutationReceipt(mode,connectionId,threadId,threadName,dispatchedNanos,operationDeadlineNanos,hardDeadlineNanos,finishedNanos,updated.get(),commitRequested,committed.get(),rolledBack.get(),cancelRequested.get(),updateStarted.get(),connectionClosed.get(),statementClosed.get(),executorTerminated,failure==null?"":FinanceProjectionVersionCoverageIntegrationTest.failure(failure));}
    }
    private record CleanupResult(long startedNanos,long endedNanos,long deadlineNanos,long limitNanos,boolean withinDeadline,boolean success,boolean writerExecutorTerminated,boolean cacheExecutorTerminated,boolean lettuceStopped,int deletedCacheKeys,int activeHikariConnections,boolean mutationFinished,boolean statementClosed,boolean connectionClosed,List<String> errors,List<Throwable> causes){
        Map<String,Object> receipt(){var result=new LinkedHashMap<String,Object>();result.put("startedNanos",startedNanos);result.put("endedNanos",endedNanos);result.put("deadlineNanos",deadlineNanos);result.put("limitNanos",limitNanos);result.put("elapsedNanos",endedNanos-startedNanos);result.put("withinDeadline",withinDeadline);result.put("success",success);result.put("writerExecutorTerminated",writerExecutorTerminated);result.put("cacheExecutorTerminated",cacheExecutorTerminated);result.put("lettuceStopped",lettuceStopped);result.put("deletedCacheKeys",deletedCacheKeys);result.put("activeHikariConnections",activeHikariConnections);result.put("mutationFinished",mutationFinished);result.put("statementClosed",statementClosed);result.put("connectionClosed",connectionClosed);result.put("errors",errors);return result;}
        RuntimeException failure(){var result=new IllegalStateException("Owned test resource cleanup failed: "+errors);causes.forEach(result::addSuppressed);return result;}
    }
    private static final class MutationTimeoutException extends RuntimeException {
        private final MutationReceipt receipt;
        MutationTimeoutException(MutationReceipt receipt,Throwable cause){super("Owned B mutation exceeded its short operation deadline",cause);this.receipt=receipt;}
        MutationReceipt receipt(){return receipt;}
    }

    private static final class SnapshotWitness {
        final DataSource source;final ConnectionHolder holder;final Connection connection;final Object physical;final long deadline,connectionId,threadId,borrowedNanos;final String threadName;int checks;
        SnapshotWitness(DataSource source){
            this.source=source;borrowedNanos=System.nanoTime();threadId=Thread.currentThread().threadId();threadName=Thread.currentThread().getName();holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);assertNotNull(holder);connection=holder.getConnection();physical=physical(connection);assertTrue(holder.hasTimeout());deadline=holder.getDeadline().getTime();
            try(var statement=connection.createStatement();var row=statement.executeQuery("SELECT CONNECTION_ID()")){assertTrue(row.next());connectionId=row.getLong(1);}catch(SQLException failure){throw new AssertionError(failure);}verify();
        }
        void verify(){checks++;assertSame(holder,TransactionSynchronizationManager.getResource(source));assertSame(connection,holder.getConnection());assertSame(physical,physical(connection));assertTrue(holder.hasTimeout());assertEquals(deadline,holder.getDeadline().getTime());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());try{assertTrue(connection.isReadOnly());assertFalse(connection.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());}catch(SQLException failure){throw new AssertionError(failure);}}
        Map<String,Object> receipt(){verify();return Map.of("connectionId",connectionId,"threadId",threadId,"threadName",threadName,"holderIdentity",System.identityHashCode(holder),"physicalIdentity",System.identityHashCode(physical),"deadlineMillis",deadline,"borrowedNanos",borrowedNanos,"checks",checks,"readOnlyRepeatableRead",true);}
        private static Object physical(Connection connection){try{return connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class);}catch(SQLException failure){throw new AssertionError(failure);}}
    }
}
