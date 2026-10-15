package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class TikTokProductVideoUploaderTest {
    @TempDir Path storage;
    JdbcTemplate db;
    TikTokProductVideoUploadLedger ledger;
    TikTokShopOfficialClient client;
    TikTokProductVideoUploader uploader;
    ProductCapture capture;
    MarketplaceAccess.Connection connection;
    List<Map<String, Object>> sources;
    Map<String, Object> row;
    byte[] bytes;
    String video;

    @BeforeEach void setup() throws Exception {
        db = mock(JdbcTemplate.class);
        ledger = mock(TikTokProductVideoUploadLedger.class);
        client = mock(TikTokShopOfficialClient.class);
        uploader = uploader("https://open-api.tiktokglobalshop.com", "app-test");
        capture = new ProductCapture("org-1", "user-1", "capture", "fingerprint", "SHOPEE",
                "https://source.example/product", "source-shop", "product", "Product", "Description", "Category",
                null, "MYR", BigDecimal.TEN, BigDecimal.TEN, "{}", "{}");
        connection = connection("org-1", "target-shop", MarketplacePlatform.TIKTOK_SHOP);
        video = UUID.randomUUID().toString();
        bytes = new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0,'i','s','o','m',0,0,0,0};
        Files.createDirectories(storage.resolve("capture-videos"));
        Files.write(storage.resolve("capture-videos").resolve(video), bytes);
        sources = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("id", video, "mime_type", "video/mp4",
                "byte_length", (long) bytes.length, "content_sha256", TikTokProductVideoUploader.digest(bytes)))));
        when(db.queryForList(contains("FROM product_capture_videos"), eq("org-1"), eq(capture.getId()))).thenReturn(sources);
        row = new LinkedHashMap<>(Map.of("status", "PREPARED", "upload_attempts", 0));
        when(ledger.prepare(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString())).thenAnswer(ignored -> row);
        when(ledger.get(anyString(), anyString(), anyString())).thenAnswer(ignored -> row);
        when(ledger.acquire(anyString(), anyString(), anyString())).thenReturn(true);
        when(ledger.beginUpload(anyString(), anyString(), anyString(), anyString())).thenAnswer(ignored -> {
            int attempts = (Integer) row.get("upload_attempts");
            if (attempts >= 3) return false;
            row.put("upload_attempts", attempts + 1);
            row.put("attempt_status", "UPLOADING");
            return true;
        });
        doAnswer(call -> { row.put("status", "READY"); row.put("video_id", call.getArgument(3)); return null; })
                .when(ledger).ready(anyString(), anyString(), anyString(), anyString(), anyString());
        doAnswer(call -> { row.put("attempt_status", "FAILED"); row.put("attempt_message", call.getArgument(4)); return null; })
                .when(ledger).failed(anyString(), anyString(), anyString(), anyString(), anyString());
        when(client.apiMultipart(eq(TikTokProductVideoUploader.UPLOAD), eq(connection), eq(Map.of()),
                anyString(), anyString(), any(byte[].class), anyMap())).thenReturn(Map.of(
                        "code", 0, "data", Map.of("id", "v09-test-video", "format", "MP4"), "request_id", "req-video"));
    }

    @Test void noArchiveMeansNoVideoUploadOrLedgerWrite() {
        sources.clear();
        assertThat(uploader.upload(capture, connection, "request-a").present()).isFalse();
        verifyNoInteractions(ledger, client);
        verify(db).queryForList(contains("organization_id=? AND capture_id=? AND superseded_by IS NULL"), eq("org-1"), eq(capture.getId()));
    }

    @Test void uploadsLocalBinaryWithOfficialDataAndNameContractAndStableFirstVideo() {
        sources.add(Map.of("id", UUID.randomUUID().toString()));
        String filename = "video" + TikTokProductVideoUploader.digest(bytes).substring(0, 24) + ".mp4";
        assertThat(uploader.upload(capture, connection, "request-a")).isEqualTo(new TikTokProductVideoUploader.UploadedVideo("v09-test-video", video, 2));
        verify(client).apiMultipart(TikTokProductVideoUploader.UPLOAD, connection, Map.of(), filename,
                "video/mp4", bytes, Map.of("name", filename));
        assertThat(filename).matches("[A-Za-z0-9]+\\.mp4");
        assertThat(row.get("status")).isEqualTo("READY");
        verify(client, never()).apiPost(anyString(), any(), anyMap(), anyMap());
        verify(ledger).release(anyString(), eq("org-1"), anyString());
    }

    @Test void webmArchiveUsesWebmNameAndMime() {
        sources.getFirst().put("mime_type", "video/webm");
        when(client.apiMultipart(anyString(), eq(connection), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(Map.of("data", Map.of("id", "webm-video", "format", "WEBM")));
        assertThat(uploader.upload(capture, connection, "request-a").videoId()).isEqualTo("webm-video");
        verify(client).apiMultipart(eq(TikTokProductVideoUploader.UPLOAD), eq(connection), eq(Map.of()),
                endsWith(".webm"), eq("video/webm"), aryEq(bytes), anyMap());
    }

    @Test void privateVideoUsesFrozenFileKeyButKeepsItsOwnUploadIdentity() {
        String copy=UUID.randomUUID().toString();
        sources.getFirst().put("id",copy);sources.getFirst().put("storage_video_id",video);
        assertThat(uploader.upload(capture,connection,"private-copy").sourceVideoId()).isEqualTo(copy);
        verify(client).apiMultipart(anyString(),eq(connection),anyMap(),anyString(),eq("video/mp4"),aryEq(bytes),anyMap());
        verify(ledger).prepare(anyString(),eq("org-1"),eq(connection.shop().getId()),eq(copy),anyString(),anyString(),anyLong(),anyString());
    }

    @Test void readyCheckpointIsReusedAcrossRetriesWithoutLocalFileOrAnotherUpload() throws Exception {
        uploader.upload(capture, connection, "request-a");
        Files.delete(storage.resolve("capture-videos").resolve(video));
        assertThat(uploader.upload(capture, connection, "request-b").videoId()).isEqualTo("v09-test-video");
        verify(client, times(1)).apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap());
    }

    @Test void mismatchedTenantAndWrongPlatformStopBeforeAnyDataRead() {
        assertThatThrownBy(() -> uploader.upload(capture, connection("org-2", "shop", MarketplacePlatform.TIKTOK_SHOP), "request-a")).hasMessageContaining("同一企业");
        assertThatThrownBy(() -> uploader.upload(capture, connection("org-1", "shop", MarketplacePlatform.SHOPEE), "request-a")).hasMessageContaining("平台");
        verifyNoInteractions(db, ledger, client);
    }

    @Test void checksumAndSymlinkProtectionPreventUntrustedArchiveReads() throws Exception {
        Files.write(storage.resolve("capture-videos").resolve(video), new byte[bytes.length]);
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("校验失败");
        Files.delete(storage.resolve("capture-videos").resolve(video));
        Path outside = Files.write(storage.resolve("outside.mp4"), bytes);
        Files.createSymbolicLink(storage.resolve("capture-videos").resolve(video), outside);
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("归档文件缺失");
        verifyNoInteractions(client);
    }

    @Test void oversizedAndUnsupportedSourcesAreRejectedBeforeReadingBytes() {
        sources.getFirst().put("byte_length", TikTokProductVideoUploader.MAX_VIDEO_BYTES + 1);
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("100 MB");
        sources.getFirst().put("byte_length", (long) bytes.length);
        sources.getFirst().put("mime_type", "application/pdf");
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("格式不受支持");
        verifyNoInteractions(client, ledger);
    }

    @Test void anotherWorkerKeepsMediaPendingWithoutCallingUpstream() {
        when(ledger.acquire(anyString(), anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).isInstanceOf(VideoUploadPendingException.class).hasMessageContaining("正在上传");
        verifyNoInteractions(client);
    }

    @Test void lostResponsesRetryOnlyMediaAndStopAfterThreePersistedAttempts() {
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new MarketplaceApiException("完整响应超时", new java.net.http.HttpTimeoutException("timeout")));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).isInstanceOf(VideoUploadPendingException.class).hasMessageContaining("商品尚未创建");
        }
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("已尝试 3 次");
        verify(client, times(3)).apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap());
        verify(ledger, never()).ready(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(client, never()).apiPost(anyString(), any(), anyMap(), anyMap());
    }

    @Test void missingVideoIdNeverCreatesReadyCheckpoint() {
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(Map.of("data", Map.of("url", "https://example.test/not-an-id")));
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).isInstanceOf(VideoUploadPendingException.class).hasMessageContaining("视频编号");
        verify(ledger, never()).ready(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void platformRejectionIsPersistedWithAReadableReasonAndNeverSilentlyDropsVideo() {
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new MarketplaceApiException("TikTok Shop 官方接口失败（错误码 12019122）：invalid ratio access_token=secret"));
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("9:16").hasMessageNotContaining("secret");
        assertThat(row.get("attempt_status")).isEqualTo("FAILED");
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("9:16");
        verify(client, times(1)).apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap());
    }

    @Test void nonVideoResponseCannotBeReportedAsSuccessfulVideo() {
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(Map.of("data", Map.of("id", "file-id", "format", "PDF")));
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("不是已提交的视频格式");
        assertThat(row.get("attempt_status")).isEqualTo("FAILED");
    }

    @Test void checkpointsAreScopedToAppAndTargetShopInsteadOfOnlyVideoHash() {
        row.put("status", "READY"); row.put("video_id", "cached-video");
        uploader.upload(capture, connection, "request-a");
        uploader.upload(capture, connection("org-1", "another-shop", MarketplacePlatform.TIKTOK_SHOP), "request-a");
        uploader("https://open-api.tiktokglobalshop.com", "other-app").upload(capture, connection, "request-a");
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(3)).prepare(keys.capture(), eq("org-1"), anyString(), eq(video), anyString(), anyString(), anyLong(), anyString());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test void databaseFailureRemainsPendingInsteadOfLosingTheVideo() {
        when(ledger.prepare(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).isInstanceOf(VideoUploadPendingException.class).hasMessageContaining("无法保存");
        verifyNoInteractions(client);
    }

    @Test void newManualBatchGetsItsOwnBudgetAfterOriginalRequestExhaustion() {
        Map<String, Map<String, Object>> budgets = scopedAttemptLedger();
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new MarketplaceApiException("HTTP 503"))
                .thenThrow(new MarketplaceApiException("HTTP 503"))
                .thenThrow(new MarketplaceApiException("HTTP 503"))
                .thenReturn(Map.of("data", Map.of("id", "recovered-video")));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> uploader.upload(capture, connection, "old-workflow"))
                    .isInstanceOf(VideoUploadPendingException.class);
        }
        assertThatThrownBy(() -> uploader.upload(capture, connection, "old-workflow")).hasMessageContaining("已尝试 3 次");
        assertThat(uploader.upload(capture, connection, "new-manual-batch").videoId()).isEqualTo("recovered-video");
        assertThat(budgets.values().stream().map(value -> value.get("upload_attempts")).toList()).containsExactlyInAnyOrder(3, 1);
        verify(client, times(4)).apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap());
    }

    @Test void alternatingWorkflowPollsCannotResetEachOthersExhaustedBudget() {
        Map<String, Map<String, Object>> budgets = scopedAttemptLedger();
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new MarketplaceApiException("HTTP 503"));
        for (int attempt = 0; attempt < 3; attempt++) {
            for (String key : List.of("workflow-a", "workflow-b")) {
                assertThatThrownBy(() -> uploader.upload(capture, connection, key)).isInstanceOf(VideoUploadPendingException.class);
            }
        }
        for (String key : List.of("workflow-a", "workflow-b", "workflow-a", "workflow-b")) {
            assertThatThrownBy(() -> uploader.upload(capture, connection, key)).hasMessageContaining("已尝试 3 次");
        }
        assertThat(budgets.values().stream().map(value -> value.get("upload_attempts")).toList()).containsOnly(3);
        verify(client, times(6)).apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap());
    }

    @Test void permanentRejectionBlocksThatRequestButANewManualBatchCanTryAgain() {
        scopedAttemptLedger();
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new MarketplaceApiException("错误码 12019122"))
                .thenReturn(Map.of("data", Map.of("id", "new-request-video")));
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("9:16");
        assertThatThrownBy(() -> uploader.upload(capture, connection, "request-a")).hasMessageContaining("9:16");
        assertThat(uploader.upload(capture, connection, "request-b").videoId()).isEqualTo("new-request-video");
        verify(client, times(2)).apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap());
    }

    @Test void aVideoRequiresStableRequestIdentityBeforePreparingAnUpload() {
        assertThatThrownBy(() -> uploader.upload(capture, connection, null)).hasMessageContaining("固定请求编号");
        verifyNoInteractions(ledger, client);
    }

    private Map<String, Map<String, Object>> scopedAttemptLedger() {
        Map<String, Map<String, Object>> budgets = new LinkedHashMap<>();
        java.util.function.Function<String, Map<String, Object>> snapshot = requestKey -> {
            Map<String, Object> budget = budgets.computeIfAbsent(requestKey, ignored -> new LinkedHashMap<>(Map.of(
                    "attempt_status", "PREPARED", "upload_attempts", 0)));
            Map<String, Object> result = new LinkedHashMap<>(row);
            result.putAll(budget);
            return result;
        };
        when(ledger.prepare(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(call -> snapshot.apply(call.getArgument(7)));
        when(ledger.get(anyString(), anyString(), anyString())).thenAnswer(call -> snapshot.apply(call.getArgument(2)));
        when(ledger.beginUpload(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            Map<String, Object> budget = budgets.get(call.getArgument(3));
            int count = (Integer) budget.get("upload_attempts");
            if (count >= 3 || "FAILED".equals(budget.get("attempt_status"))) return false;
            budget.put("upload_attempts", count + 1); budget.put("attempt_status", "UPLOADING");
            return true;
        });
        doAnswer(call -> {
            Map<String, Object> budget = budgets.get(call.getArgument(3));
            budget.put("attempt_status", "FAILED"); budget.put("attempt_message", call.getArgument(4));
            return null;
        }).when(ledger).failed(anyString(), anyString(), anyString(), anyString(), anyString());
        return budgets;
    }

    private TikTokProductVideoUploader uploader(String baseUrl, String appKey) {
        return new TikTokProductVideoUploader(db, ledger, client, new ObjectMapper(), storage.toString(), baseUrl, appKey);
    }

    private MarketplaceAccess.Connection connection(String org, String externalId, MarketplacePlatform platform) {
        Shop shop = new Shop(org, platform, externalId, "seller", "cipher", "LOCAL", "Test", "MY", "[]");
        return new MarketplaceAccess.Connection(shop, new MarketplaceAccess.TokenSet("access-token", "refresh-token",
                Instant.now().plusSeconds(3600), null, List.of("seller.product.basic")));
    }
}
