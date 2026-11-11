package com.yingdao.aicommerce.finance;

import java.math.*;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Advertising presentation includes ledger fees; only independent AD costs reduce net settlement again. */
final class FinanceAdvertisingReport {
    private FinanceAdvertisingReport() {}
    static final Set<String> COMMISSION_FIELDS=Set.of("affiliate_ads_commission_amount","tap_shop_ads_commission");
    static final Set<String> LEDGER_FIELDS=Set.of("pay_per_sale","gmv_max_ad_fee_amount","smart_promotion_fee_amount",
            "affiliate_ads_commission_amount","tap_shop_ads_commission");
    static final Set<String> LEDGER_LABELS=Set.of("成交广告费","GMV Max 广告费用","智能推广费用","达人广告佣金","合作伙伴店铺广告佣金");
    static boolean ledgerCategory(String category){
        String value=text(category).trim().toLowerCase(Locale.ROOT);
        return LEDGER_LABELS.stream().anyMatch(label->label.toLowerCase(Locale.ROOT).equals(value))
                ||LEDGER_FIELDS.contains(value.substring(value.lastIndexOf('.')+1));
    }
    static boolean includes(Map<String,Object> row){
        return Set.of("AD","AD_GMV").contains(text(row.get("kind")))
                ||"FEE".equals(row.get("kind"))&&ledgerCategory(text(row.get("category")));
    }
    static String ledgerPredicate(String alias){
        String column="LOWER(TRIM("+(alias.isBlank()?"category":alias+".category")+"))";
        String labels=String.join(",",new TreeSet<>(LEDGER_LABELS).stream().map(v->"'"+v.toLowerCase(Locale.ROOT)+"'").toList());
        String fields=String.join(",",new TreeSet<>(LEDGER_FIELDS).stream().map(v->"'"+v+"'").toList());
        return "("+column+" IN ("+labels+") OR SUBSTRING_INDEX("+column+",'.',-1) IN ("+fields+"))";
    }
    static Map<String,Object> build(List<Map<String,Object>> facts,List<Map<String,Object>> shops,Filter filter,
                                    boolean cny,FinanceFx.Quote quote,String granularity){
        var groups=new HashMap<String,String>();
        shops.forEach(shop->groups.put(text(shop.get("id")),text(shop.get("group_id"))));
        var prepared=new ArrayList<Map<String,Object>>();
        if(cny)quote.preload(facts.stream().map(FinanceFx::businessDate).filter(Objects::nonNull).toList());
        for(var fact:facts){
            if(!includes(fact)||!filter.currency().isBlank()&&!filter.currency().equals(fact.get("currency")))continue;
            LocalDate day=LocalDate.parse(text(fact.get("record_date")));
            if(day.isBefore(filter.start())||day.isAfter(filter.end()))continue;
            LocalDate fxDay=FinanceFx.businessDate(fact);
            var row=new LinkedHashMap<>(fact);
            String kind=text(row.get("kind"));
            row.put("sourceType",kind.equals("FEE")?"SETTLEMENT":kind.equals("AD")?"INDEPENDENT":"GMV");
            row.put("group_id",groups.getOrDefault(text(row.get("shop_id")),""));
            row.put("category",kind.equals("FEE")?FinanceSettlementBreakdown.label(categoryField(text(row.get("category")))):
                    kind.equals("AD")?"独立广告消耗":"GMV Max 专项消耗");
            String field=categoryField(text(fact.get("category")));
            row.put("advertisingCommission",kind.equals("FEE")&&COMMISSION_FIELDS.contains(field.substring(field.lastIndexOf('.')+1)));
            if(cny){
                if(quote.rate(text(row.get("currency")),fxDay)==null){
                    if(decimal(row.get("amount"))!=null&&decimal(row.get("amount")).signum()!=0)row.put("known_amounts",0);
                    if(decimal(row.get("revenue"))!=null&&decimal(row.get("revenue")).signum()!=0)row.put("known_revenues",0);
                }
                row.put("amount",quote.convert(row.get("amount"),text(row.get("currency")),fxDay));
                row.put("revenue",quote.convert(row.get("revenue"),text(row.get("currency")),fxDay));
                row.put("currency","CNY");
            }
            prepared.add(row);
        }
        String currency=cny?"CNY":filter.currency();
        var ordinary=prepared.stream().filter(r->!r.get("sourceType").equals("GMV")).toList();
        var independent=ordinary.stream().filter(r->r.get("sourceType").equals("INDEPENDENT")).toList();
        var settled=ordinary.stream().filter(r->r.get("sourceType").equals("SETTLEMENT")).toList();
        var gmv=prepared.stream().filter(r->r.get("sourceType").equals("GMV")).toList();
        var result=new LinkedHashMap<String,Object>();
        result.put("total",summary(ordinary,currency));
        result.put("independent",summary(independent,currency));
        result.put("settlement",summary(settled,currency));
        result.put("commission",summary(settled.stream().filter(r->Boolean.TRUE.equals(r.get("advertisingCommission"))).toList(),currency));
        result.put("unassigned",summary(ordinary.stream().filter(r->"UNASSIGNED".equals(r.get("shop_id"))).toList(),currency));
        result.put("shops",breakdown(prepared,currency,"shop_id","group_id"));
        result.put("groups",breakdown(prepared,currency,"group_id"));
        result.put("categories",breakdown(ordinary,currency,"sourceType","category"));
        result.put("performance",performance(independent,currency));
        var gmvSummary=summary(gmv,currency);
        gmvSummary.put("roi",ratio(completeValue(gmvSummary,"revenue","known_revenues"),decimal(gmvSummary.get("amount")),BigDecimal.ONE));
        result.put("gmv",gmvSummary);
        var points=new LinkedHashMap<String,Map<String,Object>>();
        for(LocalDate day=filter.start();!day.isAfter(filter.end());day=day.plusDays(1)){
            String period=bucket(day,granularity);
            points.computeIfAbsent(period,k->new LinkedHashMap<>(Map.of("period",k)));
        }
        var periods=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var row:ordinary){
            String period=bucket(LocalDate.parse(text(row.get("record_date"))),granularity);
            periods.computeIfAbsent(period,k->new ArrayList<>()).add(row);
        }
        periods.forEach((period,rows)->{
            var point=points.get(period);if(point==null)return;
            point.put("AD_TOTAL",summary(rows,currency).get("amount"));
            point.put("AD_INDEPENDENT",recordedAmount(rows.stream().filter(r->r.get("sourceType").equals("INDEPENDENT")).toList(),currency));
            point.put("AD_SETTLEMENT",recordedAmount(rows.stream().filter(r->r.get("sourceType").equals("SETTLEMENT")).toList(),currency));
        });
        result.put("trend",new ArrayList<>(points.values()));
        result.put("basis","当前已入账广告费用 = 平台账单广告费（含广告佣金）+ 普通独立广告报表或导入消耗。GMV Max 自动按店铺单列；普通报表不保证覆盖 GMV Max，且 GMV Max 与账单扣费的重叠尚待核对，因此不直接相加或重复扣减利润。不能将当前入账合计视为完整媒体消耗。未归属费用保留在账户层级。");
        result.put("profitBasis","利润沿用经营范围：平台账单广告费及广告佣金已随平台净结算及费用处理，只额外扣减独立广告消耗；刷单等既有排除规则保留，卡片合计不重复扣减。");
        result.put("coverageNote","仅统计已同步或已导入的记录；没有记录不代表平台实际消耗为零。广告充值、充值或技术支持混合字段、优惠券和普通达人佣金不凭名称推定为广告费用。");
        return result;
    }
    private static String categoryField(String category){
        for(String field:LEDGER_FIELDS)if(FinanceSettlementBreakdown.label(field).equalsIgnoreCase(category.trim()))return field;
        return category.trim().toLowerCase(Locale.ROOT);
    }
    private static Object recordedAmount(List<Map<String,Object>> rows,String currency){
        return rows.isEmpty()?null:summary(rows,currency).get("amount");
    }
    static Map<String,Object> summary(List<Map<String,Object>> rows,String currency){
        var grouped=FinanceService.aggregate(rows);
        if(grouped.size()>1)throw bad("广告金额需要先按币种筛选或使用同一汇率换算，不能直接跨币种相加");
        var result=new LinkedHashMap<String,Object>(grouped.isEmpty()?Map.of():grouped.getFirst());
        long count=number(result.get("records")),known=number(result.get("known_amounts"));
        BigDecimal amount=decimal(result.get("amount"));
        result.put("currency",currency);result.put("records",count);result.put("known_amounts",known);
        result.put("knownAmount",amount==null?BigDecimal.ZERO:amount);
        result.put("amount",known<count?null:amount==null?BigDecimal.ZERO:amount);
        result.put("complete",known==count);result.put("hasRecords",count>0);
        return result;
    }
    private static List<Map<String,Object>> breakdown(List<Map<String,Object>> rows,String currency,String...keys){
        var groups=new LinkedHashMap<List<String>,List<Map<String,Object>>>();
        for(var row:rows)groups.computeIfAbsent(Arrays.stream(keys).map(k->text(row.get(k))).toList(),k->new ArrayList<>()).add(row);
        var result=new ArrayList<Map<String,Object>>();
        groups.forEach((key,items)->{
            var row=summary(items.stream().filter(r->!r.get("sourceType").equals("GMV")).toList(),currency);
            for(int i=0;i<keys.length;i++)row.put(keys[i],key.get(i));
            row.put("independent",summary(items.stream().filter(r->r.get("sourceType").equals("INDEPENDENT")).toList(),currency));
            row.put("settlement",summary(items.stream().filter(r->r.get("sourceType").equals("SETTLEMENT")).toList(),currency));
            row.put("commission",summary(items.stream().filter(r->Boolean.TRUE.equals(r.get("advertisingCommission"))).toList(),currency));
            row.put("gmv",summary(items.stream().filter(r->r.get("sourceType").equals("GMV")).toList(),currency));
            result.add(row);
        });
        return result;
    }
    private static Map<String,Object> performance(List<Map<String,Object>> rows,String currency){
        var result=summary(rows,currency);
        BigDecimal spend=decimal(result.get("amount"));
        BigDecimal impressions=completeValue(result,"impressions","known_impressions");
        BigDecimal clicks=completeValue(result,"clicks","known_clicks");
        BigDecimal conversions=completeValue(result,"conversions","known_conversions");
        result.put("clickThroughRate",ratio(clicks,impressions,BigDecimal.valueOf(100)));
        result.put("costPerClick",ratio(spend,clicks,BigDecimal.ONE));
        result.put("costPerThousand",ratio(spend,impressions,BigDecimal.valueOf(1000)));
        result.put("costPerConversion",ratio(spend,conversions,BigDecimal.ONE));
        result.put("conversionRate",ratio(conversions,clicks,BigDecimal.valueOf(100)));
        return result;
    }
    static BigDecimal completeValue(Map<String,Object> row,String field,String known){
        return number(row.get("records"))>0&&number(row.get(known))==number(row.get("records"))?decimal(row.get(field)):null;
    }
    private static BigDecimal ratio(BigDecimal numerator,BigDecimal denominator,BigDecimal scale){
        return numerator!=null&&denominator!=null&&denominator.signum()>0?numerator.multiply(scale).divide(denominator,6,RoundingMode.HALF_UP):null;
    }
}
