package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Shop spending is separate from platform deductions, attribution revenue and profit postings. */
final class FinanceAdSpendReport {
    static final List<String> MONEY=List.of("amount","cash_amount","credit_amount","grant_amount");
    private FinanceAdSpendReport() {}
    static String fingerprint(Map<String,Object> r){
        return hash(text(r.get("currency"))+"\0"+MONEY.stream().map(k->{BigDecimal n=decimal(r.get(k));return n==null?"?":n.stripTrailingZeros().toPlainString();}).reduce("",(a,b)->a+"\0"+b));
    }
    static void validateFunding(Map<String,Object> r){
        BigDecimal total=decimal(r.get("amount"));if(total==null)throw bad("缺少消耗合计");
        if(MONEY.subList(1,4).stream().allMatch(k->r.get(k)!=null)&&MONEY.subList(1,4).stream().map(k->decimal(r.get(k))).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(total)!=0)
            throw bad("现金、信用额度和赠款之和不等于合计");
    }
    static String bucket(Map<String,Object> r){return String.join("\0",text(r.get("platform")),text(r.get("account_id")),text(r.get("record_date")),text(r.get("currency")));}
    static String periodKey(Map<String,Object> r){return String.join("\0",text(r.get("platform")),text(r.get("record_date")),text(r.get("currency")));}
    static String campaignKey(Map<String,Object> r){return bucket(r)+"\0"+text(r.get("campaign_id"));}
    static Map<String,Object> api(Map<String,Object> record,Map<String,Object> raw){
        var r=new LinkedHashMap<String,Object>();r.put("source_key",record.get("id"));r.put("platform",record.get("platform"));r.put("shop_id",record.get("shop_id"));r.put("record_date",text(record.get("record_date")));r.put("currency",record.get("currency"));r.put("amount",record.get("amount"));
        r.put("account_id",first(raw,"advertiser_id","account_id"));r.put("account_name",first(raw,"advertiser_name","account_name"));r.put("campaign_id",first(raw,"dimensions.campaign_id","campaign_id"));r.put("campaign_name",first(raw,"dimensions.campaign_name","campaign_name"));
        r.put("source","API".equals(record.get("source"))?("AD_GMV".equals(record.get("kind"))?"API_GMV":"API_BASIC"):"LEGACY_IMPORT");
        for(String k:MONEY.subList(1,4))r.put(k,null);return r;
    }
    static List<Map<String,Object>> allocated(Map<String,Object> source,List<Map<String,Object>> assignments,List<Map<String,Object>> shops){
        var known=new HashMap<String,Map<String,Object>>();shops.forEach(s->known.put(text(s.get("id")),s));
        String owner=text(source.get("shop_id"));var base=new LinkedHashMap<>(source);base.put("fingerprint",fingerprint(source));
        if(!owner.isBlank()&&!owner.equals("UNASSIGNED")&&known.containsKey(owner)){base.put("assignment_status","OFFICIAL");return List.of(base);}
        if(assignments.isEmpty()){base.put("shop_id","UNASSIGNED");base.put("assignment_status","PENDING");return List.of(base);}
        if(assignments.stream().anyMatch(a->!fingerprint(source).equals(a.get("fingerprint"))||!known.containsKey(text(a.get("shop_id"))))){base.put("shop_id","UNASSIGNED");base.put("assignment_status","REVIEW");return List.of(base);}
        var out=new ArrayList<Map<String,Object>>();var used=new HashMap<String,BigDecimal>();var unique=new HashSet<String>();
        for(var a:assignments){
            String shop=text(a.get("shop_id"));if(!unique.add(shop)||!source.get("platform").equals(known.get(shop).get("platform")))throw bad("分配店铺重复或平台不一致");
            var row=new LinkedHashMap<>(base);row.put("shop_id",shop);row.put("assignment_status","CONFIRMED");
            for(String k:MONEY){BigDecimal original=decimal(source.get(k)),value=decimal(a.get(k));
                if(value==null&&!text(a.get(k)).isBlank())throw bad("分配金额格式不正确");
                if(original==null&&value!=null)throw bad("来源未提供金额分项，不能补造分配金额");
                if(original!=null&&value==null)throw bad("请填写来源已提供的全部分配金额");
                if(value!=null){if(value.precision()>24||value.scale()>6||value.signum()!=0&&value.signum()!=original.signum())throw bad("分配金额符号或精度不正确");used.merge(k,value,BigDecimal::add);}row.put(k,value);
            }
            validateFunding(row);out.add(row);
        }
        var rest=new LinkedHashMap<>(base);boolean remaining=false;
        for(String k:MONEY){BigDecimal original=decimal(source.get(k));if(original==null)continue;BigDecimal usedAmount=used.getOrDefault(k,BigDecimal.ZERO);
            if(usedAmount.abs().compareTo(original.abs())>0)throw bad("分配金额超过原始消耗");
            BigDecimal value=original.subtract(usedAmount);rest.put(k,value);remaining|=value.signum()!=0;
        }
        if(remaining){rest.put("shop_id","UNASSIGNED");rest.put("assignment_status","PARTIAL");out.add(rest);}return out;
    }
    static Map<String,Object> build(List<Map<String,Object>> sources,List<Map<String,Object>> assignments,List<Map<String,Object>> shops,Filter f,FinanceFx.Quote quote,String search,String status){
        var imports=new HashSet<String>();var series=new HashSet<String>();var importCounts=new HashMap<String,Integer>();var officialByCampaign=new HashMap<String,List<Map<String,Object>>>();var apiPeriods=new HashSet<String>();
        for(var r:sources){String key=campaignKey(r);if(text(r.get("source")).startsWith("API_"))apiPeriods.add(periodKey(r));if("IMPORT_FUNDING".equals(r.get("source"))){imports.add(key);importCounts.merge(key,1,Integer::sum);}if("API_GMV".equals(r.get("source")))officialByCampaign.computeIfAbsent(key,k->new ArrayList<>()).add(r);if(!text(r.get("campaign_id")).isBlank())series.add(bucket(r));}
        var known=new HashMap<String,Map<String,Object>>();shops.forEach(s->known.put(text(s.get("id")),s));
        var splits=new HashMap<String,List<Map<String,Object>>>();assignments.forEach(a->splits.computeIfAbsent(text(a.get("source_key")),k->new ArrayList<>()).add(a));
        var prepared=new ArrayList<Map<String,Object>>();int excluded=0,unresolved=0;
        for(var source:sources){
            String currency=text(source.get("currency")),platform=text(source.get("platform"));LocalDate day=LocalDate.parse(text(source.get("record_date")));
            if(day.isBefore(f.start())||day.isAfter(f.end())||!f.platform().isBlank()&&!platform.equals(f.platform())||!f.currency().isBlank()&&!currency.equals(f.currency()))continue;
            if(!"IMPORT_FUNDING".equals(source.get("source"))&&imports.contains(campaignKey(source))||"API_BASIC".equals(source.get("source"))&&!text(source.get("account_id")).isBlank()&&series.contains(bucket(source))){excluded++;continue;}
            var attributed=new LinkedHashMap<>(source);
            boolean unknownIdentity=(text(source.get("account_id")).isBlank()||text(source.get("account_id")).startsWith("manual:"))&&apiPeriods.contains(periodKey(source));
            if(unknownIdentity){attributed.put("overlap_status","IDENTITY_REQUIRED");unresolved++;}
            if("IMPORT_FUNDING".equals(source.get("source"))&&importCounts.get(campaignKey(source))==1){
                var matches=officialByCampaign.getOrDefault(campaignKey(source),List.of());
                var owners=matches.stream().map(r->text(r.get("shop_id"))).distinct().toList();
                if(owners.size()==1&&known.containsKey(owners.getFirst())&&matches.stream().allMatch(r->r.get("amount")!=null)&&matches.stream().map(r->decimal(r.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(decimal(source.get("amount")))==0)attributed.put("shop_id",owners.getFirst());
            }
            for(var row:allocated(attributed,splits.getOrDefault(text(source.get("source_key")),List.of()),shops)){
                String shop=text(row.get("shop_id"));var details=known.getOrDefault(shop,Map.of());String group=text(details.get("group_id"));
                if(!f.shopId().isBlank()&&!f.shopId().equals(shop)||!f.groupId().isBlank()&&!(f.groupId().equals("UNGROUPED")?group.isBlank():f.groupId().equals(group)))continue;
                row.put("group_id",group);row.put("group_name",text(details.get("group_name")));row.put("shop_name",shop.equals("UNASSIGNED")?"待分配店铺":first(details,"display_name","name"));
                if(unknownIdentity)row.put("assignment_status","SOURCE_REVIEW");
                row.put("source_currency",currency);row.put("currency",quote==null?currency:"CNY");
                for(String k:MONEY){row.put("original_"+k,decimal(row.get(k)));if(quote!=null)row.put(k,quote.convert(row.get(k),currency));}
                if(prepared.size()>=50000)throw bad("分配后的明细超过 50,000 条，请缩小日期范围");prepared.add(row);
            }
        }
        prepared.sort(Comparator.<Map<String,Object>,String>comparing(r->text(r.get("record_date"))).reversed().thenComparing(r->text(r.get("source_key"))));
        var storeRows=summaries(prepared,"platform","shop_id","shop_name","group_id","group_name","currency");
        for(var shop:shops){
            String id=text(shop.get("id")),group=text(shop.get("group_id")),platform=text(shop.get("platform"));
            if(!f.shopId().isBlank()&&!f.shopId().equals(id)||!f.platform().isBlank()&&!f.platform().equals(platform)||!f.groupId().isBlank()&&!(f.groupId().equals("UNGROUPED")?group.isBlank():f.groupId().equals(group)))continue;
            if(storeRows.stream().anyMatch(r->id.equals(r.get("shop_id"))))continue;
            var empty=new LinkedHashMap<String,Object>();empty.put("shop_id",id);empty.put("shop_name",first(shop,"display_name","name"));empty.put("group_id",group);empty.put("group_name",text(shop.get("group_name")));empty.put("platform",platform);empty.put("currency",quote==null?(f.currency().isBlank()?"—":f.currency()):"CNY");empty.put("records",0);empty.put("pending_records",0);
            for(String k:MONEY){empty.put(k,null);empty.put("known_"+k,0L);}storeRows.add(empty);
        }
        var groupRows=summaries(prepared,"platform","group_id","group_name","currency");
        for(var store:storeRows)if(groupRows.stream().noneMatch(g->g.get("platform").equals(store.get("platform"))&&g.get("group_id").equals(store.get("group_id")))){var empty=new LinkedHashMap<>(store);empty.remove("shop_id");empty.remove("shop_name");empty.put("shop_count",0);groupRows.add(empty);}
        var result=new LinkedHashMap<String,Object>();result.put("totals",summaries(prepared,"currency"));result.put("groups",groupRows);result.put("shops",storeRows);result.put("overlapExcluded",excluded);result.put("overlapUnresolved",unresolved);
        var needle=text(search).trim().toLowerCase(Locale.ROOT);
        result.put("items",prepared.stream().filter(r->(status==null||status.isBlank()||status.equals("PENDING")?status==null||status.isBlank()||"UNASSIGNED".equals(r.get("shop_id"))||"SOURCE_REVIEW".equals(r.get("assignment_status")):status.equals(r.get("assignment_status")))&&(needle.isBlank()||List.of("campaign_id","campaign_name","account_id","account_name","shop_name").stream().anyMatch(k->text(r.get(k)).toLowerCase(Locale.ROOT).contains(needle)))).toList());
        result.put("scopeRecords",prepared.size());return result;
    }
    static List<Map<String,Object>> summaries(List<Map<String,Object>> rows,String...keys){
        var groups=new LinkedHashMap<List<String>,List<Map<String,Object>>>();for(var r:rows)groups.computeIfAbsent(Arrays.stream(keys).map(k->text(r.get(k))).toList(),k->new ArrayList<>()).add(r);
        var result=new ArrayList<Map<String,Object>>();groups.forEach((key,items)->{
            var r=new LinkedHashMap<String,Object>();for(int i=0;i<keys.length;i++)r.put(keys[i],key.get(i));r.put("records",items.size());r.put("shop_count",items.stream().map(x->text(x.get("shop_id"))).filter(x->!x.equals("UNASSIGNED")).distinct().count());r.put("pending_records",items.stream().filter(x->"UNASSIGNED".equals(x.get("shop_id"))||"IDENTITY_REQUIRED".equals(x.get("overlap_status"))).count());r.put("source_review_records",items.stream().filter(x->"IDENTITY_REQUIRED".equals(x.get("overlap_status"))).count());
            for(String k:MONEY){long count=items.stream().filter(x->x.get(k)!=null&&!"IDENTITY_REQUIRED".equals(x.get("overlap_status"))).count();var sum=items.stream().filter(x->!"IDENTITY_REQUIRED".equals(x.get("overlap_status"))).map(x->decimal(x.get(k))).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);r.put("known_"+k,count);r.put(k,count==items.size()?sum:null);r.put("partial_"+k,sum);}
            result.add(r);
        });return result;
    }
}
