package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Exact first-settlement costs by shop and business day, including the partial-month day-one variant. */
final class FinanceSettlementCostDays {
    private static final int INSERT_ROWS=128;
    private final FinanceRepository repo;
    private final FinanceCostReport report;
    private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    record Window(LocalDate start,LocalDate end,List<String> shops){
        boolean includes(LocalDate day,String shop){
            return !day.isBefore(start)&&!day.isAfter(end)&&(shops.isEmpty()||shops.contains(shop));
        }
    }
    record Patch(List<Window> windows,List<Object[]> values){}

    FinanceSettlementCostDays(FinanceRepository repo){
        this.repo=repo;
        this.report=new FinanceCostReport(repo);
    }

    Patch calculate(Scope scope,Filter month,List<Window> windows){
        var values=new ArrayList<Object[]>();
        if(windows.isEmpty())return new Patch(windows,values);
        var full=repo.inProjectionWindows(month,windows,()->report.settlementDayFacts(scope,month));
        append(values,scope,month,full,false);
        if(windows.getFirst().start().equals(month.start())){
            var first=new Filter(month.start(),month.start(),"",month.platform(),"","day");
            var firstRows=repo.inProjectionWindow(first,first.start(),first.end(),windows.getFirst().shops(),
                ()->report.settlementDayFacts(scope,first));
            append(values,scope,month,firstRows,true);
        }
        return new Patch(windows,values);
    }

    void publish(Scope scope,Filter month,Patch patch){
        for(var window:patch.windows()){
            var args=new ArrayList<Object>(List.of(scope.organizationId(),month.platform(),month.start(),window.start(),window.end()));
            String shops=restrictShops("shop_id",window.shops(),args);
            repo.jdbc().update("""
                DELETE FROM finance_query_settlement_cost_days
                WHERE organization_id=? AND platform_filter=? AND partition_month=? AND record_date BETWEEN ? AND ?
                """+shops,args.toArray());
        }
        var values=patch.values();
        for(int offset=0;offset<values.size();offset+=INSERT_ROWS){
            var batch=values.subList(offset,Math.min(offset+INSERT_ROWS,values.size()));
            var args=new ArrayList<Object>(batch.size()*8);
            batch.forEach(row->Collections.addAll(args,row));
            repo.jdbc().update("""
                INSERT INTO finance_query_settlement_cost_days
                  (organization_id,platform_filter,partition_month,record_date,shop_id,filter_currency,partial_month,payload_json)
                VALUES
                """+String.join(",",Collections.nCopies(batch.size(),"(?,?,?,?,?,?,?,?)")),args.toArray());
        }
    }

    List<Map<String,Object>> read(Scope scope,Filter filter,List<Window> pending){
        var lastMonth=filter.end().withDayOfMonth(1);
        boolean partial=filter.end().getDayOfMonth()<filter.end().lengthOfMonth();
        var args=new ArrayList<Object>(List.of(scope.organizationId(),filter.platform(),filter.start().withDayOfMonth(1),lastMonth,
            filter.start(),filter.end(),filter.currency()));
        String partialWhere=partial?" AND d.partial_month=(d.record_date=?)":" AND d.partial_month=FALSE";
        if(partial)args.add(lastMonth);
        String filters=shopFilter(scope,filter,args);
        var rows=repo.jdbc().queryForList("""
            SELECT d.record_date,d.shop_id,d.payload_json FROM finance_query_settlement_cost_days d
            WHERE d.organization_id=? AND d.platform_filter=? AND d.partition_month BETWEEN ? AND ?
              AND d.record_date BETWEEN ? AND ? AND d.filter_currency=?
            """+partialWhere+filters+" ORDER BY d.record_date,d.shop_id",args.toArray());
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows){
            var day=LocalDate.parse(text(row.get("record_date")).substring(0,10));String shop=text(row.get("shop_id"));
            if(pending.stream().noneMatch(window->window.includes(day,shop)))
                result.add(map(json.readValue(text(row.get("payload_json")),Map.class)));
        }
        // The live repair retains the user's complete semantic period and all
        // its authorization/first-settlement predicates inside the same snapshot.
        if(!pending.isEmpty())result.addAll(repo.inProjectionWindows(filter,pending,()->report.nativeSettlementRows(scope,filter)));
        return result;
    }

    Map<String,Object> summarize(Scope scope,Filter filter,List<Window> pending){
        return report.settlementSummaryFromDays(scope,read(scope,filter,pending));
    }

    private static String shopFilter(Scope scope,Filter filter,List<Object> args){
        String where=" AND "+FinanceRepository.visible("d");
        if(!filter.shopId().isBlank()){
            where+=" AND d.shop_id=?";args.add(filter.shopId());
        }
        if(filter.groupId().isBlank())return where;
        args.add(scope.organizationId());
        String group="d.shop_id IN (SELECT id FROM finance_connections WHERE organization_id=? AND status<>'REMOVED' AND ";
        if(filter.groupId().equals("UNGROUPED"))return where+" AND (d.shop_id='UNASSIGNED' OR "+group+"group_id IS NULL))";
        args.add(filter.groupId());
        return where+" AND "+group+"group_id=?)";
    }

    private static String restrictShops(String column,List<String> shops,List<Object> args){
        if(shops.isEmpty())return "";
        args.addAll(shops);
        return " AND "+column+" IN ("+String.join(",",Collections.nCopies(shops.size(),"?"))+")";
    }

    private void append(List<Object[]> values,Scope scope,Filter month,List<Map<String,Object>> facts,boolean partial){
        for(var row:facts)values.add(new Object[]{scope.organizationId(),month.platform(),month.start(),row.get("record_date"),
            row.get("shop_id"),row.get("filter_currency"),partial,payload(row)});
    }

    private String payload(Map<String,Object> row){
        var result=new LinkedHashMap<>(row);
        result.remove("record_date");result.remove("filter_currency");
        return json.writeValueAsString(result);
    }
}
