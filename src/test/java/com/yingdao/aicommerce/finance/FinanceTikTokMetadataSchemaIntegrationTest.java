package com.yingdao.aicommerce.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import javax.tools.ToolProvider;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the exact fixed V156 helper and SQL against a disposable real MySQL schema. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceTikTokMetadataSchemaIntegrationTest {
    @TempDir Path temporary;
    @Test void fixedHelperPreservesV155HistoryAndProtectedAdDataAndRefusesReplay()throws Exception{
        temporary=temporary.toRealPath();
        var fixture=new FinanceConfigurationIntegrationTest();
        try{
            fixture.migrateThrough(154);
            var oldRuntime=temporary.resolve("old/db/migration");Files.createDirectories(oldRuntime);
            try(var sqls=Files.list(Path.of("src/main/resources/db/migration"))){
                for(var sql:sqls.filter(p->p.getFileName().toString().matches("V[0-9]+__.*\\.sql")).toList()){
                    int version=Integer.parseInt(sql.getFileName().toString().split("__")[0].substring(1));
                    if(version<=155)Files.copy(sql,oldRuntime.resolve(sql.getFileName()));
                }
            }
            var old=Flyway.configure().dataSource(fixture.dataSource).locations("filesystem:"+oldRuntime)
                .baselineVersion("154").target("155").load();
            old.baseline();assertEquals(1,old.migrate().migrationsExecuted);assertTrue(old.validateWithResult().validationSuccessful);
            assertEquals("155",old.info().current().getVersion().getVersion());
            fixture.setup();
            fixture.db.update("""
                INSERT INTO finance_ad_accounts(organization_id,user_id,advertiser_id,name,currency,access_token_encrypted) VALUES
                ('org','u','adv','Account','PHP','encrypted'),('org2','u2','adv2','Account 2','PHP','encrypted-2')
                """);
            fixture.db.update("INSERT INTO finance_ad_stores(organization_id,advertiser_id,store_id,store_name) VALUES('org','adv','store','Store')");
            fixture.db.update("INSERT INTO finance_ad_streams(organization_id,advertiser_id,channel,store_id) VALUES('org','adv','BASIC','store')");
            fixture.db.update("INSERT INTO finance_ad_coverage(organization_id,advertiser_id,channel,store_id,record_date,row_count,amount,currency) VALUES('org','adv','BASIC','store','2026-10-01',1,3,'PHP')");
            fixture.db.update("INSERT INTO finance_ad_bc_sync(organization_id,credential_key) VALUES('org','credential')");
            fixture.db.update("INSERT INTO finance_ad_bc_costs(organization_id,source_key,advertiser_id,advertiser_name,record_date,currency,amount,bc_ids_json) VALUES('org','bc-source','adv','Account','2026-10-01','PHP',4,'[]')");
            fixture.db.update("""
                INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,currency,amount,source,raw_json) VALUES
                ('ad-row','org','u','','TIKTOK_SHOP','AD','external','2026-10-01','PHP',5,'API','{\"advertiser_id\":\"adv\"}'),
                ('ad-row-old','org','u','','TIKTOK_SHOP','AD','external-old','2026-09-30','PHP',4,'API','{\"advertiser_id\":\"adv\"}'),
                ('gmv-row','org','u','','TIKTOK_SHOP','AD_GMV','gmv','2026-10-01','PHP',7,'API','{\"advertiser_id\":\"adv\"}'),
                ('gmv-row-next','org','u','','TIKTOK_SHOP','AD_GMV','gmv-next','2026-10-02','PHP',6,'API','{\"advertiser_id\":\"adv\"}'),
                ('order-row','org','u','','TIKTOK_SHOP','ORDER','order','2026-10-01','PHP',99,'API','{}'),
                ('ad-row-2','org2','u2','','TIKTOK_SHOP','AD','external-2','2026-10-01','PHP',8,'API','{\"advertiser_id\":\"adv2\"}'),
                ('unowned-ad','unknown','u3','','TIKTOK_SHOP','AD','external-3','2026-10-01','PHP',9,'API','{\"advertiser_id\":\"unknown\"}')
                """);
            fixture.db.update("INSERT INTO finance_ad_spend_imports(organization_id,source_key,platform,account_id,account_name,campaign_id,record_date,currency,amount,source_json,file_name,created_by) VALUES('org','import-source','TIKTOK_SHOP','adv','Account','campaign','2026-10-01','PHP',6,'{}','private.csv','u')");
            fixture.db.update("INSERT INTO finance_ad_spend_allocation_sets(id,organization_id,source_key,revision,fingerprint,created_by) VALUES('allocation-set','org','import-source',1,'fingerprint','u')");
            fixture.db.update("INSERT INTO finance_ad_spend_allocations(allocation_set_id,shop_id,amount) VALUES('allocation-set','shop',6)");
            var protectedTables=List.of("finance_records","finance_ad_accounts","finance_ad_stores","finance_ad_streams","finance_ad_coverage","finance_ad_bc_sync","finance_ad_bc_costs","finance_ad_spend_imports","finance_ad_spend_allocation_sets","finance_ad_spend_allocations","finance_connections","finance_authorization_sessions","finance_sync_states");
            var before=new LinkedHashMap<String,List<Map<String,Object>>>();
            for(var table:protectedTables)before.put(table,fixture.db.queryForList("SELECT * FROM "+table));
            var bridgeClasses=compileSnapshotBridge();
            var snapshotConfig=temporary.resolve("snapshot-config.json");Files.writeString(snapshotConfig,"{}");
            var snapshotBefore=pythonSnapshot(snapshotConfig,bridgeClasses,"before");
            assertEquals(2,snapshotBefore.get("version"));
            assertEquals(5,tableSnapshot(snapshotBefore,"finance_records").get("rows"),"Every date of AD/AD_GMV for companies owning ad accounts is guarded");
            var dateQueries=queries(snapshotBefore).stream().filter(q->q.contains("DISTINCT record_date")).toList();
            var recordQueries=queries(snapshotBefore).stream().filter(q->q.contains("FROM `finance_records`")&&q.contains("SHA2(")).toList();
            assertEquals(4,dateQueries.size());assertEquals(5,recordQueries.size());
            assertTrue(recordQueries.stream().allMatch(q->q.matches("(?s).*kind='AD(?:_GMV)?'.*record_date='2026-[0-9]{2}-[0-9]{2}'.*ORDER BY `id`.*")));
            String explain=fixture.db.queryForObject("EXPLAIN FORMAT=JSON "+recordQueries.stream().filter(q->q.contains("kind='AD'")).findFirst().orElseThrow(),String.class);
            var planTable=planTable(new ObjectMapper().readValue(explain,Map.class));
            System.out.println("Task2 finance_records guard plan: "+explain);
            assertEquals("idx_finance_page",planTable.get("key"));
            assertEquals(List.of("organization_id","kind","record_date"),planTable.get("used_key_parts"));
            assertTrue(Set.of("ref","range").contains(planTable.get("access_type")));
            assertFalse(explain.contains("\"using_filesort\": true"));
            fixture.db.update("UPDATE finance_ad_spend_imports SET amount=7 WHERE organization_id='org' AND source_key='import-source'");
            var rowDrift=pythonSnapshot(snapshotConfig,bridgeClasses,"row-drift");
            assertNotEquals(tableSnapshot(snapshotBefore,"finance_ad_spend_imports"),tableSnapshot(rowDrift,"finance_ad_spend_imports"));
            assertEquals(snapshotBefore.get("metadataSha256"),rowDrift.get("metadataSha256"));
            fixture.db.update("UPDATE finance_ad_spend_imports SET amount=6 WHERE organization_id='org' AND source_key='import-source'");
            fixture.db.execute("ALTER TABLE finance_ad_accounts ADD COLUMN task2_guard_probe INT NULL");
            var schemaDrift=pythonSnapshot(snapshotConfig,bridgeClasses,"schema-drift");
            assertNotEquals(snapshotBefore.get("metadataSha256"),schemaDrift.get("metadataSha256"));
            fixture.db.execute("ALTER TABLE finance_ad_accounts DROP COLUMN task2_guard_probe");
            var addition=Files.createDirectory(temporary.resolve("addition"));
            Files.copy(Path.of("src/main/resources/db/migration/V156__finance_ad_campaign_metadata.sql"),addition.resolve("V156__finance_ad_campaign_metadata.sql"));
            var classes=Files.createDirectory(temporary.resolve("helper-classes"));
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),"scripts/qa/java/FinanceTikTokMetadataSchemaMaintenance.java"));
            String owner=UUID.randomUUID().toString().replace("-","");
            var launch=temporary.resolve("launch.json");Files.writeString(launch,"\""+owner+"\"");
            var values=Map.of("url",System.getenv("FINANCE_QA_JDBC"),"user",System.getenv("FINANCE_QA_USER"),"password",System.getenv("FINANCE_QA_PASSWORD"),"owner",owner);
            var libraries=Arrays.stream(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(File.pathSeparator))
                .filter(p->!Path.of(p).toAbsolutePath().normalize().equals(Path.of("target/classes").toAbsolutePath().normalize()))
                .filter(p->!Path.of(p).toAbsolutePath().normalize().equals(Path.of("target/test-classes").toAbsolutePath().normalize())).toList();
            String classpath=classes+File.pathSeparator+temporary.resolve("old")+File.pathSeparator+String.join(File.pathSeparator,libraries);
            var applied155=fixture.db.queryForMap("SELECT * FROM flyway_schema_history WHERE version='155' AND type='SQL'");
            fixture.db.update("DELETE FROM flyway_schema_history WHERE version='155' AND type='SQL'");
            fixture.db.update("UPDATE flyway_schema_history SET version='155' WHERE type='BASELINE' AND version='154'");
            assertNotEquals(0,helper("validate",addition,launch,owner,classpath,values,false),"BASELINE 155 cannot substitute for applied V155 SQL");
            fixture.db.update("UPDATE flyway_schema_history SET version='154' WHERE type='BASELINE' AND version='155'");
            fixture.db.update("""
                INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,applied155.get("installed_rank"),applied155.get("version"),applied155.get("description"),applied155.get("type"),
                applied155.get("script"),applied155.get("checksum"),applied155.get("installed_by"),applied155.get("installed_on"),
                applied155.get("execution_time"),applied155.get("success"));
            fixture.db.update("UPDATE flyway_schema_history SET script='V155__wrong.sql' WHERE version='155' AND type='SQL'");
            assertNotEquals(0,helper("validate",addition,launch,owner,classpath,values,false),"V155 script drift must be rejected");
            fixture.db.update("UPDATE flyway_schema_history SET script=? WHERE version='155' AND type='SQL'",applied155.get("script"));
            fixture.db.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='155' AND type='SQL'");
            assertNotEquals(0,helper("validate",addition,launch,owner,classpath,values,false),"V155 checksum drift must be rejected");
            fixture.db.update("UPDATE flyway_schema_history SET checksum=? WHERE version='155' AND type='SQL'",applied155.get("checksum"));
            assertTrue(old.validateWithResult().validationSuccessful);
            var prefix=fixture.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            assertEquals(0,helper("validate",addition,launch,owner,classpath,values,true));
            assertEquals(prefix,fixture.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
            assertEquals(0,helper("migrate",addition,launch,owner,classpath,values,true));
            for(var table:protectedTables)assertEquals(before.get(table),fixture.db.queryForList("SELECT * FROM "+table),table);
            var after=fixture.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            assertEquals(prefix,after.subList(0,prefix.size()));assertEquals(prefix.size()+1,after.size());
            assertEquals("156",after.getLast().get("version"));assertTrue(old.validateWithResult().validationSuccessful);
            assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='finance_ad_campaign_metadata'",Integer.class));
            assertNotEquals(0,helper("migrate",addition,launch,owner,classpath,values,false),"An already applied V156 cannot be replayed");
            assertEquals(after,fixture.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
        }finally{fixture.closeDataSource();}
    }
    private int helper(String mode,Path addition,Path launch,String owner,String classpath,Map<String,String> values,boolean expectSuccess)throws Exception{
        var output=temporary.resolve("helper-"+mode+"-"+UUID.randomUUID()+".log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Dheyi.financeProjectionSchemaOwner="+owner,"-cp",classpath,"FinanceTikTokMetadataSchemaMaintenance",mode,addition.toString(),launch.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try(var input=process.getOutputStream()){input.write(new ObjectMapper().writeValueAsBytes(values));}
        if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Disposable schema helper did not finish");}
        if(expectSuccess)assertTrue(Files.readString(output).contains("\"success\":true"),"Fixed helper did not confirm the expected operation: "+Files.readAllLines(output).stream().filter(line->line.startsWith("{\"success\"")).reduce((a,b)->b).orElse("no JSON diagnostic"));
        return process.exitValue();
    }
    private Path compileSnapshotBridge()throws Exception{
        var source=temporary.resolve("SnapshotSql.java");
        Files.writeString(source,"""
            import java.sql.*;
            public class SnapshotSql {
                public static void main(String[] args)throws Exception{
                    String query=new String(System.in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    try(var connection=DriverManager.getConnection(System.getenv("FINANCE_QA_JDBC"),System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD"));
                        var statement=connection.createStatement();var rows=statement.executeQuery(query)){
                        int count=rows.getMetaData().getColumnCount();StringBuilder output=new StringBuilder();
                        while(rows.next()){
                            if(output.length()>0)output.append('\\n');
                            for(int index=1;index<=count;index++){
                                if(index>1)output.append('\\t');String value=rows.getString(index);
                                if(value==null||value.indexOf('\\t')>=0||value.indexOf('\\n')>=0||value.indexOf('\\r')>=0)throw new IllegalStateException();
                                output.append(value);
                            }
                        }
                        System.out.print(output);
                    }
                }
            }
            """);
        var classes=Files.createDirectory(temporary.resolve("snapshot-bridge-classes"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),source.toString()));
        return classes;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> pythonSnapshot(Path config,Path bridgeClasses,String name)throws Exception{
        var harness=temporary.resolve("snapshot-harness.py");
        if(!Files.exists(harness))Files.writeString(harness,"""
            import importlib.util,json,os,subprocess,sys
            from pathlib import Path
            script=Path(sys.argv[1]);spec=importlib.util.spec_from_file_location('task2_activation',script)
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            class Host:
                def __init__(self): self.queries=[]
                def sql(self,query):
                    self.queries.append(query)
                    result=subprocess.run([os.environ['TASK2_JAVA'],'-cp',os.environ['TASK2_SQL_CLASSPATH'],'SnapshotSql'],input=query,text=True,capture_output=True)
                    if result.returncode: raise RuntimeError('Snapshot SQL bridge failed: '+result.stderr)
                    return result.stdout
            host=Host();activation=object.__new__(module.Activation);activation.config_path=Path(sys.argv[2])
            activation.runtime=lambda config:type('Runtime',(),{'host':host})()
            value=activation.protected_snapshot();value['queries']=host.queries
            Path(sys.argv[3]).write_text(json.dumps(value,sort_keys=True))
            """);
        var output=temporary.resolve("snapshot-"+name+".json");
        var process=new ProcessBuilder("python3",harness.toString(),Path.of("scripts/maintenance/finance_tiktok_metadata_activation.py").toAbsolutePath().toString(),config.toString(),output.toString()).redirectErrorStream(true).redirectOutput(temporary.resolve("snapshot-"+name+".log").toFile());
        process.environment().put("TASK2_JAVA",Path.of(System.getProperty("java.home"),"bin/java").toString());
        process.environment().put("TASK2_SQL_CLASSPATH",bridgeClasses+File.pathSeparator+System.getProperty("java.class.path"));
        var running=process.start();
        if(!running.waitFor(90,java.util.concurrent.TimeUnit.SECONDS)){running.destroyForcibly();fail("Python snapshot SQL timed out");}
        assertEquals(0,running.exitValue(),"Python snapshot SQL failed: "+Files.readString(temporary.resolve("snapshot-"+name+".log")));
        assertTrue(Files.exists(output),"Python snapshot SQL did not complete: "+Files.readString(temporary.resolve("snapshot-"+name+".log")));
        return new ObjectMapper().readValue(Files.readAllBytes(output),Map.class);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> tableSnapshot(Map<String,Object> snapshot,String table){
        return (Map<String,Object>)((Map<String,Object>)snapshot.get("tables")).get(table);
    }
    @SuppressWarnings("unchecked") private List<String> queries(Map<String,Object> snapshot){return (List<String>)snapshot.get("queries");}
    @SuppressWarnings("unchecked") private Map<String,Object> planTable(Object value){
        if(value instanceof Map<?,?> map){
            if("finance_records".equals(map.get("table_name")))return (Map<String,Object>)map;
            for(Object nested:map.values()){var found=planTable(nested);if(found!=null)return found;}
        }else if(value instanceof List<?> list)for(Object nested:list){var found=planTable(nested);if(found!=null)return found;}
        return null;
    }
}
