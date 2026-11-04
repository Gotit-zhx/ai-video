package com.yingdao.aicommerce.finance;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceQianyiReturnCostSummaryTest {
    FinanceOrderCosts.Context context(Map<String,Object> rules){
        return new FinanceOrderCosts.Context("shop","group","TH","Asia/Bangkok","book",List.of(
            Map.of("sku","PACK","warehouse","海外仓-泰国","prices",Map.of("2026-09",10,"2026-10",12)),
            Map.of("sku","ZERO","warehouse","海外仓-泰国","prices",Map.of("2026-10",0))),
            Map.of("rules",rules),Map.of());
    }
    Map<String,Object> source(List<Map<String,Object>> lines){
        return Map.of("receiveTime",1790784000000L,"returnSkuList",lines,"orderRefundAmount",99999,"returnStatus","FINISH");
    }
    Map<String,Object> line(String sku,int qty,String id){return Map.of("sku",sku,"quantity",qty,"orderSkuId",id,"selected",1);}
    void money(String expected,Object actual){assertNotNull(actual);assertEquals(0,decimal(expected).compareTo(decimal(actual)));}
    @Test void valuesOnlyReturnedQuantityAtChinaReceiptMonthUsingExistingWarehouseRules(){
        var value=FinanceQianyiReturnCostSummary.value(source(List.of(line("PACK",2,"line"))),context(Map.of("basis","shipment")));
        money("24",value.get("amountCny"));assertEquals("2026-10",value.get("costMonth"));
        assertEquals("CNY",value.get("currency"));assertEquals("RETURN_RECEIPT_STANDARD",value.get("basis"));
        assertEquals("海外仓-泰国",value.get("warehouse"));
    }
    @Test void missingSkuDoesNotTurnPartialCostIntoACompleteZero(){
        var value=FinanceQianyiReturnCostSummary.value(source(List.of(line("PACK",1,"one"),line("UNKNOWN",3,"two"))),context(Map.of()));
        assertNull(value.get("amountCny"));money("12",value.get("knownAmountCny"));assertEquals(1,value.get("matchedLines"));
    }
    @Test void skipsDeletedAndUnselectedLinesAndDeduplicatesSourceLineIdentity(){
        var item=line("PACK",1,"one");var deleted=new LinkedHashMap<>(line("PACK",9,"deleted"));deleted.put("isDeleted",1);
        var unselected=new LinkedHashMap<>(line("PACK",8,"unselected"));unselected.put("selected",0);
        var value=FinanceQianyiReturnCostSummary.value(source(List.of(item,item,deleted,unselected)),context(Map.of()));
        money("12",value.get("amountCny"));assertEquals(1,value.get("lineCount"));
        assertNull(FinanceQianyiReturnCostSummary.value(source(List.of(item,line("PACK",2,"one"))),context(Map.of())).get("amountCny"));
    }
    @Test void invalidQuantityNoReceiptOrNoCatalogRemainPending(){
        assertNull(FinanceQianyiReturnCostSummary.value(source(List.of(line("PACK",0,"one"))),context(Map.of())).get("amountCny"));
        assertNull(FinanceQianyiReturnCostSummary.value(source(List.of()),context(Map.of())).get("amountCny"));
        assertNull(FinanceQianyiReturnCostSummary.value(Map.of("returnSkuList",List.of(line("PACK",1,"one"))),context(Map.of())).get("amountCny"));
        assertNull(FinanceQianyiReturnCostSummary.value(source(List.of(line("PACK",1,"one"))),null).get("amountCny"));
    }
    @Test void confirmedZeroAndHistoricalPricesRetainExistingPolicy(){
        assertNull(FinanceQianyiReturnCostSummary.value(source(List.of(line("ZERO",1,"one"))),context(Map.of())).get("amountCny"));
        money("0",FinanceQianyiReturnCostSummary.value(source(List.of(line("ZERO",1,"one"))),context(Map.of("allowZero",true))).get("amountCny"));
        var later=new LinkedHashMap<>(source(List.of(line("PACK",1,"one"))));later.put("receiveTime",1793462400000L);
        var value=FinanceQianyiReturnCostSummary.value(later,context(Map.of()));
        money("12",value.get("amountCny"));assertEquals("ESTIMATED",value.get("status"));assertEquals(1,value.get("fallbackLines"));
        assertNull(FinanceQianyiReturnCostSummary.value(later,context(Map.of("missing","pending"))).get("amountCny"));
    }
}
