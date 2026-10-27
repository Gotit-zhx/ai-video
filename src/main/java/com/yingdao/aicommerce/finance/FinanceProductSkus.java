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
    /** Display-only identities from the original order. Never use a platform ID as a merchant cost code. */
    static List<Map<String,Object>> identities(String platform,Map<String,Object> raw){
        if(!Set.of("SHOPEE","TIKTOK_SHOP").contains(platform))return List.of();
        boolean shopee=platform.equals("SHOPEE");var result=new ArrayList<Map<String,Object>>();
        for(var item:rows(raw.get(shopee?"item_list":"line_items"))){
            var value=new LinkedHashMap<String,Object>();
            String seller=displayText(item.get(shopee?"model_sku":"seller_sku"),255);
            if(shopee&&seller.isBlank())seller=displayText(item.get("item_sku"),255);
            value.put("sellerSku",seller);
            value.put("productId",displayText(item.get(shopee?"item_id":"product_id"),128));
            value.put("skuId",displayText(item.get(shopee?"model_id":"sku_id"),128));
            value.put("name",displayText(item.get(shopee?"item_name":"product_name"),500));
            value.put("spec",displayText(item.get(shopee?"model_name":"sku_name"),500));
            value.put("image",displayText(shopee?at(item,"image_info.image_url"):item.get("sku_image"),1500));
            value.put("quantity",decimal(item.get(shopee?"model_quantity_purchased":"quantity")));
            String line=displayText(item.get("id"),128);if(line.isBlank())line=displayText(item.get("line_item_id"),128);
            value.put("lineId",line);result.add(value);
        }
        return result;
    }
    private static String displayText(Object value,int limit){
        String text=value instanceof String||value instanceof Number?text(value).trim():"";
        return text.substring(0,Math.min(limit,text.length()));
    }
    static String missingIdentity(Map<String,Object> item){
        var parts=new ArrayList<String>();
        if(!text(item.get("name")).isBlank())parts.add(text(item.get("name")));
        if(!text(item.get("spec")).isBlank())parts.add("规格："+item.get("spec"));
        if(!text(item.get("skuId")).isBlank())parts.add("平台 SKU："+item.get("skuId"));
        if(!text(item.get("productId")).isBlank())parts.add("SPU："+item.get("productId"));
        parts.add("商家 SKU 未提供");return String.join(" · ",parts);
    }
    /** Only enrich the requested page. No per-row queries or whole-history JSON scan. */
    public void attach(Scope scope,List<Map<String,Object>> records){
        for(int start=0;start<records.size();start+=100)page(scope,records.subList(start,Math.min(start+100,records.size())));
    }
    private void page(Scope scope,List<Map<String,Object>> records){
        var orders=new LinkedHashMap<OrderKey,SortedSet<String>>();
        var statuses=new HashMap<OrderKey,String>();
        var types=new HashMap<OrderKey,String>();
        var times=new HashMap<OrderKey,String>();
        var billTimes=new HashMap<String,SortedSet<String>>();
        var bills=new LinkedHashMap<String,SortedSet<String>>();
        var shopIds=records.stream().map(r->text(r.get("shop_id"))).filter(v->!v.isBlank()).distinct().toList();
        if(!shopIds.isEmpty()){
            var args=new ArrayList<Object>();args.add(scope.organizationId());args.addAll(shopIds);
            var zones=new HashMap<String,String>();
            for(var row:repo.jdbc().queryForList("SELECT id,timezone FROM finance_connections WHERE organization_id=? AND id IN ("
                    +String.join(",",Collections.nCopies(shopIds.size(),"?"))+")",args.toArray()))zones.put(text(row.get("id")),text(row.get("timezone")));
            for(var row:records)row.put("order_timezone",zones.getOrDefault(text(row.get("shop_id")),"UTC"));
        }
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
            for(var row:repo.jdbc().queryForList("SELECT o.shop_id,o.order_id,o.status related_order_status,JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.create_time')) order_created_time,JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.order_type')) order_type,JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.is_sample_order')) is_sample_order,"+SKU+" sku FROM finance_records o "+ITEMS.replace("JOIN JSON_TABLE","LEFT JOIN JSON_TABLE")+" ON TRUE "
                    +" WHERE o.organization_id=? AND o.kind='ORDER' AND o.source='API' AND ("+where+")",args.toArray())){
                String code=text(row.get("sku")).trim();
                var key=new OrderKey(text(row.get("shop_id")),text(row.get("order_id")));
                String time=FinanceFields.orderTime(row.get("order_created_time"));if(time!=null)times.put(key,time);
                statuses.put(key,text(row.get("related_order_status")));
                types.put(key,FinanceFields.orderType(text(row.get("order_type")),text(row.get("is_sample_order"))));
                if(!code.isBlank())orders.get(key).add(code);
            }
        }
        if(!bills.isEmpty()){
            var args=new ArrayList<Object>();args.add(scope.organizationId());args.addAll(bills.keySet());
            String slots=String.join(",",Collections.nCopies(bills.size(),"?"));
            for(var row:repo.jdbc().queryForList("""
                SELECT r.id record_id,JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.create_time')) order_created_time,%s sku FROM finance_records r
                JOIN finance_records t ON t.organization_id=r.organization_id AND t.shop_id=r.shop_id
                  AND t.kind='SETTLEMENT' AND t.source='API' AND t.statement_id=r.external_id
                JOIN finance_records o ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
                  AND o.kind='ORDER' AND o.source='API' AND o.order_id=t.order_id
                %s ON TRUE WHERE r.organization_id=? AND r.kind='BILL' AND r.id IN (%s)
                """.formatted(SKU,ITEMS.replace("JOIN JSON_TABLE","LEFT JOIN JSON_TABLE"),slots),args.toArray())){
                String time=FinanceFields.orderTime(row.get("order_created_time"));
                if(time!=null)billTimes.computeIfAbsent(text(row.get("record_id")),key->new TreeSet<>()).add(time);
                String code=text(row.get("sku")).trim();
                if(!code.isBlank())bills.get(text(row.get("record_id"))).add(code);
            }
        }
        for(var row:records){
            String time=times.get(new OrderKey(text(row.get("shop_id")),text(row.get("order_id"))));
            if(time!=null)row.put("order_created_time",time);
            var range=billTimes.get(text(row.get("id")));
            if(range!=null&&!range.isEmpty()){row.put("order_created_time",range.first());row.put("order_created_time_end",range.last());}
            var codes="BILL".equals(row.get("kind"))?bills.get(text(row.get("id"))):orders.get(new OrderKey(text(row.get("shop_id")),text(row.get("order_id"))));
            if(codes!=null)put(row,codes);
            String status=statuses.get(new OrderKey(text(row.get("shop_id")),text(row.get("order_id"))));
            String type=types.get(new OrderKey(text(row.get("shop_id")),text(row.get("order_id"))));
            if(type!=null&&!type.isBlank())row.put("order_type_label",type);
            if(status!=null&&!status.isBlank()){
                row.put("related_order_status",status);
                if("SETTLEMENT".equals(row.get("kind"))&&"API".equals(row.get("source"))
                    &&Set.of("CANCELLED","CANCELED").contains(status)&&decimal(row.get("amount"))!=null&&decimal(row.get("amount")).signum()==0)
                    row.put("settlement_amount_note","已取消订单，平台明确返回结算金额 0");
                else if("SETTLEMENT".equals(row.get("kind"))&&"API".equals(row.get("source"))&&type!=null&&type.contains("样品单")
                    &&decimal(row.get("amount"))!=null&&decimal(row.get("amount")).signum()<0)
                    row.put("settlement_amount_note",type+"，平台扣费形成负结算");
            }
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
            expenses.get(i).put("order_created_time",references.get(i).get("order_created_time"));
            expenses.get(i).put("order_timezone",references.get(i).get("order_timezone"));
        }
    }
}
