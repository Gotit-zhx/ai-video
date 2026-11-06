package com.yingdao.aicommerce.finance;

import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Compare two class directories against one held, explicitly read-only snapshot.
 * Args: extracted BOOT-INF, probe classes, candidate classes, YYYY-MM, dashboard|summary.
 * No Spring context/workers/login are started; only counts, timings and hashes leave SQL. */
public final class FinanceColdReadProbe {
    static final ObjectMapper JSON=new ObjectMapper();
    static Object canonical(Object value){
        if(value instanceof Map<?,?> map){var out=new TreeMap<String,Object>();map.forEach((k,v)->out.put(String.valueOf(k),canonical(v)));return out;}
        if(value instanceof List<?> list)return list.stream().map(FinanceColdReadProbe::canonical)
            .sorted(Comparator.comparing(JSON::writeValueAsString)).toList();
        if(value instanceof Number n)return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return value;
    }
    static String hash(Object value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(value))));}
    public static Map<String,Object> read(Connection held,String month,String mode){
        var db=new JdbcTemplate(new SingleConnectionDataSource(held,true));db.setQueryTimeout(120);
        var org=db.queryForObject("SELECT organization_id FROM finance_connections WHERE status<>'REMOVED' GROUP BY organization_id ORDER BY COUNT(*) DESC LIMIT 1",String.class);
        var repo=new FinanceRepository(db,JSON);var scope=companyScope(org);var m=YearMonth.parse(month);
        // The summary reference can be very slow: bound this equality check to two
        // days. Full-month candidate timing is a separate measurement, not a shortcut.
        var filter=new Filter(m.atDay(1),mode.equals("summary")?m.atDay(2):m.atEndOfMonth(),"","","","day");
        if(mode.equals("summary"))return new FinanceCostReport(repo).summary(scope,filter,"SETTLEMENT");
        var fx=db.queryForObject("SELECT id FROM finance_fx_snapshots ORDER BY rate_time DESC LIMIT 1",String.class);
        if(fx==null||fx.isBlank())throw new IllegalStateException("An existing FX snapshot is required");
        var response=new FinanceService(repo,null,null,new FinanceFx(repo,JSON)).dashboard(scope,filter,true,fx);
        var metrics=new TreeMap<String,Object>();
        for(String key:List.of("totals","trend","ranking","fees","statuses","profit","productCosts","currencies","reportingMonths"))metrics.put(key,response.get(key));
        return metrics;
    }
    public static void main(String[] args)throws Exception{
        if(!"true".equals(System.getenv("FINANCE_PERF_READ_ONLY")))throw new IllegalStateException("Read-only opt-in required");
        var boot=Path.of(args[0]);var probes=Path.of(args[1]);var candidate=Path.of(args[2]);
        var jars=new ArrayList<URL>();try(var files=Files.list(boot.resolve("lib"))){for(var file:files.filter(p->p.toString().endsWith(".jar")).sorted().toList())jars.add(file.toUri().toURL());}
        try(var connection=DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))){
            connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){statement.execute("SET SESSION MAX_EXECUTION_TIME=120000");statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");}
            var held=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,values)->{
                // Repository transaction templates must not commit between variants.
                if(Set.of("commit","rollback","close").contains(method.getName()))return null;
                if(method.getName().equals("setAutoCommit")&&Boolean.TRUE.equals(values[0]))throw new IllegalStateException("Snapshot must remain open");
                try{return method.invoke(connection,values);}catch(InvocationTargetException e){throw e.getCause();}
            });
            String expected=null;var measurements=new ArrayList<Map<String,Object>>();
            for(boolean changed:List.of(false,true)){
                var urls=new ArrayList<URL>();if(changed)urls.add(candidate.toUri().toURL());
                urls.add(probes.toUri().toURL());urls.add(boot.resolve("classes").toUri().toURL());urls.addAll(jars);
                try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())){
                    var method=loader.loadClass(FinanceColdReadProbe.class.getName()).getMethod("read",Connection.class,String.class,String.class);
                    long start=System.nanoTime();Object result=method.invoke(null,held,args[3],args[4]);long millis=(System.nanoTime()-start)/1_000_000;
                    String actual=hash(result);if(expected==null)expected=actual;else if(!expected.equals(actual))throw new IllegalStateException("Financial metrics differ between class sets");
                    var measurement=Map.<String,Object>of("candidate",changed,"millis",millis,"financialHash",actual);measurements.add(measurement);System.out.println(JSON.writeValueAsString(measurement));
                }
            }
            connection.rollback();
            System.out.println(JSON.writeValueAsString(Map.of("month",args[3],"mode",args[4],"sameSnapshot",true,"equal",true,"productionWrites",false,"measurements",measurements)));
        }
    }
}
