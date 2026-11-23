package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductCaptureTemplateServiceTest {
    static final String IMAGE="11111111-1111-1111-1111-111111111111";
    static PlatformPrincipal user(String org, String permission) {
        return new PlatformPrincipal("u",org,"qa","qa","QA","",true,List.of(),List.of(permission));
    }
    static ProductCaptureTemplateService.Save input(long version,String hex) {
        return new ProductCaptureTemplateService.Save(version,"  纯棉 T 恤  ","  正面  ",List.of(new ProductCaptureTemplateService.Variant(
                "22222222-2222-2222-2222-222222222222"," 正面 ",IMAGE,List.of(new ProductCaptureTemplateService.Color(
                "33333333-3333-3333-3333-333333333333"," 本白 ",hex," wh "))," a01 ")));
    }
    @Test void trimsInputsAndNormalizesShortHexWithoutTrustingClientUrls() {
        var value=ProductCaptureTemplateService.normalize(input(0,"#abc"));
        assertEquals("纯棉 T 恤",value.name());assertEquals("正面",value.description());
        assertEquals("#AABBCC",value.variants().getFirst().colors().getFirst().hex());
        assertEquals("A01",value.variants().getFirst().styleCode());
        assertEquals("WH",value.variants().getFirst().colors().getFirst().colorCode());
        var invalid=new ProductCaptureTemplateService.Variant(UUID.randomUUID().toString(),"正面","https://example.com/a.png",value.variants().getFirst().colors(),"A01");
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(new ProductCaptureTemplateService.Save(0,"t","",List.of(invalid))));
        for(String hex:List.of("red","#ZZZZZZ","#12345",""))assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(input(0,hex)));
    }
    @Test void sizeRowsSupportLegacyTemplatesAndRejectDuplicateOptionsOrInvalidMeasurements() {
        assertEquals(List.of(), ProductCaptureTemplateService.normalizeSizes(null, true));
        var small = new ProductCaptureTemplateService.Size(UUID.randomUUID().toString(), " S ", "",
                new java.math.BigDecimal("88.50"), null, null, null);
        var normalized = ProductCaptureTemplateService.normalizeSizes(List.of(small), true).getFirst();
        assertEquals("S", normalized.name());
        assertEquals("S", normalized.sizeCode());
        assertEquals("88.5", normalized.chestCm().toPlainString());
        var duplicate = new ProductCaptureTemplateService.Size(UUID.randomUUID().toString(), "s", "S2", null, null, null, null);
        assertThrows(BusinessException.class, () -> ProductCaptureTemplateService.normalizeSizes(List.of(small, duplicate), true));
        var invalid = new ProductCaptureTemplateService.Size(UUID.randomUUID().toString(), "M", "M",
                java.math.BigDecimal.valueOf(-1), null, null, null);
        assertThrows(BusinessException.class, () -> ProductCaptureTemplateService.normalizeSizes(List.of(invalid), false));
    }
    @Test void unauthorizedAndReadonlyWritesStopBeforeAnyDatabaseOrStorageAccess() {
        var db=mock(JdbcTemplate.class);var archive=mock(CommerceImageArchive.class);
        var service=new ProductCaptureTemplateService(db,archive,new ObjectMapper());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(BusinessException.class,()->service.list(0,50,null)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.save(UUID.randomUUID().toString(),input(0,"#fff"),user("org","product:view"))).getStatus());
        assertThrows(BusinessException.class,()->service.list(0,50,user(null,"product:edit")));
        verifyNoInteractions(db,archive);
    }
    @Test void ossMissingFailsClosedAndOversizeOrSpoofedImagesAreRejected() {
        var db=mock(JdbcTemplate.class);var archive=mock(CommerceImageArchive.class);
        var service=new ProductCaptureTemplateService(db,archive,new ObjectMapper());var user=user("org","product:edit");
        var fake=new MockMultipartFile("file","fake.png","image/png","not a png at all".getBytes());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,()->service.upload(fake,user)).getStatus());
        when(archive.usesOss()).thenReturn(true);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(BusinessException.class,()->service.upload(fake,user)).getStatus());
        var oversized=mock(org.springframework.web.multipart.MultipartFile.class);when(oversized.isEmpty()).thenReturn(false);when(oversized.getSize()).thenReturn((long)ProductCaptureTemplateService.MAX_IMAGE_BYTES+1);
        assertThrows(BusinessException.class,()->service.upload(oversized,user));verifyNoInteractions(db);verify(archive,never()).store(anyString(),any());
    }
    @Test void duplicateVariantsAndUnboundedPayloadsAreRejected() {
        var valid=input(0,"#fff");
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(new ProductCaptureTemplateService.Save(0,"t","",List.of(valid.variants().getFirst(),valid.variants().getFirst()))));
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(new ProductCaptureTemplateService.Save(-1,"t","",valid.variants())));
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(new ProductCaptureTemplateService.Save(0,"x".repeat(51),"",valid.variants())));
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(new ProductCaptureTemplateService.Save(0,"t","",Collections.nCopies(31,valid.variants().getFirst()))));
    }
    private ProductCaptureTemplateService.Save withCodes(String style, String first, String second) {
        var colors=new ArrayList<ProductCaptureTemplateService.Color>();
        colors.add(new ProductCaptureTemplateService.Color(UUID.randomUUID().toString(),"白色","#fff",first));
        if(second!=null)colors.add(new ProductCaptureTemplateService.Color(UUID.randomUUID().toString(),"黑色","#000",second));
        return new ProductCaptureTemplateService.Save(0,"t","",List.of(new ProductCaptureTemplateService.Variant(UUID.randomUUID().toString(),"圆领",IMAGE,colors,style)));
    }
    @Test void requiresOneStyleAndRejectsMissingInvalidOrDuplicateCodes() {
        for(String code:List.of("","A1","A001","01A","款式1"))
            assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(withCodes(code,"WH",null)));
        for(String code:List.of("","WH-1","white color","黑色","X".repeat(17)))
            assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(withCodes("A01",code,null)));
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(withCodes("A01",null,null)));
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(withCodes(null,"WH",null)));
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(withCodes("A01"," wh ","WH")));
        assertThrows(BusinessException.class,()->ProductCaptureTemplateService.normalize(new ProductCaptureTemplateService.Save(0,"t","",List.of())));
    }
    @Test void preservesLeadingZeroCodesAndNormalizesCase() {
        var value=ProductCaptureTemplateService.normalize(withCodes(" a01 ","001"," bk "));
        assertEquals("A01",value.variants().getFirst().styleCode());
        assertEquals(List.of("001","BK"),value.variants().getFirst().colors().stream().map(ProductCaptureTemplateService.Color::colorCode).toList());
    }

}
