package com.yingdao.aicommerce.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated baseline-159 -> V160 upgrade using the repository migration file. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceProjectionRecordIdentityMigrationIntegrationTest {
    @TempDir Path temporary;

    @Test void upgradesTheOldGuardAndInvalidatesEveryPreviouslyReadyCompany()throws Exception{
        var f=new FinanceConfigurationIntegrationTest();
        try{
            f.migrateThrough(157);
            f.migration("V159__finance_first_settlement_order.sql");
            f.setup();
            String shop=f.connect(f.owner,false);
            var projections=new FinanceQueryProjections(f.repo,f.service,new FinanceAdSpendService(f.repo,f.service));
            var month=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
            var entry=new Entry(shop,"SHOPEE","FEE","old-number","","","pay_per_sale","SETTLED",LocalDate.of(2026,10,12),"USD",decimal("12.123456"),null,null,null,null,"API",Map.of());
            f.repo.save(f.owner,entry);
            String recordId=f.db.queryForObject("SELECT id FROM finance_records WHERE organization_id=? AND external_id='old-number'",String.class,f.owner.organizationId());

            projections.request(f.owner,month);
            drainAndBuild(projections);
            assertEquals(0,eventCount(f,f.owner.organizationId()));
            var initialVersion=projections.version(f.owner,month);
            assertEquals("READY",initialVersion.get("status"));
            assertTrue(projections.find(f.owner,month).isPresent());
            String oldRevision=String.valueOf(initialVersion.get("revision"));

            assertEquals(1,f.db.update("UPDATE finance_records SET external_id='new-number' WHERE id=?",recordId));
            assertEquals(0,eventCount(f,f.owner.organizationId()),"The baseline-159 guard must reproduce the omitted identity event");
            assertEquals("READY",projections.version(f.owner,month).get("status"));
            assertTrue(projections.find(f.owner,month).isPresent());

            var sourceBefore=f.db.queryForMap("SELECT id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,currency,amount,source,SHA2(CAST(raw_json AS CHAR),256) raw_hash FROM finance_records WHERE id=?",recordId);
            var otherTriggers=triggersExceptTarget(f);
            int triggerCount=f.db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",Integer.class);

            Path migration=Path.of("src/main/resources/db/migration/V160__finance_projection_record_identity_events.sql");
            assertTrue(Files.exists(migration));
            Files.copy(migration,temporary.resolve(migration.getFileName()));
            var flyway=Flyway.configure().dataSource(f.dataSource).locations("filesystem:"+temporary).baselineVersion("159").baselineDescription("isolated fixture baseline 159").load();
            flyway.baseline();
            var historyPrefix=history(f);
            assertEquals(1,flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals("160",flyway.info().current().getVersion().getVersion());
            assertEquals(historyPrefix,f.db.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM flyway_schema_history WHERE version<>'160' ORDER BY installed_rank"));
            assertEquals(0,flyway.migrate().migrationsExecuted);

            assertEquals(sourceBefore,f.db.queryForMap("SELECT id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,currency,amount,source,SHA2(CAST(raw_json AS CHAR),256) raw_hash FROM finance_records WHERE id=?",recordId));
            assertEquals(triggerCount,f.db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",Integer.class));
            assertEquals(otherTriggers,triggersExceptTarget(f));
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='fin_qp_records_au' AND event_object_table='finance_records' AND action_timing='AFTER' AND event_manipulation='UPDATE'",Integer.class));
            String trigger=f.db.queryForObject("SELECT action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='fin_qp_records_au'",String.class);
            for(String comparison:List.of("OLD.id <=> NEW.id","OLD.external_id <=> NEW.external_id","OLD.user_id <=> NEW.user_id"))
                assertEquals(2,occurrences(trigger,comparison),comparison+" must guard both OLD and NEW event projections");
            for(String retained:List.of("OLD.organization_id <=> NEW.organization_id","OLD.shop_id <=> NEW.shop_id","OLD.kind <=> NEW.kind","OLD.record_date <=> NEW.record_date","OLD.order_id <=> NEW.order_id","OLD.statement_id <=> NEW.statement_id","OLD.currency <=> NEW.currency","OLD.source <=> NEW.source","OLD.status <=> NEW.status","OLD.category <=> NEW.category","OLD.amount <=> NEW.amount","OLD.revenue <=> NEW.revenue","OLD.impressions <=> NEW.impressions","OLD.clicks <=> NEW.clicks","OLD.conversions <=> NEW.conversions","OLD.raw_json <=> NEW.raw_json","OLD.platform <=> NEW.platform"))
                assertEquals(2,occurrences(trigger,retained),retained+" must remain in both guards");
            assertEquals(2,occurrences(trigger,"'finance_records'"));

            var migrationEvents=f.db.queryForList("SELECT event_scope,record_date,shop_id,order_id,record_id,source_table,statement_id FROM finance_query_events ORDER BY event_id");
            assertEquals(1,migrationEvents.size());
            assertEquals("ALL",migrationEvents.getFirst().get("event_scope"));
            assertEquals("finance_records",migrationEvents.getFirst().get("source_table"));
            for(String nullable:List.of("record_date","shop_id","order_id","record_id","statement_id"))assertNull(migrationEvents.getFirst().get(nullable));
            assertEquals(0,eventCount(f,"empty-company"));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM finance_query_partitions WHERE organization_id='empty-company'",Integer.class));

            var pending=projections.version(f.owner,month);
            assertEquals("PENDING",pending.get("status"));
            assertEquals(oldRevision,pending.get("revision"));
            assertTrue(projections.find(f.owner,month).isEmpty());
            int drained=projections.drainEvents();
            assertEquals(1,drained);
            assertEquals(0,eventCount(f,f.owner.organizationId()));
            assertTrue(f.db.queryForObject("SELECT requested_revision>built_revision FROM finance_query_partitions WHERE organization_id=? AND platform_filter='' AND partition_month='2026-10-01'",Boolean.class,f.owner.organizationId()));
            assertEquals("PENDING",projections.version(f.owner,month).get("status"));
            for(int i=0;i<20&&projections.buildNext();i++){}
            var ready=projections.version(f.owner,month);
            assertEquals("READY",ready.get("status"));
            assertNotEquals(oldRevision,ready.get("revision"));
            assertTrue(projections.find(f.owner,month).isPresent());

            assertEquals(0,number(f.repo.details(f.owner,month,"FEE",1,50,"old-number",false).get("total")));
            var current=f.repo.details(f.owner,month,"FEE",1,50,"new-number",false);
            assertEquals(1,number(current.get("total")));
            assertEquals("new-number",rows(current.get("items")).getFirst().get("external_id"));
            assertEquals(0,decimal("12.123456").compareTo(decimal(rows(current.get("items")).getFirst().get("amount"))));

            System.out.println("FINANCE_PROJECTION_RECORD_IDENTITY_MIGRATION "+f.repo.write(Map.of(
                "baselineHistory",historyPrefix,"migrationEvents",migrationEvents,"oldRevision",oldRevision,"newRevision",ready.get("revision"),
                "source",sourceBefore,"nativeCurrent",current,"triggerCount",triggerCount,"drained",drained,"emptyCompanyEvents",eventCount(f,"empty-company"))));
        }finally{f.closeDataSource();}
    }

    private static void drainAndBuild(FinanceQueryProjections projections){
        for(int i=0;i<20&&projections.drainEvents()>0;i++){}
        for(int i=0;i<20&&projections.buildNext();i++){}
    }
    private static long eventCount(FinanceConfigurationIntegrationTest f,String organization){return f.db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE organization_id=?",Long.class,organization);}
    private static List<Map<String,Object>> history(FinanceConfigurationIntegrationTest f){return f.db.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");}
    private static List<Map<String,Object>> triggersExceptTarget(FinanceConfigurationIntegrationTest f){return f.db.queryForList("SELECT trigger_name,action_timing,event_manipulation,event_object_table,action_statement,action_orientation,action_condition FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name<>'fin_qp_records_au' ORDER BY trigger_name");}
    private static int occurrences(String text,String value){int count=0,offset=0;while((offset=text.indexOf(value,offset))>=0){count++;offset+=value.length();}return count;}
}
