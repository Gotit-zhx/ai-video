package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceShopeeGmsReportTest {
    final Filter filter=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
    final List<Map<String,Object>> shops=List.of(Map.of("id","s","platform","SHOPEE","name","Shop","group_id","g","group_name","Group","external_shop_id","10001"),Map.of("id","t","platform","TIKTOK_SHOP","name","TikTok","group_id","g","group_name","Group"));
    Map<String,Object> source(String id,String date,String total,String gms){
        var r=new LinkedHashMap<String,Object>(Map.of("source_key",id,"platform","SHOPEE","shop_id","s","account_id","10001","record_date",date,"currency","USD","amount",new BigDecimal(total),"source","API_SHOPEE_CPC"));
        if(gms!=null){r.put("gms_amount",new BigDecimal(gms));r.put("gms_currency","USD");}return r;
    }
    Map<String,Object> build(List<Map<String,Object>> data,FinanceFx.Quote quote){return FinanceAdSpendReport.build(data,List.of(),shops,filter,quote,"","");}
    @Test void commonEntryUsesTheReferenceInEveryTableAndPreservesRawFacts(){
        var raw=source("one","2026-10-01","120","20");var report=build(List.of(raw),null);
        for(String key:List.of("totals","groups","shops","items")){
            var row=rows(report.get(key)).getFirst();assertEquals(new BigDecimal("100"),row.get("amount"));
            assertEquals(new BigDecimal("120"),row.get("all_shop_amount"));assertEquals(new BigDecimal("20"),row.get("gms_amount"));
            assertEquals(FinanceShopeeGmsApi.BASIS,row.get("reference_basis"));
        }
        var detail=rows(report.get("items")).getFirst();assertEquals(new BigDecimal("100"),detail.get("original_amount"));assertEquals(new BigDecimal("120"),detail.get("original_all_shop_amount"));
        assertEquals(new BigDecimal("100"),rows(build(List.of(detail),null).get("items")).getFirst().get("amount"));
        assertEquals(new BigDecimal("120"),raw.get("amount"));assertFalse(raw.containsKey("all_shop_amount"));
    }
    @Test void oldCachedCpcIsUnknownAndDoesNotRestoreTheOldTotalOrAnImportedDuplicate(){
        var known=source("known","2026-10-01","120","20");var old=source("cached","2026-10-02","50",null);
        var imported=new LinkedHashMap<>(old);imported.put("source","IMPORT_FUNDING");imported.put("source_key","import");
        var report=build(List.of(known,old,imported),null);assertEquals(1,report.get("overlapExcluded"));
        for(String key:List.of("totals","groups","shops")){
            var row=rows(report.get(key)).getFirst();assertNull(row.get("amount"));assertEquals(1L,row.get("known_amount"));
            assertEquals(new BigDecimal("100"),row.get("partial_amount"));assertEquals("GMS 日报待补齐",row.get("reason_amount"));
            assertEquals(new BigDecimal("170"),row.get("all_shop_amount"));assertNull(row.get("gms_amount"));assertEquals(new BigDecimal("20"),row.get("partial_gms_amount"));
        }
        assertEquals(2,rows(report.get("items")).size());
    }
    @Test void allAuxiliaryAmountsFollowTheSameDailyFxAndNeverBecomeFundingComponents(){
        var one=source("one","2026-10-01","120","20");var two=source("two","2026-10-02","10","0");
        String fingerprint=FinanceAdSpendReport.fingerprint(one);one.put("all_shop_amount",new BigDecimal("120"));assertEquals(fingerprint,FinanceAdSpendReport.fingerprint(one));
        var quote=new FinanceFx.Quote("history",Instant.EPOCH,Map.of(),Map.of(filter.start(),new FinanceFx.Day(filter.start(),Map.of("USD",new BigDecimal("7"))),filter.start().plusDays(1),new FinanceFx.Day(filter.start().plusDays(1),Map.of("USD",new BigDecimal("8")))));
        var report=build(List.of(one,two),quote);var total=rows(report.get("totals")).getFirst();
        assertEquals(new BigDecimal("780.000000"),total.get("amount"));assertEquals(new BigDecimal("920.000000"),total.get("all_shop_amount"));assertEquals(new BigDecimal("140.000000"),total.get("gms_amount"));
        var day=rows(report.get("items")).stream().filter(r->"one".equals(r.get("source_key"))).findFirst().orElseThrow();
        assertEquals(new BigDecimal("20"),day.get("original_gms_amount"));assertEquals(new BigDecimal("120"),day.get("original_all_shop_amount"));assertEquals("USD",day.get("source_currency"));assertEquals("CNY",day.get("currency"));
        for(String key:List.of("cash_amount","credit_amount","grant_amount","net_amount"))assertNull(total.get(key));
    }
    @Test void mixedPlatformSummaryHasSeparateShopeeCoverageWithoutRequiringGmsForTiktok(){
        var tk=new LinkedHashMap<String,Object>(Map.of("source_key","tk","platform","TIKTOK_SHOP","shop_id","t","account_id","20001","campaign_id","30001","record_date","2026-10-01","currency","USD","amount",new BigDecimal("5"),"source","API_GMV"));
        var total=rows(build(List.of(source("one","2026-10-01","120","20"),tk),null).get("totals")).getFirst();
        assertEquals(new BigDecimal("105"),total.get("amount"));assertEquals(1L,total.get("reference_records"));
        assertEquals(new BigDecimal("120"),total.get("all_shop_amount"));assertEquals(new BigDecimal("20"),total.get("gms_amount"));assertEquals(1L,total.get("known_gms_amount"));
    }
    @Test void referencePreservesNegativeResultsAndMissingCpcIsAlsoUnknown(){
        var raw=source("refund","2026-10-01","10","12");assertEquals(new BigDecimal("-2"),rows(build(List.of(raw),null).get("items")).getFirst().get("amount"));
        raw.remove("amount");var unknown=rows(build(List.of(raw),null).get("items")).getFirst();assertNull(unknown.get("amount"));assertTrue(text(unknown.get("reason_amount")).contains("CPC"));
    }
    @Test void missingNativeCpcCannotFallBackToLegacyApiOrImportedAmounts(){
        for(String code:List.of("API_CAMPAIGN","IMPORT_FUNDING","LEGACY_IMPORT")){
            var raw=source("legacy","2026-10-01","50",null);raw.put("source",code);
            var report=build(List.of(raw),null);
            for(String table:List.of("totals","groups","shops","items"))assertNull(rows(report.get(table)).getFirst().get("amount"),code+" must not silently restore a different main basis");
            var item=rows(report.get("items")).getFirst();assertEquals(new BigDecimal("50"),item.get("source_reported_amount"));assertNull(item.get("all_shop_amount"));assertTrue(text(item.get("reason_amount")).contains("CPC"));
            assertEquals(new BigDecimal("50"),raw.get("amount"));
        }
    }
}
