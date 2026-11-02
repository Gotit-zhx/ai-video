package com.yingdao.aicommerce.finance;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSyncErrorTest {
    @Test void storageFailureRetainsUsefulCodeWithoutExposingSql(){
        var failure=new org.springframework.jdbc.UncategorizedSQLException(
            "PreparedStatementCallback","INSERT INTO finance_records(secret) VALUES('private')",
            new SQLException("The table finance_records is full","HY000",1114));
        String message=FinanceSync.publicError(failure);
        assertTrue(message.contains("1114"));
        assertTrue(message.contains("空间不足"));
        assertTrue(message.contains("进度已保留"));
        assertFalse(message.contains("INSERT"));
        assertFalse(message.contains("private"));
    }
    @Test void databaseAndPlatformFailuresHaveDistinctActionableMessages(){
        assertTrue(FinanceSync.publicError(new SQLException("deadlock","40001",1213)).contains("数据库暂忙"));
        assertTrue(FinanceSync.publicError(new org.springframework.dao.DataAccessResourceFailureException("SELECT secret")).contains("数据库连接"));
        assertEquals("授权已过期",FinanceSync.publicError(new IllegalStateException("授权已过期")));
        assertFalse(FinanceSync.publicError(new IllegalStateException("token=private-value")).contains("private-value"));
    }
}
