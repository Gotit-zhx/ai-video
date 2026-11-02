package com.yingdao.aicommerce.finance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Exercise the production configuration path inside an always-rolled-back transaction. */
public final class FinanceShopConfigureProbe {
    public static void main(String[] args) throws Exception {
        try (var ds = new com.zaxxer.hikari.HikariDataSource()) {
            ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));
            ds.setUsername(System.getenv("BACKFILL_USER"));
            ds.setPassword(System.getenv("BACKFILL_PASSWORD"));
            ds.setMaximumPoolSize(1);
            ds.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=5");
            var repo = new FinanceRepository(new JdbcTemplate(ds), new ObjectMapper());
            var service = new FinanceService(repo, null, null, null);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.setTimeout(12);
            for (var shop : repo.jdbc().queryForList("""
                    SELECT id,organization_id,currency,timezone,group_id FROM finance_connections
                    WHERE organization_id=? AND status='ACTIVE' ORDER BY enabled,id
                    """, args[0])) {
                long start = System.nanoTime();
                try {
                    tx.executeWithoutResult(status -> {
                        try {
                            service.configure(companyScope(args[0]), text(shop.get("id")), true,
                                    text(shop.get("currency")), text(shop.get("timezone")), text(shop.get("group_id")));
                        } finally { status.setRollbackOnly(); }
                    });
                    System.out.println(shop.get("id") + " CONFIGURE_OK_ROLLED_BACK "
                            + (System.nanoTime()-start)/1_000_000 + "ms");
                } catch (Exception error) {
                    Throwable cause=error;
                    while(cause.getCause()!=null)cause=cause.getCause();
                    System.out.println(shop.get("id") + " " + cause.getClass().getSimpleName() + " " + cause.getMessage());
                }
            }
        }
    }
}
