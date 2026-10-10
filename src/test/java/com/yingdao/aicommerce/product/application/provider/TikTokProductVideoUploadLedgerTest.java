package com.yingdao.aicommerce.product.application.provider;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TikTokProductVideoUploadLedgerTest {
    @Test void mediaCheckpointsUseIndependentTransactionsAndTenantScopedLookups() {
        assertThat(TikTokProductVideoUploadLedger.class.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), eq("hash-key"), eq("org"), eq("request-key"))).thenReturn(List.of(Map.of("status", "READY")));
        var ledger = new TikTokProductVideoUploadLedger(db);
        assertThat(ledger.prepare("hash-key", "org", "shop", "video", "content", "destination", 20, "request-key")).containsEntry("status", "READY");
        verify(db).update(contains("INSERT IGNORE INTO tiktok_product_video_uploads"), eq("hash-key"), eq("org"), eq("shop"), eq("video"), eq("content"), eq("destination"), eq(20L));
        verify(db).queryForList(contains("WHERE u.id=? AND u.organization_id=? AND a.request_key=?"), eq("hash-key"), eq("org"), eq("request-key"));
        verify(db).update(contains("INSERT IGNORE INTO tiktok_product_video_upload_attempts"), eq("hash-key"), eq("org"), eq("request-key"));
    }

    @Test void leaseAndAttemptsAreAtomicallyScopedToOwningWorker() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.update(anyString(), any(), any(), any())).thenReturn(1);
        when(db.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        var ledger = new TikTokProductVideoUploadLedger(db);
        assertThat(ledger.acquire("id", "org", "worker")).isTrue();
        assertThat(ledger.beginUpload("id", "org", "worker", "request-key")).isTrue();
        verify(db).update(contains("(locked_by IS NULL OR lease_until<NOW(6))"), eq("worker"), eq("id"), eq("org"));
        verify(db).update(contains("u.locked_by=? AND a.request_key=? AND u.video_id IS NULL AND a.upload_attempts<3"), eq("id"), eq("org"), eq("worker"), eq("request-key"));
        ledger.release("id", "org", "worker");
        verify(db).update(contains("WHERE id=? AND organization_id=? AND locked_by=?"), eq("id"), eq("org"), eq("worker"));
    }

    @Test void lossOfLeaseCannotBeReportedAsASavedSuccess() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var ledger = new TikTokProductVideoUploadLedger(db);
        assertThatThrownBy(() -> ledger.ready("id", "org", "worker", "video-id", "{}"))
                .isInstanceOf(VideoUploadPendingException.class).hasMessageContaining("另一任务接管");
        assertThatThrownBy(() -> ledger.failed("id", "org", "worker", "request-key", "rejected"))
                .isInstanceOf(VideoUploadPendingException.class);
    }
}
