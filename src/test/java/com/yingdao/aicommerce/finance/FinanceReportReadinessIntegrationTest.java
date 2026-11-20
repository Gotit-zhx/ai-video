package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceReportReadinessIntegrationTest {
    private final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    private FinanceReportReadiness readiness;
    private FinanceQueryProjections projections;
    private FinanceController guard;
    private Scope company;
    private String shop;
    private final LocalDate day=LocalDate.of(2025,10,21);
    private final Scope viewer=new Scope("org","viewer");

    @BeforeAll void schema()throws Exception{
        fixture.migrate();
        fixture.migration("V151__finance_historical_fx.sql");
        fixture.migration("V167__finance_report_export_reminders.sql");
    }
    @AfterAll void close(){fixture.closeDataSource();}

    @BeforeEach void configure(){
        fixture.setup();
        fixture.db.update("DELETE FROM finance_report_export_reminders");
        shop=fixture.connect(fixture.owner,false);
        company=companyScope(fixture.owner.organizationId());
        projections=mock(FinanceQueryProjections.class);
        when(projections.version(any(),any(),anyLong())).thenReturn(Map.of("status","READY","revision","fixture"));
        guard=new FinanceController(fixture.service,fixture.repo,null,mock(FinanceAds.class));
        var returns=new FinanceQianyiReturns(fixture.repo,fixture.service);
        readiness=new FinanceReportReadiness(fixture.repo,fixture.service,projections,returns);
    }

    @Test void selectedWindowAndChannelControlReadinessAndRemindersSurviveNewServiceInstance(){
        var selection=select("RECORDS",Map.of("view","ORDER","orderScope","ALL"));
        setSync("ORDER","SUCCESS",null);
        setSync("SETTLEMENT","ERROR",null);
        assertEquals(FinanceReportReadiness.State.READY,readiness.assess(company,selection).state());

        long nextMonth=Instant.parse("2025-11-01T00:00:00Z").getEpochSecond();
        setSync("ORDER","RUNNING",Map.of("from",nextMonth,"until",nextMonth+86400));
        assertEquals(FinanceReportReadiness.State.READY,readiness.assess(company,selection).state());

        long active=Instant.parse("2025-10-21T00:00:00Z").getEpochSecond();
        setSync("ORDER","RUNNING",Map.of("from",active,"until",active+86400));
        assertEquals(FinanceReportReadiness.State.PROCESSING,readiness.assess(company,selection).state());
        new FinanceReportReminders(fixture.repo,guard,readiness).subscribe(viewer,company,selection);

        var replacement=new FinanceReportReminders(fixture.repo,guard,readiness);
        assertEquals("WAITING",replacement.list(viewer).getFirst().get("state"));
        assertTrue(replacement.list(new Scope("org","another-user")).isEmpty());
        replacement.check();
        assertEquals("WAITING",replacement.list(viewer).getFirst().get("state"));

        setSync("ORDER","SUCCESS",null);
        fixture.db.update("UPDATE finance_report_export_reminders SET next_check_at=UTC_TIMESTAMP(6)");
        replacement.check();
        var ready=replacement.list(viewer).getFirst();
        assertEquals("READY",ready.get("state"));
        assertEquals("RECORDS",replacement.readySelection(viewer,text(ready.get("id"))).get("type"));
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
            ()->replacement.readySelection(new Scope("org","another-user"),text(ready.get("id"))));
        replacement.dismiss(new Scope("other","viewer"),text(ready.get("id")));
        assertEquals(1,replacement.list(viewer).size());
        replacement.dismiss(viewer,text(ready.get("id")));
        assertTrue(replacement.list(viewer).isEmpty());
    }

    @Test void pendingMonthlyProjectionOnlyBlocksAffectedShopAndCalendarDays(){
        setSync("ORDER","SUCCESS",null);
        fixture.db.update("DELETE FROM finance_query_events");
        when(projections.version(any(),any(),anyLong())).thenReturn(Map.of("status","PENDING","revision","fixture"));
        FinanceReportSelection selected=select("RECORDS",Map.of("view","ORDER","orderScope","ALL"));
        fixture.db.update("""
            INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,source_table)
            VALUES(?,'DAY',?,?,'finance_records'),(?,'DAY',?,'another-shop','finance_records')
            """,company.organizationId(),day.minusDays(1),shop,company.organizationId(),day);
        assertTrue(readiness.assess(company,selected).ready());
        fixture.db.update("""
            INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,source_table)
            VALUES(?,'DAY',?,?,'finance_records')
            """,company.organizationId(),day,shop);
        assertEquals(FinanceReportReadiness.State.PROCESSING,readiness.assess(company,selected).state());
    }

    @Test void missingCoverageIsNotZeroAndFeeChangesHaveASeparateDependency(){
        var ads=readiness.assess(company,select("ADVERTISING",Map.of()));
        assertEquals(FinanceReportReadiness.State.PARTIAL,ads.state());
        assertTrue(ads.gaps().stream().anyMatch(g->g.source().equals("Shopee 广告")));
        setSync("SETTLEMENT","SUCCESS",null);
        var fee=select("FEE_MATRIX",Map.of());
        assertTrue(readiness.assess(company,fee).ready());
        fixture.repo.save(company,new Entry(shop,"SHOPEE","FEE","fee-gap","","","物流","SETTLED",
            day,"PHP",null,null,null,null,null,"API",Map.of()));
        var partial=readiness.assess(company,fee);
        assertEquals(FinanceReportReadiness.State.PARTIAL,partial.state());
        assertTrue(partial.notes(fee).stream().anyMatch(row->row.getLast().contains("金额尚未提供")));
    }

    @Test void costAndQianyiReadinessAndHistoricalFxHaveIndependentSignals(){
        setSync("ORDER","SUCCESS",null);setSync("SETTLEMENT","SUCCESS",null);
        assertTrue(readiness.assess(company,select("ORDER_COST",Map.of())).ready());
        var afterSales=readiness.assess(company,select("QIANYI",Map.of()));
        assertEquals(FinanceReportReadiness.State.PARTIAL,afterSales.state());
        assertTrue(afterSales.gaps().stream().anyMatch(g->g.source().equals("千易售后")));

        fixture.repo.save(company,new Entry(shop,"SHOPEE","FEE","fee-fx","","","物流","SETTLED",
            day,"PHP",decimal("42"),null,null,null,null,"API",Map.of()));
        var foreign=select("RECORDS",Map.of("view","FEE","displayCurrency","CNY"));
        assertTrue(readiness.assess(company,foreign).gaps().stream().anyMatch(g->g.source().equals("历史汇率")));
        String snapshot="1234567890abcdef1234567890abcdef";
        fixture.db.update("""
            INSERT INTO finance_fx_historical_snapshots(id,provider,cutoff_date,created_at,payload_json)
            VALUES(?,'ECB',?,UTC_TIMESTAMP(6),?)
            """,snapshot,day,fixture.repo.write(Map.of("days",Map.of(day.toString(),
                Map.of("rateDate",day.toString(),"rates",Map.of("PHP","0.12"))))));
        assertTrue(readiness.assess(company,select("RECORDS",Map.of("view","FEE","displayCurrency","CNY","fxSnapshot",snapshot))).ready());
    }

    @Test void partialWorkbookHasCoverageReceiptAndOnlyTheRequestingUserCanDownload()throws Exception{
        setSync("SETTLEMENT","SUCCESS",null);
        var selected=select("FEE_MATRIX",Map.of());
        fixture.repo.save(company,new Entry(shop,"SHOPEE","FEE","fee-gap","","","物流","SETTLED",
            day,"PHP",null,null,null,null,null,"API",Map.of()));
        var status=readiness.assess(company,selected);
        var generator=new FinanceReportFile(guard,null,fixture.service,null);
        try(var jobs=new FinanceReportExportJobs(fixture.repo,readiness,generator)){
            String key="finance-qa-key-101920";
            assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->jobs.submit(viewer,company,selected,key,status.revision(),false));
            assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->jobs.submit(viewer,company,selected,key,"stale-revision",true));
            String id=text(jobs.submit(viewer,company,selected,key,status.revision(),true).get("id"));
            assertEquals(id,jobs.submit(viewer,company,selected,key,status.revision(),true).get("id"));
            for(int i=0;i<200&&Set.of("QUEUED","RUNNING").contains(text(jobs.list(viewer).getFirst().get("status")));i++)
                Thread.sleep(20);
            assertEquals("READY",jobs.list(viewer).getFirst().get("status"),jobs.list(viewer).toString());
            var file=jobs.download(viewer,id);
            assertTrue(file.filename().startsWith("部分数据-"));
            var workbook=FinanceWorkbook.read(file.bytes(),file.filename());
            assertTrue(workbook.stream().anyMatch(row->row.contains("部分数据 · 仅供核对")));
            assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->jobs.download(new Scope("org","another-user"),id));
        }
    }

    private FinanceReportSelection select(String type,Map<String,String> options){
        var scope=new HashMap<>(options);
        scope.put("start",day.toString());scope.put("end",day.toString());scope.put("shopId",shop);
        return FinanceReportSelection.parse(guard,type,scope);
    }

    private void setSync(String channel,String state,Map<String,Long> checkpoint){
        fixture.db.update("""
            UPDATE finance_sync_states SET status=?,coverage_start='2025-10-01',
              last_success_at='2025-10-25 00:00:00',checkpoint_json=? WHERE shop_id=? AND channel=?
            """,state,checkpoint==null?null:fixture.repo.write(checkpoint),shop,channel);
    }
}
