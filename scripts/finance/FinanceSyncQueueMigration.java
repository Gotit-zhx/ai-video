package com.yingdao.aicommerce.finance;

/** Add the order-to-invoice notification queue without rewriting any source data. */
public final class FinanceSyncQueueMigration {
    public static void main(String[] args){
        var flyway=org.flywaydb.core.Flyway.configure()
            .dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))
            .locations("classpath:db/migration").validateMigrationNaming(true).validateOnMigrate(true)
            .initSql("SET SESSION lock_wait_timeout=10").target("139").load();
        var current=flyway.info().current();
        if(current==null||!java.util.Set.of("138","139").contains(current.getVersion().getVersion()))
            throw new IllegalStateException("Unexpected production schema");
        for(var pending:flyway.info().pending())if(!pending.getVersion().getVersion().equals("139"))
            throw new IllegalStateException("Only queue migration 139 is authorized");
        System.out.println("Invoice arrival queue verified; migrations applied="+flyway.migrate().migrationsExecuted);
    }
}
