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

/** Real isolated V160 -> V161 upgrade using the repository migration file. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceProjectionCurrencyByteMigrationIntegrationTest {
    @TempDir Path temporary;

    @Test void fencesExistingReadyFactsAndRegistersCurrencyBytesWithoutChangingOtherGuards()throws Exception{
        var f=new FinanceConfigurationIntegrationTest();
        try{
            f.migrateThrough(158);
            f.migration("V159__finance_first_settlement_order.sql");
            f.migration("V160__product_capture_templates.sql");
            // The current reader needs the settlement-cost tables. These migrations leave the V160 trigger intact.
            f.migration("V163__finance_settlement_cost_projection.sql");
            f.migration("V164__finance_settlement_cost_scope_variants.sql");
            f.setup();
            String shop=f.connect(f.owner,false);
            var projections=new FinanceQueryProjections(f.repo,f.service,new FinanceAdSpendService(f.repo,f.service));
            var month=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
            var entry=new Entry(shop,"SHOPEE","FEE","currency-fee","","","pay_per_sale","SETTLED",LocalDate.of(2026,10,12),"USD",decimal("12.123456"),null,null,null,null,"API",Map.of());
            f.repo.save(f.owner,entry);
            String recordId=f.db.queryForObject("SELECT id FROM finance_records WHERE organization_id=? AND external_id='currency-fee'",String.class,f.owner.organizationId());
            projections.request(f.owner,month);drainAndBuild(projections);
            assertEquals(0,eventCount(f,f.owner.organizationId()));
            var initialVersion=projections.version(f.owner,month);assertEquals("READY",initialVersion.get("status"));assertTrue(projections.find(f.owner,month).isPresent());
            String oldRevision=text(initialVersion.get("revision"));

            var sourceBefore=source(f,recordId);
            var otherTriggers=triggersExceptTarget(f);
            int triggerCount=f.db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",Integer.class);
            String oldTrigger=targetTrigger(f);
            assertEquals(2,occurrences(oldTrigger,"OLD.currency <=> NEW.currency"));
            assertEquals(0,occurrences(oldTrigger,"HEX(OLD.currency) <=> HEX(NEW.currency)"));
            assertEquals(0,occurrences(oldTrigger,"OLD.external_id <=> NEW.external_id"));

            try(var connection=f.dataSource.getConnection()){
                connection.setAutoCommit(false);
                try(var statement=connection.prepareStatement("UPDATE finance_records SET currency='usd' WHERE id=?")){
                    statement.setString(1,recordId);assertEquals(1,statement.executeUpdate());
                }
                connection.rollback();
            }
            assertEquals(sourceBefore,source(f,recordId));assertEquals(0,eventCount(f,f.owner.organizationId()));

            Path migration=Path.of("src/main/resources/db/migration/V161__finance_projection_currency_byte_events.sql");
            assertTrue(Files.exists(migration));Files.copy(migration,temporary.resolve(migration.getFileName()));
            var flyway=Flyway.configure().dataSource(f.dataSource).locations("filesystem:"+temporary).baselineVersion("160").baselineDescription("isolated fixture baseline 160").load();
            flyway.baseline();var historyPrefix=history(f);
            assertEquals(1,flyway.migrate().migrationsExecuted);assertTrue(flyway.validateWithResult().validationSuccessful);assertEquals("161",flyway.info().current().getVersion().getVersion());
            assertEquals(historyPrefix,f.db.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM flyway_schema_history WHERE version<>'161' ORDER BY installed_rank"));
            assertEquals(0,flyway.migrate().migrationsExecuted);

            assertEquals(sourceBefore,source(f,recordId));
            assertEquals(triggerCount,f.db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",Integer.class));
            assertEquals(otherTriggers,triggersExceptTarget(f));
            String trigger=targetTrigger(f);
            assertEquals(2,occurrences(trigger,"HEX(OLD.currency) <=> HEX(NEW.currency)"));
            assertEquals(0,occurrences(trigger,"OLD.currency <=> NEW.currency"));
            for(String retained:List.of("OLD.organization_id <=> NEW.organization_id","OLD.id <=> NEW.id","OLD.user_id <=> NEW.user_id","OLD.shop_id <=> NEW.shop_id","OLD.kind <=> NEW.kind","OLD.external_id <=> NEW.external_id","OLD.record_date <=> NEW.record_date","OLD.order_id <=> NEW.order_id","OLD.statement_id <=> NEW.statement_id","OLD.source <=> NEW.source","OLD.status <=> NEW.status","OLD.category <=> NEW.category","OLD.amount <=> NEW.amount","OLD.revenue <=> NEW.revenue","OLD.impressions <=> NEW.impressions","OLD.clicks <=> NEW.clicks","OLD.conversions <=> NEW.conversions","OLD.raw_json <=> NEW.raw_json","OLD.platform <=> NEW.platform"))
                assertEquals(2,occurrences(trigger,retained),retained);

            var migrationEvents=events(f);assertEquals(1,migrationEvents.size());assertEquals("ALL",migrationEvents.getFirst().get("event_scope"));assertEquals("finance_records",migrationEvents.getFirst().get("source_table"));
            for(String nullable:List.of("record_date","shop_id","order_id","record_id","statement_id"))assertNull(migrationEvents.getFirst().get(nullable));
            assertEquals(0,eventCount(f,"empty-company"));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM finance_query_partitions WHERE organization_id='empty-company'",Integer.class));
            var pending=projections.version(f.owner,month);assertEquals("PENDING",pending.get("status"));assertEquals(oldRevision,pending.get("revision"));assertTrue(projections.find(f.owner,month).isEmpty());
            assertEquals(1,projections.drainEvents());assertEquals(0,eventCount(f,f.owner.organizationId()));assertEquals("PENDING",projections.version(f.owner,month).get("status"));
            for(int i=0;i<20&&projections.buildNext();i++){}
            var ready=projections.version(f.owner,month);assertEquals("READY",ready.get("status"));assertNotEquals(oldRevision,ready.get("revision"));assertTrue(projections.find(f.owner,month).isPresent());

            try(var connection=f.dataSource.getConnection()){
                connection.setAutoCommit(false);
                try(var statement=connection.prepareStatement("UPDATE finance_records SET currency='usd' WHERE id=?")){
                    statement.setString(1,recordId);assertEquals(1,statement.executeUpdate());
                }
                connection.rollback();
            }
            assertEquals(sourceBefore,source(f,recordId));assertEquals(0,eventCount(f,f.owner.organizationId()));

            assertEquals(1,f.db.update("UPDATE finance_records SET currency='usd' WHERE id=?",recordId));
            var currencyEvents=events(f);assertEquals(2,currencyEvents.size());assertTrue(currencyEvents.stream().allMatch(row->"DAY".equals(row.get("event_scope"))&&recordId.equals(row.get("record_id"))&&"finance_records".equals(row.get("source_table"))));
            var sourceAfterCurrency=source(f,recordId);assertEquals("usd",sourceAfterCurrency.get("currency"));assertEquals("757364",sourceAfterCurrency.get("currency_hex"));
            for(String key:List.of("id","organization_id","user_id","shop_id","platform","kind","external_id","order_id","statement_id","category","status","record_date","amount","source","raw_hash","updated_at"))assertEquals(canonical(sourceBefore.get(key)),canonical(sourceAfterCurrency.get(key)),key);
            assertEquals("PENDING",projections.version(f.owner,month).get("status"));assertEquals(2,projections.drainEvents());assertEquals(0,eventCount(f,f.owner.organizationId()));

            assertEquals(1,f.db.update("UPDATE finance_records SET external_id='new-number' WHERE id=?",recordId));
            var identityEvents=events(f);assertEquals(2,identityEvents.size());assertTrue(identityEvents.stream().allMatch(row->"DAY".equals(row.get("event_scope"))&&recordId.equals(row.get("record_id"))));

            System.out.println("FINANCE_PROJECTION_CURRENCY_BYTE_MIGRATION "+f.repo.write(Map.of(
                "baselineHistory",historyPrefix,"migrationEvents",migrationEvents,"oldRevision",oldRevision,"newRevision",ready.get("revision"),
                "sourceBefore",sourceBefore,"sourceAfterCurrency",sourceAfterCurrency,"currencyEvents",currencyEvents,"identityEvents",identityEvents,"triggerCount",triggerCount)));
        }finally{f.closeDataSource();}
    }

    private static void drainAndBuild(FinanceQueryProjections projections){for(int i=0;i<20&&projections.drainEvents()>0;i++){}for(int i=0;i<20&&projections.buildNext();i++){} }
    private static long eventCount(FinanceConfigurationIntegrationTest f,String organization){return f.db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE organization_id=?",Long.class,organization);}
    private static List<Map<String,Object>> events(FinanceConfigurationIntegrationTest f){return f.db.queryForList("SELECT event_scope,record_date,shop_id,order_id,record_id,source_table,statement_id FROM finance_query_events ORDER BY event_id");}
    private static Map<String,Object> source(FinanceConfigurationIntegrationTest f,String id){return f.db.queryForMap("SELECT id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,HEX(currency) currency_hex,amount,source,SHA2(CAST(raw_json AS CHAR),256) raw_hash,updated_at FROM finance_records WHERE id=?",id);}
    private static String targetTrigger(FinanceConfigurationIntegrationTest f){return f.db.queryForObject("SELECT action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='fin_qp_records_au' AND event_object_table='finance_records' AND action_timing='AFTER' AND event_manipulation='UPDATE'",String.class);}
    private static List<Map<String,Object>> history(FinanceConfigurationIntegrationTest f){return f.db.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");}
    private static List<Map<String,Object>> triggersExceptTarget(FinanceConfigurationIntegrationTest f){return f.db.queryForList("SELECT trigger_name,action_timing,event_manipulation,event_object_table,action_statement,action_orientation,action_condition FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name<>'fin_qp_records_au' ORDER BY trigger_name");}
    private static int occurrences(String text,String value){int count=0,offset=0;while((offset=text.indexOf(value,offset))>=0){count++;offset+=value.length();}return count;}
    private static Object canonical(Object value){if(value instanceof Number number)return new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString();if(value instanceof java.sql.Timestamp timestamp)return timestamp.toInstant().toString();if(value instanceof java.sql.Date date)return date.toLocalDate().toString();return value;}
}
