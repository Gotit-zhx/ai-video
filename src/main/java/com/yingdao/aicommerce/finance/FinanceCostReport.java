package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Deduplicates related orders before summing standard costs; never changes statement reconciliation. */
public final class FinanceCostReport {
    final FinanceRepository repo;
    public FinanceCostReport(FinanceRepository repo){this.repo=repo;}
    static String confirmed(String a){return a+".kind='SETTLEMENT' AND "+a+".source='API' AND "+a+".status='SETTLED' AND "+a+".order_id IS NOT NULL AND "+a+".order_id<>''";}
    static String firstSettlement(String a,boolean oneTransaction){
        return firstSettlementIdentity(a,oneTransaction)+" AND "+FinanceOrderCharges.normalOrder(a);
    }
    static String firstSettlementIdentity(String a,boolean oneTransaction){
        return confirmed(a)+" AND ("+a+".platform<>'TIKTOK_SHOP' OR "+a+".category='ORDER')"
            +" AND NOT EXISTS (SELECT 1 FROM finance_records earlier"
            +" WHERE earlier.organization_id="+a+".organization_id AND earlier.shop_id="+a+".shop_id"
            +" AND earlier.kind='SETTLEMENT' AND earlier.order_id="+a+".order_id AND earlier.source='API' AND earlier.status='SETTLED'"
            +" AND (earlier.platform<>'TIKTOK_SHOP' OR earlier.category='ORDER')"
            +" AND (earlier.record_date<"+a+".record_date"
            +(oneTransaction?" OR (earlier.record_date="+a+".record_date AND earlier.id<"+a+".id)":"")+"))";
    }
    static String hasSettlement(String a,String order){
        return "EXISTS (SELECT 1 FROM finance_records settled WHERE settled.organization_id="+a+".organization_id"
            +" AND settled.shop_id="+a+".shop_id AND settled.order_id="+order+" AND "+confirmed("settled")+")";
    }
    static String scopeKind(String kind,boolean record){
        String value=text(kind).toUpperCase(Locale.ROOT);
        if(!record&&value.equals("REFUND"))return "REFUND_SETTLED";
        return !record&&Set.of("","ALL","ORDER","SETTLED_ORDER","FEE","COST","AD","AD_GMV").contains(value)?"SETTLED_ORDER":value;
    }
    static String costStatus(String value){
        String selected=text(value).toUpperCase(Locale.ROOT);if(selected.isBlank())return "ALL";
        if(!Set.of("ALL","PENDING").contains(selected))throw bad("成本状态筛选不正确");
        return selected;
    }
    /** Applied before pagination and export; missing orders/snapshots and queued recalculations remain pending. */
    static String costStatusWhere(String value){
        return costStatus(value).equals("ALL")?"":"""
             WHERE (EXISTS (SELECT 1 FROM finance_order_cost_jobs filter_job WHERE filter_job.order_record_id=x.order_record_id AND filter_job.priority<2)
               OR NOT EXISTS (SELECT 1 FROM finance_order_costs filter_cost WHERE filter_cost.order_record_id=x.order_record_id
                 AND filter_cost.status IN ('READY','ESTIMATED','EXCLUDED')))
            """;
    }
    static final String AGGREGATE="""
        COUNT(*) order_count,
        COALESCE(SUM(CASE WHEN j.order_record_id IS NULL THEN c.label_cost_cny END),0) label_cost_cny,
        COALESCE(SUM(CASE WHEN j.order_record_id IS NULL THEN c.charges_pending END),0) charges_pending,
        SUM(CASE WHEN x.order_record_id IS NULL THEN 1 ELSE 0 END) missing_order_count,
        SUM(CASE WHEN j.order_record_id IS NOT NULL THEN 1 ELSE 0 END) queued_count,
        SUM(CASE WHEN j.priority<2 THEN 1 ELSE 0 END) product_queued_count,
        SUM(CASE WHEN j.priority=2 THEN 1 ELSE 0 END) label_queued_count,
        SUM(CASE WHEN (j.order_record_id IS NULL OR j.priority=2) AND c.status='EXCLUDED' THEN 1 ELSE 0 END) excluded_count,
        SUM(CASE WHEN (j.order_record_id IS NULL OR j.priority=2) AND c.status IN ('READY','ESTIMATED') THEN 1 ELSE 0 END) ready_count,
        SUM(CASE WHEN (j.order_record_id IS NULL OR j.priority=2) AND c.status='ESTIMATED' THEN 1 ELSE 0 END) estimated_count,
        SUM(CASE WHEN (j.order_record_id IS NULL OR j.priority=2) AND c.status IN ('READY','ESTIMATED','EXCLUDED') THEN 0 ELSE 1 END) pending_count,
        COALESCE(SUM(CASE WHEN j.order_record_id IS NULL OR j.priority=2 THEN c.known_amount_cny END),0) known_amount_cny,
        CASE WHEN SUM(CASE WHEN (j.order_record_id IS NULL OR j.priority=2) AND c.status IN ('READY','ESTIMATED','EXCLUDED') THEN 0 ELSE 1 END)=0
          THEN COALESCE(SUM(c.amount_cny),0) END amount_cny
        """;
    static String related(String kind){
        if("SAMPLE_SETTLED".equals(kind))return related("SETTLED_ORDER")
            +"o.platform='TIKTOK_SHOP' AND (JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.order_type'))='SELLER_FUND_FREE_SAMPLE'"
            +" OR LOWER(JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.is_sample_order')))='true') AND ";
        if("REFUND_SETTLED".equals(kind))return """
            SELECT DISTINCT o.id order_record_id,r.shop_id,r.order_id reference,r.record_date settlement_date
            FROM finance_records r LEFT JOIN finance_records o
              ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
              AND o.kind='ORDER' AND o.source='API' AND o.order_id=r.order_id
            WHERE
            """+firstSettlement("r",false)+" AND EXISTS (SELECT 1 FROM finance_records refund"
                +" WHERE refund.organization_id=r.organization_id AND refund.shop_id=r.shop_id"
                +" AND refund.order_id=r.order_id AND refund.kind='REFUND' AND refund.source='API') AND ";
        if("SETTLED_ORDER".equals(kind))return """
            SELECT DISTINCT o.id order_record_id,r.shop_id,r.order_id reference,r.record_date settlement_date
            FROM finance_records r LEFT JOIN finance_records o
              ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
              AND o.kind='ORDER' AND o.source='API' AND o.order_id=r.order_id
            WHERE
            """+firstSettlement("r",false)+" AND ";
        if(!Set.of("REFUND","SETTLEMENT","PENDING","BILL","RECONCILIATION").contains(kind))
            return "SELECT DISTINCT r.id order_record_id,r.shop_id,r.order_id reference FROM finance_records r WHERE r.kind='ORDER' AND "+hasSettlement("r","r.order_id")+" AND "+FinanceOrderCharges.normalOrder("r")+" AND ";
        if(Set.of("BILL","RECONCILIATION").contains(kind))return """
            SELECT DISTINCT o.id order_record_id,r.shop_id,COALESCE(NULLIF(t.order_id,''),r.external_id) reference
            FROM finance_records r LEFT JOIN finance_records t
              ON t.organization_id=r.organization_id AND t.shop_id=r.shop_id AND t.kind='SETTLEMENT'
              AND t.source='API' AND t.statement_id=r.external_id
            LEFT JOIN finance_records o ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
              AND o.kind='ORDER' AND o.source='API' AND o.order_id=t.order_id
            WHERE r.kind='BILL' AND
            """+firstSettlement("t",true)+" AND ";
        return """
            SELECT DISTINCT o.id order_record_id,r.shop_id,COALESCE(NULLIF(r.order_id,''),r.external_id) reference
            FROM finance_records r LEFT JOIN finance_records o
              ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
              AND o.kind='ORDER' AND o.source='API' AND o.order_id=r.order_id
            WHERE r.kind='%s' AND
            """.formatted(kind)+(kind.equals("PENDING")?"1=0":kind.equals("SETTLEMENT")?firstSettlement("r",false):hasSettlement("r","r.order_id"))+" AND "+FinanceOrderCharges.normalOrder("r")+" AND ";
    }
    public Map<String,Object> summary(Scope scope,Filter filter,String kind){
        return summary(scope,scopeKind(kind,false),repo.query(scope,filter,"r"));
    }
    /** Read first-settled orders and their cost snapshots once for every dashboard metric. */
    List<Map<String,Object>> dashboardFacts(Scope scope,Filter filter){
        var q=repo.query(scope,filter,"r");
        return repo.jdbc().queryForList("""
            SELECT r.shop_id,r.record_date,r.currency settlement_currency,o.currency,'ORDER' kind,'' category,
              o.status,COUNT(o.id) records,COUNT(o.amount) known_amounts,COUNT(o.revenue) known_revenues,
              SUM(o.amount) amount,SUM(o.revenue) revenue,SUM(o.impressions) impressions,
              SUM(o.clicks) clicks,SUM(o.conversions) conversions,
            """+AGGREGATE.replace("x.order_record_id","o.id")+"""
            FROM finance_records r FORCE INDEX(idx_finance_company_kind_date)
            LEFT JOIN finance_records o FORCE INDEX(idx_finance_settled_order)
              ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id AND o.order_id=r.order_id
              AND o.kind='ORDER' AND o.source='API'
            LEFT JOIN finance_order_costs c ON c.order_record_id=o.id
            LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=o.id
            WHERE
            """+firstSettlement("r",true)+" AND "+q.where()
                +" GROUP BY r.shop_id,r.record_date,r.currency,o.currency,o.status",q.args().toArray());
    }
    Map<String,Object> dashboardSummary(Scope scope,Filter filter,List<Map<String,Object>> facts){
        var byShop=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:facts){
            if(!filter.currency().isBlank()&&!filter.currency().equals(row.get("settlement_currency")))continue;
            var shop=byShop.computeIfAbsent(text(row.get("shop_id")),id->new LinkedHashMap<>(Map.of("shop_id",id)));
            for(String field:COST_SUMS){var value=decimal(row.get(field));if(value!=null)shop.merge(field,value,(a,b)->((BigDecimal)a).add((BigDecimal)b));}
        }
        byShop.values().forEach(row->row.put("amount_cny",number(row.get("pending_count"))==0?row.get("known_amount_cny"):null));
        return summary(scope,"SETTLED_ORDER",new ArrayList<>(byShop.values()));
    }
    static final List<String> COST_SUMS=List.of("order_count","missing_order_count","excluded_count","ready_count","estimated_count","pending_count","queued_count","product_queued_count","label_queued_count","known_amount_cny","label_cost_cny","charges_pending");
    private Map<String,Object> summary(Scope scope,String kind,FinanceRepository.Query q){
        String selected=text(kind).toUpperCase(Locale.ROOT);
        var perShop=repo.jdbc().queryForList("SELECT x.shop_id,"+AGGREGATE+" FROM ("+related(selected)+q.where()+") x"
            +" LEFT JOIN finance_order_costs c ON c.order_record_id=x.order_record_id"
            +" LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=x.order_record_id GROUP BY x.shop_id",q.args().toArray());
        return summary(scope,selected,perShop);
    }
    private Map<String,Object> summary(Scope scope,String selected,List<Map<String,Object>> perShop){
        var total=new LinkedHashMap<String,Object>();
        for(String key:COST_SUMS){
            BigDecimal n=BigDecimal.ZERO;for(var row:perShop){BigDecimal v=decimal(row.get(key));if(v!=null)n=n.add(v);}total.put(key,n);
        }
        total.put("amount_cny",number(total.get("pending_count"))==0?total.get("known_amount_cny"):null);
        var result=new LinkedHashMap<String,Object>();result.put("total",total);result.put("shops",perShop);result.put("currency","CNY");
        result.put("scope",selected);result.put("basis",switch(selected){
            case "SETTLED_ORDER"->"仅统计非刷单的平台已结算订单，按首次平台结算日期归属，每笔订单商品成本只计一次；待结算订单不计入。";
            case "SAMPLE_SETTLED"->"已结算样品订单的商品成本，已包含在商品成本合计中，不重复加计；以平台样品标记为准，不将零金额订单直接认定为样品。";
            case "REFUND","REFUND_SETTLED"->"按首次平台结算日期核对关联退款的已结算订单成本，同一订单去重；不重复扣减或自动退回商品成本。";
            case "PENDING"->"待结算订单尚未完成平台结算，不计入已结算商品成本。";
            case "SETTLEMENT"->"所选结算日期内首次结算的订单成本；后续重复结算或调整不重复计整单成本。";
            case "BILL","RECONCILIATION"->"仅计入归属于所选账单的首次平台结算订单成本；重复结算或调整不重复计整单成本。";
            default->"仅核对已完成平台结算的订单商品成本；未结算订单不计入。";
        });
        result.put("formula","商家 SKU 成本单价 × 购买数量 × 组合数量；完整组合装单价优先，成本固定为人民币。");
        result.put("pendingNote","待补齐订单不按零成本计算；历史月份价格标为估算。");
        boolean productRebuilding=false,labelRebuilding=false;
        if(!perShop.isEmpty()){
            String slots=String.join(",",Collections.nCopies(perShop.size(),"?"));var args=new ArrayList<Object>();args.add(scope.organizationId());perShop.forEach(row->args.add(row.get("shop_id")));
            for(var rebuild:repo.jdbc().queryForList("SELECT DISTINCT status FROM finance_cost_rebuilds WHERE organization_id=? AND status IN ('READY','LABELS') AND shop_id IN ("+slots+")",args.toArray())){
                if("READY".equals(rebuild.get("status")))productRebuilding=true;
                else labelRebuilding=true;
            }
        }
        boolean productProcessing=productRebuilding||number(total.get("product_queued_count"))>0;
        boolean labelProcessing=labelRebuilding||number(total.get("label_queued_count"))>0;
        result.put("productProcessing",productProcessing);result.put("labelProcessing",labelProcessing);
        result.put("rebuilding",productRebuilding||labelRebuilding);result.put("processing",productProcessing||labelProcessing);
        return result;
    }
    /** Drill down using the source's date basis; a selected bill is not restricted to order creation dates. */
    public Map<String,Object> orders(Scope scope,Filter filter,String kind,String recordId,int page){
        return orders(scope,filter,kind,recordId,page,"ALL");
    }
    public Map<String,Object> orders(Scope scope,Filter filter,String kind,String recordId,int page,String status){return orders(scope,filter,kind,recordId,page,status,"");}
    public Map<String,Object> orders(Scope scope,Filter filter,String kind,String recordId,int page,String status,String numbers){
        String selectedStatus=costStatus(status);
        String selected=scopeKind(kind,!text(recordId).isBlank());
        if(!Set.of("SETTLED_ORDER","SAMPLE_SETTLED","ORDER","REFUND","REFUND_SETTLED","SETTLEMENT","PENDING","BILL","RECONCILIATION").contains(selected))throw bad("成本来源类型不正确");
        FinanceRepository.Query q=text(recordId).isBlank()?repo.query(scope,filter,"r"):
                new FinanceRepository.Query("r.organization_id=? AND r.id=? AND "+FinanceRepository.visible("r"),List.of(scope.organizationId(),recordId));
        q=FinanceRecordSearch.orders(q,Set.of("BILL","RECONCILIATION").contains(selected)?"o.order_id":"r.order_id",numbers);
        String from=" FROM ("+related(selected)+q.where()+") x";
        // The exact same deduplicated relation supplies the summary and page count.
        var costs=summary(scope,selected,q);
        long total=number(map(costs.get("total")).get(selectedStatus.equals("PENDING")?"pending_count":"order_count"));
        int safePage=Math.max(1,page);var args=new ArrayList<>(q.args());args.add(50);args.add((safePage-1L)*50);
        var items=repo.jdbc().queryForList("""
            SELECT o.id,x.shop_id,o.platform,'ORDER' kind,o.external_id,COALESCE(o.order_id,x.reference) order_id,
              o.status,o.record_date,o.currency,o.amount,o.source,o.updated_at,o.raw_json,
            """+(Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED").contains(selected)?"x.settlement_date":"NULL")+" first_settlement_date"
                +from+" LEFT JOIN finance_records o ON o.id=x.order_record_id"+costStatusWhere(selectedStatus)
                +" ORDER BY "+(Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED").contains(selected)?"x.settlement_date":"o.record_date")+" DESC,x.order_record_id,x.shop_id,x.reference LIMIT ? OFFSET ?",args.toArray());
        items.forEach(repo::enrich);attach(scope,items,false);attachSettlementAmounts(scope,items);
        for(var item:items)if(item.get("id")==null){item.put("product_cost_state","订单待同步");item.put("product_cost_pending",1);}
        pendingIssues(scope,items);
        var result=new LinkedHashMap<String,Object>();
        result.put("items",items);result.put("total",total);result.put("page",safePage);result.put("pageSize",50);
        result.put("costStatus",selectedStatus);
        result.put("productCosts",costs);return result;
    }
    /** Shared page/export amounts: use the first settlement day's provider fields, never buyer payment. */
    void attachSettlementAmounts(Scope scope,List<Map<String,Object>> items){
        for(int start=0;start<items.size();start+=100){
            var batch=items.subList(start,Math.min(start+100,items.size()));
            var byShop=new LinkedHashMap<String,Set<String>>();
            for(var row:batch){
                String order=first(row,"order_id","reference");
                if(!order.isBlank())byShop.computeIfAbsent(text(row.get("shop_id")),key->new LinkedHashSet<>()).add(order);
            }
            if(byShop.isEmpty())continue;
            var predicates=new ArrayList<String>();var args=new ArrayList<Object>();args.add(scope.organizationId());
            byShop.forEach((shop,orders)->{
                predicates.add("(s.shop_id=? AND s.order_id IN ("+String.join(",",Collections.nCopies(orders.size(),"?"))+"))");
                args.add(shop);args.addAll(orders);
            });
            var values=repo.jdbc().queryForList("""
                SELECT s.shop_id,s.order_id,s.currency settlement_currency,MIN(s.record_date) first_settlement_date,
                  COUNT(*) settlement_records,COUNT(s.revenue) known_goods,COUNT(s.amount) known_net,
                  SUM(s.revenue) settlement_goods_amount,SUM(s.amount) settlement_net_amount
                FROM finance_records s FORCE INDEX(idx_finance_cost_order)
                WHERE s.organization_id=? AND
                """+firstSettlement("s",false)+" AND "+FinanceRepository.visible("s")+" AND ("+String.join(" OR ",predicates)+") GROUP BY s.shop_id,s.order_id,s.currency",args.toArray());
            var grouped=new HashMap<String,List<Map<String,Object>>>();
            for(var row:values)grouped.computeIfAbsent(text(row.get("shop_id"))+"\0"+text(row.get("order_id")),key->new ArrayList<>()).add(row);
            for(var row:batch){
                var matches=grouped.getOrDefault(text(row.get("shop_id"))+"\0"+first(row,"order_id","reference"),List.of());
                row.put("settlement_goods_amount",null);row.put("settlement_net_amount",null);row.put("settlement_currency","");
                row.put("settlement_amount_note","优惠前商品金额待补齐，不以原订单金额或净结算替代");
                if(matches.size()!=1){if(matches.size()>1)row.put("settlement_amount_note","订单存在不同结算币种，请在结算明细分币种核对");continue;}
                var value=matches.getFirst();row.put("settlement_currency",value.get("settlement_currency"));
                row.put("first_settlement_date",value.get("first_settlement_date"));
                if(number(value.get("known_goods"))==number(value.get("settlement_records"))){
                    row.put("settlement_goods_amount",value.get("settlement_goods_amount"));
                    row.put("settlement_amount_note","首次结算日的优惠前商品金额，直接取平台结算字段；后续调整请查结算明细");
                }
                if(number(value.get("known_net"))==number(value.get("settlement_records")))row.put("settlement_net_amount",value.get("settlement_net_amount"));
            }
        }
    }
    private void pendingIssues(Scope scope,List<Map<String,Object>> items){
        var pending=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:items)if(number(row.get("product_cost_pending"))>0){
            row.put("product_cost_issue",row.get("id")==null?"关联订单尚未同步":"等待后台计算成本");
            if(row.get("id")!=null)pending.put(text(row.get("id")),row);
        }
        if(pending.isEmpty())return;
        var args=new ArrayList<Object>();args.add(scope.organizationId());args.addAll(pending.keySet());
        String slots=String.join(",",Collections.nCopies(pending.size(),"?"));
        for(var snapshot:repo.jdbc().queryForList("""
            SELECT c.order_record_id,c.data_json FROM finance_order_costs c
            LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=c.order_record_id
            WHERE c.organization_id=? AND c.order_record_id IN (%s) AND (j.order_record_id IS NULL OR j.priority=2)
            """.formatted(slots),args.toArray())){
            var cost=new FinanceOrderCosts(repo).decode(snapshot.get("data_json"));var issues=new LinkedHashSet<String>();
            for(var line:rows(cost.get("lines")))if(!text(line.get("issue")).isBlank())issues.add(text(line.get("issue")));
            if(!issues.isEmpty())pending.get(text(snapshot.get("order_record_id"))).put("product_cost_issue",String.join("；",issues));
        }
    }
    public void attach(Scope scope,List<Map<String,Object>> records,boolean detail){
        new FinanceOrderCharges(repo).attach(scope,records);
        var eligible=records.stream().filter(r->Set.of("ORDER","REFUND","SETTLEMENT","BILL","PENDING").contains(text(r.get("kind")))).toList();
        if(eligible.isEmpty())return;
        // Bound each enrichment query, including full exports.
        for(int from=0;from<eligible.size();from+=100){
            var page=eligible.subList(from,Math.min(from+100,eligible.size()));
            String slots=String.join(",",Collections.nCopies(page.size(),"?"));var args=new ArrayList<Object>();args.add(scope.organizationId());page.forEach(r->args.add(r.get("id")));
            String sql="""
                SELECT x.record_id,%s FROM (
                  SELECT DISTINCT r.id record_id,r.shop_id,o.id order_record_id,
                    COALESCE(NULLIF(o.order_id,''),NULLIF(t.order_id,''),NULLIF(r.order_id,''),r.external_id) reference
                  FROM finance_records r LEFT JOIN finance_records t
                    ON r.kind='BILL' AND t.organization_id=r.organization_id AND t.shop_id=r.shop_id
                    AND t.kind='SETTLEMENT' AND t.source='API' AND t.statement_id=r.external_id
                  LEFT JOIN finance_records o ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
                    AND o.kind='ORDER' AND o.source='API'
                    AND o.order_id=CASE WHEN r.kind='BILL' THEN t.order_id ELSE r.order_id END
                  WHERE r.organization_id=? AND r.id IN (%s) AND (%s)
                ) x LEFT JOIN finance_order_costs c ON c.order_record_id=x.order_record_id
                  LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=x.order_record_id
                GROUP BY x.record_id
                """.formatted(AGGREGATE,slots,
                    "(r.kind IN ('ORDER','REFUND') AND "+hasSettlement("r","r.order_id")+" AND "+FinanceOrderCharges.normalOrder("r")+")"
                    +" OR (r.kind='SETTLEMENT' AND "+firstSettlement("r",true)+")"
                    +" OR (r.kind='BILL' AND "+firstSettlement("t",true)+")");
            var values=new HashMap<String,Map<String,Object>>();
            for(var row:repo.jdbc().queryForList(sql,args.toArray()))values.put(text(row.get("record_id")),row);
            for(var row:page){
                var costs=values.get(text(row.get("id")));
                if(costs==null){
                    row.put("product_cost_cny",null);row.put("product_cost_known_cny",null);row.put("product_cost_pending",0);
                    row.put("product_cost_state",Boolean.TRUE.equals(row.get("marketing_order"))?"刷单，不计经营成本":Set.of("BILL","SETTLEMENT").contains(text(row.get("kind")))?"本条未新增商品成本":"未结算，不计入");
                    row.put("product_cost_note","仅首次平台结算计入商品成本；未结算订单与后续重复结算不重复计入。");
                    continue;
                }
                row.put("product_cost_cny",costs.get("amount_cny"));row.put("product_cost_known_cny",costs.get("known_amount_cny"));
                row.put("product_cost_pending",costs.get("pending_count"));
                row.put("product_cost_state",number(costs.get("pending_count"))>0?"待补齐":number(costs.get("excluded_count"))==number(costs.get("order_count"))?"不计成本":number(costs.get("estimated_count"))>0?"历史价估算":"已计算");
                row.put("product_cost_note","已结算订单商品成本（人民币）；首次结算计入，退款只作关联核对，不再重复扣减。");
                if(detail&&"ORDER".equals(row.get("kind"))&&(number(costs.get("queued_count"))==0||number(costs.get("pending_count"))==0)){
                    var snapshots=repo.jdbc().queryForList("SELECT data_json,version,updated_at FROM finance_order_costs WHERE organization_id=? AND order_record_id=?",scope.organizationId(),row.get("id"));
                    if(!snapshots.isEmpty()){
                        var snapshot=new FinanceOrderCosts(repo).decode(snapshots.getFirst().get("data_json"));
                        row.put("productCost",snapshot);row.put("product_cost_version",snapshots.getFirst().get("version"));
                    }
                }
            }
        }
    }
}
