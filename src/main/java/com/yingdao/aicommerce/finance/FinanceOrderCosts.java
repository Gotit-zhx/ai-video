package com.yingdao.aicommerce.finance;

import java.math.*;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Merchant-SKU-first, platform-SKU-fallback standard costs, separate from actual warehouse accounting. */
public final class FinanceOrderCosts {
    static final String POLICY_VERSION="sku-platform-sku-standard-v1";
    final FinanceRepository repo;
    public FinanceOrderCosts(FinanceRepository repo){this.repo=repo;}
    public record Context(String shopId,String groupId,String region,String timezone,String bookId,
                          List<Map<String,Object>> costs,Map<String,Object> settings,Map<String,Object> mappings){}
    record CatalogKey(String organization,String group){}
    record Catalog(String id,List<Map<String,Object>> costs){}
    record Lookup(String warehouse,Map<String,Map<String,Object>> codes,Map<String,Set<String>> normalized,
                  Set<String> allCodes,Map<String,List<Map<String,Object>>> explicitBySku){}
    static boolean giftSku(Object sku){return "GIFT".equalsIgnoreCase(text(sku).trim());}
    static boolean hasGift(Map<String,Object> raw){
        return FinanceProductSkus.fromOrder("TIKTOK_SHOP",raw).stream().anyMatch(FinanceOrderCosts::giftSku)
            ||FinanceProductSkus.fromOrder("SHOPEE",raw).stream().anyMatch(FinanceOrderCosts::giftSku);
    }
    static void fixedGift(Map<String,Object> line){
        line.remove("issue");
        line.putAll(Map.of("costCode","GIFT","factor",BigDecimal.ONE,"unitPriceCny",BigDecimal.ZERO,
            "amountCny",BigDecimal.ZERO,"priceMonth","固定零成本","previousPrice",false,
            "fixedGift",true,"costSource","GIFT 赠品固定零成本"));
    }
    static String resolveWarehouse(Context context){
        return resolveWarehouse(context.region(),context.costs(),context.settings());
    }
    static String resolveWarehouse(String region,List<Map<String,Object>> costs,Map<String,Object> settings){
        String explicit=text(settings.get("warehouse"));
        if(!explicit.isBlank())return explicit;
        var warehouses=new LinkedHashSet<String>();
        for(var c:costs)warehouses.add(text(c.get("warehouse")));
        var names=switch(region.toUpperCase(Locale.ROOT)){
            case "PH"->List.of("菲律宾");case "TH"->List.of("泰国");case "MY"->List.of("马来");
            case "VN"->List.of("越南");case "SG"->List.of("新加坡");case "ID"->List.of("印尼","印度尼西亚");default->List.<String>of();
        };
        var matched=warehouses.stream().filter(w->names.stream().anyMatch(w::contains)).toList();
        if(matched.size()==1)return matched.getFirst();
        return warehouses.size()==1?warehouses.iterator().next():"";
    }
    static Object shippingTime(Map<String,Object> raw){
        for(String key:List.of("collection_time","shipped_time","ship_time"))if(number(raw.get(key))>0)return raw.get(key);
        return null;
    }
    static Map<String,Object> calculate(Map<String,Object> order,Map<String,Object> raw,Context context){
        return calculate(order,raw,context,null);
    }
    static Lookup lookup(Context context){
        String warehouse=resolveWarehouse(context);
        var codes=new LinkedHashMap<String,Map<String,Object>>();var normalized=new LinkedHashMap<String,Set<String>>();
        var allCodes=new LinkedHashSet<String>();
        for(var cost:context.costs()){
            if(!text(cost.get("manualId")).isBlank())continue;
            String code=text(cost.get("sku"));allCodes.add(code);
            normalized.computeIfAbsent(FinanceCostWorkspace.normalizedSku(code),k->new LinkedHashSet<>()).add(code);
            if(warehouse.equals(text(cost.get("warehouse"))))codes.put(code,cost);
        }
        var explicitBySku=new HashMap<String,List<Map<String,Object>>>();
        for(var entry:context.mappings().entrySet()){
            var mapping=map(entry.getValue());String suffix=":"+text(mapping.get("warehouse"));
            if(context.groupId().equals(text(mapping.get("groupId")))&&!text(mapping.get("manualCostId")).isBlank()&&entry.getKey().endsWith(suffix))
                explicitBySku.computeIfAbsent(entry.getKey().substring(0,entry.getKey().length()-suffix.length()),k->new ArrayList<>()).add(mapping);
        }
        return new Lookup(warehouse,codes,normalized,allCodes,explicitBySku);
    }
    static Map<String,Object> calculate(Map<String,Object> order,Map<String,Object> raw,Context context,Lookup prepared){
        String status=text(order.get("status")),warehouse=prepared==null?resolveWarehouse(context):prepared.warehouse();
        var rules=map(context.settings().get("rules"));
        boolean excluded=Set.of("UNPAID","CANCELLED","CANCELED").contains(status);
        var out=new LinkedHashMap<String,Object>();
        out.put("currency","CNY");out.put("basis","MERCHANT_SKU_STANDARD");out.put("warehouse",warehouse);out.put("policyVersion",POLICY_VERSION);
        out.put("bookId",context.bookId());out.put("dateBasis",text(rules.getOrDefault("basis","order")));
        out.put("lines",new ArrayList<Map<String,Object>>());
        if(excluded){out.putAll(Map.of("status","EXCLUDED","amountCny",BigDecimal.ZERO,"knownAmountCny",BigDecimal.ZERO,"lineCount",0,"matchedLines",0,"fallbackLines",0,"note","未付款或取消订单，不计标准成本"));return out;}
        String month="";
        try{
            Object basis="shipment".equals(out.get("dateBasis"))?shippingTime(raw):order.get("record_date");
            if(basis!=null)month=date(basis,context.timezone()).toString().substring(0,7);
        }catch(RuntimeException ignored){}
        out.put("costMonth",month);
        boolean tk="TIKTOK_SHOP".equals(order.get("platform"));
        var items=rows(raw.get(tk?"line_items":"item_list"));var lines=new ArrayList<Map<String,Object>>();
        var index=prepared==null?lookup(context):prepared;
        var codes=index.codes();var normalized=index.normalized();var allCodes=index.allCodes();var explicitBySku=index.explicitBySku();
        BigDecimal known=BigDecimal.ZERO;int matched=0,fallback=0;
        var seen=new HashMap<String,String>();
        for(var item:items){
            String lineId=first(item,"id","line_item_id"),product=first(item,tk?"product_id":"item_id"),sku=first(item,tk?"sku_id":"model_id");
            String seller=first(item,tk?"seller_sku":"model_sku",tk?"seller_sku":"item_sku").trim();
            BigDecimal quantity=amount(item,tk?"quantity":"model_quantity_purchased");
            // TikTok supplies one uniquely identified order line per purchased unit when no quantity field is present.
            if(tk&&quantity==null&&!lineId.isBlank())quantity=BigDecimal.ONE;
            var line=new LinkedHashMap<String,Object>();line.put("lineId",lineId);line.put("productId",product);line.put("skuId",sku);
            line.put("sellerSku",seller);line.put("quantity",quantity);line.put("warehouse",warehouse);
            String lineStatus=first(item,"display_status","status");
            String signature=seller+"\0"+product+"\0"+sku+"\0"+quantity+"\0"+lineStatus+"\0"+first(item,"package_status");
            if(!lineId.isBlank()){
                String previous=seen.putIfAbsent(lineId,signature);
                if(previous!=null){if(previous.equals(signature))continue;line.put("issue","平台商品行重复且内容不一致");lines.add(line);continue;}
            }
            lines.add(line);
            // This report excludes cancelled sales regardless of dispatch. Actual logistics fees stay in the platform ledger.
            if(Set.of("UNPAID","CANCELLED","CANCELED").contains(lineStatus)){line.put("amountCny",BigDecimal.ZERO);line.put("excluded",true);matched++;continue;}
            // Explicit merchant-SKU rule: no catalogue, warehouse, month or manual mapping is needed.
            if(giftSku(seller)){fixedGift(line);matched++;continue;}
            if(quantity==null||quantity.signum()<=0||quantity.stripTrailingZeros().scale()>0||quantity.compareTo(new BigDecimal("1000000"))>0){line.put("issue","缺少有效购买数量");continue;}
            if(month.isBlank()){line.put("issue","缺少所选成本口径的业务日期");continue;}
            String mappingKey=context.shopId()+":"+product+":"+sku+":"+warehouse;
            var saved=map(context.mappings().get(mappingKey));
            // An explicit standalone price stays pinned even if an Excel change alters warehouse inference.
            var explicit=explicitBySku.getOrDefault(context.shopId()+":"+product+":"+sku,List.of());
            boolean selectedExplicit=!saved.isEmpty()&&!FinanceCostWorkspace.automaticMethod(text(saved.get("method")))
                &&context.groupId().equals(text(saved.get("groupId")));
            if(!selectedExplicit&&!explicit.isEmpty()){
                if(explicit.size()!=1){line.put("issue","手动成本存在多个成本仓，请配置明确的成本仓");continue;}
                saved=explicit.getFirst();
            }
            String lineWarehouse=!text(saved.get("manualCostId")).isBlank()&&context.groupId().equals(text(saved.get("groupId")))?text(saved.get("warehouse")):warehouse;
            line.put("warehouse",lineWarehouse);
            if(lineWarehouse.isBlank()){line.put("issue","请配置成本仓，无法唯一匹配店铺国家");continue;}
            boolean manual=!saved.isEmpty()&&!FinanceCostWorkspace.automaticMethod(text(saved.get("method")))
                    &&context.groupId().equals(text(saved.get("groupId")));
            var automatic=FinanceCostWorkspace.matchProduct(seller,sku,codes.keySet(),normalized);
            String code=manual?text(saved.get("code")):automatic==null?"":automatic.code();
            BigDecimal factor=manual?decimal(saved.get("factor")):automatic!=null&&automatic.bundleQuantity()!=null?BigDecimal.valueOf(automatic.bundleQuantity()):BigDecimal.ONE;
            String manualId=manual?text(saved.get("manualCostId")):"";
            Map<String,Object> selected;
            if(!manualId.isBlank()){
                selected=context.costs().stream().filter(c->manualId.equals(text(c.get("manualId")))&&lineWarehouse.equals(text(c.get("warehouse")))).findFirst().orElse(Map.of());
                if(selected.isEmpty()){line.put("issue","关联的手动成本已删除，请重新核对");continue;}
                code=text(selected.get("sku"));
            }else{
                if(context.costs().isEmpty()){line.put("issue","店铺分组尚未配置成本资料");continue;}
                if(code.isBlank()){
                    var elsewhere=FinanceCostWorkspace.matchProduct(seller,sku,allCodes,normalized);
                    line.put("issue",elsewhere!=null?"商品 SKU 与平台 SKU 均未匹配到 "+lineWarehouse+" 的成本；其他仓仅找到 "+elsewhere.code()+"，不能自动使用其他国家成本，请补齐本仓成本或人工确认":
                        "商品 SKU 与平台 SKU 均未匹配到唯一成本型号，请人工确认");continue;
                }
                if(!codes.containsKey(code)){line.put("issue","成本表缺少 "+lineWarehouse+" 的 "+code+" 成本，不能自动使用其他国家成本");continue;}
                selected=codes.get(code);
            }
            line.put("matchedBy",manual?"MANUAL":automatic!=null&&automatic.method().equals("PLATFORM_SKU_CODE")?"PLATFORM_SKU":"SKU");
            if(factor==null||factor.signum()<=0){line.put("issue","人工关联缺少有效换算数量");continue;}
            var prices=map(selected.get("prices"));String priceMonth=manualId.isBlank()?month:"固定单价";
            BigDecimal price=decimal(manualId.isBlank()?prices.get(month):selected.get("unitPriceCny"));
            if(manualId.isBlank()&&price==null&&!"pending".equals(text(rules.getOrDefault("missing","previous")))){
                final String targetMonth=month;
                priceMonth=prices.keySet().stream().filter(m->m.compareTo(targetMonth)<=0&&decimal(prices.get(m))!=null).max(String::compareTo).orElse("");
                price=decimal(prices.get(priceMonth));
            }
            line.put("costCode",code);line.put("factor",factor);line.put("priceMonth",priceMonth);line.put("bookId",context.bookId());line.put("unitPriceCny",price);
            if(!manualId.isBlank()){line.put("manualCostId",manualId);line.put("costSource","手动成本");line.put("unit",selected.get("unit"));}
            else if(map(selected.get("priceSources")).containsKey(priceMonth))line.put("priceSource",map(selected.get("priceSources")).get(priceMonth));
            if(price==null||price.signum()<0){line.put("issue","缺少 "+month+" 可用成本价");continue;}
            // Imported zeroes require explicit confirmation; do not silently treat blank/formula placeholders as free goods.
            if(manualId.isBlank()&&price.signum()==0&&!Boolean.TRUE.equals(rules.get("allowZero"))){line.put("issue","成本表为零值，需核对单价");continue;}
            BigDecimal value=price.multiply(quantity).multiply(factor).setScale(6,RoundingMode.HALF_UP);
            if(value.precision()-value.scale()>18){line.put("issue","成本金额超出支持范围");continue;}
            BigDecimal next=known.add(value);
            if(next.precision()-next.scale()>18){line.put("issue","订单成本合计超出支持范围");continue;}
            boolean previous=manualId.isBlank()&&!priceMonth.equals(month);line.put("previousPrice",previous);line.put("amountCny",value);
            known=known.add(value);matched++;if(previous)fallback++;
        }
        if(items.isEmpty())lines.add(new LinkedHashMap<>(Map.of("issue","平台尚未返回订单商品明细")));
        int total=lines.size();boolean complete=total>0&&matched==total;
        var lineWarehouses=lines.stream().map(l->text(l.get("warehouse"))).filter(w->!w.isBlank()).distinct().toList();
        if(!lineWarehouses.isEmpty())out.put("warehouse",String.join("、",lineWarehouses));
        boolean allExcluded=complete&&lines.stream().allMatch(line->Boolean.TRUE.equals(line.get("excluded")));
        out.put("lines",lines);out.put("status",allExcluded?"EXCLUDED":complete?(fallback>0?"ESTIMATED":"READY"):"INCOMPLETE");
        out.put("amountCny",complete?known:null);out.put("knownAmountCny",known);out.put("lineCount",total);
        out.put("matchedLines",matched);out.put("fallbackLines",fallback);
        out.put("note",allExcluded?"全部商品明细未付款或已取消，不计标准成本":complete?(fallback>0?"使用最近历史月份的标准成本估算":
            lines.stream().anyMatch(l->l.containsKey("manualCostId"))?"按已关联成本计算（含独立手动成本），金额为人民币":"按商品 SKU 优先、平台 SKU 兜底与有效购买数量统计标准成本，取消商品明细不计入"):"成本资料待补齐，不按零成本计算");
        return out;
    }
    Context context(String organization,String shopId){
        return context(organization,shopId,new HashMap<>());
    }
    Context context(String organization,String shopId,Map<CatalogKey,Catalog> catalogs){
        var shops=repo.jdbc().queryForList("SELECT group_id,region,timezone FROM finance_connections WHERE organization_id=? AND id=? AND status<>'REMOVED'",organization,shopId);
        if(shops.isEmpty())return null;var shop=shops.getFirst();String group=text(shop.get("group_id"));
        var catalog=catalogs.computeIfAbsent(new CatalogKey(organization,group),key->{
            var books=repo.jdbc().queryForList("SELECT id,data_json FROM finance_cost_books WHERE organization_id=? AND group_id=? ORDER BY created_at DESC,id DESC LIMIT 1",organization,group);
            return books.isEmpty()?new Catalog("",List.of()):new Catalog(text(books.getFirst().get("id")),FinanceCostSources.allCosts(decode(books.getFirst().get("data_json"))));
        });
        var settings=repo.jdbc().queryForList("SELECT data_json FROM finance_cost_settings WHERE organization_id=? AND shop_id=?",organization,shopId);
        var mappings=new LinkedHashMap<String,Object>();
        for(var m:repo.jdbc().queryForList("SELECT product_id,sku_id,warehouse,data_json FROM finance_cost_mappings WHERE organization_id=? AND shop_id=?",organization,shopId))
            mappings.put(shopId+":"+m.get("product_id")+":"+m.get("sku_id")+":"+m.get("warehouse"),decode(m.get("data_json")));
        String zone=text(shop.get("timezone"));if(zone.isBlank())zone=timezoneFor(text(shop.get("region")));
        return new Context(shopId,group,text(shop.get("region")),zone,catalog.id(),catalog.costs(),
                settings.isEmpty()?Map.of():decode(settings.getFirst().get("data_json")),mappings);
    }
    Context labelContext(String organization,String shopId){
        var shops=repo.jdbc().queryForList("SELECT group_id,region,timezone FROM finance_connections WHERE organization_id=? AND id=? AND status<>'REMOVED'",organization,shopId);
        if(shops.isEmpty())return null;var shop=shops.getFirst();
        return new Context(shopId,text(shop.get("group_id")),text(shop.get("region")),text(shop.get("timezone")),"",List.of(),Map.of(),Map.of());
    }
    Map<String,Object> decode(Object value){return value==null?Map.of():repo.read(value instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):text(value));}
    static Object canonical(Object value){
        if(value instanceof Map<?,?> m){var sorted=new TreeMap<String,Object>();m.forEach((k,v)->sorted.put(text(k),canonical(v)));return sorted;}
        if(value instanceof List<?> list)return list.stream().map(FinanceOrderCosts::canonical).toList();
        return value;
    }
    static String sourceHash(FinanceRepository repo,Map<String,Object> raw,String status){return hash(POLICY_VERSION+":"+(hasGift(raw)?"gift-zero-v1:":"cancelled-excluded-v2:")+repo.write(canonical(raw))+status);}
    static void enqueue(FinanceRepository repo,Scope s,Entry entry){
        if(!entry.kind().equals("ORDER"))return;
        // INSERT ... SELECT takes shared locks on its source rows in MySQL's
        // default isolation. Parallel order writers would then both try to
        // upgrade the same label-state lock when marking their arrivals dirty.
        // A normal consistent read avoids those locks; the job's primary key
        // still makes the following insert idempotent.
        boolean required=Boolean.TRUE.equals(repo.jdbc().queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM finance_order_costs WHERE order_record_id=? AND source_hash=?) AND EXISTS (
              SELECT 1 FROM finance_connections c WHERE c.organization_id=? AND c.id=? AND c.status<>'REMOVED' AND (
              ? OR EXISTS(SELECT 1 FROM finance_cost_books b WHERE b.organization_id=c.organization_id AND b.group_id=c.group_id)
              OR EXISTS(SELECT 1 FROM finance_label_order_totals l JOIN finance_label_state ls
                ON ls.organization_id=l.organization_id AND ls.active_generation=l.generation
                WHERE l.organization_id=c.organization_id AND l.shop_id=c.id)))
            """,Boolean.class,id(s,entry),sourceHash(repo,entry.raw(),entry.status()),s.organizationId(),entry.shopId(),hasGift(entry.raw())));
        if(!required)return;
        repo.jdbc().update("""
            INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE priority=0
            """,id(s,entry),s.organizationId(),entry.shopId());
    }
    void requestShop(String organization,String shopId){
        if(!repo.writableShop(organization,shopId))return;
        repo.jdbc().update("""
            INSERT INTO finance_cost_rebuilds(organization_id,shop_id) VALUES(?,?)
            ON DUPLICATE KEY UPDATE cursor_id='',status='READY',requested_at=UTC_TIMESTAMP(6)
            """,organization,shopId);
        repo.jdbc().update("UPDATE finance_order_cost_jobs SET priority=1 WHERE organization_id=? AND shop_id=? AND priority=2",organization,shopId);
        recordPolicyRequest(organization,shopId);
    }
    /** One small durable scheduling receipt per shop/policy, never per-order financial snapshots. */
    void recordPolicyRequest(String organization,String shopId){
        String id=hash("standard-cost-policy:"+POLICY_VERSION+":"+organization+":"+shopId).substring(0,32);
        int added=repo.jdbc().update("""
            INSERT IGNORE INTO finance_workspace_audit(id,organization_id,entity_type,entity_id,after_json,reason,actor_id)
            VALUES(?,?,'COST_POLICY_REBUILD',?,?,?,'SYSTEM')
            """,id,organization,shopId,repo.write(Map.of("policyVersion",POLICY_VERSION,"status","QUEUED")),"标准成本匹配策略已加入有界后台重算");
        if(added>0&&number(repo.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='finance_query_events'",Long.class))>0){
            String companyId=hash("standard-cost-policy-invalidation:"+POLICY_VERSION+":"+organization).substring(0,32);
            int first=repo.jdbc().update("""
                INSERT IGNORE INTO finance_workspace_audit(id,organization_id,entity_type,entity_id,after_json,reason,actor_id)
                VALUES(?,?,'COST_POLICY_INVALIDATE',?,?,?,'SYSTEM')
                """,companyId,organization,organization,repo.write(Map.of("policyVersion",POLICY_VERSION)),"财务投影的旧成本匹配策略已失效");
            if(first>0)repo.jdbc().update("INSERT INTO finance_query_events(organization_id,event_scope,source_table) VALUES(?,'ALL','finance_cost_policy')",organization);
        }
    }
    /** Finished old histories need an explicit pass even if no upstream order changes again. */
    void seedPolicyUpgrades(){
        var shops=repo.jdbc().queryForList("""
            SELECT c.organization_id,c.id FROM finance_connections c
            JOIN finance_cost_rebuilds r ON r.organization_id=c.organization_id AND r.shop_id=c.id
            WHERE c.status<>'REMOVED' AND r.status='DONE'
              AND EXISTS(SELECT 1 FROM finance_cost_books b WHERE b.organization_id=c.organization_id AND b.group_id=c.group_id)
              AND NOT EXISTS(SELECT 1 FROM finance_workspace_audit a WHERE a.id=LEFT(SHA2(CONCAT('standard-cost-policy:',?,':',c.organization_id,':',c.id),256),32))
            ORDER BY c.organization_id,c.id LIMIT 10
            """,POLICY_VERSION);
        for(var shop:shops){
            String organization=text(shop.get("organization_id")),id=text(shop.get("id"));
            // Lock only this primary-key row. Never reset another active scan's cursor.
            var current=repo.jdbc().queryForList("SELECT status FROM finance_cost_rebuilds WHERE organization_id=? AND shop_id=? FOR UPDATE SKIP LOCKED",organization,id);
            if(!current.isEmpty()&&"DONE".equals(current.getFirst().get("status")))requestShop(organization,id);
        }
    }
    void requestGroup(Scope s,String group){
        for(String shop:repo.jdbc().queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND group_id=? AND status<>'REMOVED'",String.class,s.organizationId(),group))
            requestShop(s.organizationId(),shop);
    }
    /** Label edits do not invalidate already calculated SKU costs. Full/live requests always win. */
    void requestLabelGroup(Scope s,String group){
        for(String shop:repo.jdbc().queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND group_id=? AND status<>'REMOVED'",String.class,s.organizationId(),group)){
            if(!repo.writableShop(s.organizationId(),shop))continue;
            repo.jdbc().update("""
                INSERT INTO finance_cost_rebuilds(organization_id,shop_id,status) VALUES(?,?,'LABELS')
                ON DUPLICATE KEY UPDATE cursor_id='',status=IF(status='READY','READY','LABELS'),requested_at=UTC_TIMESTAMP(6)
                """,s.organizationId(),shop);
        }
    }
    int seedAndFill(){
        var missing=repo.jdbc().queryForList("""
            SELECT c.organization_id,c.id FROM finance_connections c
            WHERE c.status<>'REMOVED' AND NOT EXISTS (
              SELECT 1 FROM finance_cost_rebuilds r WHERE r.organization_id=c.organization_id AND r.shop_id=c.id)
            AND EXISTS (
              SELECT 1 FROM finance_cost_books b WHERE b.organization_id=c.organization_id AND b.group_id=c.group_id)
            ORDER BY c.organization_id,c.id LIMIT 100
            """);
        for(var shop:missing)if(repo.writableShop(text(shop.get("organization_id")),text(shop.get("id")))){
            int added=repo.jdbc().update("INSERT IGNORE INTO finance_cost_rebuilds(organization_id,shop_id) VALUES(?,?)",shop.get("organization_id"),shop.get("id"));
            if(added>0)recordPolicyRequest(text(shop.get("organization_id")),text(shop.get("id")));
        }
        seedPolicyUpgrades();
        var jobs=repo.jdbc().queryForList("SELECT * FROM finance_cost_rebuilds WHERE status IN ('READY','LABELS') ORDER BY requested_at LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(jobs.isEmpty())return 0;var job=jobs.getFirst();
        if(!repo.writableShop(text(job.get("organization_id")),text(job.get("shop_id")))){
            repo.jdbc().update("DELETE FROM finance_cost_rebuilds WHERE organization_id=? AND shop_id=?",job.get("organization_id"),job.get("shop_id"));
            return 0;
        }
        var ids=repo.jdbc().queryForList("""
            SELECT id FROM finance_records WHERE organization_id=? AND shop_id=? AND kind='ORDER'
            AND id>? ORDER BY id LIMIT 500
            """,String.class,job.get("organization_id"),job.get("shop_id"),job.get("cursor_id"));
        if(!ids.isEmpty()){
            int priority="LABELS".equals(job.get("status"))?2:1;
            insertRows("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id,priority) VALUES ",
                    "(?,?,?,?)"," ON DUPLICATE KEY UPDATE priority=LEAST(priority,VALUES(priority))",
                    ids.stream().map(id->new Object[]{id,job.get("organization_id"),job.get("shop_id"),priority}).toList());
            repo.jdbc().update("UPDATE finance_cost_rebuilds SET cursor_id=?,status=? WHERE organization_id=? AND shop_id=?",ids.getLast(),ids.size()<500?"DONE":job.get("status"),job.get("organization_id"),job.get("shop_id"));
        }else repo.jdbc().update("UPDATE finance_cost_rebuilds SET status='DONE' WHERE organization_id=? AND shop_id=?",job.get("organization_id"),job.get("shop_id"));
        return ids.size();
    }
    /** A bounded multi-row statement avoids one network round trip per row without driver-specific flags. */
    void insertRows(String prefix,String tuple,String suffix,List<Object[]> rows){
        if(rows.isEmpty())return;
        repo.jdbc().update(prefix+String.join(",",Collections.nCopies(rows.size(),tuple))+suffix,
            rows.stream().flatMap(Arrays::stream).toArray());
    }
    List<String> candidateJobs(int limit,int page){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Enumerate cost-job candidates before starting the calculation transaction");
        int size=Math.min(100,Math.max(1,limit));
        // The ordered secondary-index range must remain unlocked: its gap locks
        // otherwise block unrelated synchronization INSERTs for the whole batch.
        return repo.jdbc().queryForList("SELECT order_record_id FROM finance_order_cost_jobs ORDER BY priority,requested_at,order_record_id LIMIT ? OFFSET ?",
                String.class,size,Math.min(3,Math.max(0,page))*size);
    }
    List<Map<String,Object>> claimJobs(List<String> ids){
        if(ids.isEmpty())return List.of();
        if(ids.size()>100)throw new IllegalArgumentException("At most 100 cost jobs per transaction");
        return repo.jdbc().queryForList("SELECT * FROM finance_order_cost_jobs FORCE INDEX(PRIMARY) WHERE order_record_id IN ("
                +String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY priority,requested_at,order_record_id FOR UPDATE SKIP LOCKED",ids.toArray());
    }
    Set<String> processBatch(int limit){
        // Existing explicit repair callers already own their transaction. Retain
        // their locking read so candidate enumeration cannot establish an older
        // repeatable-read snapshot before the jobs are actually locked.
        return processJobs(repo.jdbc().queryForList("SELECT * FROM finance_order_cost_jobs ORDER BY priority,requested_at,order_record_id LIMIT ? FOR UPDATE SKIP LOCKED",Math.min(100,Math.max(1,limit))));
    }
    Set<String> processBatch(List<String> candidates){return processJobs(claimJobs(candidates));}
    private Set<String> processJobs(List<Map<String,Object>> jobs){
        if(jobs.isEmpty())return Set.of();
        var contexts=new HashMap<String,Context>();var labelContexts=new HashMap<String,Context>();
        var catalogs=new HashMap<CatalogKey,Catalog>();var lookups=new HashMap<String,Lookup>();
        var writable=new HashMap<String,Boolean>();var changed=new LinkedHashSet<String>();
        var records=new HashMap<String,Map<String,Object>>();
        String slots=String.join(",",Collections.nCopies(jobs.size(),"?"));
        for(var order:repo.jdbc().queryForList("""
            SELECT o.*,c.data_json cost_data,c.source_hash cost_source_hash,
              lc.amount_cny invoice_label_amount,lc.pending_count invoice_label_pending,lc.shipment_count invoice_label_shipments
            FROM finance_records o LEFT JOIN finance_order_costs c ON c.order_record_id=o.id
            """+FinanceLabelCosts.join("o.organization_id","o.shop_id","o.order_id")+" WHERE o.kind='ORDER' AND o.id IN ("+slots+")",
            jobs.stream().map(j->j.get("order_record_id")).toArray()))
            records.put(text(order.get("id")),order);
        var actual=new FinanceActualCosts(repo).forRecords(records.keySet());
        var writes=new ArrayList<Object[]>();var hashes=new ArrayList<Object[]>();
        for(var job:jobs){
            String recordId=text(job.get("order_record_id")),organization=text(job.get("organization_id")),shop=text(job.get("shop_id"));
            String key=organization+":"+shop;
            if(!writable.computeIfAbsent(key,k->repo.writableShop(organization,shop)))continue;
            var order=records.get(recordId);
            if(order==null||!organization.equals(order.get("organization_id"))||!shop.equals(order.get("shop_id")))continue;
            var raw=decode(order.get("raw_json"));String sourceHash=sourceHash(repo,raw,text(order.get("status")));
            var old=decode(order.get("cost_data"));
            boolean labelOnly=number(job.get("priority"))==2&&sourceHash.equals(text(order.get("cost_source_hash")))&&old.containsKey("status")&&old.containsKey("lines");
            Context context=labelOnly?labelContexts.computeIfAbsent(key,k->labelContext(organization,shop)):contexts.computeIfAbsent(key,k->context(organization,shop,catalogs));
            if(context==null)continue;
            var product=labelOnly?old:actual.containsKey(recordId)?actual.get(recordId):calculate(order,raw,context,lookups.computeIfAbsent(key,k->lookup(context)));
            var label=new LinkedHashMap<String,Object>();label.put("amount_cny",order.get("invoice_label_amount"));
            label.put("pending_count",order.get("invoice_label_pending"));label.put("shipment_count",order.get("invoice_label_shipments"));
            var result=FinanceLabelCosts.overlay(product,label);
            result.put("policyVersion",POLICY_VERSION);
            String serialized=repo.write(result);
            if(!old.equals(decode(serialized))){
                // Persist the current derived result only. Repeated synchronization
                // must not append full before/after snapshots for every order;
                // explicit cost imports and manual edits keep their own audit trail.
                writes.add(new Object[]{recordId,organization,shop,order.get("order_id"),order.get("record_date"),result.get("status"),result.get("amountCny"),result.get("knownAmountCny"),
                    result.get("lineCount"),result.get("matchedLines"),result.get("fallbackLines"),sourceHash,serialized,result.get("labelKnownCny"),result.get("chargesPending"),result.get("basis")});
                changed.add(organization);
            }else if(!sourceHash.equals(text(order.get("cost_source_hash"))))hashes.add(new Object[]{sourceHash,recordId});
            // Completing any queued job changes the report's processing state even if its amount is unchanged.
            changed.add(organization);
        }
        insertRows("""
            INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,amount_cny,known_amount_cny,line_count,matched_lines,fallback_lines,source_hash,data_json,label_cost_cny,charges_pending,cost_basis)
            VALUES
            ""","(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)","""
             ON DUPLICATE KEY UPDATE record_date=VALUES(record_date),status=VALUES(status),amount_cny=VALUES(amount_cny),
            known_amount_cny=VALUES(known_amount_cny),line_count=VALUES(line_count),matched_lines=VALUES(matched_lines),fallback_lines=VALUES(fallback_lines),
            source_hash=VALUES(source_hash),data_json=VALUES(data_json),label_cost_cny=VALUES(label_cost_cny),charges_pending=VALUES(charges_pending),cost_basis=VALUES(cost_basis),version=version+1,updated_at=UTC_TIMESTAMP(6)
            """,writes);
        if(!hashes.isEmpty())repo.jdbc().batchUpdate("UPDATE finance_order_costs SET source_hash=? WHERE order_record_id=?",hashes);
        repo.jdbc().update("DELETE FROM finance_order_cost_jobs WHERE order_record_id IN ("+slots+")",jobs.stream().map(j->j.get("order_record_id")).toArray());
        return changed;
    }
}
