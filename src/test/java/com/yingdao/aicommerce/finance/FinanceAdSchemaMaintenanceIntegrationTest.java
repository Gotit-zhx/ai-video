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

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceAdSchemaMaintenanceIntegrationTest {
    @TempDir Path root;
    @Test void isolatedUpgradePreservesExistingAccountsAndOldRuntimeValidation()throws Exception{
        String url=System.getenv("FINANCE_QA_JDBC"),user=System.getenv("FINANCE_QA_USER"),password=System.getenv("FINANCE_QA_PASSWORD");
        assertTrue(url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"));
        Path legacy=Files.createDirectory(root.resolve("legacy")),addition=Files.createDirectory(root.resolve("addition")),classes=Files.createDirectory(root.resolve("classes"));
        Files.writeString(legacy.resolve("V147__synthetic_baseline.sql"),"""
            CREATE TABLE finance_ad_accounts(organization_id CHAR(36),user_id CHAR(36),advertiser_id VARCHAR(64),
              access_token_encrypted TEXT,checkpoint_json JSON,PRIMARY KEY(organization_id,advertiser_id));
            """);
        Files.copy(Path.of("src/main/resources/db/migration/V148__finance_ad_automation.sql"),addition.resolve("V148__finance_ad_automation.sql"));
        var oldRuntime=Flyway.configure().dataSource(url,user,password).locations("filesystem:"+legacy).load();oldRuntime.migrate();
        try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement()){
            sql.executeUpdate("INSERT INTO finance_ad_accounts VALUES('org','owner','100001','unit-only-encrypted-value','{\"page\":8}')");
        }
        int compiled=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),"ops/heyi-ops/FinanceAdSchemaMaintenance.java");
        assertEquals(0,compiled);
        try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())){
            var method=loader.loadClass("FinanceAdSchemaMaintenance").getMethod("execute",String[].class,Map.class);
            var values=Map.<String,Object>of("url",url,"user",user,"password",password,"baseLocation","filesystem:"+legacy);
            for(String mode:List.of("validate","migrate","validate","migrate")){
                var result=(Map<?,?>)method.invoke(null,new String[]{mode,addition.toString()},values);
                assertEquals(true,result.get("success"));assertEquals(true,result.get("oldRuntimeValidationPassed"));
            }
            assertTrue(oldRuntime.validateWithResult().validationSuccessful,"V147 runtime accepts the verified future V148 addition");
            try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement()){
                try(var row=sql.executeQuery("SELECT user_id,access_token_encrypted,JSON_EXTRACT(checkpoint_json,'$.page'),discovery_status FROM finance_ad_accounts")){
                    assertTrue(row.next());assertEquals("owner",row.getString(1));assertEquals("unit-only-encrypted-value",row.getString(2));assertEquals(8,row.getInt(3));assertEquals("PENDING",row.getString(4));assertFalse(row.next());
                }
                try(var rows=sql.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='148' AND success=1")){rows.next();assertEquals(1,rows.getInt(1));}
                try(var rows=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('finance_ad_stores','finance_ad_streams','finance_ad_report_stage','finance_ad_coverage')")){rows.next();assertEquals(4,rows.getInt(1));}
            }
            var invalid=new HashMap<>(values);invalid.put("url","jdbc:mysql://example.invalid/production?useSSL=true");
            var rejected=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,new String[]{"validate",addition.toString()},invalid));
            assertInstanceOf(IllegalArgumentException.class,rejected.getCause());
        }
    }
}
