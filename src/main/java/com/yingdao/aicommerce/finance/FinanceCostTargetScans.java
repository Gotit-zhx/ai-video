package com.yingdao.aicommerce.finance;

import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Finds existing orders for edited mappings. The order-cost worker still owns calculation and publication. */
final class FinanceCostTargetScans {
    static final Sku ALL_SKUS=new Sku("","");
    private static final int BATCH=200;
    record Sku(String product,String sku){}
    private record Target(Sku sku,String through){}
    record Shop(String organization,String id){}
    record Result(String organization,boolean finished){}
    private record Candidate(Map<String,Object> order,Map<String,Object> raw){}
    private final FinanceRepository repo;

    FinanceCostTargetScans(FinanceRepository repo){this.repo=repo;}

    boolean enabled(Scope scope){
        return Boolean.TRUE.equals(repo.jdbc().queryForObject("""
            SELECT EXISTS(SELECT 1 FROM finance_cost_target_companies WHERE organization_id=?)
            """,Boolean.class,scope.organizationId()));
    }

    void request(Scope scope,String shop,Collection<Sku> skus){
        if(skus.isEmpty())return;
        repo.jdbc().update("""
            INSERT INTO finance_cost_target_scans(organization_id,shop_id,active_targets,next_targets)
            VALUES(?,?,JSON_ARRAY(),JSON_ARRAY()) ON DUPLICATE KEY UPDATE shop_id=VALUES(shop_id)
            """,scope.organizationId(),shop);
        var scan=repo.jdbc().queryForMap("""
            SELECT cursor_id,active_targets,next_targets FROM finance_cost_target_scans
            WHERE organization_id=? AND shop_id=? FOR UPDATE
            """,scope.organizationId(),shop);
        String cursor=text(scan.get("cursor_id"));
        var active=targets(scan.get("active_targets"));
        var next=targets(scan.get("next_targets"));
        for(var sku:skus){
            if(cursor.isBlank()){
                merge(active,new Target(sku,""));
                continue;
            }
            var running=active.get(sku);
            if(running==null)running=active.get(ALL_SKUS);
            // The unvisited suffix uses current settings. Only the visited prefix of
            // a running target can need another check after a newer edit.
            String replayThrough=running!=null&&running.through().isBlank()?cursor:"";
            merge(next,new Target(sku,replayThrough));
        }
        repo.jdbc().update("""
            UPDATE finance_cost_target_scans SET active_targets=?,next_targets=?
            WHERE organization_id=? AND shop_id=?
            """,writeTargets(active),writeTargets(next),scope.organizationId(),shop);
    }

    void requestPrice(Scope scope,String group,String manualId,List<Map<String,Object>> before,List<Map<String,Object>> after){
        var targetsByShop=new TreeMap<String,Set<Sku>>();
        for(var row:repo.jdbc().queryForList("""
            SELECT m.shop_id,m.product_id,m.sku_id FROM finance_cost_mappings m
            JOIN finance_connections c ON c.organization_id=m.organization_id AND c.id=m.shop_id
            WHERE m.organization_id=? AND c.group_id=? AND c.status<>'REMOVED'
              AND JSON_UNQUOTE(JSON_EXTRACT(m.data_json,'$.groupId'))=?
              AND JSON_UNQUOTE(JSON_EXTRACT(m.data_json,'$.manualCostId'))=?
            """,scope.organizationId(),group,group,manualId)){
            targetsByShop.computeIfAbsent(text(row.get("shop_id")),ignored->new LinkedHashSet<>())
                    .add(new Sku(text(row.get("product_id")),text(row.get("sku_id"))));
        }
        requestWarehouseChanges(scope,group,before,after,targetsByShop);
        targetsByShop.forEach((shop,skus)->request(scope,shop,skus));
    }

    private void requestWarehouseChanges(Scope scope,String group,List<Map<String,Object>> before,List<Map<String,Object>> after,
                                         Map<String,Set<Sku>> targetsByShop){
        // Creating or removing the last cost entry in a warehouse can also
        // change the inferred warehouse for other products at the same shop.
        boolean availableChanged=before.isEmpty()!=after.isEmpty();
        if(!availableChanged&&warehouses(before).equals(warehouses(after)))return;
        for(var row:repo.jdbc().queryForList("""
            SELECT c.id,c.region,s.data_json FROM finance_connections c LEFT JOIN finance_cost_settings s
              ON s.organization_id=c.organization_id AND s.shop_id=c.id
            WHERE c.organization_id=? AND c.group_id=? AND c.status<>'REMOVED'
            """,scope.organizationId(),group)){
            String region=text(row.get("region"));
            var settings=row.get("data_json")==null?Map.<String,Object>of():repo.read(text(row.get("data_json")));
            if(availableChanged||!FinanceOrderCosts.resolveWarehouse(region,before,settings).equals(FinanceOrderCosts.resolveWarehouse(region,after,settings)))
                targetsByShop.put(text(row.get("id")),Set.of(ALL_SKUS));
        }
    }

    private static Set<String> warehouses(List<Map<String,Object>> costs){
        var warehouses=new HashSet<String>();
        for(var cost:costs)warehouses.add(text(cost.get("warehouse")));
        return warehouses;
    }

    List<Shop> due(){
        // Enumerate in autocommit. Claim by primary key before the first
        // consistent read so a committed edit cannot fall behind the snapshot.
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Enumerate targeted cost scans before the calculation transaction");
        return repo.jdbc().queryForList("""
            SELECT organization_id,shop_id FROM finance_cost_target_scans ORDER BY next_at,organization_id,shop_id LIMIT 4
            """).stream().map(row->new Shop(text(row.get("organization_id")),text(row.get("shop_id")))).toList();
    }

    Result scanBatch(FinanceOrderCosts costs,List<Shop> due){
        for(var shop:due){
            var claimed=repo.jdbc().queryForList("""
                SELECT cursor_id,active_targets,next_targets FROM finance_cost_target_scans
                WHERE organization_id=? AND shop_id=? FOR UPDATE SKIP LOCKED
                """,shop.organization(),shop.id());
            if(!claimed.isEmpty())return scanShop(costs,shop,claimed.getFirst());
        }
        return null;
    }

    private Result scanShop(FinanceOrderCosts costs,Shop selected,Map<String,Object> job){
        String org=selected.organization(),shop=selected.id(),cursor=text(job.get("cursor_id"));
        if(!repo.writableShop(org,shop))return finish(org,shop);
        var active=targets(job.get("active_targets"));
        if(active.isEmpty())return finish(org,shop);
        String bound=active.values().stream().anyMatch(t->t.through().isBlank())?"":active.values().stream()
                .map(Target::through).max(String::compareTo).orElseThrow();
        var args=new ArrayList<Object>(List.of(org,shop,cursor));
        if(!bound.isBlank())args.add(bound);
        args.add(BATCH);
        var orders=repo.jdbc().queryForList("""
            SELECT o.id,o.record_date,o.platform,o.status,o.raw_json,c.data_json current_cost,
                j.order_record_id IS NOT NULL queued
            FROM (
              SELECT id FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
              WHERE organization_id=? AND shop_id=? AND kind='ORDER' AND id>?
            """+(bound.isBlank()?"":" AND id<=?")+" ORDER BY id LIMIT ?) ids "
            +"""
            JOIN finance_records o ON o.id=ids.id
            LEFT JOIN finance_order_costs c ON c.order_record_id=o.id AND c.organization_id=o.organization_id
            LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=o.id AND j.organization_id=o.organization_id AND j.priority<2
            ORDER BY o.id
            """,args.toArray());
        var candidates=new ArrayList<Candidate>();
        for(var order:orders){
            if(Set.of("UNPAID","CANCELLED","CANCELED").contains(text(order.get("status"))))continue;
            var raw=costs.decode(order.get("raw_json"));
            if(matches(order,raw,active))candidates.add(new Candidate(order,raw));
        }
        queueChangedOrders(costs,org,shop,candidates);

        if(orders.size()==BATCH){
            repo.jdbc().update("""
                UPDATE finance_cost_target_scans SET cursor_id=?,next_at=UTC_TIMESTAMP(6)
                WHERE organization_id=? AND shop_id=?
                """,orders.getLast().get("id"),org,shop);
            return new Result(org,false);
        }
        var next=targets(job.get("next_targets"));
        if(next.isEmpty())return finish(org,shop);
        repo.jdbc().update("""
            UPDATE finance_cost_target_scans SET cursor_id='',active_targets=?,next_targets=JSON_ARRAY(),
                next_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND shop_id=?
            """,writeTargets(next),org,shop);
        return new Result(org,false);
    }

    private void queueChangedOrders(FinanceOrderCosts costs,String org,String shop,List<Candidate> candidates){
        if(candidates.isEmpty())return;
        var context=costs.context(org,shop);
        if(context==null)return;
        var lookup=FinanceOrderCosts.lookup(context);
        var actual=new FinanceActualCosts(repo).forRecords(candidates.stream().map(c->text(c.order().get("id"))).toList());
        var changed=new ArrayList<Object[]>();
        for(var candidate:candidates){
            var order=candidate.order();
            String id=text(order.get("id"));
            if(actual.containsKey(id))continue;
            var previous=costs.decode(order.get("current_cost"));
            var current=FinanceOrderCosts.calculate(order,candidate.raw(),context,lookup);
            if(Boolean.TRUE.equals(order.get("queued"))||number(order.get("queued"))>0
                    ||!standardValue(previous).equals(standardValue(current)))
                changed.add(new Object[]{id,org,shop});
        }
        costs.insertRows("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id,priority) VALUES ",
                "(?,?,?,1)"," ON DUPLICATE KEY UPDATE priority=LEAST(priority,VALUES(priority))",changed);
    }

    private Result finish(String organization,String shop){
        repo.jdbc().update("DELETE FROM finance_cost_target_scans WHERE organization_id=? AND shop_id=?",organization,shop);
        return new Result(organization,true);
    }

    private static boolean matches(Map<String,Object> order,Map<String,Object> raw,Map<Sku,Target> active){
        String id=text(order.get("id"));
        if(applies(active.get(ALL_SKUS),id))return true;
        boolean tk="TIKTOK_SHOP".equals(order.get("platform"));
        for(var line:rows(raw.get(tk?"line_items":"item_list"))){
            var sku=new Sku(first(line,tk?"product_id":"item_id"),first(line,tk?"sku_id":"model_id"));
            if(applies(active.get(sku),id))return true;
        }
        return false;
    }

    private static boolean applies(Target target,String id){
        return target!=null&&(target.through().isBlank()||id.compareTo(target.through())<=0);
    }

    private static Object standardValue(Map<String,Object> cost){
        var product=new LinkedHashMap<>(cost);
        for(String key:List.of("bookId","labelCostCny","labelKnownCny","labelBasis","chargesPending","labelNote","labelRuleId","labelRuleVersion"))
            product.remove(key);
        if(product.containsKey("lines"))product.put("lines",rows(product.get("lines")).stream().map(line->{
            var value=new LinkedHashMap<>(line);
            value.remove("bookId");
            value.remove("priceSource");
            return value;
        }).toList());
        return comparable(product);
    }

    static Object comparable(Object value){
        if(value instanceof Number number)return Objects.requireNonNull(decimal(number)).stripTrailingZeros();
        if(value instanceof Map<?,?> map){
            var normalized=new LinkedHashMap<String,Object>();
            map.forEach((key,item)->normalized.put(text(key),comparable(item)));
            return normalized;
        }
        if(value instanceof List<?> list)return list.stream().map(FinanceCostTargetScans::comparable).toList();
        return value;
    }

    private Map<Sku,Target> targets(Object json){
        var result=new LinkedHashMap<Sku,Target>();
        for(var row:rows(repo.read("{\"targets\":"+text(json)+"}").get("targets"))){
            var sku=new Sku(text(row.get("product")),text(row.get("sku")));
            result.put(sku,new Target(sku,text(row.get("through"))));
        }
        return result;
    }

    private String writeTargets(Map<Sku,Target> targets){
        return repo.write(targets.values().stream().map(t->Map.of(
                "product",t.sku().product(),"sku",t.sku().sku(),"through",t.through())).toList());
    }

    private static void merge(Map<Sku,Target> targets,Target target){
        targets.merge(target.sku(),target,(left,right)->left.through().isBlank()
                ||!right.through().isBlank()&&left.through().compareTo(right.through())>=0?left:right);
    }

    static boolean pending(FinanceRepository repo,Scope scope,List<?> shops){
        if(shops.isEmpty())return false;
        String slots=String.join(",",Collections.nCopies(shops.size(),"?"));
        var args=new ArrayList<Object>(List.of(scope.organizationId()));
        args.addAll(shops);
        return Boolean.TRUE.equals(repo.jdbc().queryForObject("""
            SELECT EXISTS (SELECT 1 FROM finance_cost_target_scans WHERE organization_id=? AND shop_id IN (
            """+slots+"))",Boolean.class,args.toArray()));
    }
}
