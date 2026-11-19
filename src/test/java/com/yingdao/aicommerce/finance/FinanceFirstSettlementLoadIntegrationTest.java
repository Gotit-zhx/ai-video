package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.ops.MaintenanceGate;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded, selected isolated experiment. XML success is not parameter admission. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceFirstSettlementLoadIntegrationTest extends FinanceFirstSettlementTestSupport {
    static final long SEED=20261114L;
    static final String PROFILE_ENV="FINANCE_FIRST_SETTLEMENT_LOAD_PROFILE";
    static final LocalDate START=LocalDate.of(2026,10,1),END=LocalDate.of(2026,10,31);
    static final Scope COMPANY=new Scope("fsf-load-company","fsf-load-user");
    static final Filter WINDOW=new Filter(START,END,"","","","day","");
    static final int MIN_SAMPLES=30,MAX_REQUESTS=200;
    static final long PHASE_NANOS=TimeUnit.SECONDS.toNanos(120);
    final ThreadLocal<Sample> current=new ThreadLocal<>();
    final List<ExecutorService> executors=new ArrayList<>();
    final Map<String,Object> receipt=new LinkedHashMap<>();
    final Map<String,StatementTrace> planInputs=new ConcurrentHashMap<>();
    final List<Map<String,Object>> gaps=new ArrayList<>(),plans=new ArrayList<>();
    final List<Sample> measured=new ArrayList<>();
    final List<Sample> startedSamples=new CopyOnWriteArrayList<>();
    final AtomicInteger sampleSequence=new AtomicInteger();
    final List<Map<String,Object>> mutations=new ArrayList<>();
    TracingSource observed;FinanceRepository instrumented;FinanceFirstSettlementWorker worker;
    Path gateDirectory;long hardDeadline;String runId;boolean historyAvailable;
    Phase phase;

    record Profile(String name,int owners,int candidates,String load,int readers,int batch,int cap,
                   long candidateMillis,long nativeReserveMillis,long completionReserveMillis,int unresolved) {
        boolean continuous(){return load.equals("continuous");}
        Map<String,Object> inputs(){
            var out=new LinkedHashMap<String,Object>();out.put("name",name);out.put("randomSeed",SEED);out.put("owners",owners);
            out.put("eligibleCandidatesPerOwner",candidates);out.put("load",load);out.put("readerConcurrency",readers);
            out.put("workerLanes",1);out.put("workerBatch",batch);out.put("dirtyCap",cap);out.put("candidateMillis",candidateMillis);
            out.put("nativeReserveMillis",nativeReserveMillis);out.put("completionReserveMillis",completionReserveMillis);
            out.put("staticUnresolvedOwners",unresolved);out.put("workspaceRrSeconds",30);out.put("maximumPhaseSeconds",120);
            out.put("maximumRequests",MAX_REQUESTS);out.put("minimumCompletedPerMode",MIN_SAMPLES);
            out.put("sourceFiles",1);out.put("invoiceSourceRows",owners);out.put("sourceFirstPageRows",Math.min(50,owners));
            return out;
        }
    }
    static Profile baseline(){return new Profile("baseline",1000,4,"clean",1,64,64,500,10000,2000,0);}
    static Map<String,Profile> profiles(){
        var out=new LinkedHashMap<String,Profile>();var b=baseline();out.put(b.name(),b);
        for(int n:List.of(10000,50000))out.put("size-"+n,new Profile("size-"+n,n,4,"clean",1,64,64,500,10000,2000,0));
        for(int n:List.of(1,16))out.put("candidates-"+n,new Profile("candidates-"+n,1000,n,"clean",1,64,64,500,10000,2000,0));
        for(int n:List.of(10,100))out.put("dirty-"+(n==10?"1pct":"10pct"),new Profile("dirty-"+(n==10?"1pct":"10pct"),1000,4,"static-dirty",1,64,64,500,10000,2000,n));
        for(int n:List.of(2,4))out.put("readers-"+n,new Profile("readers-"+n,1000,4,"clean",n,64,64,500,10000,2000,0));
        for(int n:List.of(0,16,64,256))out.put("cap-"+n,new Profile("cap-"+n,1000,4,"static-dirty",1,64,n,500,10000,2000,16));
        out.put("clean-cap-0",new Profile("clean-cap-0",1000,4,"clean",1,64,0,500,10000,2000,0));
        for(int n:List.of(100,250,500,1000))out.put("budget-"+n,new Profile("budget-"+n,1000,4,"static-dirty",1,64,64,n,10000,2000,16));
        for(int n:List.of(5000,10000,20000))out.put("native-reserve-"+n,new Profile("native-reserve-"+n,1000,4,"clean",1,64,64,500,n,2000,0));
        for(int n:List.of(1000,2000,5000))out.put("completion-reserve-"+n,new Profile("completion-reserve-"+n,1000,4,"clean",1,64,64,500,10000,n,0));
        for(int n:List.of(16,64,256))out.put("continuous-batch-"+n,new Profile("continuous-batch-"+n,1000,4,"continuous",1,n,64,500,10000,2000,0));
        return out;
    }

    @Test void selectedBaselinePilotMeasuresRealWorkspaceAndRejectsNativeOnlyAsAcceleration()throws Exception {
        resetHarness();
        long started=System.nanoTime();hardDeadline=started+TimeUnit.SECONDS.toNanos(540);
        runId=UUID.randomUUID().toString().substring(0,8);
        String selected=Optional.ofNullable(System.getenv(PROFILE_ENV)).orElse("baseline");
        Profile profile=profiles().get(selected);assertNotNull(profile,"Unknown selected profile: "+selected);
        receipt.put("schema","first-settlement-load-v1");receipt.put("startedAt",Instant.now().toString());receipt.put("runId",runId);
        receipt.put("selectedProfile",profile.inputs());receipt.put("status","RUNNING");receipt.put("admitted",false);
        receipt.put("productionMode","OFF; no production settings selected");receipt.put("profileManifest",manifest(selected));
        receipt.put("measurementBoundary",Map.of("workspace","actual workspace call inside the original 30s RO/RR; validation excluded",
            "validation","preserved native and independent original identity oracle in that same bound snapshot",
            "rrLifecycle","connection acquisition, workspace, validation, metrics and commit; validation included",
            "comparison","alternating native/ACTIVE order on the same fixed workload; no worker during static-dirty sampling"));
        Throwable primary=null;
        try {
            if(profile.continuous())throw new IllegalStateException("Continuous three-window writer/worker stage is pending root's pilot review; profile was not executed");
            observed=new TracingSource(dataSource);instrumented=new FinanceRepository(new JdbcTemplate(observed),new ObjectMapper());
            receipt.put("environment",environment());probeMetrics();
            Fixture smoke=seedFixture(16,4);initializeAndPublish(smoke,64,false);
            Sample active=sample(smoke,baseline(),Mode.ACTIVE,"smoke",null),off=sample(smoke,baseline(),Mode.OFF,"negative-control",null);
            requireSuccess(active);requireSuccess(off);
            assertTrue(accelerationGuard(List.of(active)),"Smoke must exercise actual non-fallback FACTS before measurements");
            assertFalse(accelerationGuard(List.of(off)),"A real OFF/native-only sample must be rejected as acceleration evidence");
            receipt.put("smoke",active.json());receipt.put("negativeControl",Map.of("acceptedAsAcceleration",false,"reason","NO_FACTS_SAMPLE","sample",off.json()));
            setup();resetFirstSettlementPrototype();planInputs.clear();mutations.clear();
            Fixture fixture=seedFixture(profile.owners(),profile.candidates());initializeAndPublish(fixture,profile.batch(),true);
            if(profile.unresolved()>0)dirty(fixture,profile.unresolved());
            var before=globalStatus();
            int rounds=(MIN_SAMPLES+profile.readers()-1)/profile.readers();
            long phaseStart=runPhase(fixture,profile,rounds,PHASE_NANOS,future->{});
            long elapsed=System.nanoTime()-phaseStart;
            var after=globalStatus();
            receipt.put("serverStatusBefore",before);receipt.put("serverStatusAfter",after);receipt.put("serverStatusDelta",delta(before,after));
            collectPhaseEvidence(phaseStart);
            var nativeSamples=measured.stream().filter(s->s.mode==Mode.OFF).toList();var factSamples=measured.stream().filter(s->s.mode==Mode.ACTIVE).toList();
            receipt.put("rates",Map.of("windowSeconds",elapsed/1_000_000_000d,"sourceMutations",0,"coalescedInvalidations",0,
                "newPendingOwnerCycles",0,"successfulOwnerPublications",0,"lambdaOwnerCyclesPerSecond",0,"muPublicationsPerSecond",0,
                "scope","static measurement window only; setup publications and initial dirty mutations excluded"));
            receipt.put("dirtyAge",age());receipt.put("threeWindowWorkerAdmission","INSUFFICIENT: static pilot has no continuous writer/worker windows");
            captureReaderPlans();captureDatePlans(fixture);
            receipt.put("admission",admission(nativeSamples,factSamples));
            receipt.put("fixtureEnd",Map.of("eligibleSourceRows",count("finance_records"),"activeTotals",count("finance_label_order_totals"),
                "sourceFiles",count("finance_label_imports"),"invoiceSourceRows",count("finance_label_invoice_rows"),"pendingOwners",pending()));
            for(var s:measured)if(s.hardFailure!=null)throw new AssertionError(s.hardFailure);
            assertTrue(nativeSamples.size()>=MIN_SAMPLES&&factSamples.size()>=MIN_SAMPLES,"Selected phase has insufficient completed attempts");
            if(profile.unresolved()<=profile.cap())assertTrue(accelerationGuard(factSamples),"No completed FACTS sample; cannot accept this as an acceleration pilot");
            receipt.put("status","PILOT_COMPLETED_NOT_ADMITTED");
        }catch(Exception|AssertionError failure) {
            primary=failure;receipt.put("status","FAILED_OR_INCOMPLETE");receipt.put("failure",failure.getClass().getSimpleName());throw failure;
        }finally {finishRun(selected,started,primary);}
    }

    long phaseStartedNanos;int phaseSubmitted;
    static final class Attempt {
        final Sample sample;final long dispatchedNanos;FutureTask<Sample> future;
        Attempt(Sample sample){this.sample=sample;dispatchedNanos=System.nanoTime();}
    }
    static final class Phase {
        final long startedNanos,deadlineNanos,budgetNanos;final List<Attempt> attempts=new ArrayList<>();
        long stoppedNanos;String stopReason="RUNNING";
        Phase(long budget,long hardDeadline){startedNanos=System.nanoTime();budgetNanos=budget;deadlineNanos=Math.min(startedNanos+budget,hardDeadline);}
    }
    static final class PhaseDeadlineReached extends RuntimeException{}
    long runPhase(Fixture fixture,Profile profile,int rounds,long limitNanos,Consumer<Future<Sample>> beforeAwait)throws Exception {
        phase=new Phase(limitNanos,hardDeadline);long phaseStart=phase.startedNanos;phaseStartedNanos=phaseStart;
        receipt.put("phaseBudgetMillis",ms(limitNanos));
        ExecutorService readers=Executors.newFixedThreadPool(profile.readers());executors.add(readers);
        try {
            for(int round=0;round<rounds;round++) {
                requirePhaseTime();
                assertEquals(profile.unresolved(),pending(),"Static unresolved population must not silently become clean");
                for(var mode:round%2==0?List.of(Mode.OFF,Mode.ACTIVE):List.of(Mode.ACTIVE,Mode.OFF)) {
                    requirePhaseTime();
                    if(phase.attempts.size()+profile.readers()>MAX_REQUESTS)break;
                    var barrier=profile.readers()==1?null:new CyclicBarrier(profile.readers());
                    var group=new ArrayList<Attempt>();
                    for(int r=0;r<profile.readers();r++) {
                        requirePhaseTime();
                        var result=newSample(mode,"measurement",profile.owners());result.barrier=barrier;
                        result.phaseStartedNanos=phase.startedNanos;result.phaseDeadlineNanos=phase.deadlineNanos;
                        var attempt=new Attempt(result);result.dispatchedNanos=attempt.dispatchedNanos;
                        attempt.future=new FutureTask<>(()->{
                            try{return sample(result,fixture,profile);}
                            catch(Exception|AssertionError failure){result.error=failure.getClass().getSimpleName();result.disposition="FAILED";
                                result.completed=false;result.finishedNanos=System.nanoTime();result.finished=true;throw failure;}
                        });
                        // Registration precedes execution, so cancelled/queued attempts survive exceptional exits.
                        phase.attempts.add(attempt);group.add(attempt);requirePhaseTime();
                        readers.execute(attempt.future);phaseSubmitted++;
                    }
                    for(var attempt:group) {
                        beforeAwait.accept(attempt.future);requirePhaseTime();
                        long remaining=phase.deadlineNanos-System.nanoTime();
                        if(remaining<=0)throw new PhaseDeadlineReached();
                        try{measured.add(attempt.future.get(remaining,TimeUnit.NANOSECONDS));}
                        catch(TimeoutException timeout){throw new PhaseDeadlineReached();}
                    }
                }
            }
            phase.stopReason="COMPLETED";phase.stoppedNanos=System.nanoTime();return phaseStart;
        }catch(Exception|AssertionError failure) {
            stopPhase(failure instanceof PhaseDeadlineReached?"PHASE_DEADLINE":failure.getClass().getSimpleName());throw failure;
        }
    }
    void requirePhaseTime(){
        checkTime();if(System.nanoTime()>=phase.deadlineNanos)throw new PhaseDeadlineReached();
    }
    void stopPhase(String reason){
        if(phase==null)return;if(phase.stoppedNanos==0){phase.stoppedNanos=System.nanoTime();phase.stopReason=reason;}
        for(var attempt:phase.attempts)if(!attempt.sample.finished){attempt.sample.cancellationReason=reason;attempt.sample.cancelRequested=true;}
        if(observed!=null)observed.cancelAll();
        for(var attempt:phase.attempts)if(!attempt.future.isDone())attempt.future.cancel(true);
    }
    void collectPhaseEvidence(long phaseStart){
        long stopped=phase==null||phase.stoppedNanos==0?System.nanoTime():phase.stoppedNanos;
        long cutoff=phase==null?stopped:Math.min(stopped,phase.deadlineNanos);
        receipt.put("measurementElapsedMillis",ms(cutoff-phaseStart));
        if(phase!=null)receipt.put("phaseBoundary",Map.of("stopReason",phase.stopReason,"registeredAttempts",phase.attempts.size(),
            "absoluteBudgetMillis",ms(phase.deadlineNanos-phase.startedNanos),"observedStopDecisionMillis",ms(stopped-phaseStart),
            "deadlineDetectionLagMillis",ms(Math.max(0,stopped-phase.deadlineNanos)),"completionRule","finished before absolute cutoff; cancelled and late results excluded"));
        receipt.put("samples",measured.stream().map(Sample::json).toList());
        receipt.put("nativeSummary",summary(measured.stream().filter(s->s.mode==Mode.OFF).toList()));
        receipt.put("candidateSummary",summary(measured.stream().filter(s->s.mode==Mode.ACTIVE).toList()));
        receipt.put("maximumLiveRequestConnections",observed.maximumReaders.get());receipt.put("statementErrors",observed.errors);
    }
    Path finishRun(String selected,long started,Throwable primary)throws Exception {
        long cleanupStarted=phase!=null&&phase.stoppedNanos!=0&&!phase.stopReason.equals("COMPLETED")?
            Math.min(phase.stoppedNanos,phase.deadlineNanos):System.nanoTime();
        long cleanupDeadline=cleanupStarted+TimeUnit.SECONDS.toNanos(5);
        boolean restoreInterrupt=Thread.interrupted();
        if(phase!=null&&phase.stoppedNanos==0)stopPhase(primary==null?"FINALIZATION":primary.getClass().getSimpleName());
        else if(observed!=null)observed.cancelAll();
        if(phase!=null)for(var attempt:phase.attempts)if(!attempt.future.isDone()){
            attempt.sample.cancelRequested=true;attempt.sample.cancellationReason=phase.stopReason;attempt.future.cancel(true);
        }
        for(var executor:executors)executor.shutdownNow();
        boolean stopped=true;
        for(var executor:executors)try{stopped&=executor.awaitTermination(Math.max(0,cleanupDeadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
            catch(InterruptedException interrupted){restoreInterrupt=true;stopped=false;}
        if(phase!=null) {
            measured.clear();
            for(var attempt:phase.attempts){var sample=attempt.sample;
                sample.futureCancelled=attempt.future.isCancelled();
                if(!sample.finished){sample.completed=false;sample.error=sample.startedNanos==0?"CANCELLED_BEFORE_START":"IN_FLIGHT_AT_CLEANUP_LIMIT";sample.disposition=sample.error;}
                else if(sample.futureCancelled||sample.cancelRequested||sample.finishedNanos>phase.deadlineNanos){sample.completed=false;
                    if(sample.error.isEmpty())sample.error=sample.futureCancelled||sample.cancelRequested?"PHASE_CANCELLED":"RESULT_AFTER_PHASE_DEADLINE";
                    sample.disposition=sample.futureCancelled||sample.cancelRequested?"FAILED_CANCELLED":"LATE_RESULT_EXCLUDED";}
                measured.add(sample);
            }
            collectPhaseEvidence(phase.startedNanos);
        }
        receipt.put("prePhaseSamples",startedSamples.stream().filter(s->!s.purpose.equals("measurement")).map(Sample::json).toList());
        if(gateDirectory!=null)try{Files.deleteIfExists(gateDirectory.resolve("closed"));Files.deleteIfExists(gateDirectory);}
            catch(java.io.IOException error){receipt.put("gateCleanupError",error.getClass().getSimpleName());stopped=false;}
        receipt.put("cleanup",Map.of("executorsTerminated",stopped,"activePooledConnections",dataSource.getHikariPoolMXBean().getActiveConnections(),
            "activeObservedConnections",observed==null?0:observed.activeConnections.get(),"activeObservedStatements",observed==null?0:observed.active.size(),
            "statementCancellationCalls",observed==null?0:observed.cancellationCalls.get(),"elapsedMillis",ms(System.nanoTime()-cleanupStarted),
            "maximumCleanupMillis",5000,"scope","bounded cleanup after experiment cutoff; never extra sampling time"));
        receipt.put("cancellationErrors",observed==null?List.of():observed.cancellationErrors);
        receipt.put("plans",plans);receipt.put("metricGaps",gaps);receipt.put("elapsedMillis",ms(System.nanoTime()-started));
        Path out=Path.of("/tmp/finance-bi-performance-qa/first-settlement-load");Files.createDirectories(out);
        Path file=out.resolve(selected+"-"+runId+".json");
        try{Files.writeString(file,repo.write(receipt),java.nio.file.StandardOpenOption.CREATE_NEW);System.out.println("FSF_LOAD_RECEIPT "+file.toAbsolutePath());
            if(primary==null){assertTrue(stopped);assertEquals(0,dataSource.getHikariPoolMXBean().getActiveConnections());assertEquals(0,observed.activeConnections.get());assertTrue(observed.active.isEmpty());}}
        finally{if(restoreInterrupt)Thread.currentThread().interrupt();}
        return file;
    }
    void resetHarness(){
        current.remove();executors.clear();receipt.clear();planInputs.clear();gaps.clear();plans.clear();measured.clear();startedSamples.clear();mutations.clear();
        sampleSequence.set(0);observed=null;instrumented=null;worker=null;gateDirectory=null;phaseSubmitted=0;phaseStartedNanos=0;historyAvailable=false;phase=null;
    }

    @Test void shortPhaseDeadlineCancelsInFlightWorkspaceAndPreventsTheFollowingMode()throws Exception {
        var control=controlledExit(false);
        assertAll("Absolute phase deadline and real JDBC cancellation",()->assertTrue(control.failure() instanceof PhaseDeadlineReached),
            ()->assertEquals("FAILED_OR_INCOMPLETE",control.saved().get("status")),
            ()->assertEquals(3,rows(control.saved().get("samples")).size(),"Two completed plus one interrupted attempt must be retained"),
            ()->assertEquals(2,rows(control.saved().get("samples")).stream().filter(s->Boolean.TRUE.equals(s.get("completed"))).count()),
            ()->assertTrue(startedSamples.stream().allMatch(s->s.startedNanos<phaseStartedNanos+TimeUnit.MILLISECONDS.toNanos(600)),"No second mode may start beyond the absolute phase deadline"),
            ()->assertTrue(number(map(control.saved().get("cleanup")).get("statementCancellationCalls"))>0),
            ()->assertTrue(control.elapsedMillis()<3000,"SLEEP(5) must be cancelled, not awaited to natural completion"));
        assertTrue(decimal(control.saved().get("measurementElapsedMillis")).compareTo(decimal("600"))<=0);
        assertEquals("PHASE_DEADLINE",map(control.saved().get("phaseBoundary")).get("stopReason"));
        assertCancelledSample(control.saved());
        assertControlCleanup(control.saved());
    }
    @Test void exceptionalExitRetainsCompletedAndRealInterruptedWorkspaceAttemptsInItsReceipt()throws Exception {
        var control=controlledExit(true);var samples=rows(control.saved().get("samples"));
        assertAll("Exceptional evidence retention",()->assertTrue(control.failure() instanceof ControlledFailure),
            ()->assertEquals("FAILED_OR_INCOMPLETE",control.saved().get("status")),
            ()->assertEquals(3,samples.size(),"The finally receipt must preserve completed and cancelled/in-flight attempts"),
            ()->assertEquals(2,samples.stream().filter(s->Boolean.TRUE.equals(s.get("completed"))).count()),
            ()->assertEquals(1,samples.stream().filter(s->!Boolean.TRUE.equals(s.get("completed"))&&!text(s.get("error")).isBlank()).count()),
            ()->assertFalse(rows(control.saved().get("statementErrors")).isEmpty(),"Cancellation must be an actual driver failure, not a fake financial result"));
        assertControlCleanup(control.saved());
        assertCancelledSample(control.saved());
    }
    record ControlResult(Map<String,Object> saved,Throwable failure,double elapsedMillis){}
    static final class ControlledFailure extends RuntimeException{}
    ControlResult controlledExit(boolean exceptional)throws Exception {
        resetHarness();long started=System.nanoTime();hardDeadline=started+TimeUnit.SECONDS.toNanos(30);runId=UUID.randomUUID().toString().substring(0,8);
        observed=new TracingSource(dataSource);instrumented=new FinanceRepository(new JdbcTemplate(observed),new ObjectMapper());
        var fixture=seedFixture(16,4);initializeAndPublish(fixture,64,false);
        var profile=new Profile("deadline-control",16,4,"clean",1,64,64,500,10000,2000,0);
        receipt.put("schema","first-settlement-load-control-v1");receipt.put("selectedProfile",profile.inputs());receipt.put("status","RUNNING");receipt.put("admitted",false);
        observed.sqlTransform=(sample,sql)->sample!=null&&sample.sequence==3&&sql.startsWith("SELECT id,platform,group_id,status FROM finance_connections")?sql+" AND SLEEP(5)=0":sql;
        Throwable failure=null;Path file;long phaseStart=0;
        try {
            phaseStart=runPhase(fixture,profile,2,TimeUnit.MILLISECONDS.toNanos(exceptional?5000:600),future->{
                if(phaseSubmitted==3){awaitExecutingSleep();if(exceptional)throw new ControlledFailure();}
            });
            collectPhaseEvidence(phaseStart);receipt.put("status","CONTROL_COMPLETED");
        }catch(Exception|AssertionError problem){failure=problem;receipt.put("status","FAILED_OR_INCOMPLETE");receipt.put("failure",problem.getClass().getSimpleName());}
        finally {file=finishRun(exceptional?"exception-control":"deadline-control",started,failure);}
        return new ControlResult(repo.read(Files.readString(file)),failure,ms(System.nanoTime()-phaseStartedNanos));
    }
    void awaitExecutingSleep(){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        try {
            while(System.nanoTime()<until){if(observed.active.stream().anyMatch(FinanceFirstSettlementLoadIntegrationTest::mysqlExecuting)){
                Thread.sleep(30);assertTrue(observed.active.stream().anyMatch(FinanceFirstSettlementLoadIntegrationTest::mysqlExecuting),"The actual MySQL query must remain in flight");receipt.put("realDriverInFlightObserved",true);return;}
                Thread.sleep(2);
            }
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        throw new AssertionError("The controlled real SQL never entered driver execution");
    }
    void assertControlCleanup(Map<String,Object> saved){
        var cleanup=map(saved.get("cleanup"));assertTrue(Boolean.TRUE.equals(saved.get("realDriverInFlightObserved")));assertEquals(true,cleanup.get("executorsTerminated"));
        assertEquals(0,number(cleanup.get("activePooledConnections")));assertEquals(0,number(cleanup.get("activeObservedConnections")));assertEquals(0,number(cleanup.get("activeObservedStatements")));
        assertTrue(decimal(cleanup.get("elapsedMillis")).compareTo(decimal("5000"))<=0,"Cancellation and executor/resource exit share the bounded cleanup interval");
    }
    void assertCancelledSample(Map<String,Object> saved){
        var interrupted=rows(saved.get("samples")).stream().filter(s->!Boolean.TRUE.equals(s.get("completed"))).toList();assertEquals(1,interrupted.size());
        var sample=interrupted.getFirst();assertEquals("FAILED_CANCELLED",sample.get("disposition"));assertEquals(false,sample.get("fourMetricsEqual"));
        assertEquals("NO_PERIOD",sample.get("route"));assertEquals(1,number(sample.get("controlledRealDelayStatements")));
        assertEquals(false,sample.get("workspaceReturned"));assertTrue(decimal(sample.get("workspaceMillis")).signum()>0);
        assertEquals("NOT_STARTED",sample.get("nativeValidationMillis"));assertEquals("NOT_STARTED",sample.get("oracleValidationMillis"));
    }

    List<Map<String,Object>> manifest(String selected){
        return profiles().values().stream().map(p->{var row=p.inputs();row.put("execution",p.name().equals(selected)?"SELECTED":"UNEXECUTED");
            row.put("implementation",p.continuous()?"PENDING_CONTINUOUS_THREE_WINDOW_STAGE":"SELECTABLE_STATIC_HARNESS");return row;}).toList();
    }
    record Fixture(String shop,int owners,int candidates) {}
    Fixture seedFixture(int owners,int candidates)throws Exception {
        checkTime();long start=System.nanoTime();String shop=groupedShop(COMPANY,"load-shop",null);var random=new Random(SEED);
        db.update("INSERT INTO finance_label_state(organization_id,active_generation,requested_revision,applied_revision) VALUES(?,'load-active',1,1)",COMPANY.organizationId());
        db.update("INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,status,row_count,matched_count,amount_cny,created_by) VALUES('load-file',?,'synthetic-load.xlsx',?,'',0,'READY',?,?,0,?)",
            COMPANY.organizationId(),"a".repeat(64),owners,owners,COMPANY.userId());
        for(int first=0;first<owners;first+=100) {
            checkTime();var sourceArgs=new ArrayList<Object>();var totalsArgs=new ArrayList<Object>();var invoiceArgs=new ArrayList<Object>();int size=Math.min(100,owners-first);
            for(int index=first;index<first+size;index++) {
                String order=order(index);LocalDate date=START.plusDays(random.nextInt(28));
                BigDecimal amount=index%17==0?BigDecimal.ZERO:BigDecimal.valueOf(random.nextInt(20001)-10000,6);
                Collections.addAll(totalsArgs,COMPANY.organizationId(),shop,order,amount,index%3,index%4+1);
                Collections.addAll(invoiceArgs,index+1,COMPANY.organizationId(),"event-"+index,"row-"+index,"bucket-"+index,order,amount,shop);
                for(int row=0;row<candidates;row++)Collections.addAll(sourceArgs,recordId(index,row),COMPANY.organizationId(),COMPANY.userId(),shop,
                    index%2==0?"SHOPEE":"TIKTOK_SHOP",recordId(index,row),order,java.sql.Date.valueOf(date.plusDays(row/2)),row%2==0?"PHP":"USD");
            }
            db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES "+
                String.join(",",Collections.nCopies(size,"(?,'load-active',?,?,?,?,?)")),totalsArgs.toArray());
            db.update("INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note,match_checked) VALUES "+
                String.join(",",Collections.nCopies(size,"('load-file',?,?,?,?,?,?,'synthetic shop','synthetic customer','synthetic warehouse','synthetic document','2026-08-01',?,?,'MATCHED','synthetic matched',TRUE)")),invoiceArgs.toArray());
            db.update("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,category,status,record_date,currency,source,raw_json) VALUES "+
                String.join(",",Collections.nCopies(size*candidates,"(?,?,?,?,?,'SETTLEMENT',?,?,'ORDER','SETTLED',?,?,'API','{}')")),sourceArgs.toArray());
        }
        receipt.put("sourceSeed",Map.of("owners",owners,"eligibleRows",owners*candidates,"invoiceSourceRows",owners,"sourceFiles",1,"elapsedMillis",ms(System.nanoTime()-start),
            "excludedFromRequestLatency",true,"seed",SEED));return new Fixture(shop,owners,candidates);
    }
    void initializeAndPublish(Fixture fixture,int batch,boolean collectPlans)throws Exception {
        long start=System.nanoTime();var init=new FinanceFirstSettlementInitialization(instrumented);var facts=new FinanceFirstSettlementFacts(instrumented);
        init.discoverCompanies(8);var guard=facts.guard(COMPANY.organizationId()).orElseThrow();
        boolean seeded=false,requeued=false;
        for(int n=0;n<fixture.owners()+10;n++){checkTime();if(init.seed(guard,batch).complete()){seeded=true;break;}}
        for(int n=0;n<fixture.owners()+10;n++){checkTime();if(init.requeue(guard,batch).complete()){requeued=true;break;}}
        assertTrue(seeded&&requeued&&init.complete(guard));
        long initialized=System.nanoTime();
        if(collectPlans){capturePlan("seed-per-shop",planInputs.get("SEED"));capturePlan("claim-pending",new StatementTrace("CLAIM","SETUP",
            "SELECT organization_id,shop_id,order_id FROM finance_first_settlement_facts FORCE INDEX(idx_fsf_pending) WHERE organization_id=? AND pending=TRUE ORDER BY shop_id,order_id LIMIT ?",List.of(COMPANY.organizationId(),32)));}
        if(gateDirectory==null)gateDirectory=Files.createTempDirectory("finance-fsf-load-gate-");
        var constructor=MaintenanceGate.class.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
        var gate=constructor.newInstance(gateDirectory.resolve("closed"));
        worker=new FinanceFirstSettlementWorker(facts,init,batch,30,8,Duration.ofSeconds(2),System::nanoTime,gate);
        long publicationStarted=System.nanoTime();
        int ticks=0,published=0,claimed=0,rejected=0;
        while(pending()>0) {
            checkTime();var tick=worker.tick();ticks++;published+=tick.published();claimed+=tick.claimed();rejected+=tick.rejected();
            if(ticks>fixture.owners()*2+20)throw new IllegalStateException("Real worker publication did not converge within bounded ticks");
        }
        assertTrue(facts.guard(COMPANY.organizationId()).orElseThrow().seedComplete());
        assertEquals(fixture.owners(),db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? AND applied_revision=requested_revision AND applied_revision>0 AND schema_version=1 AND applied_generation=1 AND applied_registration_version=1",Integer.class,COMPANY.organizationId()));
        if(collectPlans)receipt.put("initializationAndBackfill",Map.of("initializationMillis",ms(initialized-start),"prePublicationPlanAndWorkerSetupMillis",ms(publicationStarted-initialized),"publicationMillis",ms(System.nanoTime()-publicationStarted),
            "workerBatch",batch,"workerLanes",1,"ticks",ticks,"claimed",claimed,"successfulPublications",published,"rejected",rejected,"excludedFromRequestLatency",true));
    }
    void dirty(Fixture fixture,int owners) {
        for(int index=0;index<owners;index++) {
            long before=System.nanoTime();
            db.update("UPDATE finance_records SET currency=IF(currency='PHP','USD','PHP'),record_date=IF(?=0,'2026-09-30',record_date) WHERE id=?",index%2,recordId(index,0));
            long after=System.nanoTime();long revision=db.queryForObject("SELECT requested_revision FROM finance_first_settlement_facts WHERE organization_id=? AND shop_id=? AND order_id=?",Long.class,COMPANY.organizationId(),fixture.shop(),order(index));
            mutations.add(Map.of("commitLowerNanos",before,"commitUpperNanos",after,"requestedRevision",revision));
        }
        assertEquals(owners,pending());
    }

    Sample sample(Fixture fixture,Profile profile,Mode mode,String purpose,CyclicBarrier barrier) {
        var result=newSample(mode,purpose,profile.owners());result.barrier=barrier;return sample(result,fixture,profile);
    }
    Sample newSample(Mode mode,String purpose,int owners){var result=new Sample(mode,purpose,owners);result.sequence=sampleSequence.incrementAndGet();return result;}
    Sample sample(Sample result,Fixture fixture,Profile profile) {
        result.startedNanos=System.nanoTime();startedSamples.add(result);
        if(result.cancelRequested||result.startedNanos>=result.phaseDeadlineNanos){result.completed=false;result.error="PHASE_NOT_STARTED_DEADLINE";
            result.disposition="NOT_STARTED_DEADLINE";result.finishedNanos=System.nanoTime();result.finished=true;return result;}
        current.set(result);result.disposition="RUNNING";var mode=result.mode;
        long start=System.nanoTime();
        var config=new FinanceLabelFirstSettlementFacts.Configuration(mode,profile.cap(),mode==Mode.OFF?List.of():List.of(
            new ApprovedRange(COMPANY.organizationId(),START,END,mode,1,1,"synthetic-load-"+runId)),
            new CandidateBudget(profile.candidateMillis(),profile.nativeReserveMillis(),profile.completionReserveMillis()),"");
        var labels=new FinanceLabelCosts(instrumented,null,"target/first-settlement-load-unused",config,System::nanoTime);
        try {
            instrumented.readSnapshot(30,()->{
                result.stage="WORKSPACE";result.workspaceStarted=true;long work=System.nanoTime();Map<String,Object> page;
                try{page=labels.workspace(COMPANY,WINDOW,Map.of());result.workspaceReturned=true;}
                finally{result.workspaceNanos=System.nanoTime()-work;}
                assertEquals(Math.min(50,fixture.owners()),rows(page.get("rows")).size());assertEquals(fixture.owners(),number(page.get("total")));
                assertEquals(1,rows(page.get("files")).size());assertNotNull(page.get("state"));
                var value=period(map(page.get("period")));result.hash=FinanceLabelFirstSettlementFacts.normalizedHash(value);
                result.stage="NATIVE_VALIDATION";result.nativeValidationStarted=true;long validation=System.nanoTime();Period nativeValue;
                try{nativeValue=period(labels.nativePeriod(COMPANY,WINDOW));}finally{result.nativeValidationNanos=System.nanoTime()-validation;}
                result.stage="ORACLE_VALIDATION";result.oracleValidationStarted=true;validation=System.nanoTime();Period original;
                try{original=originalPeriod();}finally{result.oracleValidationNanos=System.nanoTime()-validation;}
                if(!FinanceLabelFirstSettlementFacts.equal(value,nativeValue)||!FinanceLabelFirstSettlementFacts.equal(value,original))
                    result.hardFailure="Financial mismatch between workspace, original native and independent identity hashes";
                assertTrue(result.snapshotValid,"Every workspace/validation SQL must retain the same real holder/connection/deadline");
                result.stage="METRIC";if(historyAvailable)readActualRows(result);
                return null;
            });result.completed=true;
        }catch(Exception|AssertionError failure) {
            result.error=failure.getClass().getSimpleName();result.timeout=causedBy(failure,SQLTimeoutException.class)||failure.getClass().getSimpleName().contains("TimedOut");
            if(failure instanceof AssertionError)result.hardFailure=failure.getMessage();
        }finally {result.rrNanos=System.nanoTime()-start;current.remove();labels.stop();}
        result.facts=result.statements.stream().anyMatch(s->s.stage.equals("WORKSPACE")&&s.operation.equals("FACT_AGGREGATE"));
        result.nativeWorkspace=result.statements.stream().anyMatch(s->s.stage.equals("WORKSPACE")&&s.operation.equals("NATIVE_PERIOD"));
        result.finishedNanos=System.nanoTime();
        if(result.cancelRequested||result.finishedNanos>result.phaseDeadlineNanos){result.completed=false;
            if(result.error.isEmpty())result.error=result.cancelRequested?"PHASE_CANCELLED":"RESULT_AFTER_PHASE_DEADLINE";
            result.disposition=result.cancelRequested?"FAILED_CANCELLED":"LATE_RESULT_EXCLUDED";
        }else result.disposition=result.completed?"COMPLETED":"FAILED";
        result.finished=true;
        return result;
    }
    Period originalPeriod(){
        var q=instrumented.query(COMPANY,WINDOW,"r");var args=new ArrayList<Object>();args.add(COMPANY.organizationId());args.addAll(q.args());
        return period(instrumented.jdbc().queryForMap("""
            SELECT COUNT(*) orders,COALESCE(SUM(lc.amount_cny),0) amount_cny,
              COALESCE(SUM(lc.pending_count),0) conflicts,COALESCE(SUM(lc.shipment_count),0) shipments
            FROM finance_label_state ls JOIN finance_label_order_totals lc
              ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation
            JOIN finance_records r ON r.organization_id=lc.organization_id AND r.shop_id=lc.shop_id AND r.order_id=lc.order_id
            WHERE ls.organization_id=? AND
            """+FinanceCostReport.firstSettlementIdentity("r",true)+" AND "+FinanceOrderCharges.normalOrder("r")+" AND "+q.where(),args.toArray()));
    }
    static Period period(Map<String,Object> p){return new Period(decimal(p.get("amount_cny")),number(p.get("orders")),number(p.get("conflicts")),number(p.get("shipments")));}
    void requireSuccess(Sample sample){assertTrue(sample.completed,"Smoke failed: "+sample.error);assertNull(sample.hardFailure);assertTrue(sample.snapshotValid);}
    boolean accelerationGuard(List<Sample> samples){return samples.stream().anyMatch(s->s.completed&&s.hardFailure==null&&s.facts&&!s.nativeWorkspace);}

    Map<String,Object> summary(List<Sample> samples){
        var complete=samples.stream().filter(s->s.completed).toList();var out=new LinkedHashMap<String,Object>();
        out.put("attempts",samples.size());out.put("completed",complete.size());out.put("errors",samples.stream().filter(s->!s.error.isEmpty()).count());
        out.put("timeouts",samples.stream().filter(s->s.timeout).count());out.put("facts",complete.stream().filter(s->s.facts).count());
        out.put("nativeWorkspace",complete.stream().filter(s->s.nativeWorkspace).count());
        out.put("fallbackRatio",complete.isEmpty()?"UNAVAILABLE":complete.stream().filter(s->s.nativeWorkspace&&s.mode!=Mode.OFF).count()/(double)complete.size());
        out.put("workspaceMillis",percentiles(complete.stream().map(s->s.workspaceNanos).toList()));out.put("rrLifecycleMillis",percentiles(complete.stream().map(s->s.rrNanos).toList()));
        out.put("connectionAcquireMillis",percentiles(complete.stream().map(s->s.acquireNanos).toList()));
        out.put("nativeValidationMillis",percentiles(complete.stream().map(s->s.nativeValidationNanos).toList()));out.put("oracleValidationMillis",percentiles(complete.stream().map(s->s.oracleValidationNanos).toList()));
        out.put("nativeOwnerLookups",complete.stream().flatMap(s->s.statements.stream()).filter(s->s.stage.equals("WORKSPACE")&&s.operation.equals("DIRTY_LOOKUP")).count());
        out.put("perOwnerLookupMillis",percentiles(complete.stream().flatMap(s->s.statements.stream()).filter(s->s.stage.equals("WORKSPACE")&&s.operation.equals("DIRTY_LOOKUP")).map(s->s.nanos).toList()));
        out.put("allCompletedHashesMatch",complete.stream().allMatch(s->s.hardFailure==null));return out;
    }
    Map<String,Object> admission(List<Sample> nativeSamples,List<Sample> candidates){
        var reasons=new ArrayList<String>();
        if(nativeSamples.stream().filter(s->s.completed).count()<30||candidates.stream().filter(s->s.completed).count()<30)reasons.add("INSUFFICIENT_COMPLETED_SAMPLES");
        if(candidates.stream().anyMatch(s->!s.error.isEmpty()))reasons.add("REQUEST_ERRORS");
        if(!accelerationGuard(candidates))reasons.add("NO_NON_FALLBACK_FACTS");
        if(!gaps.isEmpty())reasons.add("REQUIRED_METRIC_GAPS");
        if(p95(candidates)>=p95(nativeSamples))reasons.add("NO_P95_IMPROVEMENT");
        reasons.add("CONTINUOUS_THREE_30_SECOND_WINDOWS_NOT_RUN");
        return Map.of("admitted",false,"reasons",reasons,"p95ImprovedInThisFixture",p95(candidates)<p95(nativeSamples),"productionSelection","NONE");
    }
    double p95(List<Sample> samples){var values=samples.stream().filter(s->s.completed).map(s->s.workspaceNanos).sorted().toList();return values.isEmpty()?Double.POSITIVE_INFINITY:ms(values.get((int)Math.ceil(values.size()*.95)-1));}
    static Map<String,Object> percentiles(List<Long> values){
        if(values.isEmpty())return Map.of("status","NOT_APPLICABLE_OR_UNAVAILABLE","count",0);
        var sorted=values.stream().sorted().toList();return Map.of("count",sorted.size(),"p50",ms(sorted.get((int)Math.ceil(sorted.size()*.5)-1)),
            "p95",ms(sorted.get((int)Math.ceil(sorted.size()*.95)-1)),"max",ms(sorted.getLast()));
    }
    Map<String,Object> age(){
        if(mutations.isEmpty())return Map.of("status",pending()==0?"NO_PENDING_OWNER":"UNKNOWN_UNCONTROLLED_PENDING","source","controlled mutation/commit intervals, never updated_at");
        long now=System.nanoTime();long lower=mutations.stream().mapToLong(m->number(m.get("commitLowerNanos"))).min().orElseThrow();
        long upper=mutations.stream().mapToLong(m->number(m.get("commitUpperNanos"))).min().orElseThrow();
        return Map.of("status","CONTROLLED_STATIC_PENDING","oldestAgeLowerMillis",ms(now-upper),"oldestAgeUpperMillis",ms(now-lower),"observations",mutations.size(),"pendingOwners",pending());
    }

    Map<String,Object> environment(){
        var out=new LinkedHashMap<String,Object>();out.put("dataSourceWrapper",TracingSource.class.getName());out.put("actualDelegateClass",dataSource.getClass().getName());
        out.put("localPool",Map.of("type","HikariDataSource","maximumConnections",dataSource.getMaximumPoolSize(),"minimumIdle",dataSource.getMinimumIdle(),
            "measurement","actual local datasource acquisition; not production connection-pool waiting"));
        out.put("server",db.queryForMap("SELECT VERSION() mysql_version,@@innodb_buffer_pool_size buffer_pool_bytes,@@innodb_page_size innodb_page_size,@@max_connections max_connections"));
        out.put("runner",Map.of("perClassMavenDeadlineSeconds",600,"mysqlTmpfsLimitBytes",3L*1024*1024*1024,"source","unchanged scripts/ci/java.py",
            "mysqlContainerCpuLimit","NOT_OBSERVED","mysqlContainerMemoryLimit","NOT_OBSERVED"));
        out.put("testJvm",Map.of("maximumHeapBytes",Runtime.getRuntime().maxMemory(),"availableProcessors",Runtime.getRuntime().availableProcessors(),"scope","test JVM host, not MySQL container"));return out;
    }
    void probeMetrics(){
        try {
            db.queryForList("SELECT EVENT_ID,ROWS_EXAMINED,LOCK_TIME,SQL_TEXT FROM performance_schema.events_statements_history_long WHERE 1=0");
            var rows=db.queryForList("SELECT ENABLED FROM performance_schema.setup_consumers WHERE NAME='events_statements_history_long'");
            historyAvailable=!rows.isEmpty()&&"YES".equals(rows.getFirst().get("ENABLED"));
            if(!historyAvailable)gap("actual_rows_examined_and_statement_lock_time","HISTORY_LONG_CONSUMER_DISABLED",null);
        }catch(RuntimeException failure){gap("actual_rows_examined_and_statement_lock_time","EXISTING_PRIVILEGE_OR_CAPABILITY_GAP",failure);}
        try{db.queryForList("SELECT * FROM performance_schema.data_lock_waits LIMIT 1");}
        catch(RuntimeException failure){gap("individual_lock_wait_events","EXISTING_PRIVILEGE_OR_CAPABILITY_GAP",failure);}
        receipt.put("metricsCapabilities",Map.of("statementHistoryLongAvailable",historyAvailable,"initialGaps",List.copyOf(gaps)));
        System.out.println("FSF_LOAD_METRICS "+repo.write(receipt.get("metricsCapabilities")));
    }
    void readActualRows(Sample sample){
        var rows=instrumented.jdbc().queryForList("SELECT SQL_TEXT,ROWS_EXAMINED,LOCK_TIME FROM performance_schema.events_statements_history_long WHERE THREAD_ID=(SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=CONNECTION_ID()) AND SQL_TEXT LIKE ?","/*fsf-load:"+runId+":"+sample.id+":%" );
        var workspace=rows.stream().filter(r->text(r.get("SQL_TEXT")).startsWith("/*fsf-load:"+runId+":"+sample.id+":WORKSPACE*/")).toList();
        long expected=sample.statements.stream().filter(s->s.stage.equals("WORKSPACE")).count();
        if(workspace.size()!=expected){sample.rowsStatus="INCOMPLETE_HISTORY_COVERAGE";return;}
        sample.rowsExamined=workspace.stream().mapToLong(r->number(r.get("ROWS_EXAMINED"))).sum();sample.lockPicos=workspace.stream().mapToLong(r->number(r.get("LOCK_TIME"))).sum();sample.rowsStatus="ACTUAL_COMPLETE_WORKSPACE_STATEMENTS";
    }
    Map<String,Long> globalStatus(){
        var out=new LinkedHashMap<String,Long>();
        try {
            for(var row:db.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Innodb_buffer_pool_pages_data','Innodb_buffer_pool_pages_free','Innodb_deadlocks','Innodb_row_lock_waits','Innodb_row_lock_time')"))
                out.put(text(row.get("Variable_name")),Long.parseLong(text(row.get("Value"))));
            for(String required:List.of("Innodb_deadlocks","Innodb_row_lock_waits","Innodb_row_lock_time"))if(!out.containsKey(required))gap(required,"GLOBAL_COUNTER_UNAVAILABLE",null);
        }catch(RuntimeException failure){gap("global_server_status","EXISTING_PRIVILEGE_OR_CAPABILITY_GAP",failure);}return out;
    }
    Map<String,Long> delta(Map<String,Long> before,Map<String,Long> after){var out=new LinkedHashMap<String,Long>();before.forEach((key,value)->{if(after.containsKey(key))out.put(key,after.get(key)-value);});return out;}
    void gap(String metric,String reason,Throwable failure){
        var out=new LinkedHashMap<String,Object>();out.put("metric",metric);out.put("status",reason);
        SQLException sql=findSql(failure);if(sql!=null){out.put("sqlState",String.valueOf(sql.getSQLState()));out.put("vendorCode",sql.getErrorCode());}
        gaps.add(out);
    }

    void captureReaderPlans(){
        capturePlan("clean-totals",planInputs.get("FACT_AGGREGATE"));capturePlan("missing-old-mode-enumeration",planInputs.get("UNRESOLVED"));
        var lookup=new StatementTrace("DIRTY_LOOKUP","PLAN","SELECT r0.id,r0.organization_id,r0.shop_id,r0.order_id,r0.record_date,r0.platform,r0.currency,r0.status FROM finance_records r0 FORCE INDEX(idx_finance_first_settlement) WHERE r0.organization_id=? AND r0.shop_id=? AND r0.order_id=? AND "+
            FinanceFirstSettlementFacts.eligible("r0")+" ORDER BY r0.organization_id,r0.shop_id,r0.kind,r0.order_id,r0.source,r0.status,r0.record_date,r0.id LIMIT 1",
            List.of(COMPANY.organizationId(),db.queryForObject("SELECT id FROM finance_connections WHERE organization_id=? LIMIT 1",String.class,COMPANY.organizationId()),order(0)));
        capturePlan("dirty-full-history-lookup",lookup);
    }
    void captureDatePlans(Fixture fixture){
        var guard=new FinanceFirstSettlementFacts(instrumented).guard(COMPANY.organizationId()).orElseThrow();
        assertTrue(guard.seedComplete(),"Date-only candidate experiments are forbidden until coverage is complete");
        String clean="f.applied_revision=f.requested_revision AND f.applied_revision>0 AND f.schema_version=1 AND f.applied_generation=1 AND f.applied_registration_version=1";
        capturePlan("date-index-candidate-test-only",new StatementTrace("DATE_INDEX","PLAN","SELECT f.first_record_id,f.first_record_date,f.first_platform,f.first_currency FROM finance_first_settlement_facts f FORCE INDEX(idx_fsf_date) WHERE f.organization_id=? AND f.has_settlement=TRUE AND "+clean+" AND f.first_record_date BETWEEN ? AND ?",
            List.of(COMPANY.organizationId(),java.sql.Date.valueOf(START),java.sql.Date.valueOf(END))));
        receipt.put("dateBulkScope",Map.of("publicRoute",false,"coverageGateVerified",true,"dirtyOldNewMonthClosure","UNEXECUTED_PENDING_STAGED_DATE_BULK_PROBE",
            "decision","plan inspection only; no date-bulk admission"));
        receipt.put("storage",Map.of("informationSchemaReportedDataAndIndexBytes",db.queryForObject("SELECT COALESCE(SUM(DATA_LENGTH+INDEX_LENGTH),0) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),
            "scope","metadata-reported allocation, not an exact tmpfs measurement","actualDiskUsage","NOT_OBSERVED","mysqlTmpfsCapBytes",3L*1024*1024*1024));
    }
    void capturePlan(String name,StatementTrace statement){
        if(statement==null){gap("plan_"+name,"NO_EXECUTED_STATEMENT_CAPTURE",null);return;}
        String natural=statement.sql.replaceAll("(?i)\\s+FORCE INDEX\\([A-Za-z0-9_]+\\)","");
        for(String access:statement.sql.equals(natural)?List.of("actual_sql"):List.of("actual_forced_sql","natural_without_force")) {
            checkTime();String sql=access.equals("natural_without_force")?natural:statement.sql;
            var out=new LinkedHashMap<String,Object>();out.put("operation",name);out.put("access",access);out.put("syntheticFixture",true);out.put("parameterCount",statement.args.size());
            try {
                var values=instrumented.readSnapshot(5,()->Map.of("estimatedJson",instrumented.jdbc().queryForObject("EXPLAIN FORMAT=JSON "+sql,String.class,statement.args.toArray()),
                    "actualAnalyzeTree",instrumented.jdbc().queryForObject("EXPLAIN ANALYZE "+sql,String.class,statement.args.toArray())));
                boolean truncated=values.values().stream().anyMatch(v->v.length()>100000);var bounded=new LinkedHashMap<String,String>();values.forEach((k,v)->bounded.put(k,v.substring(0,Math.min(v.length(),100000))));
                out.putAll(bounded);out.put("truncated",truncated);out.put("status",truncated?"INCOMPLETE_BOUNDED_CAPTURE":"CAPTURED");
            }catch(RuntimeException failure){out.put("status","FAILED");SQLException error=findSql(failure);if(error!=null){out.put("sqlState",String.valueOf(error.getSQLState()));out.put("vendorCode",error.getErrorCode());}gap("plan_"+name,"PLAN_CAPTURE_FAILED",failure);}
            plans.add(out);
        }
    }
    long pending(){return db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? AND pending=TRUE",Long.class,COMPANY.organizationId());}
    long count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE organization_id=?",Long.class,COMPANY.organizationId());}
    void checkTime(){if(System.nanoTime()>=hardDeadline)throw new IllegalStateException("Harness hard deadline reached before the runner's unchanged 600-second limit");}
    static String order(int n){return "load-order-"+String.format(Locale.ROOT,"%08d",n);}
    static String recordId(int n,int row){return "load-record-"+String.format(Locale.ROOT,"%08d-%02d",n,row);}
    static double ms(long nanos){return Math.round(nanos/1000d)/1000d;}
    static SQLException findSql(Throwable error){for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof SQLException sql)return sql;return null;}
    static boolean causedBy(Throwable error,Class<?> type){for(Throwable t=error;t!=null;t=t.getCause())if(type.isInstance(t))return true;return false;}

    static final class Sample {
        final String id=UUID.randomUUID().toString().substring(0,8);final Mode mode;final String purpose;final int owners;
        final List<StatementTrace> statements=new CopyOnWriteArrayList<>();volatile String stage="CONNECT",hash="",error="",hardFailure,rowsStatus="UNAVAILABLE";
        volatile boolean completed,facts,nativeWorkspace,snapshotValid=true,firstQuery=true,timeout,finished,cancelRequested,futureCancelled;
        volatile boolean workspaceStarted,workspaceReturned,nativeValidationStarted,oracleValidationStarted;
        volatile String disposition="REGISTERED",cancellationReason="";CyclicBarrier barrier;
        Connection connection;ConnectionHolder holder;long deadline=-1,connectionId,acquireNanos,workspaceNanos,nativeValidationNanos,oracleValidationNanos,rrNanos,rowsExamined,lockPicos;
        int sequence;volatile long startedNanos,finishedNanos;long dispatchedNanos,phaseStartedNanos,phaseDeadlineNanos=Long.MAX_VALUE;
        Sample(Mode mode,String purpose,int owners){this.mode=mode;this.purpose=purpose;this.owners=owners;}
        Map<String,Object> json(){
            var out=new LinkedHashMap<String,Object>();out.put("mode",mode);out.put("purpose",purpose);out.put("completed",completed);out.put("error",error);out.put("timeout",timeout);
            out.put("route",facts?"FACTS":nativeWorkspace?"NATIVE":"NO_PERIOD");out.put("fourMetricHash",hash);out.put("fourMetricsEqual",completed&&hardFailure==null);
            out.put("attemptSequence",sequence);out.put("disposition",disposition);out.put("terminal",finished);out.put("futureCancelled",futureCancelled);
            out.put("cancellationReason",cancellationReason);out.put("finishedAfterPhaseCutoff",finishedNanos>phaseDeadlineNanos);
            if(phaseStartedNanos>0){out.put("dispatchedOffsetMillis",ms(dispatchedNanos-phaseStartedNanos));
                out.put("startedOffsetMillis",startedNanos==0?"NOT_STARTED":ms(startedNanos-phaseStartedNanos));
                out.put("finishedOffsetMillis",finishedNanos==0?"NOT_FINISHED":ms(finishedNanos-phaseStartedNanos));}
            out.put("controlledRealDelayStatements",statements.stream().filter(s->s.sql.contains("SLEEP(5)")).count());
            out.put("workspaceReturned",workspaceReturned);out.put("workspaceMillis",workspaceStarted?ms(workspaceNanos):"NOT_STARTED");
            out.put("nativeValidationMillis",nativeValidationStarted?ms(nativeValidationNanos):"NOT_STARTED");
            out.put("oracleValidationMillis",oracleValidationStarted?ms(oracleValidationNanos):"NOT_STARTED");out.put("rrLifecycleMillis",ms(rrNanos));out.put("localDataSourceAcquireMillis",ms(acquireNanos));
            out.put("oneConnectionHolderDeadline",snapshotValid);out.put("actualRowsExaminedStatus",rowsStatus);if(rowsStatus.startsWith("ACTUAL")){out.put("actualRowsExamined",rowsExamined);out.put("statementLockTimePicoseconds",lockPicos);}
            out.put("operationCounts",statements.stream().filter(s->s.stage.equals("WORKSPACE")).collect(java.util.stream.Collectors.groupingBy(s->s.operation,LinkedHashMap::new,java.util.stream.Collectors.counting())));
            out.put("candidateEffectiveTimeoutMillis",statements.stream().filter(s->s.stage.equals("WORKSPACE")&&(s.operation.equals("FACT_AGGREGATE")||s.sql.contains("finance_first_settlement_"))).map(s->s.timeoutMillis).toList());return out;
        }
    }
    static final class StatementTrace {
        final String operation,stage,sql;final List<Object> args;long nanos,timeoutMillis,rows;
        StatementTrace(String operation,String stage,String sql,List<Object> args){this.operation=operation;this.stage=stage;this.sql=sql;this.args=args;}
    }
    String operation(String sql){
        if(sql.contains("SUM(lc.amount_cny)")&&sql.contains("f.first_record_id id"))return "FACT_AGGREGATE";
        if(sql.contains("SUM(lc.amount_cny)")&&sql.contains("JOIN LATERAL"))return "NATIVE_PERIOD";
        if(sql.contains(") unresolved LIMIT"))return "UNRESOLVED";
        if(sql.contains("SELECT r0.id,r0.organization_id,r0.shop_id,r0.order_id,r0.record_date"))return "DIRTY_LOOKUP";
        if(sql.contains("SELECT DISTINCT r.order_id")&&sql.contains("finance_records"))return "SEED";
        if(sql.contains("SELECT organization_id,shop_id,order_id")&&sql.contains("idx_fsf_pending"))return "CLAIM";
        if(sql.startsWith("SELECT COUNT(*) FROM finance_label_invoice_rows"))return "SOURCE_COUNT";
        if(sql.contains("SELECT r.import_id,r.source_row"))return "SOURCE_PAGE";
        if(sql.contains("source_name,status,source_type"))return "FILES";
        if(sql.startsWith("SELECT requested_revision,applied_revision,error_message"))return "STATE";
        return "OTHER";
    }
    final class TracingSource extends AbstractDataSource {
        final DataSource delegate;final AtomicInteger activeConnections=new AtomicInteger(),liveReaders=new AtomicInteger(),maximumReaders=new AtomicInteger();
        final Set<PreparedStatement> active=ConcurrentHashMap.newKeySet();final List<Map<String,Object>> errors=new CopyOnWriteArrayList<>();
        final Set<PreparedStatement> cancellationAttempted=ConcurrentHashMap.newKeySet();
        final List<Map<String,Object>> cancellationErrors=new CopyOnWriteArrayList<>();
        final AtomicInteger cancellationCalls=new AtomicInteger();BiFunction<Sample,String,String> sqlTransform=(sample,sql)->sql;
        TracingSource(DataSource delegate){this.delegate=delegate;}
        @Override public Connection getConnection()throws SQLException{long start=System.nanoTime();var connection=delegate.getConnection();return wrap(connection,System.nanoTime()-start);}
        @Override public Connection getConnection(String user,String password)throws SQLException{long start=System.nanoTime();var connection=delegate.getConnection(user,password);return wrap(connection,System.nanoTime()-start);}
        Connection wrap(Connection connection,long acquire)throws SQLException {
            var sample=current.get();long connectionId;
            try(var s=connection.createStatement();var row=s.executeQuery("SELECT CONNECTION_ID()")){row.next();connectionId=row.getLong(1);}
            if(sample!=null){sample.acquireNanos+=acquire;sample.connectionId=connectionId;}
            activeConnections.incrementAndGet();boolean[] closed={false};
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close")&&!closed[0]){closed[0]=true;activeConnections.decrementAndGet();if(sample!=null&&sample.connection!=null)liveReaders.decrementAndGet();}
                if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql){
                    var ctx=current.get();String phase=ctx==null?"SETUP":ctx.stage;
                    if(ctx!=null) {
                        var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(this);
                        if(ctx.connection==null){ctx.connection=(Connection)proxy;ctx.holder=holder;ctx.deadline=holder.getDeadline().getTime();int live=liveReaders.incrementAndGet();maximumReaders.accumulateAndGet(live,Math::max);}
                        ctx.snapshotValid&=ctx.connection==proxy&&ctx.holder==holder&&ctx.deadline==holder.getDeadline().getTime()&&holder.hasTimeout()
                            &&TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                            &&connection.isReadOnly()&&!connection.getAutoCommit()&&connection.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ;
                        if(ctx.firstQuery&&phase.equals("WORKSPACE")){ctx.firstQuery=false;if(ctx.barrier!=null)ctx.barrier.await(10,TimeUnit.SECONDS);}
                        if(ctx.cancelRequested||System.nanoTime()>=ctx.phaseDeadlineNanos)throw new PhaseDeadlineReached();
                    }
                    String executedSql=sqlTransform.apply(ctx,sql);
                    var actual=args.clone();actual[0]=ctx==null?executedSql:"/*fsf-load:"+runId+":"+ctx.id+":"+phase+"*/ "+executedSql;
                    var statement=(PreparedStatement)invoke(connection,method,actual);var params=new TreeMap<Integer,Object>();
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                        String name=m.getName();if(name.startsWith("set")&&a!=null&&a.length>1&&a[0] instanceof Integer index)params.put(index,a[1]);
                        if(name.equals("executeQuery")||name.equals("executeUpdate")) {
                            if(ctx!=null&&(ctx.cancelRequested||System.nanoTime()>=ctx.phaseDeadlineNanos))throw new PhaseDeadlineReached();
                            var trace=new StatementTrace(operation(sql),phase,executedSql,new ArrayList<>(params.values()));trace.timeoutMillis=mysqlMillis(statement);long begin=System.nanoTime();active.add(statement);
                            if(ctx!=null)ctx.statements.add(trace);if(Set.of("SEED","CLAIM","FACT_AGGREGATE","UNRESOLVED","DIRTY_LOOKUP").contains(trace.operation))planInputs.putIfAbsent(trace.operation,trace);
                            try {
                                Object result=invoke(statement,m,a);
                                if(result instanceof ResultSet rows)return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)->{
                                    Object value=invoke(rows,rm,ra);if(rm.getName().equals("next")&&Boolean.TRUE.equals(value))trace.rows++;
                                    if(rm.getName().equals("close")){trace.nanos=System.nanoTime()-begin;active.remove(statement);}return value;
                                });
                                trace.nanos=System.nanoTime()-begin;active.remove(statement);return result;
                            }catch(Throwable failure){active.remove(statement);trace.nanos=System.nanoTime()-begin;SQLException error=findSql(failure);
                                if(error!=null)errors.add(Map.of("stage",phase,"operation",trace.operation,"sqlState",String.valueOf(error.getSQLState()),"vendorCode",error.getErrorCode(),"driverException",error.getClass().getSimpleName()));throw failure;}
                        }
                        return invoke(statement,m,a);
                    });
                }
                return invoke(connection,method,args);
            });
        }
        // isClosed/getters can wait for Connector/J's executing-query lock. cancel() is its
        // asynchronous path; deduplicate by statement instead of checking closed state first.
        void cancelAll(){for(var statement:active)try{if(cancellationAttempted.add(statement)){cancellationCalls.incrementAndGet();statement.cancel();}}
            catch(SQLException|RuntimeException failure){var details=new LinkedHashMap<String,Object>();details.put("exception",failure.getClass().getSimpleName());
                SQLException error=findSql(failure);if(error!=null){details.put("sqlState",String.valueOf(error.getSQLState()));details.put("vendorCode",error.getErrorCode());}
                cancellationErrors.add(details);}}
    }
    static Object invoke(Object target,java.lang.reflect.Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static long mysqlMillis(PreparedStatement statement)throws SQLException {
        try {Class type=Class.forName("com.mysql.cj.jdbc.JdbcStatement");Object query=type.getMethod("getQuery").invoke(statement.unwrap(type));
            return ((Number)Class.forName("com.mysql.cj.Query").getMethod("getTimeoutInMillis").invoke(query)).longValue();}
        catch(ReflectiveOperationException failure){throw new SQLException("Cannot inspect the isolated query timeout",failure);}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static boolean mysqlExecuting(PreparedStatement statement){
        try {Class type=Class.forName("com.mysql.cj.jdbc.JdbcStatement");Object query=type.getMethod("getQuery").invoke(statement.unwrap(type));
            return ((java.util.concurrent.atomic.AtomicBoolean)Class.forName("com.mysql.cj.Query").getMethod("getStatementExecuting").invoke(query)).get();}
        catch(ReflectiveOperationException|SQLException failure){throw new IllegalStateException(failure);}
    }
}
