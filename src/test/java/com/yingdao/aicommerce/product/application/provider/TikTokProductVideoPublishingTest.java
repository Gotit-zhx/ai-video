package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TikTokProductVideoPublishingTest {
    TikTokShopOfficialClient client;
    TikTokProductVideoUploader videos;
    TikTokShopOfficialPublishingProvider provider;
    ProductCapture capture;
    MarketplaceAccess.Connection connection;
    ProductPublishingProvider.ExternalCreateBoundary boundary;
    AtomicReference<Map<String, Object>> created;

    @BeforeEach void setup() {
        var authorization = mock(ShopAuthorizationService.class);
        client = mock(TikTokShopOfficialClient.class);
        videos = mock(TikTokProductVideoUploader.class);
        var images = mock(ProductCaptureImageMapper.class);
        var skus = mock(ProductCaptureSkuMapper.class);
        var downloader = mock(MarketplaceImageDownloader.class);
        provider = new TikTokShopOfficialPublishingProvider(authorization, client, skus, images, downloader,
                new ObjectMapper(), videos, new TikTokProductDescription(client, downloader));
        capture = new ProductCapture("org", "user", "key", "fingerprint", "SHOPEE", "https://example.test/item", "source", "123",
                "Product", "Product description", "Category", null, "MYR", BigDecimal.TEN, BigDecimal.TEN, "{}", "{}");
        var shop = new Shop("org", MarketplacePlatform.TIKTOK_SHOP, "remote-shop", "merchant", "cipher", "LOCAL", "Test", "MY", "[]");
        connection = new MarketplaceAccess.Connection(shop,
                new MarketplaceAccess.TokenSet("access", "refresh", Instant.now().plusSeconds(3600), null, List.of()));
        when(authorization.connectionForPublishing("org", "shop", MarketplacePlatform.TIKTOK_SHOP)).thenReturn(connection);
        ProductCaptureImage image = new ProductCaptureImage("org", capture.getId(), "https://source.example/img.webp", "MAIN", null, 0, "image/webp", "{}");
        image.processed("https://processed.example/img.webp");
        when(images.selectList(any())).thenReturn(List.of(image));
        when(skus.selectList(any())).thenReturn(List.of());
        when(downloader.downloadProductImage(anyString(), any())).thenReturn(new MarketplaceImageDownloader.ImageBinary(new byte[]{1,2,3}, "image/jpeg", ".jpg"));
        when(client.apiVersion()).thenReturn("202309");
        when(client.apiMultipart(anyString(), eq(connection), anyMap(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(Map.of("data", Map.of("uri", "main-uri")));
        when(client.apiPost(endsWith("/listing_check"), eq(connection), anyMap(), anyMap()))
                .thenReturn(Map.of("data", Map.of("check_result", "PASS")));
        created = new AtomicReference<>();
        when(client.apiPost(eq("/product/202309/products"), eq(connection), anyMap(), anyMap())).thenAnswer(call -> {
            created.set(call.getArgument(3));
            return Map.of("data", Map.of("product_id", "remote-product"));
        });
        when(client.apiGet(anyString(), eq("access"), eq("cipher"), anyMap())).thenAnswer(ignored -> {
            var data = new LinkedHashMap<>(created.get());
            data.put("id", "remote-product");
            data.put("status", "DRAFT");
            @SuppressWarnings("unchecked") var payloadSkus = (List<Map<String, Object>>) created.get().get("skus");
            data.put("skus", payloadSkus.stream().map(sku -> {
                var copied = new LinkedHashMap<>(sku); copied.put("id", "remote-sku"); return copied;
            }).toList());
            return Map.of("data", data);
        });
        when(client.apiGet(eq("/product/202309/categories/601302/attributes"), eq("access"), eq("cipher"), anyMap()))
                .thenReturn(Map.of("data", Map.of("attributes", List.of())));
        when(client.apiGet(eq("/product/202309/categories/601302/rules"), eq("access"), eq("cipher"), anyMap()))
                .thenReturn(Map.of("data", Map.of("size_chart", Map.of("is_supported", false))));
        when(videos.upload(capture, connection, "publish-key")).thenReturn(new TikTokProductVideoUploader.UploadedVideo("video-id", "source-video", 1));
        boundary = mock(ProductPublishingProvider.ExternalCreateBoundary.class);
    }

    @Test void videoIsIncludedInListingCheckAndCreationAndConfirmedByProductReadback() {
        var result = provider.publish(capture, "TIKTOK_SHOP", "shop", request(), boundary);
        assertThat(created.get()).containsEntry("video", Map.of("id", "video-id"));
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> preflight = ArgumentCaptor.forClass(Map.class);
        verify(client).apiPost(endsWith("/listing_check"), eq(connection), eq(Map.of()), preflight.capture());
        assertThat(preflight.getValue()).containsEntry("video", Map.of("id", "video-id"));
        assertThat(result.providerResponse()).containsEntry("videoId", "video-id")
                .containsEntry("videoCount", 1).containsEntry("videoVerification", "CONFIRMED_BY_GET_PRODUCT");
        var ordered = inOrder(videos, client, boundary);
        ordered.verify(videos).upload(capture, connection, "publish-key");
        ordered.verify(client).apiPost(endsWith("/listing_check"), eq(connection), anyMap(), anyMap());
        ordered.verify(boundary).enter();
        ordered.verify(client).apiPost(eq("/product/202309/products"), eq(connection), anyMap(), anyMap());
    }

    @Test void pendingVideoStopsBeforeImageUploadAndBeforeProductCreateBoundary() {
        when(videos.upload(capture, connection, "publish-key")).thenThrow(new VideoUploadPendingException("upload pending"));
        assertThatThrownBy(() -> provider.publish(capture, "TIKTOK_SHOP", "shop", request(), boundary))
                .isInstanceOf(VideoUploadPendingException.class);
        verifyNoInteractions(boundary);
        verify(client, never()).apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(), anyMap());
        verify(client, never()).apiPost(anyString(), any(), anyMap(), anyMap());
    }

    @Test void sourceTextSizeChartIsAutomaticallyIncludedInPreflightAndDraftCreation() {
        capture.updateDraft(capture.getTitle(),
                "Size chart (cm): Length Chest Sleeve Shoulder S 68cm 47cm 19cm 46cm M 70cm 50cm 20cm 48cm",
                capture.getCategoryName(), null, "MYR", BigDecimal.TEN, BigDecimal.TEN, "{}");
        when(client.apiGet(eq("/product/202309/categories/601302/rules"), eq("access"), eq("cipher"), anyMap()))
                .thenReturn(Map.of("data", Map.of("size_chart", Map.of("is_supported", true, "is_required", true))));
        var result = provider.publish(capture, "TIKTOK_SHOP", "shop", request(), boundary);
        assertThat(created.get()).containsEntry("size_chart", Map.of("image", Map.of("uri", "main-uri")));
        verify(client).apiMultipart(endsWith("/images/upload"), eq(connection), anyMap(), anyString(),
                anyString(), any(byte[].class), eq(Map.of("use_case", "SIZE_CHART_IMAGE")));
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> preflight = ArgumentCaptor.forClass(Map.class);
        verify(client).apiPost(endsWith("/listing_check"), eq(connection), anyMap(), preflight.capture());
        assertThat(preflight.getValue().get("size_chart")).isEqualTo(created.get().get("size_chart"));
        assertThat(result.providerResponse()).containsKey("sizeChartSourceType");
    }

    @Test void requestCannotInventVideoIdWhenNoCollectedVideoExists() {
        when(videos.upload(capture, connection, "publish-key")).thenReturn(TikTokProductVideoUploader.UploadedVideo.none());
        var request = new LinkedHashMap<>(request());
        request.put("tiktokShopPayload", Map.of("video", Map.of("id", "unverified")));
        var result = provider.publish(capture, "TIKTOK_SHOP", "shop", request, boundary);
        assertThat(created.get()).doesNotContainKey("video");
        assertThat(result.providerResponse()).doesNotContainKey("videoVerification");
    }

    @Test void missingOrMismatchedReadbackVideoNeverBecomesSuccessfulUpload() {
        Map<String, Object> expected = Map.of("skus", List.of(Map.of("seller_sku", "sku")), "video", Map.of("id", "expected-video"));
        var data = new LinkedHashMap<String, Object>(Map.of("id", "product", "status", "DRAFT", "skus", List.of(Map.of("id", "remote-sku", "seller_sku", "sku"))));
        assertThatThrownBy(() -> provider.verifyReadback("product", expected, Map.of(), Map.of("data", data)))
                .hasMessageContaining("视频缺失或不一致");
        data.put("video", Map.of("id", "wrong-video"));
        assertThatThrownBy(() -> provider.verifyReadback("product", expected, Map.of(), Map.of("data", data)))
                .hasMessageContaining("禁止盲目重试");
    }

    private Map<String, Object> request() {
        return Map.of("publishStock", 999, "categoryId", "601302", "warehouseId", "warehouse", "weightGrams", 250,
                "packageLengthCm", 10, "packageWidthCm", 10, "packageHeightCm", 2, "idempotencyKey", "publish-key");
    }
}
