package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static com.yingdao.aicommerce.finance.FinanceData.number;

/** The captured legacy prefix is covered only by fully rebuilt, committed month snapshots. */
final class FinanceQueryPriority {
    @FunctionalInterface
    interface Rows {List<Map<String,Object>> read(String sql,Object...arguments);}

    private final FinanceRepository repo;
    private volatile Long installedThrough;

    FinanceQueryPriority(FinanceRepository repo){this.repo=repo;}

    long cutover(){
        var value=installedThrough;
        if(value!=null)return value;
        if(repo.jdbc().queryForList("""
            SELECT 1 FROM information_schema.tables
            WHERE table_schema=DATABASE() AND table_name='finance_query_priority_cutover'
            """).isEmpty()){
            installedThrough=-1L;
        }else{
            long captured=repo.jdbc().queryForObject(
                "SELECT legacy_through FROM finance_query_priority_cutover WHERE id=1",Long.class);
            installedThrough=captured;
        }
        return installedThrough;
    }

    long coveredThrough(Rows read,String organization,String platform,List<LocalDate> months){
        if(cutover()<=0)return 0;
        var coverage=read.read("""
            SELECT COUNT(*) covered_months, MIN(b.legacy_through) legacy_through
            FROM finance_query_priority_baselines b
            JOIN finance_query_priority_orgs o ON o.organization_id=b.organization_id
            WHERE b.organization_id=? AND b.platform_filter=? AND b.partition_month BETWEEN ? AND ?
            """,organization,platform,months.getFirst(),months.getLast()).getFirst();
        return number(coverage.get("covered_months"))==months.size()?number(coverage.get("legacy_through")):0;
    }

    void publishBaseline(String organization,String platform,LocalDate month,long legacyThrough){
        repo.jdbc().update("""
            INSERT INTO finance_query_priority_baselines(organization_id,platform_filter,partition_month,legacy_through)
            VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE legacy_through=GREATEST(legacy_through,VALUES(legacy_through))
            """,organization,platform,month,legacyThrough);
    }
}
