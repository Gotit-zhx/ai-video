package com.yingdao.aicommerce.finance;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Brief, bounded views of the existing order SKU sample. A token never grants shop access. */
final class FinanceCostSkuPages {
    private static final int PAGE_SIZE=12, MAX_VIEWS=6, MAX_SKUS=24_000;
    private static final long TTL=Duration.ofMinutes(2).toNanos();
    record Shop(String organization,String owner,String id,String group){}
    record Source(List<Map<String,Object>> products,Map<String,Object> mappings,List<Map<String,Object>> prices,
                  List<Map<String,Object>> manualPrices,Map<String,Object> metadata){}
    private record View(Shop shop,long expires,Map<String,List<Map<String,Object>>> products,
                        Map<String,List<Map.Entry<String,Object>>> mappings,Map<String,Set<String>> codes,
                        Set<String> manualPrices,Map<String,Object> metadata,int size){}
    private record Group(List<Map<String,Object>> products,boolean pending){}
    private final Map<String,View> views=new LinkedHashMap<>();

    Map<String,Object> page(Shop shop,String token,int requested,String search,String status,String warehouse,Supplier<Source> read){
        if(search.length()>255||warehouse.length()>120||!Set.of("","good","danger","warn").contains(status))
            throw bad("SKU 筛选条件不正确");
        var view=get(shop,token);
        if(view==null){
            token=UUID.randomUUID().toString();
            view=prepare(shop,read.get());
            remember(token,view);
        }
        String needle=search.trim().toLowerCase(Locale.ROOT);
        var codes=view.codes().getOrDefault(warehouse,Set.of());
        var normalized=new HashMap<String,Set<String>>();
        for(String code:codes)normalized.computeIfAbsent(FinanceCostWorkspace.normalizedSku(code),k->new HashSet<>()).add(code);
        var matches=new ArrayList<Group>();var matchingSkus=new HashSet<String>();int linked=0;
        for(var specs:view.products().values()){
            boolean found=false,pending=false;
            for(var sku:specs){
                String tone=tone(view,sku,warehouse,codes,normalized);
                if("good".equals(tone))linked++;
                else pending=true;
                if((status.isBlank()||status.equals(tone))&&searches(sku,needle)){
                    found=true;matchingSkus.add(identity(shop,sku));
                }
            }
            if(found)matches.add(new Group(specs,pending));
        }
        matches.sort(Comparator.comparing(Group::pending).reversed());
        int page=Math.max(1,Math.min(requested,Math.max(1,(matches.size()+PAGE_SIZE-1)/PAGE_SIZE)));
        int from=(page-1)*PAGE_SIZE;
        var results=new ArrayList<Map<String,Object>>();
        var mappings=new LinkedHashMap<String,Object>();
        for(var group:matches.subList(from,Math.min(from+PAGE_SIZE,matches.size()))){
            for(var row:group.products()){
                String id=identity(shop,row);
                var item=new LinkedHashMap<>(row);
                item.put("matches_filter",matchingSkus.contains(id));results.add(item);
                for(var entry:view.mappings().getOrDefault(id,List.of()))
                    mappings.put(entry.getKey(),entry.getValue());
            }
        }
        var result=new LinkedHashMap<>(view.metadata());
        result.put("snapshot",token);result.put("page",page);result.put("total",matches.size());
        result.put("skuCount",view.size());result.put("linkedSkuCount",linked);
        result.put("products",results);result.put("mappings",mappings);
        return result;
    }

    private static View prepare(Shop shop,Source source){
        var products=new LinkedHashMap<String,List<Map<String,Object>>>();
        var keys=new HashSet<String>();
        for(var row:source.products()){
            products.computeIfAbsent(text(row.get("product_id")),ignored->new ArrayList<>()).add(row);
            keys.add(identity(shop,row));
        }
        var mappings=new HashMap<String,List<Map.Entry<String,Object>>>();
        for(var mapping:source.mappings().entrySet()){
            String suffix=":"+text(map(mapping.getValue()).get("warehouse"));
            String key=mapping.getKey();
            if(!key.endsWith(suffix))continue;
            String prefix=key.substring(0,key.length()-suffix.length());
            if(keys.contains(prefix))mappings.computeIfAbsent(prefix,ignored->new ArrayList<>()).add(mapping);
        }
        var codes=new HashMap<String,Set<String>>();
        for(var cost:source.prices())codes.computeIfAbsent(text(cost.get("warehouse")),ignored->new HashSet<>()).add(text(cost.get("sku")));
        var manual=new HashSet<String>();
        for(var cost:source.manualPrices())manual.add(manualPriceKey(text(cost.get("id")),text(cost.get("warehouse"))));
        var metadata=new LinkedHashMap<>(source.metadata());metadata.remove("products");metadata.remove("mappings");
        return new View(shop,System.nanoTime()+TTL,products,mappings,codes,manual,metadata,source.products().size());
    }

    private static String tone(View view,Map<String,Object> sku,String warehouse,Set<String> codes,Map<String,Set<String>> normalized){
        if(FinanceOrderCosts.giftSku(sku.get("seller_sku")))return "good";
        var saved=view.mappings().getOrDefault(identity(view.shop(),sku),List.of()).stream()
                .map(entry->map(entry.getValue()))
                .filter(mapping->view.shop().group().equals(text(mapping.get("groupId"))))
                .toList();
        if(warehouse.isBlank()){
            if(saved.stream().map(mapping->text(mapping.get("code"))).distinct().count()==1){
                var first=saved.getFirst();
                if(!FinanceCostWorkspace.automaticMethod(text(first.get("method")))){
                    String manual=text(first.get("manualCostId")),code=text(first.get("code"));
                    if(manual.isBlank()?view.codes().values().stream().anyMatch(available->available.contains(code)):
                            view.manualPrices().stream().anyMatch(price->price.startsWith(manual+"\0")))return "good";
                }
            }
            return "danger";
        }
        for(var entry:saved){
            if(!warehouse.equals(text(entry.get("warehouse")))||FinanceCostWorkspace.automaticMethod(text(entry.get("method"))))continue;
            String manual=text(entry.get("manualCostId"));
            return (manual.isBlank()?codes.contains(text(entry.get("code"))):
                    view.manualPrices().contains(manualPriceKey(manual,warehouse)))?"good":"danger";
        }
        String seller=text(sku.get("seller_sku")).trim(), skuId=text(sku.get("sku_id")).trim();
        if(FinanceCostWorkspace.matchProduct(seller,skuId,codes,normalized)!=null)return "good";
        return ambiguous(seller,normalized)||normalized.getOrDefault(FinanceCostWorkspace.normalizedSku(skuId),Set.of()).size()>1?"warn":"danger";
    }

    private static boolean ambiguous(String code,Map<String,Set<String>> normalized){
        String key=FinanceCostWorkspace.normalizedSku(code);
        if(normalized.getOrDefault(key,Set.of()).size()>1)return true;
        int bundle=key.indexOf('*');
        return bundle>0&&key.substring(bundle+1).matches("[1-9][0-9]{0,5}")
                &&normalized.getOrDefault(key.substring(0,bundle),Set.of()).size()>1;
    }

    private static String manualPriceKey(String id,String warehouse){return id+"\0"+warehouse;}
    private static String identity(Shop shop,Map<String,Object> row){return shop.id()+":"+row.get("product_id")+":"+row.get("sku_id");}
    private static boolean searches(Map<String,Object> row,String needle){
        if(needle.isBlank())return true;
        return String.join(" ",List.of("product_id","sku_id","seller_sku","name","spec").stream().map(key->text(row.get(key))).toList())
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    private synchronized View get(Shop shop,String token){
        removeExpired();
        var view=views.get(token);
        return view!=null&&shop.equals(view.shop())?view:null;
    }
    private synchronized void remember(String token,View view){
        removeExpired();
        int current=views.values().stream().mapToInt(View::size).sum();
        while(!views.isEmpty()&&(views.size()>=MAX_VIEWS||current+view.size()>MAX_SKUS)){
            var first=views.keySet().iterator().next();current-=views.remove(first).size();
        }
        views.put(token,view);
    }
    private void removeExpired(){long now=System.nanoTime();views.values().removeIf(view->now-view.expires()>=0);}
}
