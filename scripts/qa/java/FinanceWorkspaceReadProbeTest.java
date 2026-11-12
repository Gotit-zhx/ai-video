package com.yingdao.aicommerce.finance;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Standalone, no database or Spring context. Compile/run together with the probe. */
public final class FinanceWorkspaceReadProbeTest {
    static Class<?> probe;
    static Object call(String name,Class<?>[] types,Object... args)throws Exception{
        try{return probe.getDeclaredMethod(name,types).invoke(null,args);}
        catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    static Object canonical(Object value)throws Exception{return call("canonical",new Class<?>[]{Object.class},value);}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void denied(Runnable action){try{action.run();throw new AssertionError("Guard accepted forbidden operation");}catch(SecurityException expected){}}
    public static void main(String[] args)throws Exception{
        try{probe=Class.forName("com.yingdao.aicommerce.finance.FinanceWorkspaceReadProbe");}
        catch(ClassNotFoundException absent){throw new AssertionError("Read-only comparison probe is absent",absent);}
        check(canonical(Map.of("n",new BigDecimal("10.000000"))).equals(canonical(Map.of("n",10L))),"Exact numeric normalization");
        check(!canonical(Map.of("n","10")).equals(canonical(Map.of("n",10))),"Strings must remain distinct from numbers");
        var nullable=new LinkedHashMap<String,Object>();nullable.put("n",null);
        check(!canonical(nullable).equals(canonical(Map.of("n",0))),"Unknown differs from zero");
        check(!canonical(nullable).equals(canonical(Map.of())),"Absent differs from null");
        check(canonical(List.of(Map.of("n",2),Map.of("n",1))).equals(canonical(List.of(Map.of("n",1),Map.of("n",2)))),"Fact ordering is irrelevant");
        check(!canonical(List.of(1,1)).equals(canonical(List.of(1))),"Duplicate rows matter");
        check(canonical(java.sql.Date.valueOf("2026-10-01")).equals("2026-10-01"),"SQL date survives projection JSON");
        check(canonical(Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"))).equals("2026-10-01T00:00:00Z"),"SQL timestamp survives projection JSON");
        for(String sql:List.of("SELECT 1","SELECT JSON_EXTRACT(raw_json,'$.into') FROM finance_records WHERE id=?"))
            call("requireSelect",new Class<?>[]{String.class},sql);
        for(String sql:List.of("UPDATE finance_records SET amount=0","SELECT 1; DELETE FROM finance_records","SELECT 1 INTO OUTFILE '/tmp/qa'","SELECT GET_LOCK('x',0)","SELECT * FROM finance_records FOR UPDATE","SELECT @x := 1","SELECT 1 /* unsafe */","WITH changed AS (DELETE FROM finance_records) SELECT 1")){
            try{call("requireSelect",new Class<?>[]{String.class},sql);throw new AssertionError("Unsafe SQL accepted");}catch(SecurityException expected){}
        }
        call("silenceLogging",new Class<?>[]{});
        int[] physical={0};
        Connection raw=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(Set.of("commit","rollback","close").contains(m.getName()))physical[0]++;
            if(m.getName().equals("prepareStatement"))return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(statement,sm,sa)->{
                if(sm.getName().equals("executeQuery"))return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(result,rm,ra)->null);
                return null;
            });
            if(m.getName().equals("isClosed"))return false;
            if(m.getName().equals("isReadOnly"))return true;
            if(m.getName().equals("getTransactionIsolation"))return Connection.TRANSACTION_REPEATABLE_READ;
            if(m.getName().equals("getAutoCommit"))return false;
            return null;
        });
        Connection held=(Connection)call("heldConnection",new Class<?>[]{Connection.class},raw);
        held.commit();held.rollback();held.close();check(physical[0]==0,"Reader cannot end held snapshot");
        denied(()->{try{held.setReadOnly(false);}catch(SQLException e){throw new RuntimeException(e);}});
        denied(()->{try{held.setAutoCommit(true);}catch(SQLException e){throw new RuntimeException(e);}});
        denied(()->{try{held.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);}catch(SQLException e){throw new RuntimeException(e);}});
        denied(()->{try{held.unwrap(Connection.class);}catch(SQLException e){throw new RuntimeException(e);}});
        denied(()->{try{held.prepareStatement("INSERT INTO finance_query_events(event_scope) VALUES('ALL')");}catch(SQLException e){throw new RuntimeException(e);}});
        PreparedStatement statement=held.prepareStatement("SELECT 1");
        check(statement.getConnection()==held,"Statement cannot expose physical connection");
        denied(()->{try{statement.unwrap(PreparedStatement.class);}catch(SQLException e){throw new RuntimeException(e);}});
        denied(()->{try{statement.executeUpdate();}catch(SQLException e){throw new RuntimeException(e);}});
        denied(()->{try{statement.execute("DELETE FROM finance_records");}catch(SQLException e){throw new RuntimeException(e);}});
        ResultSet result=statement.executeQuery();
        check(result.getStatement()==statement,"Result cannot expose physical statement");
        denied(()->{try{result.updateString(1,"unsafe");}catch(SQLException e){throw new RuntimeException(e);}});
        denied(()->{try{result.unwrap(ResultSet.class);}catch(SQLException e){throw new RuntimeException(e);}});
        var db=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(held,true));
        var repo=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper());
        check(repo.readSnapshot(180,()->repo.readSnapshot(15,()->42))==42,"Nested repository reader completes");
        check(physical[0]==0,"Repository transaction templates cannot commit/close physical snapshot");
        System.out.println("PASS canonical/snapshot/write-guard/nested-repository checks (no database)");
    }
}
