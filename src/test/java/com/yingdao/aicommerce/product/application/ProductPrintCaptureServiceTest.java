package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductPrintCaptureServiceTest {
    private final PlatformPrincipal editor = principal("product:edit");
    private final ProductCaptureService captures = mock(ProductCaptureService.class);
    private final CommerceCaptureSourceArchiveService sourceArchives = mock(CommerceCaptureSourceArchiveService.class);
    private final CommerceImageArchive archive = mock(CommerceImageArchive.class);
    private final MarketplaceImageDownloader downloader = mock(MarketplaceImageDownloader.class);
    private final CommerceSampleService samples = mock(CommerceSampleService.class);
    private final ProductPrintCaptureService service = new ProductPrintCaptureService(
            captures, new SafeProductImageUrl(), sourceArchives, archive, downloader, samples);

    @Test
    void lookupExposesOnlyUsableOriginalsAndSkipsSizeCharts() {
        var capture = new ProductCapture("org-a", "u", "key", "hash", "SHOPEE",
                "https://shopee.ph/product/105/209", "105", "209", "印花 T 恤", null, null, null,
                null, null, null, "{}", "{}");
        var original = image(capture, "https://images.example.com/t-shirt.jpg", "MAIN");
        var sizeChart = image(capture, "https://images.example.com/chart.jpg", "SIZE_CHART");
        var legacyUnsafe = image(capture, "https://127.0.0.1/image.jpg", "DETAIL");
        when(captures.findPrintSource(capture.getSourceUrl(), editor))
                .thenReturn(new ProductCaptureService.PrintSource(capture, List.of(original, sizeChart, legacyUnsafe)));

        var result = service.lookup(capture.getSourceUrl(), editor);
        assertEquals(capture.getId(), result.captureId());
        assertEquals("印花 T 恤", result.title());
        assertEquals(List.of(new ProductPrintCaptureService.Image(original.getId(), original.getOriginalUrl())),
                result.images());
        verifyNoInteractions(sourceArchives, archive, downloader, samples);
    }

    @Test
    void extractionNeedsEditPermissionAndExplicitConfirmationBeforeAnyRead() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.extract("capture", "image", true, "request-id", principal("product:view"))).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class,
                () -> service.extract("capture", "image", false, "request-id", editor)).getStatus());
        verifyNoInteractions(captures, sourceArchives, archive, downloader, samples);
    }

    @Test
    void extractionRetryUsesTheSameArchivedSourceAndRefusesChangingImage() {
        var db = mock(JdbcTemplate.class);
        var actual = spy(new CommerceSampleService(db, null, null, archive, downloader, null));
        var existing = Map.<String, Object>of("id", "original-id", "prompt", "来源采集商品 capture-a / image-a",
                "status", "READY", "product", "UPLOADED", "asset_kind", "PRODUCT_IMAGE");
        when(db.queryForList(contains("request_key=?"), eq("org-a"), eq("u"),
                matches("capture-v2-src-[a-f0-9]{64}"))).thenReturn(List.of(existing));
        var request = new CommerceSampleService.Request("UPLOADED", 0, true, "original-id", "PRINT_EXTRACT");
        var result = new CommerceSampleService.View("print-id", "UPLOADED", 0, "READY", "model",
                "/api/v1/commerce-samples/print-id/image", "original-id", "PRINT_EXTRACT");
        doReturn(result).when(actual).generate(eq(request), matches("capture-v2-art-[a-f0-9]{64}"), same(editor));

        assertSame(result, actual.extractCapturePrint("request-id", "capture-a", "image-a",
                () -> fail("Retry must reuse the archived product image"), editor));
        assertEquals(HttpStatus.CONFLICT, assertThrows(BusinessException.class,
                () -> actual.extractCapturePrint("request-id", "capture-a", "another-image",
                        () -> fail("The same key is bound to the original image"), editor)).getStatus());
        verifyNoInteractions(archive, downloader);
    }

    private static PlatformPrincipal principal(String permission) {
        return new PlatformPrincipal("u", "org-a", "org", "user", "用户", "", true,
                List.of(), List.of(permission));
    }

    private static ProductCaptureImage image(ProductCapture capture, String url, String role) {
        return new ProductCaptureImage(capture.getOrganizationId(), capture.getId(), url, role,
                null, 0, "image/jpeg", "{}");
    }
}
