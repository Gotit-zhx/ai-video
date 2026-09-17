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
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopeeProductVideoPublicationTest {
    ShopeeOfficialClient client;
    ShopeeProductVideoUploader videos;
    ShopeeOfficialPublishingProvider provider;
    ProductCapture capture;
    MarketplaceAccess.Connection connection;
    List<String> events;

    @BeforeEach void setup() {
        var shops = mock(ShopAuthorizationService.class); client = mock(ShopeeOfficialClient.class);
        var skus = mock(ProductCaptureSkuMapper.class); var images = mock(ProductCaptureImageMapper.class);
        videos = mock(ShopeeProductVideoUploader.class);
        provider = spy(new ShopeeOfficialPublishingProvider(shops, client, skus, images, mock(MarketplaceImageDownloader.class), new ObjectMapper(), videos));
        capture = new ProductCapture("org-1", "user-1", "key", "fingerprint", "SHOPEE", "https://example.invalid/product", "source-shop", "1",
                "Product", "Description", "Category", null, "MYR", new BigDecimal("20"), new BigDecimal("20"), "{}", "{}");
        connection = new MarketplaceAccess.Connection(new Shop("org-1", MarketplacePlatform.SHOPEE, "9988", null, null, "SELLER", "Shop", "MY", "[]"),
                new MarketplaceAccess.TokenSet("access", "refresh", Instant.now().plusSeconds(3600), null, List.of()));
        when(shops.connectionForPublishing("org-1", connection.shop().getId(), MarketplacePlatform.SHOPEE)).thenReturn(connection);
        when(client.shopGet(ShopeeOfficialClient.SHOP_INFO_PATH, connection, Map.of())).thenReturn(Map.of("response", Map.of("status", "NORMAL", "is_upgraded_cbsc", false)));
        var image = new ProductCaptureImage("org-1", capture.getId(), "https://source.invalid/original.png", "MAIN", "", 0, "image/png", "{}");
        image.processed("https://processed.invalid/output.png");
        when(images.selectList(any())).thenReturn(List.of(image)); when(skus.selectList(any())).thenReturn(List.of());
        doReturn(Map.of("https://processed.invalid/output.png", "image-123")).when(provider).uploadImages(eq(connection), anyList());
        events = new ArrayList<>();
        when(client.shopPost(eq("/api/v2/product/add_item"), eq(connection), anyMap())).thenAnswer(call -> { events.add("add_item"); return Map.of("response", Map.of("item_id", 123456)); });
        when(client.shopGet("/api/v2/product/get_item_base_info", connection, Map.of("item_id_list", 123456L)))
                .thenReturn(Map.of("response", Map.of("item_list", List.of(Map.of("item_id", 123456L, "item_status", "UNLIST")))));
    }

    @Test void videoIsReadyBeforeCreateBoundaryAndOneVideoIdIsAttachedToUnlistedItem() {
        when(videos.upload(capture, connection)).thenAnswer(ignored -> { events.add("video_ready"); return new ShopeeProductVideoUploader.UploadedVideo("video-123", "source-video", 2); });
        var result = provider.publish(capture, "SHOPEE", connection.shop().getId(), request(), () -> events.add("create_boundary"));
        assertThat(events).containsExactly("video_ready", "create_boundary", "add_item");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).shopPost(eq("/api/v2/product/add_item"), eq(connection), payload.capture());
        assertThat(payload.getValue()).containsEntry("video_upload_id", List.of("video-123")).containsEntry("item_status", "UNLIST");
        assertThat(result.providerResponse()).containsEntry("videoCount", 1).containsEntry("sourceVideoCount", 2).containsEntry("listingMode", "PENDING_LISTING");
    }

    @Test void noVideoKeepsExistingProductPublishingPath() {
        when(videos.upload(capture, connection)).thenReturn(ShopeeProductVideoUploader.UploadedVideo.none());
        var result = provider.publish(capture, "SHOPEE", connection.shop().getId(), request(), () -> events.add("create_boundary"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).shopPost(eq("/api/v2/product/add_item"), eq(connection), payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("video_upload_id");
        assertThat(result.providerResponse()).containsEntry("videoCount", 0).containsEntry("remoteStatus", "UNLIST");
    }

    @Test void pendingOrRejectedVideoNeverCrossesProductCreateBoundary() {
        when(videos.upload(capture, connection)).thenThrow(new VideoUploadPendingException("still transcoding"));
        assertThatThrownBy(() -> provider.publish(capture, "SHOPEE", connection.shop().getId(), request(), () -> events.add("create_boundary")))
                .isInstanceOf(VideoUploadPendingException.class);
        assertThat(events).isEmpty();
        verify(provider, never()).uploadImages(any(), anyList());
        verify(client, never()).shopPost(eq("/api/v2/product/add_item"), any(), anyMap());
    }

    private Map<String, Object> request() {
        return Map.of("publishStock", 999, "categoryId", "2", "weightGrams", "250", "logisticInfo", List.of(Map.of("logistic_id", 123, "enabled", true)));
    }
}
