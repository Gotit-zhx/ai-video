package com.yingdao.aicommerce.product.mapper;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real MySQL update predicates, including the exact lease-expiry boundary. */
@EnabledIfEnvironmentVariable(named = "HEYI_EXECUTION_TEST_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_execution_test.*")
class CommerceBatchExecutionMapperLeaseTest {
    SqlSession session;
    CommerceBatchExecutionMapper mapper;
    final Instant now = Instant.parse("2026-09-14T00:00:00Z");
    final String id = UUID.randomUUID().toString();

    @BeforeEach void database() throws Exception {
        var source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", System.getenv("HEYI_EXECUTION_TEST_URL"), "root", "");
        var configuration = new Configuration(new Environment("lease-test", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CommerceBatchExecutionMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(CommerceBatchExecutionMapper.class);
        try (var statement = session.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS commerce_batch_executions (
                      id VARCHAR(64) PRIMARY KEY, organization_id VARCHAR(64), batch_id VARCHAR(64),
                      created_by VARCHAR(64), stage VARCHAR(32), status VARCHAR(32), message VARCHAR(1000),
                      lease_token VARCHAR(64), lease_until TIMESTAMP(6) NULL,
                      created_at TIMESTAMP(6), updated_at TIMESTAMP(6))
                    """);
        }
        mapper.insert(new CommerceBatchExecution(id, "org", "batch-" + id, "user", "EXTRACT", "QUEUED", "queued",
                null, null, now, now));
        assertEquals(1, mapper.claim(id, "owner", now, now.plusSeconds(300)));
    }

    @AfterEach void cleanup() throws Exception {
        if (session == null) return;
        try (var statement = session.getConnection().prepareStatement("DELETE FROM commerce_batch_executions WHERE id=?")) {
            statement.setString(1, id); statement.executeUpdate();
        } finally { session.close(); }
    }

    @Test void renewsLiveOwnerAcrossMoreThanFiveMinutesWithoutChangingStageProgressTime() {
        for (int seconds = 30; seconds <= 420; seconds += 30) {
            assertEquals(1, mapper.renew(id, "owner", now.plusSeconds(seconds), now.plusSeconds(seconds + 300)));
        }
        assertEquals(now.plusSeconds(720), mapper.byId(id).leaseUntil());
        assertEquals(now, mapper.byId(id).updatedAt());
        assertEquals(1, mapper.finish(id, "owner", "VARIATE", "QUEUED", "saved", now.plusSeconds(420)));
        assertEquals("QUEUED", mapper.byId(id).status());
        assertNull(mapper.byId(id).leaseToken());
        assertEquals(0, mapper.renew(id, "owner", now.plusSeconds(421), now.plusSeconds(721)));
    }

    @Test void exactExpiryCannotRenewOrFinishAndExpirySweepMarksItUnknown() {
        assertEquals(0, mapper.renew(id, "owner", now.plusSeconds(300), now.plusSeconds(600)));
        assertEquals(0, mapper.finish(id, "owner", "VARIATE", "QUEUED", "late", now.plusSeconds(300)));
        assertEquals(1, mapper.expire(now.plusSeconds(300)));
        assertEquals("UNKNOWN", mapper.byId(id).status());
        assertEquals(0, mapper.renew(id, "owner", now.plusSeconds(301), now.plusSeconds(601)));
        assertNull(mapper.byId(id).leaseToken());
    }

    @Test void anotherTokenCannotRenewDrainOrFinishAndCannotOverwriteOwner() {
        assertEquals(0, mapper.renew(id, "old-owner", now.plusSeconds(30), now.plusSeconds(600)));
        assertEquals(0, mapper.drain(id, "old-owner", now.plusSeconds(30)));
        assertEquals(0, mapper.finish(id, "old-owner", "VARIATE", "QUEUED", "old", now.plusSeconds(30)));
        assertEquals("owner", mapper.byId(id).leaseToken());
        assertEquals(now.plusSeconds(300), mapper.byId(id).leaseUntil());
        assertEquals("RUNNING", mapper.byId(id).status());
    }

    @Test void pausingCanRenewCurrentCallButCompletionCannotQueueAnotherCall() {
        assertEquals(1, mapper.drain(id, "owner", now.plusSeconds(30)));
        assertEquals("PAUSING", mapper.byId(id).status());
        assertEquals(1, mapper.renew(id, "owner", now.plusSeconds(60), now.plusSeconds(360)));
        assertEquals(1, mapper.finish(id, "owner", "VARIATE", "QUEUED", "saved", now.plusSeconds(61)));
        assertEquals("PAUSED", mapper.byId(id).status());
        assertEquals("VARIATE", mapper.byId(id).stage());
        assertTrue(mapper.byId(id).message().contains("暂停后续调用"));
        assertEquals(0, mapper.renew(id, "owner", now.plusSeconds(62), now.plusSeconds(362)));
    }

    @Test void renewalNeverShortensALiveLeaseAndExpiredShutdownCannotReviveIt() {
        assertEquals(1, mapper.renew(id, "owner", now.plusSeconds(30), now.plusSeconds(200)));
        assertEquals(now.plusSeconds(300), mapper.byId(id).leaseUntil());
        assertEquals(0, mapper.drain(id, "owner", now.plusSeconds(300)));
        assertEquals("RUNNING", mapper.byId(id).status());
        assertEquals(now.plusSeconds(300), mapper.byId(id).leaseUntil());
    }
}
