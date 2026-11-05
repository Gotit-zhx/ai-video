package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Distinct Seller Center report calendars; neither is a bank-arrival date. */
final class FinanceSettlementDates {
    static final String PENDING="DATE_PENDING";
    static final int VERSION=4;
    static final String LOCAL="LOCAL_BALANCE";
    static final String CROSS_BORDER="CROSS_BORDER_STATEMENT";
    static final String SOURCE="TikTok 跨境店 Statement Date：statement_time 的 UTC 日期次日；不代表银行到账时间";
    static final String LOCAL_SOURCE="TikTok 本土店余额账单：payment_time 按店铺当地日期归属，表示款项计入卖家余额；不代表银行到账时间";
    static boolean completed(Map<String,Object> raw){
        return Set.of("PAID","SETTLED").contains(first(raw,"_finance_payment_status","payment_status"))
                && number(raw.containsKey("_finance_payment_time")?raw.get("_finance_payment_time"):raw.get("payment_time"))>0;
    }
    static LocalDate day(Map<String,Object> raw,String zone){
        return day(raw,zone,text(raw.get("_finance_seller_type")));
    }
    static LocalDate day(Map<String,Object> raw,String zone,String sellerType){
        if(!completed(raw))throw bad("平台尚未确认账单已完成结算，暂不计入已结算统计");
        if(LOCAL.equals(basis(raw,sellerType))){
            long payment=number(raw.containsKey("_finance_payment_time")?raw.get("_finance_payment_time"):raw.get("payment_time"));
            return Instant.ofEpochSecond(payment).atZone(ZoneId.of(zone)).toLocalDate();
        }
        long statement=number(raw.containsKey("_finance_statement_time")?raw.get("_finance_statement_time"):raw.get("statement_time"));
        if(statement<=0)throw bad("平台尚未返回有效账单时间，未使用下单日或支付时间替代账单日期");
        // This is an explicit report-calendar convention, not a timezone correction.
        // PH1, PH2 and TH2: all 90 September daily revenue/net totals match exactly.
        return Instant.ofEpochSecond(statement).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
    }
    static Map<String,Object> metadata(Map<String,Object> bill,String zone){
        return metadata(bill,zone,text(bill.get("_finance_seller_type")));
    }
    static Map<String,Object> metadata(Map<String,Object> bill,String zone,String sellerType){
        var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("statement_time","payment_time","payment_status","payment_id")){
            Object value=bill.containsKey("_finance_"+field)?bill.get("_finance_"+field):bill.get(field);
            if(value!=null)result.put("_finance_"+field,value);
        }
        result.put("_finance_date_version",VERSION);result.put("_finance_timezone",zone);
        result.put("_finance_date_basis",basis(bill,sellerType));
        if(!text(sellerType).isBlank())result.put("_finance_seller_type",sellerType);
        if(completed(bill))result.put("_finance_export_statement_date",day(bill,zone,sellerType).toString());
        result.put("_finance_date_source",completed(bill)?source(result):"平台结算日期待确认，暂不计入按结算日期筛选的统计");
        return result;
    }
    static String basis(Map<String,Object> raw,String sellerType){
        // Official authorization type, not a shop name/ID or a total-matching exception.
        if("LOCAL".equals(sellerType))return LOCAL;
        if("CROSS_BORDER".equals(sellerType))return CROSS_BORDER;
        return LOCAL.equals(raw.get("_finance_date_basis"))?LOCAL:CROSS_BORDER;
    }
    static String source(Map<String,Object> raw){
        return LOCAL.equals(basis(raw,text(raw.get("_finance_seller_type"))))?LOCAL_SOURCE:SOURCE;
    }
    static boolean exportBasis(Map<String,Object> raw){
        // Existing version-3 cross-border evidence still supersedes legacy month overrides.
        return number(raw.get("_finance_date_version"))>=3&&!text(raw.get("_finance_export_statement_date")).isBlank();
    }
}
