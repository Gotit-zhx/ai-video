package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceHistoricalFxBusinessDateIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    @BeforeAll void schema()throws Exception{fixture.migrate();}
    @AfterAll void close(){fixture.closeDataSource();}
    @Test void monthAnchorIsSplitFromKnownDayForBothLedgerAndFirstSettlementCosts(){
        fixture.setup();var db=fixture.db;var repo=fixture.repo;var owner=fixture.owner;
        String shop=fixture.connect(owner,false);db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by)"
            +" VALUES(?,?,?,?,?,'TEST','month only','qa')",owner.organizationId(),shop,"legacy","bill-legacy",Date.valueOf("2026-10-01"));
        for(String order:List.of("legacy","normal")){
            LocalDate day=LocalDate.of(2026,9,order.equals("legacy")?20:21);
            var entry=new Entry(shop,"TIKTOK_SHOP","ORDER",order,order,"","ORDER","COMPLETED",day,"PHP",decimal("100"),null,null,null,null,"API",Map.of());
            repo.save(owner,entry);
            db.update("INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,amount_cny,known_amount_cny,source_hash,data_json)"
                +" VALUES(?,?,?,?,?,'READY',?,?,'unit','{}')",id(owner,entry),owner.organizationId(),shop,order,day,order.equals("legacy")?70:140,order.equals("legacy")?70:140);
            db.update("DELETE FROM finance_order_cost_jobs WHERE order_record_id=?",id(owner,entry));
            repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","settled-"+order,order,"bill-"+order,"ORDER","SETTLED",
                order.equals("legacy")?day:LocalDate.of(2026,10,1),"PHP",decimal(order.equals("legacy")?"20":"30"),decimal(order.equals("legacy")?"20":"30"),null,null,null,"API",Map.of()));
        }
        var filter=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),shop,"","","day");
        var buckets=repo.dashboardBuckets(owner,filter,false);
        assertEquals(0,decimal("50").compareTo(decimal(FinanceService.aggregate(buckets,"kind").getFirst().get("amount"))));
        var quote=new FinanceFx.Quote("h",Instant.EPOCH,Map.of(),Map.of(LocalDate.of(2026,10,1),new FinanceFx.Day(LocalDate.of(2026,10,1),Map.of("PHP",decimal("0.1")))));
        var converted=FinanceFx.groups(buckets,quote,"kind").getFirst();
        assertNull(converted.get("amount"));assertEquals(0,decimal("3").compareTo(decimal(converted.get("known_amount"))));
        assertTrue(buckets.stream().anyMatch(row->row.containsKey("fx_date")&&row.get("fx_date")==null));
        var report=new FinanceCostReport(repo);var facts=report.dashboardFacts(owner,filter);
        var costs=report.dashboardSummary(owner,filter,facts);FinanceCostReport.displayAmounts(costs,facts,"PHP",quote);
        assertNull(map(costs.get("total")).get("display_amount"));
        assertEquals(0,decimal("1400").compareTo(decimal(map(costs.get("total")).get("known_display_amount"))));
        var lazy=report.displaySummary(owner,filter,"SETTLED_ORDER","PHP",quote);
        assertNull(map(lazy.get("total")).get("display_amount"));
        assertEquals(0,decimal("1400").compareTo(decimal(map(lazy.get("total")).get("known_display_amount"))));
        assertEquals(0,decimal("210").compareTo(decimal(map(lazy.get("total")).get("amount_cny"))));
        assertEquals(LocalDate.of(2026,10,1).toString(),text(db.queryForObject("SELECT record_date FROM finance_records WHERE external_id='settled-legacy'",Date.class)));
        assertTrue(repo.dashboardBuckets(owner,new Filter(filter.start(),LocalDate.of(2026,10,20),shop,"","","day"),false)
            .stream().noneMatch(row->row.containsKey("fx_date")),"Month-only rows remain excluded from partial-month filters");
    }
}
