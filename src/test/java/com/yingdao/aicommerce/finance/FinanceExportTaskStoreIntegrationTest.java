package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.common.exception.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.Scope;
import static com.yingdao.aicommerce.finance.FinanceExportTaskStore.Lane.STANDARD;
import static com.yingdao.aicommerce.finance.FinanceExportTaskStore.Request;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceExportTaskStoreIntegrationTest {
    @Test void waitingForCompleteDataLeavesOneSlotForCurrentOrderCosts(){
        String url=System.getenv("FINANCE_QA_JDBC");
        assertTrue(url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"));
        var dataSource=new DriverManagerDataSource(url,System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V173__finance_durable_report_exports.sql")).execute(dataSource);
        var jdbc=new JdbcTemplate(dataSource);
        var store=new FinanceExportTaskStore(new FinanceRepository(jdbc,new ObjectMapper()));
        var owner=new Scope("test-company","test-user");
        var complete=new Request("ORDER_COST",Map.of("end","2026-11-30"),false,STANDARD);
        var current=new Request("ORDER_COST",complete.selection(),true,STANDARD);

        var first=store.submit(owner,"complete-export-01",complete);
        store.submit(owner,"complete-export-02",complete);
        assertThrows(BusinessException.class,()->store.submit(owner,"current-export-001",current));
        jdbc.update("""
            UPDATE finance_report_export_jobs SET state='WAITING',receipt_json=JSON_OBJECT('state','PARTIAL')
            WHERE id=?
            """,first.get("id"));

        assertThrows(BusinessException.class,()->store.submit(owner,"complete-export-03",complete));
        assertThrows(BusinessException.class,()->store.submit(owner,"current-other-0001",
            new Request("RECORDS",complete.selection(),true,STANDARD)));
        var extra=store.submit(owner,"current-export-001",current);
        assertEquals("QUEUED",extra.get("state"));
        assertEquals(extra.get("id"),store.submit(owner,"current-export-001",current).get("id"));
        assertThrows(BusinessException.class,()->store.submit(owner,"current-export-002",current));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM finance_report_export_jobs WHERE state='WAITING' AND allow_partial=0",Integer.class));
    }
}
