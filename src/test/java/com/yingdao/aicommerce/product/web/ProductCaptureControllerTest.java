package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.ExtensionAuthService;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.application.ProductCaptureService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductCaptureControllerTest {
    @Test
    void listReturnsOnlyPagedSummaryFieldsAndKeepsLegacyLimitCompatibility() throws Exception {
        ProductCaptureService service = mock(ProductCaptureService.class);
        ProductCaptureController controller = new ProductCaptureController(
                service, mock(ExtensionAuthService.class), new ObjectMapper()
        );
        PlatformPrincipal principal = new PlatformPrincipal(
                "user-1", "org-a", "tenant", "user", "User", "password", true,
                List.of(), List.of("product:view")
        );
        var capture = new com.yingdao.aicommerce.product.domain.ProductCapture(
                "org-a", "user-1", "capture-summary-key", "fingerprint",
                "SHOPEE", "https://shopee.ph/a-i.123.456", "123", "456",
                "Shopee 商品", "large description", null, null, "PHP",
                java.math.BigDecimal.TEN, java.math.BigDecimal.TEN, "{}", "{\"secret\":true}"
        );
        var thumbnail = new com.yingdao.aicommerce.product.domain.ProductCaptureImage(
                "org-a", capture.getId(), "https://cdn.example.com/main.webp",
                "MAIN", null, 0, "image/webp", "{}"
        );
        var page = new ProductCaptureService.CapturePage(
                List.of(new ProductCaptureService.CaptureSummary(capture, thumbnail)),
                0, 25, 51, 3, Map.of("CAPTURED", 51L)
        );
        when(service.list("org-a", "DRAFT", "dress", 0, 25)).thenReturn(page);

        var response = controller.list("DRAFT", "dress", 0, null, 25, principal);

        assertEquals(51, response.data().totalElements());
        assertEquals("https://cdn.example.com/main.webp",
                response.data().content().getFirst().thumbnailUrl());
        String json = new ObjectMapper().writeValueAsString(response.data());
        assertFalse(json.contains("rawData"));
        assertFalse(json.contains("normalizedData"));
        assertFalse(json.contains("fieldSources"));
        assertFalse(json.contains("skus"));
        assertFalse(json.contains("images"));
        assertFalse(json.contains("large description"));
        verify(service).list("org-a", "DRAFT", "dress", 0, 25);
    }

    @Test
    void extensionTokenCannotMintAnotherPairingCode() {
        ExtensionAuthService authService = mock(ExtensionAuthService.class);
        ProductCaptureController controller = new ProductCaptureController(
                mock(ProductCaptureService.class), authService, new ObjectMapper()
        );
        PlatformPrincipal principal = new PlatformPrincipal(
                "user-1", "org-a", "tenant", "user", "User", "password", true,
                List.of(), List.of("product:collect")
        );
        Authentication authentication = mock(Authentication.class);
        when(authentication.getDetails()).thenReturn(new ExtensionAuthService.AuthenticatedToken(
                "token-id", principal, List.of("product:collect"), Instant.now().plusSeconds(60),
                "Device", "extension-id", "0.1.0"
        ));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> controller.pairingCode(principal, authentication));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        verify(authService, never()).createPairingCode(principal);
    }
}
