package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read only the small indexed month-correction relation; preserve covering scans of the ordinary ledger. */
final class FinanceFxDates {
    static final List<String> LEDGER_SUMS=List.of("records","known_amounts","known_revenues","known_impressions","known_clicks","known_conversions",
        "amount","revenue","impressions","clicks","conversions");
    static final List<String> LEDGER_KEYS=List.of("shop_id","record_date","kind","currency","category","status");
    static final List<String> COST_KEYS=List.of("shop_id","record_date","settlement_currency","currency","status","fx_date");
    private FinanceFxDates(){}
    static boolean hasWholeMonth(Filter f){
        LocalDate first=f.start().withDayOfMonth(1);if(f.start().getDayOfMonth()!=1)first=first.plusMonths(1);
        return !first.withDayOfMonth(first.lengthOfMonth()).isAfter(f.end());
    }
    static String trustedDate(String alias){
        return "CASE WHEN CAST(JSON_UNQUOTE(JSON_EXTRACT("+alias+".raw_json,'$._finance_date_version')) AS UNSIGNED)>=3"
            +" THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT("+alias+".raw_json,'$._finance_export_statement_date')),'') END";
    }
    static String correctedFrom(){
        return " FROM finance_reporting_months rm FORCE INDEX(idx_finance_reporting_month)"
            +" STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_marketing_order)"
            +" ON r.organization_id=rm.organization_id AND r.shop_id=rm.shop_id AND r.order_id=rm.order_id AND r.statement_id=rm.statement_id ";
    }
    static String correctionWhere(){
        return " rm.organization_id=? AND rm.reporting_month BETWEEN ? AND ?"
            +" AND JSON_EXTRACT(r.raw_json,'$._finance_reporting_month') IS NOT NULL AND ";
    }
    static List<Object> args(Scope scope,Filter f,FinanceRepository.Query query){
        var result=new ArrayList<Object>(List.of(scope.organizationId(),Date.valueOf(f.start().withDayOfMonth(1)),Date.valueOf(f.end())));
        result.addAll(query.args());return result;
    }
    static List<Map<String,Object>> ledger(FinanceRepository repo,Scope scope,Filter f){
        if(!hasWholeMonth(f))return List.of();
        var q=repo.query(scope,f,"r");String fx=trustedDate("r"),normal=FinanceOrderCharges.normalOrder("r");
        return repo.jdbc().queryForList("SELECT r.shop_id,r.record_date,r.kind,r.currency,"
            +"CASE WHEN r.kind='FEE' THEN r.category ELSE '' END category,'' status,"+fx+" fx_date,"
            +"CASE WHEN "+normal+" THEN 1 ELSE 0 END fx_operating,"
            +"COUNT(*) records,COUNT(r.amount) known_amounts,COUNT(r.revenue) known_revenues,"
            +"COUNT(r.impressions) known_impressions,COUNT(r.clicks) known_clicks,COUNT(r.conversions) known_conversions,"
            +"SUM(r.amount) amount,SUM(r.revenue) revenue,SUM(r.impressions) impressions,SUM(r.clicks) clicks,SUM(r.conversions) conversions"
            +correctedFrom()+" WHERE "+correctionWhere()+q.where()+" AND r.kind IN ('SETTLEMENT','FEE') AND r.source='API'"
            +" GROUP BY r.shop_id,r.record_date,r.kind,r.currency,CASE WHEN r.kind='FEE' THEN r.category ELSE '' END,"+fx+",fx_operating",args(scope,f,q).toArray());
    }
    static List<Map<String,Object>> costs(FinanceRepository repo,Scope scope,Filter f,String kind){
        if(!hasWholeMonth(f))return List.of();
        var q=repo.query(scope,f,"r");String extra="";
        if("SAMPLE_SETTLED".equals(kind))extra=" AND o.platform='TIKTOK_SHOP' AND (JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.order_type'))='SELLER_FUND_FREE_SAMPLE' OR LOWER(JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.is_sample_order')))='true')";
        if("REFUND_SETTLED".equals(kind))extra=" AND EXISTS (SELECT 1 FROM finance_records refund WHERE refund.organization_id=r.organization_id AND refund.shop_id=r.shop_id AND refund.order_id=r.order_id AND refund.kind='REFUND' AND refund.source='API')";
        String costDate=trustedDate("r");
        return repo.jdbc().queryForList("SELECT r.shop_id,r.record_date,o.record_date fx_date,r.currency settlement_currency,o.currency,'ORDER' kind,'' category,"
            +"o.status,"+costDate+" cost_fx_date,COUNT(o.id) records,COUNT(o.amount) known_amounts,COUNT(o.revenue) known_revenues,"
            +"SUM(o.amount) amount,SUM(o.revenue) revenue,SUM(o.impressions) impressions,SUM(o.clicks) clicks,SUM(o.conversions) conversions,"
            +FinanceCostReport.AGGREGATE.replace("x.order_record_id","o.id")+correctedFrom()
            +" LEFT JOIN finance_records o FORCE INDEX(idx_finance_settled_order) ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id AND o.order_id=r.order_id AND o.kind='ORDER' AND o.source='API'"
            +" LEFT JOIN finance_order_costs c ON c.order_record_id=o.id LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=o.id"
            +FinanceLabelCosts.join("r.organization_id","r.shop_id","r.order_id")+" WHERE "+correctionWhere()+q.where()
            +" AND "+FinanceCostReport.firstSettlement("r",true)+extra
            +" GROUP BY r.shop_id,r.record_date,o.record_date,r.currency,o.currency,o.status,"+costDate,args(scope,f,q).toArray());
    }
    /** Split corrected amounts away from ordinary same-day amounts without changing any native total. */
    static List<Map<String,Object>> split(List<Map<String,Object>> original,List<Map<String,Object>> corrections,List<String> keys,List<String> sums,String count,String fxField){
        if(corrections.isEmpty())return original;
        var result=new ArrayList<Map<String,Object>>();var lookup=new HashMap<List<Object>,Map<String,Object>>();
        for(var row:original){var copy=new LinkedHashMap<>(row);result.add(copy);lookup.put(key(row,keys),copy);}
        var additions=new ArrayList<Map<String,Object>>();
        for(var correction:corrections){
            var target=lookup.get(key(correction,keys));if(target==null)continue;
            for(String field:sums){
                BigDecimal value=decimal(target.get(field)),less=decimal(correction.get(field));
                if(value!=null&&less!=null)target.put(field,value.subtract(less));
            }
            if(number(target.get("known_amounts"))==0&&sums.contains("amount"))target.put("amount",null);
            if(number(target.get("known_revenues"))==0&&sums.contains("revenue"))target.put("revenue",null);
            if(sums.contains("amount_cny"))target.put("amount_cny",number(target.get("pending_count"))==0?target.get("known_amount_cny"):null);
            var addition=new LinkedHashMap<>(correction);addition.remove("fx_operating");
            if(!addition.containsKey(fxField))addition.put(fxField,null);additions.add(addition);
        }
        result.removeIf(row->number(row.get(count))<=0);result.addAll(additions);return result;
    }
    private static List<Object> key(Map<String,Object> row,List<String> names){
        return names.stream().map(name->{
            Object value=row.get(name);return name.endsWith("date")&&value!=null?text(value):value;
        }).toList();
    }
}
