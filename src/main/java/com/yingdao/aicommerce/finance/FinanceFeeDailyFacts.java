package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Native fee buckets plus committed contributions that the worker has yet to fold. */
final class FinanceFeeDailyFacts {
    private static final int BATCH=1000;
    private static final String FACTS="finance_fee_daily_facts";
    private static final String CHANGES="finance_fee_daily_changes";
    private static final String COVERAGE="finance_fee_daily_coverage";
    private static final String KEYS="organization_id,record_date,shop_id,platform,kind,currency,category";
    private static final List<String> VALUES=List.of("amount","revenue","impressions","clicks","conversions");
    private static final List<String> COUNTS=List.of("known_amounts","known_revenues","known_impressions","known_clicks","known_conversions");
    private static final String MEASURES="records,"+String.join(",",COUNTS)+","+String.join(",",VALUES);
    private static final String FIELD_LIST=KEYS+","+MEASURES;
    private static final List<String> COLUMNS=List.of(FIELD_LIST.split(","));
    private final FinanceRepository repo;

    FinanceFeeDailyFacts(FinanceRepository repo){this.repo=repo;}

    private static String expressions(boolean delta,boolean aggregate){
        var fields=new ArrayList<String>(List.of(aggregate?(delta?"SUM(direction)":"COUNT(*)")+" records":"direction records"));
        for(int i=0;i<VALUES.size();i++){
            String value=VALUES.get(i);
            String known=delta?"IF("+value+" IS NULL,0,direction)":value;
            fields.add((aggregate?(delta?"SUM("+known+")":"COUNT("+known+")"):known)+" "+COUNTS.get(i));
        }
        for(String value:VALUES){
            String nativeValue=Set.of("impressions","clicks").contains(value)?"CAST("+value+" AS DECIMAL(65,0))":value;
            String amount=(delta?"direction*":"")+"COALESCE("+nativeValue+",0)";
            fields.add((aggregate?"SUM("+amount+")":amount)+" "+value);
        }
        return String.join(",",fields);
    }

    Optional<List<Map<String,Object>>> read(Scope scope,Filter filter,FinanceRepository.Query query){
        if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!TransactionSynchronizationManager.hasResource(Objects.requireNonNull(repo.jdbc().getDataSource()))
                ||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_REPEATABLE_READ))return Optional.empty();
        var range=new Object[]{scope.organizationId(),filter.start(),filter.end()};
        String where="organization_id=? AND record_date BETWEEN ? AND ?";
        long days=ChronoUnit.DAYS.between(filter.start(),filter.end())+1;
        if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM "+COVERAGE+" WHERE "+where+" AND ready",Long.class,range)!=days)
            return Optional.empty();
        if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM (SELECT change_id FROM "+CHANGES+" WHERE "+where+
            " LIMIT 10001) pending",Long.class,range)>10000)return Optional.empty();

        var fields=new ArrayList<>(List.of("d.shop_id","d.record_date","d.kind","d.currency","d.category","'' status","SUM(d.records) records"));
        for(String count:COUNTS)fields.add("SUM(d."+count+") "+count);
        for(int i=0;i<VALUES.size();i++)fields.add("IF(SUM(d."+COUNTS.get(i)+")=0,NULL,SUM(d."+VALUES.get(i)+")) "+VALUES.get(i));
        // The raw query groups with the source collation. If a group contains multiple byte
        // representations, only the native query may choose how to represent that group.
        fields.add("(COUNT(DISTINCT CAST(d.shop_id AS BINARY))>1 OR COUNT(DISTINCT CAST(d.kind AS BINARY))>1"
            +" OR COUNT(DISTINCT CAST(d.currency AS BINARY))>1 OR COUNT(DISTINCT CAST(d.category AS BINARY))>1)+0 byte_variants");
        String columns="""
            organization_id,record_date,shop_id COLLATE utf8mb4_0900_ai_ci shop_id,
            platform COLLATE utf8mb4_0900_ai_ci platform,kind COLLATE utf8mb4_0900_ai_ci kind,
            currency COLLATE utf8mb4_0900_ai_ci currency,category COLLATE utf8mb4_0900_ai_ci category,'' status,
            """;
        var parameters=new ArrayList<Object>(Arrays.asList(range));parameters.addAll(Arrays.asList(range));parameters.addAll(query.args());
        var rows=repo.jdbc().queryForList("SELECT "+String.join(",",fields)+" FROM (SELECT "+columns+
            MEASURES+" FROM (SELECT "+FIELD_LIST+" FROM "+FACTS+" WHERE "+where+
            " UNION ALL SELECT "+KEYS+","+expressions(true,false)+" FROM "+CHANGES+" WHERE "+where+") changes) d WHERE "+
            query.where()+" GROUP BY d.shop_id,d.record_date,d.kind,d.currency,d.category HAVING SUM(d.records)>0",parameters.toArray());
        if(rows.stream().anyMatch(row->number(row.get("byte_variants"))>0))return Optional.empty();
        rows.forEach(row->row.remove("byte_variants"));
        return Optional.of(rows);
    }

    void advance(){
        // Initialize distinct business days, regardless of the number of requested platform variants.
        var next=repo.jdbc().queryForList("""
            WITH RECURSIVE day_offsets(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM day_offsets WHERE n<30)
            SELECT DISTINCT p.organization_id,DATE_ADD(p.partition_month,INTERVAL day_offsets.n DAY) business_day
            FROM finance_query_partitions p JOIN day_offsets ON day_offsets.n<DAY(LAST_DAY(p.partition_month))
            LEFT JOIN finance_fee_daily_coverage d ON d.organization_id=p.organization_id
              AND d.record_date=DATE_ADD(p.partition_month,INTERVAL day_offsets.n DAY) AND d.ready
            WHERE d.organization_id IS NULL ORDER BY business_day,p.organization_id LIMIT 1
            """);
        if(next.isEmpty()){
            // Historical imports may update dates without a requested month.
            // Bound the search and let the same worker publish those days too.
            next=repo.jdbc().queryForList("""
                SELECT c.organization_id,c.record_date business_day
                FROM (SELECT organization_id,record_date FROM finance_fee_daily_changes ORDER BY change_id LIMIT 1000) c
                LEFT JOIN finance_fee_daily_coverage d
                  ON d.organization_id=c.organization_id AND d.record_date=c.record_date AND d.ready
                WHERE d.organization_id IS NULL LIMIT 1
                """);
        }
        if(!next.isEmpty()){
            var day=next.getFirst();
            initialize(text(day.get("organization_id")),LocalDate.parse(text(day.get("business_day"))));
        }
        if(MaintenanceGate.instance().closed()||Thread.currentThread().isInterrupted())return;
        var dirty=repo.jdbc().queryForList("""
            SELECT d.organization_id,d.record_date FROM finance_fee_daily_coverage d
            WHERE d.ready AND EXISTS (SELECT 1 FROM finance_fee_daily_changes c FORCE INDEX(idx_fee_change_day)
              WHERE c.organization_id=d.organization_id AND c.record_date=d.record_date)
            ORDER BY d.folded_at,d.record_date,d.organization_id LIMIT 4
            """);
        for(var day:dirty){
            if(MaintenanceGate.instance().closed()||Thread.currentThread().isInterrupted())break;
            compact(text(day.get("organization_id")),LocalDate.parse(text(day.get("record_date"))));
        }
    }

    void initialize(String organization,LocalDate day){
        transaction(()->{
            repo.jdbc().update("INSERT IGNORE INTO "+COVERAGE+"(organization_id,record_date) VALUES(?,?)",organization,day);
            if(lockReady(organization,day))return;
            // Plain reads establish one repeatable snapshot of the ledger and its events.
            String rawKeys="organization_id,record_date,shop_id COLLATE utf8mb4_0900_bin shop_id,"
                +"platform COLLATE utf8mb4_0900_bin platform,kind COLLATE utf8mb4_0900_bin kind,"
                +"currency COLLATE utf8mb4_0900_bin currency,category COLLATE utf8mb4_0900_bin category";
            var fresh=repo.jdbc().queryForList("SELECT "+KEYS+","+expressions(false,true)+" FROM (SELECT "+rawKeys+
                ",amount,revenue,impressions,clicks,conversions FROM finance_records FORCE INDEX(idx_finance_company_kind_date)"+
                " WHERE organization_id=? AND kind='FEE' AND record_date=? AND status<>'DATE_PENDING') native_fees GROUP BY "+KEYS,organization,day);
            if(!fresh.isEmpty())repo.jdbc().batchUpdate("INSERT INTO "+FACTS+"("+FIELD_LIST+") VALUES("+slots(COLUMNS.size())+")",
                fresh.stream().map(row->COLUMNS.stream().map(row::get).toArray()).toList());
            long last=0;
            for(;;){
                var visible=repo.jdbc().queryForList("SELECT change_id FROM "+CHANGES+" WHERE organization_id=? AND record_date=?"+
                    " AND change_id>? ORDER BY change_id LIMIT "+BATCH,Long.class,organization,day,last);
                if(visible.isEmpty())break;
                remove(visible);
                last=visible.getLast();
            }
            repo.jdbc().update("UPDATE "+COVERAGE+" SET ready=TRUE WHERE organization_id=? AND record_date=?",organization,day);
        });
    }

    void compact(String organization,LocalDate day){
        transaction(()->{
            if(!lockReady(organization,day))return;
            var selected=repo.jdbc().queryForList("SELECT change_id FROM "+CHANGES+" WHERE organization_id=? AND record_date=?"+
                " ORDER BY change_id LIMIT "+BATCH,Long.class,organization,day);
            if(selected.isEmpty())return;
            String sums=Arrays.stream(MEASURES.split(",")).map(col->col+"="+FACTS+"."+col+"+VALUES("+col+")")
                .collect(java.util.stream.Collectors.joining(","));
            repo.jdbc().update("INSERT INTO "+FACTS+"("+FIELD_LIST+") SELECT "+KEYS+","+expressions(true,true)+" FROM "+CHANGES+
                " WHERE change_id IN ("+slots(selected.size())+") GROUP BY "+KEYS+" ON DUPLICATE KEY UPDATE "+sums,selected.toArray());
            String zero=Arrays.stream(MEASURES.split(",")).map(field->field+"=0")
                .collect(java.util.stream.Collectors.joining(" AND "));
            repo.jdbc().update("DELETE FROM "+FACTS+" WHERE organization_id=? AND record_date=? AND "+zero,organization,day);
            remove(selected);
            repo.jdbc().update("UPDATE "+COVERAGE+" SET folded_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND record_date=?",organization,day);
        });
    }

    private boolean lockReady(String organization,LocalDate day){
        return Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT ready FROM "+COVERAGE+
            " WHERE organization_id=? AND record_date=? FOR UPDATE",Boolean.class,organization,day));
    }
    private void remove(List<Long> ids){
        repo.jdbc().update("DELETE FROM "+CHANGES+" WHERE change_id IN ("+slots(ids.size())+")",ids.toArray());
    }
    private static String slots(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private void transaction(Runnable action){
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(repo.jdbc().getDataSource())));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(120);
        tx.executeWithoutResult(status->action.run());
    }
}
