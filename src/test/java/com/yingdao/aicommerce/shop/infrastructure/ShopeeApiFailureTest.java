package com.yingdao.aicommerce.shop.infrastructure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopeeApiFailureTest {
    @Test void officialErrorKeepsItsCodeWhenAMessageExistsAndRedactsCredentials(){
        String code="ads_error_product_gms_campaign_not_found";
        var failure=ShopeeApiFailureFixture.failure("/api/v2/ads/get_gms_campaign_performance",code,"Product GMS Campaign cannot be found. access_token=synthetic-secret");
        assertEquals(code,assertInstanceOf(ShopeeOfficialClient.ApiFailure.class,failure).errorCode());
        assertFalse(failure.getMessage().contains("synthetic-secret"));
    }
    @Test void malformedErrorCodeCannotBypassCredentialRedaction(){
        var failure=ShopeeApiFailureFixture.failure("/api/v2/ads/get_gms_campaign_performance","partner_key:synthetic-secret","Report unavailable");
        assertEquals("unrecognized_error",assertInstanceOf(ShopeeOfficialClient.ApiFailure.class,failure).errorCode());
        assertFalse(failure.getMessage().contains("synthetic-secret"));
    }
}
