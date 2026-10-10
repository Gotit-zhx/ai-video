package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductWatermarkAssetsTest {
    @Test void onlyReadsReadyImageOwnedByRequestedCapture() {
        var ledger = mock(ProductWatermarkLedger.class);
        var archive = mock(CommerceImageArchive.class);
        var assets = new ProductWatermarkAssets(ledger, archive);
        when(ledger.get("check", "org")).thenReturn(Map.of("capture_id", "capture",
                "image_id", "image", "status", "READY", "output_key", "private-key"));
        assets.read("org", "capture", "image", "check");
        verify(archive).read("org", "private-key");
        assertThrows(IllegalArgumentException.class, () -> assets.read("org", "other", "image", "check"));
        assertThrows(IllegalArgumentException.class, () -> assets.read("org", "capture", "other", "check"));
        verifyNoMoreInteractions(archive);
    }
    @Test void neverExposesUnverifiedCandidate() {
        var ledger = mock(ProductWatermarkLedger.class);
        var archive = mock(CommerceImageArchive.class);
        var assets = new ProductWatermarkAssets(ledger, archive);
        for (String status : new String[]{"EDITED", "VALIDATING", "REVIEW_REQUIRED", "EDITING"}) {
            when(ledger.get("check", "org")).thenReturn(Map.of("capture_id", "capture",
                    "image_id", "image", "status", status, "candidate_key", "candidate"));
            assertThrows(IllegalArgumentException.class, () -> assets.read("org", "capture", "image", "check"));
        }
        verifyNoInteractions(archive);
    }
    @Test void tenantLookupFailureCannotFallBackToAnotherOrganization() {
        var ledger = mock(ProductWatermarkLedger.class);
        var archive = mock(CommerceImageArchive.class);
        when(ledger.get("check", "other-org")).thenThrow(new IllegalArgumentException("不存在"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductWatermarkAssets(ledger, archive).read("other-org", "capture", "image", "check"));
        verifyNoInteractions(archive);
    }
}
