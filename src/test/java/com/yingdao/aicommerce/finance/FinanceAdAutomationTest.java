package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceAdAutomationTest {
    @Test void advertiserReportRequestsDocumentedCashVoucherAndNetCostAtAdvertiserDayLevel(){
        var repo=org.mockito.Mockito.mock(FinanceRepository.class);org.mockito.Mockito.when(repo.write(org.mockito.ArgumentMatchers.any())).thenAnswer(i->i.getArgument(0).toString());
        var ads=new FinanceAds(repo,null,(com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp)null,null);
        try{var query=new FinanceAdAutomation(ads).query("100001","BASIC","",LocalDate.of(2026,10,1),LocalDate.of(2026,10,7),1);
            assertEquals("AUCTION_ADVERTISER",query.get("data_level"));assertEquals("[stat_time_day]",query.get("dimensions"));
            for(String name:List.of("billed_cost","cash_spend","voucher_spend"))assertTrue(query.get("metrics").toString().contains(name),name);
            assertFalse(query.get("metrics").toString().contains("spend_credits"));
        }finally{ads.stop();}
    }
    @Test void incompletePagesAndMalformedListsCannotBeAcceptedAsZero(){
        assertThrows(RuntimeException.class,()->FinanceAdAutomation.pages(Map.of(),1,0));
        assertThrows(RuntimeException.class,()->FinanceAdAutomation.pages(Map.of("page_info",Map.of("total_page",2)),1,0));
        assertThrows(RuntimeException.class,()->FinanceAdAutomation.pages(Map.of("page_info",Map.of("total_page",1,"page",2)),1,1));
        assertThrows(RuntimeException.class,()->FinanceAdAutomation.requiredRows(Map.of("list",List.of("invalid")),"list"));
        assertEquals(1,FinanceAdAutomation.pages(Map.of("page_info",Map.of("total_page",0)),1,0));
    }
    @Test void anEmptyReportMustStillHaveAConsistentPageAndNumericTotal(){
        assertThrows(RuntimeException.class,()->FinanceAdAutomation.pages(Map.of("page_info",Map.of("total_page",0,"page",2,"total_number",0)),1,0));
        assertThrows(RuntimeException.class,()->FinanceAdAutomation.pages(Map.of("page_info",Map.of("total_page",0,"page",1,"total_number","unknown")),1,0));
    }
    @Test void reportIdentityIncludesTheCampaignAndDatesMustStayInsideTheRequestedWindow(){
        var day=LocalDate.of(2026,11,1);var row=Map.<String,Object>of("dimensions",Map.of("stat_time_day","2026-11-01 00:00:00","campaign_id","100001"),"metrics",Map.of("cost","1"));
        FinanceAdAutomation.validateRow(row,"GMV",day,day.plusDays(6));
        assertThrows(RuntimeException.class,()->FinanceAdAutomation.validateRow(row,"GMV",day.plusDays(1),day.plusDays(6)));
        assertThrows(RuntimeException.class,()->FinanceAdAutomation.validateRow(Map.of("dimensions",Map.of("stat_time_day","2026-11-01"),"metrics",Map.of("cost","1")),"GMV",day,day));
        assertNotEquals(FinanceAdAutomation.rowKey(row,"GMV"),FinanceAdAutomation.rowKey(Map.of("dimensions",Map.of("stat_time_day","2026-11-01","campaign_id","100002")),"GMV"));
    }
    @Test void exportedAttributionKeepsLongIdentifiersAndOnlyWhitelistedFields(){
        var row=new HashMap<String,Object>();row.put("kind","AD_GMV");row.put("platform","TIKTOK_SHOP");row.put("source","API");
        FinanceFields.enrich(row,Map.of("_finance_store_id","1234567890123456789","_finance_channel","GMV",
                "dimensions",Map.of("campaign_id","9876543210123456789"),"metrics",Map.of("net_cost","12.34"),"access_token","unit-only-secret"));
        assertEquals("1234567890123456789",row.get("ad_store_id"));assertEquals("9876543210123456789",row.get("ad_campaign_id"));
        assertEquals(new java.math.BigDecimal("12.34"),row.get("net_cost"));assertFalse(row.containsKey("access_token"));
    }
}
