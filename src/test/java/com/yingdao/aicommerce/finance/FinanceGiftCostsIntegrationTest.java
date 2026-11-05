package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceGiftCostsIntegrationTest extends FinanceActualCostsIntegrationTest {
    @Test void giftWithoutCatalogueIsQueuedCalculatedAndExportedAsZero()throws Exception{
        group=service.saveGroup(owner,null,"赠品");shop=groupedShop(owner,"gift",group);
        var entry=new Entry(shop,"SHOPEE","ORDER","gift-order","gift-order","","ORDER","COMPLETED",
            LocalDate.of(2026,8,10),"PHP",decimal("120"),null,null,null,null,"API",
            Map.of("item_list",List.of(Map.of("item_id","p","model_id","m","model_sku"," gift ","model_quantity_purchased",3))));
        repo.save(owner,entry);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class));
        repo.save(owner,dated(shop,"SETTLEMENT","gift-statement","gift-order","bill",LocalDate.of(2026,9,2),"90",
            Map.of("order_income",Map.of("original_price",120,"commission_fee",30))));
        compute();
        var saved=db.queryForMap("SELECT * FROM finance_order_costs");
        assertEquals("READY",saved.get("status"));equalMoney("0",saved.get("amount_cny"));
        equalMoney("0",total(9).get("amount_cny"));assertEquals(0,number(total(9).get("pending_count")));
        var data=new FinanceCostWorkspace(repo,service).costs(owner,shop);
        assertEquals(0,number(data.get("pendingSkuCount")));
        assertEquals(true,rows(data.get("products")).getFirst().get("fixed_gift_cost"));
        var bytes=new FinanceCostExport(repo).export(owner,period(9),"SETTLED_ORDER","");
        var lines=FinanceCostExportTest.sheet(bytes,3);
        assertEquals("赠品 · 固定 0 成本",FinanceCostExportTest.value(lines,"X2"));
        equalMoney("0",FinanceCostExportTest.value(lines,"S2"));equalMoney("0",FinanceCostExportTest.value(lines,"T2"));
        equalMoney("90",db.queryForObject("SELECT amount FROM finance_records WHERE kind='SETTLEMENT'",java.math.BigDecimal.class));
        repo.save(owner,entry);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='ORDER_STANDARD_COST'",Integer.class));
    }
    @Test void mixedGiftOnlyRemovesItsOwnMissingCostAndKeepsOtherPendingGoods(){
        fixture();addOrder("mixed","COMPLETED");
        var raw=Map.<String,Object>of("item_list",List.of(
            Map.of("item_id","p1","model_id","m1","model_sku","GIFT","model_quantity_purchased",2),
            Map.of("item_id","p2","model_id","m2","model_sku","PACK","model_quantity_purchased",2),
            Map.of("item_id","p3","model_id","m3","model_sku","GIFT-BOX","model_quantity_purchased",1)));
        repo.save(owner,new Entry(shop,"SHOPEE","ORDER","mixed","mixed","","ORDER","COMPLETED",LocalDate.of(2026,8,10),
            "PHP",decimal("100"),null,null,null,null,"API",raw));compute();
        var saved=db.queryForMap("SELECT * FROM finance_order_costs");
        assertEquals("INCOMPLETE",saved.get("status"));assertNull(saved.get("amount_cny"));equalMoney("20",saved.get("known_amount_cny"));
        var lines=rows(repo.read(text(saved.get("data_json"))).get("lines"));
        assertTrue(lines.stream().filter(l->"GIFT".equals(l.get("sellerSku"))).noneMatch(l->l.containsKey("issue")));
        var pending=new FinanceCostWorkspace(repo,service).costs(owner,shop);
        assertEquals(1,number(pending.get("pendingSkuCount")));
        assertEquals("GIFT-BOX",rows(pending.get("products")).stream().filter(p->number(p.get("pending_cost_orders"))>0).findFirst().orElseThrow().get("seller_sku"));
    }
    @Test void actualGiftPolicyKeepsOriginalEvidenceAndOtherLines(){
        var gift=new LinkedHashMap<String,Object>(Map.of("costCode","GIFT","amountCny",decimal("9"),"unitPriceCny",decimal("9")));
        var other=new LinkedHashMap<String,Object>(Map.of("costCode","PACK","amountCny",decimal("20"),"unitPriceCny",decimal("10")));
        var source=Map.<String,Object>of("lines",List.of(gift,other),"amountCny",decimal("29"),"note","实际出库");
        var result=FinanceActualCosts.giftPolicy(source);
        equalMoney("20",result.get("amountCny"));equalMoney("29",source.get("amountCny"));equalMoney("9",gift.get("amountCny"));
        assertEquals(result,FinanceActualCosts.giftPolicy(source));
    }
}
