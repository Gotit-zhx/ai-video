package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductListingWarehouseServiceTest {
    PlatformPrincipal user(String permission){return new PlatformPrincipal("u","org","org","u","u","",true,List.of("STAFF"),List.of(permission));}
    @Test void deniedReadersNeverAccessCredentialsOrWarehouseApi(){
        var db=mock(JdbcTemplate.class);var shops=mock(ShopAuthorizationService.class);var client=mock(TikTokShopOfficialClient.class);
        var service=new ProductListingWarehouseService(db,shops,client);
        assertThrows(RuntimeException.class,()->service.get("shop",user("finance:view")));
        assertThrows(RuntimeException.class,()->service.save(Map.of(),user("product:view")));
        verifyNoInteractions(db,shops,client);
    }
    @Test void readsAreScopedByCompanyShopAndModule(){
        var db=mock(JdbcTemplate.class);
        var service=new ProductListingWarehouseService(db,mock(ShopAuthorizationService.class),mock(TikTokShopOfficialClient.class));
        assertEquals("",service.config("org-a","shop-a","CAPTURE").warehouseId());
        service.config("org-a","shop-a","FISSION");
        verify(db).query(contains("organization_id=? AND shop_id=? AND data_module=?"),any(org.springframework.jdbc.core.RowMapper.class),
                eq("org-a"),eq("shop-a"),eq("CAPTURE"));
        verify(db).query(contains("organization_id=? AND shop_id=? AND data_module=?"),any(org.springframework.jdbc.core.RowMapper.class),
                eq("org-a"),eq("shop-a"),eq("FISSION"));
    }
    @Test void obsoleteSelectionIsVisibleForCorrectionInsteadOfSilentlyChangingShippingLocation(){
        var capability=new TikTokShopListingCapabilityResolver.Capability("LOCAL",null,List.of());
        var view=ProductListingWarehouseService.view("shop",new ProductListingWarehouseService.Config("old",3),
                List.of(Map.of("id","current","type","SALES_WAREHOUSE","effect_status","ENABLED","name","海外仓")),capability);
        assertEquals("old",view.warehouseId());assertEquals("",view.resolvedWarehouseId());
        assertEquals(1,view.warehouses().size());assertTrue(view.error().contains("已停用"));
    }
}
