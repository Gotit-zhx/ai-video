package com.yingdao.aicommerce.finance;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Standalone, no database or Spring context. Compile/run together with both probes. */
public final class FinanceWorkspaceBuildCandidateProbeTest {
    static Class<?> probe;
    static Object call(String name,Class<?>[] types,Object... args)throws Exception{
        try{return probe.getDeclaredMethod(name,types).invoke(null,args);}
        catch(InvocationTargetException failure){
            if(failure.getCause() instanceof Exception exception)throw exception;
            throw failure;
        }
    }
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void rejected(String expected,Runnable action){
        try{action.run();throw new AssertionError("Expected rejection "+expected);}
        catch(RuntimeException failure){check(expected.equals(failure.getMessage()),"Expected "+expected+", got "+failure.getMessage());}
    }
    @SuppressWarnings("unchecked")
    static Map<String,String> options(String...args)throws Exception{return (Map<String,String>)call("options",new Class<?>[]{String[].class},(Object)args);}
    static FinanceData.Filter filter(Map<String,String> args)throws Exception{return (FinanceData.Filter)call("filter",new Class<?>[]{Map.class},args);}
    static Map<String,Object> partition(long schema,long built,long requested,boolean full,boolean payload){
        var row=new LinkedHashMap<String,Object>();
        row.put("schema_version",schema);row.put("built_revision",built);row.put("requested_revision",requested);row.put("full_rebuild",full);
        row.put("full_month_json",payload?"{}":null);row.put("partial_first_day_json",payload?"{}":null);row.put("spend_json",payload?"{}":null);
        row.put("dirty_days",1L);
        return row;
    }
    static String reason(List<Map<String,Object>> rows)throws Exception{return (String)call("notApplicableReason",new Class<?>[]{List.class},rows);}
    static void denied(Throwing action){try{action.run();throw new AssertionError("Guard accepted forbidden operation");}catch(SecurityException expected){}catch(Exception failure){throw new RuntimeException(failure);}}
    @FunctionalInterface interface Throwing {void run()throws Exception;}

    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        try{probe=Class.forName("com.yingdao.aicommerce.finance.FinanceWorkspaceBuildCandidateProbe");}
        catch(ClassNotFoundException absent){throw new AssertionError("Candidate-only comparison probe is absent",absent);}

        var month=filter(options("--month","2026-10","--platform","TIKTOK_SHOP","--view","orders","--kind","REFUND","--page","2"));
        check(month.start().equals(LocalDate.parse("2026-10-01"))&&month.end().equals(LocalDate.parse("2026-10-31")),"Month expands to one complete month");
        var explicit=filter(options("--start","2026-02-01","--end","2026-02-28"));
        check(explicit.start().equals(LocalDate.parse("2026-02-01"))&&explicit.end().equals(LocalDate.parse("2026-02-28")),"Exact full-month range is accepted");
        for(String[] invalid:List.of(
                new String[]{"--start","2026-10-02","--end","2026-10-31"},
                new String[]{"--start","2026-10-01","--end","2026-11-30"})){
            rejected("COMPLETE_MONTH_REQUIRED",()->{try{filter(options(invalid));}catch(Exception failure){throw new RuntimeException(failure.getMessage());}});
        }
        for(String[] invalid:List.of(
                new String[]{"--month","2026-10","--shop","secret-shop"},
                new String[]{"--month","2026-10","--group","secret-group"},
                new String[]{"--month","2026-10","--currency","USD"})){
            rejected("UNSUPPORTED_COMPOSITION_FILTER",()->{try{filter(options(invalid));}catch(Exception failure){throw new RuntimeException(failure.getMessage());}});
        }

        check("PARTITION_ABSENT".equals(reason(List.of())),"Absent partition is not applicable");
        check("PARTITION_SCHEMA_UNSUPPORTED".equals(reason(List.of(partition(2,4,5,false,true)))),"Wrong schema is not applicable");
        check("SAVED_BASELINE_MISSING".equals(reason(List.of(partition(1,0,5,false,true)))),"Zero built revision has no saved baseline");
        check("SAVED_BASELINE_MISSING".equals(reason(List.of(partition(1,4,5,false,false)))),"All saved bodies are required");
        check("FULL_REBUILD_REQUIRED".equals(reason(List.of(partition(1,4,5,true,true)))),"Full rebuild is never forced through candidate probe");
        check("BASELINE_REVISIONS_EQUAL".equals(reason(List.of(partition(1,5,5,false,true)))),"Equal revisions make this pending-revision diagnostic inapplicable without claiming public readiness");
        check("PENDING_REVISION_INVALID".equals(reason(List.of(partition(1,6,5,false,true)))),"Requested revision must be newer than built revision");
        var noDirtyDays=partition(1,4,5,false,true);noDirtyDays.put("dirty_days",0L);
        check("DIRTY_DAYS_ABSENT".equals(reason(List.of(noDirtyDays))),"Incremental calculation requires captured dirty days");
        check(reason(List.of(partition(1,4,5,false,true)))==null,"Incremental pending partition is applicable");

        Map<String,Object> outcome=(Map<String,Object>)call("outcome",new Class<?>[]{String.class,Map.class},"complete",Map.of("equal",true,"candidateOnly",false));
        check("complete".equals(outcome.get("operation")),"Operation label is fixed");
        check(Boolean.TRUE.equals(outcome.get("candidateOnly")),"Every outcome is candidate-only");
        check(Boolean.FALSE.equals(outcome.get("publishedProjection")),"Candidate is never published proof");
        check(Boolean.FALSE.equals(outcome.get("cacheReadyVerified")),"Candidate never verifies public cache readiness");
        check(Boolean.FALSE.equals(outcome.get("fiveSecondAcceptanceClaimed")),"Candidate never claims the five-second gate");
        check("CANDIDATE_PROBE_FAILED".equals(call("failureCode",new Class<?>[]{Throwable.class},new SQLException("password=secret SELECT *"))),"SQL failures are sanitized");

        Method build=(Method)call("buildMethod",new Class<?>[]{});
        Constructor<?> key=(Constructor<?>)call("keyConstructor",new Class<?>[]{});
        check(build.getName().equals("build")&&Modifier.isPrivate(build.getModifiers())&&build.getParameterCount()==2,"Exact private calculation method is wired");
        check(Modifier.isPrivate(key.getDeclaringClass().getModifiers())&&key.getParameterCount()==3,"Exact private projection key is wired");

        for(String sql:List.of("SELECT 1","SELECT full_month_json FROM finance_query_partitions WHERE organization_id=?"))FinanceWorkspaceReadProbe.requireSelect(sql);
        for(String sql:List.of("UPDATE finance_query_partitions SET built_revision=1","SELECT * FROM finance_query_partitions FOR UPDATE","SELECT 1; DELETE FROM finance_query_dirty_days"))
            denied(()->FinanceWorkspaceReadProbe.requireSelect(sql));
        int[] physicalEnds={0};
        Connection raw=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(Set.of("commit","rollback","close").contains(m.getName()))physicalEnds[0]++;
            if(m.getName().equals("isClosed"))return false;
            if(m.getName().equals("isReadOnly"))return true;
            if(m.getName().equals("getTransactionIsolation"))return Connection.TRANSACTION_REPEATABLE_READ;
            if(m.getName().equals("getAutoCommit"))return false;
            return null;
        });
        Connection held=FinanceWorkspaceReadProbe.heldConnection(raw);
        held.commit();held.rollback();held.close();check(physicalEnds[0]==0,"Candidate calculation cannot end held snapshot");
        denied(()->held.setReadOnly(false));denied(()->held.setAutoCommit(true));denied(()->held.prepareStatement("DELETE FROM finance_query_dirty_days"));

        System.out.println("PASS candidate options/metadata/labels/reflection/write-guard checks (no database)");
    }
}
