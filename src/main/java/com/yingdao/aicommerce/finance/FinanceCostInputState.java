package com.yingdao.aicommerce.finance;

import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Configuration fingerprints and narrowly targeted reverse changes. Never snapshots automatic order results. */
final class FinanceCostInputState {
    private final FinanceRepository repo;private final FinanceService service;private final FinanceCostWorkspace workspace;
    FinanceCostInputState(FinanceRepository repo,FinanceService service,FinanceCostWorkspace workspace){this.repo=repo;this.service=service;this.workspace=workspace;}
    void lock(Scope scope,String group,String shop){
        new FinanceGroups(repo).require(scope,group,true);
        if(!shop.isBlank()&&!group.equals(text(service.ownedShop(scope,shop).get("group_id"))))throw bad("店铺已调整分组，请重新预览");
    }
    Map<String,Object> baseline(Scope s,String group,String shop,Map<String,Object> body){
        var result=new LinkedHashMap<String,Object>();
        result.put("sources",repo.jdbc().queryForList("SELECT id,content_hash,data_json,source_order FROM finance_cost_sources WHERE organization_id=? AND group_id=? ORDER BY id",s.organizationId(),group));
        result.put("manual",repo.jdbc().queryForList("SELECT id,code,warehouse,unit,unit_price_cny,version FROM finance_manual_costs WHERE organization_id=? AND group_id=? ORDER BY id",s.organizationId(),group));
        result.put("bookId",workspace.book(s,group).getOrDefault("bookId",""));
        // Shared sources/manual prices can acquire new human dependencies in any shop in this group.
        // The four automatic methods are deliberately excluded: they are not later explicit decisions.
        result.put("explicitMappings",repo.jdbc().queryForList("""
            SELECT m.id,m.shop_id,m.product_id,m.sku_id,m.warehouse,m.data_json,m.version
            FROM finance_cost_mappings m JOIN finance_connections c
              ON c.organization_id=m.organization_id AND c.id=m.shop_id
            WHERE m.organization_id=? AND c.group_id=? AND c.status<>'REMOVED'
              AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.data_json,'$.method')),'')
                NOT IN ('EXACT_CODE','NORMALIZED_CODE','BUNDLE_CODE','SPU_CODE')
            ORDER BY m.id
            """,s.organizationId(),group));
        // A later warehouse/basis decision in any group shop also depends on shared sources/manual prices.
        result.put("explicitSettings",repo.jdbc().queryForList("""
            SELECT st.shop_id,st.data_json,st.version
            FROM finance_cost_settings st JOIN finance_connections c
              ON c.organization_id=st.organization_id AND c.id=st.shop_id
            WHERE st.organization_id=? AND c.group_id=? AND c.status<>'REMOVED'
            ORDER BY st.shop_id
            """,s.organizationId(),group));
        result.put("shops",repo.jdbc().queryForList("SELECT id,group_id,status FROM finance_connections WHERE organization_id=? AND group_id=? AND status<>'REMOVED' ORDER BY id",s.organizationId(),group));
        if(!shop.isBlank()){
            result.put("settings",repo.jdbc().queryForList("SELECT data_json,version FROM finance_cost_settings WHERE organization_id=? AND shop_id=?",s.organizationId(),shop));
            if(body.containsKey("product_id"))result.put("mappings",repo.jdbc().queryForList("SELECT id,data_json,version FROM finance_cost_mappings WHERE organization_id=? AND shop_id=? AND product_id=? AND sku_id=? ORDER BY id",s.organizationId(),shop,body.get("product_id"),body.get("sku_id")));
        }
        return result;
    }
    String fingerprint(Scope s,String group,String shop,Map<String,Object> body){return digest(baseline(s,group,shop,body));}
    String digest(Object object){return hash(repo.write(canonical(object)));}
    private Object canonical(Object value){
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(text(k),canonical(v)));return sorted;}
        if(value instanceof Collection<?> list)return list.stream().map(this::canonical).toList();
        if(value instanceof Number n){var d=decimal(n);return d==null?text(n):d.scale() < -18||d.scale() > 18?d.toString():d.stripTrailingZeros().toPlainString();}
        if(value instanceof byte[] bytes)return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        return value;
    }
    Map<String,Object> impact(Scope s,String group,String shop){
        boolean groupWide=shop.isBlank();var args=groupWide?new Object[]{s.organizationId(),group}:new Object[]{s.organizationId(),shop};
        String scope=groupWide?"c.group_id=?":"c.id=?";
        long shops=repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_connections c WHERE c.organization_id=? AND "+scope+" AND c.status<>'REMOVED'",Long.class,args);
        long orders=repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_connections c JOIN finance_records r ON r.organization_id=c.organization_id AND r.shop_id=c.id AND r.kind='ORDER' WHERE c.organization_id=? AND "+scope+" AND c.status<>'REMOVED'",Long.class,args);
        var result=new LinkedHashMap<String,Object>();result.put("scopeShops",shops);result.put("scopeOrderCount",orders);result.put("hasHistorical",orders>0);result.put("coverage","SCOPE_UPPER_BOUND");result.put("amountChange",null);result.put("largeScopeThreshold",FinanceCostInputPreview.LARGE_ORDER_SCOPE);
        result.put("actualScope",(groupWide?"分组内相关店铺":"当前店铺")+"，现有适用历史及后续订单；已入库订单范围上界 "+orders+" 笔，非精确受影响订单数。缺失/未同步订单不在数量内，金额影响未核定。");return result;
    }
    Map<String,Object> capture(Scope s,String type,String group,String shop,String target,Map<String,Object> body){
        var result=new LinkedHashMap<String,Object>();
        if(Set.of("IMPORT","REMOVE_SOURCE").contains(type))result.put("source",one("SELECT id,organization_id,group_id,source_name,content_hash,data_json,source_order,legacy_merged,created_by FROM finance_cost_sources WHERE organization_id=? AND group_id=? AND id=?",s.organizationId(),group,target));
        if(Set.of("MANUAL_CREATE","MANUAL_UPDATE","MANUAL_DELETE").contains(type))result.put("manual",one("SELECT id,organization_id,group_id,code,warehouse,unit,unit_price_cny,version,created_by,updated_by FROM finance_manual_costs WHERE organization_id=? AND group_id=? AND id=?",s.organizationId(),group,target));
        if(Set.of("MAPPING","MANUAL_CREATE").contains(type)){
            String id=mappingId(s,shop,body);result.put("mappingId",id);
            result.put("mapping",one("SELECT id,organization_id,shop_id,product_id,sku_id,warehouse,data_json,version,updated_by FROM finance_cost_mappings WHERE organization_id=? AND id=?",s.organizationId(),id));
        }
        if(type.equals("SETTINGS"))result.put("settings",one("SELECT organization_id,shop_id,data_json,version,updated_by FROM finance_cost_settings WHERE organization_id=? AND shop_id=?",s.organizationId(),shop));
        return result;
    }
    Map<String,Object> one(String sql,Object...args){var found=repo.jdbc().queryForList(sql,args);return found.isEmpty()?Map.of():found.getFirst();}
    static String mappingId(Scope s,String shop,Map<String,Object> body){return hash(s.organizationId()+"\0"+shop+"\0"+text(body.get("product_id"))+"\0"+text(body.get("sku_id"))+"\0"+text(body.get("warehouse")));}
    /** The caller verified the complete post-apply fingerprint; only this operation's targets are restored. */
    void restore(Scope s,String actor,String group,String shop,String target,Map<String,Object> before,Map<String,Object> after){
        if(before.containsKey("source"))restoreRow("finance_cost_sources",s,"id",target,map(before.get("source")),map(after.get("source")),actor);
        if(before.containsKey("manual"))restoreRow("finance_manual_costs",s,"id",target,map(before.get("manual")),map(after.get("manual")),actor);
        if(before.containsKey("mapping"))restoreRow("finance_cost_mappings",s,"id",text(before.get("mappingId")),map(before.get("mapping")),map(after.get("mapping")),actor);
        if(before.containsKey("settings"))restoreRow("finance_cost_settings",s,"shop_id",shop,map(before.get("settings")),map(after.get("settings")),actor);
        if(before.containsKey("source")||before.containsKey("manual")){new FinanceCostSources(repo).snapshot(s,actor,group);new FinanceOrderCosts(repo).requestGroup(s,group);}
        else new FinanceOrderCosts(repo).requestShop(s.organizationId(),shop);
        workspace.audit(s,actor,"COST_INPUT_UNDO",target,after,before,"撤销已核对的单次人工操作");service.invalidateAfterCommit(s);
    }
    private void restoreRow(String table,Scope s,String key,String target,Map<String,Object> before,Map<String,Object> after,String actor){
        repo.jdbc().update("DELETE FROM "+table+" WHERE organization_id=? AND "+key+"=?",s.organizationId(),target);
        if(before.isEmpty())return;
        var restored=new LinkedHashMap<>(before);
        if(restored.containsKey("version"))restored.put("version",Math.max(number(before.get("version")),number(after.get("version")))+1);
        if(restored.containsKey("updated_by"))restored.put("updated_by",actor);
        String cols=String.join(",",restored.keySet()),slots=String.join(",",Collections.nCopies(restored.size(),"?"));
        repo.jdbc().update("INSERT INTO "+table+" ("+cols+") VALUES ("+slots+")",restored.values().toArray());
    }
}
