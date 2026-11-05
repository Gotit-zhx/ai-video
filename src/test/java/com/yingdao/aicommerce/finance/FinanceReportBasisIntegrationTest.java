package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceReportBasisIntegrationTest extends FinanceConfigurationIntegrationTest {
    @Test void repairRespectsEachLocalTimezoneAndNeverConvertsNativeCurrency(){
        for(var region:List.of(new String[]{"PHP","Asia/Manila","2026-11-01"},
                new String[]{"THB","Asia/Bangkok","2026-10-31"},new String[]{"MYR","Asia/Kuala_Lumpur","2026-11-01"})){
            setup();String shop=connect(owner,false);
            db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP',seller_type='LOCAL',currency=?,timezone=? WHERE id=?",region[0],region[1],shop);
            long paid=Instant.parse("2026-10-31T16:30:00Z").getEpochSecond();
            var bill=Map.<String,Object>of("id","boundary","statement_time",paid-3600,"payment_time",paid,"payment_status","SETTLED");
            var raw=new LinkedHashMap<>(bill);raw.put("type","ORDER");raw.put("revenue_amount","123.45");
            var e=new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","tx","order","boundary","ORDER","SETTLED",
                    LocalDate.of(2026,10,30),region[0],decimal("123.45"),null,null,null,null,"API",raw);
            repo.save(owner,e);
            repo.repairSettlementDates(owner,shop,bill,List.of(id(owner,e)));
            var after=db.queryForMap("SELECT record_date,currency,amount FROM finance_records WHERE id=?",id(owner,e));
            assertEquals(region[2],text(after.get("record_date")));assertEquals(region[0],after.get("currency"));
            assertEquals(0,decimal("123.45").compareTo(decimal(after.get("amount"))));
        }
    }
    @Test void localDateRepairPreservesEveryAmountAndMovesOrderFeeDashboardAndExportTogether(){
        String shop=connect(owner,false);
        db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP',seller_type='LOCAL' WHERE id=?",shop);
        var bill=Map.<String,Object>of("id","local-bill","statement_time",1793404800L,
                "payment_time",1793477583L,"payment_status","SETTLED");
        var oldDay=LocalDate.of(2026,10,31);
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","ORDER","order","order","","订单","COMPLETED",oldDay,
                "PHP",decimal("100"),null,null,null,null,"API",Map.of("id","order")));
        var raw=new LinkedHashMap<String,Object>(Map.of("type","ORDER","settlement_amount","80",
                "revenue_amount","100","fee_tax_amount","-20","fee_tax_breakdown",Map.of("fee",Map.of("transaction_fee_amount","-20"))));
        raw.putAll(FinanceSettlementDates.metadata(bill,"Asia/Manila","CROSS_BORDER"));
        // Two legitimate transactions share an order ID. Both must remain.
        for(String txId:List.of("tx1","tx2"))repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT",txId,"order","local-bill","ORDER",
                "SETTLED",oldDay,"PHP",decimal("80"),null,null,null,null,"API",raw));
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","BILL","local-bill","","local-bill","结算账单","SETTLED",oldDay,
                "PHP",decimal("160"),null,null,null,null,"API",bill));
        var unchanged=db.queryForList("SELECT id,external_id,order_id,statement_id,amount,revenue,currency,updated_at FROM finance_records ORDER BY id");
        var ids=db.queryForList("SELECT id FROM finance_records WHERE statement_id='local-bill'",String.class);
        assertEquals(5,repo.repairSettlementDates(owner,shop,bill,ids));
        assertEquals(0,repo.repairSettlementDates(owner,shop,bill,ids));
        assertEquals(unchanged,db.queryForList("SELECT id,external_id,order_id,statement_id,amount,revenue,currency,updated_at FROM finance_records ORDER BY id"));
        var october=new Filter(LocalDate.of(2026,10,1),oldDay,shop,"","","day");
        var november=new Filter(LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),shop,"","","day");
        for(String kind:List.of("ORDER","SETTLEMENT","FEE","BILL")){
            assertEquals(0,number(repo.details(owner,october,kind,1,50).get("total")));
            assertEquals(Set.of("SETTLEMENT","FEE").contains(kind)?2:1,number(repo.details(owner,november,kind,1,50).get("total")));
        }
        assertEquals(1,number(map(new FinanceCostReport(repo).summary(owner,november,"ORDER").get("total")).get("order_count")));
        assertTrue(repo.dashboardBuckets(owner,october,false).isEmpty());
        assertEquals("ORDER",repo.dashboardBuckets(owner,october,true).getFirst().get("kind"),
                "The separate creation-date view must keep the original order date");
        assertEquals(0,decimal(repo.reconciliation(owner,november).getFirst().get("difference")).signum());
        var csv=FinanceWorkbook.read(controller().export(Map.of("start","2026-11-01","end","2026-11-30","view","SETTLEMENT",
                "format","csv","shopId",shop),principal()).getBody(),"report.csv");
        assertEquals(3,csv.size());
        assertEquals(0,number(repo.details(other,november,"SETTLEMENT",1,50).get("total")));
    }
}
