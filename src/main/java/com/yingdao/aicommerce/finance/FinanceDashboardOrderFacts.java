package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yingdao.aicommerce.finance.FinanceData.*;

final class FinanceDashboardOrderFacts {
    private static final int EVENT_BATCH=512;
    private static final int READ_EVENTS=4096;
    private static final int INSERT_BATCH=128;
    static final String ORDER_VALUES="finance_dashboard_order_values";
    private static final List<String> KEYS=List.of("organization_id","shop_id","order_id","member_id",
        "record_date","platform","currency","fx_date","order_currency","order_status");
    private static final List<String> LEDGER=List.of("records","known_amounts","known_revenues","amount",
        "revenue","impressions","clicks","conversions");
    private static final List<String> COLUMNS=java.util.stream.Stream.of(KEYS,LEDGER,FinanceCostReport.COST_SUMS,
        List.of("cost_before_pending")).flatMap(Collection::stream).toList();
    static final String KEY_ROWS="""
        SELECT DISTINCT j.shop_key,j.order_key FROM JSON_TABLE(CAST(? AS JSON),'$[*]' COLUMNS(
          shop_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$[0]',
          order_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$[1]'
        )) j
        """;
    private record Pending(List<Long> events,List<List<String>> orders,boolean rebuild) {}
    private record CoveredOrders(boolean grouped,List<List<String>> changed) {}

    private final FinanceRepository repo;
    private final FinanceDashboardOrderGroups groups;

    FinanceDashboardOrderFacts(FinanceRepository repo){
        this.repo=repo;
        this.groups=new FinanceDashboardOrderGroups(repo);
    }

    Optional<List<Map<String,Object>>> read(Scope scope,Filter filter){
        var covered=coveredOrders(scope,filter);
        if(covered.isEmpty())return Optional.empty();
        var changed=covered.get().changed();
        var part=repo.dashboardRecordScope(scope,filter,"d");
        if(covered.get().grouped()){
            if(changed.isEmpty())return groups.read(scope,part,List.of(),"",List.of());
            var live=repo.dashboardRecordScope(scope,filter,"r");
            var args=new ArrayList<Object>();
            args.add(scope.organizationId());
            args.addAll(live.args());
            return groups.read(scope,part,changed,nativeValues(scope,live,changedSource("changed")),args);
        }
        return FinanceDashboardOrderGroups.verified(readOrderRows(scope,filter,part,changed));
    }

    /** Reuse only settled order identities. The caller reads live costs and jobs in this snapshot. */
    Optional<FinanceRepository.Query> settledMembers(Scope scope,Filter filter){
        // Multiple first-day transactions can have different platform or currency values.
        if(!filter.platform().isBlank()||!filter.currency().isBlank())return Optional.empty();
        var covered=coveredOrders(scope,filter);
        if(covered.isEmpty())return Optional.empty();
        var part=repo.dashboardRecordScope(scope,filter,"d");
        var changed=covered.get().changed();
        String saved="SELECT NULLIF(d.member_id,'') order_record_id,d.organization_id,d.shop_id,d.order_id reference,d.record_date settlement_date"
            +" FROM "+ORDER_VALUES+" d FORCE INDEX(idx_dashboard_order_day)";
        if(changed.isEmpty())return Optional.of(new FinanceRepository.Query(saved+" WHERE "+part.where(),part.args()));
        var live=repo.dashboardRecordScope(scope,filter,"r");
        var args=new ArrayList<Object>();
        args.add(repo.write(changed));
        args.addAll(part.args());
        args.add(scope.organizationId());
        args.addAll(live.args());
        String sql="WITH changed AS ("+KEY_ROWS+") "+saved+"""
            LEFT JOIN changed x ON x.shop_key=d.shop_id AND x.order_key=d.order_id
            WHERE x.order_key IS NULL AND
            """+part.where()+"""
            UNION ALL
            SELECT o.id,r.organization_id,r.shop_id,r.order_id,r.record_date
            FROM
            """+changedSource("changed")+orderJoin()+" WHERE "+FinanceCostReport.firstSettlement("r",true)
            +" AND "+live.where();
        return Optional.of(new FinanceRepository.Query(sql,args));
    }

    Optional<Map<String,Object>> labelPeriod(Scope scope,Filter filter,boolean useIndex){
        var coverage=coveredOrders(scope,filter);
        if(coverage.isEmpty())return Optional.empty();
        var stored=repo.dashboardRecordScope(scope,filter,"d");
        String index=useIndex?"idx_dashboard_label_member":"PRIMARY";
        String hint=useIndex?"/*+ SET_VAR(optimizer_switch='use_invisible_indexes=on') */ ":"";
        String verified="""
            SELECT 1 FROM finance_dashboard_order_values d FORCE INDEX(%s)
            WHERE d.organization_id=lc.organization_id AND d.shop_id=lc.shop_id AND d.order_id=lc.order_id AND
            """.formatted(index)+stored.where()+" LIMIT 1";
        // Probe once per active label. A materialized semi-join can instead walk
        // every historical order in each selected shop before applying the dates.
        String membership=" JOIN LATERAL ("+verified+") period_member ON TRUE ";
        String totals="""
            COUNT(*) orders,COALESCE(SUM(lc.amount_cny),0) amount_cny,
              COALESCE(SUM(lc.pending_count),0) conflicts,COALESCE(SUM(lc.shipment_count),0) shipments
            FROM finance_label_state ls JOIN finance_label_order_totals lc
              ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation
            """;
        var changed=coverage.get().changed();
        if(changed.isEmpty()){
            var args=new ArrayList<Object>(stored.args());
            args.add(scope.organizationId());
            return Optional.of(repo.jdbc().queryForMap("SELECT "+hint+totals+membership+" WHERE ls.organization_id=?",args.toArray()));
        }
        // Keep the lookup for thousands of unchanged orders separate from the
        // small set that needs a fresh first-settlement lookup.
        var live=repo.dashboardRecordScope(scope,filter,"r");
        var args=new ArrayList<Object>();
        args.add(repo.write(changed));
        args.add(scope.organizationId());
        args.add(scope.organizationId());
        args.addAll(live.args());
        args.addAll(stored.args());
        args.add(scope.organizationId());
        args.add(scope.organizationId());
        // An order without an active label total cannot change this report.
        // Filter those events before joining the full settlement history.
        String sql="WITH changed AS ("+KEY_ROWS+"), labelled AS ("
            +"SELECT DISTINCT k.shop_key,k.order_key FROM changed k "
            +"JOIN finance_label_state s ON s.organization_id=? "
            +"JOIN finance_label_order_totals l ON l.organization_id=s.organization_id "
            +"AND l.generation=s.active_generation AND l.shop_id=k.shop_key AND l.order_id=k.order_key"
            +"), corrected AS (SELECT DISTINCT r.shop_id,r.order_id FROM "
            +changedSource("labelled")+" WHERE "+FinanceCostReport.firstSettlement("r",true)+" AND "+live.where()+") "
            +"SELECT "+hint+"SUM(orders) orders,SUM(amount_cny) amount_cny,SUM(conflicts) conflicts,SUM(shipments) shipments FROM ("
            +"SELECT "+totals+membership+" WHERE ls.organization_id=?"
            +" AND NOT EXISTS (SELECT 1 FROM changed k WHERE k.shop_key=lc.shop_id AND k.order_key=lc.order_id)"
            +" UNION ALL "
            +"SELECT "+totals+" JOIN corrected r ON r.shop_id=lc.shop_id AND r.order_id=lc.order_id"
            +" WHERE ls.organization_id=?) totals";
        return Optional.of(repo.jdbc().queryForMap(sql,args.toArray()));
    }

    private Optional<CoveredOrders> coveredOrders(Scope scope,Filter filter){
        if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_REPEATABLE_READ)
                ||!TransactionSynchronizationManager.hasResource(Objects.requireNonNull(repo.jdbc().getDataSource())))
            return Optional.empty();
        var coverage=repo.jdbc().queryForMap("""
            SELECT COUNT(*) order_days,SUM(d.groups_ready) grouped_days FROM finance_dashboard_order_days d
            JOIN finance_dashboard_order_companies c ON c.organization_id=d.organization_id AND c.enabled
            JOIN finance_dashboard_order_worker_state w ON w.organization_id=d.organization_id
              AND d.record_date>=w.eligible_from AND d.record_date<w.eligible_until
            WHERE d.organization_id=? AND d.record_date BETWEEN ? AND ? AND d.ready
            """,scope.organizationId(),filter.start(),filter.end());
        long days=filter.start().datesUntil(filter.end().plusDays(1)).count();
        if(number(coverage.get("order_days"))!=days)return Optional.empty();
        if(!repo.jdbc().queryForList("""
            SELECT /*+ SET_VAR(optimizer_switch='use_invisible_indexes=on') */ 1
            FROM finance_records FORCE INDEX(idx_finance_fx_month_corrected)
            WHERE organization_id=? AND fx_month_corrected=TRUE AND record_date BETWEEN ? AND ? LIMIT 1
            """,scope.organizationId(),filter.start(),filter.end()).isEmpty())return Optional.empty();

        var pending=pending(scope,READ_EVENTS);
        if(pending.rebuild())return Optional.empty();
        return Optional.of(new CoveredOrders(number(coverage.get("grouped_days"))==days,pending.orders()));
    }

    private List<Map<String,Object>> readOrderRows(Scope scope,Filter filter,FinanceRepository.Query part,
                                                   List<List<String>> orders){
        var fields=new ArrayList<>(List.of("v.shop_id","v.record_date","v.fx_date","v.currency settlement_currency",
            "v.order_currency currency","'ORDER' kind","'' category","v.order_status status"));
        java.util.stream.Stream.concat(LEDGER.stream(),FinanceCostReport.COST_SUMS.stream())
            .forEach(name->fields.add("SUM(v."+name+") "+name));
        fields.add("IF(SUM(v.pending_count)=0,SUM(v.cost_before_pending),NULL) amount_cny");
        fields.add(FinanceDashboardOrderGroups.byteVariants("v"));
        String select="SELECT "+String.join(",",fields);
        String group=" GROUP BY v.shop_id,v.record_date,v.fx_date,v.currency,v.order_currency,v.order_status";
        String table=" FROM "+ORDER_VALUES+" d FORCE INDEX(idx_dashboard_order_day)";
        if(orders.isEmpty()){
            fields.replaceAll(field->field.replace("v.","d."));
            return repo.jdbc().queryForList("SELECT "+String.join(",",fields)+table+" WHERE "+
                part.where()+group.replace("v.","d."),part.args().toArray());
        }
        // Replace old contributions even when a current winner has moved to another month or disappeared.
        var live=repo.dashboardRecordScope(scope,filter,"r");
        var args=new ArrayList<Object>();
        args.add(repo.write(orders));
        args.addAll(part.args());
        args.add(scope.organizationId());
        args.addAll(live.args());
        String names=String.join(",",COLUMNS);
        String savedColumns=COLUMNS.stream().map(column->"d."+column).collect(java.util.stream.Collectors.joining(","));
        String sql="WITH changed AS ("+KEY_ROWS+") "+select+" FROM (SELECT "+savedColumns+table+"""
              LEFT JOIN changed x ON x.shop_key=d.shop_id AND x.order_key=d.order_id
              WHERE x.order_key IS NULL AND
            """+part.where()+" UNION ALL SELECT "+names+" FROM ("+
            nativeValues(scope,live,changedSource("changed"))+") live_values) v"+group;
        return repo.jdbc().queryForList(sql,args.toArray());
    }

    void advance(Instant now){
        var companies=repo.jdbc().queryForList("""
            SELECT c.organization_id FROM finance_dashboard_order_companies c
            LEFT JOIN finance_dashboard_order_worker_state w ON w.organization_id=c.organization_id
            WHERE c.enabled ORDER BY w.checked_at,c.organization_id LIMIT 1
            """,String.class);
        if(companies.isEmpty())return;
        var scope=companyScope(companies.getFirst());
        if(repo.jdbc().queryForList("SELECT organization_id FROM finance_dashboard_order_worker_state WHERE organization_id=?",
                scope.organizationId()).isEmpty())
            repo.jdbc().update("INSERT IGNORE INTO finance_dashboard_order_worker_state(organization_id) VALUES(?)",scope.organizationId());
        var first=FinanceCalendar.previousMonth(now).atDay(1);
        var end=first.plusMonths(2);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(repo.jdbc().getDataSource())));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(40);
        tx.executeWithoutResult(status->{
            if(repo.jdbc().queryForList("""
                SELECT organization_id FROM finance_dashboard_order_worker_state WHERE organization_id=?
                FOR UPDATE SKIP LOCKED
                """,scope.organizationId()).isEmpty())return;
            if(repo.jdbc().queryForList("SELECT organization_id FROM finance_dashboard_order_companies WHERE organization_id=? AND enabled",
                    scope.organizationId()).isEmpty())return;

            var pending=pending(scope,EVENT_BATCH);
            if(pending.rebuild()){
                repo.jdbc().update("UPDATE finance_dashboard_order_days SET ready=FALSE WHERE organization_id=?",scope.organizationId());
            }else if(!pending.orders().isEmpty()){
                replaceOrders(scope,new Filter(first,end.minusDays(1),"","","","day"),pending.orders());
            }
            advanceCoverage(scope,first,end);

            // Facts for older dates are disposable. Remove at most one date per turn.
            var stale=repo.jdbc().queryForList("""
                SELECT record_date FROM finance_dashboard_order_days WHERE organization_id=? AND record_date<? ORDER BY record_date LIMIT 1
                """,java.sql.Date.class,scope.organizationId(),first);
            if(!stale.isEmpty()){
                groups.removeDay(scope,stale.getFirst().toLocalDate());
                repo.jdbc().update("DELETE FROM "+ORDER_VALUES+" WHERE organization_id=? AND record_date=?",scope.organizationId(),stale.getFirst());
                repo.jdbc().update("DELETE FROM finance_dashboard_order_days WHERE organization_id=? AND record_date=?",scope.organizationId(),stale.getFirst());
            }
            if(!pending.events().isEmpty()){
                var ids=pending.events();
                repo.jdbc().update("DELETE FROM finance_dashboard_order_events WHERE event_id IN ("+
                    String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
            }
            repo.jdbc().update("""
                UPDATE finance_dashboard_order_worker_state
                SET checked_at=UTC_TIMESTAMP(6),eligible_from=?,eligible_until=?
                WHERE organization_id=?
                """,first,end,scope.organizationId());
        });
    }

    private void advanceCoverage(Scope scope,LocalDate first,LocalDate end){
        var coverage=new HashMap<LocalDate,Boolean>();
        repo.jdbc().query("""
            SELECT record_date,groups_ready FROM finance_dashboard_order_days
            WHERE organization_id=? AND record_date>=? AND record_date<? AND ready
            """,row->{coverage.put(row.getDate(1).toLocalDate(),row.getBoolean(2));},scope.organizationId(),first,end);
        first.datesUntil(end).filter(day->!Boolean.TRUE.equals(coverage.get(day))).findFirst().ifPresent(day->{
            if(!coverage.containsKey(day))seedDay(scope,day);
            groups.refreshDay(scope,day);
            repo.jdbc().update("UPDATE finance_dashboard_order_days SET groups_ready=TRUE WHERE organization_id=? AND record_date=?",
                scope.organizationId(),day);
        });
    }

    private void seedDay(Scope scope,LocalDate day){
        var part=new FinanceRepository.Query("r.organization_id=? AND r.record_date=?",List.of(scope.organizationId(),day));
        var current=repo.jdbc().queryForList(nativeValues(scope,part,"finance_records r FORCE INDEX(idx_finance_company_kind_date)"),part.args().toArray());
        repo.jdbc().update("DELETE FROM "+ORDER_VALUES+" WHERE organization_id=? AND record_date=?",scope.organizationId(),day);
        insert(current);
        repo.jdbc().update("""
            INSERT INTO finance_dashboard_order_days(organization_id,record_date,ready) VALUES(?,?,TRUE)
            ON DUPLICATE KEY UPDATE ready=TRUE,groups_ready=FALSE
            """,scope.organizationId(),day);
    }

    private void replaceOrders(Scope scope,Filter range,List<List<String>> orders){
        String data=repo.write(orders);
        var part=new FinanceRepository.Query("r.record_date BETWEEN ? AND ?",List.of(range.start(),range.end()));
        var current=repo.jdbc().queryForList("WITH changed AS ("+KEY_ROWS+") "+
            nativeValues(scope,part,changedSource("changed")),data,scope.organizationId(),range.start(),range.end());
        var shopDays=new LinkedHashSet<List<String>>();
        FinanceDashboardOrderGroups.collectShopDays(shopDays,repo.jdbc().queryForList(
            "SELECT DISTINCT d.record_date,d.shop_id FROM ("+KEY_ROWS+") x STRAIGHT_JOIN "+ORDER_VALUES+
            " d ON d.organization_id=? AND d.shop_id=x.shop_key AND d.order_id=x.order_key",data,scope.organizationId()));
        repo.jdbc().update("DELETE d FROM ("+KEY_ROWS+") x STRAIGHT_JOIN "+ORDER_VALUES+
            " d ON d.organization_id=? AND d.shop_id=x.shop_key AND d.order_id=x.order_key",data,scope.organizationId());
        insert(current);
        FinanceDashboardOrderGroups.collectShopDays(shopDays,current);
        groups.refreshChanged(scope,shopDays);
    }

    private Pending pending(Scope scope,int count){
        var events=repo.jdbc().queryForList("""
            SELECT event_id,event_scope,shop_id,order_id,record_id FROM finance_dashboard_order_events
            WHERE organization_id=? ORDER BY event_id LIMIT ?
            """,scope.organizationId(),count==EVENT_BATCH?count:count+1);
        var ids=events.stream().map(event->number(event.get("event_id"))).toList();
        var keys=new LinkedHashSet<List<String>>();
        if(ids.size()>count)return new Pending(List.of(),List.of(),true);
        var records=new LinkedHashSet<String>();
        for(var event:events){
            if("ALL".equals(event.get("event_scope")))return new Pending(ids,List.of(),true);
            String shop=text(event.get("shop_id")),order=text(event.get("order_id"));
            if(!shop.isBlank()&&!order.isBlank())keys.add(List.of(shop,order));
            else if(!text(event.get("record_id")).isBlank())records.add(text(event.get("record_id")));
            else return new Pending(ids,List.of(),true);
        }
        if(!records.isEmpty()){
            var args=new ArrayList<Object>();args.add(scope.organizationId());args.addAll(records);
            var resolved=repo.jdbc().queryForList("SELECT shop_id,order_id FROM finance_records WHERE organization_id=? AND id IN ("+
                String.join(",",Collections.nCopies(records.size(),"?"))+")",args.toArray());
            if(resolved.size()!=records.size())return new Pending(ids,List.of(),true);
            for(var row:resolved){
                String shop=text(row.get("shop_id")),order=text(row.get("order_id"));
                if(!shop.isBlank()&&!order.isBlank())keys.add(List.of(shop,order));
            }
        }
        return new Pending(ids,List.copyOf(keys),false);
    }

    private static String changedSource(String name){
        return name+" k STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_settled_order)"
            +" ON r.organization_id=? AND r.shop_id=k.shop_key AND r.order_id=k.order_key AND r.kind='SETTLEMENT'";
    }

    private static String orderJoin(){
        return """
             LEFT JOIN finance_records o FORCE INDEX(idx_finance_settled_order)
                ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id AND o.order_id=r.order_id
                AND o.kind='ORDER' AND o.source='API'
            """;
    }

    private String nativeValues(Scope scope,FinanceRepository.Query part,String source){
        return """
            SELECT r.organization_id,r.shop_id,r.order_id,COALESCE(o.id,'') member_id,
              r.record_date,r.platform,r.currency,o.record_date fx_date,o.currency order_currency,o.status order_status,
              COUNT(o.id) records,COUNT(o.amount) known_amounts,COUNT(o.revenue) known_revenues,
              SUM(o.amount) amount,SUM(o.revenue) revenue,SUM(o.impressions) impressions,
              SUM(o.clicks) clicks,SUM(o.conversions) conversions, COALESCE(SUM(c.amount_cny),0) cost_before_pending,
            """+FinanceCostReport.AGGREGATE.replace("x.order_record_id","o.id")+"""
            FROM %s
            %s
            LEFT JOIN finance_order_costs c ON c.order_record_id=o.id
            """.formatted(source,orderJoin())+FinancePublishedCosts.jobJoin("o.id",repo.costProgressMode(scope).separated())
            +FinanceLabelCosts.join("r.organization_id","r.shop_id","r.order_id")
            +" WHERE "+FinanceCostReport.firstSettlement("r",true)+" AND "+part.where()
            +" GROUP BY r.organization_id,r.shop_id,r.order_id,o.id,r.record_date,r.platform,r.currency,o.record_date,o.currency,o.status";
    }

    private void insert(List<Map<String,Object>> current){
        String row="("+String.join(",",Collections.nCopies(COLUMNS.size(),"?"))+")";
        for(int offset=0;offset<current.size();offset+=INSERT_BATCH){
            var batch=current.subList(offset,Math.min(offset+INSERT_BATCH,current.size()));
            var args=new ArrayList<Object>(batch.size()*COLUMNS.size());
            batch.forEach(value->COLUMNS.forEach(column->args.add(value.get(column))));
            repo.jdbc().update("INSERT INTO "+ORDER_VALUES+"("+String.join(",",COLUMNS)+") VALUES "+
                String.join(",",Collections.nCopies(batch.size(),row)),args.toArray());
        }
    }
}
