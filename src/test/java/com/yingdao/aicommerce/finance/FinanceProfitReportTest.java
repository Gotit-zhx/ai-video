package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceProfitReportTest {
    static Map<String,Object> total(String kind,String amount){
        var row=new LinkedHashMap<String,Object>();
        row.put("kind",kind);row.put("amount",decimal(amount));row.put("records",1);row.put("known_amounts",amount==null?0:1);
        row.put("currency","CNY");return row;
    }
    static Map<String,Object> cost(String amount,int pending,int estimated){
        var total=new LinkedHashMap<String,Object>();
        total.put("amount_cny",decimal(amount));total.put("pending_count",pending);total.put("estimated_count",estimated);
        return new LinkedHashMap<>(Map.of("total",total));
    }
    static Map<String,Object> settlement(String net,String gross){
        var row=total("SETTLEMENT",net);row.put("revenue",decimal(gross));row.put("known_revenues",gross==null?0:1);return row;
    }
    static List<Map<String,Object>> ledger(){
        return List.of(settlement("100","120"),total("FEE","20"),total("AD","10"),total("COST","5"),
            total("REFUND","999"),total("ORDER","9999"));
    }
    static void money(String expected,Object actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo(decimal(actual)));}
    @Test void explicitProviderGrossAndDetailedDeductionsAgreeWithoutDeductingRefundsTwice(){
        var r=FinanceProfitReport.calculate(ledger(),cost("50",0,0),"CNY",null);
        money("120",r.get("settledGrossAmount"));money("35",r.get("amount"));assertEquals("READY",r.get("status"));
        money("35",decimal(r.get("settledGrossAmount")).subtract(decimal(r.get("platformFees")))
            .subtract(decimal(r.get("advertising"))).subtract(decimal(r.get("logisticsLabelCost"))).subtract(decimal(r.get("productCost"))));
    }
    @Test void nativeCurrencyConvertsRmbCostsBeforeSubtracting(){
        var q=new FinanceFx.Quote("test",Instant.parse("2026-10-23T00:00:00Z"),Map.of("PHP",new BigDecimal("0.1")));
        var r=FinanceProfitReport.calculate(ledger(),cost("5",0,0),"PHP",q);
        money("50",r.get("productCost"));money("5",r.get("productCostCny"));money("35",r.get("amount"));
        assertNotNull(r.get("fx"));
    }
    @Test void missingFxNeverSubtractsRmbDirectlyFromNativeCurrency(){
        var r=FinanceProfitReport.calculate(ledger(),cost("50",0,0),"PHP",null);
        assertNull(r.get("amount"));assertNull(r.get("productCost"));assertEquals("MISSING_FX",r.get("status"));
        money("120",r.get("settledGrossAmount"));
    }
    @Test void incompleteFinancialAmountsOrCostsNeverProduceAnApparentlyCompleteProfit(){
        var unknown=List.of(settlement("100",null),total("FEE",null),total("AD","10"),total("COST","5"));
        var r=FinanceProfitReport.calculate(unknown,cost("50",0,0),"CNY",null);
        assertNull(r.get("amount"));assertNull(r.get("settledGrossAmount"));assertEquals("INCOMPLETE_AMOUNTS",r.get("status"));
        r=FinanceProfitReport.calculate(ledger(),cost(null,1,0),"CNY",null);
        assertNull(r.get("amount"));assertEquals("INCOMPLETE_COST",r.get("status"));
        var rebuilding=cost("50",0,0);rebuilding.put("processing",true);
        assertNull(FinanceProfitReport.calculate(ledger(),rebuilding,"CNY",null).get("amount"));
        var partlyKnown=settlement("100","120");partlyKnown.put("records",2);
        assertNull(FinanceProfitReport.calculate(List.of(partlyKnown),cost("0",0,0),"CNY",null).get("amount"));
    }
    @Test void missingRecordedExpensesAndHistoricalCostPricesAreClearlyEstimated(){
        var r=FinanceProfitReport.calculate(List.of(settlement("100","120"),total("FEE","20")),cost("50",0,0),"CNY",null);
        money("50",r.get("amount"));assertEquals("ESTIMATED",r.get("status"));assertEquals(true,r.get("estimated"));
        assertEquals(2,((List<?>)r.get("notes")).size());
        assertEquals("ESTIMATED",FinanceProfitReport.calculate(ledger(),cost("50",0,2),"CNY",null).get("status"));
    }
    @Test void signedAdjustmentsAndNegativeProfitsAreNotClamped(){
        var r=FinanceProfitReport.calculate(List.of(settlement("100","95"),total("FEE","-5"),total("AD","10"),total("COST","5")),cost("120",0,0),"CNY",null);
        money("95",r.get("settledGrossAmount"));money("-35",r.get("amount"));
        assertEquals("NO_SETTLEMENT",FinanceProfitReport.calculate(List.of(),cost("0",0,0),"CNY",null).get("status"));
    }
    @Test void grossRankingMatchesFeesWithinShopAndCurrencyAndLeavesLedgerUnchanged(){
        var a=settlement("100","120");a.put("shop_id","a");
        var b=settlement("200","200");b.put("shop_id","b");
        var fee=total("FEE","20");fee.put("shop_id","a");
        var foreignFee=total("FEE","999");foreignFee.put("shop_id","a");foreignFee.put("currency","PHP");
        var result=FinanceProfitReport.withGross(List.of(a,b,fee,foreignFee),"shop_id");
        money("120",result.get(0).get("settled_gross_amount"));money("200",result.get(1).get("settled_gross_amount"));
        assertFalse(a.containsKey("settled_gross_amount"));money("100",result.get(0).get("amount"));
    }
    @Test void missingGrossIsNeverSynthesizedFromNetPlusFees(){
        var r=FinanceProfitReport.calculate(List.of(settlement("100",null),total("FEE","20")),cost("50",0,0),"CNY",null);
        assertNull(r.get("settledGrossAmount"));assertNull(r.get("amount"));
        assertEquals("INCOMPLETE_AMOUNTS",r.get("status"));
    }
    @Test void missingLogisticsAmountDoesNotThrowOrProduceProfit(){
        var r=FinanceProfitReport.calculate(List.of(settlement("100","120"),total("FEE","20"),total("COST",null)),cost("50",0,0),"CNY",null);
        assertNull(r.get("amount"));assertEquals("INCOMPLETE_AMOUNTS",r.get("status"));
    }
    @Test void operatingCostGroupsProductAndLabelsWithoutAddingRefundsAgain(){
        var costs=cost("50",0,0);map(costs.get("total")).put("label_cost_cny",decimal("3"));
        var r=FinanceProfitReport.calculate(ledger(),costs,"CNY",null);
        money("58",r.get("operatingCost"));money("58",r.get("knownOperatingCost"));money("32",r.get("amount"));
        money("32",decimal(r.get("settledLedgerAmount")).subtract(decimal(r.get("advertising"))).subtract(decimal(r.get("operatingCost"))));
        assertTrue(text(r.get("formula")).contains("营业成本"));
        // A non-CNY display converts both RMB components using the same reference, not mixed units.
        var q=new FinanceFx.Quote("test",Instant.EPOCH,Map.of("PHP",decimal("0.1")));
        r=FinanceProfitReport.calculate(ledger(),costs,"PHP",q);money("535",r.get("operatingCost"));
    }
    @Test void partiallyKnownOperatingCostStaysPartialAndMissingFxIsNeverTreatedAsZero(){
        var costs=cost(null,1,0);map(costs.get("total")).put("known_amount_cny",decimal("12"));
        var r=FinanceProfitReport.calculate(ledger(),costs,"CNY",null);
        assertNull(r.get("operatingCost"));money("17",r.get("knownOperatingCost"));assertNull(r.get("amount"));
        r=FinanceProfitReport.calculate(ledger(),costs,"PHP",null);
        assertNull(r.get("operatingCost"));assertNull(r.get("knownOperatingCost"));
        costs=cost("0",0,0);map(costs.get("total")).put("label_cost_cny",decimal("3"));
        r=FinanceProfitReport.calculate(ledger(),costs,"PHP",null);
        assertNull(r.get("logisticsLabelCost"));assertNull(r.get("operatingCost"));assertNull(r.get("amount"));
        var incomplete=total("COST","5");incomplete.put("records",2);
        r=FinanceProfitReport.calculate(List.of(settlement("100","120"),incomplete),cost("50",0,0),"CNY",null);
        assertNull(r.get("operatingCost"));assertNull(r.get("amount"));
    }
}
