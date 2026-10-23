package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Deduplicates related orders before summing standard costs; never changes statement reconciliation. */
public final class FinanceCostReport {
    final FinanceRepository repo;
    public FinanceCostReport(FinanceRepository repo){this.repo=repo;}
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
        if(!Set.of("REFUND","SETTLEMENT","PENDING","BILL","RECONCILIATION").contains(kind))
            return "SELECT DISTINCT r.id order_record_id,r.shop_id,r.order_id reference FROM finance_records r WHERE r.kind='ORDER' AND ";
        if(Set.of("BILL","RECONCILIATION").contains(kind))return """
            SELECT DISTINCT o.id order_record_id,r.shop_id,COALESCE(NULLIF(t.order_id,''),r.external_id) reference
            FROM finance_records r LEFT JOIN finance_records t
              ON t.organization_id=r.organization_id AND t.shop_id=r.shop_id AND t.kind='SETTLEMENT'
              AND t.source='API' AND t.statement_id=r.external_id
            LEFT JOIN finance_records o ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
              AND o.kind='ORDER' AND o.source='API' AND o.order_id=t.order_id
            WHERE r.kind='BILL' AND
            """;
        return """
            SELECT DISTINCT o.id order_record_id,r.shop_id,COALESCE(NULLIF(r.order_id,''),r.external_id) reference
            FROM finance_records r LEFT JOIN finance_records o
              ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id
              AND o.kind='ORDER' AND o.source='API' AND o.order_id=r.order_id
            WHERE r.kind='%s' AND
            """.formatted(kind);
    }
    public Map<String,Object> summary(Scope scope,Filter filter,String kind){
        String selected=text(kind).toUpperCase(Locale.ROOT);
        var q=repo.query(scope,filter,"r");
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
            case "REFUND"->"所选退款关联的完整订单标准成本，同一订单去重；退款不自动退回商品成本。";
            case "SETTLEMENT","BILL","PENDING","RECONCILIATION"->"所选结算记录关联的订单标准成本，同一订单去重；不从每条结算记录重复扣减。";
            default->"按所选下单日期统计有效订单的商品标准成本；不是仓库实际出库成本。";
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
                  WHERE r.organization_id=? AND r.id IN (%s)
                ) x LEFT JOIN finance_order_costs c ON c.order_record_id=x.order_record_id
                  LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=x.order_record_id
                GROUP BY x.record_id
                """.formatted(AGGREGATE,slots);
            var values=new HashMap<String,Map<String,Object>>();
            for(var row:repo.jdbc().queryForList(sql,args.toArray()))values.put(text(row.get("record_id")),row);
            for(var row:page){
                var costs=values.get(text(row.get("id")));if(costs==null)continue;
                row.put("product_cost_cny",costs.get("amount_cny"));row.put("product_cost_known_cny",costs.get("known_amount_cny"));
                row.put("product_cost_pending",costs.get("pending_count"));
                row.put("product_cost_state",number(costs.get("pending_count"))>0?"待补齐":number(costs.get("excluded_count"))==number(costs.get("order_count"))?"不计成本":number(costs.get("estimated_count"))>0?"历史价估算":"已计算");
                row.put("product_cost_note","关联完整订单的商品标准成本（人民币），跨记录汇总时按订单去重");
                if(detail&&"ORDER".equals(row.get("kind"))){
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
