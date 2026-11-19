package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Official Ads shop-level CPC daily report. No inferred funding split or GMS addition. */
final class FinanceShopeeAdsApi {
    static final String PATH="/api/v2/ads/get_all_cpc_ads_daily_performance";
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT);
    private FinanceShopeeAdsApi() {}
    static Map<String,Object> query(LocalDate from,LocalDate to){
        if(from==null||to==null||!to.isAfter(from)||ChronoUnit.DAYS.between(from,to)>29)throw bad("Shopee 广告日报日期范围无效");
        return Map.of("start_date",DATE.format(from),"end_date",DATE.format(to));
    }
    static List<Map<String,Object>> daily(Map<String,Object> envelope,LocalDate from,LocalDate to){
        query(from,to);
        if(!text(envelope.get("error")).isBlank()||!text(envelope.get("warning")).isBlank())throw bad("Shopee 广告报表返回错误或不完整提示，已保留原消耗");
        Object response=envelope.get("response");
        if(!(response instanceof List<?> values)||values.size()>30)throw bad("Shopee 广告日报响应不完整");
        var seen=new HashSet<LocalDate>();var result=new ArrayList<Map<String,Object>>();
        for(Object item:values){
            if(!(item instanceof Map<?,?>))throw bad("Shopee 广告日报记录无效");
            var raw=map(item);LocalDate date;
            try{date=LocalDate.parse(text(raw.get("date")),DATE);}catch(RuntimeException invalid){throw bad("Shopee 广告日报缺少有效业务日");}
            if(date.isBefore(from)||date.isAfter(to)||!seen.add(date))throw bad("Shopee 广告日报日期越界或重复");
            Object expense=raw.get("expense");
            if(!(expense instanceof Number)&&!(expense instanceof String))throw bad("Shopee 广告日报缺少消耗金额");
            BigDecimal amount=decimal(expense);if(amount!=null)amount=amount.stripTrailingZeros();
            if(amount==null||amount.scale()>6||amount.precision()-amount.scale()>18)throw bad("Shopee 广告日报金额格式或精度无效");
            var row=new LinkedHashMap<String,Object>();row.put("record_date",date.toString());row.put("amount",amount);row.put("raw",raw);result.add(row);
        }
        return result;
    }
    static void requireClosedDays(List<Map<String,Object>> facts,LocalDate from,LocalDate to,LocalDate today){
        LocalDate closedEnd=to.isBefore(today)?to:today.minusDays(1);
        var returned=new HashSet<String>();facts.forEach(row->returned.add(text(row.get("record_date"))));
        var missing=new ArrayList<LocalDate>();
        for(LocalDate day=from;!day.isAfter(closedEnd);day=day.plusDays(1))if(!returned.contains(day.toString()))missing.add(day);
        if(!missing.isEmpty())throw new IncompleteReport(missing);
    }
    static final class IncompleteReport extends IllegalArgumentException {
        IncompleteReport(List<LocalDate> missing){
            super("Shopee 日报缺少 "+missing.size()+" 个已结束日期（"+missing.stream().map(LocalDate::toString).reduce((a,b)->a+"、"+b).orElse("")+"）；本窗口未发布，保留原数据和检查点等待重试");
        }
    }
    static Map<String,Object> plan(LocalDate today,LocalDate lastSuccess){
        var plan=new LinkedHashMap<String,Object>();LocalDate oldest=start(today,lastSuccess);
        if(lastSuccess==null){
            LocalDate month=today.withDayOfMonth(1),previous=month.minusMonths(1);
            plan.put("from",previous.toString());plan.put("until",month.minusDays(1).toString());
            var remaining=new ArrayList<Map<String,Object>>();remaining.add(Map.of("from",month.toString(),"until",today.toString()));
            if(oldest.isBefore(previous))remaining.add(Map.of("from",oldest.toString(),"until",previous.minusDays(1).toString()));
            plan.put("remaining",remaining);
        }else{plan.put("from",oldest.toString());plan.put("until",today.toString());}
        return plan;
    }
    static LocalDate windowEnd(LocalDate from,LocalDate until){
        // Shopee's one-month limit is 30 inclusive days, including in 31-day months.
        LocalDate end=from.withDayOfMonth(from.lengthOfMonth());
        if(from.plusDays(29).isBefore(end))end=from.plusDays(29);
        return end.isBefore(until)?end:until;
    }
    static LocalDate start(LocalDate today,LocalDate lastSuccess){return today.minusDays(lastSuccess==null?89:lastSuccess.isBefore(today)?30:6);}
}
