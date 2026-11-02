package com.yingdao.aicommerce.finance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Enable only the two newly authorized shops blocked by the broken configure endpoint. */
public final class FinanceAutoSyncRecovery {
    public static void main(String[] args){
        String organization="6a61f788-6685-4b64-82b1-eec97ed8cf90";
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));
            ds.setPassword(System.getenv("BACKFILL_PASSWORD"));ds.setMaximumPoolSize(1);
            ds.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=5");
            var repo=new FinanceRepository(new JdbcTemplate(ds),new ObjectMapper());
            var service=new FinanceService(repo,null,null,null);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(15);
            tx.executeWithoutResult(status->{
                for(String id:java.util.List.of("ac03b55a-d62e-4424-8e90-5970636bc3c4","d9240333-88a9-41f2-b8c2-b80d023eaf90")){
                    var row=repo.jdbc().queryForMap("""
                        SELECT id,name,enabled,currency,timezone,group_id FROM finance_connections
                        WHERE organization_id=? AND id=? AND status='ACTIVE' AND created_at>='2026-10-29'
                          AND platform='TIKTOK_SHOP' FOR UPDATE
                        """,organization,id);
                    if(Boolean.TRUE.equals(row.get("enabled"))){System.out.println(row.get("name")+" ALREADY_ENABLED");continue;}
                    if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE organization_id=? AND shop_id=?",Integer.class,organization,id)!=0)
                        throw new IllegalStateException("Shop was previously configured; preserve its manual pause");
                    service.configure(companyScope(organization),id,true,text(row.get("currency")),text(row.get("timezone")),text(row.get("group_id")));
                    if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE organization_id=? AND shop_id=? AND status='READY'",Integer.class,organization,id)!=6)
                        throw new IllegalStateException("Incomplete sync lanes");
                    System.out.println(row.get("name")+" ENABLED SIX_SYNC_CHANNELS");
                }
            });
        }
    }
}
