package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Financial funding reports, independent of API credentials and legacy profit postings. */
final class FinanceAdSpendImport {
    private FinanceAdSpendImport() {}
    static List<Map<String,Object>> parse(List<List<String>> table,String platform,String accountId,String accountName,boolean debitNegative){
        if(!Set.of("TIKTOK_SHOP","SHOPEE").contains(platform))throw bad("请选择 TikTok 或 Shopee");
        if(accountName==null||accountName.isBlank()||accountName.length()>250)throw bad("请填写广告账户或独立报表来源名称");
        accountId=text(accountId).trim();if(accountId.isBlank()||!accountId.matches("[A-Za-z0-9_-]{1,64}"))throw bad("请填写来源平台 ID：TK 广告账户或 Shopee 店铺 ID，用于核对 API 与导入重叠");
        if(table.size()<2||table.size()>20001)throw bad("报表须包含 1 至 20,000 行明细");
        var h=new HashMap<String,Integer>();for(int i=0;i<table.getFirst().size();i++)h.put(table.getFirst().get(i).trim().toLowerCase(Locale.ROOT),i);
        var result=new ArrayList<Map<String,Object>>();var keys=new HashMap<String,Map<String,Object>>();
        for(int i=1;i<table.size();i++){
            var values=table.get(i);if(values.stream().allMatch(String::isBlank))continue;
            var r=new LinkedHashMap<String,Object>();String day=column(values,h,"日期","date","stat_time_day"),currency=column(values,h,"币种","currency").toUpperCase(Locale.ROOT);
            try{LocalDate.parse(day);}catch(Exception e){throw bad("第 "+(i+1)+" 行缺少 YYYY-MM-DD 日期");}
            if(!currency.matches("[A-Z]{3}"))throw bad("第 "+(i+1)+" 行币种无效");
            String campaign=column(values,h,"推广系列id","广告系列id","广告id","campaign_id","ad_id"),name=column(values,h,"系列名称","推广系列名称","广告名称","campaign_name","ad_name");
            if(name.length()>500)throw bad("第 "+(i+1)+" 行推广系列名称过长");
            if(campaign.isBlank()||campaign.length()>120||!campaign.matches("[A-Za-z0-9_:-]+"))throw bad("第 "+(i+1)+" 行缺少完整文本推广系列 ID，不能用名称猜测");
            if(campaign.matches(".*[Ee][+-]\\d+.*"))throw bad("推广系列 ID 已变为科学计数法，请重新导出文本编号");
            r.put("platform",platform);r.put("account_id",accountId);r.put("account_name",accountName.trim());r.put("campaign_id",campaign);r.put("campaign_name",name);r.put("record_date",day);r.put("currency",currency);
            String type=column(values,h,"类型","type","report_type");if(type.length()>80)throw bad("第 "+(i+1)+" 行类型过长");
            r.put("report_type",type);r.put("source","IMPORT_FUNDING");r.put("shop_id","UNASSIGNED");r.put("source_row",i+1);
            String[] fields={"cash_amount","credit_amount","grant_amount","amount"};String[][] aliases={{"现金消耗","cash_amount"},{"信用额度消耗","credit_amount"},{"广告费用赠款消耗","广告赠款消耗","grant_amount"},{"金额","消耗","花费","expense","amount","spend","cost"}};
            var original=new LinkedHashMap<String,Object>();
            for(int n=0;n<fields.length;n++){
                String value=column(values,h,aliases[n]);BigDecimal amount=value.isBlank()?null:decimal(value.replace(",",""));
                if(!value.isBlank()&&(amount==null||amount.precision()>24||amount.scale()>6))throw bad("第 "+(i+1)+" 行金额格式或精度无效");
                original.put(fields[n],amount);r.put(fields[n],amount==null?null:debitNegative?amount.negate():amount);
            }
            if(r.get("amount")==null)throw bad("第 "+(i+1)+" 行缺少消耗合计");
            FinanceAdSpendReport.validateFunding(r);
            r.put("source_values",original);r.put("source_key",hash(String.join("\0",platform,accountId,day,currency,campaign)));
            var old=keys.putIfAbsent(text(r.get("source_key")),r);
            if(old!=null){if(!FinanceAdSpendReport.fingerprint(old).equals(FinanceAdSpendReport.fingerprint(r)))throw bad("第 "+(i+1)+" 行与同日同系列记录冲突，整份报表未发布");continue;}
            result.add(r);
        }
        if(result.isEmpty())throw bad("报表没有有效消耗记录");return result;
    }
    static String column(List<String> values,Map<String,Integer> h,String...names){
        for(String name:names){Integer i=h.get(name);if(i!=null&&i<values.size())return values.get(i).trim();}return "";
    }
}
