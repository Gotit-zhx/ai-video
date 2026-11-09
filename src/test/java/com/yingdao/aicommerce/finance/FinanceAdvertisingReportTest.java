package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceAdvertisingReportTest {
    final Filter filter=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","PHP","day");
    final List<Map<String,Object>> shops=List.of(Map.of("id","a","group_id","baby"),Map.of("id","b","group_id","clothes"));
    Map<String,Object> row(String kind,String shop,String category,String amount){
        var row=new LinkedHashMap<String,Object>();
        row.put("kind",kind);row.put("shop_id",shop);row.put("category",category);row.put("currency","PHP");
        row.put("record_date","2026-10-09");row.put("records",1L);row.put("amount",decimal(amount));
        row.put("known_amounts",amount==null?0L:1L);row.put("known_revenues",0L);
        row.put("known_impressions",0L);row.put("known_clicks",0L);row.put("known_conversions",0L);
        return row;
    }
    Map<String,Object> report(List<Map<String,Object>> rows){return FinanceAdvertisingReport.build(rows,shops,filter,false,null,"day");}
    void money(String expected,Object actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo(decimal(actual)));}
    Map<String,Object> find(Object rows,String key,String value){return rows(rows).stream().filter(r->value.equals(r.get(key))).findFirst().orElseThrow();}

    @Test void allAdvertisingIncludesLedgerFeesAndUnassignedSpendWithoutAddingGmvAgain(){
        var report=report(List.of(row("AD","a","","100"),row("FEE","a","成交广告费","30"),
                row("FEE","b","fee_tax_breakdown.gmv_max_ad_fee_amount","25"),row("AD","UNASSIGNED","","50"),
                row("AD_GMV","a","","999"),row("FEE","a","commission_fee","700"),
                row("FEE","a","广告充值或技术支持费（平台合并字段）","600")));
        money("205",map(report.get("total")).get("amount"));money("150",map(report.get("independent")).get("amount"));
        money("55",map(report.get("settlement")).get("amount"));money("50",map(report.get("unassigned")).get("amount"));
        money("999",map(report.get("gmv")).get("amount"));
        money("130",find(report.get("shops"),"shop_id","a").get("amount"));
        money("25",find(report.get("groups"),"group_id","clothes").get("amount"));
        money("50",find(report.get("groups"),"group_id","").get("amount"));
        money("205",find(report.get("trend"),"period","2026-10-09").get("AD_TOTAL"));
        assertFalse(find(report.get("trend"),"period","2026-10-10").containsKey("AD_TOTAL"),"absent days are not fabricated zero spend");
    }
    @Test void refundsAndEveryPageContributeToTheDateRange(){
        var input=new ArrayList<Map<String,Object>>();
        for(int i=0;i<120;i++)input.add(row("AD","a","","0.1"));
        input.add(row("FEE","a","智能推广费用","-2.5"));
        var outside=row("AD","a","","999");outside.put("record_date","2026-11-01");input.add(outside);
        var result=report(input);
        money("9.5",map(result.get("total")).get("amount"));assertEquals(121,number(map(result.get("total")).get("records")));
        money("-2.5",map(result.get("settlement")).get("amount"));
    }
    @Test void gmvOnlyStoresAndGroupsStayVisibleWithoutClaimingFullFinancialReconciliation(){
        var result=report(List.of(row("AD_GMV","b","","13")));
        var shop=find(result.get("shops"),"shop_id","b");var group=find(result.get("groups"),"group_id","clothes");
        money("13",map(shop.get("gmv")).get("amount"));money("13",map(group.get("gmv")).get("amount"));
        assertEquals(false,shop.get("hasRecords"));money("0",map(result.get("total")).get("amount"));
        assertTrue(text(result.get("basis")).contains("不能将当前入账合计视为完整媒体消耗"));
    }
    @Test void nativeCurrenciesStaySeparateAndAllCnyTotalsUseOneQuote(){
        var php=row("AD","a","","100");var thb=row("FEE","b","gmv_max_ad_fee_amount","30");thb.put("currency","THB");
        var quote=new FinanceFx.Quote("fixed",Instant.EPOCH,Map.of("PHP",decimal("0.1"),"THB",decimal("0.2")));
        var all=new Filter(filter.start(),filter.end(),"","","","day");
        var cny=FinanceAdvertisingReport.build(List.of(php,thb),shops,all,true,quote,"day");
        money("16",map(cny.get("total")).get("amount"));money("10",find(cny.get("shops"),"shop_id","a").get("amount"));
        money("100",map(report(List.of(php,thb)).get("total")).get("amount"));
        assertThrows(RuntimeException.class,()->FinanceAdvertisingReport.build(List.of(php,thb),shops,all,true,
                new FinanceFx.Quote("missing",Instant.EPOCH,Map.of("PHP",BigDecimal.ONE)),"day"));
        assertThrows(RuntimeException.class,()->FinanceAdvertisingReport.build(List.of(php,thb),shops,all,false,null,"day"));
    }
    @Test void missingMoneyDoesNotBecomeACompleteTotal(){
        var report=report(List.of(row("AD","a","","10"),row("FEE","a","pay_per_sale",null)));
        var total=map(report.get("total"));assertNull(total.get("amount"));assertEquals(false,total.get("complete"));money("10",total.get("knownAmount"));
        assertNull(find(report.get("trend"),"period","2026-10-09").get("AD_TOTAL"));
        money("0",map(report(List.of(row("AD","a","","0"))).get("total")).get("amount"));
        assertEquals(true,map(report(List.of(row("AD","a","","0"))).get("total")).get("hasRecords"));
        assertEquals(false,map(report(List.of()).get("total")).get("hasRecords"));
    }
    @Test void performanceUsesWeightedTotalsAndNeverAppliesLedgerSpendToCpc(){
        var one=row("AD","a","","20");var two=row("AD","b","","80");
        for(var item:List.of(one,two)){item.put("known_impressions",1);item.put("known_clicks",1);item.put("known_conversions",1);}
        one.put("impressions",100);one.put("clicks",10);one.put("conversions",2);
        two.put("impressions",900);two.put("clicks",30);two.put("conversions",3);
        var result=report(List.of(one,two,row("FEE","a","pay_per_sale","900")));
        var p=map(result.get("performance"));money("4",p.get("clickThroughRate"));money("2.5",p.get("costPerClick"));
        money("100",p.get("costPerThousand"));money("20",p.get("costPerConversion"));money("12.5",p.get("conversionRate"));
        var unknown=row("AD","a","","10");
        var partial=map(report(List.of(one,unknown)).get("performance"));assertNull(partial.get("clickThroughRate"));assertNull(partial.get("costPerClick"));
        one.put("clicks",0);one.put("impressions",0);one.put("conversions",0);
        var zero=map(report(List.of(one)).get("performance"));assertNull(zero.get("clickThroughRate"));assertNull(zero.get("costPerConversion"));
    }
    @Test void absentSourcesStayUnknownInTrendsWhileRecordedZeroRemainsZero(){
        var fees=report(List.of(row("FEE","a","达人广告佣金","10")));
        var point=find(fees.get("trend"),"period","2026-10-09");
        assertNull(point.get("AD_INDEPENDENT"));money("10",point.get("AD_SETTLEMENT"));
        var zero=report(List.of(row("AD","a","","0")));
        point=find(zero.get("trend"),"period","2026-10-09");
        money("0",point.get("AD_INDEPENDENT"));assertNull(point.get("AD_SETTLEMENT"));
    }
    @Test void gmvAttributionAndLedgerPresentationNeverChangeTheProfitFormula(){
        var gmv=row("AD_GMV","a","","30");gmv.put("revenue",decimal("90"));gmv.put("known_revenues",1);
        money("3",map(report(List.of(gmv)).get("gmv")).get("roi"));money("0",map(report(List.of(gmv)).get("total")).get("amount"));
        var settled=row("SETTLEMENT","a","","80");settled.put("revenue",decimal("100"));settled.put("known_revenues",1);
        var totals=List.of(settled,row("FEE","a","pay_per_sale","20"),row("AD","a","","10"));
        var costs=Map.<String,Object>of("total",Map.of("amount_cny",BigDecimal.ZERO,"known_amount_cny",BigDecimal.ZERO));
        money("70",FinanceProfitReport.calculate(totals,costs,"CNY",null).get("amount"));
        money("30",map(report(totals).get("total")).get("amount"));
    }
    @Test void advertisingCommissionsAreIncludedAndItemizedWithoutChangingProfitOrPerformance(){
        var inputs=new ArrayList<Map<String,Object>>(List.of(row("FEE","a","GMV Max 广告费用","10"),
                row("FEE","a","fee_tax_breakdown.fee.affiliate_ads_commission_amount","20"),
                row("FEE","b","合作伙伴店铺广告佣金","30"),
                row("FEE","a","达人广告佣金","-5"),
                row("FEE","a","affiliate_commission_amount","900"),
                row("FEE","b","affiliate_partner_commission_amount","800"),
                row("AD","a","","7")));
        var result=report(inputs);
        money("62",map(result.get("total")).get("amount"));money("55",map(result.get("settlement")).get("amount"));
        money("45",map(result.get("commission")).get("amount"));money("7",map(result.get("performance")).get("amount"));
        money("15",map(find(result.get("shops"),"shop_id","a").get("commission")).get("amount"));
        money("30",map(find(result.get("groups"),"group_id","clothes").get("commission")).get("amount"));
        money("15",find(result.get("categories"),"category","达人广告佣金").get("amount"));
        money("30",find(result.get("categories"),"category","合作伙伴店铺广告佣金").get("amount"));
        money("62",find(result.get("trend"),"period","2026-10-09").get("AD_TOTAL"));
        var settled=row("SETTLEMENT","a","","100");settled.put("revenue",decimal("1855"));settled.put("known_revenues",1);inputs.add(settled);
        var costs=Map.<String,Object>of("total",Map.of("amount_cny",BigDecimal.ZERO,"known_amount_cny",BigDecimal.ZERO));
        money("93",FinanceProfitReport.calculate(inputs,costs,"CNY",null).get("amount"));
        var missing=report(List.of(row("FEE","a","达人广告佣金",null)));
        assertNull(map(missing.get("total")).get("amount"));assertNull(map(missing.get("commission")).get("amount"));
    }
    @Test void classificationIncludesAdvertisingCommissionsButExcludesOrdinaryCommissionsAndFunding(){
        for(String category:List.of("pay_per_sale","order_income.pay_per_sale","GMV Max 广告费用","智能推广费用",
                "达人广告佣金","合作伙伴店铺广告佣金"," AFFILIATE_ADS_COMMISSION_AMOUNT ","Fee_Tax_Breakdown.Fee.TAP_SHOP_ADS_COMMISSION"))assertTrue(FinanceAdvertisingReport.ledgerCategory(category),category);
        for(String category:List.of("GMV_PAYMENT_FOR_ADS","GMV_PAYMENT_FOR_TIKTOK_ADS","平台调整（GMV_PAYMENT_FOR_TIKTOK_ADS）",
                "ads_escrow_top_up_fee_or_technical_support_fee","campaign_fee","order_ams_commission_fee","gmv_max_coupon_fee","佣金",
                "affiliate_commission_amount","达人佣金","affiliate_partner_commission_amount","合作伙伴佣金"))assertFalse(FinanceAdvertisingReport.ledgerCategory(category),category);
        assertFalse(FinanceAdvertisingReport.includes(row("SETTLEMENT","a","pay_per_sale","50")));
    }
}
