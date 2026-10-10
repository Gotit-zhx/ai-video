package com.yingdao.aicommerce.shop.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ShopeeSandboxWriteGuardTest {
    @Test void rejectsProductionAndLookalikeDestinationsBeforeTransport() {
        for (String base : new String[]{"https://partner.shopeemobile.com",
                "https://openplatform.sandbox.test-stable.shopee.sg.evil.example",
                "http://openplatform.sandbox.test-stable.shopee.sg",
                "https://openplatform.sandbox.test-stable.shopee.sg@evil.example",
                "https://openplatform.sandbox.test-stable.shopee.sg?redirect=production"}) {
            var http = mock(MarketplaceHttp.class);
            var client = new ShopeeOfficialClient(http, true, "1", "test", base, base, "SHOP");
            ReflectionTestUtils.setField(client, "sandboxOnly", true);
            assertThrows(MarketplaceApiException.class,
                    () -> client.shopPost("/api/v2/product/add_item", null, Map.of()));
            assertThrows(MarketplaceApiException.class,
                    () -> client.shopMultipart("/api/v2/media_space/upload_image", null,
                            "test.png", "image/png", new byte[0], Map.of()));
            verifyNoInteractions(http);
        }
    }
    @Test void acceptsExactSandboxDestination() {
        var client = new ShopeeOfficialClient(mock(MarketplaceHttp.class), true, "1", "test",
                "https://open.sandbox.test-stable.shopee.com",
                "https://openplatform.sandbox.test-stable.shopee.sg", "SHOP");
        ReflectionTestUtils.setField(client, "sandboxOnly", true);
        ReflectionTestUtils.invokeMethod(client, "requireWriteDestination");
    }
}
