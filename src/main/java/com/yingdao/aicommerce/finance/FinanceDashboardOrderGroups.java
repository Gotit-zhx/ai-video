package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Same-snapshot daily totals and signed replacements for orders the worker has not applied yet. */
final class FinanceDashboardOrderGroups {
    private static final String TABLE="finance_dashboard_order_groups";
    private static final List<String> DIMENSIONS=List.of("organization_id","shop_id","record_date","platform",
        "currency","fx_date","order_currency","order_status");
    private static final Set<String> DATE_DIMENSIONS=Set.of("record_date","fx_date");
    private static final List<String> LEDGER_SUMS=List.of("records","known_amounts","known_revenues","amount",
        "revenue","impressions","clicks","conversions");
    private static final Map<String,String> NULL_COUNTS=Map.of("amount","known_amounts","revenue","known_revenues",
        "impressions","known_impressions","clicks","known_clicks","conversions","known_conversions");
    private static final List<String> EXTRA_COUNTS=List.of("impressions","clicks","conversions");
    private static final List<String> REPORT_SUMS=Stream.concat(LEDGER_SUMS.stream(),FinanceCostReport.COST_SUMS.stream()).toList();
    private static final List<String> TOTALS=Stream.concat(REPORT_SUMS.stream(),
        Stream.concat(Stream.of("cost_before_pending"),EXTRA_COUNTS.stream().map(NULL_COUNTS::get))).toList();
    private static final String CHANGED_DAYS="""
        (SELECT DISTINCT j.day_key,j.shop_key FROM JSON_TABLE(CAST(? AS JSON),'$[*]' COLUMNS(
          day_key DATE PATH '$[0]',shop_key VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$[1]'
        )) j) x
        JOIN finance_dashboard_order_days coverage ON coverage.organization_id=? AND coverage.record_date=x.day_key
          AND coverage.ready AND coverage.groups_ready
        """;

    private final FinanceRepository repo;

    FinanceDashboardOrderGroups(FinanceRepository repo){this.repo=repo;}

    Optional<List<Map<String,Object>>> read(Scope scope,FinanceRepository.Query part,List<List<String>> orders,
                                            String nativeValues,List<Object> nativeArgs){
        String table=" FROM "+TABLE+" d WHERE "+part.where();
        if(orders.isEmpty())return verified(repo.jdbc().queryForList(reportSelect("d")+table+
            grouping("d"),part.args().toArray()));

        var args=new ArrayList<Object>();
        args.add(repo.write(orders));
        args.addAll(part.args());
        args.add(scope.organizationId());
        args.addAll(part.args());
        args.addAll(nativeArgs);
        // Keep NULL separate from a known zero, including after the last known value is removed.
        String sql="WITH changed AS ("+FinanceDashboardOrderFacts.KEY_ROWS+") "+reportSelect("v")
            +" FROM (SELECT "+dimensions("d")+","+qualified("d",TOTALS)+table
            +" UNION ALL SELECT "+dimensions("d")+","+orderTotals("d",true)
            +" FROM changed x STRAIGHT_JOIN "+FinanceDashboardOrderFacts.ORDER_VALUES+" d"
            +" ON d.organization_id=? AND d.shop_id=x.shop_key AND d.order_id=x.order_key WHERE "+part.where()
            +" UNION ALL SELECT "+dimensions("n")+","+orderTotals("n",false)+" FROM ("+nativeValues+") n) v"
            +grouping("v")+" HAVING SUM(v.order_count)>0";
        return verified(repo.jdbc().queryForList(sql,args.toArray()));
    }

    void refreshDay(Scope scope,LocalDate day){
        removeDay(scope,day);
        rebuild(" FROM "+FinanceDashboardOrderFacts.ORDER_VALUES+" d FORCE INDEX(idx_dashboard_order_day)"
            +" WHERE d.organization_id=? AND d.record_date=?",scope.organizationId(),day);
    }

    void refreshChanged(Scope scope,Set<List<String>> shopDays){
        if(shopDays.isEmpty())return;
        String pairs=repo.write(shopDays);
        repo.jdbc().update("DELETE g FROM "+CHANGED_DAYS+" STRAIGHT_JOIN "+TABLE+" g"
            +" ON g.organization_id=coverage.organization_id AND g.record_date=x.day_key AND g.shop_id=x.shop_key",
            pairs,scope.organizationId());
        rebuild(" FROM "+CHANGED_DAYS+" STRAIGHT_JOIN "+FinanceDashboardOrderFacts.ORDER_VALUES+
            " d FORCE INDEX(idx_dashboard_order_day)"+
            " ON d.organization_id=coverage.organization_id AND d.record_date=x.day_key AND d.shop_id=x.shop_key",
            pairs,scope.organizationId());
    }

    void removeDay(Scope scope,LocalDate day){
        repo.jdbc().update("DELETE FROM "+TABLE+" WHERE organization_id=? AND record_date=?",scope.organizationId(),day);
    }

    private void rebuild(String source,Object... args){
        // A collation can merge byte-distinct currencies or statuses. Keep their source
        // spellings through compaction so the reader can identify ambiguous native groups.
        String keys=DIMENSIONS.stream().map(key->DATE_DIMENSIONS.contains(key)?"d."+key:"ANY_VALUE(d."+key+")")
            .collect(Collectors.joining(","));
        String groups=DIMENSIONS.stream().map(key->DATE_DIMENSIONS.contains(key)?"d."+key:"CAST(d."+key+" AS BINARY)")
            .collect(Collectors.joining(","));
        String select=Stream.concat(REPORT_SUMS.stream(),Stream.of("cost_before_pending"))
            .map(name->"SUM(d."+name+")").collect(Collectors.joining(","));
        String counts=EXTRA_COUNTS.stream().map(name->"COUNT(d."+name+")").collect(Collectors.joining(","));
        repo.jdbc().update("INSERT INTO "+TABLE+"("+String.join(",",DIMENSIONS)+","+String.join(",",TOTALS)+")"
            +" SELECT "+keys+","+select+","+counts+source+" GROUP BY "+groups,args);
    }

    static void collectShopDays(Set<List<String>> pairs,List<Map<String,Object>> values){
        for(var row:values)pairs.add(List.of(text(row.get("record_date")),text(row.get("shop_id"))));
    }

    private static String reportSelect(String a){
        String metrics=REPORT_SUMS.stream().map(name->{
            String sum="SUM("+a+"."+name+")";
            String count=NULL_COUNTS.get(name);
            return (count==null?sum:"IF(SUM("+a+"."+count+")>0,"+sum+",NULL)")+" "+name;
        }).collect(Collectors.joining(","));
        return "SELECT "+qualified(a,List.of("shop_id","record_date","fx_date"))+","+a+".currency settlement_currency,"
            +a+".order_currency currency,'ORDER' kind,'' category,"+a+".order_status status,"+metrics
            +",IF(SUM("+a+".pending_count)=0,SUM("+a+".cost_before_pending),NULL) amount_cny,"+byteVariants(a);
    }

    static String byteVariants(String a){
        String clauses=List.of("shop_id","currency","order_currency","order_status").stream()
            .map(key->"COUNT(DISTINCT CAST("+a+"."+key+" AS BINARY))>1").collect(Collectors.joining(" OR "));
        return "("+clauses+")+0 byte_variants";
    }

    static Optional<List<Map<String,Object>>> verified(List<Map<String,Object>> rows){
        if(rows.stream().anyMatch(row->number(row.get("byte_variants"))>0))return Optional.empty();
        rows.forEach(row->row.remove("byte_variants"));
        return Optional.of(rows);
    }

    private static String grouping(String a){
        return " GROUP BY "+qualified(a,List.of("shop_id","record_date","fx_date","currency","order_currency","order_status"));
    }

    private static String orderTotals(String a,boolean subtract){
        String sign=subtract?"-":"";
        String members=Stream.concat(REPORT_SUMS.stream(),Stream.of("cost_before_pending"))
            .map(name->sign+a+"."+name).collect(Collectors.joining(","));
        String counts=EXTRA_COUNTS.stream().map(name->sign+"("+a+"."+name+" IS NOT NULL)").collect(Collectors.joining(","));
        return members+","+counts;
    }

    private static String dimensions(String a){return qualified(a,DIMENSIONS);}
    private static String qualified(String a,List<String> columns){
        return columns.stream().map(name->a+"."+name).collect(Collectors.joining(","));
    }
}
