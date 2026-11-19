package com.yingdao.aicommerce.finance;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Standalone native/projection equality and database-page probe. No Spring, FX, Redis or workers. */
public final class FinanceWorkspaceReadProbe {
    static final ObjectMapper JSON=new ObjectMapper();
    static final int TIMEOUT=180;
    static final class ProbeFailure extends RuntimeException {
        final String code;
        ProbeFailure(String code){super(code);this.code=code;}
    }
    static Object canonical(Object value){return normalize(value,true);}
    static Object normalize(Object value,boolean sortLists){
        if(value instanceof java.sql.Date day)return day.toLocalDate().toString();
        if(value instanceof Timestamp time)return time.toInstant().toString();
        if(value instanceof TemporalAccessor)return value.toString();
        if(value instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();
        if(value instanceof Map<?,?> map){
            var result=new TreeMap<String,Object>();
            map.forEach((key,item)->result.put(String.valueOf(key),normalize(item,sortLists)));return result;
        }
        if(value instanceof Collection<?> list){
            var result=new ArrayList<Object>();list.forEach(item->result.add(normalize(item,sortLists)));
            if(sortLists)result.sort(Comparator.comparing(JSON::writeValueAsString));return result;
        }
        return value;
    }
    static String digest(Object value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));}
        catch(Exception failure){throw new ProbeFailure("HASH_FAILED");}
    }
    static long differences(Object left,Object right){
        if(Objects.equals(left,right))return 0;
        if(left instanceof Map<?,?> a&&right instanceof Map<?,?> b){
            var keys=new HashSet<Object>(a.keySet());keys.addAll(b.keySet());long count=0;
            for(var key:keys)count+=!a.containsKey(key)||!b.containsKey(key)?1:differences(a.get(key),b.get(key));return count;
        }
        if(left instanceof List<?> a&&right instanceof List<?> b){
            long count=Math.abs(a.size()-b.size());for(int i=0;i<Math.min(a.size(),b.size());i++)count+=differences(a.get(i),b.get(i));return count;
        }
        return 1;
    }
    static void emit(Map<String,Object> value){System.out.println(JSON.writeValueAsString(value));}
    static boolean compare(String component,Object original,Object projected,boolean ordered){
        Object a=normalize(original,!ordered),b=normalize(projected,!ordered);
        long mismatch=differences(a,b);
        emit(Map.of("operation","compare","component",component,"equal",mismatch==0,"differentFields",mismatch,
            "nativeHash",digest(a),"projectionHash",digest(b),"nativeRows",original instanceof Collection<?> c?c.size():map(original).size(),
            "projectionRows",projected instanceof Collection<?> c?c.size():map(projected).size()));
        return mismatch==0;
    }
    static <T>T measured(String operation,Supplier<T> action){
        long start=System.nanoTime();T result=action.get();
        emit(Map.of("operation",operation,"millis",(System.nanoTime()-start)/1_000_000));return result;
    }

    /** A deny-by-default statement boundary, in addition to MySQL's READ ONLY transaction. */
    static void requireSelect(String sql){
        if(sql==null)throw new SecurityException("READ_ONLY_SQL");
        var plain=new StringBuilder();
        for(int i=0;i<sql.length();i++){
            char c=sql.charAt(i);
            if(c=='\''||c=='"'||c=='`'){
                char quote=c;boolean closed=false;plain.append(' ');
                while(++i<sql.length()){
                    c=sql.charAt(i);
                    if(c=='\\'){i++;continue;}
                    if(c==quote){if(i+1<sql.length()&&sql.charAt(i+1)==quote){i++;continue;}closed=true;break;}
                }
                if(!closed)throw new SecurityException("READ_ONLY_SQL");
            }else plain.append(c);
        }
        String text=plain.toString().toUpperCase(Locale.ROOT);
        if(!text.stripLeading().startsWith("SELECT ")&&!text.stripLeading().startsWith("SELECT\n"))throw new SecurityException("READ_ONLY_SQL");
        if(text.contains(";")||text.contains("/*")||text.contains("--")||text.contains("#")||text.contains(":=")
            ||text.matches("(?s).*\\b(INTO|OUTFILE|DUMPFILE|GET_LOCK|RELEASE_LOCK|RELEASE_ALL_LOCKS|SLEEP|BENCHMARK|LOAD_FILE|SYS_EXEC|SYS_EVAL)\\b.*")
            ||text.matches("(?s).*\\bFOR\\s+(UPDATE|SHARE)\\b.*")||text.matches("(?s).*\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b.*"))
            throw new SecurityException("READ_ONLY_SQL");
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    static Connection heldConnection(Connection raw){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(self,method,args)->{
            String name=method.getName();
            if(Set.of("commit","rollback","close").contains(name)&&method.getParameterCount()==0)return null;
            if(name.equals("setReadOnly")&&Boolean.TRUE.equals(args[0]))return null;
            if(name.equals("setAutoCommit")&&Boolean.FALSE.equals(args[0]))return null;
            if(name.equals("setTransactionIsolation")&&Objects.equals(args[0],Connection.TRANSACTION_REPEATABLE_READ))return null;
            if(name.startsWith("set")||Set.of("unwrap","abort","prepareCall","releaseSavepoint","beginRequest","endRequest").contains(name))throw new SecurityException("READ_ONLY_CONNECTION");
            if(name.equals("isWrapperFor"))return false;
            if(name.equals("prepareStatement")){requireSelect((String)args[0]);return guardedStatement((Statement)invoke(raw,method,args),(Connection)self,true);}
            if(name.equals("createStatement"))return guardedStatement((Statement)invoke(raw,method,args),(Connection)self,false);
            if(name.equals("getMetaData"))throw new SecurityException("READ_ONLY_CONNECTION");
            return invoke(raw,method,args);
        });
    }
    static Statement guardedStatement(Statement raw,Connection held,boolean prepared){
        Class<?> type=prepared?PreparedStatement.class:Statement.class;
        return (Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{type},(self,method,args)->{
            String name=method.getName();
            if(name.equals("getConnection"))return held;
            if(name.equals("unwrap")||name.contains("Batch")||name.contains("Update")||name.equals("executeLargeUpdate"))throw new SecurityException("READ_ONLY_STATEMENT");
            if(name.equals("isWrapperFor"))return false;
            if(name.startsWith("execute")){
                if(args!=null&&args.length>0&&args[0] instanceof String sql)requireSelect(sql);
                else if(!prepared)throw new SecurityException("READ_ONLY_STATEMENT");
            }
            if(name.equals("setQueryTimeout")&&((Integer)args[0]<=0||(Integer)args[0]>TIMEOUT))throw new SecurityException("READ_ONLY_TIMEOUT");
            Object result=invoke(raw,method,args);
            if(result instanceof ResultSet rs)return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
                String n=m.getName();if(n.equals("getStatement"))return self;
                if(n.equals("unwrap")||n.startsWith("update")||Set.of("insertRow","deleteRow","moveToInsertRow").contains(n))throw new SecurityException("READ_ONLY_RESULT");
                if(n.equals("isWrapperFor"))return false;return invoke(rs,m,a);
            });
            return result;
        });
    }
    static Map<String,String> options(String[] args){
        var values=new HashMap<String,String>();
        var allowed=Set.of("month","start","end","platform","shop","group","currency","view","kind","page","all-orders");
        for(int i=0;i<args.length;i+=2){
            if(i+1==args.length||!args[i].startsWith("--")||!allowed.contains(args[i].substring(2))||values.put(args[i].substring(2),args[i+1])!=null)throw new ProbeFailure("INVALID_OPTIONS");
        }
        return values;
    }
    static String env(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new ProbeFailure("MISSING_RUNTIME_ENVIRONMENT");return value;}
    static Filter filter(Map<String,String> args){
        if(args.containsKey("month")&&(args.containsKey("start")||args.containsKey("end")))throw new ProbeFailure("INVALID_DATE_OPTIONS");
        YearMonth month=args.containsKey("month")?YearMonth.parse(args.get("month")):null;
        if(month==null&&(!args.containsKey("start")||!args.containsKey("end")))throw new ProbeFailure("EXPLICIT_DATE_RANGE_REQUIRED");
        return new Filter(month==null?LocalDate.parse(args.get("start")):month.atDay(1),month==null?LocalDate.parse(args.get("end")):month.atEndOfMonth(),
            args.getOrDefault("shop",""),args.getOrDefault("platform",""),args.getOrDefault("currency",""),"day",args.getOrDefault("group",""));
    }
    static FinanceWorkspaceQueryService.Selection selection(Map<String,String> args){
        String view=args.getOrDefault("view","fees");
        var kinds=switch(view){case "settlements"->Set.of("SETTLEMENT","BILL","PENDING");case "reconciliation"->Set.of("BILL");
            case "orders"->Set.of("ORDER","REFUND");case "fees"->Set.of("FEE","COST");case "ads-support"->Set.of("AD","AD_GMV","FEE");default->throw new ProbeFailure("UNSUPPORTED_DATABASE_PAGE");};
        String kind=args.getOrDefault("kind",switch(view){case "settlements"->"SETTLEMENT";case "reconciliation"->"BILL";case "orders"->"ORDER";case "ads-support"->"AD";default->"FEE";});
        int page=Integer.parseInt(args.getOrDefault("page","1"));String all=args.getOrDefault("all-orders","false");
        if(!kinds.contains(kind)||page<1||!Set.of("true","false").contains(all)||all.equals("true")&&(!view.equals("orders")||!kind.equals("ORDER")))throw new ProbeFailure("INVALID_PAGE_OPTIONS");
        return new FinanceWorkspaceQueryService.Selection(view,kind,Boolean.parseBoolean(all),false,"",page,1,System.getenv().getOrDefault("FINANCE_PROBE_NUMBERS",""),"","");
    }
    static Map<String,Object> page(FinanceRepository repo,Scope scope,Filter filter,FinanceWorkspaceQueryService.Selection selected){
        if(selected.view().equals("reconciliation"))return repo.reconciliationPage(scope,filter,selected.page(),50,selected.numbers());
        if(selected.view().equals("ads-support")&&selected.kind().equals("FEE"))return repo.advertisingFeeDetails(scope,filter,selected.page(),50,selected.numbers());
        return repo.details(scope,filter,selected.kind(),selected.page(),50,selected.numbers(),selected.allOrders());
    }
    static void silenceLogging(){
        var factory=org.slf4j.LoggerFactory.getILoggerFactory();
        if(!(factory instanceof ch.qos.logback.classic.LoggerContext logs))throw new ProbeFailure("UNSUPPORTED_LOGGING_BACKEND");
        logs.reset();logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        java.util.logging.LogManager.getLogManager().reset();
    }
    static void run(String[] arguments)throws Exception{
        if(!"true".equals(System.getenv("FINANCE_PERF_READ_ONLY")))throw new ProbeFailure("READ_ONLY_OPT_IN_REQUIRED");
        var args=options(arguments);var requested=filter(args);var selected=selection(args);
        var nativeFilter=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),"","day",requested.groupId());
        try(var physical=DriverManager.getConnection(env("DB_URL"),env("DB_USERNAME"),env("DB_PASSWORD"))){
            physical.setReadOnly(true);physical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);physical.setAutoCommit(false);
            try(var statement=physical.createStatement()){
                statement.execute("SET SESSION MAX_EXECUTION_TIME="+(TIMEOUT*1000));
                statement.execute("SET SESSION TRANSACTION READ ONLY");
                statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
            }
            try{
                var db=new JdbcTemplate(new SingleConnectionDataSource(heldConnection(physical),true));db.setQueryTimeout(TIMEOUT);
                var session=db.queryForMap("SELECT @@session.transaction_read_only ro,@@session.transaction_isolation isolation_level");
                if(!physical.isReadOnly()||!Objects.equals(physical.getTransactionIsolation(),Connection.TRANSACTION_REPEATABLE_READ)
                    ||number(session.get("ro"))!=1||!"REPEATABLE-READ".equalsIgnoreCase(text(session.get("isolation_level"))))throw new ProbeFailure("READ_ONLY_SNAPSHOT_UNCONFIRMED");
                String org=System.getenv("FINANCE_PROBE_ORGANIZATION_ID");
                if(org==null||org.isBlank())org=db.queryForObject("SELECT organization_id FROM finance_connections WHERE status<>'REMOVED' GROUP BY organization_id ORDER BY COUNT(*) DESC,organization_id LIMIT 1",String.class);
                if(org==null||org.isBlank())throw new ProbeFailure("NO_ORGANIZATION");
                var scope=companyScope(org);var repo=new FinanceRepository(db,JSON);
                // Native fact and page methods require no exchange-rate object or provider.
                var finance=new FinanceService(repo,null,null,null);var spend=new FinanceAdSpendService(repo,finance);
                var projections=new FinanceQueryProjections(repo,finance,spend);
                var snapshot=measured("projection-read-cache-disabled",()->projections.find(scope,nativeFilter)).orElseThrow(()->new ProbeFailure("PROJECTION_NOT_READY"));
                var nativeFacts=measured("native-facts",()->finance.buildNativeDashboardFacts(scope,nativeFilter,false));
                var nativeSpend=measured("native-spend-facts",()->spend.buildNativeSpendFacts(scope,nativeFilter));
                var factKeys=new TreeSet<>(snapshot.facts().keySet());factKeys.removeAll(Set.of("_financeDataRevision","_financeFactsBuilt"));
                boolean equal=compare("facts.fields",new TreeSet<>(nativeFacts.keySet()),factKeys,false)
                    &compare("spendFacts.fields",new TreeSet<>(nativeSpend.keySet()),new TreeSet<>(snapshot.spendFacts().keySet()),false);
                for(String key:List.of("buckets","orders","advertising","reportingMonths"))equal&=compare("facts."+key,nativeFacts.get(key),snapshot.facts().get(key),false);
                for(String key:List.of("shops","sources","assignments"))equal&=compare("spendFacts."+key,nativeSpend.get(key),snapshot.spendFacts().get(key),false);
                if(!equal)throw new ProbeFailure("NATIVE_PROJECTION_FIELDS_DIFFER");
                // Raw native count and ordered page must agree with projection-assisted count, including later empty pages.
                var raw=measured("database-page-native-count-cache-disabled",()->page(repo,scope,requested,selected));
                boolean facets=FinanceRecordSearch.numbers(selected.numbers()).isEmpty();
                long exact=FinanceWorkspaceQueryService.exactTotal(snapshot.rawCounts(),requested,selected);
                Supplier<Map<String,Object>> read=()->repo.readSnapshot(TIMEOUT,()->facets?repo.withAuthoritativePageTotal(exact,()->page(repo,scope,requested,selected)):page(repo,scope,requested,selected));
                var projected=measured("database-page-projection-count-cache-disabled",read);
                var repeat=measured("database-page-repeat-result-cache-disabled",read);
                equal=compare("ordered-page.native-projection",raw,projected,true)&compare("ordered-page.repeat",projected,repeat,true);
                emit(Map.of("operation","database-page-counts","items",rows(raw.get("items")).size(),"total",number(raw.get("total")),"projectionCountUsed",facets));
                if(!equal)throw new ProbeFailure("DATABASE_PAGE_FIELDS_DIFFER");
                emit(Map.of("operation","complete","sameReadOnlyRepeatableReadSnapshot",true,"equal",true,"resultCacheEnabled",false,
                    "redisUsed",false,"fxUsed",false,"httpOrBrowserMeasured",false,"fiveSecondAcceptanceClaimed",false));
            }finally{physical.rollback();}
        }
    }
    public static void main(String[] args){
        try{silenceLogging();run(args);}catch(Throwable failure){
            // JDBC exceptions may contain SQL/bound identifiers/credentials. Never print causes or stack traces.
            emit(Map.of("operation","failed","error",failure instanceof ProbeFailure p?p.code:failure instanceof SecurityException?"READ_ONLY_GUARD_REJECTED":"READ_PROBE_FAILED"));
            System.exit(1);
        }
    }
}
