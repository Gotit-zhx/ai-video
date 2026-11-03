package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceSyncMonthIntegrationTest {
    JdbcTemplate db;FinanceService service;LocalDate month;
    @BeforeAll void schema(){
        String url=System.getenv("FINANCE_QA_JDBC");
        assertTrue(url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"));
        var ds=new DriverManagerDataSource(url,System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD"));
        db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE finance_connections(id VARCHAR(36) PRIMARY KEY,organization_id VARCHAR(36),user_id VARCHAR(36),platform VARCHAR(32),enabled BOOLEAN,timezone VARCHAR(64),status VARCHAR(24))");
        db.execute("""
            CREATE TABLE finance_sync_states(organization_id VARCHAR(36),user_id VARCHAR(36),shop_id VARCHAR(36),channel VARCHAR(24),
            status VARCHAR(24),error_message VARCHAR(700),next_at DATETIME(6),last_success_at DATETIME(6),coverage_start DATE,
            lease_until DATETIME(6),failures INT DEFAULT 0,priority_requested_at DATETIME(6),last_progress_at DATETIME(6),checkpoint_json JSON)
            """);
        db.execute("""
            CREATE TABLE finance_records(organization_id VARCHAR(36),shop_id VARCHAR(36),kind VARCHAR(24),source VARCHAR(24),
            status VARCHAR(24),record_date DATE,INDEX idx_finance_bill_status(organization_id,kind,status,source,shop_id))
            """);
        db.execute("CREATE TABLE finance_api_pacing(platform VARCHAR(32),channel VARCHAR(24),next_allowed_at DATETIME(6))");
        service=new FinanceService(new FinanceRepository(db,new ObjectMapper()),null,null,null);
    }
    @BeforeEach void data(){
        for(String table:List.of("finance_sync_states","finance_connections","finance_records"))db.update("DELETE FROM "+table);
        month=Instant.now().atZone(ZoneId.of("Asia/Manila")).toLocalDate().withDayOfMonth(1);
        db.update("INSERT INTO finance_connections VALUES('a','org','u','TIKTOK_SHOP',TRUE,'Asia/Manila','ACTIVE'),('foreign','other','u','TIKTOK_SHOP',TRUE,'Asia/Manila','ACTIVE')");
        for(String channel:List.of("ORDER","SETTLEMENT"))db.update("""
            INSERT INTO finance_sync_states(organization_id,user_id,shop_id,channel,status,next_at,last_success_at,coverage_start)
            VALUES('org','u','a',?,'SUCCESS',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),UTC_TIMESTAMP(6),?)
            """,channel,month.minusMonths(2));
    }
    void bill(String org,String shop,String kind,String source,String status,LocalDate day){
        db.update("INSERT INTO finance_records VALUES(?,?,?,?,?,?)",org,shop,kind,source,status,day);
    }
    Map<String,Object> settlement(){
        var rows=service.syncStates(new FinanceData.Scope("org","colleague"));
        assertEquals(2,rows.size());
        return rows.stream().filter(r->"SETTLEMENT".equals(r.get("channel"))).findFirst().orElseThrow();
    }
    @Test void queryBlocksPartialAndPendingBillsInThePreviousMonth(){
        bill("org","a","BILL","API","PARTIAL",month.minusDays(2));
        bill("org","a","BILL","API","DATE_PENDING",month.minusDays(1));
        var state=settlement();
        assertEquals(2L,state.get("previous_month_open_bills"));assertEquals(false,state.get("previous_month_complete"));
        assertEquals(1L,FinanceData.number(state.get("pending_date_bills")));
        db.update("UPDATE finance_records SET status='SETTLED' WHERE organization_id='org'");
        assertEquals(true,settlement().get("previous_month_complete"));
    }
    @Test void queryExcludesOtherMonthsCompaniesKindsAndImportedBills(){
        bill("other","a","BILL","API","PARTIAL",month.minusDays(1));
        bill("org","a","BILL","IMPORT","PARTIAL",month.minusDays(1));
        bill("org","a","SETTLEMENT","API","PARTIAL",month.minusDays(1));
        bill("org","a","BILL","API","PARTIAL",month);
        bill("org","a","BILL","API","DATE_PENDING",month.minusMonths(2));
        var state=settlement();
        assertEquals(0L,state.get("previous_month_open_bills"));assertEquals(true,state.get("previous_month_complete"));
        db.update("UPDATE finance_sync_states SET status='SYNCING',checkpoint_json=? WHERE channel='SETTLEMENT'",
            "{\"from\":"+month.minusDays(7).atStartOfDay(ZoneId.of("Asia/Manila")).toEpochSecond()
            +",\"to\":"+month.atStartOfDay(ZoneId.of("Asia/Manila")).toEpochSecond()
            +",\"until\":"+Instant.now().getEpochSecond()+"}");
        assertEquals(false,settlement().get("previous_month_complete"),"previous success must not hide the current unfinished month range");
    }
}
