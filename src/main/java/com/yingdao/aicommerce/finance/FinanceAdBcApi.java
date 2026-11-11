package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Public v1.3 BC costs (doc 1829079287639041), in the account currency's main units. */
final class FinanceAdBcApi {
    private FinanceAdBcApi() {}
    static Map<String,Object> discoveryQuery(int page){
        checkPage(page);return Map.of("page",page,"page_size",50);
    }
    static Map<String,Object> costQuery(String bc,LocalDate day,int page){
        checkPage(page);if(!text(bc).matches("\\d{5,30}")||day==null)throw bad("BC 资金查询标识或日期无效");
        return Map.of("bc_id",bc,"filtering","{\"start_date\":\""+day+"\",\"end_date\":\""+day+"\"}","page",page,"page_size",50);
    }
    static List<Map<String,Object>> businessCenters(Map<String,Object> data){
        var result=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();
        for(var row:FinanceAdAutomation.requiredRows(data,"list")){
            String id=text(at(row,"bc_info.bc_id"));
            if(!id.matches("\\d{5,30}")||!ids.add(id))throw bad("BC 接口返回无效或重复标识");
            result.add(Map.of("bc_id",id,"finance_role",text(at(row,"ext_user_role.finance_role"))));
        }
        return result;
    }
    static List<Map<String,Object>> costs(Map<String,Object> data,LocalDate day,Set<String> advertisers){
        var result=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();
        for(var raw:FinanceAdAutomation.requiredRows(data,"cost_list")){
            String advertiser=text(raw.get("advertiser_id"));
            if(!advertiser.matches("\\d{5,30}"))throw bad("BC 费用返回了无效账户标识");
            if(!advertisers.contains(advertiser))continue;
            if(!ids.add(advertiser))throw bad("BC 费用返回了重复账户");
            String currency=text(raw.get("currency"));if(!currency.matches("[A-Z]{3}"))throw bad("BC 费用没有返回有效币种");
            var row=new LinkedHashMap<String,Object>();
            row.put("source_key",hash("tiktok-bc-cost\0"+advertiser+"\0"+day+"\0"+currency));
            row.put("source","API_BC_COST");row.put("platform","TIKTOK_SHOP");row.put("shop_id","UNASSIGNED");
            row.put("account_id",advertiser);row.put("account_name",bounded(raw.get("advertiser_name"),250));
            row.put("campaign_id","");row.put("campaign_name","");row.put("record_date",day.toString());row.put("currency",currency);
            // The official finance statement uses negative deductions. A positive refund remains a negative expense.
            row.put("amount",money(raw,"amount",true));
            for(String key:List.of("cash_amount","grant_amount","tax_amount"))row.put(key,money(raw,key,false));
            row.put("credit_amount",null);row.put("net_amount",null);
            row.put("funding_basis","BC 账户日财务费用：负数扣费转为正数消耗，退款保留冲减；现金、赠款和税额按接口原币金额保留。未拆授信或系列，税额单独核对，不与投放报表叠加。");
            row.put("reason_credit_amount","接口未拆分");row.put("reason_net_amount","接口未返回净消耗");
            for(String key:List.of("cash_amount","grant_amount","tax_amount"))if(row.get(key)==null)row.put("reason_"+key,"接口未返回");
            result.add(row);
        }
        return result;
    }
    private static BigDecimal money(Map<String,Object> row,String key,boolean required){
        Object raw=row.get(key);if(raw==null){if(required)throw bad("BC 费用缺少消耗金额");return null;}
        if(!(raw instanceof Number)&&!(raw instanceof String))throw bad("BC 费用金额必须为数值");
        BigDecimal value=decimal(raw);if(value!=null)value=value.stripTrailingZeros();
        if(value==null||value.scale()>6||value.precision()-value.scale()>18)throw bad("BC 费用金额格式或精度无效");
        return value.negate();
    }
    private static void checkPage(int page){if(page<1||page>1000)throw bad("BC 资金页数超出安全范围");}
    private static String bounded(Object value,int length){String name=text(value);return name.substring(0,Math.min(length,name.length()));}
}
