package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** A missing V155 dependency must fail before additive tables or triggers are created. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceShopeeGmsDependencyIntegrationTest {
    @Test void missingProjectionDependencyCannotPartiallyApplyV158()throws Exception{
        String url=System.getenv("FINANCE_QA_JDBC");assertTrue(url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"));
        try(var db=DriverManager.getConnection(url,System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD"));var sql=db.createStatement()){
            String migration=Files.readString(Path.of("src/main/resources/db/migration/V158__finance_shopee_gms_reference.sql")).replaceAll("(?m)^--.*$","");
            assertThrows(SQLException.class,()->{for(String statement:migration.split(";"))if(!statement.isBlank())sql.execute(statement);});
            try(var row=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'finance_shopee_gms_%'")){assertTrue(row.next());assertEquals(0,row.getInt(1));}
        }
    }
}
