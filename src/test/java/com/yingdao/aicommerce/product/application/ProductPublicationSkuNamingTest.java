package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductPublicationSkuNamingTest {
    @Test void explicitStylePreservesPatternSuffixAndSourceFactsAcrossShopsAndRetries() {
        var allocator = mock(ProductSkuNamingService.class);
        var codes = new HashMap<String,ProductSkuNaming.PatternCode>();
        when(allocator.assign(eq("org"), eq("source"), anyList(), anyList())).thenAnswer(call ->
                ProductSkuNaming.assign("org", "source", call.getArgument(2), call.getArgument(3),
                        key -> codes.computeIfAbsent(key, ignored -> new ProductSkuNaming.PatternCode(
                                codes.isEmpty() ? "Z90" : "Z91", codes.isEmpty() ? "1234ABCD" : "8765DCBA"))));
        var service = new ProductPublicationSkuNaming(allocator, new ObjectMapper());
        var capture = mock(ProductCapture.class);
        when(capture.getOrganizationId()).thenReturn("org");
        when(capture.getRequestFingerprint()).thenReturn("source");
        var original = List.of(sku("Black/1","M"), sku("White/1","L"), sku("Black/2","M"));
        var request = Map.<String,Object>of("merchantStyleCode","a01");
        var first = service.apply(capture, original, request);
        var next = service.apply(capture, original, request);
        assertEquals(List.of("BK-A01-M-1234ABCD","WH-A01-L-1234ABCD","BK-A01-M-8765DCBA"),
                first.stream().map(ProductCaptureSku::getSellerSku).toList());
        assertEquals(first.stream().map(ProductCaptureSku::getSellerSku).toList(),
                next.stream().map(ProductCaptureSku::getSellerSku).toList());
        var json = new ObjectMapper();
        for (int i=0;i<original.size();i++) {
            assertEquals("original", original.get(i).getSellerSku());
            var expected = json.convertValue(original.get(i), Map.class);
            var actual = json.convertValue(first.get(i), Map.class);
            expected.remove("sellerSku");actual.remove("sellerSku");
            assertEquals(expected, actual);
        }
    }

    @Test void blankKeepsOriginalCodesAndDoesNotAllocate() {
        var allocator=mock(ProductSkuNamingService.class);
        var source=List.of(sku("Black","M"));
        assertSame(source,new ProductPublicationSkuNaming(allocator,new ObjectMapper())
                .apply(mock(ProductCapture.class),source,Map.of("merchantStyleCode"," ")));
        verifyNoInteractions(allocator);
    }

    @Test void styleValidationNormalizesCaseAndRejectsUnsafeOrLongCodes() {
        var request=new HashMap<String,Object>();request.put("merchantStyleCode"," b12 ");
        ProductPublicationSkuNaming.normalize(request);assertEquals("B12",request.get("merchantStyleCode"));
        for(String invalid:List.of("A1","A001","A-1","AA1","中01","A 1","A01\nB02"))
            assertThrows(RuntimeException.class,()->ProductPublicationSkuNaming.style(invalid));
        request.put("merchantStyleCode","");ProductPublicationSkuNaming.normalize(request);assertTrue(request.isEmpty());
    }

    @Test void explicitStyleRequiresVariantsBeforePublishing() {
        var allocator=mock(ProductSkuNamingService.class);
        assertThrows(RuntimeException.class,()->new ProductPublicationSkuNaming(allocator,new ObjectMapper())
                .apply(mock(ProductCapture.class),List.of(),Map.of("merchantStyleCode","A01")));
        verifyNoInteractions(allocator);
    }

    private ProductCaptureSku sku(String color,String size) {
        return new ProductCaptureSku("org","capture",color+size,color+size,"original",color+" / "+size,
                BigDecimal.TEN,new BigDecimal("12"),5,"https://image.example/source.png","SOURCE",
                new ObjectMapper().writeValueAsString(Map.of("Color",color,"Size",size)),"{}",0);
    }
}
