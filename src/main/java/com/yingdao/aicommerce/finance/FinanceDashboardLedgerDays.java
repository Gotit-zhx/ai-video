package com.yingdao.aicommerce.finance;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Historical dashboard ledger totals, with exact shop-day repairs in the reader's snapshot. */
final class FinanceDashboardLedgerDays {
    private static final int WORKER_REPAIRS=12;
    private static final int SEED_ACKNOWLEDGMENTS=128;
    private static final int READ_REPAIRS=384;
    private static final int INSERT_BATCH=128;
    private static final String AMOUNTS="finance_dashboard_ledger_values";
    private static final List<String> KEYS=List.of("organization_id","record_date","shop_id","platform","kind","currency","category",
        "imported_bill");
    private static final List<String> METRICS=List.of("amount","revenue","impressions","clicks","conversions");
    private static final List<String> COUNTS=List.of("known_amounts","known_revenues","known_impressions","known_clicks","known_conversions");
    private static final List<String> MEASURES=Stream.concat(Stream.of("records"),Stream.concat(COUNTS.stream(),METRICS.stream())).toList();
    private static final List<String> COLUMNS=Stream.concat(KEYS.stream(),MEASURES.stream()).toList();
    private static final String PAIRS="""
        SELECT j.day_key,j.shop_key,j.event_key FROM JSON_TABLE(CAST(? AS JSON),'$[*]' COLUMNS(
          day_key DATE PATH '$[0]',shop_key VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$[1]',
          event_key BIGINT PATH '$[2]'
        )) j
        """;
    private static final String CATEGORY="CASE WHEN r.kind='FEE' THEN r.category ELSE '' END";
    private static final String IMPORTED="r.kind='BILL' AND r.source='IMPORT'";
    private static final String NATIVE_ELIGIBLE="""
        r.status<>'DATE_PENDING' AND r.kind NOT IN ('ORDER','REFUND')
        """.strip();
    private record Change(LocalDate day,String shop,long event) {}
    private enum View { DASHBOARD, BILLS, AMOUNT_GAPS }

    private final FinanceRepository repo;

    FinanceDashboardLedgerDays(FinanceRepository repo){this.repo=repo;}

    Optional<List<Map<String,Object>>> read(Scope scope,Filter filter){
        return read(scope,filter,View.DASHBOARD);
    }

    Optional<List<Map<String,Object>>> billCounts(Scope scope,Filter filter){
        return read(scope,filter,View.BILLS);
    }

    Optional<List<Map<String,Object>>> amountGaps(Scope scope,Filter filter){
        return read(scope,filter,View.AMOUNT_GAPS);
    }

    private Optional<List<Map<String,Object>>> read(Scope scope,Filter filter,View view){
        if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_REPEATABLE_READ)
                ||!TransactionSynchronizationManager.hasResource(Objects.requireNonNull(repo.jdbc().getDataSource())))
            return Optional.empty();
        var range=List.of(scope.organizationId(),filter.start(),filter.end());
        Long days=repo.jdbc().queryForObject("""
            SELECT COUNT(*) FROM finance_dashboard_ledger_days d
            JOIN finance_dashboard_order_companies c ON c.organization_id=d.organization_id AND c.enabled
            JOIN finance_dashboard_ledger_worker_state w ON w.organization_id=d.organization_id
              AND d.record_date>=w.eligible_from AND d.record_date<w.eligible_until
            WHERE d.organization_id=? AND d.record_date BETWEEN ? AND ? AND d.ready
            """,Long.class,range.toArray());
        if(days!=filter.start().datesUntil(filter.end().plusDays(1)).count())return Optional.empty();
        // Existing partial-month and FX rules still consume the underlying corrected rows.
        if(!repo.jdbc().queryForList("""
            SELECT /*+ SET_VAR(optimizer_switch='use_invisible_indexes=on') */ 1
            FROM finance_records FORCE INDEX(idx_finance_fx_month_corrected)
            WHERE organization_id=? AND fx_month_corrected=TRUE AND record_date BETWEEN ? AND ? LIMIT 1
            """,range.toArray()).isEmpty())return Optional.empty();

        var changes=loadChanges("""
            SELECT record_date,shop_id,event_id FROM finance_dashboard_ledger_changes
            WHERE organization_id=? AND record_date BETWEEN ? AND ? AND (?='' OR shop_id=?) LIMIT ?
            """,scope.organizationId(),filter.start(),filter.end(),filter.shopId(),filter.shopId(),READ_REPAIRS+1);
        if(changes.size()>READ_REPAIRS)return Optional.empty();
        var stored=repo.dashboardRecordScope(scope,filter,"d");
        String table=" FROM "+AMOUNTS+" d";
        String ledgerKind=switch(view){
            case DASHBOARD->" AND NOT d.imported_bill";
            case BILLS->" AND d.kind='BILL'";
            case AMOUNT_GAPS->"";
        };
        String with="",from;
        var args=new ArrayList<Object>();
        if(changes.isEmpty()){
            from=table+" WHERE "+stored.where()+ledgerKind;
            args.addAll(stored.args());
        }else{
            var raw=repo.dashboardRecordScope(scope,filter,"r");
            with="WITH dirty AS ("+PAIRS+") ";
            args.add(write(changes));
            args.addAll(stored.args());
            args.add(scope.organizationId());
            args.addAll(raw.args());
            String rawKind=switch(view){
                case DASHBOARD->" AND NOT("+IMPORTED+")";
                case BILLS->" AND r.kind='BILL'";
                case AMOUNT_GAPS->"";
            };
            from=" FROM (SELECT "+qualified("d",COLUMNS)+table+
                " LEFT JOIN dirty p ON p.day_key=d.record_date AND p.shop_key=d.shop_id WHERE p.day_key IS NULL AND "
                +stored.where()+ledgerKind+" UNION ALL "+nativeTotals("dirty",raw.where()+rawKind)+") d";
        }
        if(view==View.AMOUNT_GAPS)return Optional.of(readAmountGaps(scope,filter,with,from,args));
        return checked(repo.jdbc().queryForList(with+report(from,view==View.BILLS),args.toArray()));
    }

    private List<Map<String,Object>> readAmountGaps(Scope scope,Filter filter,String with,String from,List<Object> args){
        // The ledger summaries include imported bills, but orders and refunds are kept separately.
        var raw=repo.query(scope,filter,"r");
        args.addAll(raw.args());
        return repo.jdbc().queryForList(with+"""
            SELECT shop_id,SUM(missing) missing FROM (
              SELECT d.shop_id,d.records-d.known_amounts missing
            """+from+"""
              UNION ALL
              SELECT r.shop_id,COUNT(*) missing FROM finance_records r FORCE INDEX(idx_finance_company_kind_date)
              WHERE
            """+raw.where()+"""
                AND r.kind IN ('ORDER','REFUND') AND r.amount IS NULL GROUP BY r.shop_id
            ) gaps GROUP BY shop_id HAVING SUM(missing)>0 ORDER BY shop_id
            """,args.toArray());
    }

    void advance(Instant now){
        var companies=repo.jdbc().queryForList("""
            SELECT c.organization_id FROM finance_dashboard_order_companies c
            LEFT JOIN finance_dashboard_ledger_worker_state w ON w.organization_id=c.organization_id
            WHERE c.enabled ORDER BY w.checked_at,c.organization_id LIMIT 1
            """,String.class);
        if(companies.isEmpty())return;
        String organization=companies.getFirst();
        if(repo.jdbc().queryForList("SELECT organization_id FROM finance_dashboard_ledger_worker_state WHERE organization_id=?",
                String.class,organization).isEmpty())
            repo.jdbc().update("INSERT IGNORE INTO finance_dashboard_ledger_worker_state(organization_id) VALUES(?)",organization);
        var first=FinanceCalendar.previousMonth(now).atDay(1);
        var end=first.plusMonths(2);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(repo.jdbc().getDataSource())));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(30);
        tx.executeWithoutResult(status->{
            if(repo.jdbc().queryForList("""
                SELECT organization_id FROM finance_dashboard_ledger_worker_state
                WHERE organization_id=? FOR UPDATE SKIP LOCKED
                """,String.class,organization).isEmpty())return;
            if(repo.jdbc().queryForList("SELECT organization_id FROM finance_dashboard_order_companies WHERE organization_id=? AND enabled",
                    String.class,organization).isEmpty())return;
            repair(organization,first,end);
            var ready=new HashSet<>(repo.jdbc().queryForList("""
                SELECT record_date FROM finance_dashboard_ledger_days
                WHERE organization_id=? AND record_date>=? AND record_date<? AND ready
                """,Date.class,organization,first,end));
            first.datesUntil(end).filter(day->!ready.contains(Date.valueOf(day))).findFirst()
                .ifPresent(day->seedDay(organization,day));
            removeOlderDay(organization,first);
            repo.jdbc().update("""
                UPDATE finance_dashboard_ledger_worker_state
                SET eligible_from=?,eligible_until=?,checked_at=UTC_TIMESTAMP(6) WHERE organization_id=?
                """,first,end,organization);
        });
    }

    private void repair(String organization,LocalDate first,LocalDate end){
        var changes=loadChanges("""
            SELECT c.record_date,c.shop_id,c.event_id FROM finance_dashboard_ledger_changes c
            JOIN finance_dashboard_ledger_days d ON d.organization_id=c.organization_id AND d.record_date=c.record_date AND d.ready
            WHERE c.organization_id=? AND c.record_date>=? AND c.record_date<? ORDER BY c.event_id LIMIT ?
            """,organization,first,end,WORKER_REPAIRS);
        if(changes.isEmpty())return;
        String keys=write(changes);
        var rows=repo.jdbc().queryForList("WITH dirty AS ("+PAIRS+") "+nativeTotals("dirty","TRUE"),keys,organization);
        repo.jdbc().update("DELETE v FROM ("+PAIRS+") p STRAIGHT_JOIN "+AMOUNTS+" v FORCE INDEX(idx_dashboard_ledger_day)"
            +" ON v.organization_id=? AND v.record_date=p.day_key AND v.shop_id=p.shop_key",keys,organization);
        insert(rows);
        acknowledge(organization,keys);
    }

    private void seedDay(String organization,LocalDate day){
        var changes=loadChanges("""
            SELECT record_date,shop_id,event_id FROM finance_dashboard_ledger_changes
            WHERE organization_id=? AND record_date=? ORDER BY event_id LIMIT ?
            """,organization,day,SEED_ACKNOWLEDGMENTS);
        var rows=repo.jdbc().queryForList(nativeTotals("", "r.organization_id=? AND r.record_date=?"),organization,day);
        repo.jdbc().update("DELETE FROM "+AMOUNTS+" WHERE organization_id=? AND record_date=?",organization,day);
        insert(rows);
        repo.jdbc().update("""
            INSERT INTO finance_dashboard_ledger_days(organization_id,record_date,ready) VALUES(?,?,TRUE)
            ON DUPLICATE KEY UPDATE ready=TRUE
            """,organization,day);
        if(!changes.isEmpty())acknowledge(organization,write(changes));
    }

    private void acknowledge(String organization,String keys){
        // Recheck the identities in the write. An older ID can commit after the worker's read.
        repo.jdbc().update("DELETE c FROM ("+PAIRS+") p STRAIGHT_JOIN finance_dashboard_ledger_changes c"
            +" ON c.organization_id=? AND c.record_date=p.day_key AND c.shop_id=p.shop_key AND c.event_id=p.event_key",keys,organization);
    }

    private void removeOlderDay(String organization,LocalDate first){
        var stale=repo.jdbc().queryForList("""
            SELECT MIN(record_date) FROM (
                (SELECT record_date FROM finance_dashboard_ledger_days
                 WHERE organization_id=? AND record_date<? ORDER BY record_date LIMIT 1)
                UNION ALL
                (SELECT record_date FROM finance_dashboard_ledger_changes
                 WHERE organization_id=? AND record_date<? ORDER BY record_date LIMIT 1)
            ) older
            """,Date.class,organization,first,organization,first).getFirst();
        if(stale==null)return;
        repo.jdbc().update("DELETE FROM "+AMOUNTS+" WHERE organization_id=? AND record_date=?",organization,stale);
        repo.jdbc().update("DELETE FROM finance_dashboard_ledger_days WHERE organization_id=? AND record_date=?",organization,stale);
        repo.jdbc().update("DELETE FROM finance_dashboard_ledger_changes WHERE organization_id=? AND record_date=?",organization,stale);
    }

    private void insert(List<Map<String,Object>> rows){
        String slots="("+String.join(",",Collections.nCopies(COLUMNS.size(),"?"))+")";
        for(int offset=0;offset<rows.size();offset+=INSERT_BATCH){
            var batch=rows.subList(offset,Math.min(offset+INSERT_BATCH,rows.size()));
            var args=new ArrayList<Object>(batch.size()*COLUMNS.size());
            batch.forEach(row->COLUMNS.forEach(column->args.add(row.get(column))));
            repo.jdbc().update("INSERT INTO "+AMOUNTS+"("+String.join(",",COLUMNS)+") VALUES "+
                String.join(",",Collections.nCopies(batch.size(),slots)),args.toArray());
        }
    }

    private List<Change> loadChanges(String sql,Object... args){
        return repo.jdbc().query(sql,(row,i)->new Change(row.getDate(1).toLocalDate(),row.getString(2),row.getLong(3)),args);
    }

    private String write(List<Change> changes){
        return repo.write(changes.stream().map(change->List.of(change.day().toString(),change.shop(),change.event())).toList());
    }

    private static String nativeTotals(String pairs,String where){
        String source=pairs.isEmpty()?"finance_records r FORCE INDEX(idx_finance_company_dashboard)"
            :pairs+" p STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_shop_dashboard)"
              +" ON r.organization_id=? AND r.record_date=p.day_key AND r.shop_id=p.shop_key";
        var select=new ArrayList<>(List.of("ANY_VALUE(r.organization_id) organization_id","r.record_date","ANY_VALUE(r.shop_id) shop_id",
            "ANY_VALUE(r.platform) platform","ANY_VALUE(r.kind) kind","ANY_VALUE(r.currency) currency","ANY_VALUE("+CATEGORY+") category",
            "("+IMPORTED+") imported_bill","COUNT(*) records"));
        for(int i=0;i<METRICS.size();i++)select.add("COUNT(r."+METRICS.get(i)+") "+COUNTS.get(i));
        METRICS.forEach(metric->select.add("SUM(r."+metric+") "+metric));
        return "SELECT "+String.join(",",select)+" FROM "+source+" WHERE "+where+" AND "+NATIVE_ELIGIBLE
            +" GROUP BY r.record_date,CAST(r.organization_id AS BINARY),CAST(r.shop_id AS BINARY),CAST(r.platform AS BINARY),"
            +"CAST(r.kind AS BINARY),CAST(r.currency AS BINARY),CAST("+CATEGORY+" AS BINARY),("+IMPORTED+")";
    }

    private static String report(String from,boolean bills){
        if(bills)return "SELECT d.currency,SUM(d.records) records,COUNT(DISTINCT CAST(d.currency AS BINARY))>1 byte_variants"
            +from+" GROUP BY d.currency ORDER BY d.currency";
        var keys=List.of("shop_id","record_date","kind","currency","category");
        String sums=MEASURES.stream().map(name->"SUM(d."+name+") "+name).collect(Collectors.joining(","));
        String variants=List.of("shop_id","kind","currency","category").stream()
            .map(name->"COUNT(DISTINCT CAST(d."+name+" AS BINARY))>1").collect(Collectors.joining(" OR "));
        return "SELECT "+qualified("d",keys)+",'' status,"+sums+",("+variants+")+0 byte_variants"
            +from+" GROUP BY "+qualified("d",keys);
    }

    private static Optional<List<Map<String,Object>>> checked(List<Map<String,Object>> rows){
        if(rows.stream().anyMatch(row->number(row.get("byte_variants"))>0))return Optional.empty();
        rows.forEach(row->row.remove("byte_variants"));
        return Optional.of(rows);
    }

    private static String qualified(String alias,List<String> columns){
        return columns.stream().map(name->alias+"."+name).collect(Collectors.joining(","));
    }
}
