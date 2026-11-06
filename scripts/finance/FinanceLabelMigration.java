package com.yingdao.aicommerce.finance;

/** Add only the reviewed invoice queue/source/snapshot tables. Existing financial records are unchanged. */
public final class FinanceLabelMigration {
    public static void main(String[] args){
        var flyway=org.flywaydb.core.Flyway.configure()
            .dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))
            .locations("classpath:db/migration").validateMigrationNaming(true).validateOnMigrate(true)
            .initSql("SET SESSION lock_wait_timeout=10").target("137").load();
        var current=flyway.info().current();
        if(current==null||!java.util.Set.of("136","137").contains(current.getVersion().getVersion()))
            throw new IllegalStateException("Unexpected production schema");
        for(var pending:flyway.info().pending())if(!pending.getVersion().getVersion().equals("137"))
            throw new IllegalStateException("Only invoice migration 137 is authorized");
        System.out.println("Invoice tables verified; migrations applied="+flyway.migrate().migrationsExecuted);
    }
}
