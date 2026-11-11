package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceAdBcApiTest {
    final LocalDate day=LocalDate.of(2026,10,1);
    Map<String,Object> cost(String advertiser,Object total){
        var row=new LinkedHashMap<String,Object>();row.put("advertiser_id",advertiser);row.put("advertiser_name","Account");
        row.put("currency","PHP");row.put("amount",total);return row;
    }
    @Test void requestsOnlyDocumentedBcAndSingleAccountDayFilters(){
        assertEquals(Map.of("page",1,"page_size",50),FinanceAdBcApi.discoveryQuery(1));
        var query=FinanceAdBcApi.costQuery("700001",day,2);
        assertEquals("700001",query.get("bc_id"));assertEquals(2,query.get("page"));assertEquals(50,query.get("page_size"));
        assertEquals("{\"start_date\":\"2026-10-01\",\"end_date\":\"2026-10-01\"}",query.get("filtering"));
        assertFalse(query.toString().contains("keyword"));assertFalse(query.containsKey("app_id"));
    }
    @Test void normalizesNegativeConsumptionAndRefundsInCurrencyMainUnits(){
        var row=cost("100001","-1376616");row.put("cash_amount","-1376616");row.put("grant_amount",0);row.put("tax_amount","-68831");
        var facts=FinanceAdBcApi.costs(Map.of("cost_list",List.of(row,cost("100002","12.34"))),day,Set.of("100001","100002"));
        assertEquals(2,facts.size());var consumed=facts.getFirst();
        assertEquals(decimal("1376616"),consumed.get("amount"));assertEquals(decimal("1376616"),consumed.get("cash_amount"));
        assertEquals(decimal("68831"),consumed.get("tax_amount"));assertEquals(decimal("0"),consumed.get("grant_amount"));
        assertEquals(decimal("-12.34"),facts.getLast().get("amount"));assertEquals("2026-10-01",consumed.get("record_date"));
        assertEquals("API_BC_COST",consumed.get("source"));assertEquals("UNASSIGNED",consumed.get("shop_id"));
        assertNull(consumed.get("credit_amount"));assertNull(consumed.get("net_amount"));
    }
    @Test void preservesMissingFundingAndIgnoresBcSummaryAndUnconnectedAccounts(){
        var facts=FinanceAdBcApi.costs(Map.of("cost_list",List.of(cost("100001","-7"),cost("999999","-200")),
                "transaction_summary",Map.of("amount","-207","cash_amount","-207")),day,Set.of("100001"));
        assertEquals(1,facts.size());var row=facts.getFirst();
        assertEquals(decimal("7"),row.get("amount"));assertNull(row.get("cash_amount"));assertNull(row.get("grant_amount"));assertNull(row.get("tax_amount"));
    }
    @Test void rejectsMalformedMissingAndDuplicateCostFacts(){
        assertThrows(RuntimeException.class,()->FinanceAdBcApi.costs(Map.of(),day,Set.of("100001")));
        assertThrows(RuntimeException.class,()->FinanceAdBcApi.costs(Map.of("cost_list",List.of("invalid")),day,Set.of("100001")));
        var row=cost("100001","NaN");assertThrows(RuntimeException.class,()->FinanceAdBcApi.costs(Map.of("cost_list",List.of(row)),day,Set.of("100001")));
        row.put("amount",Map.of("amount","-1"));assertThrows(RuntimeException.class,()->FinanceAdBcApi.costs(Map.of("cost_list",List.of(row)),day,Set.of("100001")));
        row.put("amount","-1000000000000000000");assertThrows(RuntimeException.class,()->FinanceAdBcApi.costs(Map.of("cost_list",List.of(row)),day,Set.of("100001")));
        row.put("amount","-1");row.put("cash_amount","unknown");assertThrows(RuntimeException.class,()->FinanceAdBcApi.costs(Map.of("cost_list",List.of(row)),day,Set.of("100001")));
        assertThrows(RuntimeException.class,()->FinanceAdBcApi.costs(Map.of("cost_list",List.of(cost("100001","-1"),cost("100001","-1"))),day,Set.of("100001")));
    }
    @Test void acceptsOnlyExplicitFinanceRolesAndExactBcIdentifiers(){
        var result=FinanceAdBcApi.businessCenters(Map.of("list",List.of(
                Map.of("bc_info",Map.of("bc_id","700001"),"ext_user_role",Map.of("finance_role","MANAGER")),
                Map.of("bc_info",Map.of("bc_id","700002"),"user_role","ADMIN"))));
        assertEquals(2,result.size());assertEquals("MANAGER",result.getFirst().get("finance_role"));assertEquals("",result.getLast().get("finance_role"));
        assertThrows(RuntimeException.class,()->FinanceAdBcApi.businessCenters(Map.of("list",List.of(Map.of("bc_info",Map.of("bc_id","same name"))))));
    }
    @Test void identityIsIndependentOfBcNamesAndCredentials(){
        var first=FinanceAdBcApi.costs(Map.of("cost_list",List.of(cost("100001","-1"))),day,Set.of("100001"));
        var second=cost("100001","-2");second.put("advertiser_name","Renamed");
        var last=FinanceAdBcApi.costs(Map.of("cost_list",List.of(second)),day,Set.of("100001"));
        assertEquals(1,first.size());assertEquals(1,last.size());assertEquals(first.getFirst().get("source_key"),last.getFirst().get("source_key"));
    }
    @Test void fundingCompletionPreservesMoreSpecificProviderMissingReasons(){
        var row=new LinkedHashMap<String,Object>();row.put("source","API_BC_COST");row.put("platform","TIKTOK_SHOP");
        row.put("reason_credit_amount","接口未提供授信消耗");row.put("reason_net_amount","接口未提供净额");
        FinanceAdFunding.completeNet(row);
        assertEquals("接口未提供授信消耗",row.get("reason_credit_amount"));assertEquals("接口未提供净额",row.get("reason_net_amount"));
        assertNull(row.get("credit_amount"));assertNull(row.get("net_amount"));
    }
}
