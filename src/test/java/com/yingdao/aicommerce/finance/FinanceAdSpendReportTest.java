package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceAdSpendReportTest {
    final Filter filter=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
    final List<Map<String,Object>> shops=List.of(Map.of("id","a","platform","TIKTOK_SHOP","group_id","clothes","group_name","服装","name","甲"),Map.of("id","b","platform","TIKTOK_SHOP","group_id","clothes","group_name","服装","name","乙"),Map.of("id","c","platform","SHOPEE","group_id","clothes","group_name","服装","name","丙"));
    List<Map<String,Object>> imported(){return FinanceAdSpendImport.parse(List.of(
        List.of("日期","系列名称","推广系列ID","现金消耗","信用额度消耗","广告费用赠款消耗","金额","币种","类型"),
        List.of("2026-10-02","不能猜店铺","9012345678901234","0","-6.60","-1.21","-7.81","USD","Auction")),"TIKTOK_SHOP","123","账户",true);}
    Map<String,Object> source(String id,String platform,String shop,String amount){
        var r=new LinkedHashMap<String,Object>();r.put("source_key",id);r.put("platform",platform);r.put("account_id","123");r.put("account_name","账户");r.put("campaign_id","series");r.put("campaign_name","系列");r.put("record_date","2026-10-02");r.put("currency","USD");r.put("amount",new BigDecimal(amount));r.put("source","API_GMV");r.put("shop_id",shop);return r;
    }
    Map<String,Object> report(List<Map<String,Object>> sources,List<Map<String,Object>> splits){return FinanceAdSpendReport.build(sources,splits,shops,filter,null,"","");}
    Map<String,Object> apiRecord(String kind){return new LinkedHashMap<>(Map.of("id","api","shop_id","a","platform","TIKTOK_SHOP","kind",kind,"record_date","2026-10-02","currency","USD","amount",new BigDecimal("10"),"source","API"));}
    @Test void gmvNetCostIsNotMislabelledAsCashOrCreditAndExplicitZeroIsRetained(){
        var r=FinanceAdSpendReport.api(apiRecord("AD_GMV"),Map.of("advertiser_id","123","dimensions",Map.of("campaign_id","series"),"metrics",Map.of("cost","10","net_cost","0","cash_spend","99","spend_credits","77")));
        assertEquals(BigDecimal.ZERO,r.get("net_amount"));assertNull(r.get("cash_amount"));assertNull(r.get("credit_amount"));assertNull(r.get("grant_amount"));
        assertEquals("接口未拆分",r.get("reason_cash_amount"));assertTrue(text(r.get("funding_basis")).contains("net_cost"));
    }
    @Test void advertiserFundingUsesOnlyMatchingOfficialFieldsAndNeverAppConversionCreditEvents(){
        var r=FinanceAdSpendReport.api(apiRecord("AD"),Map.of("advertiser_id","123","metrics",Map.of("spend","10","billed_cost","8","cash_spend","0","voucher_spend","2","spend_credits","19")));
        assertEquals(BigDecimal.ZERO,r.get("cash_amount"));assertEquals(new BigDecimal("2"),r.get("grant_amount"));assertEquals(new BigDecimal("8"),r.get("net_amount"));assertNull(r.get("credit_amount"));
        var missing=FinanceAdSpendReport.api(apiRecord("AD"),Map.of("metrics",Map.of("cash_spend","-","voucher_spend","","billed_cost","-")));
        assertNull(missing.get("cash_amount"));assertNull(missing.get("net_amount"));
    }
    @Test void netCostUsesSameFxAndReportsPartialCoverageWithoutChangingFundingFingerprint(){
        var a=source("1","TIKTOK_SHOP","a","10");String fingerprint=FinanceAdSpendReport.fingerprint(a);a.put("net_amount",new BigDecimal("8"));assertEquals(fingerprint,FinanceAdSpendReport.fingerprint(a));
        var b=source("2","TIKTOK_SHOP","a","5");var quote=new FinanceFx.Quote("fixed",java.time.Instant.EPOCH,Map.of("USD",new BigDecimal("7")));
        var result=FinanceAdSpendReport.build(List.of(a,b),List.of(),shops,filter,quote,"","");var total=rows(result.get("totals")).getFirst();
        assertNull(total.get("net_amount"));assertEquals(1L,total.get("known_net_amount"));assertEquals(new BigDecimal("56.000000"),total.get("partial_net_amount"));
        var detail=rows(result.get("items")).stream().filter(r->"1".equals(r.get("source_key"))).findFirst().orElseThrow();assertEquals(new BigDecimal("8"),detail.get("original_net_amount"));
    }
    @Test void accountFundingSurvivesExcludedAccountTotalWithoutBeingAllocatedToCampaigns(){
        var basic=FinanceAdSpendReport.api(apiRecord("AD"),Map.of("advertiser_id","123","metrics",Map.of("spend","10","billed_cost","8","cash_spend","6","voucher_spend","2")));
        basic.put("shop_id","UNASSIGNED");var campaign=source("gmv","TIKTOK_SHOP","a","10");campaign.put("net_amount",new BigDecimal("8"));
        var result=report(List.of(basic,campaign),List.of());assertEquals(BigDecimal.TEN,rows(result.get("totals")).getFirst().get("amount"));
        var account=rows(result.get("accountFunding")).getFirst();assertEquals(new BigDecimal("6"),account.get("cash_amount"));assertEquals(BigDecimal.TEN,account.get("amount"));
        var detail=rows(result.get("items")).getFirst();assertNull(detail.get("cash_amount"));
        var otherShop=new Filter(filter.start(),filter.end(),"b","TIKTOK_SHOP","USD","day");assertTrue(rows(FinanceAdSpendReport.build(List.of(basic,campaign),List.of(),shops,otherShop,null,"","").get("accountFunding")).isEmpty());
    }
    @Test void manualSplitsDoNotDuplicateSourceNetCostAndFundingSplitsCanUseTheirOwnKnownComponents(){
        var r=source("split","TIKTOK_SHOP","","10");r.put("net_amount",new BigDecimal("8"));
        var split=Map.<String,Object>of("source_key","split","fingerprint",FinanceAdSpendReport.fingerprint(r),"shop_id","a","amount",new BigDecimal("4"));
        for(var row:rows(report(List.of(r),List.of(split)).get("items")))assertNull(row.get("net_amount"));
        var funds=imported().getFirst();var confirmed=Map.<String,Object>of("source_key",funds.get("source_key"),"fingerprint",FinanceAdSpendReport.fingerprint(funds),"shop_id","a","amount",new BigDecimal("4"),"cash_amount",BigDecimal.ZERO,"credit_amount",new BigDecimal("3.5"),"grant_amount",new BigDecimal("0.5"));
        assertEquals(new BigDecimal("6.60"),rows(report(List.of(funds),List.of(confirmed)).get("totals")).getFirst().get("net_amount"));
    }
    @Test void fundingFileKeepsAllThreeComponentsAndReversesDebitRatherThanTakingAbsoluteValue(){
        var r=imported().getFirst();assertEquals(new BigDecimal("0"),r.get("cash_amount"));assertEquals(new BigDecimal("6.60"),r.get("credit_amount"));assertEquals(new BigDecimal("1.21"),r.get("grant_amount"));assertEquals(new BigDecimal("7.81"),r.get("amount"));assertEquals("9012345678901234",r.get("campaign_id"));
        var refunds=FinanceAdSpendImport.parse(List.of(List.of("日期","推广系列ID","金额","币种"),List.of("2026-10-02","s","2.50","USD")),"TIKTOK_SHOP","123","账户",true);assertEquals(new BigDecimal("-2.50"),refunds.getFirst().get("amount"));
    }
    @Test void fundingControlAndRepeatedCampaignDailyKeysRejectConflicts(){
        assertThrows(RuntimeException.class,()->FinanceAdSpendImport.parse(List.of(List.of("日期","推广系列ID","现金消耗","信用额度消耗","广告费用赠款消耗","金额","币种"),List.of("2026-10-02","s","0","-6","-1","-9","USD")),"TIKTOK_SHOP","123","账户",true));
        assertThrows(RuntimeException.class,()->FinanceAdSpendImport.parse(List.of(List.of("日期","推广系列ID","金额","币种"),List.of("2026-10-02","s","-2","USD"),List.of("2026-10-02","s","-3","USD")),"TIKTOK_SHOP","123","账户",true));
    }
    @Test void optionalTypeColumnDoesNotChangeTheFinancialIdentity(){
        var withoutType=FinanceAdSpendImport.parse(List.of(List.of("日期","推广系列ID","现金消耗","信用额度消耗","广告费用赠款消耗","金额","币种"),List.of("2026-10-02","9012345678901234","0","-6.60","-1.21","-7.81","USD")),"TIKTOK_SHOP","123","账户",true).getFirst();
        assertEquals(imported().getFirst().get("source_key"),withoutType.get("source_key"));
    }
    @Test void financialImportsRequireAStableAccountIdentityWithoutRequiringOAuth(){
        assertThrows(RuntimeException.class,()->FinanceAdSpendImport.parse(List.of(List.of("日期","推广系列ID","金额","币种"),List.of("2026-10-02","s","-10","USD")),"TIKTOK_SHOP","","未知账户",true));
    }
    @Test void unidentifiedEarlierSourceDoesNotDoubleTotalWhenApiArrivesLater(){
        var earlier=source("early","TIKTOK_SHOP","","10");earlier.put("account_id","manual:unknown");earlier.put("source","IMPORT_FUNDING");
        var later=source("later","TIKTOK_SHOP","a","10");later.put("source","API_BASIC");later.put("campaign_id","");
        var result=report(List.of(earlier,later),List.of());var total=rows(result.get("totals")).getFirst();assertNull(total.get("amount"));assertEquals(BigDecimal.TEN,total.get("partial_amount"));assertEquals(1,result.get("overlapUnresolved"));assertEquals(2,rows(result.get("items")).size());
    }
    @Test void exportsKeepPartialKnownAmountsExplicitInsteadOfDroppingThem(){
        var row=new HashMap<String,Object>();row.put("amount",null);row.put("known_amount",1);row.put("partial_amount",new BigDecimal("10"));
        assertEquals("10（已知部分）",FinanceAdSpendController.sheet(List.of(row),List.of("amount"),List.of("消耗合计")).get(1).getFirst());
    }
    @Test void unassignedFundingNeverUsesAccountOrCampaignNameToGuessShop(){
        var result=report(imported(),List.of());var detail=rows(result.get("items")).getFirst();assertEquals("UNASSIGNED",detail.get("shop_id"));assertEquals("PENDING",detail.get("assignment_status"));assertEquals(new BigDecimal("7.81"),rows(result.get("totals")).getFirst().get("amount"));
    }
    @Test void allFundingComponentsUseOneReferenceQuoteAndPreserveNativeAmounts(){
        var source=imported().getFirst();var quote=new FinanceFx.Quote("fixed",java.time.Instant.EPOCH,Map.of("USD",new BigDecimal("7")));
        var row=rows(FinanceAdSpendReport.build(List.of(source),List.of(),shops,filter,quote,"","").get("items")).getFirst();
        assertEquals(new BigDecimal("54.670000"),row.get("amount"));assertEquals(new BigDecimal("46.200000"),row.get("credit_amount"));assertEquals(new BigDecimal("8.470000"),row.get("grant_amount"));assertEquals(new BigDecimal("7.81"),row.get("original_amount"));assertEquals("CNY",row.get("currency"));assertEquals("USD",row.get("source_currency"));assertEquals(new BigDecimal("7.81"),source.get("amount"));
    }
    @Test void platformsShareTablesWithoutAddingUnknownFundingAsZero(){
        var result=report(List.of(source("1","TIKTOK_SHOP","a","10"),source("2","SHOPEE","c","20")),List.of());assertEquals(2,rows(result.get("groups")).size());assertEquals(3,rows(result.get("shops")).size());
        for(var r:rows(result.get("groups"))){assertNull(r.get("cash_amount"));assertEquals(0L,r.get("known_cash_amount"));}
        var missing=rows(result.get("shops")).stream().filter(r->"b".equals(r.get("shop_id"))).findFirst().orElseThrow();assertNull(missing.get("amount"));assertEquals(0,missing.get("records"));
    }
    @Test void shopeeShopScopedSourceUsesExactPlatformShopIdAndKeepsMissingFundingUnknown(){
        var imported=FinanceAdSpendImport.parse(List.of(List.of("date","ad_id","expense","currency"),List.of("2026-10-02","9012345678901234","9.00","USD")),"SHOPEE","SHOP_123","Shop source",false);
        var actual=new ArrayList<Map<String,Object>>(shops);var shop=new LinkedHashMap<>(actual.get(2));shop.put("external_shop_id","SHOP_123");actual.set(2,shop);
        var detail=rows(FinanceAdSpendReport.build(imported,List.of(),actual,filter,null,"","").get("items")).getFirst();assertEquals("c",detail.get("shop_id"));assertNull(detail.get("cash_amount"));assertEquals(new BigDecimal("9.00"),detail.get("amount"));
    }
    @Test void campaignLevelApiRowsAreNotDiscardedAsTheirOwnAccountSummary(){
        var record=new LinkedHashMap<String,Object>(Map.of("id","api","shop_id","c","platform","SHOPEE","kind","AD","record_date","2026-10-02","currency","USD","amount",new BigDecimal("9"),"source","API"));
        var normalized=FinanceAdSpendReport.api(record,Map.of("shop_id","SHOP_123","ad_id","9012345678901234","ad_name","Product promotion"));
        assertEquals("9012345678901234",normalized.get("campaign_id"));assertEquals("SHOP_123",normalized.get("account_id"));assertEquals(BigDecimal.valueOf(9),rows(report(List.of(normalized),List.of()).get("totals")).getFirst().get("amount"));
    }
    @Test void accountIdentityMissingFromOneApiFactIsNotOverlapWithItself(){
        var source=source("api","SHOPEE","c","9");source.put("account_id","");source.put("campaign_id","");source.put("source","API_BASIC");
        assertEquals(BigDecimal.valueOf(9),rows(report(List.of(source),List.of()).get("totals")).getFirst().get("amount"));
    }
    @Test void exactManualSplitKeepsResidualAndThreeFundingControls(){
        var r=imported().getFirst();String key=text(r.get("source_key")),fingerprint=FinanceAdSpendReport.fingerprint(r);
        var split=new LinkedHashMap<String,Object>(Map.of("source_key",key,"fingerprint",fingerprint,"shop_id","a","amount",new BigDecimal("4"),"cash_amount",BigDecimal.ZERO,"credit_amount",new BigDecimal("3.5"),"grant_amount",new BigDecimal("0.5")));
        var result=report(List.of(r),List.of(split));assertEquals(2,rows(result.get("items")).size());assertEquals(new BigDecimal("7.81"),rows(result.get("totals")).getFirst().get("amount"));assertEquals(new BigDecimal("6.60"),rows(result.get("totals")).getFirst().get("credit_amount"));
        var pending=rows(result.get("items")).stream().filter(x->"UNASSIGNED".equals(x.get("shop_id"))).findFirst().orElseThrow();assertEquals(new BigDecimal("3.81"),pending.get("amount"));
        split.put("amount",new BigDecimal("8"));assertThrows(RuntimeException.class,()->report(List.of(r),List.of(split)));
    }
    @Test void changedSourceAmountsInvalidatePreviouslyConfirmedAssignments(){
        var r=source("1","TIKTOK_SHOP","","10");var split=new LinkedHashMap<String,Object>(Map.of("source_key","1","fingerprint",FinanceAdSpendReport.fingerprint(r),"shop_id","a","amount",BigDecimal.TEN));r.put("amount",new BigDecimal("12"));
        var detail=rows(report(List.of(r),List.of(split)).get("items")).getFirst();assertEquals("REVIEW",detail.get("assignment_status"));assertEquals("UNASSIGNED",detail.get("shop_id"));
    }
    @Test void fundingAndApiOverlapDoesNotDoubleCountAndShopFilteringOccursAfterAssignment(){
        var imported=imported().getFirst();var api=source("api","TIKTOK_SHOP","a","7.81");api.put("campaign_id",imported.get("campaign_id"));
        var account=source("account","TIKTOK_SHOP","","7.81");account.put("campaign_id","");account.put("source","API_BASIC");
        var report=report(List.of(imported,api,account),List.of());assertEquals(new BigDecimal("7.81"),rows(report.get("totals")).getFirst().get("amount"));assertEquals(2,report.get("overlapExcluded"));
        var split=Map.<String,Object>of("source_key",imported.get("source_key"),"fingerprint",FinanceAdSpendReport.fingerprint(imported),"shop_id","a","amount",new BigDecimal("7.81"),"cash_amount",BigDecimal.ZERO,"credit_amount",new BigDecimal("6.60"),"grant_amount",new BigDecimal("1.21"));
        var f=new Filter(filter.start(),filter.end(),"a","TIKTOK_SHOP","USD","day");assertEquals(1,rows(FinanceAdSpendReport.build(List.of(imported),List.of(split),shops,f,null,"","").get("items")).size());
    }
    @Test void fundsAutoMatchOnlyOneOfficialShopWithExactMatchingCampaignAmount(){
        var funds=imported().getFirst();var official=source("official","TIKTOK_SHOP","a","7.81");official.put("campaign_id",funds.get("campaign_id"));
        assertEquals("a",rows(report(List.of(funds,official),List.of()).get("items")).getFirst().get("shop_id"));
        var second=source("second","TIKTOK_SHOP","b","3");second.put("campaign_id",funds.get("campaign_id"));official.put("amount",new BigDecimal("4.81"));
        assertEquals("UNASSIGNED",rows(report(List.of(funds,official,second),List.of()).get("items")).getFirst().get("shop_id"));
    }
    @Test void laterAutomaticMatchesDoNotOverwriteAnExplicitlyConfirmedFundingSplit(){
        var funds=imported().getFirst();var official=source("official","TIKTOK_SHOP","a","7.81");official.put("campaign_id",funds.get("campaign_id"));
        var split=Map.<String,Object>of("source_key",funds.get("source_key"),"fingerprint",FinanceAdSpendReport.fingerprint(funds),"shop_id","b","amount",new BigDecimal("7.81"),"cash_amount",BigDecimal.ZERO,"credit_amount",new BigDecimal("6.60"),"grant_amount",new BigDecimal("1.21"));
        assertEquals("b",rows(report(List.of(funds,official),List.of(split)).get("items")).getFirst().get("shop_id"));
    }
}
