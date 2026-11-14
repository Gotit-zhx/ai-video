package com.yingdao.aicommerce.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real V156 -> V157 migration, only in the runner's disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceProjectionBaselineMigrationIntegrationTest {
    @TempDir Path temporary;
    @Test void initializesRevisionFencesWithoutChangingOldFieldsPayloadsSourcesOrHistory()throws Exception{
        var f=new FinanceConfigurationIntegrationTest();
        try{
            f.migrateThrough(154);
            for(String name:List.of("V155__finance_query_projections.sql","V156__finance_ad_campaign_metadata.sql"))
                Files.copy(Path.of("src/main/resources/db/migration",name),temporary.resolve(name));
            var old=Flyway.configure().dataSource(f.dataSource).locations("filesystem:"+temporary).baselineVersion("154").target("156").load();
            old.baseline();assertEquals(2,old.migrate().migrationsExecuted);assertTrue(old.validateWithResult().validationSuccessful);
            f.db.update("""
                INSERT INTO finance_query_partitions(organization_id,platform_filter,partition_month,requested_revision,built_revision,full_rebuild,full_month_json,partial_first_day_json,spend_json,generated_at,lease_token,lease_until,failures,error_code) VALUES
                ('full','','2026-10-01',9,3,TRUE,'{"sentinel":1}','{"sentinel":2}','{"sentinel":3}','2026-11-01','lease','2026-11-02',2,'previous'),
                ('partial','SHOPEE','2026-10-01',7,5,FALSE,'{"sentinel":4}','{"sentinel":5}','{"sentinel":6}','2026-11-01',NULL,NULL,0,NULL),
                ('ready','','2026-10-01',6,6,FALSE,'{}','{}','{}','2026-11-01',NULL,NULL,0,NULL),
                ('new','','2026-10-01',1,0,TRUE,NULL,NULL,NULL,NULL,NULL,NULL,0,NULL)
                """);
            f.db.update("INSERT INTO finance_query_dirty_days VALUES('full','','2026-10-01','2026-10-03'),('partial','SHOPEE','2026-10-01','2026-10-04')");
            f.db.update("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,currency,amount,source,raw_json) VALUES('sentinel','full','u','','SHOPEE','FEE','sentinel','2026-10-03','PHP',9.123456,'API','{}')");
            var partitions=f.db.queryForList("SELECT * FROM finance_query_partitions ORDER BY organization_id");
            var days=f.db.queryForList("SELECT * FROM finance_query_dirty_days ORDER BY organization_id");
            var sources=f.db.queryForList("SELECT * FROM finance_records ORDER BY id");
            var events=f.db.queryForList("SELECT * FROM finance_query_events ORDER BY event_id");
            var history=f.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            var migration=Path.of("src/main/resources/db/migration/V157__finance_projection_baseline_revisions.sql");
            assertTrue(Files.exists(migration),"V157 baseline revision migration must exist");
            Files.copy(migration,temporary.resolve(migration.getFileName()));
            var current=Flyway.configure().dataSource(f.dataSource).locations("filesystem:"+temporary).load();
            assertEquals(1,current.migrate().migrationsExecuted);assertTrue(current.validateWithResult().validationSuccessful);
            assertEquals("157",current.info().current().getVersion().getVersion());
            var after=f.db.queryForList("SELECT * FROM finance_query_partitions ORDER BY organization_id");
            var fences=new LinkedHashMap<String,Long>();after.forEach(row->fences.put(row.get("organization_id").toString(),((Number)row.remove("full_rebuild_revision")).longValue()));
            assertEquals(Map.of("full",9L,"partial",5L,"ready",6L,"new",1L),fences);assertEquals(partitions,after);
            var afterDays=f.db.queryForList("SELECT * FROM finance_query_dirty_days ORDER BY organization_id");
            assertEquals(List.of(9L,7L),afterDays.stream().map(row->((Number)row.remove("requested_revision")).longValue()).toList());assertEquals(days,afterDays);
            assertEquals(sources,f.db.queryForList("SELECT * FROM finance_records ORDER BY id"));
            assertEquals(events,f.db.queryForList("SELECT * FROM finance_query_events ORDER BY event_id"));
            assertEquals(history,f.db.queryForList("SELECT * FROM flyway_schema_history WHERE version<>'157' ORDER BY installed_rank"));
            f.db.update("INSERT INTO finance_query_partitions(organization_id,partition_month) VALUES('default','2026-11-01')");
            f.db.update("INSERT INTO finance_query_dirty_days(organization_id,platform_filter,partition_month,record_date) VALUES('default','','2026-11-01','2026-11-02')");
            assertEquals(1L,f.db.queryForObject("SELECT full_rebuild_revision FROM finance_query_partitions WHERE organization_id='default'",Long.class));
            assertEquals(0L,f.db.queryForObject("SELECT requested_revision FROM finance_query_dirty_days WHERE organization_id='default'",Long.class));
        }finally{f.closeDataSource();}
    }
}
