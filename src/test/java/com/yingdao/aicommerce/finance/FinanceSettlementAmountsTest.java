package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceSettlementAmountsTest {
    static Map<String,Object> row(String shop,Object amount){
        var r=new LinkedHashMap<String,Object>(Map.of("shop_id",shop,"order_id","same-order","kind","SETTLEMENT","source","API","platform","TIKTOK_SHOP"));
        r.put("amount",amount);return r;
    }
    @Test void explicitZeroMissingAndNegativeAmountsRemainDistinctInDisplayAndExport(){
        var zero=row("a",BigDecimal.ZERO);FinanceFields.enrich(zero,Map.of("settlement_amount","0"));
        assertEquals("平台明确返回结算金额 0",zero.get("settlement_amount_note"));
        var missing=row("a",null);FinanceFields.enrich(missing,Map.of());
        assertTrue(text(missing.get("settlement_amount_note")).contains("尚未获取"));assertNull(missing.get("amount"));
        var negative=row("a",new BigDecimal("-200.34"));FinanceFields.enrich(negative,Map.of("settlement_amount","-200.34"));
        assertTrue(text(negative.get("settlement_amount_note")).contains("负向结算"));
        var amount=FinanceFields.catalog("SETTLEMENT").stream().filter(f->f.id().equals("amount")).findFirst().orElseThrow();
        assertEquals("0",FinanceFields.cell(zero,amount));assertEquals("",FinanceFields.cell(missing,amount));assertEquals("-200.34",FinanceFields.cell(negative,amount));
    }
    @Test void cancellationExplanationUsesRelatedOrderFromTheSameShop(){
        var db=mock(JdbcTemplate.class);var repo=new FinanceRepository(db,new ObjectMapper());
        when(db.queryForList(contains("related_order_status"),any(Object[].class))).thenReturn(List.of(
            Map.of("shop_id","a","order_id","same-order","related_order_status","CANCELLED","sku","sku-a"),
            Map.of("shop_id","b","order_id","same-order","related_order_status","COMPLETED","sku","sku-b")));
        var cancelled=row("a",BigDecimal.ZERO);var completed=row("b",BigDecimal.ZERO);
        FinanceFields.enrich(cancelled,Map.of());FinanceFields.enrich(completed,Map.of());
        new FinanceProductSkus(repo).attach(new Scope("org","company"),List.of(cancelled,completed));
        assertEquals("已取消订单，平台明确返回结算金额 0",cancelled.get("settlement_amount_note"));
        assertEquals("平台明确返回结算金额 0",completed.get("settlement_amount_note"));
        assertEquals("sku-a",cancelled.get("product_skus"));assertEquals("COMPLETED",completed.get("related_order_status"));
    }
    @Test void completedFreeSamplesAreLabelledWithoutBeingReclassifiedAsCancelled(){
        var db=mock(JdbcTemplate.class);var repo=new FinanceRepository(db,new ObjectMapper());
        when(db.queryForList(contains("related_order_status"),any(Object[].class))).thenReturn(List.of(
            Map.of("shop_id","a","order_id","same-order","related_order_status","COMPLETED","sku","sample",
                "order_type","SELLER_FUND_FREE_SAMPLE","is_sample_order","true")));
        var settled=row("a",new BigDecimal("-6.30"));
        FinanceFields.enrich(settled,Map.of("settlement_amount","-6.30"));
        new FinanceProductSkus(repo).attach(new Scope("org","company"),List.of(settled));
        assertEquals("COMPLETED",settled.get("related_order_status"));
        assertEquals("商家承担的免费样品单",settled.get("order_type_label"));
        assertTrue(text(settled.get("settlement_amount_note")).contains("扣费形成负结算"));
        var order=new LinkedHashMap<String,Object>(Map.of("kind","ORDER","source","API","platform","TIKTOK_SHOP","amount",BigDecimal.ZERO,"status","COMPLETED"));
        FinanceFields.enrich(order,Map.of("order_type","SELLER_FUND_FREE_SAMPLE","is_sample_order",true));
        assertEquals("商家承担的免费样品单",order.get("order_type_label"));
        assertEquals("COMPLETED",order.get("status"));
    }
}
