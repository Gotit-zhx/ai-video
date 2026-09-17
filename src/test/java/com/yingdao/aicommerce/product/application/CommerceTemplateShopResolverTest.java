package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceTemplateShopResolverTest {
    private CommerceBatchPlanner.Request request(List<String> ids) {
        return new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,
                CommerceBatchPlanner.Platform.SHOPEE,"PH",1,1,1,1,50,
                false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE,ids);
    }
    private Shop shop(String market) {
        return new Shop("org",MarketplacePlatform.TIKTOK_SHOP,"external",null,null,"CROSS_BORDER","Shop",market,"");
    }
    @Test void legacyTemplateIsReusableAcrossPlatformAndCountryUsingActualShop() {
        var shop=shop(" vn ");
        var target=CommerceTemplateShopResolver.targetFor(request(null),shop,"org");
        assertEquals(CommerceBatchPlanner.Platform.TIKTOK_SHOP,target.platform());
        assertEquals("VN",target.market()); assertEquals("VND",target.currency());
    }
    @Test void actualOwnedShopOverridesAnyHistoricalTemplateSelection() {
        var shop=shop("MY");
        var target=CommerceTemplateShopResolver.targetFor(request(List.of("other")),shop,"org");
        assertEquals(shop.getId(),target.shopId());assertEquals("MYR",target.currency());
    }
    @Test void clientOnlyTargetMetadataCannotSneakAnUnselectedShopIntoTemplate() {
        var c=request(null);
        var forged=new CommerceBatchPlanner.Request(c.mode(),c.platform(),c.market(),c.sourceProducts(),
                c.designsPerProduct(),c.imagesPerDesign(),c.shopCount(),c.dailyListingsPerShop(),
                false,false,false,BigDecimal.ZERO,c.observationDays(),c.targetRoi(),null,
                List.of(new CommerceBatchPlanner.ShopTarget("forged",CommerceBatchPlanner.Platform.SHOPEE,"MY","MYR")),null);
        var shops=mock(ShopMapper.class);
        var normalized=new CommerceTemplateShopResolver(shops).normalize(forged,"org");
        assertTrue(normalized.shopTargets().isEmpty());
        verifyNoInteractions(shops);
    }
    @Test void explicitPreviewStillResolvesActualPlatformsMarketsAndKeepsBudgetCurrency() {
        var a=shop("MY");var b=shop("VN");var shops=mock(ShopMapper.class);
        when(shops.selectById(a.getId())).thenReturn(a);when(shops.selectById(b.getId())).thenReturn(b);
        var c=request(List.of(a.getId(),b.getId()));
        var normalized=new CommerceTemplateShopResolver(shops).normalize(c,"org");
        assertEquals(2,normalized.shopCount());
        assertEquals(List.of("MYR","VND"),normalized.shopTargets().stream().map(CommerceBatchPlanner.ShopTarget::currency).toList());
        assertEquals("MYR",normalized.budgetCurrency());
        assertEquals(2,new CommerceBatchPlanner().preview(normalized).budgetGroups().size());
    }
    @Test void executionStillRejectsForeignExpiredAndUnsupportedShops() {
        var foreign=new Shop("other-org",MarketplacePlatform.TIKTOK_SHOP,"foreign",null,null,"LOCAL","foreign","MY","");
        assertThrows(BusinessException.class,()->CommerceTemplateShopResolver.targetFor(request(null),foreign,"org"));
        var expired=shop("MY");expired.tokenExpired("expired");
        assertThrows(BusinessException.class,()->CommerceTemplateShopResolver.targetFor(request(null),expired,"org"));
        assertThrows(BusinessException.class,()->CommerceTemplateShopResolver.targetFor(request(null),shop("US"),"org"));
    }
}
