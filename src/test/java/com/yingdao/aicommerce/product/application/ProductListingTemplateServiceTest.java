package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductListingTemplateServiceTest {
    @Test void batchChartOverridesTemplateWithoutChangingItsSnapshot() {
        var snapshot=Map.<String,Object>of("enabled",true,"sizeChartImageKey","shared-chart");
        var body=new LinkedHashMap<String,Object>(Map.of("sizeChartImageKey","batch-chart"));
        ProductListingTemplateService.apply(body,snapshot);
        assertEquals("batch-chart",ProductListingTemplateService.sizeChartImageKey(body));
        assertEquals("shared-chart",snapshot.get("sizeChartImageKey"));
        body.remove("sizeChartImageKey");
        assertEquals("shared-chart",ProductListingTemplateService.sizeChartImageKey(body));
    }
    @Test void templateChartIsCapturedInSnapshotAndDisabledTemplateNeverSuppliesIt() {
        var service=spy(new ProductListingTemplateService(mock(JdbcTemplate.class),new ObjectMapper()));
        var configured=new ProductListingTemplateService.Template("SHOPEE","Template",true,"Text",
                BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,3,"tenant/chart");
        doReturn(configured).when(service).get("org-a","SHOPEE");
        var snapshot=service.snapshot("org-a","SHOPEE",capture("{}"));
        assertEquals("tenant/chart",snapshot.get("sizeChartImageKey"));
        assertEquals("tenant/chart",ProductListingTemplateService.sizeChartImageKey(Map.of("listingTemplateSnapshot",snapshot)));
        assertEquals("",ProductListingTemplateService.sizeChartImageKey(Map.of(
                "listingTemplateSnapshot",Map.of("enabled",false,"sizeChartImageKey","tenant/chart"))));
    }
    @Test void sourceMeasurementsSurviveMarketingDescriptionReplacement() {
        var json=new ObjectMapper();
        var original="Size (cm) Chest Length\\nS 80 55\\nM 84 57";
        var capture=capture(json.writeValueAsString(Map.of("originalDescription",original,"listingTemplate",Map.of("version",2))));
        assertEquals(original,ProductListingTemplateService.sourceDescription(capture,json));
        assertEquals("source description",ProductListingTemplateService.sourceDescription(capture("{}"),json));
    }
    private PlatformPrincipal principal(String org, String permission) {
        return new PlatformPrincipal("user",org,org,"name","Name","",true,List.of(),List.of(permission));
    }
    private ProductCapture capture(String normalized) {
        return new ProductCapture("org-a","user","capture-key","fingerprint","SHOPEE","https://shopee.ph/p",null,"1",
                "Women Short Sleeve Cotton T-shirt","source description","Women > T-shirts",null,"PHP",
                BigDecimal.TEN,BigDecimal.TEN,normalized,"{}");
    }
    @Test void defaultShopeeHasExactFixedParcelAndCleanDescriptionWhileTikTokIsIndependent() {
        var db = mock(JdbcTemplate.class);
        var service = new ProductListingTemplateService(db,new ObjectMapper());
        var shopee = service.get("org-a","SHOPEE");
        assertTrue(shopee.enabled());
        assertEquals(new BigDecimal("200"),shopee.weightGrams());
        assertEquals(new BigDecimal("5"),shopee.packageLengthCm());
        assertEquals(new BigDecimal("25"),shopee.packageWidthCm());
        assertEquals(new BigDecimal("25"),shopee.packageHeightCm());
        assertTrue(shopee.description().endsWith("CurveLuxe PH — “Stretch. Slay.”"));
        assertFalse(shopee.description().contains("无法匹配"));
        assertFalse(service.get("org-b","TIKTOK").enabled());
        verify(db).query(anyString(),any(RowMapper.class),eq("org-a"),eq("SHOPEE"));
        verify(db).query(anyString(),any(RowMapper.class),eq("org-b"),eq("TIKTOK_SHOP"));
    }
    @Test void unauthorizedReadAndReadOnlyWriteNeverTouchDatabase() {
        var db=mock(JdbcTemplate.class);
        var service=new ProductListingTemplateService(db,new ObjectMapper());
        assertThrows(BusinessException.class,()->service.list(principal("org-a","hr:access")));
        assertThrows(BusinessException.class,()->service.save("SHOPEE",Map.of(),principal("org-a","product:view")));
        verifyNoInteractions(db);
    }
    @Test void invalidDimensionsAndVersionFailBeforeDatabaseWrite() {
        var db=mock(JdbcTemplate.class);
        var service=new ProductListingTemplateService(db,new ObjectMapper());
        var body=new LinkedHashMap<String,Object>(Map.of("name","Template","enabled",true,
                "description","Detail","weightGrams","200","packageLengthCm","5","packageWidthCm","25","packageHeightCm","25","version",0));
        for (String value:List.of("0","-1","NaN","0.0001","1000001")) {
            body.put("weightGrams",value);
            assertThrows(BusinessException.class,()->service.save("SHOPEE",body,principal("org-a","product:edit")));
        }
        verifyNoInteractions(db);
    }
    @Test void captureConversionKeepsOriginalFactsAndCrossPlatformUsesOriginalDescription() {
        var service=new ProductListingTemplateService(mock(JdbcTemplate.class),new ObjectMapper());
        var data=new LinkedHashMap<String,Object>();
        data.put("description","Sleeve length: short sleeved Collar: Round neck");
        data.put("attributes",Map.of("Neckline","Others")); data.put("weightGrams","350"); data.put("skus",List.of("sku"));
        service.applyCapture("org-a","SHOPEE",data);
        assertEquals(ProductListingTemplateService.SHOPEE_DESCRIPTION,data.get("description"));
        assertEquals("Sleeve length: short sleeved Collar: Round neck",data.get("originalDescription"));
        assertEquals("350",data.get("weightGrams")); assertEquals(List.of("sku"),data.get("skus"));
        try {
            var snapshot=service.snapshot("org-a","TIKTOK",capture(new ObjectMapper().writeValueAsString(data)));
            assertFalse((Boolean)snapshot.get("enabled"));
            assertEquals(data.get("originalDescription"),snapshot.get("description"));
            assertFalse(snapshot.containsKey("weightGrams"));
        } catch(Exception e) { fail(e); }
    }
    @Test void serverOwnedSnapshotOverridesParcelFieldsAndProvidesActualProviderDescription() {
        var service=new ProductListingTemplateService(mock(JdbcTemplate.class),new ObjectMapper());
        var capture=capture("{}"); var body=new LinkedHashMap<String,Object>();
        body.put("weightGrams","999"); body.put("listingTemplateSnapshot",Map.of("description","forged"));
        ProductListingTemplateService.apply(body,service.snapshot("org-a","SHOPEE",capture));
        assertEquals("200",body.get("weightGrams"));
        assertEquals(ProductListingTemplateService.SHOPEE_DESCRIPTION,ProductListingTemplateService.publicationDescription(capture,body));
        ProductListingTemplateService.apply(body,Map.of());
        assertFalse(body.containsKey("listingTemplateSnapshot"));
    }
    @Test void persistedWorkflowSnapshotIsTenantScopedAndCannotChangeWithCurrentConfiguration() {
        var db=mock(JdbcTemplate.class); var service=spy(new ProductListingTemplateService(db,new ObjectMapper()));
        var snapshot=Map.<String,Object>of("enabled",true,"description","saved","weightGrams","200");
        when(db.query(anyString(),any(RowMapper.class),eq("org-a"),eq("capture-a"),eq("workflow-key")))
                .thenReturn(List.of(Map.of("listingTemplateSnapshot",snapshot)));
        assertEquals(snapshot,service.publishSnapshot("org-a","capture-a","workflow-key","SHOPEE",capture("{}")));
        verify(service,never()).get(anyString(),anyString());
    }
}
