package com.yingdao.aicommerce.product.application.provider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ShopeeModelSyncTest {
    @Test void onlyExactAddModelValidationRejectionIsRetryable() {
        String error="Shopee 官方接口 /api/v2/product/add_model 失败：Model tier_index error. : model in position []";
        assertThat(ShopeeOfficialPublishingProvider.confirmedTierNotReady(error)).isTrue();
        assertThat(ShopeeOfficialPublishingProvider.confirmedTierNotReady("timeout "+error)).isFalse();
        assertThat(ShopeeOfficialPublishingProvider.confirmedTierNotReady(error.replace("add_model","add_item"))).isFalse();
        assertThat(ShopeeOfficialPublishingProvider.confirmedTierNotReady(null)).isFalse();
        assertThat(ShopeeOfficialPublishingProvider.confirmedTierNotReady("HTTP 504")).isFalse();
    }
}
