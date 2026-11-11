package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Provider metric meaning and scope, independent of shop attribution and profit postings. */
final class FinanceAdFunding {
    static final List<String> AMOUNTS=List.of("amount","cash_amount","credit_amount","grant_amount","net_amount");
    private FinanceAdFunding() {}
    static void api(Map<String,Object> result,Map<String,Object> raw){
        String source=text(result.get("source")),platform=text(result.get("platform"));
        for(String key:AMOUNTS.subList(1,5))result.put(key,null);
        if("TIKTOK_SHOP".equals(platform)&&source.startsWith("API_")){
            var metrics=map(raw.get("metrics"));boolean gmv="API_GMV".equals(source),account="API_BASIC".equals(source);
            result.put("net_amount",decimal(metrics.get(gmv?"net_cost":"billed_cost")));
            if(account){
                result.put("cash_amount",decimal(metrics.get("cash_spend")));
                result.put("grant_amount",decimal(metrics.get("voucher_spend")));
            }
            result.put("funding_basis",gmv?"GMV Max：cost 为总消耗，net_cost 为剔除广告赠款及优惠券后的净消耗；接口不拆现金与授信。":
                    account?"账户日报：spend 为总消耗，billed_cost 为净消耗；cash_spend、voucher_spend 仅为账户级现金及赠券消耗，不能直接拆到系列或多店。":
                    "系列日报：spend 为总消耗，billed_cost 为净消耗；现金与赠券指标仅支持账户级。");
        }else result.put("funding_basis","SHOPEE".equals(platform)&&source.startsWith("API_")?
                "Shopee 投放报表：expense 为广告消耗；CPC/GMS 报表未拆现金、授信、赠款或净消耗。":"资金报表：现金、授信、赠款按原始分项保留；净消耗仅在现金与授信均明确时汇总。");
        reasons(result);
    }
    static void completeNet(Map<String,Object> row){
        if(!text(row.get("source")).startsWith("API_")&&row.get("net_amount")==null){
            BigDecimal cash=decimal(row.get("cash_amount")),credit=decimal(row.get("credit_amount"));
            if(cash!=null&&credit!=null)row.put("net_amount",cash.add(credit));
        }
        reasons(row);
    }
    static void allocatedNet(Map<String,Object> row){
        // A confirmed total split does not establish how the provider's net cost splits.
        row.put("net_amount",null);completeNet(row);
        if(row.get("net_amount")==null)row.put("reason_net_amount","净消耗未分配");
    }
    static void reasons(Map<String,Object> row){
        String source=text(row.get("source"));boolean api=source.startsWith("API_"),account="API_BASIC".equals(source)&&"TIKTOK_SHOP".equals(row.get("platform"));
        for(String key:AMOUNTS.subList(1,5))if(row.get(key)==null){
            String reason=!api?"报表未提供":"net_amount".equals(key)?"接口未返回净消耗":account&&!"credit_amount".equals(key)?"接口未返回":"接口未拆分";
            row.putIfAbsent("reason_"+key,reason);
        }
    }
}
