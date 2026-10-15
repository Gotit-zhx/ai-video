package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CommerceModuleIsolationTest {
    @AfterEach void reset() { RequestContextHolder.resetRequestAttributes(); }
    void module(String value) {
        var request=new MockHttpServletRequest();
        request.addHeader("X-Commerce-Module",value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    ProductCapture capture() {
        return new ProductCapture("org","user","source-key","hash","SHOPEE","https://shopee.ph/source",
                null,"123","Source","Description","Shirt",null,"PHP",BigDecimal.TEN,BigDecimal.TEN,"{}","{}");
    }
    PlatformPrincipal user() {
        return new PlatformPrincipal("user","org","org","user","User","",true,List.of(),
                List.of("product:view","product:edit","product:publish"));
    }
    @Test void namespacesAreValidatedAndDefaultToCapture() {
        assertEquals("CAPTURE",CommerceModule.current());
        for(var value:List.of("CAPTURE","FISSION","TEMPLATES","ADS","ASSETS")) {
            module(value);assertEquals(value,CommerceModule.current());
        }
        module("other-org");assertThrows(BusinessException.class,CommerceModule::current);
    }
    @Test void templateQueriesAlwaysIncludeOrganizationPlatformAndIndependentModule() {
        var db=mock(JdbcTemplate.class);
        var service=new ProductListingTemplateService(db,new ObjectMapper());
        for(var value:List.of("CAPTURE","FISSION","TEMPLATES","ADS","ASSETS")){
            module(value);service.get("org","SHOPEE");
            verify(db).query(contains("data_module=?"),any(RowMapper.class),eq("org"),eq("SHOPEE"),eq(value));
        }
    }
    @Test void backgroundPublicationUsesCaptureOwnershipNotOpenPage() {
        var db=mock(JdbcTemplate.class);
        var service=new ProductListingTemplateService(db,new ObjectMapper());
        var capture=capture();capture.assignModule("FISSION");
        module("CAPTURE");
        var snapshot=service.snapshot("org","SHOPEE",capture);
        assertEquals("FISSION",snapshot.get("dataModule"));
        verify(db).query(anyString(),any(RowMapper.class),eq("org"),eq("SHOPEE"),eq("FISSION"));
    }
    @Test void templateUpdatesCannotWriteAnotherModuleOrOrganization() {
        var db=mock(JdbcTemplate.class);
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        var service=new ProductListingTemplateService(db,new ObjectMapper());
        var body=Map.<String,Object>of("name","Fission","enabled",false,"description","",
                "sizeChartImageKey","","version",3,"weightGrams",200,
                "packageLengthCm",5,"packageWidthCm",25,"packageHeightCm",25);
        module("FISSION");service.save("SHOPEE",body,user());
        verify(db).update(contains("AND data_module=?"),eq("Fission"),eq(false),eq(""),isNull(),
                eq(BigDecimal.valueOf(200).stripTrailingZeros()),eq(BigDecimal.valueOf(5)),eq(BigDecimal.valueOf(25)),
                eq(BigDecimal.valueOf(25)),eq("user"),eq("org"),eq("SHOPEE"),eq(3L),eq("FISSION"));
    }
    @Test void importingCreatesNewCaptureAndImageIdsWithoutEditingSourceRows() {
        var db=mock(JdbcTemplate.class);var source=mock(ProductCaptureService.class);
        var captures=mock(ProductCaptureMapper.class);var images=mock(ProductCaptureImageMapper.class);
        var skus=mock(ProductCaptureSkuMapper.class);var original=capture();
        var image=new ProductCaptureImage("org",original.getId(),"https://images.example/test.png",
                "MAIN",null,0,"image/png","{}");
        var sku=new ProductCaptureSku("org",original.getId(),"sku",null,null,"Red",
                BigDecimal.TEN,BigDecimal.TEN,2,"https://images.example/test.png","DOM","{}","{}",0);
        when(source.get(eq(original.getId()),any(PlatformPrincipal.class))).thenReturn(new ProductCaptureService.CaptureDetail(
                original,List.of(sku),List.of(image),List.of(),Map.of(),Map.of()));
        var service=new CommerceSourceCopyService(db,source,captures,images,skus,new ObjectMapper(),
                mock(CommerceImageArchive.class),mock(MarketplaceImageDownloader.class));
        var result=service.copy("batch",original.getId(),user());
        assertNotEquals(original.getId(),result.getId());assertEquals("FISSION",result.getDataModule());
        assertEquals("CAPTURE",original.getDataModule());
        var cloned=ArgumentCaptor.forClass(ProductCaptureImage.class);
        verify(images).insert(cloned.capture());
        assertNotEquals(image.getId(),cloned.getValue().getId());
        assertEquals(result.getId(),cloned.getValue().getCaptureId());
        verify(captures).insert(result);
        verify(captures,never()).updateById(result);
        verify(captures,never()).updateById(original);
    }
    @Test void imageIdReferencesAreRemappedStructurallyWithoutChangingOtherText() {
        var value=Map.of("sizeChartImageId","old","nested",List.of(Map.of("id","old","text","old caption")));
        var result=(Map<?,?>)CommerceSourceCopyService.remap(value,Map.of("old","new"));
        assertEquals("new",result.get("sizeChartImageId"));
        assertEquals(List.of(Map.of("id","new","text","old caption")),result.get("nested"));
        assertEquals("old",value.get("sizeChartImageId"));
    }
    @Test void copiedProcessedImageKeepsSkuAssociationAndIndependentArchivedBytes() {
        var db=mock(JdbcTemplate.class);var source=mock(ProductCaptureService.class);
        var captures=mock(ProductCaptureMapper.class);var images=mock(ProductCaptureImageMapper.class);
        var skus=mock(ProductCaptureSkuMapper.class);var original=capture();
        var image=new ProductCaptureImage("org",original.getId(),"https://images.example/test.png",
                "MAIN",null,0,"image/png","{}");
        image.processed("/api/v1/product-watermark/assets/check");
        var sku=new ProductCaptureSku("org",original.getId(),"sku",null,null,"Red",
                BigDecimal.TEN,BigDecimal.TEN,2,image.getOriginalUrl(),"DOM","{}","{}",0);
        when(source.get(eq(original.getId()),any())).thenReturn(new ProductCaptureService.CaptureDetail(
                original,List.of(sku),List.of(image),List.of(),Map.of(),Map.of()));
        var archive=mock(CommerceImageArchive.class);var downloader=mock(MarketplaceImageDownloader.class);
        byte[] bytes=new byte[12];
        when(downloader.downloadProductImage(image.getProcessedUrl(),image))
                .thenReturn(new MarketplaceImageDownloader.ImageBinary(bytes,"image/png",".png"));
        when(archive.store("org",bytes)).thenReturn(new CommerceImageArchive.Archived("tenant/hash","hash","image/png",12));
        var result=new CommerceSourceCopyService(db,source,captures,images,skus,new ObjectMapper(),archive,downloader)
                .copy("batch",original.getId(),user());
        var clonedImage=ArgumentCaptor.forClass(ProductCaptureImage.class);
        var clonedSku=ArgumentCaptor.forClass(ProductCaptureSku.class);
        verify(images).insert(clonedImage.capture());verify(skus).insert(clonedSku.capture());
        assertEquals(clonedImage.getValue().getOriginalUrl(),clonedSku.getValue().getImageUrl());
        assertEquals("/api/v1/commerce-publish-images/tenant/hash",clonedImage.getValue().getProcessedUrl());
        assertEquals("READY",result.getStatus());
        verify(db).update(contains("SELECT organization_id,?,?,?"),eq(result.getId()),eq(0L),
                eq(clonedImage.getValue().getId()),eq("user"),eq("org"),eq(original.getId()),eq(0L),eq(image.getId()));
    }
}
