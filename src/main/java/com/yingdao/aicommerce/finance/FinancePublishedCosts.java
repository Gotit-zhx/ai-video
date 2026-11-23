package com.yingdao.aicommerce.finance;

import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** A report reads committed cost amounts. Processing indicators read the currently scoped jobs. */
final class FinancePublishedCosts {
    record Mode(boolean separated,String stamp){
        static final Mode LEGACY=new Mode(false,"");
        String cacheSuffix(){return stamp.isEmpty()?"":":cost-progress:"+separated+":"+stamp;}
    }
    record Work(boolean rebuilding,boolean product,boolean label){
        boolean processing(){return rebuilding||product||label;}
        List<Boolean> receipt(){return List.of(rebuilding,product,label);}
        Map<String,Boolean> view(){
            return Map.of("rebuilding",rebuilding,"productProcessing",rebuilding||product,
                    "labelProcessing",label,"processing",processing());
        }
    }

    static final Set<String> KINDS=Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED","SETTLEMENT",
            "ORDER","REFUND","BILL","RECONCILIATION","PENDING");

    private final FinanceRepository repo;
    FinancePublishedCosts(FinanceRepository repo){this.repo=repo;}

    static String jobJoin(String orderRecord,boolean separated){
        // Keep one shared aggregate expression. With a constant false join the
        // job columns are NULL and MySQL eliminates the job-table lookup.
        return " LEFT JOIN finance_order_cost_jobs j ON "+(separated?"FALSE":"j.order_record_id="+orderRecord)+" ";
    }

    Work work(Scope scope,Filter filter,String kind,List<String> shops){
        if(shops.isEmpty()||"PENDING".equals(kind))return new Work(false,false,false);
        var selected=new ArrayList<Object>();selected.add(scope.organizationId());selected.addAll(shops);
        String slots=String.join(",",Collections.nCopies(shops.size(),"?"));
        boolean rebuilding=!repo.jdbc().queryForList("""
            SELECT shop_id FROM finance_cost_rebuilds WHERE organization_id=? AND shop_id IN (%s)
              AND status='READY' LIMIT 1
            """.formatted(slots),selected.toArray()).isEmpty()||FinanceCostTargetScans.pending(repo,scope,shops)
                ||new FinanceCostPriceScans(repo).pending(scope,filter);
        String condition="j.organization_id=?"
                +" AND j.shop_id IN ("+slots+") AND j.priority ";
        var range=repo.query(scope,filter,"r");
        var args=new ArrayList<>(selected);args.addAll(range.args());
        boolean product=hasScopedJob(kind,condition,"<2",range,selected,args);
        boolean label=hasScopedJob(kind,condition,"=2",range,selected,args);
        return new Work(rebuilding,product,label);
    }

    private boolean hasScopedJob(String kind,String condition,String priorities,FinanceRepository.Query range,
                                 List<Object> selected,List<Object> args){
        String jobs="SELECT 1 FROM finance_order_cost_jobs j FORCE INDEX(idx_cost_job_shop) WHERE "+condition+priorities;
        if(repo.jdbc().queryForList(jobs+" LIMIT 1",selected.toArray()).isEmpty())return false;
        if(Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED","SETTLEMENT").contains(kind)){
            // Fetch the queued order by its PK before looking for its first settlement.
            // An unrestricted EXISTS can instead repeat the whole month scan for each job.
            var scoped=firstSettlementJob(kind,range);
            String query="""
                SELECT 1 FROM finance_order_cost_jobs j FORCE INDEX(idx_cost_job_shop)
                STRAIGHT_JOIN finance_records o FORCE INDEX(PRIMARY) ON o.id=j.order_record_id
                  AND o.organization_id=j.organization_id AND o.shop_id=j.shop_id
                  AND o.kind='ORDER' AND o.source='API'
                WHERE
                """+condition+priorities+" "+scoped+" LIMIT 1";
            return !repo.jdbc().queryForList(query,args.toArray()).isEmpty();
        }
        var orders=FinanceCostReport.related(kind)+range.where()+" AND "
            +(kind.equals("ORDER")?"r.id":"o.id")+"=j.order_record_id AND r.shop_id=j.shop_id";
        return !repo.jdbc().queryForList(jobs+" AND EXISTS ("+orders+") LIMIT 1",args.toArray()).isEmpty();
    }

    private static String firstSettlementJob(String kind,FinanceRepository.Query range){
        String subtype=switch(kind){
            case "SAMPLE_SETTLED" -> """
                AND o.platform='TIKTOK_SHOP' AND (JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.order_type'))='SELLER_FUND_FREE_SAMPLE'
                  OR LOWER(JSON_UNQUOTE(JSON_EXTRACT(o.raw_json,'$.is_sample_order')))='true')
                """;
            case "REFUND_SETTLED" -> """
                AND EXISTS (SELECT 1 FROM finance_records refund FORCE INDEX(idx_finance_settled_order)
                  WHERE refund.organization_id=r.organization_id AND refund.shop_id=r.shop_id
                  AND refund.order_id=r.order_id AND refund.kind='REFUND' AND refund.source='API')
                """;
            default -> "";
        };
        return """
            AND EXISTS (SELECT 1 FROM finance_records r FORCE INDEX(idx_finance_settled_order)
              WHERE r.organization_id=o.organization_id AND r.shop_id=o.shop_id AND r.order_id=o.order_id AND
            """+FinanceCostReport.firstSettlement("r",false)+" AND "+range.where()+" "+subtype+" LIMIT 1)";
    }

    static List<String> shopIds(List<Map<String,Object>> shops,Filter filter){
        return shops.stream().filter(shop->FinanceGroups.matches(shop,filter))
                .map(shop->text(shop.get("id"))).filter(id->!id.isBlank()).distinct().toList();
    }

    static Filter responseFilter(Filter filter,Map<String,Object> result){
        var response=map(result.get("filters"));
        if(response.isEmpty()||Objects.equals(filter.currency(),response.get("currency")))return filter;
        return new Filter(filter.start(),filter.end(),filter.shopId(),filter.platform(),text(response.get("currency")),
                filter.granularity(),filter.groupId());
    }

    Map<String,Object> apply(Scope scope,Filter filter,Map<String,Object> source){
        if(!repo.costProgressMode(scope).separated())return source;
        var report=map(source.get("productCosts"));
        var records=map(source.get("records"));
        var detail=map(records.get("productCosts"));
        if(report.isEmpty()&&detail.isEmpty())return source;
        Filter actual=responseFilter(filter,source);
        var scopes=new LinkedHashSet<String>();
        if(!report.isEmpty())scopes.add(text(report.get("scope")));
        if(!detail.isEmpty())scopes.add(text(detail.get("scope")));
        var shops=shopIds(rows(source.get("shops")),filter);
        Map<String,Work> byScope=workByScope(scope,actual,shops,scopes);
        var result=new LinkedHashMap<>(source);
        var main=byScope.get(text(report.get("scope")));
        if(main!=null){
            result.put("productCosts",withWork(report,main));
            if(main.processing()&&source.get("profit") instanceof Map<?,?>)
                result.put("profit",pendingProfit(map(source.get("profit")),main));
        }
        var nested=byScope.get(text(detail.get("scope")));
        if(nested!=null){
            var copy=new LinkedHashMap<>(records);
            copy.put("productCosts",withWork(detail,nested));
            result.put("records",copy);
        }
        result.put("costProgressRevision",revision(byScope));
        result.put("costProgressScopes",List.copyOf(byScope.keySet()));
        return result;
    }

    Map<String,Work> workByScope(Scope scope,Filter filter,List<String> shops,Collection<String> kinds){
        var result=new TreeMap<String,Work>();
        for(String kind:kinds)if(KINDS.contains(kind))result.computeIfAbsent(kind,key->work(scope,filter,key,shops));
        return result;
    }

    static Map<String,Map<String,Boolean>> view(Map<String,Work> scopes){
        var result=new TreeMap<String,Map<String,Boolean>>();
        scopes.forEach((key,value)->result.put(key,value.view()));
        return result;
    }

    String revision(Map<String,Work> scopes){
        var receipts=new TreeMap<String,List<Boolean>>();
        scopes.forEach((key,value)->receipts.put(key,value.receipt()));
        return hash(repo.write(receipts));
    }

    static Map<String,Object> withWork(Map<String,Object> source,Work work){
        var result=new LinkedHashMap<>(source);
        result.putAll(work.view());
        return result;
    }

    private static Map<String,Object> pendingProfit(Map<String,Object> source,Work work){
        var result=new LinkedHashMap<>(source);
        String state=text(source.get("status"));
        boolean product=work.rebuilding()||work.product();
        if(product)result.put("productCost",null);
        result.put("amount",null);
        result.put("operatingCost",null);
        if(Set.of("READY","ESTIMATED","MISSING_FX","INCOMPLETE_CHARGES").contains(state)&&product)
            result.put("status","INCOMPLETE_COST");
        else if(Set.of("READY","ESTIMATED","MISSING_FX").contains(state)&&work.label())
            result.put("status","INCOMPLETE_CHARGES");
        var notes=new LinkedHashSet<String>();
        if(source.get("notes") instanceof List<?> values)values.forEach(note->notes.add(text(note)));
        notes.add(product?"已结算订单成本正在复核，仍可查看上次的商品成本金额，完整利润待确认":"贴单费用正在更新，完整利润待确认");
        result.put("notes",List.copyOf(notes));
        return result;
    }
}
