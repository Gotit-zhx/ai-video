package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only GMS identification and same-grain expense; no inferred cash or billing transactions. */
final class FinanceShopeeGmsApi {
    static final String DISCOVERY="/api/v2/ads/get_gms_campaign_performance";
    static final String DAILY="/api/v2/ads/get_product_campaign_daily_performance";
    static final String BASIS="SHOPEE_CPC_MINUS_IDENTIFIED_GMS_V1";
    static final String LABEL="广告消耗（账单参考）";
    static final String DESCRIPTION="Shopee 账单参考金额 = 全店 CPC 日报消耗 − 已识别 GMS 同日消耗；不是原始扣款流水，缺失日期和金额不补零。";
    static final List<String> AUXILIARY=List.of("all_shop_amount","gms_amount");
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT);
    private FinanceShopeeGmsApi() {}

    static String campaign(Map<String,Object> envelope){
        complete(envelope);var response=map(envelope.get("response"));String id=text(response.get("campaign_id"));
        if(!id.matches("[1-9]\\d{0,29}"))throw bad("GMS 报表没有提供有效计划，不能将未识别的 GMS 当作零费用");
        expense(map(response.get("report")).get("expense"));return id;
    }
    static Map<String,Object> query(LocalDate from,LocalDate to,List<String> ids){
        if(ids.isEmpty()||ids.size()>100||new HashSet<>(ids).size()!=ids.size()||ids.stream().anyMatch(id->!id.matches("[1-9]\\d{0,29}")))throw bad("GMS 计划范围无效");
        var result=new LinkedHashMap<>(FinanceShopeeAdsApi.query(from,to));result.put("campaign_id_list",String.join(",",ids));return result;
    }
    static List<Map<String,Object>> daily(Map<String,Object> envelope,String shop,List<String> ids,LocalDate from,LocalDate to,LocalDate today){
        query(from,to,ids);complete(envelope);Object value=envelope.get("response");
        if(value instanceof List<?> legacy){if(legacy.size()!=1)throw bad("GMS 日报店铺范围不完整");value=legacy.getFirst();}
        var response=map(value);if(!shop.equals(text(response.get("shop_id"))))throw bad("GMS 日报店铺不匹配");
        if(!(response.get("campaign_list") instanceof List<?> campaigns))throw bad("GMS 日报计划范围不完整");
        var expected=new HashSet<>(ids);var returned=new HashSet<String>();
        var totals=new TreeMap<LocalDate,BigDecimal>();var counts=new HashMap<LocalDate,Integer>();
        var evidence=new HashMap<LocalDate,List<Map<String,Object>>>();
        for(Object item:campaigns){
            var campaign=map(item);String id=text(campaign.get("campaign_id"));
            if(!expected.contains(id)||!returned.add(id))throw bad("GMS 日报计划越界或重复");
            if(!(campaign.get("metrics_list") instanceof List<?> metrics)||metrics.size()>30)throw bad("GMS 日报日期范围不完整");
            var dates=new HashSet<LocalDate>();var facts=new ArrayList<Map<String,Object>>();
            for(Object metric:metrics){
                if(!(metric instanceof Map<?,?>))throw bad("GMS 日报记录无效");var raw=map(metric);LocalDate day;
                try{day=LocalDate.parse(text(raw.get("date")),DATE);}catch(RuntimeException invalid){throw bad("GMS 日报缺少有效业务日");}
                if(day.isBefore(from)||day.isAfter(to)||!dates.add(day))throw bad("GMS 日报日期越界或重复");
                BigDecimal amount=expense(raw.get("expense"));totals.merge(day,amount,BigDecimal::add);counts.merge(day,1,Integer::sum);
                evidence.computeIfAbsent(day,k->new ArrayList<>()).add(Map.of("campaign_id",id,"metrics",raw));
                facts.add(Map.of("record_date",day.toString()));
            }
            FinanceShopeeAdsApi.requireClosedDays(facts,from,to,today);
        }
        if(!returned.equals(expected))throw bad("GMS 日报没有完整返回已识别计划，保留原数据和窗口");
        var result=new ArrayList<Map<String,Object>>();
        totals.forEach((day,amount)->{
            // A partial current day must not become a complete sum merely because another campaign returned it.
            if(counts.get(day)==ids.size())result.add(Map.of("record_date",day.toString(),"amount",amount,"raw",Map.of("campaign_ids",List.copyOf(ids),"campaigns",evidence.get(day))));
        });return result;
    }
    static Map<String,Object> reference(Map<String,Object> source){
        var row=new LinkedHashMap<>(source);
        BigDecimal total=decimal(source.containsKey("all_shop_amount")?source.get("all_shop_amount"):source.get("amount"));
        BigDecimal gms=decimal(source.get("gms_amount"));boolean currency=text(source.get("currency")).equals(text(source.get("gms_currency")));
        row.put("all_shop_amount",total);row.put("gms_amount",currency?gms:null);row.put("reference_basis",BASIS);
        row.put("funding_basis",DESCRIPTION);
        if(total==null)row.put("reason_all_shop_amount","全店 CPC 日报待补齐");else row.remove("reason_all_shop_amount");
        if(gms==null||!currency)row.put("reason_gms_amount",!currency&&gms!=null?"GMS 日报币种不一致":"GMS 日报待补齐");else row.remove("reason_gms_amount");
        if(total==null||gms==null||!currency){row.put("amount",null);row.put("reason_amount",total==null?row.get("reason_all_shop_amount"):row.get("reason_gms_amount"));}
        else{row.put("amount",total.subtract(gms));row.remove("reason_amount");}
        return row;
    }
    private static void complete(Map<String,Object> envelope){
        if(!text(envelope.get("error")).isBlank()||!text(envelope.get("warning")).isBlank())throw bad("GMS 报表返回错误或不完整提示，保留原数据与窗口");
    }
    private static BigDecimal expense(Object value){
        if(!(value instanceof Number)&&!(value instanceof String))throw bad("GMS 报表未提供消耗，不能视为零");
        BigDecimal amount=decimal(value);if(amount!=null)amount=amount.stripTrailingZeros();
        if(amount==null||amount.scale()>6||amount.precision()-amount.scale()>18)throw bad("GMS 报表金额格式或精度无效");return amount;
    }
}
