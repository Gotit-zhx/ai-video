package com.yingdao.aicommerce.finance;

/** Run only the reviewed additive lookup-index migration while the web service stays available. */
public final class FinanceQueryIndexMigration {
    public static void main(String[] args) {
        var flyway=org.flywaydb.core.Flyway.configure()
                .dataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"))
                .locations("classpath:db/migration").validateMigrationNaming(true).validateOnMigrate(true)
                .initSql("SET SESSION lock_wait_timeout=10").target("136").load();
        var current=flyway.info().current();
        if(current==null||!java.util.Set.of("135","136").contains(current.getVersion().getVersion()))
            throw new IllegalStateException("Unexpected production schema; review required");
        var pending=flyway.info().pending();
        if(pending.length>1||(pending.length==1&&!pending[0].getVersion().getVersion().equals("136")))
            throw new IllegalStateException("Only the reviewed index migration may run");
        var result=flyway.migrate();
        System.out.println("Verified lookup index migration; applied="+result.migrationsExecuted);
    }
}
