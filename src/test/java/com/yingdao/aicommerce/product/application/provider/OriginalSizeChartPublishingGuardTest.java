package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OriginalSizeChartPublishingGuardTest {
    @Test
    void sourceChartPreservationDoesNotDisableOrdinaryImageDryRunProtection() {
        for (Class<?> type : List.of(ShopeeOfficialPublishingProvider.class, TikTokShopOfficialPublishingProvider.class)) {
            Object provider = mock(type, CALLS_REAL_METHODS);
            ProductCaptureImage chart = image("SIZE_CHART");
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(provider, "rejectDryRunImages", List.of(chart)))
                    .doesNotThrowAnyException();
            for (String role : List.of("MAIN", "GALLERY", "VARIANT", "DETAIL")) {
                ProductCaptureImage ordinary = image(role);
                assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(provider, "rejectDryRunImages", List.of(chart, ordinary)))
                        .hasMessageContaining("Dry Run");
                ordinary.processed("https://cdn.example.com/processed.png");
                assertThatCode(() -> ReflectionTestUtils.invokeMethod(provider, "rejectDryRunImages", List.of(chart, ordinary)))
                        .doesNotThrowAnyException();
            }
        }
    }
    private ProductCaptureImage image(String role) {
        var image = new ProductCaptureImage("org", "capture", "https://cdn.example.com/original.png",
                role, "", 0, "image/png", "{}");
        image.processed(image.getOriginalUrl());
        return image;
    }
}
