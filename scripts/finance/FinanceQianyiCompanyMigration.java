package com.yingdao.aicommerce.finance;

/** Add only the reviewed Qianyi configuration/source tables; no financial rows are rewritten. */
public final class FinanceQianyiCompanyMigration {
    public static void main(String[] args){
        var flyway=org.flywaydb.core.Flyway.configure()
            .dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))
            .locations("classpath:db/migration").validateMigrationNaming(true).validateOnMigrate(true)
            .initSql("SET SESSION lock_wait_timeout=10").target("140").load();
        var current=flyway.info().current();
        if(current==null||!java.util.Set.of("139","140").contains(current.getVersion().getVersion()))
            throw new IllegalStateException("Unexpected production schema");
        for(var pending:flyway.info().pending())if(!pending.getVersion().getVersion().equals("140"))
            throw new IllegalStateException("Only Qianyi migration 140 is authorized");
        System.out.println("Qianyi additive tables verified; migrations applied="+flyway.migrate().migrationsExecuted);
    }
}
