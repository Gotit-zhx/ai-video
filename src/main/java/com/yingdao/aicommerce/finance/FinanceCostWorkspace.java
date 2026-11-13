package com.yingdao.aicommerce.finance;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Company-scoped cost configuration and expense ledger. No inferred cost currency or invented profit. */
@Service
public class FinanceCostWorkspace {
    public List<Map<String,Object>> returnCosts(Scope scope,Filter filter,String numbers){
        return new FinanceActualCosts(repo).returns(scope,filter,numbers);
    }
    private final FinanceRepository repo; private final FinanceService service;
    public FinanceCostWorkspace(FinanceRepository repo,FinanceService service){this.repo=repo;this.service=service;}
    public Map<String,Object> orderCosts(Scope scope,Filter filter,String kind,String recordId,int page){
        return new FinanceCostReport(repo).orders(scope,filter,kind,recordId,page);
    }
    public Map<String,Object> orderCosts(Scope scope,Filter filter,String kind,String recordId,int page,String costStatus){return orderCosts(scope,filter,kind,recordId,page,costStatus,"");}
    public Map<String,Object> orderCosts(Scope scope,Filter filter,String kind,String recordId,int page,String costStatus,String numbers){
        return new FinanceCostReport(repo).orders(scope,filter,kind,recordId,page,costStatus,numbers);
    }
    /** Requested only when opening the operating-cost breakdown, never on every dashboard poll. */
    @Transactional(readOnly=true,timeout=30)
    public Map<String,Object> costSummary(Scope scope,Filter filter,String kind){
        if(!Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED").contains(kind))throw bad("成本分类不正确");
        return new FinanceCostReport(repo).summary(scope,filter,kind);
    }
    public Map<String,Object> costSummary(Scope scope,Filter filter,String kind,String displayCurrency,String snapshot){
        if(!Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED").contains(kind))throw bad("成本分类不正确");
        String currency="CNY".equals(displayCurrency)||filter.currency().isBlank()?"CNY":filter.currency();
        var quote="CNY".equals(currency)?null:service.exchangeRates().historical(snapshot,filter.start(),filter.end());
        return new FinanceCostReport(repo).displaySummary(scope,filter,kind,currency,quote);
    }
    static String required(Map<String,Object> body,String key,int max){
        String value=text(body.get(key)).trim();if(value.isBlank()||value.length()>max)throw bad("请填写有效的 "+key);return value;
    }
    static String choice(Map<String,Object> body,String key,String...allowed){
        String value=text(body.get(key));if(!Set.of(allowed).contains(value))throw bad(key+" 配置不正确");return value;
    }
    static LocalDate day(Object value){try{return LocalDate.parse(text(value));}catch(Exception e){throw bad("日期请使用 YYYY-MM-DD");}}
    static BigDecimal positive(Object value){
        BigDecimal n=decimal(value);if(n==null||n.signum()<=0||n.scale()>6||n.precision()-n.scale()>18)throw bad("金额或数量必须大于零，最多 6 位小数");return n;
    }
    Map<String,Object> decode(Object value){return repo.read(value instanceof byte[] b?new String(b,StandardCharsets.UTF_8):text(value));}
    void audit(Scope s,String actor,String type,String id,Object before,Object after,String reason){
        repo.jdbc().update("INSERT INTO finance_workspace_audit(id,organization_id,entity_type,entity_id,before_json,after_json,reason,actor_id) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),s.organizationId(),type,id,before==null?null:repo.write(before),repo.write(after),reason,actor);
    }
    Map<String,Object> book(Scope s,String group){
        if(group.isBlank())return Map.of();
        var result=repo.jdbc().queryForList("SELECT id,source_name,data_json,created_at FROM finance_cost_books WHERE organization_id=? AND group_id=? ORDER BY created_at DESC,id DESC LIMIT 1",s.organizationId(),group);
        if(result.isEmpty())return Map.of();
        var row=result.getFirst();var resultData=new LinkedHashMap<>(decode(row.get("data_json")));resultData.put("currency","CNY");resultData.put("bookId",row.get("id"));resultData.put("sourceName",row.get("source_name"));return resultData;
    }
    /** Lightweight navigation context. Cost books, settings, mappings and order JSON stay lazy. */
    public Map<String,Object> context(Scope s,String shopId,String requestedGroup){
        var shops=service.shops(s);
        if(!text(requestedGroup).isBlank())new FinanceGroups(repo).require(s,requestedGroup,false);
        if(text(shopId).isBlank()){
            shopId=shops.stream().filter(item->text(requestedGroup).isBlank()||requestedGroup.equals(text(item.get("group_id"))))
                    .map(item->text(item.get("id"))).findFirst().orElse("");
        }
        String selectedShopId=shopId;
        var shop=shops.stream().filter(item->selectedShopId.equals(text(item.get("id")))).findFirst();
        if(shop.isEmpty()){
            if(!selectedShopId.isBlank())throw bad("没有找到本公司的财务店铺");
            String group=text(requestedGroup);
            return new LinkedHashMap<>(Map.of("live",true,"catalogLoaded",false,"settingsLoaded",false,
                    "shopId","","groupId",group,"groupName",""));
        }
        String group=text(shop.get().get("group_id"));
        if(!text(requestedGroup).isBlank()&&!requestedGroup.equals(group))throw bad("店铺不属于所选分组");
        return new LinkedHashMap<>(Map.of("live",true,"catalogLoaded",false,"settingsLoaded",false,
                "shopId",selectedShopId,"groupId",group,"groupName",text(shop.get().get("group_name"))));
    }
    public Map<String,Object> costs(Scope s,String shopId){return costs(s,shopId,"");}
    public Map<String,Object> costs(Scope s,String shopId,String requestedGroup){
        var shops=service.shops(s);
        if(!text(requestedGroup).isBlank())new FinanceGroups(repo).require(s,requestedGroup,false);
        var catalogs=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:repo.jdbc().queryForList("""
            SELECT b.group_id,b.source_name,COALESCE(JSON_LENGTH(b.data_json,'$.costs'),0)+COALESCE(JSON_LENGTH(b.data_json,'$.manualCosts'),0) cost_rows
            FROM finance_groups g JOIN finance_cost_books b ON b.id=(
                SELECT id FROM finance_cost_books
                WHERE organization_id=g.organization_id AND group_id=g.id
                ORDER BY created_at DESC,id DESC LIMIT 1
            ) WHERE g.organization_id=?
            """,s.organizationId())){
            catalogs.put(text(row.get("group_id")),Map.of("sourceName",text(row.get("source_name")),"costRows",number(row.get("cost_rows"))));
        }
        var shopCatalogs=new LinkedHashMap<String,Object>();
        for(var item:shops)shopCatalogs.put(text(item.get("id")),catalogs.getOrDefault(text(item.get("group_id")),Map.of("sourceName","","costRows",0)));
        if(text(shopId).isBlank()){
            var available=shops.stream().filter(item->text(requestedGroup).isBlank()||requestedGroup.equals(text(item.get("group_id")))).toList();
            var selected=available.stream().filter(item->catalogs.containsKey(text(item.get("group_id")))).findFirst();
            if(selected.isEmpty()&&!available.isEmpty())selected=Optional.of(available.getFirst());
            if(selected.isEmpty()){
                String group=text(requestedGroup);
                var empty=new LinkedHashMap<>(book(s,group));empty.putIfAbsent("costs",List.of());empty.putIfAbsent("manualCosts",List.of());
                empty.putAll(Map.of("live",true,"catalogLoaded",true,"settingsLoaded",true,"shopId","","groupId",group,"products",List.of(),"shopCatalogs",shopCatalogs,"groupCatalogs",catalogs));
                empty.put("imports",new FinanceCostSources(repo).sources(s,group));
                return empty;
            }
            shopId=text(selected.get().get("id"));
        }
        String selectedShopId=shopId;
        var shop=shops.stream().filter(item->selectedShopId.equals(item.get("id"))).findFirst().orElseThrow(()->bad("没有找到本公司的财务店铺"));
        String group=text(shop.get("group_id"));
        if(!text(requestedGroup).isBlank()&&!requestedGroup.equals(group))throw bad("店铺不属于所选分组");
        var result=new LinkedHashMap<>(book(s,group));result.putIfAbsent("costs",List.of());
        result.putIfAbsent("manualCosts",List.of());
        result.put("shopId",shopId);result.put("groupId",group);result.put("groupName",text(shop.get("group_name")));result.put("live",true);
        result.put("catalogLoaded",true);result.put("settingsLoaded",true);
        result.put("shopCatalogs",shopCatalogs);
        result.put("groupCatalogs",catalogs);
        var settings=repo.jdbc().queryForList("SELECT data_json,version FROM finance_cost_settings WHERE organization_id=? AND shop_id=?",s.organizationId(),shopId);
        result.put("settings",settings.isEmpty()?Map.of():decode(settings.getFirst().get("data_json")));
        result.put("settingsVersion",settings.isEmpty()?0:settings.getFirst().get("version"));
        result.put("suggestedWarehouse",FinanceOrderCosts.resolveWarehouse(text(shop.get("region")),
            FinanceCostSources.allCosts(result),map(result.get("settings"))));
        var mappings=new LinkedHashMap<String,Object>();
        for(var row:repo.jdbc().queryForList("SELECT product_id,sku_id,data_json,version FROM finance_cost_mappings WHERE organization_id=? AND shop_id=?",s.organizationId(),shopId)){
            var mapping=new LinkedHashMap<>(decode(row.get("data_json")));mapping.put("version",row.get("version"));mappings.put(shopId+":"+row.get("product_id")+":"+row.get("sku_id")+":"+mapping.get("warehouse"),mapping);
        }
        result.put("mappings",mappings);
        // Bounded local projection: does not call platforms, return buyer payloads, or claim full catalogue coverage.
        boolean tk="TIKTOK_SHOP".equals(shop.get("platform"));
        String fields=tk?"""
            product_id VARCHAR(128) PATH '$.product_id',sku_id VARCHAR(128) PATH '$.sku_id',
            seller_sku VARCHAR(255) PATH '$.seller_sku',name VARCHAR(500) PATH '$.product_name',
            spec VARCHAR(500) PATH '$.sku_name',image VARCHAR(1500) PATH '$.sku_image'
            """:"""
            product_id VARCHAR(128) PATH '$.item_id',sku_id VARCHAR(128) PATH '$.model_id',
            seller_sku VARCHAR(255) PATH '$.model_sku',item_sku VARCHAR(255) PATH '$.item_sku',
            name VARCHAR(500) PATH '$.item_name',spec VARCHAR(500) PATH '$.model_name',
            image VARCHAR(1500) PATH '$.image_info.image_url'
            """;
        var rows=repo.jdbc().queryForList("""
            SELECT r.id record_id,r.record_date,x.* FROM (
             SELECT id,record_date,raw_json FROM finance_records FORCE INDEX(idx_finance_shop)
             WHERE organization_id=? AND user_id=? AND shop_id=? AND kind='ORDER'
             ORDER BY record_date DESC,id DESC LIMIT 2000
            ) r JOIN JSON_TABLE(r.raw_json,? COLUMNS(%s)) x
            ORDER BY r.record_date DESC,r.id DESC LIMIT 12000
            """.formatted(fields),s.organizationId(),service.shopScope(s,shopId).userId(),shopId,tk?"$.line_items[*]":"$.item_list[*]");
        var products=new LinkedHashMap<String,Map<String,Object>>();Set<String> orders=new HashSet<>();
        for(var row:rows){
            orders.add(text(row.get("record_id")));String product=text(row.get("product_id")),sku=text(row.get("sku_id"));
            if(product.isBlank()||sku.isBlank())continue;
            products.putIfAbsent(product+":"+sku,productSnapshot(shopId,row));
        }
        addPendingProducts(s,shopId,tk,fields,products,result);
        result.put("products",new ArrayList<>(products.values()));result.put("sampleOrders",orders.size());result.put("orderLimit",2000);
        result.put("imports",new FinanceCostSources(repo).sources(s,group));
        return result;
    }
    static Map<String,Object> productSnapshot(String shopId,Map<String,Object> row){
        var p=new LinkedHashMap<String,Object>();p.put("shop_id",shopId);
        for(String key:List.of("product_id","sku_id","seller_sku","name","spec","image"))p.put(key,text(row.get(key)));
        if(text(p.get("seller_sku")).isBlank())p.put("seller_sku",text(row.get("item_sku")));
        p.put("fixed_gift_cost",FinanceOrderCosts.giftSku(p.get("seller_sku")));
        return p;
    }
    /** Include historical unresolved identities, even when absent from the recent-order sample.
     * Reads saved cost issues first, then only one order per identity; never infers delisting or a cost code. */
    private void addPendingProducts(Scope s,String shopId,boolean tk,String fields,
                                    Map<String,Map<String,Object>> products,Map<String,Object> result){
        var pending=repo.jdbc().queryForList("""
            SELECT missing.product_id,missing.sku_id,COUNT(DISTINCT c.order_record_id) pending_orders,
              MAX(c.order_record_id) sample_record_id,
              GROUP_CONCAT(DISTINCT missing.issue_text ORDER BY missing.issue_text SEPARATOR '；') pending_reason
            FROM finance_order_costs c JOIN JSON_TABLE(c.data_json,'$.lines[*]' COLUMNS(
              product_id VARCHAR(128) PATH '$.productId',sku_id VARCHAR(128) PATH '$.skuId',
              seller_sku VARCHAR(255) PATH '$.sellerSku',
              issue_text VARCHAR(500) PATH '$.issue')) missing
            WHERE c.organization_id=? AND c.shop_id=? AND c.status='INCOMPLETE'
              AND COALESCE(missing.issue_text,'')<>'' AND COALESCE(missing.product_id,'')<>'' AND COALESCE(missing.sku_id,'')<>''
              AND UPPER(TRIM(COALESCE(missing.seller_sku,'')))<>'GIFT'
              AND
            """+FinanceCostReport.hasSettlement("c","c.order_id")+"""
             GROUP BY missing.product_id,missing.sku_id
             ORDER BY pending_orders DESC,missing.product_id,missing.sku_id LIMIT 1001
            """,s.organizationId(),shopId);
        boolean truncated=pending.size()>1000;if(truncated)pending=pending.subList(0,1000);
        var wanted=new LinkedHashMap<String,Map<String,Object>>();
        var ids=new LinkedHashSet<String>();
        for(var row:pending){wanted.put(row.get("product_id")+":"+row.get("sku_id"),row);ids.add(text(row.get("sample_record_id")));}
        var samples=new ArrayList<>(ids);int added=0;
        for(int start=0;start<samples.size();start+=100){
            var batch=samples.subList(start,Math.min(start+100,samples.size()));
            String slots=String.join(",",Collections.nCopies(batch.size(),"?"));
            var args=new ArrayList<Object>();args.add(tk?"$.line_items[*]":"$.item_list[*]");args.add(s.organizationId());args.add(shopId);args.addAll(batch);
            var source=repo.jdbc().queryForList("""
                SELECT r.id record_id,x.* FROM finance_records r JOIN JSON_TABLE(r.raw_json,? COLUMNS(%s)) x
                WHERE r.organization_id=? AND r.shop_id=? AND r.kind='ORDER' AND r.source='API' AND r.id IN (%s)
                """.formatted(fields,slots),args.toArray());
            for(var row:source){
                String key=row.get("product_id")+":"+row.get("sku_id");var issue=wanted.get(key);
                if(issue==null||!Objects.equals(text(issue.get("sample_record_id")),text(row.get("record_id"))))continue;
                if(!products.containsKey(key)){var p=productSnapshot(shopId,row);p.put("historical_cost_pending",true);products.put(key,p);added++;}
            }
        }
        for(var row:pending){
            var p=products.get(row.get("product_id")+":"+row.get("sku_id"));
            if(p!=null){p.put("pending_cost_orders",row.get("pending_orders"));p.put("pending_cost_reason",row.get("pending_reason"));}
        }
        result.put("historicalPendingSkuCount",added);result.put("pendingSkuCount",pending.size());
        result.put("pendingSkusTruncated",truncated);result.put("pendingSkuLimit",1000);
    }
    /** Accepts the supplied warehouse-by-month shape or an explicit four-column long table. */
    static List<Map<String,Object>> parseCosts(byte[] bytes,String name){
        var rows=FinanceWorkbook.readCostTable(bytes,name);if(rows.size()<2)throw bad("成本表没有数据");
        var values=new LinkedHashMap<String,Map<String,Object>>();int header=-1,skuCol=-1,warehouseCol=-1,monthCol=-1,priceCol=-1;
        var monthColumns=new LinkedHashMap<Integer,String>();
        for(int i=0;i<Math.min(8,rows.size());i++){
            var r=rows.get(i);
            for(int c=0;c<r.size();c++){
                String h=r.get(c).trim();
                if(Set.of("型号","成本型号","SKU","sku").contains(h)){skuCol=c;header=i;}
                if(Set.of("仓库","成本仓","成本仓库").contains(h))warehouseCol=c;
                if(Set.of("月份","成本月份").contains(h))monthCol=c;
                if(Set.of("单价","成本单价","单位成本","期末单价").contains(h))priceCol=c;
                String m=month(h);if(m!=null)monthColumns.put(c,m);
            }
            if(header>=0)break;
        }
        if(skuCol<0||warehouseCol<0||(monthColumns.isEmpty()&&(monthCol<0||priceCol<0)))throw bad("需要型号、仓库和月份单价列；也支持 型号/仓库/月份/单价 四列格式");
        String previousWarehouse="";
        for(int i=header+1;i<rows.size();i++){
            var r=rows.get(i);String sku=cell(r,skuCol),warehouse=cell(r,warehouseCol);
            if(!warehouse.isBlank())previousWarehouse=warehouse;else warehouse=previousWarehouse;
            if(sku.isBlank()||Set.of("型号","成本型号").contains(sku))continue;
            if(warehouse.isBlank()||sku.length()>255||warehouse.length()>120||sku.equals("[FORMULA]")||warehouse.equals("[FORMULA]"))throw bad("第 "+(i+1)+" 行型号或仓库不正确");
            String key=warehouse+"\0"+sku;
            var row=values.computeIfAbsent(key,k->{var v=new LinkedHashMap<String,Object>();v.put("sku",sku);v.put("prices",new LinkedHashMap<String,Object>());return v;});
            row.put("warehouse",warehouse);var prices=map(row.get("prices"));
            Map<Integer,String> columns=monthColumns;
            if(columns.isEmpty()){String m=month(cell(r,monthCol));if(m==null)throw bad("第 "+(i+1)+" 行月份不正确");columns=Map.of(priceCol,m);}
            for(var column:columns.entrySet()){
                String raw=cell(r,column.getKey());if(raw.isBlank())continue;
                BigDecimal value=decimal(raw.replace(",",""));
                if(value==null||value.signum()<0||value.precision()-value.scale()>18)throw bad("第 "+(i+1)+" 行单价不正确");
                if(value.scale()>18)throw bad("第 "+(i+1)+" 行成本精度过高");
                if(prices.containsKey(column.getValue())){
                    if(value.compareTo(decimal(prices.get(column.getValue())))==0)continue;
                    throw bad("第 "+(i+1)+" 行同型号、仓库和月份的单价不一致，请先核对");
                }
                prices.put(column.getValue(),value);
            }
        }
        if(values.isEmpty())throw bad("没有识别到成本行");return new ArrayList<>(values.values());
    }
    static String cell(List<String> row,int i){return i>=0&&i<row.size()?row.get(i).trim():"";}
    static String month(String value){
        String v=value.replace("年","-").replace("月","").trim();
        if(v.matches("\\d{5,6}"))v=v.substring(0,4)+"-"+v.substring(4);
        try{return v.matches("\\d{4}-\\d{1,2}")?YearMonth.of(Integer.parseInt(v.substring(0,4)),Integer.parseInt(v.substring(5))).toString():null;}catch(Exception e){return null;}
    }
    @Transactional public Map<String,Object> importCosts(Scope s,String actor,String group,byte[] bytes,String name){
        new FinanceGroups(repo).require(s,group,true);
        String digest=hash(Base64.getEncoder().encodeToString(bytes));
        var existing=repo.jdbc().queryForList("SELECT id FROM finance_cost_sources WHERE organization_id=? AND group_id=? AND content_hash=?",s.organizationId(),group,digest);
        if(!existing.isEmpty())return Map.of("duplicate",true,"id",existing.getFirst().get("id"));
        var incoming=parseCosts(bytes,name);
        String id=UUID.randomUUID().toString();var body=Map.of("costs",incoming,"currency","CNY");
        long sequence=repo.jdbc().queryForObject("SELECT COALESCE(MAX(source_order),0)+1 FROM finance_cost_sources WHERE organization_id=? AND group_id=?",Long.class,s.organizationId(),group);
        repo.jdbc().update("INSERT INTO finance_cost_sources(id,organization_id,group_id,source_name,content_hash,data_json,source_order,created_by) VALUES(?,?,?,?,?,?,?,?)",
                id,s.organizationId(),group,name.substring(0,Math.min(250,name.length())),digest,repo.write(body),sequence,actor);
        new FinanceCostSources(repo).snapshot(s,actor,group);
        audit(s,actor,"COST_IMPORT",id,null,Map.of("file",name,"groupId",group,"rows",incoming.size(),"currency","CNY"),"导入人民币成本资料；计价单位另行确认");
        new FinanceOrderCosts(repo).requestGroup(s,group);
        service.invalidateAfterCommit(s);
        return Map.of("duplicate",false,"id",id,"rows",incoming.size());
    }
    @Transactional public void removeSource(Scope s,String actor,String group,String id){
        new FinanceGroups(repo).require(s,group,true);
        var old=repo.jdbc().queryForList("SELECT source_name,data_json FROM finance_cost_sources WHERE organization_id=? AND group_id=? AND id=?",s.organizationId(),group,id);
        if(old.isEmpty())throw bad("成本文件已删除或不属于当前分组，请刷新");
        repo.jdbc().update("DELETE FROM finance_cost_sources WHERE organization_id=? AND group_id=? AND id=?",s.organizationId(),group,id);
        audit(s,actor,"COST_SOURCE_DELETE",id,decode(old.getFirst().get("data_json")),Map.of("groupId",group,"file",old.getFirst().get("source_name")),"移除该文件提供的成本；手动成本保留");
        new FinanceCostSources(repo).snapshot(s,actor,group);
        new FinanceOrderCosts(repo).requestGroup(s,group);service.invalidateAfterCommit(s);
    }
    static BigDecimal manualPrice(Map<String,Object> body){
        BigDecimal price=decimal(body.get("unitPriceCny"));
        if(price==null||price.signum()<0||price.scale()>6||price.precision()-price.scale()>18)throw bad("请填写有效人民币单价，最多 6 位小数");
        if(!Boolean.TRUE.equals(body.get("confirmed")))throw bad("请确认手动单价及计价单位");
        if(price.signum()==0&&!Boolean.TRUE.equals(body.get("zeroConfirmed")))throw bad("零元成本需要单独确认");
        return price;
    }
    @Transactional public Map<String,Object> manualMapping(Scope s,String actor,String shopId,Map<String,Object> body){
        var shop=service.ownedShop(s,shopId);String group=text(shop.get("group_id"));
        new FinanceGroups(repo).require(s,group,true);
        String code=required(body,"code",255),warehouse=required(body,"warehouse",120),unit=required(body,"unit",40);
        BigDecimal price=manualPrice(body);
        var duplicate=repo.jdbc().queryForList("SELECT id FROM finance_manual_costs WHERE organization_id=? AND group_id=? AND code=? AND warehouse=?",s.organizationId(),group,code,warehouse);
        if(!duplicate.isEmpty())throw bad("此分组已有同型号、同仓库的手动成本，请选择已有手动成本关联");
        String id=UUID.randomUUID().toString();
        repo.jdbc().update("""
            INSERT INTO finance_manual_costs(id,organization_id,group_id,code,warehouse,unit,unit_price_cny,created_by,updated_by)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,id,s.organizationId(),group,code,warehouse,unit,price,actor,actor);
        var saved=new LinkedHashMap<>(body);saved.put("manualCostId",id);
        new FinanceCostSources(repo).snapshot(s,actor,group);
        mapping(s,actor,shopId,saved);
        audit(s,actor,"MANUAL_COST",id,null,Map.of("groupId",group,"code",code,"warehouse",warehouse,"unit",unit,"unitPriceCny",price),"新增独立手动单价并关联 SKU，适用历史和后续订单");
        new FinanceOrderCosts(repo).requestGroup(s,group);service.invalidateAfterCommit(s);
        return Map.of("id",id);
    }
    @Transactional public void changeManual(Scope s,String actor,String group,String id,Map<String,Object> body,boolean delete){
        new FinanceGroups(repo).require(s,group,true);
        var old=repo.jdbc().queryForList("SELECT * FROM finance_manual_costs WHERE organization_id=? AND group_id=? AND id=? FOR UPDATE",s.organizationId(),group,id);
        if(old.isEmpty()||number(old.getFirst().get("version"))!=number(body.get("version")))throw bad("手动成本已删除或被修改，请刷新后重试");
        Map<String,Object> after;
        if(delete){
            repo.jdbc().update("DELETE FROM finance_manual_costs WHERE organization_id=? AND group_id=? AND id=?",s.organizationId(),group,id);
            // Keep references so deletion never silently switches an explicit manual decision to an Excel price.
            after=Map.of("deleted",true,"groupId",group);
        }else{
            BigDecimal price=manualPrice(body);String unit=required(body,"unit",40);
            repo.jdbc().update("UPDATE finance_manual_costs SET unit=?,unit_price_cny=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND group_id=? AND id=?",
                unit,price,actor,s.organizationId(),group,id);
            after=Map.of("unit",unit,"unitPriceCny",price,"groupId",group);
        }
        audit(s,actor,delete?"MANUAL_COST_DELETE":"MANUAL_COST",id,old.getFirst(),after,delete?"删除独立手动成本，已关联订单回到待补齐":"修改手动成本并重算历史关联订单");
        new FinanceCostSources(repo).snapshot(s,actor,group);
        new FinanceOrderCosts(repo).requestGroup(s,group);service.invalidateAfterCommit(s);
    }
    @Transactional public void settings(Scope s,String actor,String shopId,Map<String,Object> body){
        var shop=service.ownedShop(s,shopId);String warehouse=text(body.get("warehouse"));var rules=new LinkedHashMap<>(map(body.get("rules")));
        String currency=text(rules.get("currency"));if(!currency.isBlank()&&!currency.equals("CNY"))throw bad("成本表统一使用人民币 CNY");rules.put("currency","CNY");
        choice(rules,"unit","","包","片","箱","套");choice(rules,"basis","order","shipment");choice(rules,"missing","pending","previous");
        choice(rules,"freight","","included","excluded");choice(rules,"inbound","","included","excluded");
        if(!warehouse.isBlank()&&FinanceCostSources.allCosts(book(s,text(shop.get("group_id")))).stream().noneMatch(c->warehouse.equals(c.get("warehouse"))))throw bad("成本仓不属于当前分组");
        var old=repo.jdbc().queryForList("SELECT data_json,version FROM finance_cost_settings WHERE organization_id=? AND shop_id=? FOR UPDATE",s.organizationId(),shopId);
        long version=old.isEmpty()?0:number(old.getFirst().get("version"));if(version!=number(body.get("version")))throw bad("配置已被其他成员修改，请刷新后重试");
        var saved=Map.of("warehouse",warehouse,"rules",rules);
        repo.jdbc().update("INSERT INTO finance_cost_settings(organization_id,shop_id,data_json,updated_by) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE data_json=VALUES(data_json),version=version+1,updated_by=VALUES(updated_by),updated_at=UTC_TIMESTAMP(6)",s.organizationId(),shopId,repo.write(saved),actor);
        audit(s,actor,"COST_SETTINGS",shopId,old.isEmpty()?null:decode(old.getFirst().get("data_json")),saved,"更新成本口径");
        new FinanceOrderCosts(repo).requestShop(s.organizationId(),shopId);service.invalidateAfterCommit(s);
    }
    @Transactional public void mapping(Scope s,String actor,String shopId,Map<String,Object> body){
        var shop=service.ownedShop(s,shopId);String product=required(body,"product_id",128),sku=required(body,"sku_id",128),warehouse=required(body,"warehouse",120),code=required(body,"code",255);
        new FinanceGroups(repo).require(s,text(shop.get("group_id")),true);
        BigDecimal factor=positive(body.get("factor"));if(!Boolean.TRUE.equals(body.get("confirmed")))throw bad("请确认销售规格与成本型号");
        String manualId=text(body.get("manualCostId"));
        if(manualId.isBlank()){
            if(rows(book(s,text(shop.get("group_id"))).get("costs")).stream().noneMatch(c->warehouse.equals(c.get("warehouse"))&&code.equals(c.get("sku"))))throw bad("成本型号不属于当前分组和仓库");
        }else if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_manual_costs WHERE organization_id=? AND group_id=? AND id=? AND warehouse=? AND code=?",Integer.class,s.organizationId(),shop.get("group_id"),manualId,warehouse,code)!=1)
            throw bad("手动成本不属于当前分组和仓库，或已删除");
        String id=hash(s.organizationId()+"\0"+shopId+"\0"+product+"\0"+sku+"\0"+warehouse);
        var old=repo.jdbc().queryForList("SELECT data_json,version FROM finance_cost_mappings WHERE organization_id=? AND id=? FOR UPDATE",s.organizationId(),id);
        long version=old.isEmpty()?0:number(old.getFirst().get("version"));if(version!=number(body.get("version")))throw bad("关联已被其他成员修改，请刷新后重试");
        var saved=new LinkedHashMap<String,Object>(Map.of("code",code,"warehouse",warehouse,"factor",factor,"groupId",text(shop.get("group_id"))));
        if(!manualId.isBlank())saved.put("manualCostId",manualId);
        repo.jdbc().update("INSERT INTO finance_cost_mappings(id,organization_id,shop_id,product_id,sku_id,warehouse,data_json,updated_by) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE data_json=VALUES(data_json),version=version+1,updated_by=VALUES(updated_by),updated_at=UTC_TIMESTAMP(6)",
                id,s.organizationId(),shopId,product,sku,warehouse,repo.write(saved),actor);
        audit(s,actor,"COST_MAPPING",id,old.isEmpty()?null:decode(old.getFirst().get("data_json")),saved,"人工确认 SKU 成本关系");
        new FinanceOrderCosts(repo).requestShop(s.organizationId(),shopId);service.invalidateAfterCommit(s);
    }
    // Only presentation wrappers are ignored; size letters, hyphens and bundle quantities remain significant.
    static String normalizedSku(String code){
        String value=text(code).replaceAll("[\\s\\p{Z}\\uFEFF]+","").replace('（','(').replace('）',')').replace('×','*').replace('＊','*');
        String previous;
        do{
            previous=value;
            value=value.replaceFirst("^\\(([^()]+)\\)(\\*[1-9][0-9]*)?$","$1$2");
        }while(!previous.equals(value));
        return value;
    }
    static String matchingCostCode(String sellerCode,Set<String> codes,Map<String,Set<String>> normalized){
        String code=text(sellerCode).trim();
        if(code.isBlank())return "";
        if(codes.contains(code))return code;
        var matches=normalized.getOrDefault(normalizedSku(code),Set.of()).stream().filter(codes::contains).toList();
        return matches.size()==1?matches.iterator().next():"";
    }
    record CostCodeMatch(String code,String method,Integer bundleQuantity){}
    static CostCodeMatch matchSku(String sellerCode,Set<String> codes,Map<String,Set<String>> normalized){
        String code=matchingCostCode(sellerCode,codes,normalized);
        if(!code.isBlank())return new CostCodeMatch(code,code.equals(text(sellerCode).trim())?"EXACT_CODE":"NORMALIZED_CODE",null);
        String formatted=normalizedSku(sellerCode);
        if(normalized.getOrDefault(formatted,Set.of()).stream().anyMatch(codes::contains))return null; // Ambiguous full bundle codes must not fall back to a base price.
        var bundle=java.util.regex.Pattern.compile("^([^*]+)\\*([1-9][0-9]{0,5})$").matcher(formatted);
        if(!bundle.matches())return null;
        code=matchingCostCode(bundle.group(1),codes,normalized);
        return code.isBlank()?null:new CostCodeMatch(code,"BUNDLE_CODE",Integer.valueOf(bundle.group(2)));
    }
    static boolean automaticMethod(String method){return Set.of("EXACT_CODE","NORMALIZED_CODE","BUNDLE_CODE","SPU_CODE").contains(method);}
    static CostCodeMatch matchProduct(String sellerCode,String productId,Set<String> codes,Map<String,Set<String>> normalized){
        var sku=matchSku(sellerCode,codes,normalized);
        if(sku!=null)return sku;
        String spu=matchingCostCode(productId,codes,normalized);
        return spu.isBlank()?null:new CostCodeMatch(spu,"SPU_CODE",null);
    }
    @Transactional public Map<String,Object> autoMatch(Scope s,String actor,String shopId,Map<String,Object> body){
        repo.requireShopWrite(s,shopId);
        var data=costs(s,shopId);var catalog=rows(data.get("costs"));
        var byWarehouse=new LinkedHashMap<String,Set<String>>();
        for(var cost:catalog)byWarehouse.computeIfAbsent(text(cost.get("warehouse")),key->new LinkedHashSet<>()).add(text(cost.get("sku")));
        var normalized=new LinkedHashMap<String,Set<String>>();
        for(var codes:byWarehouse.values())for(String code:codes)normalized.computeIfAbsent(normalizedSku(code),key->new LinkedHashSet<>()).add(code);
        var existing=map(data.get("mappings"));int candidates=0;
        for(var product:rows(data.get("products"))){
            if(FinanceOrderCosts.giftSku(product.get("seller_sku")))continue;
            String productId=text(product.get("product_id")),skuId=text(product.get("sku_id"));
            for(var available:byWarehouse.entrySet()){
            String warehouse=available.getKey();
            var match=matchProduct(text(product.get("seller_sku")),productId,available.getValue(),normalized);
            if(match==null)continue;
            String code=match.code();
            if(existing.containsKey(shopId+":"+productId+":"+skuId+":"+warehouse))continue;
            String id=hash(s.organizationId()+"\0"+shopId+"\0"+productId+"\0"+skuId+"\0"+warehouse);
            var saved=new LinkedHashMap<String,Object>();saved.put("code",code);saved.put("warehouse",warehouse);saved.put("groupId",data.get("groupId"));
            saved.put("method",match.method());saved.put("factor",null);saved.put("unitConfirmed",false);
            if(match.bundleQuantity()!=null)saved.put("bundleQuantity",match.bundleQuantity());
            // Preserve manual decisions, including a decision saved concurrently with this batch.
            repo.jdbc().update("""
                INSERT INTO finance_cost_mappings(id,organization_id,shop_id,product_id,sku_id,warehouse,data_json,updated_by)
                VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """,id,s.organizationId(),shopId,productId,skuId,warehouse,repo.write(saved),actor);
            candidates++;
            }
        }
        if(candidates>0)audit(s,actor,"COST_AUTO_MATCH",shopId,null,Map.of("candidateCount",candidates),"按商品 SKU 优先、平台 SPU 兜底自动关联各成本仓；保留已有人工确认关系");
        return costs(s,shopId);
    }
    public Map<String,Object> expenses(Scope s,Map<String,String> query){
        String start=query.getOrDefault("start",LocalDate.now().minusDays(29).toString()),end=query.getOrDefault("end",LocalDate.now().toString());
        if(day(start).isAfter(day(end)))throw bad("开始日期不能晚于结束日期");
        var args=new ArrayList<Object>(List.of(s.organizationId(),start,end));
        String where="organization_id=? AND record_date>=? AND record_date<=?";
        String currency=query.getOrDefault("currency","CNY"),group=query.getOrDefault("group","");
        if(!currency.matches("[A-Z]{3}"))throw bad("币种不正确");where+=" AND currency=?";args.add(currency);
        if(group.equals("UNGROUPED")){where+=" AND scope<>'group' AND target_id IN (SELECT id FROM finance_connections WHERE organization_id=? AND group_id IS NULL AND status<>'REMOVED')";args.add(s.organizationId());}
        else if(!group.isBlank()){new FinanceGroups(repo).require(s,group,false);where+=" AND ((scope='group' AND target_id=?) OR (scope<>'group' AND target_id IN (SELECT id FROM finance_connections WHERE organization_id=? AND group_id=? AND status<>'REMOVED')))";args.addAll(List.of(group,s.organizationId(),group));}
        String search=query.getOrDefault("search","").trim();if(search.length()>200)throw bad("搜索内容过长");
        if(!search.isBlank()){where+=" AND (LOCATE(?,source_ref)>0 OR LOCATE(?,order_id)>0)";args.addAll(List.of(search,search));}
        int page=(int)Math.min(100000,Math.max(1,number(query.getOrDefault("page","1"))));
        var totals=repo.jdbc().queryForMap("SELECT COUNT(*) records,COALESCE(SUM(amount),0) amount,COALESCE(SUM(CASE WHEN treatment='extra' AND scope<>'group' THEN amount ELSE 0 END),0) posted,SUM(treatment IN ('unit','settlement','ad')) included,SUM(treatment='pending' OR (treatment='extra' AND scope='group')) pending FROM finance_expenses WHERE "+where,args.toArray());
        args.add(50);args.add((page-1)*50);
        var items=repo.jdbc().queryForList("SELECT id,data_json,version FROM finance_expenses WHERE "+where+" ORDER BY record_date DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->{var row=new LinkedHashMap<>(decode(r.get("data_json")));row.put("id",r.get("id"));row.put("version",r.get("version"));return row;}).toList();
        new FinanceProductSkus(repo).attachExpenses(s,items);
        var rules=repo.jdbc().queryForList("SELECT id,data_json,created_at FROM finance_expense_rules WHERE organization_id=? ORDER BY created_at DESC LIMIT 100",s.organizationId()).stream().map(r->{var body=new LinkedHashMap<>(decode(r.get("data_json")));body.put("id",r.get("id"));return body;}).toList();
        var changes=repo.jdbc().queryForList("SELECT before_json,after_json,reason,actor_id,created_at FROM finance_workspace_audit WHERE organization_id=? AND entity_type='EXPENSE' ORDER BY created_at DESC LIMIT 100",s.organizationId()).stream().map(r->Map.of("before",r.get("before_json")==null?Map.of():decode(r.get("before_json")),"after",decode(r.get("after_json")),"reason",r.get("reason"),"actor",r.get("actor_id"),"time",r.get("created_at").toString())).toList();
        return Map.of("rows",items,"totals",totals,"rules",rules,"changes",changes,"page",page,"pageSize",50);
    }
    @Transactional public Map<String,Object> expense(Scope s,String actor,String id,Map<String,Object> body){
        String scope=choice(body,"scope","order","shop","group"),target=required(body,"target",36),category=choice(body,"category","packaging","shipping","warehouse","staff","rent","other");
        String source=required(body,"source",200),treatment=choice(body,"treatment","extra","settlement","unit","ad","pending"),currency=required(body,"currency",8);
        if(!currency.matches("[A-Z]{3}"))throw bad("币种不正确");
        var shop=scope.equals("group")?Map.<String,Object>of():service.ownedShop(s,target);
        if(scope.equals("group"))new FinanceGroups(repo).require(s,target,true);
        String order=scope.equals("order")?required(body,"order",128):"";
        if(scope.equals("order")&&repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=? AND shop_id=? AND kind='ORDER' AND external_id=?",Integer.class,s.organizationId(),target,order)==0)throw bad("订单编号不属于当前店铺，或该订单尚未同步");
        LocalDate start=day(body.get("start")),end=day(body.get("end")),record=day(body.get("recordDate"));
        if(start.isAfter(end)||start.plusYears(10).isBefore(end))throw bad("费用期间不正确");
        BigDecimal amount=positive(body.get("amount"));String reason=text(body.get("note")).trim();if(reason.length()>1000)throw bad("说明不能超过 1000 字");
        var old=id==null?List.<Map<String,Object>>of():repo.jdbc().queryForList("SELECT data_json,version FROM finance_expenses WHERE organization_id=? AND id=? FOR UPDATE",s.organizationId(),id);
        if(id!=null&&(old.isEmpty()||number(old.getFirst().get("version"))!=number(body.get("version"))))throw bad("记录不存在或已被修改，请刷新重试");
        if(!old.isEmpty()){
            var previous=decode(old.getFirst().get("data_json"));
            if(!"group".equals(text(previous.get("scope"))))repo.requireShopWrite(s,text(previous.get("target")));
        }
        if(id!=null&&reason.isBlank())throw bad("请填写调整原因");
        String identifier=id==null?UUID.randomUUID().toString():id;long version=old.isEmpty()?1:number(old.getFirst().get("version"))+1;
        var saved=new LinkedHashMap<String,Object>();saved.put("id",identifier);saved.put("scope",scope);saved.put("target",target);saved.put("order",order);saved.put("category",category);saved.put("source",source);saved.put("amount",amount);saved.put("currency",currency);saved.put("start",start.toString());saved.put("end",end.toString());saved.put("recordDate",record.toString());saved.put("treatment",treatment);saved.put("note",reason);saved.put("version",version);
        saved.put("status",treatment.equals("extra")?(scope.equals("group")?"待分摊":"已计入"):treatment.equals("pending")?"待核对":"不重复计入");
        String digest=hash(category+"\0"+source.toUpperCase(Locale.ROOT));
        if(treatment.equals("extra")&&repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=? AND kind='COST' AND source='IMPORT' AND external_id=?",Integer.class,s.organizationId(),source)>0)throw bad("此来源编号已通过成本文件导入，请勿再次计入");
        try{
            if(id==null)repo.jdbc().update("INSERT INTO finance_expenses(id,organization_id,scope,target_id,order_id,category,source_ref,dedupe_key,amount,currency,start_date,end_date,record_date,treatment,data_json,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    identifier,s.organizationId(),scope,target,order,category,source,digest,amount,currency,start,end,record,treatment,repo.write(saved),actor,actor);
            else repo.jdbc().update("UPDATE finance_expenses SET scope=?,target_id=?,order_id=?,category=?,source_ref=?,dedupe_key=?,amount=?,currency=?,start_date=?,end_date=?,record_date=?,treatment=?,data_json=?,version=?,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=?",
                    scope,target,order,category,source,digest,amount,currency,start,end,record,treatment,repo.write(saved),version,actor,s.organizationId(),identifier);
        }catch(DuplicateKeyException e){throw bad("同类费用已有相同来源编号，请调整原记录，避免重复入账");}
        repo.jdbc().update("DELETE FROM finance_records WHERE organization_id=? AND source='MANUAL_COST' AND external_id=?",s.organizationId(),"expense:"+identifier);
        if(treatment.equals("extra")&&!scope.equals("group")){
            Scope owner=service.shopScope(s,target);
            repo.save(owner,new Entry(target,text(shop.get("platform")),"COST","expense:"+identifier,order,"",category,"CONFIRMED",record,currency,amount,null,null,null,null,"MANUAL_COST",Map.of("expense_id",identifier,"source_reference",source,"period_start",start.toString(),"period_end",end.toString())));
        }
        audit(s,actor,"EXPENSE",identifier,old.isEmpty()?null:decode(old.getFirst().get("data_json")),saved,reason.isBlank()?"新增费用":reason);
        service.invalidateAfterCommit(s);return saved;
    }
    @Transactional public void expenseRule(Scope s,String actor,Map<String,Object> body){
        String scope=choice(body,"scope","group","shop"),target=required(body,"target",36);
        if(scope.equals("group"))new FinanceGroups(repo).require(s,target,true);else service.ownedShop(s,target);
        choice(body,"category","packaging","shipping","warehouse","staff","rent","other");choice(body,"method","none","orders","units","revenue");day(body.get("start"));
        var saved=new LinkedHashMap<String,Object>();for(String key:List.of("scope","target","category","method","start"))saved.put(key,body.get(key));
        saved.put("status","待执行");String id=UUID.randomUUID().toString();
        repo.jdbc().update("INSERT INTO finance_expense_rules(id,organization_id,data_json,created_by) VALUES(?,?,?,?)",id,s.organizationId(),repo.write(saved),actor);
        audit(s,actor,"EXPENSE_RULE",id,null,saved,"保存分摊规则版本；不会自动重算历史费用");
    }
}
