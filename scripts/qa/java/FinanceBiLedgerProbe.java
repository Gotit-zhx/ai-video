package com.yingdao.aicommerce.finance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Compares statement-local temp-table budgets in one read-only repeatable snapshot. */
public final class FinanceBiLedgerProbe {
    static final class ProbeJdbc extends JdbcTemplate {
        boolean boundedMemory;
        ProbeJdbc(Connection connection) { super(new SingleConnectionDataSource(connection, true)); }
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
            if (boundedMemory && sql.contains("GROUP BY shop_id,record_date,kind,currency") && !sql.contains("SET_VAR"))
                sql=sql.replaceFirst("SELECT", "SELECT /*+ SET_VAR(tmp_table_size=67108864) */");
            return super.queryForList(sql,args);
        }
    }
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("FINANCE_PERF_READ_ONLY"))) throw new IllegalStateException("Read-only opt-in required");
        var json=new ObjectMapper();var results=new ArrayList<Map<String,Object>>();
        try(var connection=DriverManager.getConnection(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))){
            connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            var db=new ProbeJdbc(connection);db.setQueryTimeout(90);db.execute("SET SESSION MAX_EXECUTION_TIME=90000");
            var original=db.queryForObject("SELECT @@session.tmp_table_size",Long.class);
            String org=db.queryForObject("SELECT organization_id FROM finance_connections WHERE status<>'REMOVED' GROUP BY organization_id ORDER BY COUNT(*) DESC LIMIT 1",String.class);
            var month=YearMonth.parse(args.length==0?"2026-10":args[0]);
            var filter=new Filter(month.atDay(1),month.atEndOfMonth(),"","","","day");var repo=new FinanceRepository(db,json);
            for(boolean candidate:List.of(false,true)){
                db.boundedMemory=candidate;
                long before=number(db.queryForMap("SHOW SESSION STATUS LIKE 'Created_tmp_disk_tables'").get("Value"));
                long started=System.nanoTime();var rows=repo.dashboardBuckets(companyScope(org),filter,false);
                long millis=(System.nanoTime()-started)/1_000_000;
                long after=number(db.queryForMap("SHOW SESSION STATUS LIKE 'Created_tmp_disk_tables'").get("Value"));
                var normalized=rows.stream().map(row->json.writeValueAsString(FinanceBiPerformanceProbe.canonical(row))).sorted().toList();
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",normalized).getBytes(StandardCharsets.UTF_8)));
                results.add(Map.of("candidate",candidate,"millis",millis,"groups",rows.size(),"diskTempTables",after-before,"financialHash",hash));
            }
            if(!results.get(0).get("financialHash").equals(results.get(1).get("financialHash")))throw new IllegalStateException("Ledger groups changed");
            if(!Objects.equals(original,db.queryForObject("SELECT @@session.tmp_table_size",Long.class)))throw new IllegalStateException("Statement hint leaked to session");
            connection.rollback();
        }
        System.out.println(json.writeValueAsString(Map.of("measurements",results,"sameSnapshot",true,"resultsEqual",true,"sessionSettingsPreserved",true,"productionWrites",false)));
    }
}
