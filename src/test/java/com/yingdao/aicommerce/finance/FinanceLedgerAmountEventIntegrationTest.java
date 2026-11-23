package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceLedgerAmountEventIntegrationTest {
    private final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    private final Filter october=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");

    @BeforeAll void migrate()throws Exception{
        fixture.migrate();
        fixture.migration("V165__finance_amount_change_event_scope.sql");
    }

    @AfterAll void close(){fixture.closeDataSource();}

    @Test void amountChangesRetainFirstSettlementCostsAndCurrentBillAmount(){
        var projection=seed();
        var report=new FinanceCostReport(fixture.repo);
        var november=new Filter(LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),"","","","day");
        projection.request(fixture.owner,november);
        assertTrue(projection.buildNext());
        assertEquals("READY",projection.version(fixture.owner,november).get("status"));
        var before=costs(projection);
        fixture.db.update("UPDATE finance_records SET amount=17.123456 WHERE external_id='settlement'");
        fixture.db.update("UPDATE finance_records SET amount=3.123456 WHERE external_id='fee'");
        assertEquals(List.of("DAY","DAY"),events());
        assertEquals(before,costs(projection));
        assertEquals(report.summary(fixture.owner,october,"SETTLEMENT"),costs(projection));
        assertEquals("PENDING",projection.version(fixture.owner,november).get("status"));
        var bills=fixture.repo.reconciliation(fixture.owner,november,50);
        assertEquals(decimal("17.123456"),decimal(bills.getFirst().get("detail_amount")));
        drain(projection);
        assertEquals(0,number(fixture.db.queryForObject("SELECT MAX(cost_revision) FROM finance_query_dirty_shop_days",Long.class)));
        assertEquals(1,number(fixture.db.queryForObject("SELECT COUNT(*) FROM finance_query_dirty_days WHERE record_date='2026-11-01'",Long.class)));
        assertTrue(projection.buildNext());
        assertEquals(before,costs(projection));
    }

    @Test void currencyAndDateMovesRetainBothOrderInvalidations(){
        var projection=seed();
        fixture.db.update("UPDATE finance_records SET amount=amount WHERE external_id='settlement'");
        assertTrue(events().isEmpty());
        fixture.db.update("UPDATE finance_records SET currency='php',record_date='2026-09-30' WHERE external_id='settlement'");
        assertEquals(List.of("ORDER","ORDER"),events());
        assertEquals(List.of(LocalDate.of(2026,10,12),LocalDate.of(2026,9,30)),
            fixture.db.queryForList("SELECT record_date FROM finance_query_events ORDER BY event_id",java.sql.Date.class)
                .stream().map(java.sql.Date::toLocalDate).toList());
        assertEquals(new FinanceCostReport(fixture.repo).summary(fixture.owner,october,"SETTLEMENT"),costs(projection));
    }

    private FinanceQueryProjections seed(){
        fixture.setup();
        String shop=fixture.connect(fixture.owner,false);
        save(shop,"ORDER","order","2026-09-28");
        save(shop,"SETTLEMENT","settlement","2026-10-12");
        save(shop,"FEE","fee","2026-10-12");
        save(shop,"BILL","bill","2026-11-01");
        var projection=new FinanceQueryProjections(fixture.repo,fixture.service,new FinanceAdSpendService(fixture.repo,fixture.service));
        projection.request(fixture.owner,october);
        drain(projection);
        assertTrue(projection.buildNext());
        return projection;
    }

    private void save(String shop,String kind,String id,String date){
        fixture.repo.save(fixture.owner,new Entry(shop,"SHOPEE",kind,id,"order","bill","ORDER","SETTLED",
            LocalDate.parse(date),"PHP",decimal("20.123456"),null,null,null,null,"API",Map.of()));
    }

    private List<String> events(){
        return fixture.db.queryForList("SELECT event_scope FROM finance_query_events ORDER BY event_id",String.class);
    }

    private static void drain(FinanceQueryProjections p){while(p.drainEvents()>0){}}

    private Map<String,Object> costs(FinanceQueryProjections p){
        return fixture.repo.readSnapshot(()->p.settlementCosts(fixture.owner,october)).orElseThrow();
    }
}
