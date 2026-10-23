package com.yingdao.aicommerce.finance;

import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Original merchant SKUs from the related orders, independent of cost configuration. */
public final class FinanceProductSkus {
    private final FinanceRepository repo;
    public FinanceProductSkus(FinanceRepository repo){this.repo=repo;}
    private record OrderKey(String shop,String order){}
    static final String ITEMS="""
        JOIN JSON_TABLE(COALESCE(CASE o.platform
          WHEN 'TIKTOK_SHOP' THEN JSON_EXTRACT(o.raw_json,'$.line_items')
          WHEN 'SHOPEE' THEN JSON_EXTRACT(o.raw_json,'$.item_list') END,JSON_ARRAY()),
          '$[*]' COLUMNS(seller_sku TEXT PATH '$.seller_sku',model_sku TEXT PATH '$.model_sku',item_sku TEXT PATH '$.item_sku')) sk
        """;
    static final String SKU="TRIM(CASE WHEN o.platform='TIKTOK_SHOP' THEN sk.seller_sku ELSE COALESCE(NULLIF(TRIM(sk.model_sku),''),sk.item_sku) END)";
    static SortedSet<String> fromOrder(String platform,Map<String,Object> raw){
        var codes=new TreeSet<String>();
        if(!Set.of("SHOPEE","TIKTOK_SHOP").contains(platform))return codes;
        for(var item:rows(raw.get(platform.equals("SHOPEE")?"item_list":"line_items"))){
            String code=platform.equals("SHOPEE")?text(item.get("model_sku")).trim():text(item.get("seller_sku")).trim();
            if(code.isBlank()&&platform.equals("SHOPEE"))code=text(item.get("item_sku")).trim();
            if(!code.isBlank())codes.add(code);
        }
        return codes;
    }
    static void put(Map<String,Object> row,Collection<String> codes){
        row.put("product_sku_values",List.copyOf(codes));
        row.put("product_skus",String.join("\n",codes));
    }
    /** Only enrich the requested page. No per-row queries or whole-history JSON scan. */
    public void attach(Scope scope,List<Map<String,Object>> records){
        for(int start=0;start<records.size();start+=100)page(scope,records.subList(start,Math.min(start+100,records.size())));
    }
    private void page(Scope scope,List<Map<String,Object>> records){
        var orders=new LinkedHashMap<OrderKey,SortedSet<String>>();
        var bills=new LinkedHashMap<String,SortedSet<String>>();
        for(var row:records){
            if("ORDER".equals(row.get("kind")))continue;
            put(row,List.of());
            if("BILL".equals(row.get("kind"))&&!text(row.get("id")).isBlank())bills.put(text(row.get("id")),new TreeSet<>());
            else if(Set.of("REFUND","SETTLEMENT","PENDING","FEE","COST").contains(text(row.get("kind")))){
                String shop=text(row.get("shop_id")),order=text(row.get("order_id"));
                if(!shop.isBlank()&&!order.isBlank())orders.putIfAbsent(new OrderKey(shop,order),new TreeSet<>());
            }
        }
        if(!orders.isEmpty()){
            var args=new ArrayList<Object>();args.add(scope.organizationId());
            orders.keySet().forEach(key->{args.add(key.shop());args.add(key.order());});
            String where=String.join(" OR ",Collections.nCopies(orders.size(),"(o.shop_id=? AND o.order_id=?)"));
            for(var row:repo.jdbc().queryForList("SELECT o.shop_id,o.order_id,"+SKU+" sku FROM finance_records o "+ITEMS
                    +" WHERE o.organization_id=? AND o.kind='ORDER' AND o.source='API' AND ("+where+")",args.toArray())){
                String code=text(row.get("sku")).trim();
                if(!code.isBlank())orders.get(new OrderKey(text(row.get("shop_id")),text(row.get("order_id")))).add(code);
            }
        }
        if(!bills.isEmpty()){
            var args=new ArrayList<Object>();args.add(scope.organizationId());args.addAll(bills.keySet());
            String slots=String.join(",",Collections.nCopies(bills.size(),"?"));
            for(var row:repo.jdbc().queryForList("""
                SELECT r.id record_id,%s sku FROM finance_records r
                JOIN finance_records t ON t.organization_id=r.organization_id AND t.shop_id=r.shop_id
                  AND t.kind='SETTLEMENT' AND t.source='API' AND t.statement_id=r.external_id
                JOIN finance_records o ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
                  AND o.kind='ORDER' AND o.source='API' AND o.order_id=t.order_id
                %s WHERE r.organization_id=? AND r.kind='BILL' AND r.id IN (%s)
                """.formatted(SKU,ITEMS,slots),args.toArray())){
                String code=text(row.get("sku")).trim();
                if(!code.isBlank())bills.get(text(row.get("record_id"))).add(code);
            }
        }
        for(var row:records){
            var codes="BILL".equals(row.get("kind"))?bills.get(text(row.get("id"))):orders.get(new OrderKey(text(row.get("shop_id")),text(row.get("order_id"))));
            if(codes!=null)put(row,codes);
        }
    }
    public void attachExpenses(Scope scope,List<? extends Map<String,Object>> expenses){
        var references=new ArrayList<Map<String,Object>>();
        for(var row:expenses)references.add(new LinkedHashMap<>(Map.of("kind","COST","shop_id","order".equals(row.get("scope"))?text(row.get("target")):"",
                "order_id",text(row.get("order")))));
        attach(scope,references);
        for(int i=0;i<expenses.size();i++){
            expenses.get(i).put("product_skus",references.get(i).get("product_skus"));
            expenses.get(i).put("product_sku_values",references.get(i).get("product_sku_values"));
        }
    }
}
