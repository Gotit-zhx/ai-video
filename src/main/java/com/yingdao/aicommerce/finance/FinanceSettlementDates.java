package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Seller Center statement-report calendar verified against the supplied September workbook. */
final class FinanceSettlementDates {
    static final String PENDING="DATE_PENDING";
    static final int VERSION=3;
    static final String SOURCE="TikTok 账单日期（Excel 报表口径）：statement_time 的 UTC 日期次日；已与官方导出逐日核对，不代表订单完成时间或银行到账时间";
    static boolean completed(Map<String,Object> raw){
        return Set.of("PAID","SETTLED").contains(first(raw,"_finance_payment_status","payment_status"))
                && number(raw.containsKey("_finance_payment_time")?raw.get("_finance_payment_time"):raw.get("payment_time"))>0;
    }
    static LocalDate day(Map<String,Object> raw,String zone){
        if(!completed(raw))throw bad("平台尚未确认账单已完成结算，暂不计入已结算统计");
        long statement=number(raw.containsKey("_finance_statement_time")?raw.get("_finance_statement_time"):raw.get("statement_time"));
        if(statement<=0)throw bad("平台尚未返回有效账单时间，未使用下单日或支付时间替代账单日期");
        // This is an explicit report-calendar convention, not a timezone correction.
        // PH1, PH2 and TH2: all 90 September daily revenue/net totals match exactly.
        return Instant.ofEpochSecond(statement).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
    }
    static Map<String,Object> metadata(Map<String,Object> bill,String zone){
        var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("statement_time","payment_time","payment_status","payment_id")){
            Object value=bill.containsKey("_finance_"+field)?bill.get("_finance_"+field):bill.get(field);
            if(value!=null)result.put("_finance_"+field,value);
        }
        result.put("_finance_date_version",VERSION);result.put("_finance_timezone",zone);
        if(completed(bill))result.put("_finance_export_statement_date",day(bill,zone).toString());
        result.put("_finance_date_source",completed(bill)?SOURCE:"平台结算日期待确认，暂不计入按结算日期筛选的统计");
        return result;
    }
    static boolean exportBasis(Map<String,Object> raw){
        return number(raw.get("_finance_date_version"))>=VERSION&&!text(raw.get("_finance_export_statement_date")).isBlank();
    }
}
