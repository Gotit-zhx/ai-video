package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class ShopeeProductVideoUploaderTest {
    @TempDir Path storage;
    JdbcTemplate db;
    ShopeeProductVideoUploadLedger ledger;
    ShopeeOfficialClient client;
    ShopeeProductVideoUploader uploader;
    ProductCapture capture;
    MarketplaceAccess.Connection connection;
    Map<String, Object> row;
    List<Map<String, Object>> sources;
    String video;
    byte[] bytes;

    @BeforeEach void setup() throws Exception {
        db = mock(JdbcTemplate.class); ledger = mock(ShopeeProductVideoUploadLedger.class);
        client = mock(ShopeeOfficialClient.class);
        uploader = new ShopeeProductVideoUploader(db, ledger, client, new ObjectMapper(), storage.toString());
        capture = new ProductCapture("org-1", "user-1", "key", "fingerprint", "SHOPEE", "https://example.invalid/product", "source-shop", "1",
                "Product", "Description", "Category", null, "MYR", new BigDecimal("20"), new BigDecimal("20"), "{}", "{}");
        connection = connection("org-1");
        video = UUID.randomUUID().toString(); bytes = new byte[ShopeeProductVideoUploader.PART_BYTES + 17];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        Files.createDirectories(storage.resolve("capture-videos")); Files.write(storage.resolve("capture-videos").resolve(video), bytes);
        sources = new ArrayList<>();
        sources.add(new LinkedHashMap<>(Map.of("id", video, "mime_type", "video/mp4", "byte_length", (long) bytes.length,
                "content_sha256", ShopeeProductVideoUploader.digest("SHA-256", bytes))));
        when(db.queryForList(contains("FROM product_capture_videos"), eq("org-1"), eq(capture.getId()))).thenReturn(sources);
        when(client.videoUploadScope()).thenReturn("https://partner.shopeemobile.com:123");
        row = new LinkedHashMap<>(Map.of("status", "PREPARED", "next_part", 0));
        when(ledger.prepare(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong())).thenAnswer(ignored -> row);
        when(ledger.get(anyString(), eq("org-1"))).thenAnswer(ignored -> row);
        when(ledger.acquire(anyString(), eq("org-1"), anyString())).thenReturn(true);
        when(ledger.beginInitialization(anyString(), eq("org-1"), anyString())).thenReturn(true);
        doAnswer(call -> { row.put("status", call.getArgument(3)); return null; }).when(ledger).phase(anyString(), anyString(), anyString(), anyString());
        doAnswer(call -> { row.put("status", "UPLOADING"); row.put("video_upload_id", call.getArgument(3)); row.put("next_part", 0); return null; })
                .when(ledger).initialized(anyString(), anyString(), anyString(), anyString());
        doAnswer(call -> { row.put("next_part", ((Integer) call.getArgument(3)) + 1); return null; })
                .when(ledger).partUploaded(anyString(), anyString(), anyString(), anyInt());
        doAnswer(call -> { row.put("status", call.getArgument(3)); row.put("message", call.getArgument(5)); return null; })
                .when(ledger).result(anyString(), anyString(), anyString(), anyString(), anyString(), nullable(String.class));
        when(client.shopPost(eq(ShopeeProductVideoUploader.INIT), eq(connection), anyMap()))
                .thenReturn(Map.of("response", Map.of("video_upload_id", "sg-upload-123")));
        when(client.shopGet(eq(ShopeeProductVideoUploader.RESULT), eq(connection), anyMap())).thenReturn(result("SUCCEEDED"));
    }

    @Test void noCapturedVideoMakesNoMediaRequest() {
        sources.clear();
        assertThat(uploader.upload(capture, connection, ignored -> {}).present()).isFalse();
        verifyNoInteractions(client, ledger);
        verify(db).queryForList(contains("organization_id=? AND capture_id=? AND superseded_by IS NULL"), eq("org-1"), eq(capture.getId()));
    }

    @Test void uploadsDeterministicFirstVideoWithCorrectFourMiBPartsAndChecksums() {
        sources.add(Map.of("id", UUID.randomUUID().toString()));
        var uploaded = uploader.upload(capture, connection, ignored -> {});
        assertThat(uploaded).isEqualTo(new ShopeeProductVideoUploader.UploadedVideo("sg-upload-123", video, 2));
        verify(client).shopPost(ShopeeProductVideoUploader.INIT, connection,
                Map.of("file_md5", ShopeeProductVideoUploader.digest("MD5", bytes), "file_size", bytes.length));
        byte[] first = Arrays.copyOfRange(bytes, 0, ShopeeProductVideoUploader.PART_BYTES);
        byte[] last = Arrays.copyOfRange(bytes, ShopeeProductVideoUploader.PART_BYTES, bytes.length);
        verify(client).uploadVideoPart(eq("sg-upload-123"), eq(0), eq(ShopeeProductVideoUploader.digest("MD5", first)), aryEq(first));
        verify(client).uploadVideoPart(eq("sg-upload-123"), eq(1), eq(ShopeeProductVideoUploader.digest("MD5", last)), aryEq(last));
        verify(client).completeVideoUpload(eq("sg-upload-123"), eq(List.of(0, 1)), anyLong());
        assertThat(row.get("status")).isEqualTo("READY");
    }

    @Test void readyMediaIsReusedWithoutReadingArchiveOrCallingUpstream() throws Exception {
        row.put("status", "READY"); row.put("video_upload_id", "existing-upload");
        Files.delete(storage.resolve("capture-videos").resolve(video));
        assertThat(uploader.upload(capture, connection, ignored -> {}).uploadId()).isEqualTo("existing-upload");
        verify(client, never()).shopPost(anyString(), any(), anyMap());
        verify(client, never()).uploadVideoPart(anyString(), anyInt(), anyString(), any());
        verify(client, never()).shopGet(anyString(), any(), anyMap());
        verify(ledger, never()).acquire(anyString(), anyString(), anyString());
    }

    @Test void interruptedPartUploadResumesOnlyUnconfirmedSequence() {
        row.put("status", "UPLOADING"); row.put("video_upload_id", "existing-upload"); row.put("next_part", 1);
        uploader.upload(capture, connection, ignored -> {});
        verify(client, never()).shopPost(anyString(), any(), anyMap());
        verify(client, times(1)).uploadVideoPart(eq("existing-upload"), eq(1), anyString(), any());
        verify(client, never()).uploadVideoPart(anyString(), eq(0), anyString(), any());
    }

    @Test void lostCompleteResponseIsRecoveredByReadbackWithoutReuploadOrSecondComplete() {
        doThrow(new IllegalStateException("response lost")).when(client).completeVideoUpload(anyString(), anyList(), anyLong());
        assertThatThrownBy(() -> uploader.upload(capture, connection, ignored -> {})).hasMessage("response lost");
        assertThat(row.get("status")).isEqualTo("COMPLETING");
        assertThat(uploader.upload(capture, connection, ignored -> {}).uploadId()).isEqualTo("sg-upload-123");
        verify(client, times(1)).shopPost(anyString(), any(), anyMap());
        verify(client, times(2)).uploadVideoPart(anyString(), anyInt(), anyString(), any());
        verify(client, times(1)).completeVideoUpload(anyString(), anyList(), anyLong());
    }

    @Test void boundedTranscodingWaitPreservesReusableUploadForNextAttempt() {
        row.put("status", "TRANSCODING"); row.put("video_upload_id", "existing-upload");
        when(client.shopGet(eq(ShopeeProductVideoUploader.RESULT), eq(connection), anyMap())).thenReturn(result("TRANSCODING"));
        List<Long> sleeps = new ArrayList<>();
        assertThatThrownBy(() -> uploader.upload(capture, connection, sleeps::add)).hasMessageContaining("不重复上传视频");
        assertThat(sleeps).hasSize(ShopeeProductVideoUploader.POLL_ATTEMPTS - 1);
        assertThat(row.get("status")).isEqualTo("TRANSCODING");
        when(client.shopGet(eq(ShopeeProductVideoUploader.RESULT), eq(connection), anyMap())).thenReturn(result("SUCCEEDED"));
        assertThat(uploader.upload(capture, connection, ignored -> {}).present()).isTrue();
        verify(client, never()).uploadVideoPart(anyString(), anyInt(), anyString(), any());
        verify(client, never()).completeVideoUpload(anyString(), anyList(), anyLong());
    }

    @Test void platformVideoFailureIsPersistedAndNeverSilentlySkipped() {
        when(client.shopGet(eq(ShopeeProductVideoUploader.RESULT), eq(connection), anyMap()))
                .thenReturn(Map.of("response", Map.of("status", "FAILED", "message", "Video is too short")));
        assertThatThrownBy(() -> uploader.upload(capture, connection, ignored -> {})).hasMessageContaining("Video is too short");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThatThrownBy(() -> uploader.upload(capture, connection, ignored -> {})).hasMessageContaining("Video is too short");
        verify(client, times(1)).shopPost(anyString(), any(), anyMap());
    }

    @Test void organizationMismatchIsRejectedBeforeReadingOrUploadingVideo() {
        assertThatThrownBy(() -> uploader.upload(capture, connection("other-org"), ignored -> {})).hasMessageContaining("同一企业");
        verifyNoInteractions(db, client, ledger);
    }

    @Test void tamperedArchiveAndOversizedVideoNeverReachMediaUpload() throws Exception {
        Files.write(storage.resolve("capture-videos").resolve(video), new byte[bytes.length]);
        assertThatThrownBy(() -> uploader.upload(capture, connection, ignored -> {})).hasMessageContaining("归档校验失败");
        verify(client, never()).shopPost(anyString(), any(), anyMap());
        sources.getFirst().put("byte_length", ShopeeProductVideoUploader.MAX_VIDEO_BYTES + 1);
        assertThatThrownBy(() -> uploader.upload(capture, connection, ignored -> {})).hasMessageContaining("30 MB");
        verify(client, never()).uploadVideoPart(anyString(), anyInt(), anyString(), any());
    }

    @Test void competingUploadLeasePreventsDuplicateMediaCalls() {
        when(ledger.acquire(anyString(), eq("org-1"), anyString())).thenReturn(false);
        assertThatThrownBy(() -> uploader.upload(capture, connection, ignored -> {})).hasMessageContaining("正在上传");
        verify(client, never()).shopPost(anyString(), any(), anyMap());
    }

    @Test void transientPartFailureDefersProductWorkflowAndRetainsUploadSession() {
        doThrow(new com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException("店铺官方接口请求失败", new java.io.IOException("socket closed")))
                .when(client).uploadVideoPart(anyString(), anyInt(), anyString(), any());
        assertThatThrownBy(() -> uploader.upload(capture, connection, ignored -> {})).isInstanceOf(VideoUploadPendingException.class);
        assertThat(row).containsEntry("status", "UPLOADING").containsEntry("video_upload_id", "sg-upload-123").containsEntry("next_part", 0);
        verify(client, never()).completeVideoUpload(anyString(), anyList(), anyLong());
    }

    @Test void exhaustedUnknownInitializationDoesNotCreateAnUnboundedSeriesOfMediaSessions() {
        row.put("status", "INITIATING");
        when(ledger.beginInitialization(anyString(), eq("org-1"), anyString())).thenReturn(false);
        assertThatThrownBy(() -> uploader.upload(capture, connection, ignored -> {})).hasMessageContaining("已尝试 3 次");
        verify(client, never()).shopPost(anyString(), any(), anyMap());
        verify(client, never()).uploadVideoPart(anyString(), anyInt(), anyString(), any());
    }

    @Test void reuseKeyIsSeparatedByShopAndApiEnvironment() {
        row.put("status", "READY"); row.put("video_upload_id", "ready");
        uploader.upload(capture, connection, ignored -> {});
        uploader.upload(capture, connection("org-1"), ignored -> {});
        when(client.videoUploadScope()).thenReturn("https://openplatform.sandbox.test-stable.shopee.sg:123");
        uploader.upload(capture, connection, ignored -> {});
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(3)).prepare(ids.capture(), eq("org-1"), anyString(), eq(video), anyString(), anyString(), anyLong());
        assertThat(ids.getAllValues()).doesNotHaveDuplicates();
    }

    private Map<String, Object> result(String status) { return Map.of("response", Map.of("status", status)); }
    private MarketplaceAccess.Connection connection(String org) {
        return new MarketplaceAccess.Connection(new Shop(org, MarketplacePlatform.SHOPEE, "9988", null, null, "SELLER", "Shop", "MY", "[]"),
                new MarketplaceAccess.TokenSet("access", "refresh", Instant.now().plusSeconds(3600), null, List.of()));
    }
}
