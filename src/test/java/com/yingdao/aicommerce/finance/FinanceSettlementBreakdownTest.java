package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSettlementBreakdownTest {
    static Map<String,Object> raw(String json){return map(new ObjectMapper().readValue(json,Map.class));}
    static void money(String expected,BigDecimal value){assertNotNull(value);assertEquals(0,new BigDecimal(expected).compareTo(value));}
    static void balanced(FinanceSettlementBreakdown.Result r){money(r.net().toPlainString(),r.goods().subtract(r.deductions()));}
    @Test void tiktokDiscountIsNotConfusedWithCommissionAndAliasesAreNotDoubleCounted(){
        var r=FinanceSettlementBreakdown.parse("TIKTOK_SHOP",raw("""
            {"type":"ORDER","revenue_amount":"286.99","revenue_breakdown":{"subtotal_before_discount_amount":"902","seller_discount_amount":"-615.01"},
             "fee_tax_amount":"-90.95","shipping_cost_amount":"0",
             "fee_tax_breakdown":{"fee":{"platform_commission_amount":"-60","affiliate_commission_amount":"-30.95",
               "affiliate_commission_amount_before_pit":"-30.95"},"tax":{}},
             "shipping_cost_breakdown":{"actual_shipping_fee_amount":"-20","shipping_fee_discount_amount":"20",
               "supplementary_component":{"logistics_discount_amount":"20"}}}
            """),new BigDecimal("196.04"));
        money("286.99",r.goods());balanced(r);money("90.95",r.deductions());
        assertEquals(4,r.fees().size());assertTrue(r.fees().stream().noneMatch(f->f.group().equals("待核对")));
        assertTrue(r.fees().stream().noneMatch(f->f.field().startsWith("revenue_breakdown.")));
    }
    @Test void fullTiktokRefundIsZeroRevenueWithoutChargingRefundAgain(){
        var r=FinanceSettlementBreakdown.parse("TIKTOK_SHOP",raw("""
            {"type":"ORDER","revenue_amount":"0","revenue_breakdown":{"subtotal_before_discount_amount":"858","seller_discount_amount":"-585",
              "refund_subtotal_before_discount_amount":"-858","seller_discount_refund_amount":"585"},
             "fee_tax_amount":"0","shipping_cost_amount":"0"}
            """),BigDecimal.ZERO);
        money("0",r.goods());balanced(r);assertTrue(r.fees().isEmpty());
    }
    @Test void missingFeeSubcomponentIsNamedAsUnresolvedNotInventedTax(){
        var r=FinanceSettlementBreakdown.parse("TIKTOK_SHOP",raw("""
            {"type":"ORDER","revenue_amount":"285.71","revenue_breakdown":{"subtotal_before_discount_amount":"285.71"},
             "fee_tax_amount":"-88.03","fee_tax_breakdown":{"fee":{"platform_commission_amount":"-85.17"}},
             "shipping_cost_amount":"-62","shipping_cost_breakdown":{"actual_shipping_fee_amount":"-62"}}
            """),new BigDecimal("135.68"));
        balanced(r);var missing=r.fees().stream().filter(f->f.group().equals("待核对")).toList();
        assertEquals(1,missing.size());money("2.86",missing.getFirst().amount());
        assertEquals("fee_tax_amount.unitemized",missing.getFirst().field());
    }
    @Test void shopeeShippingContributionsAndDuplicateCreditCardFieldAreNotDoubleCounted(){
        var r=FinanceSettlementBreakdown.parse("SHOPEE",raw("""
            {"order_income":{"original_price":899,"seller_discount":349,"voucher_from_seller":165,
             "original_cost_of_goods_sold":550,"seller_return_refund":0,
             "escrow_amount":278,"escrow_amount_after_adjustment":278,
             "actual_shipping_fee":77,"buyer_paid_shipping_fee":6,"shopee_shipping_rebate":71,"final_shipping_fee":-6,
             "commission_fee":54,"service_fee":33,"seller_transaction_fee":13,"credit_card_transaction_fee":13,
             "order_ams_commission_fee":6,"seller_order_processing_fee":1,"voucher_from_shopee":30}}
            """),new BigDecimal("278"));
        balanced(r);money("385",r.goods());money("107",r.deductions());
        assertTrue(r.fees().stream().noneMatch(f->f.group().equals("待核对")));
        assertTrue(r.fees().stream().noneMatch(f->f.field().endsWith("final_shipping_fee")||f.field().endsWith("credit_card_transaction_fee")));
    }
    @Test void shopeeFinalOriginalPriceAlreadyReflectsRefund(){
        var r=FinanceSettlementBreakdown.parse("SHOPEE",raw("""
            {"order_income":{"original_price":0,"seller_discount":0,"original_cost_of_goods_sold":100,
             "seller_return_refund":-100,"escrow_amount":0}}
            """),BigDecimal.ZERO);
        money("0",r.goods());balanced(r);assertTrue(r.fees().isEmpty());
        r=FinanceSettlementBreakdown.parse("SHOPEE",raw("""
            {"order_income":{"original_price":100,"seller_discount":20,"original_cost_of_goods_sold":160,
             "seller_return_refund":-80,"commission_fee":10,"escrow_amount":70}}
            """),new BigDecimal("70"));
        money("80",r.goods());balanced(r);assertEquals(1,r.fees().size());
    }
    @Test void shopeeAdjustmentUsesActualBeforeAndAfterSign(){
        var r=FinanceSettlementBreakdown.parse("SHOPEE",raw("""
            {"order_income":{"original_price":100,"seller_discount":0,"original_cost_of_goods_sold":100,
             "escrow_amount":100,"escrow_amount_after_adjustment":105,"total_adjustment_amount":5}}
            """),new BigDecimal("105"));
        balanced(r);money("-5",r.deductions());
    }
    @Test void shopeeMonthlyIncomeUsesFinalGoodsIncludingExplicitZeroAndKeepsCumulativeAdjustmentsSeparate(){
        var raw=raw("""
            {"_finance_released_amount":-11,"order_income":{"original_price":0,"seller_discount":0,
             "original_cost_of_goods_sold":298,"seller_return_refund":-197,"cost_of_goods_sold":0,
             "escrow_amount":-11,"escrow_amount_after_adjustment":217.97,"total_adjustment_amount":228.97,
             "seller_order_processing_fee":11}}
            """);
        var r=FinanceSettlementBreakdown.parse("SHOPEE",raw,new BigDecimal("-11"));
        money("0",r.goods());money("11",r.deductions());balanced(r);
        assertTrue(r.fees().stream().noneMatch(f->f.group().equals("平台调整")||f.group().equals("待核对")));
        r=FinanceSettlementBreakdown.parse("SHOPEE",raw("""
            {"order_income":{"cost_of_goods_sold":100,"voucher_from_seller":5,"seller_coin_cash_back":2,
             "seller_discount":900,"seller_return_refund":-800,"commission_fee":10,"escrow_amount":83}}
            """),new BigDecimal("83"));
        money("93",r.goods());balanced(r);
        assertEquals(1,r.fees().size(),"seller refund and discount are already included, not deducted twice");
    }
    @Test void missingOriginalAmountIsDifferentFromExplicitZeroAndAdjustmentsHaveNoNewSales(){
        assertNull(FinanceSettlementBreakdown.parse("TIKTOK_SHOP",Map.of("type","ORDER"),BigDecimal.ZERO).goods());
        assertNull(FinanceSettlementBreakdown.parse("SHOPEE",Map.of("order_income",Map.of("escrow_amount",100)),new BigDecimal("100")).goods());
        var r=FinanceSettlementBreakdown.parse("TIKTOK_SHOP",Map.of("type","LOGISTICS_ADJUSTMENT","adjustment_amount","3"),new BigDecimal("3"));
        money("0",r.goods());money("-3",r.deductions());balanced(r);
        money("0",FinanceSettlementBreakdown.parse("SHOPEE",Map.of("order_income",Map.of("original_price",0)),BigDecimal.ZERO).goods());
    }
    @Test void unknownContributingFieldIsRetainedWithItsOriginalNameAndSign(){
        var r=FinanceSettlementBreakdown.parse("TIKTOK_SHOP",raw("""
            {"type":"ORDER","revenue_amount":"100","revenue_breakdown":{"subtotal_before_discount_amount":"100"},
             "fee_tax_amount":"-7","fee_tax_breakdown":{"fee":{"new_provider_fee_amount":"-7"}}}
            """),new BigDecimal("93"));
        balanced(r);assertTrue(r.fees().getFirst().label().contains("new_provider_fee_amount"));
        money("-7",r.fees().getFirst().sourceAmount());
    }
    @Test void originalPriceCannotSubstituteForMissingTotalRevenue(){
        var r=FinanceSettlementBreakdown.parse("TIKTOK_SHOP",raw("""
            {"type":"ORDER","revenue_breakdown":{"subtotal_before_discount_amount":"902","seller_discount_amount":"-615.01"},
             "fee_tax_amount":"-90.95"}
            """),new BigDecimal("196.04"));
        assertNull(r.goods());
        assertTrue(r.fees().stream().noneMatch(f->f.field().startsWith("revenue_breakdown.")));
    }
    @Test void laterRefundHasNegativeRevenueAndOnlyActualFeeReturns(){
        var r=FinanceSettlementBreakdown.parse("TIKTOK_SHOP",raw("""
            {"type":"ORDER","revenue_amount":"-273","revenue_breakdown":{"subtotal_before_discount_amount":"0",
              "refund_subtotal_before_discount_amount":"-858","seller_discount_refund_amount":"585"},
             "fee_tax_amount":"20","fee_tax_breakdown":{"fee":{"platform_commission_amount":"20"}}}
            """),new BigDecimal("-253"));
        money("-273",r.goods());money("-20",r.deductions());balanced(r);
    }
}
