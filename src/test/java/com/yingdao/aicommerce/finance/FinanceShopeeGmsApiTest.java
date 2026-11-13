package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceShopeeGmsApiTest {
    final LocalDate from=LocalDate.of(2026,10,1),to=from.plusDays(2),today=to.plusDays(1);
    Map<String,Object> metric(int day,Object expense){return Map.of("date","%02d-10-2026".formatted(day),"expense",expense);}
    Map<String,Object> campaign(String id,Object... days){return Map.of("campaign_id",id,"metrics_list",List.of(days));}
    Map<String,Object> envelope(Object... campaigns){return Map.of("response",Map.of("shop_id","10001","campaign_list",List.of(campaigns)));}
    @Test void discoveryRequiresARealCampaignAndAnExplicitCompleteReport(){
        assertEquals("9001",FinanceShopeeGmsApi.campaign(Map.of("response",Map.of("campaign_id",9001,"report",Map.of("expense",0)))));
        for(var invalid:List.of(Map.of(),Map.of("response",Map.of("campaign_id",0,"report",Map.of("expense",0))),Map.of("response",Map.of("campaign_id",9001)),Map.of("warning","partial","response",Map.of("campaign_id",9001,"report",Map.of("expense",0)))))
            assertThrows(RuntimeException.class,()->FinanceShopeeGmsApi.campaign(map(invalid)));
    }
    @Test void exactDailyExpensesSupportCurrentObjectAndLegacyArrayWithoutFundingGuesses(){
        var response=envelope(campaign("9001",metric(1,"1.230001"),metric(2,0),metric(3,"-0.2")));
        for(var value:List.of(response,Map.of("response",List.of(response.get("response"))))){
            var facts=FinanceShopeeGmsApi.daily(map(value),"10001",List.of("9001"),from,to,today);
            assertEquals(3,facts.size());assertEquals(new BigDecimal("1.230001"),facts.getFirst().get("amount"));
            assertEquals(BigDecimal.ZERO,decimal(facts.get(1).get("amount")));assertEquals(new BigDecimal("-0.2"),facts.getLast().get("amount"));
            assertFalse(facts.getFirst().containsKey("cash_amount"));
        }
    }
    @Test void everyKnownCampaignMustReturnEveryClosedDayBeforeCombining(){
        var first=campaign("9001",metric(1,1),metric(2,2),metric(3,3));
        var complete=FinanceShopeeGmsApi.daily(envelope(first,campaign("9002",metric(1,4),metric(2,5),metric(3,6))),"10001",List.of("9001","9002"),from,to,today);
        assertEquals(new BigDecimal("5"),complete.getFirst().get("amount"));
        assertThrows(RuntimeException.class,()->FinanceShopeeGmsApi.daily(envelope(first,campaign("9002",metric(1,4),metric(3,6))),"10001",List.of("9001","9002"),from,to,today));
        assertThrows(RuntimeException.class,()->FinanceShopeeGmsApi.daily(envelope(first),"10001",List.of("9001","9002"),from,to,today));
    }
    @Test void foreignDuplicateOutOfRangeAndMalformedRowsAreRejected(){
        var valid=campaign("9001",metric(1,1),metric(2,2),metric(3,3));
        for(var invalid:List.of(Map.of("response",Map.of("shop_id","other","campaign_list",List.of(valid))),envelope(valid,valid),envelope(campaign("9001",metric(1,1),metric(1,2),metric(3,3))),envelope(campaign("9001",metric(1,1),metric(2,2),metric(4,3))),envelope(campaign("9001",metric(1,1),metric(2,"0.0000001"),metric(3,3))),Map.of("warning","partial","response",map(envelope(valid).get("response")))))
            assertThrows(RuntimeException.class,()->FinanceShopeeGmsApi.daily(map(invalid),"10001",List.of("9001"),from,to,today));
    }
    @Test void incompleteTodayIsNotPublishedAsACompleteMultiCampaignSum(){
        var facts=FinanceShopeeGmsApi.daily(envelope(campaign("9001",metric(1,1),metric(2,2)),campaign("9002",metric(1,4))),"10001",List.of("9001","9002"),from,from.plusDays(1),from.plusDays(1));
        assertEquals(1,facts.size());assertEquals(new BigDecimal("5"),facts.getFirst().get("amount"));
    }
    @Test void referenceSubtractsOnceAndRetainsOriginalTotalsAndExplicitZero(){
        var source=new LinkedHashMap<String,Object>(Map.of("source","API_SHOPEE_CPC","amount",new BigDecimal("120"),"currency","PHP","gms_amount",new BigDecimal("20"),"gms_currency","PHP"));
        var row=FinanceShopeeGmsApi.reference(source);
        assertEquals(new BigDecimal("100"),row.get("amount"));assertEquals(new BigDecimal("120"),row.get("all_shop_amount"));
        assertEquals(row.get("amount"),FinanceShopeeGmsApi.reference(row).get("amount"));
        assertEquals(new BigDecimal("120"),source.get("amount"));
        source.put("gms_amount",BigDecimal.ZERO);assertEquals(new BigDecimal("120"),FinanceShopeeGmsApi.reference(source).get("amount"));
        source.put("gms_amount",new BigDecimal("-1"));assertEquals(new BigDecimal("121"),FinanceShopeeGmsApi.reference(source).get("amount"));
    }
    @Test void missingOrDifferentCurrencyGmsCannotFallBackToOldShopTotal(){
        var missing=FinanceShopeeGmsApi.reference(Map.of("source","API_SHOPEE_CPC","amount",new BigDecimal("120"),"currency","PHP"));
        assertNull(missing.get("amount"));assertTrue(text(missing.get("reason_amount")).contains("GMS"));
        var mismatch=FinanceShopeeGmsApi.reference(Map.of("source","API_SHOPEE_CPC","amount",new BigDecimal("120"),"currency","PHP","gms_amount",BigDecimal.ONE,"gms_currency","THB"));
        assertNull(mismatch.get("amount"));
    }
    @Test void campaignQueryNeverTruncatesOrAcceptsDuplicateOrEmptyScopes(){
        for(var ids:List.of(List.<String>of(),List.of("1","1"),List.of("0"),java.util.stream.IntStream.rangeClosed(1,101).mapToObj(Integer::toString).toList()))assertThrows(RuntimeException.class,()->FinanceShopeeGmsApi.query(from,to,ids));
    }
}
