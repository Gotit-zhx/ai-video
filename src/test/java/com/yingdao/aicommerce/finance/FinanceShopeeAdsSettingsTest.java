package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceShopeeAdsSettingsTest {
    @TempDir Path directory;
    @Test void missingOrNotYetLiveConfigurationNeverFallsBackToErpOrSandboxCredentials()throws Exception{
        var http=mock(MarketplaceHttp.class);assertNull(new FinanceShopeeAdsSettings(http,"").current());
        var file=directory.resolve("pending.properties");Files.writeString(file,"live-enabled=false\npartner-id=1234567\npartner-key=only-test-placeholder\n");
        assertNull(new FinanceShopeeAdsSettings(http,file.toString()).current());verifyNoInteractions(http);
    }
    @Test void liveApplicationIsBoundToItsOwnIdAndOpaqueRevisionWithoutPrintingSecrets()throws Exception{
        var file=directory.resolve("live.properties");Files.writeString(file,"live-enabled=true\npartner-id=1234567\npartner-key=only-test-placeholder\n");
        var settings=new FinanceShopeeAdsSettings(mock(MarketplaceHttp.class),file.toString());var app=settings.current();
        assertEquals("1234567",app.partnerId());assertNotNull(app.client());assertFalse(app.toString().contains("only-test-placeholder"));
        String revision=app.revision();Files.writeString(file,"live-enabled=true\npartner-id=1234567\npartner-key=rotated-test-placeholder\n");assertNotEquals(revision,settings.current().revision());
    }
    @Test void approvedRegionalEndpointsAreReadFromTheDedicatedConfiguration()throws Exception{
        var file=directory.resolve("china.properties");Files.writeString(file,"live-enabled=true\npartner-id=1234567\npartner-key=only-test-placeholder\nauth-base-url=https://open.shopee.cn\napi-base-url=https://openplatform.shopee.cn\n");
        var app=new FinanceShopeeAdsSettings(mock(MarketplaceHttp.class),file.toString()).current();
        assertTrue(app.client().authorizationUrl("https://example.test/callback","state").startsWith("https://open.shopee.cn/auth?"));
        Files.writeString(file,"live-enabled=true\npartner-id=1234567\npartner-key=only-test-placeholder\napi-base-url=https://untrusted.example\n");
        assertThrows(RuntimeException.class,()->new FinanceShopeeAdsSettings(mock(MarketplaceHttp.class),file.toString()).current());
    }
    @Test void invalidOrUnreadableConfigurationOnlyExposesASanitizedReason()throws Exception{
        var file=directory.resolve("invalid.properties");Files.writeString(file,"live-enabled=true\npartner-id=invalid\npartner-key=only-test-placeholder\n");
        var error=assertThrows(RuntimeException.class,()->new FinanceShopeeAdsSettings(mock(MarketplaceHttp.class),file.toString()).current());assertFalse(error.getMessage().contains("only-test-placeholder"));
        var missing=assertThrows(RuntimeException.class,()->new FinanceShopeeAdsSettings(mock(MarketplaceHttp.class),directory.resolve("private-path").toString()).current());assertFalse(missing.getMessage().contains("private-path"));
    }
}
