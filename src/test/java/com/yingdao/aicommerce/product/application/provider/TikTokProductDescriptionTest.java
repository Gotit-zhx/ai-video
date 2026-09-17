package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TikTokProductDescriptionTest {
    private final TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
    private final MarketplaceImageDownloader downloader = mock(MarketplaceImageDownloader.class);
    private final TikTokProductDescription helper = new TikTokProductDescription(client, downloader);

    @Test void appendsAllDetailsInSourceOrderUsingProcessedArchivesAndOfficialUrl() throws Exception {
        var capture = capture("Cotton shirt\nSoft & comfortable");
        var first = image(capture, "DETAIL", 2, "one");
        var second = image(capture, "DETAIL", 3, "two");
        var main = image(capture, "MAIN", 0, "main");
        responses();
        var result = helper.prepare(capture, List.of(second, main, first), connection("org-1"));
        assertThat(result.html()).startsWith("<p>Cotton shirt<br>Soft &amp; comfortable</p>")
                .contains("width=\"120\" height=\"200\"")
                .doesNotContain("original.example", "/api/v1/product-watermark");
        assertThat(result.html().indexOf("one")).isLessThan(result.html().indexOf("two"));
        assertThat(result.imageCount()).isEqualTo(2);
        verify(downloader).downloadProductImage(first.getProcessedUrl(), first);
        verify(downloader).downloadProductImage(second.getProcessedUrl(), second);
        verify(downloader, never()).download(anyString());
        verify(downloader, never()).downloadProductImage(main.getProcessedUrl(), main);
    }

    @Test void replacesOriginalHtmlImageInPlaceAndDoesNotAppendItAgain() throws Exception {
        var capture = capture("<p>Before</p><img src='https://original.example/one?x=1&amp;y=2' width='10'><p>After</p>");
        var detail = new ProductCaptureImage("org-1", capture.getId(), "https://original.example/one?x=1&y=2", "DETAIL", "One", 1, "image/png", "{}");
        detail.processed("/api/v1/product-watermark/assets/one");
        responses();
        var result = helper.prepare(capture, List.of(detail), connection("org-1"));
        assertThat(result.html()).startsWith("<p>Before</p><img ").endsWith("<p>After</p>");
        assertThat(result.html()).doesNotContain("original.example").containsOnlyOnce("<img ");
        assertThat(result.imageCount()).isEqualTo(1);
    }

    @Test void duplicateDetailRecordsUploadAndAppearOnlyOnce() throws Exception {
        var capture = capture("Details");
        var first = image(capture, "DETAIL", 0, "one");
        var duplicate = image(capture, "DETAIL", 1, "one");
        responses();
        var result = helper.prepare(capture, List.of(first, duplicate), connection("org-1"));
        assertThat(result.imageCount()).isEqualTo(1);
        assertThat(result.html()).containsOnlyOnce("<img ");
    }

    @Test void processedOriginalUrlIsValidWhenWatermarkDetectionFoundNothingToRemove() throws Exception {
        var capture = capture("No-watermark product details");
        var detail = image(capture, "DETAIL", 0, "clean-original");
        detail.processed(detail.getOriginalUrl());
        responses();
        var result = helper.prepare(capture, List.of(detail), connection("org-1"));
        assertThat(result.imageCount()).isEqualTo(1);
        assertThat(result.html()).contains("https://p16-oec-va.ibyteimg.com/").doesNotContain("original.example");
        verify(downloader).downloadProductImage(detail.getOriginalUrl(), detail);
        verify(client).apiMultipart(eq("/product/202309/images/upload"), any(), eq(Map.of()),
                anyString(), eq("image/png"), any(byte[].class), eq(Map.of("use_case", "DESCRIPTION_IMAGE")));
    }

    @Test void sourceHtmlGalleryImageIsReuploadedAsDescriptionImage() throws Exception {
        var capture = capture("<div><img src=\"https://original.example/main\"/></div>");
        var main = image(capture, "MAIN", 0, "main");
        responses();
        assertThat(helper.prepare(capture, List.of(main), connection("org-1")).imageCount()).isEqualTo(1);
        verify(client).apiMultipart(eq("/product/202309/images/upload"), any(), eq(Map.of()),
                anyString(), eq("image/png"), any(byte[].class), eq(Map.of("use_case", "DESCRIPTION_IMAGE")));
    }

    @Test void neverEmbedsUncapturedOrUnprocessedOriginalImages() {
        var capture = capture("<p>Details</p><img src='https://original.example/not-captured'>");
        assertThatThrownBy(() -> helper.prepare(capture, List.of(), connection("org-1")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未采集处理");
        var plain = capture("Details");
        var image = image(plain, "DETAIL", 0, "one");
        image.processing();
        assertThatThrownBy(() -> helper.prepare(plain, List.of(image), connection("org-1")))
                .hasMessageContaining("尚未处理完成");
        verifyNoInteractions(client, downloader);
    }

    @Test void enforcesImageOwnershipBeforeUploading() {
        var capture = capture("Details");
        var foreign = image(capture("Another capture"), "DETAIL", 0, "one");
        assertThatThrownBy(() -> helper.prepare(capture, List.of(foreign), connection("org-1")))
                .hasMessageContaining("不属于当前商品");
        assertThatThrownBy(() -> helper.prepare(capture, List.of(), connection("org-other")))
                .hasMessageContaining("不属于当前店铺公司");
        verifyNoInteractions(client, downloader);
    }

    @Test void imageLimitIsExplicitAndNoThirtyFirstImageIsSilentlyDropped() {
        var capture = capture("Details");
        var images = new ArrayList<ProductCaptureImage>();
        for (int i = 0; i < 31; i++) images.add(image(capture, "DETAIL", i, "image-" + i));
        assertThatThrownBy(() -> helper.prepare(capture, images, connection("org-1")))
                .hasMessageContaining("30 张").hasMessageContaining("31 张");
        verifyNoInteractions(client, downloader);
    }

    @Test void rejectsOverlongDescriptionBeforeRemoteUploads() {
        var capture = capture("x".repeat(10_001));
        assertThatThrownBy(() -> helper.prepare(capture, List.of(), connection("org-1")))
                .hasMessageContaining("10000 字符");
        verifyNoInteractions(client, downloader);
    }

    @Test void rejectsMissingOfficialUrlInsteadOfPassingTheUriOrOriginalUrl() throws Exception {
        var capture = capture("Details");
        responses();
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(byte[].class), anyMap()))
                .thenReturn(Map.of("data", Map.of("uri", "tos-path/uri-only")));
        assertThatThrownBy(() -> helper.prepare(capture, List.of(image(capture, "DETAIL", 0, "one")), connection("org-1")))
                .hasMessageContaining("图片 URL");
    }

    @Test void longImagesAreResizedProportionallyBelowOfficialSideLimit() throws Exception {
        var result = TikTokProductDescription.prepareBinary(binary(800, 5000));
        assertThat(result.width()).isEqualTo(639);
        assertThat(result.height()).isEqualTo(3999);
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(result.binary().bytes()));
        assertThat(decoded.getWidth()).isEqualTo(639);
        assertThat(decoded.getHeight()).isEqualTo(3999);
    }

    @Test void imagesWithinLimitsKeepExactProcessedBytes() throws Exception {
        var binary = binary(120, 200);
        var result = TikTokProductDescription.prepareBinary(binary);
        assertThat(result.binary()).isSameAs(binary);
        assertThat(result.width()).isEqualTo(120);
        assertThat(result.height()).isEqualTo(200);
    }

    @Test void excessiveAspectRatioProducesActionableErrorInsteadOfInvalidUpload() throws Exception {
        assertThatThrownBy(() -> TikTokProductDescription.prepareBinary(binary(100, 5000)))
                .hasMessageContaining("长图分段");
    }

    private void responses() throws Exception {
        when(client.apiVersion()).thenReturn("202309");
        when(downloader.downloadProductImage(anyString(), any())).thenReturn(binary(120, 200));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.apiMultipart(anyString(), any(), anyMap(), anyString(), anyString(), any(byte[].class), eq(Map.of("use_case", "DESCRIPTION_IMAGE"))))
                .thenAnswer(invocation -> Map.of("data", Map.of("url", "https://p16-oec-va.ibyteimg.com/" + (calls.incrementAndGet() == 1 ? "one" : "two"), "uri", "tos/uri", "width", 120, "height", 200)));
    }
    private static MarketplaceImageDownloader.ImageBinary binary(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new MarketplaceImageDownloader.ImageBinary(bytes.toByteArray(), "image/png", ".png");
    }
    private static ProductCapture capture(String description) {
        return new ProductCapture("org-1", "user-1", "key", "fingerprint", "SHOPEE", "https://shopee.my/item-i.1.2", "1", "2",
                "Cotton shirt", description, "Women > T-shirts", "", "MYR", BigDecimal.ONE, BigDecimal.ONE, "{}", "{}");
    }
    private static ProductCaptureImage image(ProductCapture capture, String role, int order, String name) {
        var image = new ProductCaptureImage(capture.getOrganizationId(), capture.getId(), "https://original.example/" + name,
                role, name, order, "image/png", "{}");
        image.processed("/api/v1/product-watermark/assets/" + name);
        return image;
    }
    private static MarketplaceAccess.Connection connection(String organization) {
        var shop = new Shop(organization, MarketplacePlatform.TIKTOK_SHOP, "external", "merchant", "cipher", "LOCAL", "Demo", "MY", "[]");
        return new MarketplaceAccess.Connection(shop, new MarketplaceAccess.TokenSet("fake-access", "fake-refresh", Instant.now().plusSeconds(3600), null, List.of()));
    }
}
