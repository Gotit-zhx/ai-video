package com.yingdao.aicommerce.finance;

import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Independent import sources and manual prices materialized into an immutable calculation snapshot. */
final class FinanceCostSources {
    private final FinanceRepository repo;
    FinanceCostSources(FinanceRepository repo){this.repo=repo;}
    Map<String,Object> decode(Object value){
        return repo.read(value instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):text(value));
    }
    List<Map<String,Object>> sources(Scope s,String group){
        return repo.jdbc().queryForList("""
            SELECT id,source_name,created_at,legacy_merged,JSON_LENGTH(data_json,'$.costs') row_count
            FROM finance_cost_sources WHERE organization_id=? AND group_id=? ORDER BY source_order DESC
            """,s.organizationId(),group);
    }
    List<Map<String,Object>> manual(Scope s,String group){
        return repo.jdbc().queryForList("""
            SELECT id,code sku,warehouse,unit,unit_price_cny unitPriceCny,version,updated_at
            FROM finance_manual_costs WHERE organization_id=? AND group_id=? ORDER BY created_at,id
            """,s.organizationId(),group).stream().map(row->{
                var value=new LinkedHashMap<>(row);value.put("manualId",row.get("id"));return (Map<String,Object>)value;
            }).toList();
    }
    /** Call only while holding the group row lock and inside the caller's transaction. */
    void snapshot(Scope s,String actor,String group){
        var merged=new LinkedHashMap<String,Map<String,Object>>();
        var files=repo.jdbc().queryForList("""
            SELECT id,source_name,data_json FROM finance_cost_sources
            WHERE organization_id=? AND group_id=? ORDER BY source_order
            """,s.organizationId(),group);
        for(var file:files)for(var cost:rows(decode(file.get("data_json")).get("costs"))){
            String key=cost.get("warehouse")+"\0"+cost.get("sku");
            var row=merged.computeIfAbsent(key,k->new LinkedHashMap<>(Map.of("sku",cost.get("sku"),"warehouse",cost.get("warehouse"),
                "prices",new LinkedHashMap<String,Object>(),"priceSources",new LinkedHashMap<String,Object>())));
            for(var price:map(cost.get("prices")).entrySet()){
                map(row.get("prices")).put(price.getKey(),price.getValue());
                map(row.get("priceSources")).put(price.getKey(),Map.of("id",file.get("id"),"name",file.get("source_name")));
            }
        }
        var manual=manual(s,group);
        String name=files.size()==1?text(files.getFirst().get("source_name")):files.size()+" 份成本表";
        if(!manual.isEmpty())name+=" · "+manual.size()+" 条手动成本";
        String id=UUID.randomUUID().toString();
        repo.jdbc().update("""
            INSERT INTO finance_cost_books(id,organization_id,group_id,source_name,content_hash,data_json,created_by)
            VALUES(?,?,?,?,?,?,?)
            """,id,s.organizationId(),group,name.substring(0,Math.min(name.length(),250)),hash("sources-v2:"+id),
                repo.write(Map.of("costs",merged.values(),"manualCosts",manual,"currency","CNY","sourceVersion",2)),actor);
    }
    static List<Map<String,Object>> allCosts(Map<String,Object> book){
        var result=new ArrayList<>(rows(book.get("costs")));result.addAll(rows(book.get("manualCosts")));return result;
    }
}
