package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductListingFulfillmentServiceTest {
    Map<String,Object> warehouse(String id,String region,String name,boolean isDefault) {
        return Map.of("id",id,"type","SALES_WAREHOUSE","status","ENABLED","name",name,
                "is_default",isDefault,"address",Map.of("region_code",region));
    }
    @Test void overseasDoesNotFallBackToDefaultDomesticWarehouse() {
        var choices=List.of(warehouse("cn","CN","China",true),warehouse("ph","PH","Manila",false));
        assertEquals("ph",ProductListingFulfillmentService.selectWarehouse(choices,Map.of("originCountry","PH")));
        assertEquals("cn",ProductListingFulfillmentService.selectWarehouse(choices,Map.of("originCountry","CN")));
    }
    @Test void ambiguousWarehousesNeedNameOrUniqueDefault() {
        var choices=List.of(warehouse("one","MY","North",false),warehouse("two","MY","South",false));
        assertThrows(BusinessException.class,()->ProductListingFulfillmentService.selectWarehouse(choices,Map.of("originCountry","MY")));
        assertEquals("two",ProductListingFulfillmentService.selectWarehouse(choices,Map.of("originCountry","MY","warehouseName","South")));
    }
    @Test void warehouseOfUnknownRegionOrDisabledWarehouseIsNeverSelected() {
        var disabled=new LinkedHashMap<>(warehouse("bad","PH","Manila",true)); disabled.put("status","DISABLED");
        var unknown=new LinkedHashMap<>(warehouse("unknown","PH","Manila",true)); unknown.remove("address");
        assertThrows(BusinessException.class,()->ProductListingFulfillmentService.selectWarehouse(List.of(disabled,unknown),Map.of("originCountry","PH")));
    }
    @Test void frozenWarehouseSurvivesRenamingAndDefaultChanges() {
        var choices=List.of(warehouse("original","PH","Renamed",false),warehouse("new","PH","Manila",true));
        assertEquals("original",ProductListingFulfillmentService.selectWarehouse(choices,
                Map.of("originCountry","PH","warehouseName","Manila","resolvedWarehouseId","original")));
    }
    @Test void missingFrozenWarehouseNeverFallsBackToAnother() {
        assertThrows(BusinessException.class,()->ProductListingFulfillmentService.selectWarehouse(
                List.of(warehouse("new","PH","Manila",true)),Map.of("originCountry","PH","resolvedWarehouseId","original")));
    }
    @Test void multiCountryTargetSelectionAndDomesticOverrideRemainSeparate() {
        assertEquals("PH",ProductListingFulfillmentService.origin("TARGET","PH"));
        assertEquals("MY",ProductListingFulfillmentService.origin("TARGET","MY"));
        assertEquals("CN",ProductListingFulfillmentService.origin("CN","PH"));
        assertThrows(BusinessException.class,()->ProductListingFulfillmentService.origin("bogus","PH"));
    }
    @Test void serverSnapshotReplacesUntrustedWarehouseAndPreservesIndependentFields() {
        var body=new LinkedHashMap<String,Object>(Map.of("warehouseId","client-controlled","categoryId","123",
                "listingFulfillmentSnapshot",Map.of("organizationId","foreign")));
        var snapshot=Map.<String,Object>of("platform","TIKTOK_SHOP","organizationId","org","resolvedWarehouseId","official");
        ProductListingFulfillmentService.apply(body,snapshot);
        assertFalse(body.containsKey("warehouseId")); assertEquals("123",body.get("categoryId"));
        assertEquals(snapshot,ProductListingFulfillmentService.snapshotFrom(body));
    }
    @Test void readsAreTenantCountryAndOriginScoped() {
        var db=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service=new ProductListingFulfillmentService(db,null,null,new tools.jackson.databind.ObjectMapper());
        var user=new com.yingdao.aicommerce.auth.PlatformPrincipal("u","org","tenant","name","Name","",true,List.of(),List.of("product:view"));
        var result=service.get("SHOPEE","PH","CN",user);
        assertEquals("CN",result.get("originCountry")); assertEquals(0,result.get("version"));
        org.mockito.Mockito.verify(db).query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Map<String,Object>>>any(),
                org.mockito.ArgumentMatchers.eq("org"),org.mockito.ArgumentMatchers.eq("SHOPEE"),
                org.mockito.ArgumentMatchers.eq("PH"),org.mockito.ArgumentMatchers.eq("CN"));
    }
}
