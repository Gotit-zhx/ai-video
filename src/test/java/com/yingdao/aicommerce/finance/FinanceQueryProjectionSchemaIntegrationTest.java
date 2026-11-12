package com.yingdao.aicommerce.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.File;
import java.time.*;
import java.util.*;
import javax.tools.ToolProvider;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the exact fixed helper and SQL against a disposable real MySQL schema. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceQueryProjectionSchemaIntegrationTest {
    @TempDir Path temporary;
    @Test void fixedHelperPreservesV154HistoryAndExistingDataAndRefusesReplay()throws Exception{
        temporary=temporary.toRealPath();
        var fixture=new FinanceConfigurationIntegrationTest();
        try{
            fixture.migrate();fixture.migration("V150__finance_ad_spend_workspace.sql");fixture.setup();
            String shop=fixture.connect(fixture.owner,false);
            fixture.repo.save(fixture.owner,new Entry(shop,"SHOPEE","FEE","before155","","","commission_fee","SETTLED",LocalDate.of(2026,10,3),"PHP",decimal("12.123456"),null,null,null,null,"API",Map.of("protected",true)));
            var before=fixture.db.queryForList("SELECT * FROM finance_records ORDER BY id");
            var oldRuntime=temporary.resolve("old/db/migration");Files.createDirectories(oldRuntime);
            try(var sqls=Files.list(Path.of("src/main/resources/db/migration"))){
                for(var sql:sqls.filter(p->p.getFileName().toString().matches("V[0-9]+__.*\\.sql")).toList()){
                    int version=Integer.parseInt(sql.getFileName().toString().split("__")[0].substring(1));
                    if(version<=154)Files.copy(sql,oldRuntime.resolve(sql.getFileName()));
                }
            }
            var old=Flyway.configure().dataSource(fixture.dataSource).locations("filesystem:"+oldRuntime).baselineVersion("154").load();
            old.baseline();assertTrue(old.validateWithResult().validationSuccessful);
            var prefix=fixture.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            fixture.db.execute("DROP INDEX idx_finance_page ON finance_records");
            var addition=Files.createDirectory(temporary.resolve("addition"));
            Files.copy(Path.of("src/main/resources/db/migration/V155__finance_query_projections.sql"),addition.resolve("V155__finance_query_projections.sql"));
            var classes=Files.createDirectory(temporary.resolve("helper-classes"));
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),"scripts/qa/java/FinanceQueryProjectionSchemaMaintenance.java"));
            String owner=UUID.randomUUID().toString().replace("-","");
            var launch=temporary.resolve("launch.json");Files.writeString(launch,"\""+owner+"\"");
            var values=Map.of("url",System.getenv("FINANCE_QA_JDBC"),"user",System.getenv("FINANCE_QA_USER"),"password",System.getenv("FINANCE_QA_PASSWORD"),"owner",owner);
            var libraries=Arrays.stream(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(File.pathSeparator))
                .filter(p->!Path.of(p).toAbsolutePath().normalize().equals(Path.of("target/classes").toAbsolutePath().normalize()))
                .filter(p->!Path.of(p).toAbsolutePath().normalize().equals(Path.of("target/test-classes").toAbsolutePath().normalize())).toList();
            String classpath=classes+File.pathSeparator+temporary.resolve("old")+File.pathSeparator+String.join(File.pathSeparator,libraries);
            assertEquals(0,helper("validate",addition,launch,owner,classpath,values));
            assertEquals(prefix,fixture.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
            assertEquals(0,helper("migrate",addition,launch,owner,classpath,values));
            assertEquals(before,fixture.db.queryForList("SELECT * FROM finance_records ORDER BY id"));
            var after=fixture.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            assertEquals(prefix,after.subList(0,prefix.size()));assertEquals(prefix.size()+1,after.size());
            assertEquals("155",after.getLast().get("version"));assertTrue(old.validateWithResult().validationSuccessful);
            assertEquals(11,fixture.db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_page'",Integer.class));
            assertEquals(3,fixture.db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'fin_qp_shopee_ad_daily_%'",Integer.class));
            assertNotEquals(0,helper("migrate",addition,launch,owner,classpath,values),"An already applied V155 cannot be replayed");
            assertEquals(after,fixture.db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
        }finally{fixture.closeDataSource();}
    }
    private int helper(String mode,Path addition,Path launch,String owner,String classpath,Map<String,String> values)throws Exception{
        var output=temporary.resolve("helper-"+mode+"-"+UUID.randomUUID()+".log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Dheyi.financeProjectionSchemaOwner="+owner,"-cp",classpath,"FinanceQueryProjectionSchemaMaintenance",mode,addition.toString(),launch.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try(var input=process.getOutputStream()){input.write(new ObjectMapper().writeValueAsBytes(values));}
        if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Disposable schema helper did not finish");}
        if(mode.equals("validate")||process.exitValue()==0)assertTrue(Files.readString(output).contains("\"success\":true"),"Fixed helper did not confirm the expected operation: "+Files.readAllLines(output).stream().filter(line->line.startsWith("{\"success\"")).reduce((a,b)->b).orElse("no JSON diagnostic"));
        return process.exitValue();
    }
}
