package com.yingdao.aicommerce.finance;

import java.sql.Date;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** An explicitly confirmed month is not an exact order-completion timestamp. */
final class FinanceReportingMonths {
    static final String SOURCE="用户确认归属月份；非接口完成时间";
    static final String NOTE="按月校正的订单只计入覆盖完整归属月的查询；不推测具体完成日。含此类订单的趋势按月展示，平台原始日期另列。";
    static final List<String> RAW_FIELDS=List.of("_finance_reporting_month","_finance_original_settlement_date","_finance_reporting_source");
    private final FinanceRepository repo;
    FinanceReportingMonths(FinanceRepository repo){this.repo=repo;}

    Entry apply(Scope s,Entry e){
        if(!e.platform().equals("TIKTOK_SHOP")||!e.source().equals("API")
                ||!Set.of("SETTLEMENT","FEE").contains(e.kind())||text(e.orderId()).isBlank())return e;
        if(FinanceSettlementDates.exportBasis(e.raw())){
            var raw=new LinkedHashMap<>(e.raw());
            for(String key:RAW_FIELDS)if(raw.containsKey(key)){
                raw.put("_finance_legacy"+key.substring("_finance".length()),raw.remove(key));
            }
            return new Entry(e.shopId(),e.platform(),e.kind(),e.externalId(),e.orderId(),e.statementId(),e.category(),
                    e.status(),e.date(),e.currency(),e.amount(),e.revenue(),e.impressions(),e.clicks(),e.conversions(),e.source(),raw);
        }
        var matches=repo.jdbc().queryForList("""
            SELECT reporting_month FROM finance_reporting_months
            WHERE organization_id=? AND shop_id=? AND order_id=? AND statement_id=?
            """,s.organizationId(),e.shopId(),e.orderId(),e.statementId());
        if(matches.isEmpty())return e;
        var month=YearMonth.from(LocalDate.parse(text(matches.getFirst().get("reporting_month"))));
        var raw=new LinkedHashMap<>(e.raw());
        // Never use a prior reporting anchor as the original provider date on a rebuild.
        String original=first(raw,"_finance_original_settlement_date");
        if(original.isBlank()||!raw.containsKey("_finance_reporting_month"))original=e.date().toString();
        raw.put("_finance_original_settlement_date",original);
        raw.put("_finance_reporting_month",month.toString());raw.put("_finance_reporting_source",SOURCE);
        return new Entry(e.shopId(),e.platform(),e.kind(),e.externalId(),e.orderId(),e.statementId(),e.category(),
                e.status(),month.atDay(1),e.currency(),e.amount(),e.revenue(),e.impressions(),e.clicks(),e.conversions(),e.source(),raw);
    }

    /** Partial-day queries must not silently pretend the first day is the completion date. */
    static FinanceRepository.Query constrain(Scope s,FinanceRepository.Query q,Filter f,String alias){
        if(f.start().getDayOfMonth()==1&&f.end().equals(YearMonth.from(f.end()).atEndOfMonth()))return q;
        String a=alias.isEmpty()?"finance_records":alias;
        var args=new ArrayList<>(q.args());args.add(s.organizationId());args.add(Date.valueOf(f.start()));args.add(Date.valueOf(f.end()));
        // Materialize only corrected record IDs. The large dashboard scan keeps its
        // covering index instead of fetching order/statement/raw JSON for every row.
        return new FinanceRepository.Query(q.where()+" AND "+a+".id NOT IN (SELECT corrected.id"
                +" FROM finance_reporting_months rm STRAIGHT_JOIN finance_records corrected FORCE INDEX(idx_finance_marketing_order)"
                +" ON corrected.organization_id=rm.organization_id AND corrected.shop_id=rm.shop_id"
                +" AND corrected.order_id=rm.order_id AND corrected.statement_id=rm.statement_id"
                +" AND corrected.kind IN ('SETTLEMENT','FEE') AND corrected.source='API' AND corrected.platform='TIKTOK_SHOP'"
                +" AND JSON_EXTRACT(corrected.raw_json,'$._finance_reporting_month') IS NOT NULL"
                +" WHERE rm.organization_id=? AND (rm.reporting_month<? OR LAST_DAY(rm.reporting_month)>?))",args);
    }

    List<Map<String,Object>> coverage(Scope s,Filter f){
        if(!f.platform().isBlank()&&!f.platform().equals("TIKTOK_SHOP"))return List.of();
        String where="m.organization_id=? AND m.reporting_month BETWEEN ? AND ? AND c.status<>'REMOVED'"
                +" AND EXISTS(SELECT 1 FROM finance_records corrected WHERE corrected.organization_id=m.organization_id"
                +" AND corrected.shop_id=m.shop_id AND corrected.order_id=m.order_id AND corrected.statement_id=m.statement_id"
                +" AND corrected.kind='SETTLEMENT' AND corrected.source='API'"
                +" AND JSON_EXTRACT(corrected.raw_json,'$._finance_reporting_month') IS NOT NULL)";
        var args=new ArrayList<Object>(List.of(s.organizationId(),Date.valueOf(f.start().withDayOfMonth(1)),Date.valueOf(f.end())));
        if(!f.shopId().isBlank()){where+=" AND m.shop_id=?";args.add(f.shopId());}
        if(!f.currency().isBlank()){where+=" AND c.currency=?";args.add(f.currency());}
        if(f.groupId().equals("UNGROUPED"))where+=" AND c.group_id IS NULL";
        else if(!f.groupId().isBlank()){where+=" AND c.group_id=?";args.add(f.groupId());}
        var result=repo.jdbc().queryForList("""
            SELECT m.reporting_month,COUNT(*) orders FROM finance_reporting_months m
            JOIN finance_connections c ON c.organization_id=m.organization_id AND c.id=m.shop_id
            WHERE
            """+where+" GROUP BY m.reporting_month ORDER BY m.reporting_month",args.toArray());
        for(var row:result){
            var month=YearMonth.from(LocalDate.parse(text(row.get("reporting_month"))));
            row.put("reporting_month",month.toString());
            row.put("included",!f.start().isAfter(month.atDay(1))&&!f.end().isBefore(month.atEndOfMonth()));
        }
        return result;
    }

    static void enrich(Map<String,Object> row,Map<String,Object> raw){
        String month=text(raw.get("_finance_reporting_month"));
        if(month.isBlank())return;
        row.put("reporting_month",month);
        row.put("platform_settlement_date",raw.get("_finance_original_settlement_date"));
        row.put("reporting_date_source",SOURCE);
    }
    static String dateLabel(Map<String,Object> row,String field){
        return text(row.get("reporting_month")).isBlank()?text(row.get(field)):row.get("reporting_month")+"（按月校正）";
    }

    /** Small indexed page lookup. Orders/refunds inherit the first settlement's reporting provenance. */
    void attach(Scope s,List<Map<String,Object>> items){
        var selected=items.stream().filter(r->Set.of("ORDER","REFUND").contains(text(r.get("kind")))
                &&!text(r.get("order_id")).isBlank()).toList();
        for(int offset=0;offset<selected.size();offset+=100){
            var batch=selected.subList(offset,Math.min(offset+100,selected.size()));
            var predicates=new ArrayList<String>();var args=new ArrayList<Object>();args.add(s.organizationId());
            var identities=new HashSet<String>();
            for(var row:batch){
                String key=text(row.get("shop_id"))+"\0"+text(row.get("order_id"));
                if(!identities.add(key))continue;
                predicates.add("(r.shop_id=? AND r.order_id=?)");args.add(row.get("shop_id"));args.add(row.get("order_id"));
            }
            var records=repo.jdbc().queryForList("""
                SELECT r.shop_id,r.order_id,JSON_EXTRACT(r.raw_json,'$._finance_reporting_month') month_json,
                  JSON_EXTRACT(r.raw_json,'$._finance_original_settlement_date') date_json
                FROM finance_records r FORCE INDEX(idx_finance_cost_order)
                WHERE r.organization_id=? AND
                """+FinanceCostReport.firstSettlementIdentity("r",true)+" AND ("+String.join(" OR ",predicates)+")",args.toArray());
            for(var record:records){
                if(record.get("month_json")==null)continue;
                var raw=repo.read("{\"_finance_reporting_month\":"+record.get("month_json")
                        +",\"_finance_original_settlement_date\":"+record.get("date_json")+"}");
                for(var row:batch)if(Objects.equals(text(row.get("shop_id")),text(record.get("shop_id")))
                        &&Objects.equals(text(row.get("order_id")),text(record.get("order_id"))))enrich(row,raw);
            }
        }
    }
}
