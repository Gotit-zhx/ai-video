package com.yingdao.aicommerce.finance;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only diagnostic for one unpublished incremental build candidate. Never publishes or changes eligibility. */
public final class FinanceWorkspaceBuildCandidateProbe {
    static final int TIMEOUT=FinanceWorkspaceReadProbe.TIMEOUT;
    static final Set<String> KNOWN_FAILURES=Set.of(
        "READ_ONLY_OPT_IN_REQUIRED","MISSING_RUNTIME_ENVIRONMENT","NO_ORGANIZATION","READ_ONLY_SNAPSHOT_UNCONFIRMED",
        "INVALID_OPTIONS","INVALID_DATE_OPTIONS","EXPLICIT_DATE_RANGE_REQUIRED","COMPLETE_MONTH_REQUIRED",
        "UNSUPPORTED_COMPOSITION_FILTER","UNSUPPORTED_DATABASE_PAGE","INVALID_PAGE_OPTIONS",
        "CANDIDATE_FIELDS_DIFFER","DATABASE_PAGE_FIELDS_DIFFER","HASH_FAILED");

    static final class NotApplicable extends RuntimeException {
        final String code;
        NotApplicable(String code){super(code);this.code=code;}
    }

    static Map<String,String> options(String[] args){return FinanceWorkspaceReadProbe.options(args);}
    static Filter filter(Map<String,String> args){
        if(args.containsKey("shop")||args.containsKey("group")||args.containsKey("currency"))
            throw new FinanceWorkspaceReadProbe.ProbeFailure("UNSUPPORTED_COMPOSITION_FILTER");
        Filter requested=FinanceWorkspaceReadProbe.filter(args);
        YearMonth month=YearMonth.from(requested.start());
        if(!requested.start().equals(month.atDay(1))||!requested.end().equals(month.atEndOfMonth()))
            throw new FinanceWorkspaceReadProbe.ProbeFailure("COMPLETE_MONTH_REQUIRED");
        return requested;
    }

    static String notApplicableReason(List<Map<String,Object>> rows){
        if(rows.isEmpty())return "PARTITION_ABSENT";
        if(rows.size()!=1)return "PARTITION_METADATA_AMBIGUOUS";
        var row=rows.getFirst();
        if(number(row.get("schema_version"))!=FinanceQueryProjections.SCHEMA_VERSION)return "PARTITION_SCHEMA_UNSUPPORTED";
        if(number(row.get("built_revision"))<=0||row.get("full_month_json")==null||row.get("partial_first_day_json")==null||row.get("spend_json")==null)
            return "SAVED_BASELINE_MISSING";
        if(Boolean.TRUE.equals(row.get("full_rebuild"))||number(row.get("full_rebuild"))!=0)return "FULL_REBUILD_REQUIRED";
        long built=number(row.get("built_revision")),requested=number(row.get("requested_revision"));
        if(requested==built)return "BASELINE_REVISIONS_EQUAL";
        if(requested<built)return "PENDING_REVISION_INVALID";
        if(number(row.get("dirty_days"))<=0)return "DIRTY_DAYS_ABSENT";
        return null;
    }

    static Map<String,Object> outcome(String operation,Map<String,Object> details){
        var result=new LinkedHashMap<String,Object>();
        result.put("operation",operation);result.putAll(details);result.put("operation",operation);
        result.put("candidateOnly",true);result.put("publishedProjection",false);result.put("cacheReadyVerified",false);
        result.put("fiveSecondAcceptanceClaimed",false);
        return result;
    }
    static void emit(String operation,Map<String,Object> details){FinanceWorkspaceReadProbe.emit(outcome(operation,details));}
    static <T>T measured(String operation,Supplier<T> action){
        long start=System.nanoTime();T result=action.get();
        emit(operation,Map.of("millis",(System.nanoTime()-start)/1_000_000));return result;
    }
    static boolean compare(String component,Object original,Object candidate,boolean ordered){
        Object nativeValue=FinanceWorkspaceReadProbe.normalize(original,!ordered);
        Object candidateValue=FinanceWorkspaceReadProbe.normalize(candidate,!ordered);
        long mismatch=FinanceWorkspaceReadProbe.differences(nativeValue,candidateValue);
        emit("candidate-compare",Map.of("component",component,"equal",mismatch==0,"differentFields",mismatch,
            "nativeHash",FinanceWorkspaceReadProbe.digest(nativeValue),"candidateHash",FinanceWorkspaceReadProbe.digest(candidateValue),
            "nativeRows",original instanceof Collection<?> c?c.size():map(original).size(),
            "candidateRows",candidate instanceof Collection<?> c?c.size():map(candidate).size()));
        return mismatch==0;
    }

    static Constructor<?> keyConstructor(){
        try{
            Class<?> key=Arrays.stream(FinanceQueryProjections.class.getDeclaredClasses()).filter(type->type.getSimpleName().equals("Key")).findFirst().orElseThrow();
            Constructor<?> constructor=key.getDeclaredConstructor(String.class,String.class,LocalDate.class);constructor.setAccessible(true);return constructor;
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Candidate key unavailable");}
    }
    static Method buildMethod(){
        try{
            Method method=FinanceQueryProjections.class.getDeclaredMethod("build",keyConstructor().getDeclaringClass(),Map.class);
            if(!Modifier.isPrivate(method.getModifiers()))throw new IllegalStateException("Candidate build scope changed");
            method.setAccessible(true);return method;
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Candidate build unavailable");}
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> calculate(FinanceQueryProjections projections,String organization,String platform,LocalDate month,Map<String,Object> previous){
        try{
            Object key=keyConstructor().newInstance(organization,platform,month);
            return (Map<String,Object>)buildMethod().invoke(projections,key,previous);
        }catch(InvocationTargetException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            throw new IllegalStateException("Candidate calculation failed");
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Candidate calculation unavailable");}
    }

    static String failureCode(Throwable failure){
        if(failure instanceof SecurityException)return "READ_ONLY_GUARD_REJECTED";
        if(failure instanceof FinanceWorkspaceReadProbe.ProbeFailure known&&KNOWN_FAILURES.contains(known.code))return known.code;
        return "CANDIDATE_PROBE_FAILED";
    }

    static void run(String[] arguments)throws Exception{
        if(!"true".equals(System.getenv("FINANCE_PERF_READ_ONLY")))
            throw new FinanceWorkspaceReadProbe.ProbeFailure("READ_ONLY_OPT_IN_REQUIRED");
        var args=options(arguments);var requested=filter(args);var selected=FinanceWorkspaceReadProbe.selection(args);
        YearMonth month=YearMonth.from(requested.start());
        try(var physical=DriverManager.getConnection(FinanceWorkspaceReadProbe.env("DB_URL"),FinanceWorkspaceReadProbe.env("DB_USERNAME"),FinanceWorkspaceReadProbe.env("DB_PASSWORD"))){
            physical.setReadOnly(true);physical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);physical.setAutoCommit(false);
            try(var statement=physical.createStatement()){
                statement.setQueryTimeout(TIMEOUT);
                statement.execute("SET SESSION MAX_EXECUTION_TIME="+(TIMEOUT*1000));
                statement.execute("SET SESSION TRANSACTION READ ONLY");
                statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
            }
            try{
                var db=new JdbcTemplate(new SingleConnectionDataSource(FinanceWorkspaceReadProbe.heldConnection(physical),true));db.setQueryTimeout(TIMEOUT);
                var session=db.queryForMap("SELECT @@session.transaction_read_only ro,@@session.transaction_isolation isolation_level");
                if(!physical.isReadOnly()||physical.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ
                    ||number(session.get("ro"))!=1||!"REPEATABLE-READ".equalsIgnoreCase(text(session.get("isolation_level"))))
                    throw new FinanceWorkspaceReadProbe.ProbeFailure("READ_ONLY_SNAPSHOT_UNCONFIRMED");
                String organization=System.getenv("FINANCE_PROBE_ORGANIZATION_ID");
                if(organization==null||organization.isBlank())organization=db.queryForObject(
                    "SELECT organization_id FROM finance_connections WHERE status<>'REMOVED' GROUP BY organization_id ORDER BY COUNT(*) DESC,organization_id LIMIT 1",String.class);
                if(organization==null||organization.isBlank())throw new FinanceWorkspaceReadProbe.ProbeFailure("NO_ORGANIZATION");
                final String selectedOrganization=organization;
                boolean relevantEvents=!db.queryForList("SELECT event_id FROM finance_query_events WHERE organization_id=? LIMIT 1",selectedOrganization).isEmpty();
                var partitions=db.queryForList("""
                    SELECT p.schema_version,p.built_revision,p.requested_revision,p.full_rebuild,p.full_rebuild_revision,
                           p.full_month_json,p.partial_first_day_json,p.spend_json,
                           (SELECT COUNT(*) FROM finance_query_dirty_days d
                            WHERE d.organization_id=p.organization_id AND d.platform_filter=p.platform_filter
                              AND d.partition_month=p.partition_month) dirty_days
                    FROM finance_query_partitions p
                    WHERE p.organization_id=? AND p.platform_filter=? AND p.partition_month=?
                    """,selectedOrganization,requested.platform(),month.atDay(1));
                String reason=notApplicableReason(partitions);
                if(reason!=null)throw new NotApplicable(reason);
                var previous=new LinkedHashMap<>(partitions.getFirst());
                emit("candidate-metadata",Map.of("applicable",true,"builtRevision",number(previous.get("built_revision")),
                    "requestedRevision",number(previous.get("requested_revision")),"dirtyDays",number(previous.get("dirty_days")),
                    "relevantEventsPresentAtSnapshotStart",relevantEvents));

                var scope=companyScope(selectedOrganization);var repo=new FinanceRepository(db,FinanceWorkspaceReadProbe.JSON);
                var finance=new FinanceService(repo,null,null,null);var spend=new FinanceAdSpendService(repo,finance);
                var projections=new FinanceQueryProjections(repo,finance,spend);
                var candidate=measured("candidate-calculation",()->calculate(projections,selectedOrganization,requested.platform(),month.atDay(1),previous));
                var candidateFacts=map(candidate.get("full"));var candidateSpend=map(candidate.get("spend"));
                var nativeFacts=measured("candidate-native-facts",()->finance.buildNativeDashboardFacts(scope,requested,false));
                var nativeSpend=measured("candidate-native-spend-facts",()->spend.buildNativeSpendFacts(scope,requested));
                boolean equal=true;
                for(String key:List.of("buckets","orders","advertising","reportingMonths"))
                    equal&=compare("facts."+key,nativeFacts.get(key),candidateFacts.get(key),false);
                for(String key:List.of("shops","sources","assignments"))
                    equal&=compare("spendFacts."+key,nativeSpend.get(key),candidateSpend.get(key),false);
                if(!equal)throw new FinanceWorkspaceReadProbe.ProbeFailure("CANDIDATE_FIELDS_DIFFER");

                var nativePage=measured("candidate-database-page-native",()->FinanceWorkspaceReadProbe.page(repo,scope,requested,selected));
                boolean facets=FinanceRecordSearch.numbers(selected.numbers()).isEmpty();
                long exact=FinanceWorkspaceQueryService.exactTotal(rows(candidateFacts.get("rawCounts")),requested,selected);
                Supplier<Map<String,Object>> read=()->repo.readSnapshot(TIMEOUT,()->facets
                    ?repo.withAuthoritativePageTotal(exact,()->FinanceWorkspaceReadProbe.page(repo,scope,requested,selected))
                    :FinanceWorkspaceReadProbe.page(repo,scope,requested,selected));
                var candidatePage=measured("candidate-database-page-count",read);
                var repeatedPage=measured("candidate-database-page-repeat",read);
                equal=compare("ordered-page.native-candidate",nativePage,candidatePage,true)
                    &compare("ordered-page.repeat",candidatePage,repeatedPage,true);
                emit("candidate-database-page-counts",Map.of("items",rows(nativePage.get("items")).size(),
                    "total",number(nativePage.get("total")),"candidateCountUsed",facets));
                if(!equal)throw new FinanceWorkspaceReadProbe.ProbeFailure("DATABASE_PAGE_FIELDS_DIFFER");
                emit("candidate-complete",Map.of("sameReadOnlyRepeatableReadSnapshot",true,"equal",true,
                    "publicFencesUnchanged",true,"publishedTruthPending",true,"fiveSecondGatePending",true,
                    "resultCacheEnabled",false,"redisUsed",false,"fxUsed",false,"httpOrBrowserMeasured",false));
            }finally{physical.rollback();}
        }
    }

    public static void main(String[] args){
        try{FinanceWorkspaceReadProbe.silenceLogging();run(args);}
        catch(NotApplicable unavailable){emit("candidate-not-applicable",Map.of("reason",unavailable.code,"applicable",false));System.exit(2);}
        catch(Throwable failure){emit("candidate-failed",Map.of("error",failureCode(failure)));System.exit(1);}
    }
}
