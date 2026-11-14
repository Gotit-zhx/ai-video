package com.yingdao.aicommerce.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static com.yingdao.aicommerce.finance.FinanceLabelFirstSettlementRowIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the real migration and production first-row read in disposable MySQL. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceFirstSettlementIndexIntegrationTest {
    @TempDir Path migrations;

    @Test void migrationPreservesLedgerAndCoveringConsumersWhileProductionFirstRowAvoidsSorting() throws Exception {
        var f=new FinanceLabelFirstSettlementRowIntegrationTest();
        try {
            f.migrateThrough(154);
            for(String name:List.of("V155__finance_query_projections.sql","V156__finance_ad_campaign_metadata.sql","V157__finance_projection_baseline_revisions.sql"))
                Files.copy(Path.of("src/main/resources/db/migration",name),migrations.resolve(name));
            var old=Flyway.configure().dataSource(f.dataSource).locations("filesystem:"+migrations).baselineVersion("154").target("157").load();
            old.baseline();assertEquals(3,old.migrate().migrationsExecuted);
            assertTrue(old.validateWithResult().validationSuccessful);
            f.migratedThrough=157;f.setup();f.fixture();
            for(int i=0;i<256;i++)f.settlement("same-id-z"+String.format("%03d",i),f.owner,f.shopA,"same-id-currency",
                LocalDate.of(2026,10,25),"SHOPEE","结算","SETTLED","API","PHP","history-"+i);
            var records=f.db.queryForList("SELECT * FROM finance_records ORDER BY id");
            var history=f.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            var oldIndex=indexParts(f,"idx_finance_settled_order");
            assertEquals(15,oldIndex.size());
            var migration=Path.of("src/main/resources/db/migration/V159__finance_first_settlement_order.sql");
            assertTrue(Files.exists(migration),"The additive first-settlement migration must exist");
            Files.copy(migration,migrations.resolve(migration.getFileName()));
            var current=Flyway.configure().dataSource(f.dataSource).locations("filesystem:"+migrations).load();
            assertEquals(1,current.migrate().migrationsExecuted);
            assertEquals("159",current.info().current().getVersion().getVersion());
            assertTrue(current.validateWithResult().validationSuccessful);
            assertEquals(history,f.db.queryForList("SELECT * FROM flyway_schema_history WHERE version<>'159' ORDER BY installed_rank"));
            assertEquals(records,f.db.queryForList("SELECT * FROM finance_records ORDER BY id"));
            assertEquals(oldIndex,indexParts(f,"idx_finance_settled_order"),"Other covering lookup consumers retain their index unchanged");
            var parts=indexParts(f,"idx_finance_first_settlement");
            assertEquals(List.of("organization_id","shop_id","kind","order_id","source","status","record_date","id","platform","category","currency"),
                parts.stream().map(row->text(row.get("column_name"))).toList());
            assertTrue(parts.stream().allMatch(row->row.get("sub_part")==null&&"A".equals(row.get("collation"))),"Ordering requires complete ascending key parts");

            var cases=List.of(
                new Expected(f.filter(OCTOBER_1,OCTOBER_31,"","","",""),11,"59.234566",3,14),
                new Expected(f.filter(OCTOBER_1,OCTOBER_31,"","","USD",""),2,"20.000000",0,2),
                new Expected(f.filter(OCTOBER_1,OCTOBER_31,"","TIKTOK_SHOP","",""),2,"9.000000",0,2),
                new Expected(f.filter(OCTOBER_1,OCTOBER_31,"","","PHP",""),9,"39.234566",3,12),
                new Expected(f.filter(OCTOBER_1,OCTOBER_31,"","SHOPEE","",""),9,"50.234566",3,12),
                new Expected(f.filter(OCTOBER_1,OCTOBER_31,f.shopA,"","",""),10,"51.234566",3,13),
                new Expected(f.filter(OCTOBER_1,OCTOBER_31,"","","",f.groupA),10,"51.234566",3,13),
                new Expected(f.filter(OCTOBER_1,OCTOBER_31,"","","",f.groupB),1,"8.000000",0,1),
                new Expected(f.filter(OCTOBER_1,LocalDate.of(2026,10,15),"","","",""),10,"55.234566",3,13)
            );
            for(var expected:cases){
                var page=f.labels.workspace(f.owner,expected.filter(),Map.of());
                f.assertPeriod(page,expected.orders(),expected.amount(),expected.conflicts(),expected.shipments());
                f.assertPeriodEquals(f.originalPeriod(expected.filter()),map(page.get("period")));
            }
            f.assertPeriod(f.labels.workspace(f.other,f.filter(OCTOBER_1,OCTOBER_31,"","","",""),Map.of()),1,"16.000000",0,1);
            var query=f.traced.periods.getFirst();
            var output=Path.of("target","first-settlement-index");Files.createDirectories(output);
            Files.writeString(output.resolve("production-plan.json"),query.plan());
            Files.writeString(output.resolve("production-analyze.txt"),f.db.queryForObject("EXPLAIN ANALYZE "+query.sql(),String.class,query.args().toArray()));
            var plan=r0QueryBlock(new ObjectMapper().readValue(query.plan(),Map.class));
            assertFalse(plan.isEmpty(),"The production workspace EXPLAIN must expose the r0 lateral query block");
            assertTrue(hasTrue(plan,"using_index"),"The first-row read must stay covering: "+plan);
            assertFalse(hasTrue(plan,"using_filesort"),"The production first-row read must use index order: "+plan);
            assertEquals("idx_finance_first_settlement",r0Table(plan).get("key"));
        } finally {f.closeDataSource();}
    }

    record Expected(Filter filter,long orders,String amount,long conflicts,long shipments) {}
    private static List<Map<String,Object>> indexParts(FinanceLabelFirstSettlementRowIntegrationTest f,String index){
        return f.db.queryForList("SELECT seq_in_index,column_name,sub_part,collation,index_type FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name=? ORDER BY seq_in_index",index);
    }
    private static boolean hasTrue(Object value,String key){
        if(value instanceof Map<?,?> map)return Boolean.TRUE.equals(map.get(key))||map.values().stream().anyMatch(child->hasTrue(child,key));
        return value instanceof List<?> list&&list.stream().anyMatch(child->hasTrue(child,key));
    }
    private static boolean containsTable(Object value,String table){
        if(value instanceof Map<?,?> map)return table.equals(map.get("table_name"))||map.values().stream().anyMatch(child->containsTable(child,table));
        return value instanceof List<?> list&&list.stream().anyMatch(child->containsTable(child,table));
    }
    private static Map<String,Object> r0QueryBlock(Object value){
        if(value instanceof Map<?,?> raw){
            var map=FinanceData.map(raw);
            for(Object child:map.values()){
                var nested=r0QueryBlock(child);
                if(!nested.isEmpty())return nested;
            }
            if(map.get("query_block") instanceof Map<?,?> block&&containsTable(block,"r0"))return FinanceData.map(block);
        }else if(value instanceof List<?> list)for(Object child:list){
            var nested=r0QueryBlock(child);
            if(!nested.isEmpty())return nested;
        }
        return Map.of();
    }
    private static Map<String,Object> r0Table(Object value){
        if(value instanceof Map<?,?> raw){
            var map=FinanceData.map(raw);
            if("r0".equals(map.get("table_name")))return map;
            for(Object child:map.values()){
                var nested=r0Table(child);
                if(!nested.isEmpty())return nested;
            }
        }else if(value instanceof List<?> list)for(Object child:list){
            var nested=r0Table(child);
            if(!nested.isEmpty())return nested;
        }
        return Map.of();
    }
}
