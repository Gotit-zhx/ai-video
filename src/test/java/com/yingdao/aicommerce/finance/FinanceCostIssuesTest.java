package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceCostIssuesTest {
    private Map<String,Object> call(String method,String value) {
        return method.equals("describe")?FinanceCostIssues.describe(value):FinanceCostIssues.of(value);
    }
    @Test void existingPendingReasonsHaveDistinctActionableCategories() {
        var cases=new LinkedHashMap<String,String>();
        cases.put("平台商品行重复且内容不一致","ORDER_LINE_CONFLICT");
        cases.put("缺少有效购买数量","QUANTITY_MISSING");
        cases.put("缺少所选成本口径的业务日期","BUSINESS_DATE_MISSING");
        cases.put("手动成本存在多个成本仓，请配置明确的成本仓","WAREHOUSE_AMBIGUOUS");
        cases.put("请配置成本仓，无法唯一匹配店铺国家","WAREHOUSE_AMBIGUOUS");
        cases.put("关联的手动成本已删除，请重新核对","MANUAL_COST_REMOVED");
        cases.put("店铺分组尚未配置成本资料","COST_SOURCE_MISSING");
        cases.put("商品 SKU 与平台 SPU 均未匹配到唯一成本型号，请人工确认","COST_MATCH_MISSING");
        cases.put("成本表缺少 菲律宾 的 001A 成本，不能自动使用其他国家成本；请人工确认","WAREHOUSE_COST_MISSING");
        cases.put("人工关联缺少有效换算数量","CONVERSION_MISSING");
        cases.put("缺少 2026-11 可用成本价","MONTH_PRICE_MISSING");
        cases.put("成本表为零值，需核对单价","ZERO_PRICE");
        cases.put("成本金额超出支持范围","COST_AMOUNT_OVERFLOW");
        cases.put("订单成本合计超出支持范围","COST_AMOUNT_OVERFLOW");
        cases.put("平台尚未返回订单商品明细","ORDER_LINES_MISSING");
        cases.put("关联订单尚未同步","ORDER_NOT_SYNCED");
        cases.put("等待后台计算成本","CALCULATION_QUEUED");
        cases.forEach((message,code)->{
            var result=call("describe",message);assertEquals(code,result.get("code"));assertEquals(message,result.get("message"));
            assertFalse(result.get("source").toString().isBlank());assertFalse(result.get("action").toString().isBlank());
            assertEquals(Set.of("code","message","source","action"),result.keySet());
        });
    }
    @Test void unknownIssuePreservesOriginalMessageWithoutInventingItsCause() {
        String unknown="未知任务错误，订单 123";var result=call("describe",unknown);
        assertEquals("UNKNOWN",result.get("code"));assertEquals(unknown,result.get("message"));
        assertEquals("未知",result.get("source"));assertEquals("UNKNOWN",call("describe",null).get("code"));
        assertEquals("UNKNOWN",call("of","not-a-real-code").get("code"));
    }
    @Test void overrideAndMissingBaselineHaveActionableMetadata() {
        for(String code:List.of("PRICE_OVERRIDE","PRICE_BASELINE_MISSING")) {
            var result=FinanceCostIssues.of(code);assertEquals(code,result.get("code"));
            assertFalse(result.get("source").toString().isBlank());assertFalse(result.get("action").toString().isBlank());
        }
    }
    @Test void knownValidationMetadataCanBeUsedWithoutParsingMessages() {
        var issue=call("of","DUPLICATE_PRICE_CONFLICT");assertEquals("DUPLICATE_PRICE_CONFLICT",issue.get("code"));
        assertFalse(issue.get("message").toString().isBlank());assertFalse(issue.get("action").toString().isBlank());
        var other=call("of","DUPLICATE_PRICE_CONFLICT");assertEquals(issue,other);
    }
}
