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
        return confirmed(a)+" AND NOT EXISTS (SELECT 1 FROM finance_records earlier"
            +" WHERE earlier.organization_id="+a+".organization_id AND earlier.shop_id="+a+".shop_id"
            +" AND earlier.kind='SETTLEMENT' AND earlier.order_id="+a+".order_id AND earlier.source='API' AND earlier.status='SETTLED'"
            +" AND (earlier.record_date<"+a+".record_date"
            +(oneTransaction?" OR (earlier.record_date="+a+".record_date AND earlier.id<"+a+".id)":"")+"))";
    }
    static String hasSettlement(String a,String order){
        return "EXISTS (SELECT 1 FROM finance_records settled WHERE settled.organization_id="+a+".organization_id"
            +" AND settled.shop_id="+a+".shop_id AND settled.order_id="+order+" AND "+confirmed("settled")+")";
    }
    static String scopeKind(String kind,boolean record){
        String value=text(kind).toUpperCase(Locale.ROOT);
        return !record&&Set.of("","ALL","ORDER","SETTLED_ORDER","FEE","COST","AD","AD_GMV").contains(value)?"SETTLED_ORDER":value;
    }
    static final String AGGREGATE="""
        COUNT(*) order_count,
        SUM(CASE WHEN x.order_record_id IS NULL THEN 1 ELSE 0 END) missing_order_count,
        SUM(CASE WHEN j.order_record_id IS NOT NULL THEN 1 ELSE 0 END) queued_count,
        SUM(CASE WHEN j.order_record_id IS NULL AND c.status='EXCLUDED' THEN 1 ELSE 0 END) excluded_count,
        SUM(CASE WHEN j.order_record_id IS NULL AND c.status IN ('READY','ESTIMATED') THEN 1 ELSE 0 END) ready_count,
        SUM(CASE WHEN j.order_record_id IS NULL AND c.status='ESTIMATED' THEN 1 ELSE 0 END) estimated_count,
        SUM(CASE WHEN j.order_record_id IS NULL AND c.status IN ('READY','ESTIMATED','EXCLUDED') THEN 0 ELSE 1 END) pending_count,
        COALESCE(SUM(CASE WHEN j.order_record_id IS NULL THEN c.known_amount_cny END),0) known_amount_cny,
        CASE WHEN SUM(CASE WHEN j.order_record_id IS NULL AND c.status IN ('READY','ESTIMATED','EXCLUDED') THEN 0 ELSE 1 END)=0
          THEN COALESCE(SUM(c.amount_cny),0) END amount_cny
        """;
    static String related(String kind){
        if("SETTLED_ORDER".equals(kind))return """
            SELECT DISTINCT o.id order_record_id,r.shop_id,r.order_id reference,r.record_date settlement_date
            FROM finance_records r LEFT JOIN finance_records o
              ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
              AND o.kind='ORDER' AND o.source='API' AND o.order_id=r.order_id
            WHERE
            """+firstSettlement("r",false)+" AND ";
        if(!Set.of("REFUND","SETTLEMENT","PENDING","BILL","RECONCILIATION").contains(kind))
            return "SELECT DISTINCT r.id order_record_id,r.shop_id,r.order_id reference FROM finance_records r WHERE r.kind='ORDER' AND "+hasSettlement("r","r.order_id")+" AND ";
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
            """.formatted(kind)+(kind.equals("PENDING")?"1=0":kind.equals("SETTLEMENT")?firstSettlement("r",false):hasSettlement("r","r.order_id"))+" AND ";
    }
    public Map<String,Object> summary(Scope scope,Filter filter,String kind){
        return summary(scope,scopeKind(kind,false),repo.query(scope,filter,"r"));
    }
    private Map<String,Object> summary(Scope scope,String kind,FinanceRepository.Query q){
        String selected=text(kind).toUpperCase(Locale.ROOT);
        var perShop=repo.jdbc().queryForList("SELECT x.shop_id,"+AGGREGATE+" FROM ("+related(selected)+q.where()+") x"
            +" LEFT JOIN finance_order_costs c ON c.order_record_id=x.order_record_id"
            +" LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=x.order_record_id GROUP BY x.shop_id",q.args().toArray());
        var total=new LinkedHashMap<String,Object>();
        for(String key:List.of("order_count","missing_order_count","excluded_count","ready_count","estimated_count","pending_count","queued_count","known_amount_cny")){
            BigDecimal n=BigDecimal.ZERO;for(var row:perShop){BigDecimal v=decimal(row.get(key));if(v!=null)n=n.add(v);}total.put(key,n);
        }
        total.put("amount_cny",number(total.get("pending_count"))==0?total.get("known_amount_cny"):null);
        var result=new LinkedHashMap<String,Object>();result.put("total",total);result.put("shops",perShop);result.put("currency","CNY");
        result.put("scope",selected);result.put("basis",switch(selected){
            case "SETTLED_ORDER"->"仅统计平台已结算订单，按首次平台结算日期归属，每笔订单商品成本只计一次；待结算订单不计入。";
            case "REFUND"->"仅核对所选退款关联的已结算订单成本，同一订单去重；不重复扣减或自动退回商品成本。";
            case "PENDING"->"待结算订单尚未完成平台结算，不计入已结算商品成本。";
            case "SETTLEMENT"->"所选结算日期内首次结算的订单成本；后续重复结算或调整不重复计整单成本。";
            case "BILL","RECONCILIATION"->"仅计入归属于所选账单的首次平台结算订单成本；重复结算或调整不重复计整单成本。";
            default->"仅核对已完成平台结算的订单商品成本；未结算订单不计入。";
        });
        result.put("formula","商家 SKU 成本单价 × 购买数量 × 组合数量；完整组合装单价优先，成本固定为人民币。");
        result.put("pendingNote","待补齐订单不按零成本计算；历史月份价格标为估算。");
        boolean rebuilding=false;
        if(!perShop.isEmpty()){
            String slots=String.join(",",Collections.nCopies(perShop.size(),"?"));var args=new ArrayList<Object>();args.add(scope.organizationId());perShop.forEach(row->args.add(row.get("shop_id")));
            rebuilding=number(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_cost_rebuilds WHERE organization_id=? AND status='READY' AND shop_id IN ("+slots+")",Long.class,args.toArray()))>0;
        }
        result.put("rebuilding",rebuilding);result.put("processing",rebuilding||number(total.get("queued_count"))>0);
        return result;
    }
    /** Drill down using the source's date basis; a selected bill is not restricted to order creation dates. */
    public Map<String,Object> orders(Scope scope,Filter filter,String kind,String recordId,int page){
        String selected=scopeKind(kind,!text(recordId).isBlank());
        if(!Set.of("SETTLED_ORDER","ORDER","REFUND","SETTLEMENT","PENDING","BILL","RECONCILIATION").contains(selected))throw bad("成本来源类型不正确");
        FinanceRepository.Query q=text(recordId).isBlank()?repo.query(scope,filter,"r"):
                new FinanceRepository.Query("r.organization_id=? AND r.id=? AND "+FinanceRepository.visible("r"),List.of(scope.organizationId(),recordId));
        String from=" FROM ("+related(selected)+q.where()+") x";
        // The exact same deduplicated relation supplies the summary and page count.
        var costs=summary(scope,selected,q);
        long total=number(map(costs.get("total")).get("order_count"));
        int safePage=Math.max(1,page);var args=new ArrayList<>(q.args());args.add(50);args.add((safePage-1L)*50);
        var items=repo.jdbc().queryForList("""
            SELECT o.id,x.shop_id,o.platform,'ORDER' kind,o.external_id,COALESCE(o.order_id,x.reference) order_id,
              o.status,o.record_date,o.currency,o.amount,o.source,o.updated_at,o.raw_json,
            """+("SETTLED_ORDER".equals(selected)?"x.settlement_date":"NULL")+" first_settlement_date"
                +from+" LEFT JOIN finance_records o ON o.id=x.order_record_id"
                +" ORDER BY "+("SETTLED_ORDER".equals(selected)?"x.settlement_date":"o.record_date")+" DESC,x.order_record_id,x.shop_id,x.reference LIMIT ? OFFSET ?",args.toArray());
        items.forEach(repo::enrich);attach(scope,items,false);
        for(var item:items)if(item.get("id")==null){item.put("product_cost_state","订单待同步");item.put("product_cost_pending",1);}
        var result=new LinkedHashMap<String,Object>();
        result.put("items",items);result.put("total",total);result.put("page",safePage);result.put("pageSize",50);
        result.put("productCosts",costs);return result;
    }
    public void attach(Scope scope,List<Map<String,Object>> records,boolean detail){
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
                    "(r.kind IN ('ORDER','REFUND') AND "+hasSettlement("r","r.order_id")+")"
                    +" OR (r.kind='SETTLEMENT' AND "+firstSettlement("r",true)+")"
                    +" OR (r.kind='BILL' AND "+firstSettlement("t",true)+")");
            var values=new HashMap<String,Map<String,Object>>();
            for(var row:repo.jdbc().queryForList(sql,args.toArray()))values.put(text(row.get("record_id")),row);
            for(var row:page){
                var costs=values.get(text(row.get("id")));
                if(costs==null){
                    row.put("product_cost_cny",null);row.put("product_cost_known_cny",null);row.put("product_cost_pending",0);
                    row.put("product_cost_state",Set.of("BILL","SETTLEMENT").contains(text(row.get("kind")))?"本条未新增商品成本":"未结算，不计入");
                    row.put("product_cost_note","仅首次平台结算计入商品成本；未结算订单与后续重复结算不重复计入。");
                    continue;
                }
                row.put("product_cost_cny",costs.get("amount_cny"));row.put("product_cost_known_cny",costs.get("known_amount_cny"));
                row.put("product_cost_pending",costs.get("pending_count"));
                row.put("product_cost_state",number(costs.get("pending_count"))>0?"待补齐":number(costs.get("excluded_count"))==number(costs.get("order_count"))?"不计成本":number(costs.get("estimated_count"))>0?"历史价估算":"已计算");
                row.put("product_cost_note","已结算订单商品成本（人民币）；首次结算计入，退款只作关联核对，不再重复扣减。");
                if(detail&&"ORDER".equals(row.get("kind"))&&number(costs.get("queued_count"))==0){
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
