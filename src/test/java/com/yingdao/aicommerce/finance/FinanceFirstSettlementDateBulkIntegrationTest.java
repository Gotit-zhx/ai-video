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
    final List<Map<String,Object>> evidence=new CopyOnWriteArrayList<>();
    final List<ExecutorService> executors=new ArrayList<>();
    final ThreadLocal<Attempt> current=new ThreadLocal<>();
    final List<Attempt> attempts=new CopyOnWriteArrayList<>();
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
                case "closure" -> {
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
                }
                case "snapshot","selectivity","selective-clean-1pct","selective-mixed-1pct","selective-clean-10pct",
                     "all-month-control","plan","continuous" -> fail("NOT_IMPLEMENTED: "+selected);
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
                receipt.put("cleanup",cleanup);receipt.put("productionAdmission",false);
                var directory=Path.of("/tmp/finance-bi-performance-qa/first-settlement-date-bulk");
                Files.createDirectories(directory);
                var path=directory.resolve("closure-"+UUID.randomUUID()+".json");
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
    static final class Options {
        boolean enabled=true;
        String grantOrganization;
        int registration=1;
        LongSupplier clock=System::nanoTime;
        Runnable afterSnapshot=()->{},beforeProof=()->{},beforeAudit=()->{};
        String auditControl="";
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
        int borrows,auditRows=-1,unresolved=-1,lookups,newTableReads,nativeData,oracleData,returnedData;
        int pendingBranch=-1,oldBranch=-1,combined=-1,externalDomainReads,dateStatements;
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
            m.put("actualDateStatements",dateStatements);
            m.put("oracleData",oracleData);m.put("returnedData",returnedData);m.put("failure",failure);
            m.put("sqlErrors",errors);m.put("elapsedMillis",(System.nanoTime()-started)/1_000_000);
            return m;
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
            if(!supported(proof)||proof.registrationVersion()!=options.registration)return nativeResolution("REGISTRATION",-1);
            var guards=select(budget,"""
                SELECT organization_id,generation,target_schema_version,registration_version,
                  registration_definition_sha,registration_boundary,seed_complete,shops_complete,requeue_complete
                FROM finance_first_settlement_initialization WHERE organization_id=?
                """,(r,n)->r.getLong(2)>0&&r.getInt(3)==proof.schemaVersion()&&r.getInt(4)==proof.registrationVersion()
                    &&proof.definitionSha().equals(r.getString(5))&&proof.boundary().equals(r.getString(6))
                    &&r.getBoolean(7)&&r.getBoolean(8)&&r.getBoolean(9)
                    ?new Guard(r.getString(1),r.getLong(2),r.getInt(3),r.getInt(4),true):null,scope.organizationId());
            if(guards.isEmpty()||guards.getFirst()==null)return nativeResolution("CLOSURE_GATE",-1);
            var guard=guards.getFirst();options.beforeAudit.run();budget.check();
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
            var u=select(budget,"SELECT organization_id,shop_id,order_id FROM (("+pending+") UNION DISTINCT ("+old+")) unresolved LIMIT ?",
                owner,scope.organizationId(),cap+1,scope.organizationId(),cap+1,cap+1);
            var attempt=current.get();attempt.pendingBranch=p.size();attempt.oldBranch=o.size();
            attempt.combined=u.size();attempt.unresolved=u.size();
            if(u.size()>cap)return nativeResolution("OWNER_CAP",u.size());
            var resolved=new ArrayList<Candidate>();
            for(var key:u) {
                attempt.lookups++;
                resolved.addAll(select(budget,"SELECT "+prefixed("r")+" FROM finance_records r FORCE INDEX(idx_finance_first_settlement) "+
                    "WHERE r.organization_id=? AND r.shop_id=? AND r.order_id=? AND "+eligible("r")+
                    " ORDER BY r.record_date,r.id LIMIT 1",candidateMapper(),key.organizationId(),key.shopId(),key.orderId()));
            }
            StringBuilder b=new StringBuilder("SELECT f.first_record_id id,f.organization_id,f.shop_id,f.order_id "+
                "FROM finance_first_settlement_facts f FORCE INDEX(idx_fsf_date) WHERE f.organization_id=? AND f.has_settlement=TRUE AND "+
                clean("f",guard)+" AND f.first_record_date>=? AND f.first_record_date<=?");
            var args=new ArrayList<Object>(List.of(scope.organizationId(),java.sql.Date.valueOf(filter.start()),java.sql.Date.valueOf(filter.end())));
            for(var row:resolved){b.append(" UNION ALL SELECT ?,?,?,?");Collections.addAll(args,row.id(),row.organization(),row.shop(),row.order());}
            // Copy fields are not assumed byte-fresh: all eight fields come from current_r in S.
            String exact="SELECT "+prefixed("current_r")+" FROM ("+b+") b JOIN finance_records current_r "+
                "ON current_r.id=b.id AND current_r.organization_id=b.organization_id "+
                "AND current_r.shop_id=b.shop_id AND current_r.order_id=b.order_id";
            budget.check();
            return new DateResolution(DateRoute.DATE_TEST,"",new Relation(exact,List.copyOf(args)),budget,u.size());
        }catch(LocalExit proactive){return nativeResolution("LOCAL_BUDGET",current.get().unresolved);}
    }
    static RowMapper<Candidate> candidateMapper(){return (r,n)->new Candidate(r.getString(1),r.getString(2),r.getString(3),
        r.getString(4),r.getDate(5).toLocalDate(),r.getString(6),r.getString(7),r.getString(8));}
    <T> List<T> select(Budget budget,String sql,RowMapper<T> mapper,Object...args) {
        checkTime();if(budget!=null)budget.check();
        return reads.execute(connection->{
            var statement=connection.prepareStatement(sql);
            for(int n=0;n<args.length;n++)StatementCreatorUtils.setParameterValue(statement,n+1,SqlTypeValue.TYPE_UNKNOWN,args[n]);
            return statement;
        },(PreparedStatementCallback<List<T>>)statement->{
            if(budget!=null)budget.bound(statement);
            try(var rows=statement.executeQuery()){
                var result=new ArrayList<T>();for(int n=0;rows.next();n++)result.add(mapper.mapRow(rows,n));return result;
            }
        });
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
                try {actual=data(scope,filter,resolution.relation(),resolution.budget(),numbers,false);}
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
        var a=current.get();if(oracle)a.oracleData++;else if(budget==null)a.nativeData++;
        var q=reader.query(scope,filter,"r");
        var identities=rows(budget,"SELECT "+exactProjection("r")+" FROM ("+relation.sql()+") r WHERE "+q.where()+
            " ORDER BY r.record_date DESC,r.id",joined(relation.args(),q.args()));
        var settled=new LinkedHashMap<String,Settled>();
        for(String kind:List.of("ORDER","REFUND"))for(boolean normal:List.of(false,true)) {
            var selected=FinanceRecordSearch.apply(q,"o",numbers,false);
            String sql=" FROM ("+relation.sql()+") r JOIN finance_records o ON o.organization_id=r.organization_id "+
                "AND o.shop_id=r.shop_id AND o.order_id=r.order_id AND o.kind='"+kind+"' AND o.source='API' WHERE "+
                selected.where()+(normal?" AND "+FinanceOrderCharges.normalOrder("r"):"");
            settled.put(kind+":"+normal,settled(sql,joined(relation.args(),selected.args()),budget));
        }
        return new Data(identities,settled);
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
        String projection="""
            SELECT r.id winner_id,o.id,o.kind,r.record_date report_date,o.record_date source_date,
              o.status,o.platform,o.currency,o.amount,o.revenue,
              (SELECT JSON_UNQUOTE(JSON_EXTRACT(p.raw_json,'$._finance_reporting_month')) FROM finance_records p WHERE p.id=r.id) reporting_month,
              (SELECT JSON_UNQUOTE(JSON_EXTRACT(p.raw_json,'$._finance_original_settlement_date')) FROM finance_records p WHERE p.id=r.id) original_date
            """;
        var bag=rows(budget,projection+relation+" ORDER BY r.record_date DESC,o.id",args);
        long count=select(budget,"SELECT COUNT(*)"+relation,(r,n)->r.getLong(1),args.toArray()).getFirst();
        assertEquals(bag.size(),count);
        var pages=new ArrayList<List<Map<String,Object>>>();
        for(long offset:new long[]{0,1,Math.max(0,count-1),count+1}) {
            var page=rows(budget,projection+relation+" ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?",joined(args,List.of(3,offset)));
            var expected=bag.subList((int)Math.min(offset,bag.size()),(int)Math.min(offset+3,bag.size()));
            assertEquals(expected,page,"First/cross/last/empty pages must preserve exact source-row ordering");
            pages.add(page);
        }
        return new Settled(bag,count,pages);
    }
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
        String[] kinds={"ORDER","ORDER","REFUND","REFUND","REFUND","ORDER","REFUND"};
        String[] tags={"o1","o2","r1","r2","r3","oi","ri"};
        BigDecimal[] amounts={null,BigDecimal.ZERO,new BigDecimal("-1.234567"),BigDecimal.ZERO,null,BigDecimal.TEN,BigDecimal.TEN};
        BigDecimal[] revenues={null,new BigDecimal("-0.000001"),null,BigDecimal.ZERO,new BigDecimal("-0.000001"),null,null};
        for(int n=0;n<kinds.length;n++)Collections.addAll(values,prefix+"-"+tags[n],company.organizationId(),company.userId(),shop,kinds[n],
            "external-"+prefix+"-"+tags[n],order,"statement-"+prefix+"-"+tags[n],n==1?"CANCELLED":"SETTLED",amounts[n],revenues[n],n<5?"API":"IMPORT");
        db.update("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,status,
              record_date,currency,amount,revenue,source,raw_json) VALUES
            """+String.join(",",Collections.nCopies(7,"(?,?,?,?,'TIKTOK_SHOP',?,?,?,?,?,'2026-08-01','PHP',?,?,?,'{}')")),values.toArray());
    }
    void total(String shop,String order) {
        db.update("INSERT IGNORE INTO finance_label_state(organization_id,active_generation,requested_revision,applied_revision) VALUES(?,'date-active',1,1)",COMPANY.organizationId());
        db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES(?,'date-active',?,?,0,0,0)",
            COMPANY.organizationId(),shop,order);
    }
    void initializeAndPublish(Scope company) throws Exception {
        checkTime();var init=new FinanceFirstSettlementInitialization(repo);var facts=new FinanceFirstSettlementFacts(repo);
        init.discoverCompanies(8);var guard=facts.guard(company.organizationId()).orElseThrow();
        boolean seed=false,requeue=false;
        for(int n=0;n<200;n++){checkTime();if(init.seed(guard,64).complete()){seed=true;break;}}
        for(int n=0;n<200;n++){checkTime();if(init.requeue(guard,64).complete()){requeue=true;break;}}
        assertTrue(seed&&requeue&&init.complete(guard));publish(company);
        assertTrue(facts.guard(company.organizationId()).orElseThrow().seedComplete());
    }
    void publish(Scope company)throws Exception {
        if(gateDirectory==null)gateDirectory=Files.createTempDirectory("finance-date-bulk-gate-");
        var constructor=MaintenanceGate.class.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
        var gate=constructor.newInstance(gateDirectory.resolve("closed"));
        var worker=new FinanceFirstSettlementWorker(new FinanceFirstSettlementFacts(repo),new FinanceFirstSettlementInitialization(repo),
            64,30,8,Duration.ofSeconds(2),System::nanoTime,gate);
        for(int n=0;pending(company)>0&&n<200;n++){checkTime();worker.tick();}
        assertEquals(0L,pending(company));assertEquals(0,gate.activeWorkers());
    }
    long pending(Scope scope){return db.queryForObject("SELECT COUNT(*) FROM finance_first_settlement_facts WHERE organization_id=? AND pending=TRUE",Long.class,scope.organizationId());}
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
        for(var e:executors)e.shutdownNow();
        for(var e:executors)assertTrue(e.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS),"Owned executor survived the shared cleanup deadline");
        assertEquals(0,source.activeConnections.get());assertTrue(source.executing.isEmpty());assertTrue(source.openStatements.isEmpty());
        assertEquals(0,dataSource.getHikariPoolMXBean().getActiveConnections());
        if(gateDirectory!=null)Files.delete(gateDirectory);
        saved.put("executorsTerminated",executors.stream().allMatch(ExecutorService::isTerminated));
        saved.put("activeObservedConnections",source.activeConnections.get());saved.put("activePooledConnections",dataSource.getHikariPoolMXBean().getActiveConnections());
        saved.put("openStatements",source.openStatements.size());saved.put("executingStatements",source.executing.size());
        saved.put("cancelCalls",source.cancelCalls.get());saved.put("cancelErrors",source.cancelErrors);
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
            activeConnections.incrementAndGet();boolean[] closed={false};
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close")&&!closed[0]){closed[0]=true;activeConnections.decrementAndGet();}
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
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,x)->{
                        if(m.getName().equals("close"))openStatements.remove(actual);
                        if(m.getName().equals("executeQuery")){
                            if(sql.equals("SELECT SLEEP(10)"))sleep=actual;
                            executing.add(actual);
                            try{return invoke(actual,m,x);}
                            catch(Throwable failure){var e=FinanceFirstSettlementLoadIntegrationTest.findSql(failure);
                                if(a!=null&&e!=null)a.errors.add(Map.of("state",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode(),"driverException",e.getClass().getSimpleName()));
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
