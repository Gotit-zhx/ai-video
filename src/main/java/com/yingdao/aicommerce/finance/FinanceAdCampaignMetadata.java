package com.yingdao.aicommerce.finance;

import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Official current campaign metadata. Targets are not historical funding allocations. */
final class FinanceAdCampaignMetadata {
    private FinanceAdCampaignMetadata() {}
    static boolean id(Object value){return text(value).matches("\\d{5,30}");}
    static Map<String,Object> query(String phase,String advertiser,String campaign,int page){
        if(!id(advertiser)||!id(campaign)||page<1||page>1000)throw bad("广告系列资料查询标识或页码无效");
        if("GMV".equals(phase))return Map.of("advertiser_id",advertiser,"campaign_id",campaign);
        if(!Set.of("CAMPAIGN","CAMPAIGN_DELETED","ADGROUP","ADGROUP_DELETED").contains(phase))throw bad("广告系列资料查询阶段无效");
        return Map.of("advertiser_id",advertiser,"page",page,"page_size",100,
                "filtering","{\"campaign_ids\":[\""+campaign+"\"],\"primary_status\":\""+(phase.endsWith("_DELETED")?"STATUS_DELETE":"STATUS_ALL")+"\"}");
    }
    static String endpoint(String phase){return switch(phase){
        case "GMV"->"/campaign/gmv_max/info/";
        case "CAMPAIGN","CAMPAIGN_DELETED"->"/campaign/get/";
        case "ADGROUP","ADGROUP_DELETED"->"/adgroup/get/";
        default->throw bad("广告系列资料查询阶段无效");
    };}
    static Map<String,Object> campaign(Map<String,Object> data,String campaign){
        var records=FinanceAdAutomation.requiredRows(data,"list");
        if(records.size()>1)throw bad("指定系列查询返回多条资料，保留旧资料");
        if(records.isEmpty())return new LinkedHashMap<>();
        var row=records.getFirst();checkIdentity(row,"campaign_id",campaign,true);
        var result=new LinkedHashMap<String,Object>();result.put("campaign_id",campaign);
        put(result,"campaign_name",row.get("campaign_name"),500);
        put(result,"campaign_objective",row.get("objective_type"),80);
        put(result,"campaign_structure",row.get("campaign_type"),80);
        put(result,"campaign_automation_type",row.get("campaign_automation_type"),80);
        result.put("metadata_endpoint","/campaign/get/");return result;
    }
    static Map<String,Object> gmv(Map<String,Object> data,String campaign){
        checkIdentity(data,"campaign_id",campaign,true);
        var result=new LinkedHashMap<String,Object>();result.put("campaign_id",campaign);
        put(result,"campaign_name",data.get("campaign_name"),500);
        String store=text(data.get("store_id"));
        if(!store.isBlank()&&!id(store))throw bad("GMV Max 系列返回无效店铺标识");
        result.put("official_store_ids",store.isBlank()?List.of():List.of(store));
        result.put("metadata_endpoint","/campaign/gmv_max/info/");
        result.put("relationship_basis","GMV_TARGET");return result;
    }
    static Map<String,Object> adgroups(Map<String,Object> data,String advertiser,String campaign){
        var stores=new TreeSet<String>();var ids=new LinkedHashSet<String>();boolean unknown=false;
        for(var row:FinanceAdAutomation.requiredRows(data,"list")){
            checkIdentity(row,"advertiser_id",advertiser,false);checkIdentity(row,"campaign_id",campaign,true);
            String group=text(row.get("adgroup_id"));if(!id(group)||!ids.add(group))throw bad("广告组资料标识无效或重复");
            String store=text(row.get("store_id"));
            if(!store.isBlank()&&!id(store))throw bad("广告组返回无效店铺标识");
            if(store.isBlank())unknown=true;else stores.add(store);
        }
        return Map.of("official_store_ids",List.copyOf(stores),"adgroup_ids",List.copyOf(ids),"has_unknown_targets",unknown);
    }
    static Map<String,Object> decorate(Map<String,Object> source,Map<String,Object> metadata){
        var result=new LinkedHashMap<>(source);
        if(text(result.get("campaign_name")).isBlank()&&!text(metadata.get("campaign_name")).isBlank())result.put("campaign_name",metadata.get("campaign_name"));
        for(String key:List.of("campaign_objective","campaign_structure","campaign_automation_type","official_store_ids","metadata_endpoint","metadata_observed_at","metadata_status","metadata_error","relationship_basis","has_unknown_targets"))
            if(metadata.containsKey(key))result.put(key,metadata.get(key));
        if(metadata.get("official_store_ids") instanceof List<?> stores){
            var evidence=new LinkedHashMap<String,Object>();evidence.put("store_ids",stores);evidence.put("status",metadata.getOrDefault("metadata_status","UNKNOWN"));
            evidence.put("source",metadata.get("metadata_endpoint"));evidence.put("observed_at",metadata.get("metadata_observed_at"));
            evidence.put("has_unknown_targets",Boolean.TRUE.equals(metadata.get("has_unknown_targets")));
            evidence.put("historical_allocation_verified",false);result.put("target_shop_evidence",evidence);
        }
        return result;
    }
    private static void checkIdentity(Map<String,Object> data,String key,String expected,boolean required){
        String value=text(data.get(key));if((required||!value.isBlank())&&(!id(value)||!expected.equals(value)))throw bad("广告系列资料不属于所查询的账户或系列");
    }
    private static void put(Map<String,Object> result,String key,Object raw,int limit){
        if(raw==null)return;if(!(raw instanceof String))throw bad("广告系列资料字段格式无效");
        String value=text(raw).trim();if(value.length()>limit)throw bad("广告系列资料字段超过长度限制");if(!value.isBlank())result.put(key,value);
    }
}
