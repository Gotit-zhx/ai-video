package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.ops.MaintenanceGate;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Correctness-only date experiment; the production true/false routes remain unchanged. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceFirstSettlementDateBulkIntegrationTest extends FinanceFirstSettlementTestSupport {
    static final Scope COMPANY=new Scope("date-bulk-company","date-bulk-user");
    static final Scope FOREIGN=new Scope("date-bulk-foreign","foreign-user");
    static final LocalDate OCT=LocalDate.of(2026,10,1);
    static final Filter MONTH=window(OCT,OCT.plusMonths(1).minusDays(1));
    static final String COLUMNS="id,organization_id,shop_id,order_id,record_date,platform,currency,status";
    static final CandidateBudget BUDGET=new CandidateBudget(500,10000,2000);
    static final SelectiveFixture SELECTIVE_1K=new SelectiveFixture(1000,10,505,200,200);
    static final SelectiveFixture SELECTIVE_10K=new SelectiveFixture(10000,100,5050,400,400);
    final List<Map<String,Object>> evidence=new CopyOnWriteArrayList<>();
    final List<ExecutorService> executors=new ArrayList<>();
    final ThreadLocal<Attempt> current=new ThreadLocal<>();
    final List<Attempt> attempts=new CopyOnWriteArrayList<>();
    final ThreadLocal<SnapshotWriter> writing=new ThreadLocal<>();
    final List<SnapshotWriter> writers=new CopyOnWriteArrayList<>();
    final ThreadLocal<PathTiming> pathTiming=new ThreadLocal<>();
    final List<PairSample> pairs=new CopyOnWriteArrayList<>();
    final List<FutureTask<?>> readerFutures=new CopyOnWriteArrayList<>();
    final Map<String,Object> selective=new LinkedHashMap<>();
    final List<PlanPair> planPairs=new CopyOnWriteArrayList<>();
    final Map<String,Object> plan=new LinkedHashMap<>();
    final AtomicInteger orderIndexHintRelations=new AtomicInteger();
    final Set<String> orderIndexHintSqlHashes=ConcurrentHashMap.newKeySet();
    boolean orderIndexHintEnabled;
    SelectiveFixture selectiveFixture=SELECTIVE_1K;
    List<Integer> selectiveExpectedOwnerOrder=List.of();
    int expectedSelectiveUnresolved;
    int expectedPendingBeforePair=-1;
    int explainStatements;
    long hardDeadline,phaseDeadline;
    int phaseAttempts;
    Path gateDirectory;
    TraceSource source;
    FinanceRepository reader;
    JdbcTemplate reads;
    int genuineDate;

    @Test @Timeout(value=540,unit=TimeUnit.SECONDS)
    void selectedDateBulkProfileClosesSourceCoverageAndExactSettledRows() throws Exception {
        String selected=System.getenv().getOrDefault("FINANCE_FIRST_SETTLEMENT_DATE_BULK_PROFILE","closure");
        hardDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(540);
        source=new TraceSource(dataSource);
        reads=new JdbcTemplate(source);reader=new FinanceRepository(reads,new ObjectMapper());
        Throwable primary=null;
        Map<String,Object> cleanup=new LinkedHashMap<>();
        try {
            switch(selected) {
                case "closure","closure-order-index" -> {
                    orderIndexHintEnabled=selected.equals("closure-order-index");
                    coverageCannotTrustADeletedFact();
                    strongGatesRequireTheWholeInitializationProtocol();
                    unresolvedIsGlobalAndResolvedBeforeDates();
                    manyNoFirstOwnersDoNotConsumeTheRepairCap();
                    noFirstBecomesPendingOnlyInTheNextSnapshot();
                    deleteAndReappearanceUseCurrentHistory();
                    eligibilityAndVersionTransitionsAreNotPruned();
                    sqlIdentityAndCurrentBytesArePreserved();
                    filtersKeepCurrentProvenanceAndSourceMultiplicity();
                    falseFirstDayWitnessPreservesEveryCurrency();
                    invalidSnapshotsAndSqlFailuresNeverBecomeFallback();
                    assertTrue(genuineDate>0,"An all-native run cannot close the date experiment");
                    if(orderIndexHintEnabled)assertTrue(orderIndexHintRelations.get()>0,"HINTED closure must execute candidate relations with the order index");
                }
                case "snapshot","snapshot-order-index" -> {
                    orderIndexHintEnabled=selected.equals("snapshot-order-index");
                    snapshotAddsAndTransfersBeforeCoverage();
                    snapshotKeepsEnumeratedDirtyAfterPublication();
                    snapshotKeepsCleanBeforeDateAndDeleteChanges();
                    snapshotKeepsCopiedFieldsAndBytes();
                    snapshotKeepsInitializationAndRegistrationBoundaries();
                    snapshotExcludesLowIdLateCommit();
                    assertTrue(genuineDate>0,"Snapshot must exercise genuine DATE_TEST");
                    if(orderIndexHintEnabled)assertTrue(orderIndexHintRelations.get()>0,"HINTED snapshot must execute candidate relations with the order index");
                }
                case "selective-clean-1pct" -> selectiveCleanOnePercent();
                case "selective-clean-1pct-order-index" -> selectiveCleanOnePercentOrderIndex();
                case "selective-clean-1pct-order-index-smoke" -> selectiveCleanOnePercentOrderIndexSmoke();
                case "selective-clean-1pct-order-index-size-10000" -> selectiveCleanOnePercentOrderIndexSize10000();
                case "selective-mixed-1pct-order-index" -> selectiveMixedOnePercentOrderIndex();
                case "plan" -> planCleanOnePercent();
                case "plan-order-index" -> planOrderIndex();
                case "selectivity","selective-mixed-1pct","selective-clean-10pct",
                     "all-month-control","continuous" -> fail("NOT_IMPLEMENTED: "+selected);
                default -> fail("Unknown date-bulk profile: "+selected);
            }
        } catch(Throwable failure) {primary=failure;throw failure;}
        finally {
            try { cleanupOwned(cleanup); }
            catch(Throwable failure) {cleanup.put("failure",failure.getClass().getSimpleName());if(primary!=null)primary.addSuppressed(failure);else {primary=failure;throw failure;}}
            finally {
                var receipt=new LinkedHashMap<String,Object>();
                receipt.put("profile",selected);receipt.put("result",primary==null?"passed":"failed");
                receipt.put("failure",primary==null?"":primary.getClass().getSimpleName());
                receipt.put("candidateMillis",500);receipt.put("nativeReserveMillis",10000);
                receipt.put("completionReserveMillis",2000);receipt.put("hardSeconds",540);
                receipt.put("phaseSeconds",120);receipt.put("phaseAttempts",200);receipt.put("rrSeconds",30);
                receipt.put("defaultCap",64);receipt.put("genuineDate",genuineDate);
                receipt.put("evidence",evidence);receipt.put("attempts",attempts.stream().map(Attempt::receipt).toList());
                receipt.put("writers",writers.stream().map(SnapshotWriter::receipt).toList());
                receipt.put("pairs",pairs.stream().map(PairSample::receipt).toList());receipt.put("selective",selective);
                receipt.put("planPairs",planPairs.stream().map(PlanPair::receipt).toList());receipt.put("plan",plan);
                receipt.put("orderIndexExperiment",Map.of("enabled",orderIndexHintEnabled,"hintedCandidateRelations",orderIndexHintRelations.get(),
                    "hintedSqlHashes",new TreeSet<>(orderIndexHintSqlHashes)));
                receipt.put("cleanup",cleanup);receipt.put("productionAdmission",false);
                var directory=Path.of("/tmp/finance-bi-performance-qa/first-settlement-date-bulk");
                Files.createDirectories(directory);
                String prefix=Set.of("closure","snapshot","selective-clean-1pct","plan","plan-order-index",
                    "selective-clean-1pct-order-index","selective-clean-1pct-order-index-smoke","selective-clean-1pct-order-index-size-10000",
                    "selective-mixed-1pct-order-index",
                    "closure-order-index","snapshot-order-index").contains(selected)?selected:"closure";
                var path=directory.resolve(prefix+"-"+UUID.randomUUID()+".json");
                Files.writeString(path,repo.write(receipt)+"\n",StandardOpenOption.CREATE_NEW);
                System.out.println("DATE_BULK_RECEIPT "+path);
            }
        }
    }

    enum DateRoute { DATE_TEST,NATIVE }
    record Relation(String sql,List<Object> args) {}
    record DateResolution(DateRoute route,String reason,Relation relation,Budget budget,int unresolved) {}
    record Candidate(String id,String organization,String shop,String order,LocalDate date,String platform,String currency,String status) {}
    record Settled(List<Map<String,Object>> bag,long count,List<List<Map<String,Object>>> pages) {}
    record Data(List<Map<String,Object>> identities,Map<String,Settled> settled) {}
    record Probe(DateResolution resolution,Data data,Attempt attempt) {}
    record SelectiveFixture(int owners,int octoberOwners,int novemberStart,int maxInitializationBatches,int maxWorkerTicks) {
        SelectiveFixture {
            if(!((owners==1000&&octoberOwners==10&&novemberStart==505)||(owners==10000&&octoberOwners==100&&novemberStart==5050)))
                throw new IllegalArgumentException("Only the frozen 1k and 10k selective fixtures are supported");
        }
        int septemberOwners(){return novemberStart-octoberOwners;}
        int novemberOwners(){return owners-novemberStart;}
    }
    record SettledRelations(String nativeSql,List<Object> nativeArgs,String candidateSql,List<Object> candidateArgs) {}
    record SqlShape(String name,String sql,List<Object> args) {
        Map<String,Object> receipt(){return Map.of("name",name,"sql",sql,"args",args,"sqlHash",hash(sql),"argCount",args.size());}
    }
    static final class PlanCapture {
        Guard guard;
        SqlShape audit,unresolved,dateForced,dateNatural;
        List<Owner> auditRows=List.of(),unresolvedRows=List.of();
        Relation relation;
    }
    static final class Options {
        boolean enabled=true;
        String grantOrganization;
        int registration=1;
        LongSupplier clock=System::nanoTime;
        Runnable afterSnapshot=()->{},beforeProof=()->{},beforeAudit=()->{};
        Runnable afterUnresolved=()->{};
        Runnable afterIdentityRead;
        String auditControl="";
        PlanCapture planCapture;
        Options(String organization){grantOrganization=organization;}
    }
    static final class LocalExit extends RuntimeException {}
    static final class Attempt {
        final String name;
        final long started=System.nanoTime();
        Connection connection;
        ConnectionHolder holder;
        long connectionId,deadline;
        boolean sameSnapshot=true,completed;
        int borrows,auditRows=-1,unresolved=-1,lookups,newTableReads,nativeData,oracleData,returnedData,hintedCandidateRelations;
        int pendingBranch=-1,oldBranch=-1,combined=-1,externalDomainReads,dateStatements;
        int proofRegistration=-1;
        long guardGeneration=-1;
        Boolean guardSeed,guardShops,guardRequeue;
        String route="",reason="",failure="",stage="SOURCE";
        final List<Map<String,Object>> errors=new ArrayList<>();
        Attempt(String name){this.name=name;}
        Map<String,Object> receipt(){
            var m=new LinkedHashMap<String,Object>();
            m.put("case",name);m.put("completed",completed);m.put("route",route);m.put("reason",reason);
            m.put("connectionId",connectionId);m.put("borrows",borrows);m.put("samePhysicalSnapshot",sameSnapshot);
            m.put("holderDeadlineMillis",deadline);m.put("auditRows",auditRows);m.put("unresolved",unresolved);
            m.put("pendingBranch",pendingBranch);m.put("oldBranch",oldBranch);m.put("combined",combined);
            m.put("lookups",lookups);m.put("newTableReads",newTableReads);m.put("nativeData",nativeData);m.put("externalDomainReads",externalDomainReads);
            m.put("hintedCandidateRelations",hintedCandidateRelations);
            m.put("actualDateStatements",dateStatements);
            m.put("proofRegistration",proofRegistration);m.put("guardGeneration",guardGeneration);
            m.put("guardSeed",guardSeed);m.put("guardShops",guardShops);m.put("guardRequeue",guardRequeue);
            m.put("oracleData",oracleData);m.put("returnedData",returnedData);m.put("failure",failure);
            m.put("sqlErrors",errors);m.put("elapsedMillis",(System.nanoTime()-started)/1_000_000);
            return m;
        }
    }
    static final class PlanOutput {
        final String shape,format,sql,sqlHash;
        final List<Object> args;
        long startedNanos,endedNanos;
        int originalChars,storedChars;
        boolean completed,truncated;
        String output="",outputHash="",failure="";
        PlanOutput(String shape,String format,SqlShape sql) {
            this.shape=shape;this.format=format;this.sql=sql.sql();this.sqlHash=hash(sql.sql());args=sql.args();
        }
        Map<String,Object> receipt() {
            var r=new LinkedHashMap<String,Object>();r.put("shape",shape);r.put("format",format);r.put("sql",sql);r.put("sqlHash",sqlHash);
            r.put("args",args);r.put("argCount",args.size());r.put("startedNanos",startedNanos);r.put("endedNanos",endedNanos);
            r.put("elapsedNanos",startedNanos==0||endedNanos==0?0:endedNanos-startedNanos);r.put("completed",completed);
            r.put("originalChars",originalChars);r.put("storedChars",storedChars);r.put("truncated",truncated);
            r.put("output",output);r.put("outputHash",outputHash);r.put("failure",failure);return r;
        }
    }
    static final class PlanPair {
        final String name;
        final Attempt attempt;
        final PathTiming resolutionTiming=new PathTiming("PLAN_RESOLUTION");
        final List<PlanOutput> outputs=new ArrayList<>();
        final List<Map<String,Object>> ordinaryReads=new ArrayList<>();
        final Map<String,Object> verification=new LinkedHashMap<>();
        long rrStartedNanos,rrEndedNanos,establishSnapshotNanos,validationNanos;
        int explainsBefore,explainsAfter;
        boolean completed;
        String failure="";
        PlanPair(String name,Attempt attempt){this.name=name;this.attempt=attempt;}
        Map<String,Object> receipt() {
            var r=new LinkedHashMap<String,Object>();r.put("pair",name);r.put("completed",completed);r.put("failure",failure);
            r.put("rrStartedNanos",rrStartedNanos);r.put("rrEndedNanos",rrEndedNanos);
            r.put("rrEnvelopeNanos",rrStartedNanos==0||rrEndedNanos==0?0:rrEndedNanos-rrStartedNanos);
            r.put("establishSnapshotNanos",establishSnapshotNanos);r.put("validationNanos",validationNanos);
            r.put("explainsBefore",explainsBefore);r.put("explainsAfter",explainsAfter);r.put("explainStatements",explainsAfter-explainsBefore);
            r.put("resolution",resolutionTiming.receipt());r.put("verification",verification);
            r.put("ordinaryReads",ordinaryReads);
            r.put("outputs",outputs.stream().map(PlanOutput::receipt).toList());r.put("physicalSnapshot",attempt.receipt());return r;
        }
    }

    /** All candidate work shares the original holder and its deadline; only LocalExit is recoverable. */
    final class Budget {
        final Options options;
        final long localDeadline;
        Budget(Options options) {
            this.options=options;
            requireUsable();
            long room=holder().getTimeToLiveInMillis()-BUDGET.nativeReserveMillis()-BUDGET.completionReserveMillis();
            localDeadline=options.clock.getAsLong()+Math.max(0,Math.min(BUDGET.candidateMillis(),room))*1_000_000;
        }
        void requireUsable(){FinanceLabelFirstSettlementFacts.requireUsable(reader);}
        long remaining(){
            requireUsable();
            return Math.min((localDeadline-options.clock.getAsLong())/1_000_000,
                holder().getTimeToLiveInMillis()-BUDGET.nativeReserveMillis()-BUDGET.completionReserveMillis());
        }
        void check(){if(remaining()<1)throw new LocalExit();}
        @SuppressWarnings({"rawtypes","unchecked"})
        void bound(PreparedStatement statement)throws SQLException {
            check();
            try {
                Class type=Class.forName("com.mysql.cj.jdbc.JdbcStatement");
                Object query=type.getMethod("getQuery").invoke(statement.unwrap(type));
                Class queryType=Class.forName("com.mysql.cj.Query");
                long existing=((Number)queryType.getMethod("getTimeoutInMillis").invoke(query)).longValue();
                long millis=remaining();if(existing>0)millis=Math.min(millis,existing);
                if(millis<1)throw new LocalExit();
                queryType.getMethod("setTimeoutInMillis",long.class).invoke(query,millis);
                assertTrue(FinanceFirstSettlementLoadIntegrationTest.mysqlMillis(statement)<=BUDGET.candidateMillis());
            }catch(ReflectiveOperationException failure){throw new SQLException("Cannot bound isolated MySQL SELECT",failure);}
        }
    }
    ConnectionHolder holder(){return (ConnectionHolder)TransactionSynchronizationManager.getResource(source);}
    static boolean supported(Registration p) {
        return p.enabled()&&p.schemaVersion()==SUPPORTED_SCHEMA_VERSION&&p.registrationVersion()>0
            &&SUPPORTED_DEFINITION_SHA.equals(p.definitionSha())&&p.boundary()!=null&&!p.boundary().isBlank();
    }
    static String clean(String a,Guard g) {
        return a+".applied_revision="+a+".requested_revision AND "+a+".applied_revision>0 AND "+
            a+".schema_version="+g.targetSchemaVersion()+" AND "+a+".applied_generation="+g.generation()+
            " AND "+a+".applied_registration_version="+g.registrationVersion();
    }
    DateResolution nativeResolution(String reason,int unresolved) {
        FinanceLabelFirstSettlementFacts.requireUsable(reader);
        return new DateResolution(DateRoute.NATIVE,reason,new Relation("SELECT "+prefixed("n")+
            " FROM finance_records n WHERE "+FinanceCostReport.firstSettlementIdentity("n",true),List.of()),null,unresolved);
    }
    SqlShape cleanDateShape(Guard guard,Scope scope,Filter filter,List<Candidate> resolved,boolean forceIndex) {
        StringBuilder b=new StringBuilder("SELECT f.first_record_id id,f.organization_id,f.shop_id,f.order_id "+
            "FROM finance_first_settlement_facts f "+(forceIndex?"FORCE INDEX(idx_fsf_date) ":"")+"WHERE f.organization_id=? AND f.has_settlement=TRUE AND "+
            clean("f",guard)+" AND f.first_record_date>=? AND f.first_record_date<=?");
        var args=new ArrayList<Object>(List.of(scope.organizationId(),java.sql.Date.valueOf(filter.start()),java.sql.Date.valueOf(filter.end())));
        for(var row:resolved){b.append(" UNION ALL SELECT ?,?,?,?");Collections.addAll(args,row.id(),row.organization(),row.shop(),row.order());}
        return new SqlShape(forceIndex?"clean-date-forced":"clean-date-natural",b.toString(),List.copyOf(args));
    }
    Relation exactRelation(SqlShape date) {
        String exact="SELECT "+prefixed("current_r")+" FROM ("+date.sql()+") b JOIN finance_records current_r "+
            "ON current_r.id=b.id AND current_r.organization_id=b.organization_id "+
            "AND current_r.shop_id=b.shop_id AND current_r.order_id=b.order_id";
        return new Relation(exact,date.args());
    }
    DateResolution resolve(Scope scope,Filter filter,int cap,Options options) {
        if(cap<0||cap>64)throw new IllegalArgumentException("Closure cap must be 0..64");
        if(!options.enabled)return nativeResolution("OFF",-1);
        if(!options.grantOrganization.equals(scope.organizationId())||filter.start().isBefore(OCT.minusMonths(1))
            ||filter.end().isAfter(OCT.plusMonths(2).minusDays(1)))return nativeResolution("UNGRANTED",-1);
        var budget=new Budget(options);
        try {
            options.beforeProof.run();budget.check();
            var registrations=select(budget,"SELECT schema_version,registration_version,definition_sha,installation_boundary,enabled "+
                "FROM finance_first_settlement_registration WHERE id=1",
                (r,n)->new Registration(r.getInt(1),r.getInt(2),r.getString(3),r.getString(4),r.getBoolean(5)));
            if(registrations.isEmpty())return nativeResolution("REGISTRATION",-1);
            var proof=registrations.getFirst();
            current.get().proofRegistration=proof.registrationVersion();
            if(!supported(proof)||proof.registrationVersion()!=options.registration)return nativeResolution("REGISTRATION",-1);
            var guards=select(budget,"""
                SELECT organization_id,generation,target_schema_version,registration_version,
                  registration_definition_sha,registration_boundary,seed_complete,shops_complete,requeue_complete
                FROM finance_first_settlement_initialization WHERE organization_id=?
                """,(r,n)->{
                    var a=current.get();a.guardGeneration=r.getLong(2);a.guardSeed=r.getBoolean(7);a.guardShops=r.getBoolean(8);a.guardRequeue=r.getBoolean(9);
                    return r.getLong(2)>0&&r.getInt(3)==proof.schemaVersion()&&r.getInt(4)==proof.registrationVersion()
                    &&proof.definitionSha().equals(r.getString(5))&&proof.boundary().equals(r.getString(6))
                    &&r.getBoolean(7)&&r.getBoolean(8)&&r.getBoolean(9)
                    ?new Guard(r.getString(1),r.getLong(2),r.getInt(3),r.getInt(4),true):null;},scope.organizationId());
            if(guards.isEmpty()||guards.getFirst()==null)return nativeResolution("CLOSURE_GATE",-1);
            var guard=guards.getFirst();options.beforeAudit.run();budget.check();
            if(options.planCapture!=null)options.planCapture.guard=guard;
            // This is full-company/full-history E\F. Request dates, permissions and currency do not narrow it.
            String auditSql="""
                SELECT DISTINCT r.organization_id,r.shop_id,r.order_id
                FROM finance_records r LEFT JOIN finance_first_settlement_facts f
                  ON f.organization_id=r.organization_id AND f.shop_id=r.shop_id AND f.order_id=r.order_id
                WHERE r.organization_id=? AND
                """+eligible("r")+" AND f.organization_id IS NULL LIMIT 1";
            // Fault controls execute real failing MySQL SQL at this audit boundary; errors cannot establish E subset F.
            if(!options.auditControl.isEmpty())select(budget,options.auditControl,(r,n)->r.getObject(1));
            var missing=select(budget,auditSql,(r,n)->new Owner(r.getString(1),r.getString(2),r.getString(3)),scope.organizationId());
            if(options.planCapture!=null){options.planCapture.audit=new SqlShape("eligible-missing-audit",auditSql,List.of(scope.organizationId()));options.planCapture.auditRows=List.copyOf(missing);}
            current.get().auditRows=missing.size();
            if(!missing.isEmpty())return nativeResolution("COVERAGE_BROKEN",-1);
            String pending="""
                SELECT f.organization_id,f.shop_id,f.order_id FROM finance_first_settlement_facts f FORCE INDEX(idx_fsf_pending)
                WHERE f.organization_id=? AND f.pending=TRUE LIMIT ?
                """;
            String old="SELECT f.organization_id,f.shop_id,f.order_id FROM finance_first_settlement_facts f "+
                "WHERE f.organization_id=? AND NOT ("+clean("f",guard)+") LIMIT ?";
            RowMapper<Owner> owner=(r,n)->new Owner(r.getString(1),r.getString(2),r.getString(3));
            // Individually bounded branches and the final UNION use actual MySQL owner collation.
            var p=select(budget,pending,owner,scope.organizationId(),cap+1);
            var o=select(budget,old,owner,scope.organizationId(),cap+1);
            String unresolvedSql="SELECT organization_id,shop_id,order_id FROM (("+pending+") UNION DISTINCT ("+old+")) unresolved LIMIT ?";
            var unresolvedArgs=List.<Object>of(scope.organizationId(),cap+1,scope.organizationId(),cap+1,cap+1);
            var u=select(budget,unresolvedSql,owner,unresolvedArgs.toArray());
            if(options.planCapture!=null){options.planCapture.unresolved=new SqlShape("pending-not-clean-union",unresolvedSql,unresolvedArgs);options.planCapture.unresolvedRows=List.copyOf(u);}
            var attempt=current.get();attempt.pendingBranch=p.size();attempt.oldBranch=o.size();
            attempt.combined=u.size();attempt.unresolved=u.size();
            if(u.size()>cap)return nativeResolution("OWNER_CAP",u.size());
            options.afterUnresolved.run();budget.check();
            var resolved=new ArrayList<Candidate>();
            for(var key:u) {
                attempt.lookups++;
                resolved.addAll(select(budget,"SELECT "+prefixed("r")+" FROM finance_records r FORCE INDEX(idx_finance_first_settlement) "+
                    "WHERE r.organization_id=? AND r.shop_id=? AND r.order_id=? AND "+eligible("r")+
                    " ORDER BY r.record_date,r.id LIMIT 1",candidateMapper(),key.organizationId(),key.shopId(),key.orderId()));
            }
            var timing=pathTiming.get();long buildStarted=timing==null?0:System.nanoTime();
            var forced=cleanDateShape(guard,scope,filter,resolved,true);
            var relation=exactRelation(forced);
            // Copy fields are not assumed byte-fresh: all eight fields come from current_r in S.
            if(options.planCapture!=null){options.planCapture.dateForced=forced;options.planCapture.dateNatural=cleanDateShape(guard,scope,filter,resolved,false);options.planCapture.relation=relation;}
            if(timing!=null)timing.relationBuildNanos+=System.nanoTime()-buildStarted;
            budget.check();
            return new DateResolution(DateRoute.DATE_TEST,"",relation,budget,u.size());
        }catch(LocalExit proactive){return nativeResolution("LOCAL_BUDGET",current.get().unresolved);}
    }
    static RowMapper<Candidate> candidateMapper(){return (r,n)->new Candidate(r.getString(1),r.getString(2),r.getString(3),
        r.getString(4),r.getDate(5).toLocalDate(),r.getString(6),r.getString(7),r.getString(8));}
    <T> List<T> select(Budget budget,String sql,RowMapper<T> mapper,Object...args) {
        var timing=pathTiming.get();String operation=timing==null?"":timedOperation(sql);
        long started=timing==null?0:System.nanoTime();
        try {
        checkTime();if(budget!=null)budget.check();
        return reads.execute(connection->{
            var statement=connection.prepareStatement(sql);
            for(int n=0;n<args.length;n++)StatementCreatorUtils.setParameterValue(statement,n+1,SqlTypeValue.TYPE_UNKNOWN,args[n]);
            return statement;
        },(PreparedStatementCallback<List<T>>)statement->{
            if(budget!=null)budget.bound(statement);
            if(timing!=null)timing.executed.merge(operation,1,Integer::sum);
            try(var rows=statement.executeQuery()){
                var result=new ArrayList<T>();for(int n=0;rows.next();n++)result.add(mapper.mapRow(rows,n));return result;
            }
        });
        }finally{if(timing!=null)timing.nanos.merge(operation,System.nanoTime()-started,Long::sum);}
    }
    List<Map<String,Object>> rows(Budget budget,String sql,List<?> args){
        return select(budget,sql,new org.springframework.jdbc.core.ColumnMapRowMapper(),args.toArray());
    }
    Probe probe(String name,Filter filter,int cap,DateRoute route,String reason,List<String> expected) {
        return probe(name,COMPANY,filter,cap,new Options(COMPANY.organizationId()),route,reason,expected,"");
    }
    Probe probe(String name,Scope scope,Filter filter,int cap,Options options,DateRoute route,String reason,List<String> expected,String numbers) {
        checkPhase();var a=new Attempt(name);attempts.add(a);current.set(a);
        try {
            var result=reader.readSnapshot(30,()->{
                // A real source SELECT establishes S before controls or writer B are admitted.
                reads.queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=?",Long.class,scope.organizationId());
                options.afterSnapshot.run();a.stage="CANDIDATE";
                var resolution=resolve(scope,filter,cap,options);
                Data actual;
                try {actual=data(scope,filter,resolution.relation(),resolution.budget(),numbers,false,options.afterIdentityRead);}
                catch(LocalExit expired){resolution=nativeResolution("LOCAL_BUDGET",resolution.unresolved());actual=data(scope,filter,resolution.relation(),null,numbers,false);}
                a.route=resolution.route().name();a.reason=resolution.reason();
                assertEquals(route,resolution.route(),name);assertEquals(reason,resolution.reason(),name);
                assertEquals(expected,ids(actual.identities()),name+" literal ordered source identities");
                a.stage="ORACLE";
                var oracle=originalData(scope,filter,numbers);
                assertEquals(oracle.identities(),actual.identities(),name+" full eight-column and HEX bag");
                assertEquals(oracle.settled(),actual.settled(),name+" original SETTLED bags/count/pages");
                FinanceLabelFirstSettlementFacts.requireUsable(reader);
                assertTrue(a.sameSnapshot);assertEquals(1,a.borrows);
                if(resolution.route()==DateRoute.DATE_TEST)assertTrue(a.dateStatements>0,"DATE_TEST must execute its real date branch");
                return new Probe(resolution,actual,a);
            });
            a.returnedData++;a.completed=true;
            if(result.resolution().route()==DateRoute.DATE_TEST)genuineDate++;
            var saved=new LinkedHashMap<String,Object>();
            saved.put("case",name);saved.put("identities",expected);saved.put("rowsHash",hash(result.data().identities().toString()));
            saved.put("sourceOracleEqual",true);saved.put("settledOracleEqual",true);
            saved.put("counts",result.data().settled().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().count())));
            saved.put("orderedPagesEqual",true);saved.put("hexEqual",true);evidence.add(saved);
            return result;
        }catch(RuntimeException|Error failure){a.failure=failure.getClass().getSimpleName();throw failure;}
        finally {current.remove();}
    }
    Data data(Scope scope,Filter filter,Relation relation,Budget budget,String numbers,boolean oracle) {
        return data(scope,filter,relation,budget,numbers,oracle,null);
    }
    Data data(Scope scope,Filter filter,Relation relation,Budget budget,String numbers,boolean oracle,Runnable afterIdentityRead) {
        var a=current.get();if(oracle)a.oracleData++;else if(budget==null)a.nativeData++;
        var q=reader.query(scope,filter,"r");
        String identitySql="SELECT "+exactProjection("r")+" FROM ("+relation.sql()+") r WHERE "+q.where()+" ORDER BY r.record_date DESC,r.id";
        var identityArgs=joined(relation.args(),q.args());
        var identities=rows(budget,identitySql,identityArgs);
        if(afterIdentityRead!=null) {
            afterIdentityRead.run();FinanceLabelFirstSettlementFacts.requireUsable(reader);
            var repeated=rows(budget,identitySql,identityArgs);
            assertEquals(identities,repeated,"Identity/rExact must remain in the original S after B commits");
            identities=repeated;
        }
        var settled=new LinkedHashMap<String,Settled>();
        for(String kind:List.of("ORDER","REFUND"))for(boolean normal:List.of(false,true)) {
            var selected=FinanceRecordSearch.apply(q,"o",numbers,false);
            String sql=candidateSettledRelation(relation,selected,kind,normal,orderIndexHintEnabled);
            settled.put(kind+":"+normal,settled(sql,joined(relation.args(),selected.args()),budget));
        }
        return new Data(identities,settled);
    }
    String candidateSettledRelation(Relation relation,FinanceRepository.Query selected,String kind,boolean normal,boolean hinted) {
        String sql=" FROM ("+relation.sql()+") r JOIN finance_records o "+(hinted?"FORCE INDEX(idx_finance_settled_order) ":"")+"ON o.organization_id=r.organization_id "+
            "AND o.shop_id=r.shop_id AND o.order_id=r.order_id AND o.kind='"+kind+"' AND o.source='API' WHERE "+
            selected.where()+(normal?" AND "+FinanceOrderCharges.normalOrder("r"):"");
        if(hinted) {
            orderIndexHintRelations.incrementAndGet();orderIndexHintSqlHashes.add(hash(sql));
            var attempt=current.get();if(attempt!=null)attempt.hintedCandidateRelations++;
        }
        return sql;
    }
    Data originalData(Scope scope,Filter filter,String numbers) {
        current.get().oracleData++;
        var q=reader.query(scope,filter,"r");
        // Independent full-source oracle: no new eligible/clean/relation builder.
        var identities=rows(null,"SELECT "+exactProjection("r")+" FROM finance_records r WHERE "+
            FinanceCostReport.firstSettlementIdentity("r",true)+" AND "+q.where()+" ORDER BY r.record_date DESC,r.id",q.args());
        var settled=new LinkedHashMap<String,Settled>();
        for(String kind:List.of("ORDER","REFUND"))for(boolean normal:List.of(false,true)) {
            var selected=FinanceRecordSearch.apply(q,"o",numbers,false);
            // Independently retained Repository relation, including original join order and predicates.
            String sql=reader.settledOrderRelation(scope,filter,kind,normal)+selected.where().substring(q.where().length());
            settled.put(kind+":"+normal,settled(sql,selected.args(),null));
        }
        return new Data(identities,settled);
    }
    Settled settled(String relation,List<?> args,Budget budget) {
        String projection=settledProjection();
        var bag=rows(budget,projection+relation+" ORDER BY r.record_date DESC,o.id",args);
        long count=select(budget,"SELECT COUNT(*)"+relation,(r,n)->r.getLong(1),args.toArray()).getFirst();
        if(pathTiming.get()==null)assertEquals(bag.size(),count);
        var pages=new ArrayList<List<Map<String,Object>>>();
        for(long offset:new long[]{0,1,Math.max(0,count-1),count+1}) {
            var page=rows(budget,projection+relation+" ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?",joined(args,List.of(3,offset)));
            if(pathTiming.get()==null) {
                var expected=bag.subList((int)Math.min(offset,bag.size()),(int)Math.min(offset+3,bag.size()));
                assertEquals(expected,page,"First/cross/last/empty pages must preserve exact source-row ordering");
            }
            pages.add(page);
        }
        return new Settled(bag,count,pages);
    }
    static String settledProjection(){return """
            SELECT r.id winner_id,o.id,o.kind,r.record_date report_date,o.record_date source_date,
              o.status,o.platform,o.currency,o.amount,o.revenue,
              (SELECT JSON_UNQUOTE(JSON_EXTRACT(p.raw_json,'$._finance_reporting_month')) FROM finance_records p WHERE p.id=r.id) reporting_month,
              (SELECT JSON_UNQUOTE(JSON_EXTRACT(p.raw_json,'$._finance_original_settlement_date')) FROM finance_records p WHERE p.id=r.id) original_date
            """;}
    static String prefixed(String alias){return Arrays.stream(COLUMNS.split(",")).map(c->alias+"."+c).collect(java.util.stream.Collectors.joining(","));}
    static String exactProjection(String alias){return prefixed(alias)+","+Arrays.stream(COLUMNS.split(","))
        .map(c->"HEX("+alias+"."+c+") "+c+"_hex").collect(java.util.stream.Collectors.joining(","));}
    static List<Object> joined(List<?> first,List<?> second){var a=new ArrayList<Object>(first);a.addAll(second);return a;}
    static List<String> ids(List<Map<String,Object>> rows){return rows.stream().map(r->text(r.get("id"))).toList();}
    static Filter window(LocalDate start,LocalDate end){return new Filter(start,end,"","","","day","");}
    static Filter chosen(String shop,String platform,String currency,String group){return new Filter(MONTH.start(),MONTH.end(),shop,platform,currency,"day",group);}

    void coverageCannotTrustADeletedFact() throws Exception {
        resetFixture();
        String shop=groupedShop(COMPANY,"date-coverage",null);
        settlement("coverage-winner",shop,"covered",OCT.plusDays(2));addSources(COMPANY,shop,"covered","coverage");
        initializeAndPublish(COMPANY);
        var clean=probe("C3_clean_cap0",MONTH,0,DateRoute.DATE_TEST,"",List.of("coverage-winner"));
        assertEquals(2,clean.data().settled().get("ORDER:false").count());
        assertEquals(3,clean.data().settled().get("REFUND:false").count());
        assertEquals(1,db.update("DELETE FROM finance_first_settlement_facts WHERE organization_id=? AND shop_id=? AND order_id='covered'",COMPANY.organizationId(),shop));
        assertTrue(new FinanceFirstSettlementFacts(repo).guard(COMPANY.organizationId()).orElseThrow().seedComplete());
        assertEquals(List.of(),db.queryForList("SELECT first_record_id FROM finance_first_settlement_facts WHERE organization_id=?",String.class,COMPANY.organizationId()),
            "Actual unsafe facts-only SQL loses the still-eligible winner");
        var before=state();
        var broken=probe("C3_deleted_eligible_fact",MONTH,64,DateRoute.NATIVE,"COVERAGE_BROKEN",List.of("coverage-winner"));
        assertEquals(1,broken.attempt().auditRows);assertEquals(0,broken.attempt().lookups);assertEquals(before,state());
        probe("C3_missing_outside_requested_month",window(OCT.minusMonths(1),OCT.minusDays(1)),64,DateRoute.NATIVE,"COVERAGE_BROKEN",List.of());
        probe("C3_missing_outside_currency",chosen("","","PHP",""),64,DateRoute.NATIVE,"COVERAGE_BROKEN",List.of());
        probe("C3_missing_outside_platform_group",chosen("","TIKTOK_SHOP","","absent-group"),64,DateRoute.NATIVE,"COVERAGE_BROKEN",List.of());
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",shop);
        probe("C3_missing_invisible_owner",MONTH,64,DateRoute.NATIVE,"COVERAGE_BROKEN",List.of());
    }


    void strongGatesRequireTheWholeInitializationProtocol() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-gates",null);
        settlement("gate-winner",shop,"gate",OCT.plusDays(2));addSources(COMPANY,shop,"gate","gate");
        initializeAndPublish(COMPANY);
        var off=new Options(COMPANY.organizationId());off.enabled=false;
        var p=probe("C1_OFF",COMPANY,MONTH,64,off,DateRoute.NATIVE,"OFF",List.of("gate-winner"),"");
        assertEquals(0,p.attempt().newTableReads);
        var denied=new Options("another-company");
        p=probe("C1_unapproved",COMPANY,MONTH,64,denied,DateRoute.NATIVE,"UNGRANTED",List.of("gate-winner"),"");
        assertEquals(0,p.attempt().newTableReads);
        var equivalentScope=new Scope("DATE-BULK-COMPANY","date-bulk-user");
        p=probe("C1_exact_grant_not_SQL_collation",equivalentScope,MONTH,64,new Options(COMPANY.organizationId()),
            DateRoute.NATIVE,"UNGRANTED",List.of("gate-winner"),"");
        assertEquals(0,p.attempt().newTableReads);
        record GateChange(String name,String table,String change,String restore,String reason) {}
        var changes=List.of(
            new GateChange("disabled","registration","enabled=FALSE","enabled=TRUE","REGISTRATION"),
            new GateChange("unsupported_schema","registration","schema_version=2","schema_version=1","REGISTRATION"),
            new GateChange("registration_zero","registration","registration_version=0","registration_version=1","REGISTRATION"),
            new GateChange("hash_mismatch","registration","definition_sha=REPEAT('0',64)","definition_sha='"+SUPPORTED_DEFINITION_SHA+"'","REGISTRATION"),
            new GateChange("blank_boundary","registration","installation_boundary=''","installation_boundary='disposable-runner-install'","REGISTRATION"),
            new GateChange("new_boundary_old_company","registration","installation_boundary='unmatched-boundary'","installation_boundary='disposable-runner-install'","CLOSURE_GATE"),
            new GateChange("company_generation_zero","initialization","generation=0","generation=1","CLOSURE_GATE"),
            new GateChange("company_schema_mismatch","initialization","target_schema_version=2","target_schema_version=1","CLOSURE_GATE"),
            new GateChange("company_registration_mismatch","initialization","registration_version=2","registration_version=1","CLOSURE_GATE"),
            new GateChange("company_hash_mismatch","initialization","registration_definition_sha=REPEAT('0',64)","registration_definition_sha='"+SUPPORTED_DEFINITION_SHA+"'","CLOSURE_GATE"),
            new GateChange("company_boundary_mismatch","initialization","registration_boundary='other'","registration_boundary='disposable-runner-install'","CLOSURE_GATE"),
            new GateChange("seed_false","initialization","seed_complete=FALSE","seed_complete=TRUE","CLOSURE_GATE"),
            new GateChange("seed_true_shops_false","initialization","shops_complete=FALSE","shops_complete=TRUE","CLOSURE_GATE"),
            new GateChange("seed_true_requeue_false","initialization","requeue_complete=FALSE","requeue_complete=TRUE","CLOSURE_GATE"));
        for(var change:changes) {
            String where=change.table().equals("registration")?"id=1":"organization_id='"+COMPANY.organizationId()+"'";
            db.update("UPDATE finance_first_settlement_"+change.table()+" SET "+change.change()+" WHERE "+where);
            var before=state();
            probe("C1_"+change.name(),MONTH,64,DateRoute.NATIVE,change.reason(),List.of("gate-winner"));
            assertEquals(before,state(),"Fallback is read-only and cannot repair its gate");
            db.update("UPDATE finance_first_settlement_"+change.table()+" SET "+change.restore()+" WHERE "+where);
        }
        db.update("DELETE FROM finance_first_settlement_registration");
        var missingProof=state();
        probe("C1_missing_proof",MONTH,64,DateRoute.NATIVE,"REGISTRATION",List.of("gate-winner"));
        assertEquals(missingProof,state());
        db.update("INSERT INTO finance_first_settlement_registration(id,schema_version,registration_version,definition_sha,installation_boundary,enabled) VALUES(1,1,1,?,'disposable-runner-install',TRUE)",SUPPORTED_DEFINITION_SHA);
        db.update("DELETE FROM finance_first_settlement_initialization WHERE organization_id=?",COMPANY.organizationId());
        var missingCompany=state();
        probe("C1_missing_company",MONTH,64,DateRoute.NATIVE,"CLOSURE_GATE",List.of("gate-winner"));
        assertEquals(missingCompany,state());
        initializeAndPublish(COMPANY);
        var init=new FinanceFirstSettlementInitialization(repo);
        var previous=init.maintenanceState(COMPANY.organizationId()).orElseThrow();
        db.update("UPDATE finance_first_settlement_registration SET registration_version=2");
        var options=new Options(COMPANY.organizationId());options.registration=2;
        probe("C1_new_proof_old_company",COMPANY,MONTH,64,options,DateRoute.NATIVE,"CLOSURE_GATE",List.of("gate-winner"),"");
        var target=new FinanceFirstSettlementFacts(repo).registration().orElseThrow();
        assertEquals(AdvanceResult.ADVANCED,init.advance(previous,target));
        assertFalse(new FinanceFirstSettlementFacts(repo).guard(COMPANY.organizationId()).orElseThrow().seedComplete());
        probe("C1_advance_not_closed",COMPANY,MONTH,64,options,DateRoute.NATIVE,"CLOSURE_GATE",List.of("gate-winner"),"");
        initializeAndPublish(COMPANY);
        probe("C1_real_upgrade_closed",COMPANY,MONTH,0,options,DateRoute.DATE_TEST,"",List.of("gate-winner"),"");
    }

    void unresolvedIsGlobalAndResolvedBeforeDates() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-core",null);
        String longId="P".repeat(128)+"L".repeat(127),prefix=longId.substring(0,128);
        String[] orders={"C","S","K","MOut","MIn","O",prefix};
        String[] tags={"core-c","core-s","core-k","core-out","core-in","core-old","core-prefix"};
        for(int n=0;n<orders.length;n++)addSources(COMPANY,shop,orders[n],tags[n]);
        settlement("core-C",shop,"C",OCT.plusDays(2));
        settlement("core-S",shop,"S",OCT.minusDays(11));
        settlement("core-K",shop,"K",OCT.plusDays(1));
        settlement("core-out-old",shop,"MOut",OCT.plusDays(1));
        settlement("core-out-next",shop,"MOut",OCT.plusMonths(1));
        settlement("core-in-old",shop,"MIn",OCT.minusDays(1));
        settlement("core-in-next",shop,"MIn",OCT.plusDays(9));
        settlement("core-O",shop,"O",OCT.plusDays(11));
        settlement("core-P",shop,prefix,OCT.plusDays(14));
        total(shop,longId);
        db.update("DELETE FROM finance_records WHERE id='core-K'");
        initializeAndPublish(COMPANY);
        assertEquals(0,ownerRows(COMPANY.organizationId(),shop,longId));
        assertEquals(1,ownerRows(COMPANY.organizationId(),shop,prefix));
        assertEquals(255,db.queryForObject("SELECT CHAR_LENGTH(order_id) FROM finance_label_order_totals WHERE organization_id=?",Integer.class,COMPANY.organizationId()));
        db.update("UPDATE finance_records SET record_date='2026-11-02' WHERE id='core-out-old'");
        db.update("UPDATE finance_records SET record_date='2026-10-11' WHERE id='core-in-old'");
        db.update("UPDATE finance_first_settlement_facts SET schema_version=0 WHERE organization_id=? AND order_id='O'",COMPANY.organizationId());
        settlement("core-N",shop,"N",OCT.plusDays(16));addSources(COMPANY,shop,"N","core-new");
        assertEquals(0,db.queryForObject("SELECT applied_revision FROM finance_first_settlement_facts WHERE organization_id=? AND order_id='N'",Integer.class,COMPANY.organizationId()));
        var october=List.of("core-N","core-P","core-O","core-in-next","core-C");
        var cap3=probe("C2_core_cap3",MONTH,3,DateRoute.NATIVE,"OWNER_CAP",october);
        assertEquals(4,cap3.attempt().combined);assertEquals(0,cap3.attempt().lookups);
        for(int cap:List.of(4,5)) {
            var r=probe("C2_core_cap"+cap,MONTH,cap,DateRoute.DATE_TEST,"",october);
            assertEquals(4,r.attempt().unresolved);assertEquals(4,r.attempt().lookups);
            assertEquals(3,r.attempt().pendingBranch);assertEquals(4,r.attempt().oldBranch);
            assertEquals(10,r.data().settled().get("ORDER:false").count());
            assertEquals(15,r.data().settled().get("REFUND:false").count());
            // Literal rows prove API source multiplicity and the cancelled ORDER survive.
            assertEquals(List.of("core-new-o1","core-new-o2","core-prefix-o1","core-prefix-o2",
                "core-old-o1","core-old-o2","core-in-o1","core-in-o2","core-c-o1","core-c-o2"),
                ids(r.data().settled().get("ORDER:false").bag()));
        }
        probe("C2_September_move_out",window(OCT.minusMonths(1),OCT.minusDays(1)),4,DateRoute.DATE_TEST,"",List.of("core-S"));
        probe("C2_November_move_in",window(OCT.plusMonths(1),OCT.plusMonths(2).minusDays(1)),4,DateRoute.DATE_TEST,"",List.of("core-out-next"));
        // Removed/out-of-month unresolved still consumes the global cap, before visibility.
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",shop);
        var global=probe("C2_invisible_dirty_still_global",MONTH,3,DateRoute.NATIVE,"OWNER_CAP",List.of());
        assertEquals(4,global.attempt().combined);assertEquals(0,global.attempt().lookups);
        evidence.add(Map.of("case","C2_domain_cardinality","eligibleOwners",7,"unresolvedOwners",4,"longTotalsCharacters",255,"longTotalsInU",false));
    }

    void manyNoFirstOwnersDoNotConsumeTheRepairCap() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-extra",null);
        settlement("extra-clean",shop,"genuine",OCT.plusDays(2));addSources(COMPANY,shop,"genuine","extra-clean");
        for(int n=0;n<65;n++)addSources(COMPANY,shop,"no-first-"+n,"extra-"+n);
        for(int n=0;n<4;n++)total(shop,"totals-without-first-"+n);
        initializeAndPublish(COMPANY);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=?",Integer.class,COMPANY.organizationId()));
        assertEquals(66,db.queryForObject("SELECT COUNT(DISTINCT shop_id,order_id) FROM finance_records WHERE organization_id=? AND kind='ORDER' AND source='API'",Integer.class,COMPANY.organizationId()));
        var r=probe("ADDENDUM_65_no_first_cap0",MONTH,0,DateRoute.DATE_TEST,"",List.of("extra-clean"));
        assertEquals(0,r.attempt().auditRows);assertEquals(0,r.attempt().unresolved);assertEquals(0,r.attempt().lookups);
        assertEquals(0,r.attempt().externalDomainReads);
        assertEquals(2,r.data().settled().get("ORDER:false").count());assertEquals(3,r.data().settled().get("REFUND:false").count());
        evidence.add(Map.of("case","ADDENDUM_extra_domain","eligibleOwners",1,"extraNoEligibleNoFactOwners",65,
            "extraApiOrderRows",130,"extraApiRefundRows",195,"noFirstTotals",4,"dMissingClassifications",0,"dMissingLookups",0));
    }

    void noFirstBecomesPendingOnlyInTheNextSnapshot() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-new",null);
        settlement("snapshot-clean",shop,"clean",OCT.plusDays(2));addSources(COMPANY,shop,"clean","snapshot-c");
        addSources(COMPANY,shop,"d","snapshot-d");initializeAndPublish(COMPANY);
        assertEquals(0,ownerRows(COMPANY.organizationId(),shop,"d"));
        var o=new Options(COMPANY.organizationId());
        o.afterSnapshot=()->insertFromOtherConnection("snapshot-new",shop,"d",OCT.plusDays(7));
        var old=probe("ADDENDUM_A_old_S_Dmissing",COMPANY,MONTH,0,o,DateRoute.DATE_TEST,"",List.of("snapshot-clean"),"");
        assertEquals(0,old.attempt().unresolved);assertEquals(0,old.attempt().lookups);
        assertEquals(1,ownerRows(COMPANY.organizationId(),shop,"d"));assertEquals(1,pending(COMPANY));
        var expected=List.of("snapshot-new","snapshot-clean");
        var cap0=probe("ADDENDUM_next_S_cap0",MONTH,0,DateRoute.NATIVE,"OWNER_CAP",expected);
        assertEquals(0,cap0.attempt().lookups);
        var cap1=probe("ADDENDUM_next_S_cap1",MONTH,1,DateRoute.DATE_TEST,"",expected);
        assertEquals(1,cap1.attempt().lookups);
    }
    void insertFromOtherConnection(String id,String shop,String order,LocalDate date) {
        try(var writer=dataSource.getConnection()) {
            writer.setAutoCommit(false);long otherId;
            try(var s=writer.createStatement();var rs=s.executeQuery("SELECT CONNECTION_ID()")){rs.next();otherId=rs.getLong(1);}
            assertNotEquals(current.get().connectionId,otherId,"A and B must hold distinct real physical connections");
            updateAndCommit(writer,"""
                INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,category,status,record_date,currency,source,raw_json)
                VALUES(?,?,?,?,'SHOPEE','SETTLEMENT',?,?,'ORDER','SETTLED',?,'USD','API','{}')
                """,id,COMPANY.organizationId(),COMPANY.userId(),shop,id,order,java.sql.Date.valueOf(date));
            evidence.add(Map.of("case",current.get().name,"writerConnectionId",otherId,"readerConnectionId",current.get().connectionId,"writerCommitted",true));
        }catch(SQLException failure){throw new IllegalStateException(failure);}
    }

    void deleteAndReappearanceUseCurrentHistory() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-delete",null);
        settlement("delete-first",shop,"delete",OCT.plusDays(1));settlement("delete-successor",shop,"delete",OCT.plusDays(5));
        addSources(COMPANY,shop,"delete","delete");initializeAndPublish(COMPANY);
        db.update("DELETE FROM finance_records WHERE id='delete-first'");
        var next=probe("C4_delete_first_exposes_successor",MONTH,1,DateRoute.DATE_TEST,"",List.of("delete-successor"));
        assertEquals(1,next.attempt().lookups);
        db.update("DELETE FROM finance_records WHERE id='delete-successor'");
        var empty=probe("C4_all_deleted_pending_empty",MONTH,1,DateRoute.DATE_TEST,"",List.of());
        assertEquals(1,empty.attempt().lookups);
        publish(COMPANY);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? AND has_settlement=FALSE AND applied_revision=requested_revision AND applied_revision>0",Integer.class,COMPANY.organizationId()));
        probe("C4_real_worker_known_empty_cap0",MONTH,0,DateRoute.DATE_TEST,"",List.of());
        settlement("delete-reinsert",shop,"delete",OCT.plusDays(8));
        assertEquals(1,pending(COMPANY));
        probe("C4_reinsert_pending_returns",MONTH,1,DateRoute.DATE_TEST,"",List.of("delete-reinsert"));
    }


    void eligibilityAndVersionTransitionsAreNotPruned() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-transitions",null);
        settlement(COMPANY,"trans-z",shop,"transition",OCT.plusDays(4),"SHOPEE","USD","ORDER","SETTLED","IMPORT");
        addSources(COMPANY,shop,"transition","transition");initializeAndPublish(COMPANY);
        probe("C5_IMPORT_is_not_eligible",MONTH,0,DateRoute.DATE_TEST,"",List.of());
        db.update("UPDATE finance_records SET source='API' WHERE id='trans-z'");
        probe("C5_API_enters",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-z"));
        db.update("UPDATE finance_records SET source='IMPORT' WHERE id='trans-z'");
        probe("C5_API_to_IMPORT_leaves",MONTH,1,DateRoute.DATE_TEST,"",List.of());
        db.update("UPDATE finance_records SET source='API' WHERE id='trans-z'");
        probe("C5_IMPORT_to_API_returns",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-z"));
        db.update("UPDATE finance_records SET status='CANCELLED' WHERE id='trans-z'");
        probe("C5_CANCELLED_leaves",MONTH,1,DateRoute.DATE_TEST,"",List.of());
        db.update("UPDATE finance_records SET status='SETTLED' WHERE id='trans-z'");
        probe("C5_SETTLED_returns",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-z"));
        db.update("UPDATE finance_records SET platform='TIKTOK_SHOP',category='FEE' WHERE id='trans-z'");
        probe("C5_TikTok_FEE_leaves",MONTH,1,DateRoute.DATE_TEST,"",List.of());
        db.update("UPDATE finance_records SET category='ORDER' WHERE id='trans-z'");
        probe("C5_TikTok_ORDER_returns",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-z"));
        db.update("UPDATE finance_records SET order_id='' WHERE id='trans-z'");
        probe("C5_empty_order_leaves",MONTH,1,DateRoute.DATE_TEST,"",List.of());
        db.update("UPDATE finance_records SET order_id='transition' WHERE id='trans-z'");
        probe("C5_nonempty_order_returns",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-z"));
        settlement("trans-a",shop,"transition",OCT.plusDays(4));
        probe("C5_SQL_id_tie",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-a"));
        db.update("UPDATE finance_records SET id='trans-zz' WHERE id='trans-a'");
        probe("C5_changed_id_reorders_tie",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-z"));
        db.update("UPDATE finance_records SET record_date='2026-09-30' WHERE id='trans-z'");
        probe("C5_date_leaves_month",MONTH,1,DateRoute.DATE_TEST,"",List.of());
        probe("C5_date_enters_September",window(OCT.minusMonths(1),OCT.minusDays(1)),1,DateRoute.DATE_TEST,"",List.of("trans-z"));
        db.update("UPDATE finance_records SET record_date='2026-10-05',currency='PHP' WHERE id='trans-z'");
        var copied=probe("C5_same_winner_new_currency_platform",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-z"));
        assertEquals("PHP",copied.data().identities().getFirst().get("currency"));
        assertEquals("TIKTOK_SHOP",copied.data().identities().getFirst().get("platform"));
        String foreignShop=groupedShop(FOREIGN,"date-migration-target",null);
        settlement(FOREIGN,"foreign-later",foreignShop,"moved",OCT.plusDays(8),"SHOPEE","USD","ORDER","SETTLED","API");
        addSources(FOREIGN,foreignShop,"moved","foreign");initializeAndPublish(FOREIGN);
        long old=revision(COMPANY.organizationId(),shop,"transition"),target=revision(FOREIGN.organizationId(),foreignShop,"moved");
        db.update("UPDATE finance_records SET organization_id=?,shop_id=?,order_id='moved' WHERE id='trans-z'",FOREIGN.organizationId(),foreignShop);
        assertEquals(old+1,revision(COMPANY.organizationId(),shop,"transition"));
        assertEquals(target+1,revision(FOREIGN.organizationId(),foreignShop,"moved"));
        probe("C5_owner_migration_old_end",MONTH,1,DateRoute.DATE_TEST,"",List.of("trans-zz"));
        probe("C5_owner_migration_new_end",FOREIGN,MONTH,1,new Options(FOREIGN.organizationId()),DateRoute.DATE_TEST,"",List.of("trans-z"),"");

        resetFixture();shop=groupedShop(COMPANY,"date-old-mode",null);
        for(int n=1;n<=3;n++){settlement("old-"+n,shop,"old-"+n,OCT.plusDays(n));addSources(COMPANY,shop,"old-"+n,"old-"+n);}
        initializeAndPublish(COMPANY);
        db.update("UPDATE finance_first_settlement_facts SET queued_generation=99 WHERE organization_id=?",COMPANY.organizationId());
        probe("C5_queued_generation_is_not_clean_condition",MONTH,0,DateRoute.DATE_TEST,"",List.of("old-3","old-2","old-1"));
        db.update("UPDATE finance_first_settlement_facts SET schema_version=0 WHERE organization_id=? AND order_id='old-1'",COMPANY.organizationId());
        db.update("UPDATE finance_first_settlement_facts SET applied_generation=0 WHERE organization_id=? AND order_id='old-2'",COMPANY.organizationId());
        db.update("UPDATE finance_first_settlement_facts SET applied_registration_version=0 WHERE organization_id=? AND order_id='old-3'",COMPANY.organizationId());
        assertEquals(0,pending(COMPANY));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? AND applied_revision=requested_revision",Integer.class,COMPANY.organizationId()));
        probe("C5_all_old_modes_cap2",MONTH,2,DateRoute.NATIVE,"OWNER_CAP",List.of("old-3","old-2","old-1"));
        var all=probe("C5_all_old_modes_equal_revisions",MONTH,3,DateRoute.DATE_TEST,"",List.of("old-3","old-2","old-1"));
        assertEquals(0,all.attempt().pendingBranch);assertEquals(3,all.attempt().oldBranch);assertEquals(3,all.attempt().lookups);
    }

    void sqlIdentityAndCurrentBytesArePreserved() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-collation",null);
        settlement("accent-a",shop,"Éclair",OCT.plusDays(2));settlement("accent-z",shop,"ECLAIR",OCT.plusDays(3));
        settlement("pad-plain",shop,"tail",OCT.plusDays(4));settlement("pad-space",shop,"tail ",OCT.plusDays(5));
        addSources(COMPANY,shop,"Éclair","accent");addSources(COMPANY,shop,"tail","pad");addSources(COMPANY,shop,"tail ","pad-space");
        initializeAndPublish(COMPANY);
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=?",Integer.class,COMPANY.organizationId()));
        assertEquals(1,ownerRows(COMPANY.organizationId(),shop,"eclair"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? AND order_id IN ('tail','tail ')",Integer.class,COMPANY.organizationId()));
        assertEquals("NO PAD",db.queryForObject("SELECT PAD_ATTRIBUTE FROM information_schema.collations WHERE COLLATION_NAME='utf8mb4_0900_ai_ci'",String.class));
        db.update("UPDATE finance_records SET currency='EUR' WHERE id='accent-z'");
        var merged=probe("C6_SQL_equivalent_owner_U_once",MONTH,1,DateRoute.DATE_TEST,"",List.of("pad-space","pad-plain","accent-a"));
        assertEquals(1,merged.attempt().unresolved);assertEquals(1,merged.attempt().lookups);
        publish(COMPANY);
        long before=revision(COMPANY.organizationId(),shop,"Éclair");
        String uppercaseShop=shop.toUpperCase(Locale.ROOT);
        db.update("UPDATE finance_records SET id='ACCENT-A',organization_id='DATE-BULK-COMPANY',shop_id=?,order_id='ECLAIR',platform='shopee',currency='usd',status='settled' WHERE id='accent-a'",uppercaseShop);
        assertEquals(before,revision(COMPANY.organizationId(),shop,"Éclair"),"SQL-equal byte-only changes need not dirty this trigger");
        assertEquals(0,pending(COMPANY));
        var bytes=probe("C6_rExact_current_eight_bytes",MONTH,0,DateRoute.DATE_TEST,"",List.of("pad-space","pad-plain","ACCENT-A"));
        var changed=bytes.data().identities().getLast();
        assertEquals("757364",changed.get("currency_hex"));assertEquals("73686F706565",changed.get("platform_hex"));
        assertEquals("414343454E542D41",changed.get("id_hex"));assertEquals("45434C414952",changed.get("order_id_hex"));
        assertEquals("736574746C6564",changed.get("status_hex"));
        var stale=db.queryForMap("SELECT HEX(f.first_record_id) copied_id,HEX(f.first_currency) copied_currency,HEX(r.id) current_id,HEX(r.currency) current_currency "+
            "FROM finance_first_settlement_facts f JOIN finance_records r ON r.id=f.first_record_id WHERE f.organization_id=? AND f.order_id='ECLAIR'",COMPANY.organizationId());
        assertNotEquals(stale.get("copied_id"),stale.get("current_id"));assertNotEquals(stale.get("copied_currency"),stale.get("current_currency"));
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->repo.save(COMPANY,
            new Entry(shop,"SHOPEE","ORDER","app-invalid","app-order","","","SETTLED",OCT,"usd",null,null,null,null,null,"API",Map.of())));
        repo.save(COMPANY,new Entry(shop,"SHOPEE","ORDER","app-valid","app-order","","","SETTLED",OCT,"USD",null,null,null,null,null,"API",Map.of()));
        var app=db.queryForMap("SELECT id,platform,currency FROM finance_records WHERE external_id='app-valid'");
        assertTrue(text(app.get("id")).matches("[0-9a-f]{64}"));assertEquals("USD",app.get("currency"));assertEquals("SHOPEE",app.get("platform"));
        evidence.add(Map.of("case","C6_byte_observation","revisionUnchanged",true,"copyBytesStale",true,"rExactEightHexEqual",true,
            "SQLCollation","utf8mb4_0900_ai_ci","NO_PAD_distinctOwners",2,"JavaOwnerNormalization",false));
    }


    void filtersKeepCurrentProvenanceAndSourceMultiplicity() throws Exception {
        resetFixture();
        String groupA=service.saveGroup(COMPANY,null,"Date A"),groupB=service.saveGroup(COMPANY,null,"Date B");
        String a=groupedShop(COMPANY,"date-sem-a",groupA),b=groupedShop(COMPANY,"date-sem-b",groupB);
        String ungrouped=groupedShop(COMPANY,"date-sem-u",null),removed=groupedShop(COMPANY,"date-sem-removed",groupA);
        String foreign=groupedShop(FOREIGN,"date-sem-foreign",null);
        record OwnerFixture(Scope company,String id,String shop,String order,int day,String platform,String currency) {}
        var fixtures=List.of(
            new OwnerFixture(COMPANY,"sem-A",a,"A",2,"SHOPEE","USD"),
            new OwnerFixture(COMPANY,"sem-B",b,"B",3,"TIKTOK_SHOP","PHP"),
            new OwnerFixture(COMPANY,"sem-ungrouped",ungrouped,"U",4,"SHOPEE","USD"),
            new OwnerFixture(COMPANY,"sem-unassigned","UNASSIGNED","UA",5,"SHOPEE","USD"),
            new OwnerFixture(COMPANY,"sem-corrected",a,"corrected",1,"TIKTOK_SHOP","USD"),
            new OwnerFixture(COMPANY,"sem-marketing",a,"marketing",6,"SHOPEE","USD"),
            new OwnerFixture(COMPANY,"sem-removed",removed,"removed",7,"SHOPEE","USD"),
            new OwnerFixture(FOREIGN,"sem-foreign",foreign,"foreign",8,"SHOPEE","USD"));
        for(var f:fixtures){
            settlement(f.company(),f.id(),f.shop(),f.order(),OCT.plusDays(f.day()-1),f.platform(),f.currency(),"ORDER","SETTLED","API");
            addSources(f.company(),f.shop(),f.order(),f.id());
        }
        settlement(COMPANY,"sem-early-FEE",a,"A",OCT.minusDays(3),"TIKTOK_SHOP","USD","FEE","SETTLED","API");
        settlement(COMPANY,"sem-early-CANCELLED",a,"A",OCT.minusDays(2),"SHOPEE","USD","ORDER","CANCELLED","API");
        settlement(COMPANY,"sem-early-IMPORT",a,"A",OCT.minusDays(1),"SHOPEE","USD","ORDER","SETTLED","IMPORT");
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",removed);
        db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by) "+
            "VALUES(?,?,'corrected','sem-corrected','2026-10-01','USER_CONFIRMED_MONTH','synthetic-test','test')",COMPANY.organizationId(),a);
        initializeAndPublish(COMPANY);initializeAndPublish(FOREIGN);
        var all=List.of("sem-marketing","sem-unassigned","sem-ungrouped","sem-B","sem-A","sem-corrected");
        var beforeMarketing=probe("C7_before_marketing_change",MONTH,0,DateRoute.DATE_TEST,"",all);
        assertEquals(12,beforeMarketing.data().settled().get("ORDER:true").count());
        long revision=revision(COMPANY.organizationId(),a,"marketing");
        db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,source_count) VALUES(?,?,'marketing',1)",COMPANY.organizationId(),a);
        assertEquals(revision,revision(COMPANY.organizationId(),a,"marketing"));
        var full=probe("C7_full_visibility_and_multiplicity",MONTH,0,DateRoute.DATE_TEST,"",all);
        assertEquals(12,full.data().settled().get("ORDER:false").count());assertEquals(18,full.data().settled().get("REFUND:false").count());
        assertEquals(10,full.data().settled().get("ORDER:true").count());assertEquals(15,full.data().settled().get("REFUND:true").count());
        var orders=full.data().settled().get("ORDER:false").bag();
        assertEquals(List.of("sem-marketing-o1","sem-marketing-o2","sem-unassigned-o1","sem-unassigned-o2",
            "sem-ungrouped-o1","sem-ungrouped-o2","sem-B-o1","sem-B-o2","sem-A-o1","sem-A-o2","sem-corrected-o1","sem-corrected-o2"),ids(orders));
        assertEquals(6,orders.stream().filter(r->r.get("amount")==null).count());
        assertEquals(6,orders.stream().filter(r->r.get("amount")!=null&&decimal(r.get("amount")).signum()==0).count());
        assertEquals(6,orders.stream().filter(r->"CANCELLED".equals(r.get("status"))).count());
        var refunds=full.data().settled().get("REFUND:false").bag();
        assertEquals(6,refunds.stream().filter(r->r.get("amount")==null).count());
        assertEquals(6,refunds.stream().filter(r->new BigDecimal("-1.234567").equals(r.get("amount"))).count());
        assertEquals(6,refunds.stream().filter(r->r.get("amount")!=null&&decimal(r.get("amount")).signum()==0).count());
        probe("C7_group_A",chosen("","","",groupA),0,DateRoute.DATE_TEST,"",List.of("sem-marketing","sem-A","sem-corrected"));
        probe("C7_group_B",chosen("","","",groupB),0,DateRoute.DATE_TEST,"",List.of("sem-B"));
        probe("C7_UNGROUPED_and_UNASSIGNED",chosen("","","","UNGROUPED"),0,DateRoute.DATE_TEST,"",List.of("sem-unassigned","sem-ungrouped"));
        probe("C7_removed_shop",chosen(removed,"","",""),0,DateRoute.DATE_TEST,"",List.of());
        probe("C7_company_isolation",FOREIGN,MONTH,0,new Options(FOREIGN.organizationId()),DateRoute.DATE_TEST,"",List.of("sem-foreign"),"");
        probe("C7_r_platform_filter",chosen("","TIKTOK_SHOP","",""),0,DateRoute.DATE_TEST,"",List.of("sem-B","sem-corrected"));
        probe("C7_r_currency_filter",chosen("","","PHP",""),0,DateRoute.DATE_TEST,"",List.of("sem-B"));
        var usd=probe("C7_r_filter_o_display_fields",chosen("","","USD",""),0,DateRoute.DATE_TEST,"",
            List.of("sem-marketing","sem-unassigned","sem-ungrouped","sem-A","sem-corrected"));
        assertTrue(usd.data().settled().get("ORDER:false").bag().stream().allMatch(r->"PHP".equals(r.get("currency"))&&"TIKTOK_SHOP".equals(r.get("platform"))));
        var numbers=probe("C7_numbers_apply_to_o",COMPANY,MONTH,0,new Options(COMPANY.organizationId()),DateRoute.DATE_TEST,"",all,"external-sem-A-o2");
        assertEquals(List.of("sem-A-o2"),ids(numbers.data().settled().get("ORDER:false").bag()));
        assertEquals(1,numbers.data().settled().get("ORDER:false").count());assertEquals(0,numbers.data().settled().get("REFUND:false").count());
        assertEquals("CANCELLED",numbers.data().settled().get("ORDER:false").bag().getFirst().get("status"));
        Filter partial=window(OCT,OCT.plusDays(9));
        probe("C7_partial_before_raw_correction",partial,0,DateRoute.DATE_TEST,"",all);
        long correctedRevision=revision(COMPANY.organizationId(),a,"corrected");
        db.update("UPDATE finance_records SET raw_json=? WHERE id='sem-corrected'",
            repo.write(Map.of("_finance_reporting_month","2026-10","_finance_original_settlement_date","2026-09-30")));
        assertEquals(correctedRevision,revision(COMPANY.organizationId(),a,"corrected"));
        probe("C7_partial_after_raw_only_correction",partial,0,DateRoute.DATE_TEST,"",all.subList(0,5));
        var corrected=probe("C7_full_month_current_provenance",MONTH,0,DateRoute.DATE_TEST,"",all);
        var correctedOrder=corrected.data().settled().get("ORDER:false").bag().getLast();
        assertEquals("2026-10",correctedOrder.get("reporting_month"));assertEquals("2026-09-30",correctedOrder.get("original_date"));
        assertEquals(java.sql.Date.valueOf("2026-08-01"),correctedOrder.get("source_date"));
        probe("C7_cross_month_partial",window(OCT.minusDays(16),OCT.plusDays(14)),0,DateRoute.DATE_TEST,"",all.subList(0,5));
        probe("C7_cross_month_complete",window(OCT.minusMonths(1),MONTH.end()),0,DateRoute.DATE_TEST,"",all);
        assertEquals(0,pending(COMPANY));
        evidence.add(Map.of("case","C7_current_predicates","rawOnlyRevisionUnchanged",true,"marketingRevisionUnchanged",true,
            "sourceOrderDate","2026-08-01","reportingMonth","2026-10","nullZeroNegativeDistinct",true));
    }

    void falseFirstDayWitnessPreservesEveryCurrency() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-false",null);
        settlement("false-a",shop,"multi",OCT.plusDays(1));
        settlement(COMPANY,"false-z",shop,"multi",OCT.plusDays(1),"TIKTOK_SHOP","PHP","ORDER","SETTLED","API");
        settlement("false-later",shop,"multi",OCT.plusDays(2));
        settlement("false-none",shop,"no-order",OCT.plusDays(1));
        addSources(COMPANY,shop,"multi","false");initializeAndPublish(COMPANY);
        probe("C8_true_still_one_winner",MONTH,0,DateRoute.DATE_TEST,"",List.of("false-a","false-none"));
        falseWitness(MONTH,List.of("false-a","false-none","false-z"),3,2);
        falseWitness(chosen("","","PHP",""),List.of("false-z"),2,2);
        falseWitness(chosen("","","USD",""),List.of("false-a","false-none"),3,2);
    }
    void falseWitness(Filter filter,List<String> literal,long expectedRelated,long expectedRefund) {
        checkPhase();var a=new Attempt("C8_false_"+filter.currency());attempts.add(a);current.set(a);
        try {
            reader.readSnapshot(30,()->{
                reads.queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=?",Long.class,COMPANY.organizationId());
                a.stage="CANDIDATE";var r=resolve(COMPANY,filter,0,new Options(COMPANY.organizationId()));
                assertEquals(DateRoute.DATE_TEST,r.route());
                // Rejoin every eligible first-day source row; winner currency/platform are not reused.
                String days="SELECT "+prefixed("r")+" FROM ("+r.relation().sql()+") b JOIN finance_records r "+
                    "ON r.organization_id=b.organization_id AND r.shop_id=b.shop_id AND r.order_id=b.order_id "+
                    "AND r.record_date=b.record_date WHERE "+eligible("r");
                var q=reader.query(COMPANY,filter,"r");
                var actual=rows(r.budget(),"SELECT "+exactProjection("r")+" FROM ("+days+") r WHERE "+q.where()+
                    " AND "+FinanceOrderCharges.normalOrder("r")+" ORDER BY r.record_date DESC,r.id",joined(r.relation().args(),q.args()));
                a.stage="ORACLE";
                var original=rows(null,"SELECT "+exactProjection("r")+" FROM finance_records r WHERE "+
                    FinanceCostReport.firstSettlement("r",false)+" AND "+q.where()+" ORDER BY r.record_date DESC,r.id",q.args());
                assertEquals(literal,ids(actual));assertEquals(original,actual);
                var wrong=rows(null,"SELECT "+exactProjection("r")+" FROM ("+r.relation().sql()+") r WHERE "+q.where()+
                    " AND "+FinanceOrderCharges.normalOrder("r")+" ORDER BY r.record_date DESC,r.id",joined(r.relation().args(),q.args()));
                if(filter.currency().isEmpty()||filter.currency().equals("PHP"))assertNotEquals(original,wrong,"Actual winner-only SQL must lose a same-day currency witness");
                for(String kind:List.of("SETTLED_ORDER","REFUND_SETTLED")) {
                    String refund=kind.equals("REFUND_SETTLED")?" AND EXISTS(SELECT 1 FROM finance_records refund WHERE "+
                        "refund.organization_id=r.organization_id AND refund.shop_id=r.shop_id AND refund.order_id=r.order_id AND refund.kind='REFUND' AND refund.source='API')":"";
                    String projected="SELECT DISTINCT o.id order_record_id,r.organization_id,r.shop_id,r.order_id reference,r.record_date settlement_date "+
                        "FROM ("+days+") r LEFT JOIN finance_records o ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id "+
                        "AND o.order_id=r.order_id AND o.kind='ORDER' AND o.source='API' WHERE "+q.where()+" AND "+FinanceOrderCharges.normalOrder("r")+refund;
                    var bag=rows(null,projected+" ORDER BY settlement_date DESC,order_record_id",joined(r.relation().args(),q.args()));
                    var oracle=rows(null,FinanceCostReport.related(kind)+q.where()+" ORDER BY settlement_date DESC,order_record_id",q.args());
                    assertEquals(oracle,bag);assertEquals(kind.equals("SETTLED_ORDER")?expectedRelated:expectedRefund,bag.size());
                    if(kind.equals("SETTLED_ORDER")&&!filter.currency().equals("PHP"))
                        assertEquals(1,bag.stream().filter(row->row.get("order_record_id")==null).count(),"LEFT JOIN no-ORDER output is preserved");
                }
                FinanceLabelFirstSettlementFacts.requireUsable(reader);assertTrue(a.sameSnapshot);assertEquals(1,a.borrows);
                return null;
            });
            a.completed=true;a.returnedData=1;a.route="DATE_TEST_FALSE_WITNESS";genuineDate++;
            evidence.add(Map.of("case",a.name,"falseFirstDayIds",literal,"sourceFalseEqual",true,"relatedDistinctEqual",true,"publicFalseNative",true));
        }catch(RuntimeException|Error failure){a.failure=failure.getClass().getSimpleName();throw failure;}
        finally{current.remove();}
    }


    void invalidSnapshotsAndSqlFailuresNeverBecomeFallback() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"date-failures",null);
        settlement("failure-clean",shop,"clean",OCT.plusDays(2));addSources(COMPANY,shop,"clean","failure-clean");
        addSources(COMPANY,shop,"budget-new","budget-new");initializeAndPublish(COMPANY);
        var clock=new AtomicLong(System.nanoTime());var local=new Options(COMPANY.organizationId());local.clock=clock::get;
        local.beforeAudit=()->{
            insertFromOtherConnection("budget-new",shop,"budget-new",OCT.plusDays(7));
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        };
        var sameS=probe("C9_proactive_budget_same_S",COMPANY,MONTH,64,local,DateRoute.NATIVE,"LOCAL_BUDGET",List.of("failure-clean"),"");
        assertEquals(-1,sameS.attempt().auditRows,"An unexecuted audit is not reported empty");
        assertEquals(0,sameS.attempt().lookups);assertEquals(1,sameS.attempt().nativeData);
        probe("C9_next_request_sees_writer",MONTH,1,DateRoute.DATE_TEST,"",List.of("budget-new","failure-clean"));
        publish(COMPANY);
        for(String fault:List.of("EXPIRED","ROLLBACK_ONLY","PHYSICAL_CLOSE")) {
            var options=new Options(COMPANY.organizationId());
            options.beforeProof=()->{
                switch(fault) {
                    case "EXPIRED" -> holder().setTimeoutInMillis(0);
                    case "ROLLBACK_ONLY" -> holder().setRollbackOnly();
                    case "PHYSICAL_CLOSE" -> {
                        try {
                            var physical=holder().getConnection().unwrap(Connection.class);
                            assertTrue(physical.getClass().getName().startsWith("com.mysql."));
                            physical.close();assertTrue(physical.isClosed());
                            evidence.add(Map.of("case","C9_PHYSICAL_CLOSE","physicalMySqlClosed",true));
                        }catch(SQLException failure){throw new IllegalStateException(failure);}
                    }
                    default -> throw new AssertionError(fault);
                }
            };
            assertThrows(RuntimeException.class,()->probe("C9_"+fault,COMPANY,MONTH,64,options,DateRoute.DATE_TEST,"",List.of(),""));
            assertWholeProbeFailed(attempts.getLast());
        }
        var error=new Options(COMPANY.organizationId());error.auditControl="SELECT id FROM finance_date_bulk_deliberately_absent_table";
        var sqlFailure=assertThrows(RuntimeException.class,()->probe("C9_actual_audit_SQL_error",COMPANY,MONTH,64,error,DateRoute.DATE_TEST,"",List.of(),""));
        var sql=FinanceFirstSettlementLoadIntegrationTest.findSql(sqlFailure);
        assertNotNull(sql);assertEquals(1146,sql.getErrorCode());assertWholeProbeFailed(attempts.getLast());
        var timeout=new Options(COMPANY.organizationId());timeout.auditControl="SELECT SLEEP(2)";
        var timeoutFailure=assertThrows(RuntimeException.class,()->probe("C9_actual_audit_SQL_timeout",COMPANY,MONTH,64,timeout,DateRoute.DATE_TEST,"",List.of(),""));
        var timedOut=FinanceFirstSettlementLoadIntegrationTest.findSql(timeoutFailure);
        assertNotNull(timedOut);assertInstanceOf(java.sql.SQLTimeoutException.class,timedOut);
        assertWholeProbeFailed(attempts.getLast());
        realCancellationFailsTheEntireProbe();
    }
    void realCancellationFailsTheEntireProbe() throws Exception {
        var executor=Executors.newSingleThreadExecutor();executors.add(executor);
        var options=new Options(COMPANY.organizationId());options.auditControl="SELECT SLEEP(10)";
        source.sleep=null;int before=source.cancelCalls.get();
        Future<Probe> future=executor.submit(()->probe("C9_actual_audit_SQL_cancel",COMPANY,MONTH,64,options,DateRoute.DATE_TEST,"",List.of(),""));
        long deadline=Math.min(hardDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
        try {
            while(System.nanoTime()<deadline) {
                var s=source.sleep;
                if(s!=null&&FinanceFirstSettlementLoadIntegrationTest.mysqlExecuting(s))break;
                if(future.isDone())fail("Cancellation control ended before its real SQL was observed executing");
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertNotNull(source.sleep);assertTrue(FinanceFirstSettlementLoadIntegrationTest.mysqlExecuting(source.sleep));
            source.cancelAll();source.cancelAll();
            assertEquals(before+1,source.cancelCalls.get(),"Second cleanup pass must not duplicate cancellation");
            var failure=assertThrows(ExecutionException.class,()->future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS));
            var sql=FinanceFirstSettlementLoadIntegrationTest.findSql(failure);
            assertNotNull(sql);assertEquals("MySQLStatementCancelledException",sql.getClass().getSimpleName());
            assertWholeProbeFailed(attempts.getLast());
            evidence.add(Map.of("case","C9_real_cancel","executingObserved",true,"directCancelCalls",source.cancelCalls.get()-before,
                "SQLState",String.valueOf(sql.getSQLState()),"vendorCode",sql.getErrorCode(),"returnedPartialData",false));
        }finally{
            if(!future.isDone()){source.cancelAll();future.cancel(true);}
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS));
        }
    }
    void assertWholeProbeFailed(Attempt a) {
        assertFalse(a.completed);assertFalse(a.failure.isEmpty());assertEquals(0,a.nativeData);
        assertEquals(0,a.oracleData);assertEquals(0,a.returnedData);assertEquals(1,a.borrows);
        assertEquals(0,source.activeConnections.get());assertTrue(source.executing.isEmpty());assertTrue(source.openStatements.isEmpty());
        evidence.add(Map.of("case",a.name,"entireProbeFailed",true,"nativeFallbacks",0,"oracleCompletions",0,"partialResponses",0,"physicalBorrows",1));
    }





    static final long SELECTIVE_SEED=20261114L;
    static String selectivePrefix(int owner){return "sel-"+String.format(Locale.ROOT,"%08d",owner);}
    static String selectiveOrder(int owner){return "selective-order-"+String.format(Locale.ROOT,"%08d",owner);}
    static LocalDate selectiveFirstDay(int owner,SelectiveFixture fixture) {
        if(owner<fixture.octoberOwners())return OCT.plusDays(owner%10);
        return (owner<fixture.novemberStart()?OCT.minusMonths(1):OCT.plusMonths(1)).plusDays(
            (owner<fixture.novemberStart()?owner-fixture.octoberOwners():owner-fixture.novemberStart())%27);
    }
    void selectiveCleanOnePercent() throws Exception {
        selectiveFixture=SELECTIVE_1K;configureSelectiveReceipt("CURRENT",30,false);
        try {selectiveSmallControls();runSelectiveLargeCohort("selective-clean-one-percent",30);}
        finally {selective.put("timingSummary",selectiveTimingSummary());}
    }
    void selectiveCleanOnePercentOrderIndex() throws Exception {
        orderIndexHintEnabled=true;selectiveFixture=SELECTIVE_1K;configureSelectiveReceipt("HINTED_ORDER_INDEX",30,false);
        try {runSelectiveLargeCohort("selective-clean-one-percent-order-index",30);}
        finally {selective.put("timingSummary",selectiveTimingSummary());}
        assertEquals(30,pairs.stream().filter(p->p.completed).count());
        assertTrue(orderIndexHintRelations.get()>=120,"Thirty HINTED pairs must build all four candidate source relations");
    }
    void selectiveCleanOnePercentOrderIndexSmoke() throws Exception {
        orderIndexHintEnabled=true;selectiveFixture=SELECTIVE_1K;configureSelectiveReceipt("HINTED_ORDER_INDEX",1,true);
        try {runSelectiveLargeCohort("selective-clean-one-percent-order-index-smoke",1);}
        finally {selective.put("timingSummary",selectiveTimingSummary());}
        assertEquals(1,pairs.stream().filter(p->p.completed).count());assertEquals(4,orderIndexHintRelations.get());
        assertEquals(25,pairs.getFirst().nativePath.selects());assertEquals(31,pairs.getFirst().candidatePath.selects());
        assertEquals("a21cab7b3e8468824243f1d26521be52634be403a7583d2b589b85bef1153cf7",
            ((Map<?,?>)selective.get("sourceSetup")).get("insertionOwnerOrderHash"));
    }
    void selectiveCleanOnePercentOrderIndexSize10000() throws Exception {
        orderIndexHintEnabled=true;selectiveFixture=SELECTIVE_10K;configureSelectiveReceipt("HINTED_ORDER_INDEX",30,false);
        try {runSelectiveLargeCohort("selective-clean-one-percent-order-index-size-10000",30);}
        finally {selective.put("timingSummary",selectiveTimingSummary());}
        assertEquals(30,pairs.stream().filter(p->p.completed).count());assertEquals(120,orderIndexHintRelations.get());
        assertTrue(pairs.stream().allMatch(p->p.nativePath.selects()==25&&p.candidatePath.selects()==31));
    }
    void selectiveMixedOnePercentOrderIndex() throws Exception {
        orderIndexHintEnabled=true;selectiveFixture=SELECTIVE_1K;selectiveExpectedOwnerOrder=List.of(14,13,12,11,10,9,8,7,6,5);
        expectedSelectiveUnresolved=10;expectedPendingBeforePair=10;configureSelectiveReceipt("HINTED_ORDER_INDEX_STATIC_MIXED",30,false);
        selective.put("expectedOwnerOrder",selectiveExpectedOwnerOrder);selective.put("staticMixed",true);
        Map<String,Object> before=null;
        try {
            prepareSelectiveFixture("selective-mixed-one-percent-order-index");
            selective.put("preMutationVerification",new LinkedHashMap<>((Map<String,Object>)selective.get("independentFixtureSQL")));
            applySelectiveMixedMutation();verifySelectiveSources(10);verifySelectiveMixedFixture();
            before=mixedFactsState();selective.put("measurementFactsStart",before);assertEquals(10,number(before.get("pending")));
            runSelectivePairs(30);
        }finally {
            if(before!=null){var after=mixedFactsState();selective.put("measurementFactsEnd",after);assertEquals(before,after,"Static mixed measurement must not publish or mutate facts");}
            selective.put("timingSummary",selectiveTimingSummary());selective.put("cohortFinallyNanos",System.nanoTime());
        }
        assertEquals(30,pairs.stream().filter(p->p.completed).count());assertEquals(120,orderIndexHintRelations.get());
        assertTrue(pairs.stream().allMatch(p->p.nativePath.selects()==25&&p.candidatePath.selects()==41&&p.pendingBeforePair==10));
    }
    void configureSelectiveReceipt(String candidateVariant,int targetPairs,boolean smokeOnly) {
        selective.put("seed",SELECTIVE_SEED);selective.put("owners",selectiveFixture.owners());selective.put("eligiblePerOwner",4);
        selective.put("octoberOwners",selectiveFixture.octoberOwners());selective.put("septemberOwners",selectiveFixture.septemberOwners());
        selective.put("novemberOwners",selectiveFixture.novemberOwners());
        selective.put("readerConcurrency",1);selective.put("workerBatch",64);selective.put("cap",64);
        selective.put("targetPairs",targetPairs);selective.put("smokeOnly",smokeOnly);
        selective.put("cacheState","ISOLATED_SEQUENTIAL_NOT_COLD_CACHE_CONTROLLED");selective.put("candidateVariant",candidateVariant);
        selective.put("unmeasured",Map.of("statementRowsExamined","NOT_MEASURED_NO_PRIVILEGE_EXPANSION",
            "physicalIO","NOT_MEASURED","globalLockWait","NOT_MEASURED"));
    }
    void runSelectiveLargeCohort(String shopName,int targetPairs) throws Exception {
        try {
            prepareSelectiveFixture(shopName);runSelectivePairs(targetPairs);
        }finally {selective.put("cohortFinallyNanos",System.nanoTime());}
    }
    void prepareSelectiveFixture(String shopName) throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,shopName,null);
        selective.put("fixtureShop",shop);seedSelectiveSources(shop);initializeSelectiveFacts();verifySelectiveSources();
    }
    void runSelectivePairs(int targetPairs) throws Exception {
        // Setup/backfill belongs to the 540s harness; the measured phase starts once, here.
        phaseDeadline=Math.min(hardDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(120));phaseAttempts=0;
        long phaseStarted=System.nanoTime();selective.put("phaseStartedNanos",phaseStarted);selective.put("phaseDeadlineNanos",phaseDeadline);
        var executor=Executors.newSingleThreadExecutor();executors.add(executor);
        try {
            for(int number=0;number<targetPairs;number++) {
                checkPhase();var a=new Attempt("SELECTIVE_PAIR_"+(number+1));attempts.add(a);
                var pair=new PairSample(number,a);pairs.add(pair);pair.phaseDeadlineNanos=phaseDeadline;
                var task=new FutureTask<PairSample>(()->measuredPair(pair));
                long waitUntil=Math.min(Math.min(hardDeadline,phaseDeadline),System.nanoTime()+TimeUnit.SECONDS.toNanos(30));
                pair.future=task;pair.waitDeadlineNanos=waitUntil;
                readerFutures.add(task);pair.dispatchedNanos=System.nanoTime();executor.execute(task);
                try {
                    task.get(Math.max(1,waitUntil-System.nanoTime()),TimeUnit.NANOSECONDS);
                    if(pair.rrEndedNanos>waitUntil){pair.lateCompletion=true;throw new TimeoutException("Late pair is not a completed sample");}
                    pair.completed=true;a.completed=true;a.returnedData=1;
                    if(pair.candidatePath.route.equals("DATE_TEST"))genuineDate++;
                }
                catch(ExecutionException failure){throw propagate(failure.getCause());}
                catch(TimeoutException failure){
                    pair.deadlineDetectionLagNanos=Math.max(0,System.nanoTime()-waitUntil);
                    pair.waitFailure=failure.getClass().getSimpleName();pair.cancelRequested=true;source.cancelAll();task.cancel(true);throw failure;
                }
            }
        }finally {
            executor.shutdownNow();selective.put("phaseEndedNanos",System.nanoTime());
            selective.put("phaseDeadlineDetectionLagNanos",Math.max(0,System.nanoTime()-phaseDeadline));
        }
        assertEquals(targetPairs,pairs.stream().filter(p->p.completed).count(),"A short phase must remain explicitly insufficient");
    }
    void selectiveSmallControls() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"selective-small-control",null);
        settlement("selective-smoke",shop,"smoke",OCT.plusDays(2));addSources(COMPANY,shop,"smoke","selective-smoke");
        initializeAndPublish(COMPANY);
        probe("SELECTIVE_SMALL_DATE",MONTH,0,DateRoute.DATE_TEST,"",List.of("selective-smoke"));
        var off=new Options(COMPANY.organizationId());off.enabled=false;
        var nativeOff=probe("SELECTIVE_SMALL_OFF",COMPANY,MONTH,64,off,DateRoute.NATIVE,"OFF",List.of("selective-smoke"),"");
        assertEquals(0,nativeOff.attempt().newTableReads);
        db.update("DELETE FROM finance_first_settlement_facts WHERE organization_id=?",COMPANY.organizationId());
        var broken=probe("SELECTIVE_SMALL_COVERAGE_BROKEN",MONTH,64,DateRoute.NATIVE,"COVERAGE_BROKEN",List.of("selective-smoke"));
        assertEquals(1,broken.attempt().auditRows);assertEquals(0,broken.attempt().lookups);
    }
    void seedSelectiveSources(String shop) {
        long started=System.nanoTime();var order=new ArrayList<Integer>();for(int n=0;n<selectiveFixture.owners();n++)order.add(n);
        Collections.shuffle(order,new Random(SELECTIVE_SEED));
        var setup=new LinkedHashMap<String,Object>();selective.put("sourceSetup",setup);
        setup.put("insertionOwnerOrderHash",hash(order.toString()));
        setup.put("allocation","owners0.."+(selectiveFixture.octoberOwners()-1)+" Oct(day=1+(owner%10)); "+selectiveFixture.octoberOwners()+
            ".."+(selectiveFixture.novemberStart()-1)+" Sep; "+selectiveFixture.novemberStart()+".."+(selectiveFixture.owners()-1)+" Nov; Sep/Nov day=1+(offset%27)");
        setup.put("idFormat","sel-%08d-s0..s3; source sel-%08d-o1/o2/r1/r2/r3/oi/ri");
        int eligibleBatches=0;int otherBatches=0;
        try {
            for(int first=0;first<selectiveFixture.owners();first+=100) {
                checkTime();var settlements=new ArrayList<Object>();var sources=new ArrayList<Object>();
                int end=Math.min(first+100,selectiveFixture.owners());
                for(int index=first;index<end;index++) {
                    int owner=order.get(index);String prefix=selectivePrefix(owner);String businessOrder=selectiveOrder(owner);
                    for(int row=0;row<4;row++) {
                        String id=prefix+"-s"+row;
                        Collections.addAll(settlements,id,COMPANY.organizationId(),COMPANY.userId(),shop,
                            owner%2==0?"SHOPEE":"TIKTOK_SHOP",id,businessOrder,id,
                            java.sql.Date.valueOf(selectiveFirstDay(owner,selectiveFixture).plusDays(row/2)),row%2==0?"USD":"PHP");
                    }
                    appendSourceValues(sources,COMPANY,shop,businessOrder,prefix);
                }
                int batchOwners=end-first;
                db.update("""
                    INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,
                      category,status,record_date,currency,source,raw_json) VALUES
                    """+String.join(",",Collections.nCopies(4*batchOwners,"(?,?,?,?,?,'SETTLEMENT',?,?,?,'ORDER','SETTLED',?,?,'API','{}')")),settlements.toArray());
                eligibleBatches++;insertSources(batchOwners,sources);otherBatches++;
            }
        }finally {
            setup.put("elapsedNanos",System.nanoTime()-started);setup.put("eligibleInsertBatches",eligibleBatches);
            setup.put("otherSourceInsertBatches",otherBatches);setup.put("maxOwnersPerBatch",100);
            setup.put("maxEligiblePerBatch",400);setup.put("maxOtherRowsPerBatch",700);
        }
    }
    void initializeSelectiveFacts() throws Exception {
        var record=new LinkedHashMap<String,Object>();selective.put("realInitialization",record);
        record.put("owners",selectiveFixture.owners());record.put("maxInitializationBatches",selectiveFixture.maxInitializationBatches());
        record.put("maxWorkerTicks",selectiveFixture.maxWorkerTicks());
        var init=new FinanceFirstSettlementInitialization(repo);var facts=new FinanceFirstSettlementFacts(repo);
        long started=System.nanoTime();init.discoverCompanies(8);
        var guard=facts.guard(COMPANY.organizationId()).orElseThrow();
        record.put("discoverNanos",System.nanoTime()-started);started=System.nanoTime();
        int seedBatches=0;int requeueBatches=0;int seedVisited=0;int requeueVisited=0;boolean seeded=false;boolean requeued=false;
        for(int n=0;n<selectiveFixture.maxInitializationBatches();n++) {checkTime();var batch=init.seed(guard,64);seedBatches++;seedVisited+=batch.visited();if(batch.complete()){seeded=true;break;}}
        record.put("seedNanos",System.nanoTime()-started);record.put("seedBatches",seedBatches);record.put("seedVisited",seedVisited);
        assertTrue(seeded);started=System.nanoTime();
        for(int n=0;n<selectiveFixture.maxInitializationBatches();n++) {checkTime();var batch=init.requeue(guard,64);requeueBatches++;requeueVisited+=batch.visited();if(batch.complete()){requeued=true;break;}}
        record.put("requeueNanos",System.nanoTime()-started);record.put("requeueBatches",requeueBatches);record.put("requeueVisited",requeueVisited);
        assertTrue(requeued);started=System.nanoTime();assertTrue(init.complete(guard));record.put("completeNanos",System.nanoTime()-started);
        assertEquals(selectiveFixture.owners(),pending(COMPANY));
        if(gateDirectory==null)gateDirectory=Files.createTempDirectory("finance-date-bulk-gate-");
        var constructor=MaintenanceGate.class.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
        var gate=constructor.newInstance(gateDirectory.resolve("closed"));
        var worker=new FinanceFirstSettlementWorker(facts,init,64,30,8,Duration.ofSeconds(2),System::nanoTime,gate);
        started=System.nanoTime();int ticks=0;int claimed=0;int published=0;int rejected=0;
        try {
            while(pending(COMPANY)>0&&ticks<selectiveFixture.maxWorkerTicks()) {
                checkTime();var tick=worker.tick();ticks++;claimed+=tick.claimed();published+=tick.published();rejected+=tick.rejected();
            }
        }finally {
            record.put("publishNanos",System.nanoTime()-started);record.put("workerTicks",ticks);record.put("claimed",claimed);
            record.put("published",published);record.put("rejected",rejected);record.put("workerBatch",64);
        }
        assertEquals(0,pending(COMPANY));assertEquals(selectiveFixture.owners(),published);assertEquals(0,rejected);assertEquals(0,gate.activeWorkers());
    }
    void verifySelectiveSources(){verifySelectiveSources(0);}
    void verifySelectiveSources(int expectedUnresolved) {
        long started=System.nanoTime();
        var verified=repo.readSnapshot(30,()->{
            var result=new LinkedHashMap<String,Object>();
            var kinds=db.queryForList("SELECT source,kind,COUNT(*) records FROM finance_records WHERE organization_id=? GROUP BY source,kind ORDER BY source,kind",COMPANY.organizationId());
            var counts=new LinkedHashMap<String,Long>();
            for(var row:kinds)counts.put(text(row.get("source"))+":"+text(row.get("kind")),number(row.get("records")));
            long owners=selectiveFixture.owners();
            assertEquals(Map.of("API:SETTLEMENT",owners*4,"API:ORDER",owners*2,"API:REFUND",owners*3,"IMPORT:ORDER",owners,"IMPORT:REFUND",owners),counts);
            result.put("sourceKindCounts",counts);
            long eligibleOwners=db.queryForObject("SELECT COUNT(*) FROM finance_records r WHERE r.organization_id=? AND "+
                FinanceCostReport.firstSettlementIdentity("r",true),Long.class,COMPANY.organizationId());
            assertEquals(owners,eligibleOwners);result.put("eligibleOwners",eligibleOwners);
            String eligible=FinanceCostReport.confirmed("r")+" AND (r.platform<>'TIKTOK_SHOP' OR r.category='ORDER')";
            long eligibleRows=db.queryForObject("SELECT COUNT(*) FROM finance_records r WHERE r.organization_id=? AND "+eligible,Long.class,COMPANY.organizationId());
            long uneven=db.queryForObject("SELECT COUNT(*) FROM (SELECT r.shop_id,r.order_id FROM finance_records r WHERE r.organization_id=? AND "+
                eligible+" GROUP BY r.shop_id,r.order_id HAVING COUNT(*)<>4) per_owner",Long.class,COMPANY.organizationId());
            assertEquals(owners*4,eligibleRows);assertEquals(0,uneven);result.put("eligibleRows",eligibleRows);result.put("ownersWithOtherThan4Eligible",uneven);
            var histogram=db.queryForList("SELECT DATE_FORMAT(r.record_date,'%Y-%m') month,COUNT(*) owners FROM finance_records r WHERE r.organization_id=? AND "+
                FinanceCostReport.firstSettlementIdentity("r",true)+" GROUP BY DATE_FORMAT(r.record_date,'%Y-%m') ORDER BY month",COMPANY.organizationId());
            var months=new LinkedHashMap<String,Long>();for(var row:histogram)months.put(text(row.get("month")),number(row.get("owners")));
            assertEquals(Map.of("2026-09",(long)selectiveFixture.septemberOwners(),"2026-10",(long)selectiveFixture.octoberOwners(),
                "2026-11",(long)selectiveFixture.novemberOwners()),months);result.put("firstMonthOwners",months);
            long missing=db.queryForObject("SELECT COUNT(*) FROM finance_records r LEFT JOIN finance_first_settlement_facts f "+
                "ON f.organization_id=r.organization_id AND f.shop_id=r.shop_id AND f.order_id=r.order_id "+
                "WHERE r.organization_id=? AND "+FinanceCostReport.firstSettlementIdentity("r",true)+" AND f.organization_id IS NULL",Long.class,COMPANY.organizationId());
            long notClean=db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? "+
                "AND NOT(applied_revision=requested_revision AND applied_revision>0 AND schema_version=1 AND applied_generation=1 AND applied_registration_version=1)",Long.class,COMPANY.organizationId());
            long facts=db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=?",Long.class,COMPANY.organizationId());
            assertEquals(0,missing);assertEquals(expectedUnresolved,notClean);assertEquals(owners,facts);
            result.put("eligibleMissing",missing);result.put("unresolved",notClean);result.put("facts",facts);
            return result;
        });
        verified.put("verificationNanos",System.nanoTime()-started);selective.put("independentFixtureSQL",verified);
    }
    @SuppressWarnings("unchecked")
    void applySelectiveMixedMutation() {
        var ids=mixedSettlementIds();var before=mixedSettlementNonDateRows(ids);long started=System.nanoTime();
        var saved=new LinkedHashMap<String,Object>();selective.put("mixedMutation",saved);saved.put("startedNanos",started);
        boolean committed=false;
        try {
            int[] result=repo.transaction(()->{
                int statements=0;int rows=0;
                for(int owner=0;owner<5;owner++) {
                    rows+=updateMixedOwnerDates(owner,LocalDate.of(2026,9,15));statements++;
                }
                for(int owner=10;owner<15;owner++) {
                    rows+=updateMixedOwnerDates(owner,OCT.plusDays(10+owner-10));statements++;
                }
                return new int[]{statements,rows};
            });
            committed=true;saved.put("statements",result[0]);saved.put("updatedRows",result[1]);
            assertEquals(10,result[0]);assertEquals(40,result[1]);
        }finally {saved.put("committed",committed);saved.put("endedNanos",System.nanoTime());saved.put("elapsedNanos",System.nanoTime()-started);}
        var after=mixedSettlementNonDateRows(ids);assertEquals(before,after,"Mixed mutation may change only record_date on the forty eligible rows");
        saved.put("eligibleRows",after.size());saved.put("nonDateHashBefore",hash(before.toString()));saved.put("nonDateHashAfter",hash(after.toString()));
        saved.put("pendingAfterCommit",pending(COMPANY));assertEquals(10,pending(COMPANY));
    }
    int updateMixedOwnerDates(int owner,LocalDate firstDay) {
        String prefix=selectivePrefix(owner);var ids=List.of(prefix+"-s0",prefix+"-s1",prefix+"-s2",prefix+"-s3");
        return repo.jdbc().update("""
            UPDATE finance_records SET record_date=CASE WHEN id IN (?,?) THEN ? ELSE ? END
            WHERE organization_id=? AND kind='SETTLEMENT' AND id IN (?,?,?,?)
            """,ids.get(0),ids.get(1),java.sql.Date.valueOf(firstDay),java.sql.Date.valueOf(firstDay.plusDays(1)),COMPANY.organizationId(),
            ids.get(0),ids.get(1),ids.get(2),ids.get(3));
    }
    List<String> mixedSettlementIds() {
        var result=new ArrayList<String>();for(int owner:List.of(0,1,2,3,4,10,11,12,13,14))for(int row=0;row<4;row++)result.add(selectivePrefix(owner)+"-s"+row);return result;
    }
    List<Map<String,Object>> mixedSettlementNonDateRows(List<String> ids) {
        String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
        return db.queryForList("SELECT id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,currency,source,amount,revenue,raw_json "+
            "FROM finance_records WHERE organization_id=? AND id IN ("+slots+") ORDER BY id",joined(List.of(COMPANY.organizationId()),ids).toArray());
    }
    void verifySelectiveMixedFixture() {
        long started=System.nanoTime();var saved=new LinkedHashMap<String,Object>();selective.put("mixedVerification",saved);
        var q=reader.query(COMPANY,MONTH,"r");var winners=db.queryForList("SELECT r.id,r.order_id,r.record_date FROM finance_records r WHERE "+
            FinanceCostReport.firstSettlementIdentity("r",true)+" AND "+q.where()+" ORDER BY r.record_date DESC,r.id",q.args().toArray());
        assertEquals(expectedSelectiveWinnersFromOrder(),ids(winners));saved.put("octoberWinnerIds",ids(winners));saved.put("octoberWinnerHash",hash(winners.toString()));
        var movedIds=new ArrayList<String>();for(int owner:List.of(0,1,2,3,4,10,11,12,13,14))movedIds.add(selectivePrefix(owner)+"-s0");
        String slots=String.join(",",Collections.nCopies(movedIds.size(),"?"));
        var moved=db.queryForList("SELECT id,record_date FROM finance_records r WHERE organization_id=? AND id IN ("+slots+") AND "+
            FinanceCostReport.firstSettlementIdentity("r",true)+" ORDER BY id",joined(List.of(COMPANY.organizationId()),movedIds).toArray());
        assertEquals(10,moved.size());
        for(var row:moved) {
            int owner=Integer.parseInt(text(row.get("id")).substring(4,12));LocalDate actual=((java.sql.Date)row.get("record_date")).toLocalDate();
            LocalDate expected=owner<5?LocalDate.of(2026,9,15):OCT.plusDays(10+owner-10);assertEquals(expected,actual);assertTrue(text(row.get("id")).endsWith("-s0"));
        }
        String unresolved="pending=TRUE OR NOT(applied_revision=requested_revision AND applied_revision>0 AND schema_version=1 AND applied_generation=1 AND applied_registration_version=1)";
        var actualOrders=db.queryForList("SELECT order_id FROM finance_first_settlement_facts WHERE organization_id=? AND ("+unresolved+") ORDER BY order_id",
            String.class,COMPANY.organizationId());
        var expectedOrders=List.of(selectiveOrder(0),selectiveOrder(1),selectiveOrder(2),selectiveOrder(3),selectiveOrder(4),
            selectiveOrder(10),selectiveOrder(11),selectiveOrder(12),selectiveOrder(13),selectiveOrder(14));
        assertEquals(expectedOrders,actualOrders);
        long matched=db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? AND ("+unresolved+") AND order_id IN ("+
            String.join(",",Collections.nCopies(expectedOrders.size(),"?"))+")",Long.class,joined(List.of(COMPANY.organizationId()),expectedOrders).toArray());
        assertEquals(10,matched);saved.put("unresolvedOrders",actualOrders);saved.put("unresolvedMatchedBySqlCollation",matched);
        saved.put("movedWinnerHash",hash(moved.toString()));saved.put("elapsedNanos",System.nanoTime()-started);
    }
    List<String> expectedSelectiveWinnersFromOrder() {
        return selectiveExpectedOwnerOrder.stream().map(owner->selectivePrefix(owner)+"-s0").toList();
    }
    Map<String,Object> mixedFactsState() {
        var rows=db.queryForList("SELECT organization_id,shop_id,order_id,requested_revision,applied_revision,has_settlement,first_record_id,first_record_date,"+
            "first_platform,first_currency,schema_version,applied_generation,applied_registration_version,queued_generation,lease_token,lease_until,pending "+
            "FROM finance_first_settlement_facts WHERE organization_id=? ORDER BY shop_id,order_id",COMPANY.organizationId());
        var result=new LinkedHashMap<String,Object>();result.put("facts",rows.size());result.put("pending",pending(COMPANY));result.put("hash",hash(rows.toString()));return result;
    }
    void planCleanOnePercent() throws Exception {
        selective.put("seed",SELECTIVE_SEED);selective.put("owners",1000);selective.put("eligiblePerOwner",4);
        selective.put("readerConcurrency",1);selective.put("workerBatch",64);selective.put("cap",64);
        plan.put("profile","plan");plan.put("targetPairs",5);plan.put("maxExplainsPerPair",4);plan.put("maxExplains",20);
        plan.put("statementDiagnosticMillis",5000);plan.put("candidateMillis",BUDGET.candidateMillis());
        plan.put("defaultFixtureBaseline","V160");plan.put("defaultFixtureSha","081b235162d84ce3f3749e761d4a04e79870d2512d07ae78eebb568b4f6b6788");
        plan.put("oldSelectiveSourceSha","a7f23e9830e210615b04bd2786f6b83dbe6a23297c9e225f9a54b2c2fa5d7a2a");
        plan.put("unmeasured",Map.of("statementRowsExamined","NOT_MEASURED_NO_PRIVILEGE_EXPANSION",
            "physicalIO","NOT_MEASURED","globalLockWait","NOT_MEASURED"));
        resetFixture();String shop=groupedShop(COMPANY,"plan-clean-one-percent",null);
        seedSelectiveSources(shop);initializeSelectiveFacts();verifySelectiveSources();
        phaseDeadline=Math.min(hardDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(120));phaseAttempts=0;
        plan.put("phaseStartedNanos",System.nanoTime());plan.put("phaseDeadlineNanos",phaseDeadline);
        try {
            for(String name:List.of("P0","P1","P2","P3","P4"))runPlanPair(name);
            assertEquals(5,planPairs.stream().filter(p->p.completed).count());
            assertEquals(20,explainStatements,"The fixed five-pair profile must issue exactly twenty EXPLAIN statements");
        }finally {
            plan.put("phaseEndedNanos",System.nanoTime());plan.put("explainStatements",explainStatements);
            plan.put("completedPairs",planPairs.stream().filter(p->p.completed).count());
            plan.put("failedPairs",planPairs.stream().filter(p->!p.completed).count());
            plan.put("allOutputsUntruncated",planPairs.stream().flatMap(p->p.outputs.stream()).noneMatch(o->o.truncated));
            plan.put("planClosed",planPairs.size()==5&&planPairs.stream().allMatch(p->p.completed)&&explainStatements==20
                &&planPairs.stream().flatMap(p->p.outputs.stream()).noneMatch(o->o.truncated));
            plan.put("performanceAdmission",false);
        }
    }
    void planOrderIndex() throws Exception {
        orderIndexHintEnabled=true;
        selective.put("seed",SELECTIVE_SEED);selective.put("owners",1000);selective.put("eligiblePerOwner",4);
        selective.put("readerConcurrency",1);selective.put("workerBatch",64);selective.put("cap",64);
        plan.put("profile","plan-order-index");plan.put("targetPairs",2);plan.put("maxExplainsPerPair",4);plan.put("maxExplains",8);
        plan.put("statementDiagnosticMillis",5000);plan.put("candidateMillis",BUDGET.candidateMillis());
        plan.put("onlySqlDifference","candidate o FORCE INDEX(idx_finance_settled_order)");
        plan.put("unmeasured",Map.of("statementRowsExamined","NOT_MEASURED_NO_PRIVILEGE_EXPANSION",
            "physicalIO","NOT_MEASURED","globalLockWait","NOT_MEASURED"));
        resetFixture();String shop=groupedShop(COMPANY,"plan-order-index",null);
        seedSelectiveSources(shop);initializeSelectiveFacts();verifySelectiveSources();
        phaseDeadline=Math.min(hardDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(120));phaseAttempts=0;
        plan.put("phaseStartedNanos",System.nanoTime());plan.put("phaseDeadlineNanos",phaseDeadline);
        try {
            runOrderIndexPlanPair("COUNT");runOrderIndexPlanPair("PAGE");
            assertEquals(2,planPairs.stream().filter(p->p.completed).count());
            assertEquals(8,explainStatements,"The order-index plan must issue exactly eight EXPLAIN statements");
            assertEquals(2,orderIndexHintRelations.get(),"Each diagnostic pair builds exactly one HINTED candidate relation");
        }finally {
            plan.put("phaseEndedNanos",System.nanoTime());plan.put("explainStatements",explainStatements);
            plan.put("completedPairs",planPairs.stream().filter(p->p.completed).count());
            plan.put("failedPairs",planPairs.stream().filter(p->!p.completed).count());
            plan.put("allOutputsUntruncated",planPairs.stream().flatMap(p->p.outputs.stream()).noneMatch(o->o.truncated));
            plan.put("planClosed",planPairs.size()==2&&planPairs.stream().allMatch(p->p.completed)&&explainStatements==8
                &&planPairs.stream().flatMap(p->p.outputs.stream()).noneMatch(o->o.truncated));
            plan.put("performanceAdmission",false);
        }
    }
    void runOrderIndexPlanPair(String name) {
        checkPhase();var a=new Attempt("ORDER_INDEX_PLAN_"+name);attempts.add(a);var pair=new PlanPair(name,a);planPairs.add(pair);current.set(a);
        pair.explainsBefore=explainStatements;pair.rrStartedNanos=System.nanoTime();
        try {
            reader.readSnapshot(30,()->{
                long establishing=System.nanoTime();
                reads.queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=?",Long.class,COMPANY.organizationId());
                pair.establishSnapshotNanos=System.nanoTime()-establishing;a.stage="ORDER_INDEX_PLAN_RESOLVE";
                var options=new Options(COMPANY.organizationId());pair.resolutionTiming.started=System.nanoTime();pathTiming.set(pair.resolutionTiming);DateResolution resolution;
                try {resolution=resolve(COMPANY,MONTH,64,options);}
                finally {pair.resolutionTiming.ended=System.nanoTime();pathTiming.remove();}
                pair.resolutionTiming.route=resolution.route().name();pair.resolutionTiming.reason=resolution.reason();pair.resolutionTiming.completed=true;
                a.route=resolution.route().name();a.reason=resolution.reason();
                assertEquals(DateRoute.DATE_TEST,resolution.route());assertEquals("",resolution.reason());assertEquals(0,a.auditRows);assertEquals(0,a.unresolved);assertEquals(0,a.lookups);
                long validating=System.nanoTime();
                if(name.equals("COUNT"))verifyOrderIndexCount(pair,resolution.relation());
                else if(name.equals("PAGE"))verifyOrderIndexPage(pair,resolution.relation());
                else fail("Unknown order-index pair "+name);
                pair.validationNanos=System.nanoTime()-validating;
                FinanceLabelFirstSettlementFacts.requireUsable(reader);assertTrue(a.sameSnapshot);assertEquals(1,a.borrows);
                assertEquals(4,explainStatements-pair.explainsBefore);assertEquals(1,a.hintedCandidateRelations);
                return null;
            });
            pair.completed=true;a.completed=true;a.returnedData=1;
        }catch(RuntimeException|Error failure){pair.failure=failure.getClass().getSimpleName();a.failure=pair.failure;throw failure;}
        finally {pair.explainsAfter=explainStatements;pair.rrEndedNanos=System.nanoTime();pathTiming.remove();current.remove();}
    }
    void verifyOrderIndexCount(PlanPair pair,Relation relation) {
        var currentRelations=orderRelations(relation,false);var hintedRelations=orderRelations(relation,true);
        assertOnlyOrderIndexDifference(currentRelations,hintedRelations);
        var nativeShape=new SqlShape("native-order-count","SELECT COUNT(*)"+currentRelations.nativeSql(),currentRelations.nativeArgs());
        var currentShape=new SqlShape("current-order-count","SELECT COUNT(*)"+currentRelations.candidateSql(),currentRelations.candidateArgs());
        var hintedShape=new SqlShape("hinted-order-count","SELECT COUNT(*)"+hintedRelations.candidateSql(),hintedRelations.candidateArgs());
        long nativeCount=planLong(pair,"native-count",nativeShape);long currentCount=planLong(pair,"current-count",currentShape);
        long hintedCount=planLong(pair,"hinted-count",hintedShape);
        assertEquals(20,nativeCount);assertEquals(nativeCount,currentCount);assertEquals(currentCount,hintedCount);
        pair.verification.put("nativeCount",nativeCount);pair.verification.put("currentCount",currentCount);pair.verification.put("hintedCount",hintedCount);
        saveOrderIndexShapes(pair,currentRelations,hintedRelations,nativeShape,currentShape,hintedShape);explainBoth(pair,currentShape,hintedShape);
    }
    void verifyOrderIndexPage(PlanPair pair,Relation relation) {
        var currentRelations=orderRelations(relation,false);var hintedRelations=orderRelations(relation,true);
        assertOnlyOrderIndexDifference(currentRelations,hintedRelations);
        var nativeShape=new SqlShape("native-order-page",settledProjection()+currentRelations.nativeSql()+
            " ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?",joined(currentRelations.nativeArgs(),List.of(50,0)));
        var currentShape=new SqlShape("current-order-page",settledProjection()+currentRelations.candidateSql()+
            " ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?",joined(currentRelations.candidateArgs(),List.of(50,0)));
        var hintedShape=new SqlShape("hinted-order-page",settledProjection()+hintedRelations.candidateSql()+
            " ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?",joined(hintedRelations.candidateArgs(),List.of(50,0)));
        var nativeRows=planRows(pair,"native-page",nativeShape);var currentRows=planRows(pair,"current-page",currentShape);
        var hintedRows=planRows(pair,"hinted-page",hintedShape);
        assertEquals(20,nativeRows.size());assertEquals(nativeRows,currentRows);assertEquals(currentRows,hintedRows);
        pair.verification.put("rows",nativeRows.size());pair.verification.put("page",1);pair.verification.put("pageSize",50);
        pair.verification.put("nativeHash",hash(nativeRows.toString()));pair.verification.put("currentHash",hash(currentRows.toString()));
        pair.verification.put("hintedHash",hash(hintedRows.toString()));
        saveOrderIndexShapes(pair,currentRelations,hintedRelations,nativeShape,currentShape,hintedShape);explainBoth(pair,currentShape,hintedShape);
    }
    void assertOnlyOrderIndexDifference(SettledRelations currentRelations,SettledRelations hintedRelations) {
        assertEquals(currentRelations.nativeSql(),hintedRelations.nativeSql());assertEquals(currentRelations.nativeArgs(),hintedRelations.nativeArgs());
        assertEquals(currentRelations.candidateArgs(),hintedRelations.candidateArgs());
        assertEquals(currentRelations.candidateSql().replace("JOIN finance_records o ON","JOIN finance_records o FORCE INDEX(idx_finance_settled_order) ON"),
            hintedRelations.candidateSql());
    }
    void saveOrderIndexShapes(PlanPair pair,SettledRelations currentRelations,SettledRelations hintedRelations,SqlShape nativeShape,SqlShape currentShape,SqlShape hintedShape) {
        pair.verification.put("nativeSql",nativeShape.sql());pair.verification.put("nativeSqlHash",hash(nativeShape.sql()));
        pair.verification.put("currentSql",currentShape.sql());pair.verification.put("currentSqlHash",hash(currentShape.sql()));
        pair.verification.put("hintedSql",hintedShape.sql());pair.verification.put("hintedSqlHash",hash(hintedShape.sql()));
        pair.verification.put("nativeArgs",nativeShape.args());pair.verification.put("currentArgs",currentShape.args());pair.verification.put("hintedArgs",hintedShape.args());
        pair.verification.put("onlyDifference","FORCE INDEX(idx_finance_settled_order)");
    }
    void runPlanPair(String name) {
        checkPhase();var a=new Attempt("PLAN_"+name);attempts.add(a);var pair=new PlanPair(name,a);planPairs.add(pair);current.set(a);
        pair.explainsBefore=explainStatements;pair.rrStartedNanos=System.nanoTime();
        try {
            reader.readSnapshot(30,()->{
                long establishing=System.nanoTime();
                reads.queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=?",Long.class,COMPANY.organizationId());
                pair.establishSnapshotNanos=System.nanoTime()-establishing;a.stage="PLAN_RESOLVE";
                var capture=new PlanCapture();var options=new Options(COMPANY.organizationId());options.planCapture=capture;
                pair.resolutionTiming.started=System.nanoTime();pathTiming.set(pair.resolutionTiming);DateResolution resolution;
                try {resolution=resolve(COMPANY,MONTH,64,options);}
                finally {pair.resolutionTiming.ended=System.nanoTime();pathTiming.remove();}
                pair.resolutionTiming.route=resolution.route().name();pair.resolutionTiming.reason=resolution.reason();pair.resolutionTiming.completed=true;
                a.route=resolution.route().name();a.reason=resolution.reason();
                assertEquals(DateRoute.DATE_TEST,resolution.route(),name+" requires the fixed clean DATE relation");assertEquals("",resolution.reason());
                assertSame(capture.relation,resolution.relation());assertEquals(0,a.auditRows);assertEquals(0,a.unresolved);assertEquals(0,a.lookups);
                long validating=System.nanoTime();
                switch(name) {
                    case "P0" -> verifyPlanP0(pair,capture);
                    case "P1" -> verifyPlanP1(pair,capture);
                    case "P2" -> verifyPlanP2(pair,resolution.relation());
                    case "P3" -> verifyPlanP3(pair,resolution.relation());
                    case "P4" -> verifyPlanP4(pair,resolution.relation());
                    default -> fail("Unknown plan pair "+name);
                }
                pair.validationNanos=System.nanoTime()-validating;
                FinanceLabelFirstSettlementFacts.requireUsable(reader);assertTrue(a.sameSnapshot);assertEquals(1,a.borrows);
                assertEquals(4,explainStatements-pair.explainsBefore,name+" must issue exactly four EXPLAIN statements");
                return null;
            });
            pair.completed=true;a.completed=true;a.returnedData=1;
        }catch(RuntimeException|Error failure){pair.failure=failure.getClass().getSimpleName();a.failure=pair.failure;throw failure;}
        finally {pair.explainsAfter=explainStatements;pair.rrEndedNanos=System.nanoTime();pathTiming.remove();current.remove();}
    }
    void verifyPlanP0(PlanPair pair,PlanCapture capture) {
        assertNotNull(capture.audit);assertNotNull(capture.unresolved);assertEquals(0,capture.auditRows.size());assertEquals(0,capture.unresolvedRows.size());
        pair.verification.put("auditRows",capture.auditRows.size());pair.verification.put("unresolvedRows",capture.unresolvedRows.size());
        pair.verification.put("comparable",false);pair.verification.put("reason","INTEGRITY_WORK_SHAPES_NOT_EQUIVALENT");
        explainBoth(pair,capture.audit,capture.unresolved);
    }
    void verifyPlanP1(PlanPair pair,PlanCapture capture) {
        assertNotNull(capture.dateForced);assertNotNull(capture.dateNatural);
        var forced=planRows(pair,"forced-clean-date",capture.dateForced);var natural=planRows(pair,"natural-clean-date",capture.dateNatural);
        var forcedIds=new TreeSet<>(ids(forced));var naturalIds=new TreeSet<>(ids(natural));
        var expected=new TreeSet<String>();for(int n=0;n<10;n++)expected.add(selectivePrefix(n)+"-s0");
        assertEquals(expected,forcedIds);assertEquals(forcedIds,naturalIds);
        pair.verification.put("forcedRows",forced.size());pair.verification.put("naturalRows",natural.size());
        pair.verification.put("identitySetHash",hash(forcedIds.toString()));pair.verification.put("equal",true);
        explainBoth(pair,capture.dateForced,capture.dateNatural);
    }
    void verifyPlanP2(PlanPair pair,Relation relation) {
        var q=reader.query(COMPANY,MONTH,"r");
        var nativeShape=new SqlShape("native-first-identity","SELECT "+exactProjection("r")+" FROM finance_records r WHERE "+
            FinanceCostReport.firstSettlementIdentity("r",true)+" AND "+q.where()+" ORDER BY r.record_date DESC,r.id",q.args());
        var candidateShape=new SqlShape("candidate-rExact-identity","SELECT "+exactProjection("r")+" FROM ("+relation.sql()+") r WHERE "+
            q.where()+" ORDER BY r.record_date DESC,r.id",joined(relation.args(),q.args()));
        var nativeRows=planRows(pair,"native-identity",nativeShape);var candidateRows=planRows(pair,"candidate-identity",candidateShape);
        assertEquals(expectedSelectiveWinners(SELECTIVE_1K),ids(nativeRows));assertEquals(nativeRows,candidateRows);
        pair.verification.put("rows",nativeRows.size());pair.verification.put("nativeHash",hash(nativeRows.toString()));
        pair.verification.put("candidateHash",hash(candidateRows.toString()));pair.verification.put("equal",true);
        explainBoth(pair,nativeShape,candidateShape);
    }
    void verifyPlanP3(PlanPair pair,Relation relation) {
        var relations=orderRelations(relation);
        var nativeShape=new SqlShape("native-order-count","SELECT COUNT(*)"+relations.nativeSql(),relations.nativeArgs());
        var candidateShape=new SqlShape("candidate-order-count","SELECT COUNT(*)"+relations.candidateSql(),relations.candidateArgs());
        long nativeCount=planLong(pair,"native-count",nativeShape),candidateCount=planLong(pair,"candidate-count",candidateShape);
        assertEquals(20,nativeCount);assertEquals(nativeCount,candidateCount);
        pair.verification.put("nativeCount",nativeCount);pair.verification.put("candidateCount",candidateCount);pair.verification.put("equal",true);
        explainBoth(pair,nativeShape,candidateShape);
    }
    void verifyPlanP4(PlanPair pair,Relation relation) {
        var relations=orderRelations(relation);
        var nativeShape=new SqlShape("native-order-page",settledProjection()+relations.nativeSql()+
            " ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?",joined(relations.nativeArgs(),List.of(50,0)));
        var candidateShape=new SqlShape("candidate-order-page",settledProjection()+relations.candidateSql()+
            " ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?",joined(relations.candidateArgs(),List.of(50,0)));
        var nativeRows=planRows(pair,"native-page",nativeShape);var candidateRows=planRows(pair,"candidate-page",candidateShape);
        assertEquals(20,nativeRows.size());assertEquals(nativeRows,candidateRows);
        var expected=new ArrayList<String>();for(int owner=9;owner>=0;owner--)for(String suffix:List.of("o1","o2"))expected.add(selectivePrefix(owner)+"-"+suffix);
        assertEquals(expected,ids(nativeRows));
        pair.verification.put("rows",nativeRows.size());pair.verification.put("page",1);pair.verification.put("pageSize",50);
        pair.verification.put("nativeHash",hash(nativeRows.toString()));pair.verification.put("candidateHash",hash(candidateRows.toString()));pair.verification.put("equal",true);
        explainBoth(pair,nativeShape,candidateShape);
    }
    SettledRelations orderRelations(Relation relation) {return orderRelations(relation,false);}
    SettledRelations orderRelations(Relation relation,boolean hinted) {
        var q=reader.query(COMPANY,MONTH,"r");var selected=FinanceRecordSearch.apply(q,"o","",false);
        String nativeSql=reader.settledOrderRelation(COMPANY,MONTH,"ORDER",false)+selected.where().substring(q.where().length());
        String candidateSql=candidateSettledRelation(relation,selected,"ORDER",false,hinted);
        return new SettledRelations(nativeSql,selected.args(),candidateSql,joined(relation.args(),selected.args()));
    }
    static List<Integer> expectedSelectiveOwners(SelectiveFixture fixture){
        var expected=new ArrayList<Integer>();
        for(int dayOffset=9;dayOffset>=0;dayOffset--)for(int owner=dayOffset;owner<fixture.octoberOwners();owner+=10)expected.add(owner);
        return List.copyOf(expected);
    }
    static List<String> expectedSelectiveWinners(SelectiveFixture fixture){
        return expectedSelectiveOwners(fixture).stream().map(owner->selectivePrefix(owner)+"-s0").toList();
    }
    void explainBoth(PlanPair pair,SqlShape first,SqlShape second) {
        explain(pair,first,"JSON");explain(pair,first,"ANALYZE");explain(pair,second,"JSON");explain(pair,second,"ANALYZE");
    }
    void explain(PlanPair pair,SqlShape shape,String format) {
        if(++explainStatements>20)fail("Plan profile exceeded its fixed twenty-EXPLAIN ceiling");
        var output=new PlanOutput(shape.name(),format,shape);pair.outputs.add(output);output.startedNanos=System.nanoTime();
        try {
            String prefix=format.equals("JSON")?"EXPLAIN FORMAT=JSON ":"EXPLAIN ANALYZE ";
            var lines=planSelect(new SqlShape(shape.name(),prefix+shape.sql(),shape.args()),(r,n)->r.getString(1));
            String full=String.join("\n",lines);output.originalChars=full.length();output.truncated=full.length()>100000;
            output.output=output.truncated?full.substring(0,100000):full;output.storedChars=output.output.length();output.outputHash=hash(full);output.completed=true;
        }catch(RuntimeException|Error failure){output.failure=failure.getClass().getSimpleName();throw failure;}
        finally {output.endedNanos=System.nanoTime();}
    }
    List<Map<String,Object>> planRows(PlanPair pair,String label,SqlShape shape){return planRead(pair,label,shape,new org.springframework.jdbc.core.ColumnMapRowMapper());}
    long planLong(PlanPair pair,String label,SqlShape shape){return planRead(pair,label,shape,(r,n)->r.getLong(1)).getFirst();}
    <T> List<T> planRead(PlanPair pair,String label,SqlShape shape,RowMapper<T> mapper) {
        long started=System.nanoTime();var saved=new LinkedHashMap<String,Object>();saved.put("label",label);saved.put("sqlHash",hash(shape.sql()));
        try {
            var result=planSelect(shape,mapper);saved.put("completed",true);saved.put("rows",result.size());saved.put("rowsHash",hash(result.toString()));return result;
        }catch(RuntimeException|Error failure){saved.put("completed",false);saved.put("failure",failure.getClass().getSimpleName());throw failure;}
        finally {saved.put("elapsedNanos",System.nanoTime()-started);pair.ordinaryReads.add(saved);}
    }
    <T> List<T> planSelect(SqlShape shape,RowMapper<T> mapper) {
        checkTime();if(System.nanoTime()>=phaseDeadline)throw new IllegalStateException("Plan phase exhausted its fixed 120-second deadline");
        return reads.execute(connection->{
            var statement=connection.prepareStatement(shape.sql());
            for(int n=0;n<shape.args().size();n++)StatementCreatorUtils.setParameterValue(statement,n+1,SqlTypeValue.TYPE_UNKNOWN,shape.args().get(n));
            return statement;
        },(PreparedStatementCallback<List<T>>)statement->{
            boundPlanStatement(statement);
            try(var rows=statement.executeQuery()){
                var result=new ArrayList<T>();for(int n=0;rows.next();n++)result.add(mapper.mapRow(rows,n));return result;
            }
        });
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    void boundPlanStatement(PreparedStatement statement)throws SQLException {
        FinanceLabelFirstSettlementFacts.requireUsable(reader);
        long millis=Math.min(5000,Math.min(holder().getTimeToLiveInMillis(),Math.min(
            TimeUnit.NANOSECONDS.toMillis(Math.max(0,phaseDeadline-System.nanoTime())),
            TimeUnit.NANOSECONDS.toMillis(Math.max(0,hardDeadline-System.nanoTime())))));
        if(millis<1)throw new IllegalStateException("No time remains for the bounded diagnostic statement");
        try {
            Class type=Class.forName("com.mysql.cj.jdbc.JdbcStatement");Object query=type.getMethod("getQuery").invoke(statement.unwrap(type));
            Class queryType=Class.forName("com.mysql.cj.Query");long existing=((Number)queryType.getMethod("getTimeoutInMillis").invoke(query)).longValue();
            if(existing>0)millis=Math.min(millis,existing);queryType.getMethod("setTimeoutInMillis",long.class).invoke(query,millis);
            assertTrue(FinanceFirstSettlementLoadIntegrationTest.mysqlMillis(statement)<=5000);
        }catch(ReflectiveOperationException failure){throw new SQLException("Cannot bound isolated plan diagnostic SELECT",failure);}
    }
    Map<String,Object> selectiveTimingSummary() {
        var completed=pairs.stream().filter(p->p.completed).toList();
        var date=completed.stream().filter(p->p.candidatePath.route.equals("DATE_TEST")).toList();
        var r=new LinkedHashMap<String,Object>();r.put("attemptedPairs",pairs.size());r.put("completedPairs",completed.size());
        r.put("trueDatePairs",date.size());r.put("candidateNativeFallbacks",completed.size()-date.size());
        r.put("failedPairs",pairs.size()-completed.size());r.put("candidatePathExercised",!date.isEmpty());
        r.put("cancelRequestedPairs",pairs.stream().filter(p->p.cancelRequested).count());
        r.put("latePairs",pairs.stream().filter(p->p.lateCompletion).count());
        r.put("sqlTimeoutPairs",pairs.stream().filter(p->p.attempt.errors.stream().anyMatch(e->text(e.get("driverException")).contains("Timeout"))).count());
        r.put("nativeAll",ranks(completed.stream().map(p->p.nativePath.total()).toList()));
        r.put("candidateAllIncludingFallback",ranks(completed.stream().map(p->p.candidatePath.total()).toList()));
        r.put("candidateTrueDateOnly",ranks(date.stream().map(p->p.candidatePath.total()).toList()));
        r.put("matchedNativeForTrueDate",ranks(date.stream().map(p->p.nativePath.total()).toList()));
        r.put("rrEnvelope",ranks(completed.stream().map(p->p.rrEndedNanos-p.rrStartedNanos).toList()));
        r.put("percentileRule","nearest-rank: sorted[ceil(p*n)-1], p50=.50 p95=.95; nanoseconds");
        r.put("performanceAdmission",false);r.put("sufficientCompletedPairs",completed.size()>=30);
        r.put("sufficientTrueDatePairs",date.size()>=30);r.put("cacheAndOrderLimit","sequential isolated, alternating first path; no cold-cache control");
        return r;
    }
    static Map<String,Object> ranks(List<Long> input) {
        if(input.isEmpty())return Map.of("samples",0,"status","NO_COMPLETED_SAMPLES");
        var sorted=new ArrayList<>(input);Collections.sort(sorted);int n=sorted.size();
        return Map.of("samples",n,"p50Nanos",sorted.get((int)Math.ceil(.50*n)-1),
            "p95Nanos",sorted.get((int)Math.ceil(.95*n)-1),"maxNanos",sorted.getLast());
    }

    static final List<String> TIMED_OPERATIONS=List.of("proof","sourceAudit","unresolved","lookup","identity","count","pages","bag","otherRead");
    static final class PathTiming {
        final String name;
        final Map<String,Long> nanos=new LinkedHashMap<>();
        final Map<String,Integer> executed=new LinkedHashMap<>();
        long started,ended,resolutionNanos,relationBuildNanos;
        boolean completed;
        String route="",reason="",failure="";
        PathTiming(String name){this.name=name;}
        long total(){return started==0||ended==0?0:ended-started;}
        long selects(){return executed.values().stream().mapToLong(Integer::longValue).sum();}
        Map<String,Object> receipt(){
            var r=new LinkedHashMap<String,Object>();r.put("path",name);r.put("startedNanos",started);r.put("endedNanos",ended);
            r.put("totalNanos",total());r.put("completed",completed);r.put("route",route);r.put("reason",reason);r.put("failure",failure);
            var operations=new LinkedHashMap<String,Object>();
            for(String op:TIMED_OPERATIONS)operations.put(op,Map.of("selectCallNanos",nanos.getOrDefault(op,0L),"executedQueries",executed.getOrDefault(op,0)));
            long queryNanos=nanos.values().stream().mapToLong(Long::longValue).sum();
            long resolveQueryNanos=List.of("proof","sourceAudit","unresolved","lookup").stream().mapToLong(op->nanos.getOrDefault(op,0L)).sum();
            r.put("operations",operations);r.put("executedQueries",selects());r.put("resolutionNanos",resolutionNanos);
            r.put("relationBuildNanos",relationBuildNanos);
            r.put("resolveOtherJavaNanos",Math.max(0,resolutionNanos-resolveQueryNanos-relationBuildNanos));
            r.put("otherJavaNanos",total()-queryNanos-relationBuildNanos);
            r.put("segmentNanosSum",queryNanos+relationBuildNanos+(total()-queryNanos-relationBuildNanos));
            return r;
        }
    }
    static final class PairSample {
        final int number;
        final String order;
        final Attempt attempt;
        final PathTiming nativePath=new PathTiming("NATIVE"),candidatePath=new PathTiming("CANDIDATE");
        long dispatchedNanos,rrStartedNanos,rrEndedNanos,validationNanos,establishSnapshotNanos,phaseDeadlineNanos,waitDeadlineNanos,deadlineDetectionLagNanos;
        int pendingBeforePair=-1;
        boolean completed,executionCompleted,equal,cancelRequested,lateCompletion;
        String failure="",waitFailure="",nativeHash="",candidateHash="";
        FutureTask<PairSample> future;
        PairSample(int number,Attempt attempt){this.number=number;this.attempt=attempt;order=number%2==0?"NATIVE_THEN_CANDIDATE":"CANDIDATE_THEN_NATIVE";}
        Map<String,Object> receipt(){
            var r=new LinkedHashMap<String,Object>();r.put("pair",number+1);r.put("order",order);r.put("completed",completed);r.put("equal",equal);
            r.put("dispatchedNanos",dispatchedNanos);r.put("rrStartedNanos",rrStartedNanos);r.put("rrEndedNanos",rrEndedNanos);
            r.put("rrEnvelopeNanos",rrStartedNanos==0||rrEndedNanos==0?0:rrEndedNanos-rrStartedNanos);
            r.put("establishSnapshotNanos",establishSnapshotNanos);r.put("validationNanos",validationNanos);
            r.put("pendingBeforePair",pendingBeforePair);
            r.put("extraOracleNanos",0);r.put("extraOracleQueries",0);
            r.put("phaseDeadlineNanos",phaseDeadlineNanos);r.put("deadlineDetectionLagNanos",deadlineDetectionLagNanos);
            r.put("waitDeadlineNanos",waitDeadlineNanos);r.put("executionCompleted",executionCompleted);
            r.put("cancelRequested",cancelRequested);r.put("lateCompletion",lateCompletion);r.put("waitFailure",waitFailure);
            r.put("futureDone",future!=null&&future.isDone());r.put("futureCancelled",future!=null&&future.isCancelled());
            r.put("native",nativePath.receipt());r.put("candidate",candidatePath.receipt());r.put("failure",failure);
            r.put("nativeRowsHash",nativeHash);r.put("candidateRowsHash",candidateHash);
            r.put("physicalSnapshot",attempt.receipt());return r;
        }
    }
    static String timedOperation(String original) {
        String sql=original.stripLeading();
        if(sql.startsWith("SELECT schema_version,registration_version")||sql.startsWith("SELECT organization_id,generation,target_schema_version"))return "proof";
        if(sql.startsWith("SELECT DISTINCT r.organization_id,r.shop_id,r.order_id"))return "sourceAudit";
        if(sql.startsWith("SELECT f.organization_id,f.shop_id,f.order_id FROM finance_first_settlement_facts")
            ||sql.contains(") unresolved LIMIT ?"))return "unresolved";
        if(sql.contains("FORCE INDEX(idx_finance_first_settlement)")&&sql.startsWith("SELECT r.id"))return "lookup";
        if(sql.startsWith("SELECT COUNT(*)"))return "count";
        if(sql.contains("ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?"))return "pages";
        if(sql.startsWith("SELECT r.id,r.organization_id,r.shop_id,r.order_id"))return "identity";
        if(sql.startsWith("SELECT r.id winner_id,o.id"))return "bag";
        return "otherRead";
    }
    PairSample measuredPair(PairSample pair) {
        var a=pair.attempt;current.set(a);pair.rrStartedNanos=System.nanoTime();
        try {
            reader.readSnapshot(30,()->{
                long establishing=System.nanoTime();
                reads.queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=?",Long.class,COMPANY.organizationId());
                pair.establishSnapshotNanos=System.nanoTime()-establishing;
                if(expectedPendingBeforePair>=0){pair.pendingBeforePair=Math.toIntExact(pending(COMPANY,reader));assertEquals(expectedPendingBeforePair,pair.pendingBeforePair);}
                Data nativeRows,candidateRows;
                if(pair.number%2==0) {
                    nativeRows=measureNative(pair.nativePath);candidateRows=measureCandidate(pair.candidatePath);
                }else {
                    candidateRows=measureCandidate(pair.candidatePath);nativeRows=measureNative(pair.nativePath);
                }
                long validating=System.nanoTime();
                assertSelectiveData(nativeRows);assertSelectiveData(candidateRows);
                assertEquals(nativeRows,candidateRows,"Both independent original relations and the full candidate contract must match");
                assertTrue(a.sameSnapshot);assertEquals(1,a.borrows);FinanceLabelFirstSettlementFacts.requireUsable(reader);
                if(pair.candidatePath.route.equals("DATE_TEST")) {
                    assertEquals(0,a.auditRows);assertEquals(expectedSelectiveUnresolved,a.unresolved);assertEquals(expectedSelectiveUnresolved,a.lookups);assertTrue(a.dateStatements>0);
                }
                for(var t:List.of(pair.nativePath,pair.candidatePath)) {
                    assertTrue(t.total()>=t.nanos.values().stream().mapToLong(Long::longValue).sum()+t.relationBuildNanos);
                }
                pair.nativeHash=hash(nativeRows.toString());pair.candidateHash=hash(candidateRows.toString());
                pair.equal=true;pair.validationNanos=System.nanoTime()-validating;
                return null;
            });
            pair.executionCompleted=true;
            return pair;
        }catch(RuntimeException|Error failure){pair.failure=failure.getClass().getSimpleName();a.failure=pair.failure;throw failure;}
        finally {pair.rrEndedNanos=System.nanoTime();pathTiming.remove();current.remove();}
    }
    Data measureNative(PathTiming t) {
        pathTiming.set(t);t.started=System.nanoTime();t.route="NATIVE";
        try {var result=originalData(COMPANY,MONTH,"");t.completed=true;return result;}
        catch(RuntimeException|Error failure){t.failure=failure.getClass().getSimpleName();throw failure;}
        finally {t.ended=System.nanoTime();pathTiming.remove();}
    }
    Data measureCandidate(PathTiming t) {
        pathTiming.set(t);t.started=System.nanoTime();
        try {
            long resolving=System.nanoTime();DateResolution r;
            try {r=resolve(COMPANY,MONTH,64,new Options(COMPANY.organizationId()));}
            finally {t.resolutionNanos=System.nanoTime()-resolving;}
            t.route=r.route().name();t.reason=r.reason();current.get().route=t.route;current.get().reason=t.reason;
            Data result;
            try {result=data(COMPANY,MONTH,r.relation(),r.budget(),"",false);}
            catch(LocalExit proactive){r=nativeResolution("LOCAL_BUDGET",r.unresolved());t.route=r.route().name();t.reason=r.reason();current.get().route=t.route;current.get().reason=t.reason;result=data(COMPANY,MONTH,r.relation(),null,"",false);}
            t.completed=true;return result;
        }catch(RuntimeException|Error failure){t.failure=failure.getClass().getSimpleName();throw failure;}
        finally {t.ended=System.nanoTime();pathTiming.remove();}
    }
    void assertSelectiveData(Data data) {
        var ownerOrder=selectiveExpectedOwnerOrder.isEmpty()?expectedSelectiveOwners(selectiveFixture):selectiveExpectedOwnerOrder;
        var expectedWinners=ownerOrder.stream().map(owner->selectivePrefix(owner)+"-s0").toList();
        assertEquals(expectedWinners,ids(data.identities()));assertEquals(selectiveFixture.octoberOwners(),data.identities().size());
        for(String kind:List.of("ORDER","REFUND"))for(boolean normal:List.of(false,true)) {
            var s=data.settled().get(kind+":"+normal);assertEquals((long)selectiveFixture.octoberOwners()*(kind.equals("ORDER")?2:3),s.count());
            assertEquals(s.bag().size(),s.count());
            var expectedIds=new ArrayList<String>();
            for(int owner:ownerOrder)for(String suffix:kind.equals("ORDER")?List.of("o1","o2"):List.of("r1","r2","r3"))
                expectedIds.add(selectivePrefix(owner)+"-"+suffix);
            assertEquals(expectedIds,ids(s.bag()));
            long[] offsets={0,1,Math.max(0,s.count()-1),s.count()+1};
            for(int page=0;page<offsets.length;page++) {
                long offset=offsets[page];
                assertEquals(s.bag().subList((int)Math.min(offset,s.bag().size()),(int)Math.min(offset+3,s.bag().size())),s.pages().get(page));
            }
            assertEquals(selectiveFixture.octoberOwners(),s.bag().stream().filter(row->row.get("amount")==null).count());
            assertEquals(selectiveFixture.octoberOwners(),s.bag().stream().filter(row->row.get("amount")!=null&&decimal(row.get("amount")).signum()==0).count());
            if(kind.equals("ORDER"))assertEquals(selectiveFixture.octoberOwners(),s.bag().stream().filter(row->"CANCELLED".equals(row.get("status"))).count());
            else assertEquals(selectiveFixture.octoberOwners(),s.bag().stream().filter(row->new BigDecimal("-1.234567").equals(row.get("amount"))).count());
        }
    }

    void snapshotAddsAndTransfersBeforeCoverage() throws Exception {
        {
            resetFixture();String shop=groupedShop(COMPANY,"snapshot-add",null);
            settlement("snapshot-add-base",shop,"base",OCT.plusDays(2));addSources(COMPANY,shop,"base","snapshot-add-base");
            addSources(COMPANY,shop,"new","snapshot-add-new");initializeAndPublish(COMPANY);
            var options=new Options(COMPANY.organizationId());SnapshotWriter[] writer={null};
            options.afterSnapshot=()->writer[0]=atBoundary("S1_add_after_source_before_proof",w->{
                commitSettlement(w,"snapshot-add-new",shop,"new",OCT.plusDays(7));
                assertEquals(1,pending(COMPANY,reader));
                freshSourceWitness(w,MONTH,List.of("snapshot-add-new","snapshot-add-base"));
            });
            var old=probe("S1_add_old_S",COMPANY,MONTH,0,options,DateRoute.DATE_TEST,"",List.of("snapshot-add-base"),"");
            assertSingleOwnerRows(old,"snapshot-add-base");assertEquals(0,old.attempt().unresolved);
            assertNotEquals(old.data().identities(),writer[0].freshRows,"A wrong fresh/reopened S would include B's committed owner");
            evidence.add(Map.of("case","S1_real_fresh_snapshot_negative_control","oldIds",ids(old.data().identities()),
                "freshIds",ids(writer[0].freshRows),"actuallyDifferent",true));
            var expected=List.of("snapshot-add-new","snapshot-add-base");
            var cap0=probe("S1_add_next_cap0",MONTH,0,DateRoute.NATIVE,"OWNER_CAP",expected);
            assertEquals(0,cap0.attempt().lookups);
            var next=probe("S1_add_next_cap1",MONTH,1,DateRoute.DATE_TEST,"",expected);
            assertEquals(1,next.attempt().lookups);
            assertSourceRows(next,List.of("snapshot-add-new-o1","snapshot-add-new-o2","snapshot-add-base-o1","snapshot-add-base-o2"),
                List.of("snapshot-add-new-r1","snapshot-add-new-r2","snapshot-add-new-r3","snapshot-add-base-r1","snapshot-add-base-r2","snapshot-add-base-r3"));
        }
        {
            resetFixture();String from=groupedShop(COMPANY,"snapshot-transfer-from",null),to=groupedShop(COMPANY,"snapshot-transfer-to",null);
            settlement("snapshot-transfer",from,"old",OCT.plusDays(2));
            addSources(COMPANY,from,"old","transfer-old");addSources(COMPANY,to,"new","transfer-new");initializeAndPublish(COMPANY);
            var options=new Options(COMPANY.organizationId());
            options.afterSnapshot=()->atBoundary("S1_transfer_after_source_before_proof",w->{
                commitMutation(w,"UPDATE finance_records SET shop_id=?,order_id='new' WHERE id='snapshot-transfer'",to);
                assertEquals(2,pending(COMPANY,reader));
            });
            var old=probe("S1_transfer_old_S",COMPANY,MONTH,0,options,DateRoute.DATE_TEST,"",List.of("snapshot-transfer"),"");
            assertSingleOwnerRows(old,"transfer-old");assertEquals(from,old.data().identities().getFirst().get("shop_id"));
            probe("S1_transfer_next_cap1",MONTH,1,DateRoute.NATIVE,"OWNER_CAP",List.of("snapshot-transfer"));
            var next=probe("S1_transfer_next_cap2",MONTH,2,DateRoute.DATE_TEST,"",List.of("snapshot-transfer"));
            assertEquals(2,next.attempt().lookups);assertSingleOwnerRows(next,"transfer-new");
            assertEquals(to,next.data().identities().getFirst().get("shop_id"));assertEquals("new",next.data().identities().getFirst().get("order_id"));
            assertNotEquals(old.data().identities(),next.data().identities());
        }
    }

    void snapshotKeepsEnumeratedDirtyAfterPublication() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"snapshot-publish",null);
        settlement("snapshot-dirty-old",shop,"dirty",OCT.minusDays(1));settlement("snapshot-dirty-next",shop,"dirty",OCT.plusDays(9));
        addSources(COMPANY,shop,"dirty","snapshot-dirty");initializeAndPublish(COMPANY);
        db.update("UPDATE finance_records SET record_date='2026-10-11' WHERE id='snapshot-dirty-old'");
        assertEquals(1,pending(COMPANY));
        var options=new Options(COMPANY.organizationId());
        options.afterUnresolved=()->{
            assertEquals(1,current.get().unresolved);
            atBoundary("S2_after_U_before_lookup_worker_publish",w->{
                publish(COMPANY,reader);
                assertEquals(0,pending(COMPANY,reader));
                assertEquals("snapshot-dirty-next",reads.queryForObject("SELECT first_record_id FROM finance_first_settlement_facts "+
                    "WHERE organization_id=? AND applied_revision=requested_revision AND has_settlement=TRUE",String.class,COMPANY.organizationId()));
                evidence.add(Map.of("case",w.boundary,"realWorkerPublishedClean",true));
            });
        };
        var old=probe("S2_enumerated_dirty_old_S",COMPANY,MONTH,1,options,DateRoute.DATE_TEST,"",List.of("snapshot-dirty-next"),"");
        assertEquals(1,old.attempt().unresolved);assertEquals(1,old.attempt().lookups);assertSingleOwnerRows(old,"snapshot-dirty");
        var next=probe("S2_next_S_clean_cap0",MONTH,0,DateRoute.DATE_TEST,"",List.of("snapshot-dirty-next"));
        assertEquals(0,next.attempt().unresolved);assertEquals(0,next.attempt().lookups);assertSingleOwnerRows(next,"snapshot-dirty");
    }

    void snapshotKeepsCleanBeforeDateAndDeleteChanges() throws Exception {
        snapshotCleanDateMove(false);snapshotCleanDateMove(true);
        {
            resetFixture();String shop=groupedShop(COMPANY,"snapshot-delete-first",null);
            settlement("snapshot-delete-first",shop,"delete",OCT.plusDays(1));settlement("snapshot-delete-next",shop,"delete",OCT.plusDays(4));
            addSources(COMPANY,shop,"delete","snapshot-delete");initializeAndPublish(COMPANY);
            var options=new Options(COMPANY.organizationId());
            options.afterIdentityRead=()->{
                assertEquals(0,current.get().unresolved);
                atBoundary("S3_clean_then_delete_first",w->commitMutation(w,"DELETE FROM finance_records WHERE id='snapshot-delete-first'"));
            };
            var old=probe("S3_delete_first_old_S",COMPANY,MONTH,0,options,DateRoute.DATE_TEST,"",List.of("snapshot-delete-first"),"");
            assertSingleOwnerRows(old,"snapshot-delete");
            var next=probe("S3_delete_first_next_S",MONTH,1,DateRoute.DATE_TEST,"",List.of("snapshot-delete-next"));
            assertSingleOwnerRows(next,"snapshot-delete");assertEquals(1,next.attempt().lookups);
        }
        {
            resetFixture();String shop=groupedShop(COMPANY,"snapshot-delete-all",null);
            settlement("snapshot-all-first",shop,"all",OCT.plusDays(1));addSources(COMPANY,shop,"all","snapshot-all");initializeAndPublish(COMPANY);
            var options=new Options(COMPANY.organizationId());
            options.afterIdentityRead=()->{
                assertEquals(0,current.get().unresolved);
                atBoundary("S3_clean_then_delete_all_candidates",w->commitMutation(w,"DELETE FROM finance_records WHERE id='snapshot-all-first'"));
            };
            var old=probe("S3_delete_all_old_S",COMPANY,MONTH,0,options,DateRoute.DATE_TEST,"",List.of("snapshot-all-first"),"");
            assertSingleOwnerRows(old,"snapshot-all");
            var next=probe("S3_delete_all_next_S_pending_empty",MONTH,1,DateRoute.DATE_TEST,"",List.of());
            assertSourceRows(next,List.of(),List.of());assertEquals(1,next.attempt().lookups);
            publish(COMPANY);
            var empty=probe("S3_delete_all_published_empty",MONTH,0,DateRoute.DATE_TEST,"",List.of());
            assertSourceRows(empty,List.of(),List.of());
        }
    }
    void snapshotCleanDateMove(boolean intoOctober) throws Exception {
        resetFixture();String tag=intoOctober?"snapshot-in":"snapshot-out",shop=groupedShop(COMPANY,tag,null);
        LocalDate first=intoOctober?OCT.minusDays(1):OCT.plusDays(1);
        LocalDate second=intoOctober?OCT.plusDays(9):OCT.plusMonths(1);
        LocalDate moved=intoOctober?OCT.plusDays(10):OCT.plusMonths(1).plusDays(1);
        Filter oldMonth=intoOctober?window(OCT.minusMonths(1),OCT.minusDays(1)):MONTH;
        Filter newMonth=intoOctober?MONTH:window(OCT.plusMonths(1),OCT.plusMonths(2).minusDays(1));
        settlement(tag+"-first",shop,"move",first);settlement(tag+"-next",shop,"move",second);
        addSources(COMPANY,shop,"move",tag);initializeAndPublish(COMPANY);
        var options=new Options(COMPANY.organizationId());
        options.afterIdentityRead=()->{
            assertEquals(0,current.get().unresolved);
            atBoundary("S3_clean_then_"+tag,w->commitMutation(w,"UPDATE finance_records SET record_date=? WHERE id=?",java.sql.Date.valueOf(moved),tag+"-first"));
        };
        var old=probe("S3_"+tag+"_old_S",COMPANY,oldMonth,0,options,DateRoute.DATE_TEST,"",List.of(tag+"-first"),"");
        assertSingleOwnerRows(old,tag);
        var exit=probe("S3_"+tag+"_next_old_month_exit",oldMonth,1,DateRoute.DATE_TEST,"",List.of());
        assertSourceRows(exit,List.of(),List.of());assertEquals(1,exit.attempt().lookups);
        var enter=probe("S3_"+tag+"_next_new_month_entry",newMonth,1,DateRoute.DATE_TEST,"",List.of(tag+"-next"));
        assertSingleOwnerRows(enter,tag);assertEquals(1,enter.attempt().lookups);
    }


    void snapshotKeepsCopiedFieldsAndBytes() throws Exception {
        {
            resetFixture();String shop=groupedShop(COMPANY,"snapshot-copy",null);
            settlement("snapshot-copy-a",shop,"copy",OCT.plusDays(2));
            settlement(COMPANY,"snapshot-copy-z",shop,"copy",OCT.plusDays(2),"TIKTOK_SHOP","PHP","ORDER","SETTLED","API");
            addSources(COMPANY,shop,"copy","snapshot-copy");initializeAndPublish(COMPANY);
            var options=new Options(COMPANY.organizationId());
            options.afterIdentityRead=()->atBoundary("S4_clean_then_platform_currency",w->
                commitMutation(w,"UPDATE finance_records SET platform='TIKTOK_SHOP',currency='PHP' WHERE id='snapshot-copy-a'"));
            var old=probe("S4_copied_old_S",COMPANY,chosen(shop,"SHOPEE","USD",""),0,options,DateRoute.DATE_TEST,"",List.of("snapshot-copy-a"),"");
            assertSingleOwnerRows(old,"snapshot-copy");
            assertEquals("SHOPEE",old.data().identities().getFirst().get("platform"));assertEquals("USD",old.data().identities().getFirst().get("currency"));
            var exit=probe("S4_copied_next_old_filter",chosen(shop,"SHOPEE","USD",""),1,DateRoute.DATE_TEST,"",List.of());
            assertSourceRows(exit,List.of(),List.of());
            var next=probe("S4_copied_next_new_filter",chosen(shop,"TIKTOK_SHOP","PHP",""),1,DateRoute.DATE_TEST,"",List.of("snapshot-copy-a"));
            assertSingleOwnerRows(next,"snapshot-copy");
            assertEquals("TIKTOK_SHOP",next.data().identities().getFirst().get("platform"));assertEquals("PHP",next.data().identities().getFirst().get("currency"));
        }
        {
            resetFixture();String shop=groupedShop(COMPANY,"snapshot-byte",null);
            settlement("snapshot-byte-a",shop,"byte",OCT.plusDays(2));
            settlement(COMPANY,"snapshot-byte-z",shop,"byte",OCT.plusDays(2),"SHOPEE","PHP","ORDER","SETTLED","API");
            addSources(COMPANY,shop,"byte","snapshot-byte");initializeAndPublish(COMPANY);
            long revision=revision(COMPANY.organizationId(),shop,"byte");
            var options=new Options(COMPANY.organizationId());
            options.afterIdentityRead=()->atBoundary("S4_clean_then_SQL_equal_bytes",w->{
                commitMutation(w,"UPDATE finance_records SET id='SNAPSHOT-BYTE-A',organization_id='DATE-BULK-COMPANY',shop_id=?,"+
                    "order_id='BYTE',platform='shopee',currency='usd',status='settled' WHERE id='snapshot-byte-a'",shop.toUpperCase(Locale.ROOT));
                assertEquals(revision,reads.queryForObject("SELECT requested_revision FROM finance_first_settlement_facts WHERE organization_id=? AND order_id='byte'",Long.class,COMPANY.organizationId()));
                assertEquals(0,pending(COMPANY,reader));
            });
            var old=probe("S4_bytes_old_S",COMPANY,MONTH,0,options,DateRoute.DATE_TEST,"",List.of("snapshot-byte-a"),"");
            assertSingleOwnerRows(old,"snapshot-byte");assertEquals("555344",old.data().identities().getFirst().get("currency_hex"));
            assertEquals("53484F504545",old.data().identities().getFirst().get("platform_hex"));
            var next=probe("S4_bytes_next_S",MONTH,0,DateRoute.DATE_TEST,"",List.of("SNAPSHOT-BYTE-A"));
            assertSingleOwnerRows(next,"snapshot-byte");assertEquals(0,next.attempt().lookups);
            assertEquals("757364",next.data().identities().getFirst().get("currency_hex"));
            assertEquals("73686F706565",next.data().identities().getFirst().get("platform_hex"));
            assertEquals("736574746C6564",next.data().identities().getFirst().get("status_hex"));
            assertEquals("BYTE",next.data().identities().getFirst().get("order_id"));
            assertNotEquals(old.data().identities(),next.data().identities());
        }
    }

    void snapshotKeepsInitializationAndRegistrationBoundaries() throws Exception {
        {
            resetFixture();String shop=groupedShop(COMPANY,"snapshot-init",null);
            settlement("snapshot-init",shop,"init",OCT.plusDays(2));addSources(COMPANY,shop,"init","snapshot-init");
            new FinanceFirstSettlementInitialization(repo).discoverCompanies(8);
            assertFalse(new FinanceFirstSettlementFacts(repo).guard(COMPANY.organizationId()).orElseThrow().seedComplete());
            var options=new Options(COMPANY.organizationId());
            options.afterSnapshot=()->atBoundary("S5_incomplete_S_then_real_initialization",w->initializeAndPublish(COMPANY,reader));
            var old=probe("S5_incomplete_initialization_old_S",COMPANY,MONTH,64,options,DateRoute.NATIVE,"CLOSURE_GATE",List.of("snapshot-init"),"");
            assertSingleOwnerRows(old,"snapshot-init");assertEquals(Boolean.FALSE,old.attempt().guardSeed);
            var unapproved=probe("S5_initialized_but_ungranted",COMPANY,MONTH,0,new Options("not-granted"),DateRoute.NATIVE,"UNGRANTED",List.of("snapshot-init"),"");
            assertEquals(0,unapproved.attempt().newTableReads);
            var next=probe("S5_initialized_next_explicit_grant",MONTH,0,DateRoute.DATE_TEST,"",List.of("snapshot-init"));
            assertSingleOwnerRows(next,"snapshot-init");assertEquals(Boolean.TRUE,next.attempt().guardSeed);
        }
        snapshotRegistrationBoundary(false);snapshotRegistrationBoundary(true);
    }
    void snapshotRegistrationBoundary(boolean advancedBeforeA) throws Exception {
        resetFixture();String tag=advancedBeforeA?"snapshot-advanced":"snapshot-upgrade",shop=groupedShop(COMPANY,tag,null);
        settlement(tag,shop,"upgrade",OCT.plusDays(2));addSources(COMPANY,shop,"upgrade",tag);initializeAndPublish(COMPANY);
        if(advancedBeforeA)advanceRegistrationTwo(repo);
        var options=new Options(COMPANY.organizationId());if(advancedBeforeA)options.registration=2;
        options.afterSnapshot=()->atBoundary("S5_"+tag+"_before_proof",w->{
            if(!advancedBeforeA)advanceRegistrationTwo(reader);
            initializeAndPublish(COMPANY,reader);
            assertEquals(1,reads.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? "+
                "AND applied_generation=2 AND applied_registration_version=2 AND applied_revision=requested_revision",Integer.class,COMPANY.organizationId()));
        });
        var old=probe("S5_"+tag+"_old_S",COMPANY,MONTH,0,options,
            advancedBeforeA?DateRoute.NATIVE:DateRoute.DATE_TEST,advancedBeforeA?"CLOSURE_GATE":"",List.of(tag),"");
        assertSingleOwnerRows(old,tag);
        assertEquals(advancedBeforeA?2:1,old.attempt().proofRegistration);
        assertEquals(advancedBeforeA?2L:1L,old.attempt().guardGeneration);
        assertEquals(advancedBeforeA?Boolean.FALSE:Boolean.TRUE,old.attempt().guardSeed);
        var stale=probe("S5_"+tag+"_next_stale_grant",MONTH,0,DateRoute.NATIVE,"REGISTRATION",List.of(tag));
        assertSingleOwnerRows(stale,tag);assertEquals(2,stale.attempt().proofRegistration);
        var missingGrant=new Options("not-granted");missingGrant.registration=2;
        var unapproved=probe("S5_"+tag+"_next_unapproved",COMPANY,MONTH,0,missingGrant,DateRoute.NATIVE,"UNGRANTED",List.of(tag),"");
        assertEquals(0,unapproved.attempt().newTableReads);
        var explicit=new Options(COMPANY.organizationId());explicit.registration=2;
        var next=probe("S5_"+tag+"_next_correct_grant",COMPANY,MONTH,0,explicit,DateRoute.DATE_TEST,"",List.of(tag),"");
        assertSingleOwnerRows(next,tag);assertEquals(2,next.attempt().proofRegistration);assertEquals(2L,next.attempt().guardGeneration);
        assertEquals(Boolean.TRUE,next.attempt().guardSeed);assertEquals(Boolean.TRUE,next.attempt().guardShops);assertEquals(Boolean.TRUE,next.attempt().guardRequeue);
    }
    void advanceRegistrationTwo(FinanceRepository runtimeRepo) {
        var init=new FinanceFirstSettlementInitialization(runtimeRepo);
        var previous=init.maintenanceState(COMPANY.organizationId()).orElseThrow();
        assertEquals(1,previous.registrationVersion());
        int changed=runtimeRepo.transaction(()->runtimeRepo.jdbc().update("UPDATE finance_first_settlement_registration "+
            "SET registration_version=2,installation_boundary='snapshot-registration-2' WHERE id=1"));
        assertEquals(1,changed);
        var target=new FinanceFirstSettlementFacts(runtimeRepo).registration().orElseThrow();
        assertEquals(AdvanceResult.ADVANCED,init.advance(previous,target));
        var guard=new FinanceFirstSettlementFacts(runtimeRepo).guard(COMPANY.organizationId()).orElseThrow();
        assertEquals(2L,guard.generation());assertFalse(guard.seedComplete());
        evidence.add(Map.of("case","S5_actual_advance","oldGeneration",previous.generation(),"newGeneration",guard.generation(),
            "registration",target.registrationVersion(),"seedCompleteAtAdvance",false));
    }

    void snapshotExcludesLowIdLateCommit() throws Exception {
        resetFixture();String shop=groupedShop(COMPANY,"snapshot-late",null);
        settlement("zzz-snapshot-stable",shop,"base",OCT.plusDays(2));addSources(COMPANY,shop,"base","late-base");
        addSources(COMPANY,shop,"late","late-new");initializeAndPublish(COMPANY);
        var writer=startWriter("S6_low_ID_insert_before_A_commit_after_S",null,w->{
            try(var connection=source.getConnection()) {
                connection.setAutoCommit(false);
                try(var statement=connection.prepareStatement("""
                    INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,
                      category,status,record_date,currency,source,raw_json)
                    VALUES('000-snapshot-late',?,?,?,'SHOPEE','SETTLEMENT','000-snapshot-late','late','000-snapshot-late',
                      'ORDER','SETTLED','2026-10-08','USD','API','{}')
                    """)) {
                    statement.setString(1,COMPANY.organizationId());statement.setString(2,COMPANY.userId());statement.setString(3,shop);
                    assertEquals(1,statement.executeUpdate());
                }
                try(var statement=connection.prepareStatement("SELECT pending FROM finance_first_settlement_facts WHERE organization_id=? AND shop_id=? AND order_id='late'")) {
                    statement.setString(1,COMPANY.organizationId());statement.setString(2,shop);
                    try(var rows=statement.executeQuery()){assertTrue(rows.next());assertTrue(rows.getBoolean(1));assertFalse(rows.next());}
                }
                w.preparedUncommitted=true;w.prepared.countDown();
                if(!w.release.await(Math.max(1,w.waitDeadline-System.nanoTime()),TimeUnit.NANOSECONDS))
                    throw new TimeoutException("Held source transaction was not released inside its bounded original wait");
                if(Thread.currentThread().isInterrupted()||System.nanoTime()>=w.waitDeadline)
                    throw new TimeoutException("Held writer cannot commit after its original deadline");
                connection.commit();w.mutationCommitted=true;
            }
            freshSourceWitness(w,MONTH,List.of("000-snapshot-late","zzz-snapshot-stable"));
            assertEquals("000-snapshot-late",reads.queryForObject("SELECT MIN(id) FROM finance_records WHERE organization_id=? AND kind='SETTLEMENT'",String.class,COMPANY.organizationId()));
        });
        try {
            assertTrue(writer.prepared.await(Math.max(1,writer.waitDeadline-System.nanoTime()),TimeUnit.NANOSECONDS));
            if(!writer.preparedUncommitted) {
                try{writer.future.get(Math.max(1,writer.waitDeadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
                catch(ExecutionException failure){throw propagate(failure.getCause());}
                fail("B must insert both source and trigger registration before A starts S");
            }
            var options=new Options(COMPANY.organizationId());
            options.afterSnapshot=()->{
                writer.readerConnectionId=current.get().connectionId;
                assertEquals(0,writer.commits.get());assertFalse(writer.future.isDone());
                writer.release.countDown();awaitWriter(writer,current.get());
            };
            var old=probe("S6_low_late_old_S",COMPANY,MONTH,0,options,DateRoute.DATE_TEST,"",List.of("zzz-snapshot-stable"),"");
            assertSingleOwnerRows(old,"late-base");assertEquals(0,old.attempt().unresolved);
            assertNotEquals(old.data().identities(),writer.freshRows);
            var expected=List.of("000-snapshot-late","zzz-snapshot-stable");
            probe("S6_low_late_next_cap0",MONTH,0,DateRoute.NATIVE,"OWNER_CAP",expected);
            var next=probe("S6_low_late_next_cap1",MONTH,1,DateRoute.DATE_TEST,"",expected);
            assertSourceRows(next,List.of("late-new-o1","late-new-o2","late-base-o1","late-base-o2"),
                List.of("late-new-r1","late-new-r2","late-new-r3","late-base-r1","late-base-r2","late-base-r3"));
            evidence.add(Map.of("case","S6_low_ID_late_commit","sourceAndPendingSeenBeforeCommit",true,"oldSnapshotExcluded",true,
                "nextSnapshotIncluded",true,"lowId","000-snapshot-late","existingId","zzz-snapshot-stable","usesHighWatermark",false));
        }finally {writer.release.countDown();}
    }

    @FunctionalInterface interface WriterAction {void run(SnapshotWriter writer)throws Exception;}
    static final class SnapshotWriter {
        final String boundary;
        final long started=System.nanoTime(),readerThread=Thread.currentThread().threadId();
        final long waitDeadline;
        final CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);
        final List<Long> connectionIds=new CopyOnWriteArrayList<>();
        final List<Map<String,Object>> errors=new CopyOnWriteArrayList<>();
        final AtomicInteger borrowed=new AtomicInteger(),closed=new AtomicInteger(),commits=new AtomicInteger(),rollbacks=new AtomicInteger(),openStatements=new AtomicInteger();
        volatile long readerConnectionId=-1,readerDeadline,threadId,ended;
        volatile boolean completed,mutationCommitted,preparedUncommitted,originalHolderRetained;
        volatile String failure="";
        ExecutorService executor;
        FutureTask<Void> future;
        List<Map<String,Object>> freshRows=List.of();
        SnapshotWriter(String boundary,long waitDeadline){this.boundary=boundary;this.waitDeadline=waitDeadline;}
        Map<String,Object> receipt(){
            var r=new LinkedHashMap<String,Object>();r.put("boundary",boundary);r.put("readerConnectionId",readerConnectionId);
            r.put("readerHolderDeadlineMillis",readerDeadline);r.put("originalHolderRetained",originalHolderRetained);
            r.put("readerThread",readerThread);r.put("writerThread",threadId);r.put("physicalWriterIds",connectionIds);
            r.put("completed",completed);r.put("mutationCommitted",mutationCommitted);r.put("preparedUncommitted",preparedUncommitted);
            r.put("borrows",borrowed.get());r.put("closed",closed.get());r.put("commits",commits.get());r.put("rollbacks",rollbacks.get());
            r.put("openStatements",openStatements.get());r.put("failure",failure);r.put("sqlErrors",errors);
            r.put("freshIds",ids(freshRows));r.put("freshRowsHash",hash(freshRows.toString()));
            r.put("writerElapsedMillis",ended==0?-1:(ended-started)/1_000_000);
            r.put("futureDone",future!=null&&future.isDone());r.put("executorTerminated",executor!=null&&executor.isTerminated());
            return r;
        }
    }
    SnapshotWriter startWriter(String boundary,Attempt a,WriterAction action) {
        long room=a==null?TimeUnit.SECONDS.toNanos(5):TimeUnit.MILLISECONDS.toNanos(Math.max(1,a.holder.getTimeToLiveInMillis()));
        long deadline=Math.min(Math.min(hardDeadline,phaseDeadline),System.nanoTime()+Math.min(room,TimeUnit.SECONDS.toNanos(5)));
        var w=new SnapshotWriter(boundary,deadline);
        if(a!=null){w.readerConnectionId=a.connectionId;w.readerDeadline=a.deadline;}
        w.executor=Executors.newSingleThreadExecutor();executors.add(w.executor);writers.add(w);
        // Register the actual FutureTask before the executor can dispatch it.
        w.future=new FutureTask<>(()->{
            writing.set(w);w.threadId=Thread.currentThread().threadId();
            try {assertNotEquals(w.readerThread,w.threadId);action.run(w);w.completed=true;return null;}
            catch(Exception|Error failure){w.failure=failure.getClass().getSimpleName();throw failure;}
            finally {w.ended=System.nanoTime();writing.remove();w.prepared.countDown();}
        });
        w.executor.execute(w.future);
        return w;
    }
    SnapshotWriter atBoundary(String boundary,WriterAction action) {
        var a=current.get();FinanceLabelFirstSettlementFacts.requireUsable(reader);
        var w=startWriter(boundary,a,action);awaitWriter(w,a);return w;
    }
    void awaitWriter(SnapshotWriter w,Attempt a) {
        w.readerDeadline=a.deadline;
        Throwable primary=null;
        try {
            assertTrue(System.nanoTime()<w.waitDeadline,"B must fit the original S and phase deadline");
            try {w.future.get(Math.max(1,w.waitDeadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
            catch(ExecutionException failure){throw propagate(failure.getCause());}
            catch(TimeoutException failure){throw new IllegalStateException("Snapshot writer exceeded its original bounded wait",failure);}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            assertTrue(w.completed);assertTrue(w.commits.get()>0,"B must actually commit");
            assertFalse(w.connectionIds.isEmpty());
            assertTrue(w.connectionIds.stream().allMatch(id->id!=a.connectionId),"B must hold a different real physical connection");
            assertEquals(w.borrowed.get(),w.closed.get());assertEquals(0,w.openStatements.get());
            assertSame(a.holder,holder());assertSame(a.connection,holder().getConnection());
            assertEquals(a.deadline,holder().getDeadline().getTime());
            FinanceLabelFirstSettlementFacts.requireUsable(reader);
            assertEquals(1,a.borrows);assertEquals(1,source.activeConnections.get());
            assertTrue(w.ended<=w.waitDeadline);w.originalHolderRetained=true;
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {
            try {
                if(!w.future.isDone()){source.cancelAll();w.future.cancel(true);}
                w.executor.shutdownNow();
                assertTrue(w.executor.awaitTermination(Math.max(0,w.waitDeadline-System.nanoTime()),TimeUnit.NANOSECONDS));
            }catch(Throwable cleanupFailure){
                if(primary!=null)primary.addSuppressed(cleanupFailure);else throw propagate(cleanupFailure);
            }
        }
    }
    static RuntimeException propagate(Throwable failure){
        if(failure instanceof Error error)throw error;
        return failure instanceof RuntimeException runtime?runtime:new IllegalStateException("Snapshot writer failed",failure);
    }
    void commitMutation(SnapshotWriter w,String sql,Object...args)throws SQLException {
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);updateAndCommit(connection,sql,args);w.mutationCommitted=true;
        }
    }
    void commitSettlement(SnapshotWriter w,String id,String shop,String order,LocalDate day)throws SQLException {
        commitMutation(w,"""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,source,raw_json)
            VALUES(?,?,?,?,'SHOPEE','SETTLEMENT',?,?,?,'ORDER','SETTLED',?,'USD','API','{}')
            """,id,COMPANY.organizationId(),COMPANY.userId(),shop,id,order,id,java.sql.Date.valueOf(day));
    }
    void freshSourceWitness(SnapshotWriter w,Filter filter,List<String> expected) {
        w.freshRows=reader.readSnapshot(30,()->{
            var q=reader.query(COMPANY,filter,"r");
            var result=rows(null,"SELECT "+exactProjection("r")+" FROM finance_records r WHERE "+
                FinanceCostReport.firstSettlementIdentity("r",true)+" AND "+q.where()+" ORDER BY r.record_date DESC,r.id",q.args());
            FinanceLabelFirstSettlementFacts.requireUsable(reader);
            var h=holder();long id=reads.queryForObject("SELECT CONNECTION_ID()",Long.class);
            assertNotEquals(w.readerConnectionId,id);assertEquals(expected,ids(result));
            evidence.add(Map.of("case",w.boundary,"freshPhysicalConnectionId",id,"freshHolderDeadlineMillis",h.getDeadline().getTime(),
                "freshLiteralIds",expected,"independentFreshSnapshot",true));
            return result;
        });
    }
    void assertSourceRows(Probe probe,List<String> orders,List<String> refunds) {
        for(boolean normal:List.of(false,true)) {
            var o=probe.data().settled().get("ORDER:"+normal);var r=probe.data().settled().get("REFUND:"+normal);
            assertEquals(orders,ids(o.bag()));assertEquals(refunds,ids(r.bag()));
            assertEquals(orders.size(),o.count());assertEquals(refunds.size(),r.count());
        }
    }
    void assertSingleOwnerRows(Probe probe,String prefix) {
        assertSourceRows(probe,List.of(prefix+"-o1",prefix+"-o2"),List.of(prefix+"-r1",prefix+"-r2",prefix+"-r3"));
    }

    // All fixture setup stays inside the single selected test.
    void resetFixture() {
        checkTime();setup();resetFirstSettlementPrototype();
        phaseDeadline=Math.min(hardDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(120));phaseAttempts=0;
        assertEquals(3,triggerCount());assertEquals(SUPPORTED_DEFINITION_SHA,triggerDefinitionSha());
    }
    void settlement(String id,String shop,String order,LocalDate date){settlement(COMPANY,id,shop,order,date,"SHOPEE","USD","ORDER","SETTLED","API");}
    void settlement(Scope company,String id,String shop,String order,LocalDate date,String platform,String currency,String category,String status,String origin){
        insertSettlement(id,company.organizationId(),shop,order,platform,category,status,origin,date,currency);
        db.update("UPDATE finance_records SET statement_id=? WHERE id=?",id,id);
    }
    void addSources(Scope company,String shop,String order,String prefix) {
        var values=new ArrayList<Object>();
        appendSourceValues(values,company,shop,order,prefix);insertSources(1,values);
    }
    void appendSourceValues(List<Object> values,Scope company,String shop,String order,String prefix) {
        String[] kinds={"ORDER","ORDER","REFUND","REFUND","REFUND","ORDER","REFUND"};
        String[] tags={"o1","o2","r1","r2","r3","oi","ri"};
        BigDecimal[] amounts={null,BigDecimal.ZERO,new BigDecimal("-1.234567"),BigDecimal.ZERO,null,BigDecimal.TEN,BigDecimal.TEN};
        BigDecimal[] revenues={null,new BigDecimal("-0.000001"),null,BigDecimal.ZERO,new BigDecimal("-0.000001"),null,null};
        for(int n=0;n<kinds.length;n++)Collections.addAll(values,prefix+"-"+tags[n],company.organizationId(),company.userId(),shop,kinds[n],
            "external-"+prefix+"-"+tags[n],order,"statement-"+prefix+"-"+tags[n],n==1?"CANCELLED":"SETTLED",amounts[n],revenues[n],n<5?"API":"IMPORT");
    }
    void insertSources(int owners,List<Object> values) {
        db.update("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,status,
              record_date,currency,amount,revenue,source,raw_json) VALUES
            """+String.join(",",Collections.nCopies(7*owners,"(?,?,?,?,'TIKTOK_SHOP',?,?,?,?,?,'2026-08-01','PHP',?,?,?,'{}')")),values.toArray());
    }
    void total(String shop,String order) {
        db.update("INSERT IGNORE INTO finance_label_state(organization_id,active_generation,requested_revision,applied_revision) VALUES(?,'date-active',1,1)",COMPANY.organizationId());
        db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES(?,'date-active',?,?,0,0,0)",
            COMPANY.organizationId(),shop,order);
    }
    void initializeAndPublish(Scope company) throws Exception {
        initializeAndPublish(company,repo);
    }
    void initializeAndPublish(Scope company,FinanceRepository runtimeRepo) throws Exception {
        checkTime();var init=new FinanceFirstSettlementInitialization(runtimeRepo);var facts=new FinanceFirstSettlementFacts(runtimeRepo);
        init.discoverCompanies(8);var guard=facts.guard(company.organizationId()).orElseThrow();
        boolean seed=false,requeue=false;
        for(int n=0;n<200;n++){checkTime();if(init.seed(guard,64).complete()){seed=true;break;}}
        for(int n=0;n<200;n++){checkTime();if(init.requeue(guard,64).complete()){requeue=true;break;}}
        assertTrue(seed&&requeue&&init.complete(guard));publish(company,runtimeRepo);
        assertTrue(facts.guard(company.organizationId()).orElseThrow().seedComplete());
    }
    void publish(Scope company)throws Exception {
        publish(company,repo);
    }
    void publish(Scope company,FinanceRepository runtimeRepo)throws Exception {
        if(gateDirectory==null)gateDirectory=Files.createTempDirectory("finance-date-bulk-gate-");
        var constructor=MaintenanceGate.class.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
        var gate=constructor.newInstance(gateDirectory.resolve("closed"));
        var worker=new FinanceFirstSettlementWorker(new FinanceFirstSettlementFacts(runtimeRepo),new FinanceFirstSettlementInitialization(runtimeRepo),
            64,30,8,Duration.ofSeconds(2),System::nanoTime,gate);
        for(int n=0;pending(company,runtimeRepo)>0&&n<200;n++){checkTime();worker.tick();}
        assertEquals(0L,pending(company,runtimeRepo));assertEquals(0,gate.activeWorkers());
    }
    long pending(Scope scope){return pending(scope,repo);}
    long pending(Scope scope,FinanceRepository runtimeRepo){return runtimeRepo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? AND pending=TRUE",Long.class,scope.organizationId());}
    List<Map<String,Object>> state(){
        var all=new ArrayList<Map<String,Object>>();
        for(String table:List.of("finance_first_settlement_registration","finance_first_settlement_initialization","finance_first_settlement_facts"))
            all.addAll(db.queryForList("SELECT * FROM "+table+" ORDER BY "+(table.endsWith("facts")?"organization_id,shop_id,order_id":table.endsWith("initialization")?"organization_id":"id")));return all;
    }
    void checkTime(){if(System.nanoTime()>=hardDeadline)throw new IllegalStateException("Date bulk harness reached its unchanged hard deadline");}
    void checkPhase(){checkTime();if(System.nanoTime()>=phaseDeadline||++phaseAttempts>200)throw new IllegalStateException("Closure phase exhausted its unchanged 120s/200-attempt boundary");}
    void cleanupOwned(Map<String,Object> saved)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        source.cancelAll();
        for(var w:writers)if(w.future!=null&&!w.future.isDone()){w.future.cancel(true);w.release.countDown();}
        for(var f:readerFutures)if(!f.isDone())f.cancel(true);
        for(var e:executors)e.shutdownNow();
        for(var e:executors)assertTrue(e.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS),"Owned executor survived the shared cleanup deadline");
        assertEquals(0,source.activeConnections.get());assertTrue(source.executing.isEmpty());assertTrue(source.openStatements.isEmpty());
        assertEquals(0,dataSource.getHikariPoolMXBean().getActiveConnections());
        for(var w:writers){assertEquals(w.borrowed.get(),w.closed.get());assertEquals(0,w.openStatements.get());}
        if(gateDirectory!=null)Files.delete(gateDirectory);
        saved.put("executorsTerminated",executors.stream().allMatch(ExecutorService::isTerminated));
        saved.put("activeObservedConnections",source.activeConnections.get());saved.put("activePooledConnections",dataSource.getHikariPoolMXBean().getActiveConnections());
        saved.put("openStatements",source.openStatements.size());saved.put("executingStatements",source.executing.size());
        saved.put("cancelCalls",source.cancelCalls.get());saved.put("cancelErrors",source.cancelErrors);
        saved.put("writerFuturesDone",writers.stream().allMatch(w->w.future!=null&&w.future.isDone()));
        saved.put("readerFuturesDone",readerFutures.stream().allMatch(FutureTask::isDone));
        saved.put("sharedLimitMillis",5000);saved.put("deadlineRespected",System.nanoTime()<=deadline);
        assertTrue(System.nanoTime()<=deadline);assertTrue(source.cancelErrors.isEmpty());
    }
    static Object invoke(Object target,java.lang.reflect.Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    final class TraceSource extends AbstractDataSource {
        final DataSource delegate;
        volatile PreparedStatement sleep;
        final AtomicInteger activeConnections=new AtomicInteger(),cancelCalls=new AtomicInteger();
        final Set<PreparedStatement> executing=ConcurrentHashMap.newKeySet(),openStatements=ConcurrentHashMap.newKeySet(),cancelled=ConcurrentHashMap.newKeySet();
        final List<String> cancelErrors=new CopyOnWriteArrayList<>();
        TraceSource(DataSource delegate){this.delegate=delegate;}
        @Override public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(delegate.getConnection(user,password));}
        Connection wrap(Connection connection)throws SQLException {
            long id;
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT CONNECTION_ID()")){r.next();id=r.getLong(1);}
            var a=current.get();if(a!=null){a.borrows++;a.connectionId=id;}
            var w=writing.get();if(w!=null){w.connectionIds.add(id);w.borrowed.incrementAndGet();}
            activeConnections.incrementAndGet();boolean[] closed={false};
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close")&&!closed[0]){closed[0]=true;activeConnections.decrementAndGet();if(w!=null)w.closed.incrementAndGet();}
                if(w!=null&&method.getName().equals("commit")){var result=invoke(connection,method,args);w.commits.incrementAndGet();return result;}
                if(w!=null&&method.getName().equals("rollback")){var result=invoke(connection,method,args);w.rollbacks.incrementAndGet();return result;}
                if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql) {
                    var h=(ConnectionHolder)TransactionSynchronizationManager.getResource(this);
                    if(a!=null) {
                        if(a.connection==null){a.connection=(Connection)proxy;a.holder=h;a.deadline=h.getDeadline().getTime();}
                        a.sameSnapshot&=a.connection==proxy&&a.holder==h&&a.deadline==h.getDeadline().getTime()&&h.hasTimeout()
                            &&TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                            &&connection.isReadOnly()&&!connection.getAutoCommit()&&connection.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ;
                        if(sql.contains("finance_first_settlement_"))a.newTableReads++;
                        if(sql.contains("finance_label_order_totals")||sql.contains("finance_label_state"))a.externalDomainReads++;
                        if(sql.contains("FORCE INDEX(idx_fsf_date)"))a.dateStatements++;
                    }
                    var actual=(PreparedStatement)invoke(connection,method,args);openStatements.add(actual);
                    boolean[] statementClosed={false};if(w!=null)w.openStatements.incrementAndGet();
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,x)->{
                        if(m.getName().equals("close")&&!statementClosed[0]){statementClosed[0]=true;openStatements.remove(actual);if(w!=null)w.openStatements.decrementAndGet();}
                        if(m.getName().equals("executeQuery")||m.getName().equals("executeUpdate")){
                            if(sql.equals("SELECT SLEEP(10)"))sleep=actual;
                            executing.add(actual);
                            try{return invoke(actual,m,x);}
                            catch(Throwable failure){var e=FinanceFirstSettlementLoadIntegrationTest.findSql(failure);
                                if(a!=null&&e!=null)a.errors.add(Map.of("state",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode(),"driverException",e.getClass().getSimpleName()));
                                if(w!=null&&e!=null)w.errors.add(Map.of("state",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode(),"driverException",e.getClass().getSimpleName()));
                                throw failure;
                            }finally{executing.remove(actual);}
                        }
                        return invoke(actual,m,x);
                    });
                }
                return invoke(connection,method,args);
            });
        }
        // Direct deduplicated cancel avoids Connector/J's executing-query getter locks.
        void cancelAll(){for(var s:executing)if(cancelled.add(s))try{cancelCalls.incrementAndGet();s.cancel();}
            catch(SQLException|RuntimeException failure){cancelErrors.add(failure.getClass().getSimpleName());}}
    }
}
