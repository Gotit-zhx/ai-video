package com.yingdao.aicommerce.finance;

import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Uses release classes with SELECT-only database access; never boots application workers. */
public final class FinanceAdvertisingReleaseVerify {
    static void validate(String sql)throws SQLException{
        String normalized=sql.strip().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
        if(!normalized.startsWith("SELECT ")||normalized.contains(";")
                ||normalized.matches(".*\\b(FOR UPDATE|FOR SHARE|INTO OUTFILE|INTO DUMPFILE|LOCK IN SHARE MODE|GET_LOCK|RELEASE_LOCK|LOAD_FILE|SLEEP|BENCHMARK)\\b.*"))
            throw new SQLNonTransientException("Verification permits SELECT only");
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    static Connection guarded(Connection delegate){
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("setReadOnly")&&Boolean.FALSE.equals(args[0]))return null;
            if(name.equals("isReadOnly"))return true;
            if(name.equals("unwrap")||name.equals("prepareCall"))throw new SQLException("Unwrapping and stored calls disabled");
            if(name.equals("isWrapperFor"))return false;
            if(name.equals("prepareStatement"))validate((String)args[0]);
            Object value=invoke(delegate,method,args);
            if(value instanceof Statement statement){
                boolean prepared=statement instanceof PreparedStatement;Class<?> type=prepared?PreparedStatement.class:Statement.class;
                return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                    String operation=m.getName();
                    if(operation.equals("getConnection"))return proxy;
                    if(operation.equals("unwrap")||operation.equals("addBatch")||operation.contains("Update")||operation.contains("Batch"))throw new SQLException("Writes disabled");
                    if(operation.equals("isWrapperFor"))return false;
                    if(operation.startsWith("execute")&&!prepared)validate((String)a[0]);
                    return invoke(statement,m,a);
                });
            }
            return value;
        });
    }
    static BigDecimal money(Object value){BigDecimal amount=decimal(value);return amount==null?BigDecimal.ZERO:amount;}
    static void equalMoney(Object expected,Object actual){
        if(money(expected).setScale(2,RoundingMode.HALF_UP).compareTo(money(actual).setScale(2,RoundingMode.HALF_UP))!=0)
            throw new IllegalStateException("Advertising control total differs");
    }
    public static void main(String[] args)throws Exception{
        var json=new ObjectMapper();var settings=new HikariConfig();
        settings.setJdbcUrl(System.getenv("DB_URL"));settings.setUsername(System.getenv("DB_USERNAME"));settings.setPassword(System.getenv("DB_PASSWORD"));
        settings.setReadOnly(true);settings.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");settings.setMaximumPoolSize(1);settings.setMinimumIdle(0);
        try(var pool=new HikariDataSource(settings)){
            var db=new JdbcTemplate(new AbstractDataSource(){
                public Connection getConnection()throws SQLException{return guarded(pool.getConnection());}
                public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Explicit credentials disabled");}
            });db.setQueryTimeout(120);
            if(number(db.queryForObject("SELECT @@session.transaction_read_only",Long.class))!=1)throw new IllegalStateException("Session is not read-only");
            if(!FinanceAds.ADVERTISER_FIELDS.equals(List.of("advertiser_id","name","currency","timezone")))throw new IllegalStateException("Incorrect advertiser contract");
            var repo=new FinanceRepository(db,json);String company=db.queryForObject("SELECT id FROM organizations WHERE code=?",String.class,"heyi");
            Scope scope=companyScope(company);var fx=new FinanceFx(repo,json);
            var month=YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1);
            var filter=new Filter(month.atDay(1),month.atEndOfMonth(),"","","","day");
            String snapshot=db.queryForObject("SELECT id FROM finance_fx_snapshots ORDER BY rate_time DESC LIMIT 1",String.class);
            var quote=fx.quote(snapshot);var service=new FinanceService(repo,null,null,fx);
            var result=repo.readSnapshot(180,()->{
                var facts=new ArrayList<Map<String,Object>>();repo.dashboardBuckets(scope,filter,false,facts);
                var report=FinanceAdvertisingReport.build(facts,service.shops(scope),filter,true,quote,"day");
                var query=repo.query(scope,filter,"r");var parameters=new ArrayList<>(query.args());
                parameters.add(java.sql.Date.valueOf(filter.start()));parameters.add(java.sql.Date.valueOf(filter.end()));
                var control=db.queryForList("SELECT r.kind,r.currency,COUNT(*) records,COUNT(r.amount) known_amounts,SUM(r.amount) amount FROM finance_records r FORCE INDEX(idx_finance_company_dashboard) WHERE "+query.where()
                        +" AND r.record_date>=? AND r.record_date<=? AND (r.kind='AD' OR (r.kind='FEE' AND "+FinanceAdvertisingReport.ledgerPredicate("r")+")) GROUP BY r.kind,r.currency",parameters.toArray());
                long records=0,known=0;BigDecimal total=BigDecimal.ZERO;
                for(var row:control){records+=number(row.get("records"));known+=number(row.get("known_amounts"));total=total.add(money(quote.convert(row.get("amount"),text(row.get("currency")))));}
                var summary=map(report.get("total"));
                if(records!=number(summary.get("records"))||known!=number(summary.get("known_amounts")))throw new IllegalStateException("Advertising record coverage differs");
                equalMoney(total,summary.get("knownAmount"));
                for(String dimension:List.of("shops","groups","categories"))equalMoney(summary.get("knownAmount"),rows(report.get(dimension)).stream()
                        .map(row->money(row.get("knownAmount"))).reduce(BigDecimal.ZERO,BigDecimal::add));
                return Map.of("readOnly",true,"aggregateChecksPassed",true,"advertiserNameContractPassed",true,
                        "start",filter.start().toString(),"end",filter.end().toString(),"records",records,
                        "shops",rows(report.get("shops")).size(),"amountCny",money(summary.get("knownAmount")).setScale(2,RoundingMode.HALF_UP),
                        "commissionCny",money(map(report.get("commission")).get("knownAmount")).setScale(2,RoundingMode.HALF_UP),
                        "independentRecords",number(map(report.get("independent")).get("records")));
            });
            System.out.println(json.writeValueAsString(result));
        }catch(Exception failure){System.err.println("Advertising release verification failed: "+failure.getClass().getSimpleName());System.exit(1);}
    }
}
