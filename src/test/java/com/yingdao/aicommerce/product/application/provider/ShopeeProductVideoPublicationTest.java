package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
    ProductCaptureSkuMapper skus;
    ProductCaptureImageMapper images;
    MarketplaceImageDownloader imageDownloader;

    ShopeeSizeChartExtractor charts;
    @BeforeEach void setup() {
        var shops = mock(ShopAuthorizationService.class); client = mock(ShopeeOfficialClient.class);
        skus = mock(ProductCaptureSkuMapper.class); images = mock(ProductCaptureImageMapper.class);
        imageDownloader = mock(MarketplaceImageDownloader.class);
        videos = mock(ShopeeProductVideoUploader.class);
        charts = mock(ShopeeSizeChartExtractor.class);
        when(charts.prepare(any(), anyList(), any(), anyLong())).thenReturn(Map.of());
        provider = spy(new ShopeeOfficialPublishingProvider(shops, client, skus, images, imageDownloader, new ObjectMapper(), videos, charts));
        capture = new ProductCapture("org-1", "user-1", "key", "fingerprint", "SHOPEE", "https://example.invalid/product", "source-shop", "1",
                "【Ready Stock】Cherry Women White Cotton T-Shirt with Short Sleeves and Round Neck for Casual Everyday Summer Wear Multicoloured", "Description", "Category", null, "MYR", new BigDecimal("20"), new BigDecimal("20"), "{}", "{}");
        connection = new MarketplaceAccess.Connection(new Shop("org-1", MarketplacePlatform.SHOPEE, "9988", null, null, "SELLER", "Shop", "MY", "[]"),
                new MarketplaceAccess.TokenSet("access", "refresh", Instant.now().plusSeconds(3600), null, List.of()));
        when(shops.connectionForPublishing("org-1", connection.shop().getId(), MarketplacePlatform.SHOPEE)).thenReturn(connection);
        when(client.shopGet(ShopeeOfficialClient.SHOP_INFO_PATH, connection, Map.of())).thenReturn(Map.of("response", Map.of("status", "NORMAL", "is_upgraded_cbsc", false)));
        when(client.shopGet(eq(ShopeeProductTitle.LIMITS), eq(connection), anyMap())).thenReturn(Map.of("response", Map.of("item_name_length_limit", Map.of("min_limit", 20, "max_limit", 100), "price_limit", Map.of("min_limit", 5, "max_limit", 200000))));
        when(client.shopGet(eq("/api/v2/product/get_attribute_tree"), eq(connection), anyMap())).thenReturn(
                Map.of("response", Map.of("list", List.of(Map.of("category_id", 2, "attribute_tree",
                        List.of(Map.of("attribute_id",100134,"name","Material","attribute_value_list",
                                List.of(Map.of("value_id",1149,"name","Cotton")))))))));
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
        when(charts.prepare(eq(capture), anyList(), eq(connection), anyLong())).thenReturn(Map.of("size_chart", "chart-123"));
        when(videos.upload(capture, connection)).thenAnswer(ignored -> { events.add("video_ready"); return new ShopeeProductVideoUploader.UploadedVideo("video-123", "source-video", 2); });
        var result = provider.publish(capture, "SHOPEE", connection.shop().getId(), request(), () -> events.add("create_boundary"));
        assertThat(events).containsExactly("video_ready", "create_boundary", "add_item");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).shopPost(eq("/api/v2/product/add_item"), eq(connection), payload.capture());
        assertThat((String) payload.getValue().get("item_name")).hasSizeBetween(20,100).startsWith("Cherry");
        assertThat(payload.getValue()).containsEntry("attribute_list", List.of(Map.of("attribute_id",100134,
                "attribute_value_list",List.of(Map.of("value_id",1149,"original_value_name","Cotton")))));
        assertThat(payload.getValue()).containsEntry("size_chart_info", Map.of("size_chart", "chart-123"));
        assertThat(payload.getValue()).containsEntry("video_upload_id", List.of("video-123")).containsEntry("item_status", "UNLIST");
        assertThat(result.providerResponse()).containsEntry("videoCount", 1).containsEntry("sourceVideoCount", 2).containsEntry("listingMode", "PENDING_LISTING");
    }

    @Test void missingOfficialTitleRulesStopsBeforeMediaAndCreateBoundary() {
        when(client.shopGet(eq(ShopeeProductTitle.LIMITS), eq(connection), anyMap())).thenReturn(Map.of("response", Map.of()));
        assertThatThrownBy(() -> provider.publish(capture, "SHOPEE", connection.shop().getId(), request(), () -> events.add("create_boundary")))
                .hasMessageContaining("标题长度规则");
        verifyNoInteractions(videos);
        assertThat(events).isEmpty();
        verify(provider, never()).uploadImages(any(), anyList());
    }

    @Test void noVideoKeepsExistingProductPublishingPath() {
        when(videos.upload(capture, connection)).thenReturn(ShopeeProductVideoUploader.UploadedVideo.none());
        var result = provider.publish(capture, "SHOPEE", connection.shop().getId(), request(), () -> events.add("create_boundary"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).shopPost(eq("/api/v2/product/add_item"), eq(connection), payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("video_upload_id");
        assertThat(result.providerResponse()).containsEntry("videoCount", 0).containsEntry("remoteStatus", "UNLIST");
    }
    @Test void actualAddItemUsesImmutableTemplateDescriptionAndParcelInKg() {
        when(videos.upload(capture,connection)).thenReturn(ShopeeProductVideoUploader.UploadedVideo.none());
        var body=new java.util.LinkedHashMap<>(request());
        com.yingdao.aicommerce.product.application.ProductListingTemplateService.apply(body,Map.of(
                "enabled",true,"description","CurveLuxe PH — Stretch. Slay.","weightGrams","200",
                "packageLengthCm","5","packageWidthCm","25","packageHeightCm","25"));
        provider.publish(capture,"SHOPEE",connection.shop().getId(),body,()->{});
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> payload=ArgumentCaptor.forClass(Map.class);
        verify(client).shopPost(eq("/api/v2/product/add_item"),eq(connection),payload.capture());
        assertThat(payload.getValue()).containsEntry("description","CurveLuxe PH — Stretch. Slay.");
        assertThat((BigDecimal)payload.getValue().get("weight")).isEqualByComparingTo("0.2");
        assertThat(payload.getValue().get("dimension")).isEqualTo(Map.of(
                "package_length",new BigDecimal("5"),"package_width",new BigDecimal("25"),"package_height",new BigDecimal("25")));
        assertThat(capture.getDescription()).isEqualTo("Description");
    }

    @Test void nineLocalDetailsPublishAsMainAndEightDetailsWhileAllSkuImagesRemainBound() {
        var localImages = new ArrayList<ProductCaptureImage>();
        var localSkus = new ArrayList<ProductCaptureSku>();
        for (int order = 0; order < 39; order++) {
            String role = order == 0 ? "MAIN" : order <= 9 ? "DETAIL" : "VARIANT";
            String original = "https://source.invalid/" + order + ".png";
            var image = new ProductCaptureImage("org-1", capture.getId(), original, role, role + order, order, "image/png", "{}");
            image.processed("https://processed.invalid/" + order + ".png"); localImages.add(image);
            if (order >= 10) {
                String color = "Color " + order;
                localSkus.add(new ProductCaptureSku("org-1", capture.getId(), "sku-" + order, "external-" + order,
                        "COLOR-" + order, color, new BigDecimal("20"), new BigDecimal("20"), 100, original,
                        "COMMERCE_GENERATED", "{\"Color\":\"" + color + "\"}", "{}", order - 10));
            }
        }
        Collections.reverse(localImages); // The adapter must use saved sort_order, not mapper iteration order.
        var frozenImages = List.copyOf(localImages); var frozenSkus = List.copyOf(localSkus);
        var before = frozenImages.stream().map(image -> List.of(image.getId(), image.getRoleName(),
                image.getSortOrder(), image.getOriginalUrl(), image.getProcessedUrl(), image.getStatus())).toList();
        when(images.selectList(any())).thenReturn(frozenImages); when(skus.selectList(any())).thenReturn(frozenSkus);
        when(videos.upload(capture, connection)).thenReturn(ShopeeProductVideoUploader.UploadedVideo.none());
        doCallRealMethod().when(provider).uploadImages(eq(connection), anyList());
        when(imageDownloader.downloadProductImage(anyString(), any())).thenAnswer(call -> {
            ProductCaptureImage image = call.getArgument(1);
            return new MarketplaceImageDownloader.ImageBinary(("image-" + image.getSortOrder()).getBytes(StandardCharsets.UTF_8), "image/png", ".png");
        });
        when(client.shopMultipart(eq("/api/v2/media_space/upload_image"), eq(connection), anyString(), eq("image/png"), any(byte[].class), anyMap()))
                .thenAnswer(call -> Map.of("response", Map.of("image_info", Map.of("image_id", new String(call.<byte[]>getArgument(4), StandardCharsets.UTF_8)))));
        when(client.shopPost(eq("/api/v2/product/init_tier_variation"), eq(connection), anyMap())).thenReturn(Map.of("response", Map.of()));
        var modelMappings = java.util.stream.IntStream.range(0, frozenSkus.size())
                .mapToObj(index -> Map.<String, Object>of("model_id", 9000L + index, "tier_index", List.of(index))).toList();
        when(client.shopGet("/api/v2/product/get_model_list", connection, Map.of("item_id", 123456L)))
                .thenReturn(Map.of("response", Map.of("model", modelMappings)));

        var result = provider.publish(capture, "SHOPEE", connection.shop().getId(), request(), () -> events.add("create_boundary"));

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> addItem = ArgumentCaptor.forClass(Map.class);
        verify(client).shopPost(eq("/api/v2/product/add_item"), eq(connection), addItem.capture());
        assertThat(addItem.getValue().get("image")).isEqualTo(Map.of("image_id_list",
                java.util.stream.IntStream.rangeClosed(0, 8).mapToObj(index -> "image-" + index).toList()));
        assertThat(result.providerResponse()).containsEntry("imageCount", 9).containsEntry("skuCount", 29);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> variation = ArgumentCaptor.forClass(Map.class);
        verify(client).shopPost(eq("/api/v2/product/init_tier_variation"), eq(connection), variation.capture());
        var tiers = (List<?>) variation.getValue().get("standardise_tier_variation");
        var options = (List<?>) ((Map<?, ?>) tiers.getFirst()).get("variation_option_list");
        assertThat(options).hasSize(29);
        for (int index = 0; index < options.size(); index++) {
            var option = (Map<?, ?>) options.get(index);
            assertThat(option.get("variation_option_name")).isEqualTo("Color " + (index + 10));
            assertThat(option.get("image_id")).isEqualTo("image-" + (index + 10));
        }
        assertThat(frozenImages).hasSize(39);
        assertThat(frozenImages.stream().filter(image -> "DETAIL".equals(image.getRoleName())).count()).isEqualTo(9);
        assertThat(frozenImages.stream().map(image -> List.of(image.getId(), image.getRoleName(),
                image.getSortOrder(), image.getOriginalUrl(), image.getProcessedUrl(), image.getStatus())).toList()).isEqualTo(before);
        verify(imageDownloader, times(39)).downloadProductImage(anyString(), any());
        verify(images).selectList(any()); verifyNoMoreInteractions(images);
        verify(skus).selectList(any()); verifyNoMoreInteractions(skus);
        assertThat(events).containsExactly("create_boundary", "add_item");
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
