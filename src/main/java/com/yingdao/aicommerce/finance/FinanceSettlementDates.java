package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Completion attribution, distinct from statement generation and order lifecycle dates. */
final class FinanceSettlementDates {
    static final String PENDING="DATE_PENDING";
    static final String SOURCE="TikTok 已完成结算记录的 payment_time（PAID / SETTLED，店铺时区）；不是下单、订单更新时间或账单生成日前一天，也不代表银行实际到账";
    static boolean completed(Map<String,Object> raw){
        return Set.of("PAID","SETTLED").contains(first(raw,"_finance_payment_status","payment_status"))
                && number(raw.containsKey("_finance_payment_time")?raw.get("_finance_payment_time"):raw.get("payment_time"))>0;
    }
    static LocalDate day(Map<String,Object> raw,String zone){
        if(!completed(raw))throw bad("平台尚未返回已完成结算的有效日期，未使用下单、更新时间或账单生成时间代替");
        return FinanceMarketplace.settlementDay(raw.containsKey("_finance_payment_time")?raw.get("_finance_payment_time"):raw.get("payment_time"),zone);
    }
    static Map<String,Object> metadata(Map<String,Object> bill,String zone){
        var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("statement_time","payment_time","payment_status","payment_id"))
            if(bill.containsKey(field))result.put("_finance_"+field,bill.get(field));
        result.put("_finance_date_version",2);result.put("_finance_timezone",zone);
        result.put("_finance_date_source",completed(bill)?SOURCE:"平台结算日期待确认，暂不计入按结算日期筛选的统计");
        return result;
    }
}
