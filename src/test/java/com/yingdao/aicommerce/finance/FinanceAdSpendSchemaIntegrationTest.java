package com.yingdao.aicommerce.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

/** Only a disposable schema. Real old-process restart is additionally required in production. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceAdSpendSchemaIntegrationTest {
    @TempDir Path root;
    @Test void exactV150AdditionPreservesOldHistoryAndDataAndRemainsValidForOldRuntime()throws Exception{
        String url=System.getenv("FINANCE_QA_JDBC"),user=System.getenv("FINANCE_QA_USER"),password=System.getenv("FINANCE_QA_PASSWORD");
        assertTrue(url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"));
        Path legacy=Files.createDirectory(root.resolve("legacy")),addition=Files.createDirectory(root.resolve("addition")),classes=Files.createDirectory(root.resolve("classes"));
        Files.writeString(legacy.resolve("V148__synthetic_baseline.sql"),"CREATE TABLE protected_ad_state(id INT PRIMARY KEY,token TEXT,checkpoint JSON); INSERT INTO protected_ad_state VALUES(1,'synthetic-encrypted-value','{\"page\":8,\"paused\":true}');");
        Files.copy(Path.of("src/main/resources/db/migration/V150__finance_ad_spend_workspace.sql"),addition.resolve("V150__finance_ad_spend_workspace.sql"));
        var old=Flyway.configure().dataSource(url,user,password).locations("filesystem:"+legacy).load();old.migrate();
        String before;
        try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement();var row=sql.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='148'")){assertTrue(row.next());before=row.getString(1);}
        Path source=Path.of("ops/heyi-ops/FinanceAdSpendSchemaMaintenance.java");assertTrue(Files.isRegularFile(source),"V150 needs a dedicated reviewed runner");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),source.toString()));
        try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())){
            var method=loader.loadClass("FinanceAdSpendSchemaMaintenance").getMethod("execute",String[].class,Map.class);
            var input=Map.<String,Object>of("url",url,"user",user,"password",password,"baseLocation","filesystem:"+legacy);
            for(String mode:List.of("validate","migrate","validate","migrate"))assertEquals(true,((Map<?,?>)method.invoke(null,new String[]{mode,addition.toString()},input)).get("oldRuntimeValidationPassed"));
            assertTrue(old.validateWithResult().validationSuccessful);
            try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement()){
                try(var row=sql.executeQuery("SELECT token,JSON_EXTRACT(checkpoint,'$.page'),JSON_EXTRACT(checkpoint,'$.paused') FROM protected_ad_state")){assertTrue(row.next());assertEquals("synthetic-encrypted-value",row.getString(1));assertEquals(8,row.getInt(2));assertEquals("true",row.getString(3));}
                try(var row=sql.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='148'")){assertTrue(row.next());assertEquals(before,row.getString(1));}
                try(var row=sql.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='150' AND success=1")){row.next();assertEquals(1,row.getInt(1));}
                try(var row=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('finance_ad_spend_imports','finance_ad_spend_allocation_sets','finance_ad_spend_allocations')")){row.next();assertEquals(3,row.getInt(1));}
            }
            Files.writeString(addition.resolve("V150__finance_ad_spend_workspace.sql"),"CREATE TABLE unreviewed(id INT);");
            var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,new String[]{"migrate",addition.toString()},input));assertInstanceOf(IllegalArgumentException.class,error.getCause());
            var unsafe=new HashMap<>(input);unsafe.put("url","jdbc:mysql://example.invalid/production?useSSL=true");
            var rejected=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,new String[]{"validate",addition.toString()},unsafe));assertInstanceOf(IllegalArgumentException.class,rejected.getCause());
        }
    }
}
