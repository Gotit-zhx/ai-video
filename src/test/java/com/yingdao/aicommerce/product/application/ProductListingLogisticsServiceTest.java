package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductListingLogisticsServiceTest {
    JdbcTemplate db;
    ShopAuthorizationService shops;
    ShopeeOfficialClient official;
    ProductListingLogisticsService service;
    MarketplaceAccess.Connection connection;
    PlatformPrincipal user = user("product:edit");
    static PlatformPrincipal user(String permission) {
        return new PlatformPrincipal("user","org-a","tenant","name","Name","",true,List.of(),List.of(permission));
    }
    static List<ProductListingLogisticsService.Channel> channels() {
        return List.of(new ProductListingLogisticsService.Channel("100","Shopee Self Pick-up",true,false),
                new ProductListingLogisticsService.Channel("200","Standard Local",true,false),
                new ProductListingLogisticsService.Channel("300","Unavailable",false,false));
    }
    @BeforeEach void setup() {
        db=mock(JdbcTemplate.class);shops=mock(ShopAuthorizationService.class);official=mock(ShopeeOfficialClient.class);
        service=new ProductListingLogisticsService(db,shops,official,new ObjectMapper());
        var shop=mock(Shop.class);when(shop.getId()).thenReturn("shop-a");when(shop.getOrganizationId()).thenReturn("org-a");
        connection=new MarketplaceAccess.Connection(shop,null);
        when(shops.connectionForPublishing("org-a","shop-a",MarketplacePlatform.SHOPEE)).thenReturn(connection);
        when(official.shopGet("/api/v2/logistics/get_channel_list",connection,Map.of()))
                .thenReturn(Map.of("response",Map.of("logistics_channel_list",List.of(
                        Map.of("logistics_channel_id",100,"logistics_channel_name","Shopee Self Pick-up","enabled",true),
                        Map.of("logistics_channel_id",200,"logistics_channel_name","Standard Local","enabled",true)))));
    }
    @Test void selectsEveryOpenChannelWithoutHardcodingFreightAndExplicitlyDisablesUnselectedChannels() {
        assertEquals(List.of(Map.of("logistic_id",100L,"enabled",true),Map.of("logistic_id",200L,"enabled",true)),
                ProductListingLogisticsService.select(channels(),new ProductListingLogisticsService.Config("ALL_ENABLED",List.of(),0)));
        assertEquals(List.of(Map.of("logistic_id",100L,"enabled",false),Map.of("logistic_id",200L,"enabled",true)),
                ProductListingLogisticsService.select(channels(),new ProductListingLogisticsService.Config("SELECTED",List.of("200"),1)));
        for(var ids:List.of(List.<String>of(),List.of("300"),List.of("999")))
            assertThrows(BusinessException.class,()->ProductListingLogisticsService.select(channels(),
                    new ProductListingLogisticsService.Config("SELECTED",ids,1)));
    }
    @Test void defaultAndSavedReadsAreScopedToOrganizationAndDestinationShop() {
        var view=service.get("shop-a",user);
        assertEquals("ALL_ENABLED",view.mode());assertEquals(0,view.version());
        assertTrue(view.channels().stream().allMatch(ProductListingLogisticsService.Channel::selected));
        verify(db).query(anyString(),any(RowMapper.class),eq("org-a"),eq("shop-a"));
        verify(shops).connectionForPublishing("org-a","shop-a",MarketplacePlatform.SHOPEE);
    }
    @Test void missingPermissionsNeverReadOrWriteOfficialChannelsOrDatabase() {
        assertThrows(BusinessException.class,()->service.get("shop-a",user("hr:access")));
        assertThrows(BusinessException.class,()->service.shops(user("hr:access")));
        assertThrows(BusinessException.class,()->service.save(Map.of(),user("product:view")));
        verifyNoInteractions(db,shops,official);
    }
    @Test void foreignSnapshotStopsBeforeOfficialApi() {
        for(var scope:List.of(Map.of("organizationId","org-b","shopId","shop-a"),Map.of("organizationId","org-a","shopId","shop-b")))
            assertThrows(BusinessException.class,()->service.resolve(connection,new LinkedHashMap<>(scope)));
        verifyNoInteractions(db,official);
    }
    @Test void saveChecksActualDestinationChannelsAndUsesOptimisticVersion() {
        var body=Map.<String,Object>of("shopId","shop-a","mode","SELECTED","channelIds",List.of("200"),"version",4);
        when(db.update(anyString(),eq("SELECTED"),eq("[\"200\"]"),eq("user"),eq("org-a"),eq("shop-a"),eq(4L))).thenReturn(1);
        service.save(body,user);
        verify(db).update(contains("AND version=?"),eq("SELECTED"),eq("[\"200\"]"),eq("user"),eq("org-a"),eq("shop-a"),eq(4L));
        when(db.update(anyString(),eq("SELECTED"),eq("[\"200\"]"),eq("user"),eq("org-a"),eq("shop-a"),eq(4L))).thenReturn(0);
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(BusinessException.class,()->service.save(body,user)).getStatus());
    }
    @Test void invalidOrClosedSelectionNeverPersists() {
        for(var ids:List.of(List.<String>of(),List.of("300"),List.of("999"),List.of("100","100"))) {
            assertThrows(BusinessException.class,()->service.save(Map.of("shopId","shop-a","mode","SELECTED","channelIds",ids,"version",0),user));
        }
        verifyNoInteractions(db);
    }
    @Test void workflowRetryUsesSavedShippingWithoutReadingCurrentConfig() {
        var saved=Map.<String,Object>of("organizationId","org-a","shopId","shop-a","mode","SELECTED","channelIds",List.of("200"),"version",3);
        when(db.query(anyString(),any(RowMapper.class),eq("org-a"),eq("capture-a"),eq("workflow-key")))
                .thenReturn(List.of(Map.of("listingLogisticsSnapshot",saved)));
        assertEquals(saved,service.publishSnapshot("org-a","capture-a","workflow-key","shop-a"));
        verify(db,never()).query(anyString(),any(RowMapper.class),eq("org-a"),eq("shop-a"));
        var body=new LinkedHashMap<String,Object>(Map.of("listingLogisticsSnapshot",Map.of("forged",true),"logisticInfo",List.of("forged")));
        ProductListingLogisticsService.apply(body,saved);
        assertEquals(saved,body.get("listingLogisticsSnapshot"));assertFalse(body.containsKey("logisticInfo"));
        ProductListingLogisticsService.apply(body,Map.of());assertFalse(body.containsKey("listingLogisticsSnapshot"));
    }
}
