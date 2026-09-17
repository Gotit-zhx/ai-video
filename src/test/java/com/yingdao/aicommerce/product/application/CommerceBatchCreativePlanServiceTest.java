package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceBatchCreativePlanServiceTest {
    final CommerceBatchMapper batches=mock(CommerceBatchMapper.class);
    final CommerceBatchExecutionMapper runs=mock(CommerceBatchExecutionMapper.class);
    final CommerceBatchCreativePlanMapper plans=mock(CommerceBatchCreativePlanMapper.class);
    final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
    final ProductCaptureService captures=mock(ProductCaptureService.class);
    final PlatformPrincipal user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:publish"));
    final ObjectMapper json=new ObjectMapper();
    final CommerceBatchCreativePlanService service=new CommerceBatchCreativePlanService(batches,runs,plans,templates,captures,json);

    @Test void reusedArtworksFreezeLibraryIdentityShaAndEveryMarkerGroup() throws Exception {
        surfaceDraft(SavedArtworkFixture.configuration());var library=mock(CommerceArtworkLibraryService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"artworkLibrary",library);
        String sha="7".repeat(64),storage=CommerceSurfaceTemplateInputs.key("org",sha);
        when(library.resolve("saved",user)).thenReturn(new CommerceArtworkLibraryService.ResolvedArtwork("saved",sha,storage));
        var requested=List.of(SavedArtworkFixture.selection("saved","saved"));
        var request=new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"image")),1,null,"SAVED_ARTWORK",requested);
        var result=service.save("b",request,user);assertEquals(1,result.plan().variantsPerPrint());assertEquals("SAVED_ARTWORK",result.plan().artworkSource());
        assertEquals("GROK_SCENE_V1",result.plan().renderPolicy());
        assertNull(result.plan().variationArtworkId());assertEquals(storage,result.plan().reusedArtworks().getFirst().groupArtifacts().get(SavedArtworkFixture.GROUP_A).storageKey());
        assertEquals(sha,result.plan().reusedArtworks().getFirst().groupArtifacts().get(SavedArtworkFixture.GROUP_B).contentSha256());
        verify(library,times(1)).resolve("saved",user);
        var inserted=org.mockito.ArgumentCaptor.forClass(CommerceBatchCreativePlanMapper.Row.class);verify(plans).insert(inserted.capture());when(plans.latest("org","b")).thenReturn(inserted.getValue());
        assertSame(inserted.getValue(),service.prepareStart("b",1,user));
        when(library.resolve("saved",user)).thenReturn(new CommerceArtworkLibraryService.ResolvedArtwork("saved","8".repeat(64),CommerceSurfaceTemplateInputs.key("org","8".repeat(64))));
        assertTrue(assertThrows(BusinessException.class,()->service.prepareStart("b",1,user)).getMessage().contains("指纹"));
    }
    @Test void inaccessibleLibraryArtifactCannotSavePlanOrFallbackToGeneration(){
        surfaceDraft(SavedArtworkFixture.configuration());var library=mock(CommerceArtworkLibraryService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"artworkLibrary",library);
        when(library.resolve("foreign",user)).thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,"素材不存在"));
        var request=new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"image")),1,null,"SAVED_ARTWORK",List.of(SavedArtworkFixture.selection("foreign","foreign")));
        assertThrows(BusinessException.class,()->service.save("b",request,user));verify(plans,never()).insert(any());
    }

    @Test void missingPermissionStopsBeforeAnyDataAccess() {
        assertThrows(BusinessException.class,()->service.get("b",null));
        verifyNoInteractions(batches,runs,plans,templates,captures);
    }

    @Test void captureAccessIsRecheckedWhenRestoringConfiguration() {
        when(batches.get("org","b")).thenReturn(new CommerceBatch("b","org","batch","t","key","hash","DRAFT","u",Instant.now()));
        when(batches.items("org","b")).thenReturn(List.of(new CommerceBatchItem("i","org","b","c",1,"shop","shop",1,"DRAFT",Instant.now())));
        when(captures.get("c",user)).thenThrow(new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,"denied"));
        assertThrows(BusinessException.class,()->service.get("b",user));
        verifyNoInteractions(plans);
    }

    @Test void changedCaptureVersionPreventsStartingSavedPlan() {
        var plan=new CommerceBatchCreativePlanValidator.Plan("template",List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"image")),2);
        when(plans.latest("org","b")).thenReturn(new CommerceBatchCreativePlanMapper.Row("p","org","b",1,json.writeValueAsString(plan),"u",Instant.now()));
        when(batches.items("org","b")).thenReturn(List.of(new CommerceBatchItem("i","org","b","c",1,"shop","shop",1,"DRAFT",Instant.now())));
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(2L);
        var image=mock(ProductCaptureImage.class);when(image.getId()).thenReturn("image");when(image.getOriginalUrl()).thenReturn("https://example.com/image.png");
        when(captures.get("c",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(image),List.of(),Map.of(),Map.of()));
        when(templates.snapshot("org","template")).thenReturn(new CommerceTemplateVersion("template","org","root",1,"PRINT_LAYOUT","name","{}","u",Instant.now()));
        var error=assertThrows(BusinessException.class,()->service.prepareStart("b",1,user));
        assertTrue(error.getMessage().contains("来源版本已变化"));
        verify(plans,never()).bind(anyString(),anyString());
    }

    @Test void surfaceTemplateFreezesItsSnapshotWithoutRequiringOldFrontCleanup() {
        var config=SurfaceTemplateFixture.configuration();
        var plan=new CommerceBatchCreativePlanValidator.Plan("surface-snapshot",List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"image")),2);
        var row=new CommerceBatchCreativePlanMapper.Row("p","org","b",1,json.writeValueAsString(plan),"u",Instant.now());
        when(plans.latest("org","b")).thenReturn(row);
        when(batches.items("org","b")).thenReturn(List.of(new CommerceBatchItem("i","org","b","c",1,"shop","shop",1,"DRAFT",Instant.now())));
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(1L);
        var image=mock(ProductCaptureImage.class);when(image.getId()).thenReturn("image");when(image.getOriginalUrl()).thenReturn("https://example.com/print.png");
        when(captures.get("c",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(image),List.of(),Map.of(),Map.of()));
        when(templates.snapshot("org","surface-snapshot")).thenReturn(new CommerceTemplateVersion("surface-snapshot","org","root",1,"PRINT_LAYOUT","surface",json.writeValueAsString(config),"u",Instant.now()));
        assertSame(row,service.prepareStart("b",1,user));
        when(templates.snapshot("org","surface-snapshot")).thenReturn(new CommerceTemplateVersion("surface-snapshot","org","root",1,"PRINT_LAYOUT","surface",json.writeValueAsString(SurfaceTemplateFixture.binding(config,"stale")),"u",Instant.now()));
        assertTrue(assertThrows(BusinessException.class,()->service.prepareStart("b",1,user)).getMessage().contains("重新保存"));
        verify(plans,never()).bind(anyString(),anyString());
    }

    void surfaceDraft(CommercePrintTemplateService.Configuration config) {
        when(batches.lock("org","b")).thenReturn(new CommerceBatch("b","org","batch","t","key","hash","DRAFT","u",Instant.now()));
        when(batches.items("org","b")).thenReturn(List.of(new CommerceBatchItem("i","org","b","c",1,"shop","shop",1,"DRAFT",Instant.now())));
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(1L);
        var image=mock(ProductCaptureImage.class);when(image.getId()).thenReturn("image");when(image.getOriginalUrl()).thenReturn("https://example.com/source.png");
        when(captures.get("c",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(image),List.of(),Map.of(),Map.of()));
        when(templates.snapshot("org","surface-snapshot")).thenReturn(new CommerceTemplateVersion("surface-snapshot","org","root",1,"PRINT_LAYOUT","surface",json.writeValueAsString(config),"u",Instant.now()));
    }
    @Test void singleUploadedArtworkIsAutomaticallyFrozenInSavedPlan() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        var result=service.save("b",new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",
                List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"image")),2),user);
        assertEquals(SurfaceTemplateFixture.hash('2'),result.plan().variationArtworkId());
        assertNotNull(result.printTemplateConfiguration());
        var inserted=org.mockito.ArgumentCaptor.forClass(CommerceBatchCreativePlanMapper.Row.class);verify(plans).insert(inserted.capture());
        assertEquals(SurfaceTemplateFixture.hash('2'),json.readTree(inserted.getValue().planJson()).path("variationArtworkId").asText());
    }
    @Test void multipleUploadedArtworksMustBeChosenBeforePlanIsSaved() {
        surfaceDraft(SurfaceTemplateFixture.multipleArtwork());
        var sources=List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"image"));
        assertTrue(assertThrows(BusinessException.class,()->service.save("b",new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",sources,2),user)).getMessage().contains("选择本次"));
        verify(plans,never()).insert(any());
        var result=service.save("b",new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",sources,2,SurfaceTemplateFixture.hash('5')),user);
        assertEquals(SurfaceTemplateFixture.hash('5'),result.plan().variationArtworkId());
    }

    @Test void sourceProductStyleUsesAllMarkersWithoutSelectingPlaceholderAsArtwork() {
        surfaceDraft(SurfaceTemplateFixture.multipleArtwork());
        var sources=List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"image"));
        var result=service.save("b",new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",sources,2,null,"SOURCE_PRODUCT"),user);
        assertNull(result.plan().variationArtworkId());
        assertTrue(CommerceBatchCreativePlanValidator.sourceProductStyle(result.plan()));
        var inserted=org.mockito.ArgumentCaptor.forClass(CommerceBatchCreativePlanMapper.Row.class);verify(plans).insert(inserted.capture());
        var row=inserted.getValue();assertEquals("SOURCE_PRODUCT",json.readTree(row.planJson()).path("artworkSource").asText());
        when(plans.latest("org","b")).thenReturn(row);
        assertSame(row,service.prepareStart("b",1,user));
    }
    @Test void ambiguousOrUnknownStyleSourceStopsBeforeSavingPlan() {
        surfaceDraft(SurfaceTemplateFixture.multipleArtwork());
        var sources=List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"image"));
        assertThrows(BusinessException.class,()->service.save("b",new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",sources,2,SurfaceTemplateFixture.hash('2'),"SOURCE_PRODUCT"),user));
        assertThrows(BusinessException.class,()->service.save("b",new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",sources,2,null,"UNKNOWN_MODE"),user));
        verify(plans,never()).insert(any());
    }
    @Test void historicalPlanWithoutStyleSourceKeepsItsOriginalMaterialMeaning() {
        var historical="{\"printTemplateSnapshotId\":\"t\",\"sources\":[{\"captureId\":\"c\",\"version\":1,\"imageId\":\"i\"}],\"variantsPerPrint\":2,\"variationArtworkId\":\""+SurfaceTemplateFixture.hash('2')+"\"}";
        var plan=json.readValue(historical,CommerceBatchCreativePlanValidator.Plan.class);
        assertNull(plan.artworkSource());assertFalse(CommerceBatchCreativePlanValidator.sourceProductStyle(plan));
        assertEquals(SurfaceTemplateFixture.hash('2'),plan.variationArtworkId());
    }
    ProductCaptureImage image(String id,String role,String url) {
        var image=mock(ProductCaptureImage.class);when(image.getId()).thenReturn(id);
        when(image.getCaptureId()).thenReturn("c");when(image.getOrganizationId()).thenReturn("org");
        when(image.getRoleName()).thenReturn(role);when(image.getOriginalUrl()).thenReturn(url);return image;
    }
    void sourceImages(List<ProductCaptureImage> images) {
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(1L);
        when(capture.getId()).thenReturn("c");when(capture.getOrganizationId()).thenReturn("org");
        when(captures.get("c",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),images,List.of(),Map.of(),Map.of()));
    }
    @Test void initializePrefersReadableMainPhotoAndPreservesAllTwentyVariants() {
        surfaceDraft(SurfaceTemplateFixture.multipleArtwork());
        sourceImages(List.of(image("detail","DETAIL","https://example.com/detail.png"),
                image("empty-main","MAIN"," "),image("main","MAIN","https://example.com/main.png")));
        var result=service.initialize("b","surface-snapshot",20,user);
        assertEquals(20,result.plan().variantsPerPrint());assertEquals("SOURCE_PRODUCT",result.plan().artworkSource());
        assertEquals(1,result.plan().sources().size());assertEquals("main",result.plan().sources().getFirst().imageId());
        assertEquals("SOURCE_SKU_COLORS",result.plan().outputPolicy());
        assertEquals("LOCAL_PHOTO_V1",result.plan().renderPolicy());
        assertEquals(List.of(new CommerceSourceColorResolver.Color(CommerceSourceColorResolver.DEFAULT_KEY,
                CommerceSourceColorResolver.DEFAULT_LABEL,List.of(),"main",true)),result.plan().sources().getFirst().colorTargets());
        var inserted=org.mockito.ArgumentCaptor.forClass(CommerceBatchCreativePlanMapper.Row.class);verify(plans).insert(inserted.capture());
        var stored=json.readValue(inserted.getValue().planJson(),CommerceBatchCreativePlanValidator.Plan.class);
        assertEquals(20,stored.variantsPerPrint());assertEquals("main",stored.sources().getFirst().imageId());
        verify(plans,never()).bind(anyString(),anyString());verify(runs,never()).insert(any());
    }
    @Test void initializeFallsBackToFirstReadablePhotoAndDeduplicatesShopCopies() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        when(batches.items("org","b")).thenReturn(List.of(
                new CommerceBatchItem("i","org","b","c",1,"shop","shop",1,"DRAFT",Instant.now()),
                new CommerceBatchItem("i2","org","b","c",1,"shop-two","shop",1,"DRAFT",Instant.now())));
        sourceImages(List.of(image("unreadable","MAIN",null),image("first","DETAIL","https://example.com/first.png"),image("later","DETAIL","https://example.com/later.png")));
        var result=service.initialize("b","surface-snapshot",3,user);
        assertEquals(1,result.plan().sources().size());assertEquals("first",result.plan().sources().getFirst().imageId());
        verify(plans,times(1)).insert(any());verify(plans,never()).bind(anyString(),anyString());
    }
    @Test void initializeWithoutReadableSourcePhotoCannotSaveOrStart() {
        surfaceDraft(SurfaceTemplateFixture.configuration());sourceImages(List.of(image("bad","MAIN"," ")));
        assertTrue(assertThrows(BusinessException.class,()->service.initialize("b","surface-snapshot",20,user)).getMessage().contains("没有可提取的原图"));
        verify(plans,never()).insert(any());verify(plans,never()).bind(anyString(),anyString());verify(runs,never()).insert(any());
    }
    @Test void initializeDoesNotSilentlyClampMoreThanTwentyVariants() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        assertTrue(assertThrows(BusinessException.class,()->service.initialize("b","surface-snapshot",21,user)).getMessage().contains("1至20"));
        verify(plans,never()).insert(any());
    }

    ProductCaptureSku sku(String key,String color,String size,String url,String source,int order) {
        return new ProductCaptureSku("org","c",key,null,null,key,BigDecimal.ONE,null,1,url,source,
                json.writeValueAsString(color==null?Map.of("Size",size):Map.of("Color",color,"Size",size)),"{}",order);
    }
    void sourceSkus(List<ProductCaptureSku> skus,List<ProductCaptureImage> images) {
        var capture=mock(ProductCapture.class);when(capture.getId()).thenReturn("c");when(capture.getOrganizationId()).thenReturn("org");
        when(capture.getVersion()).thenReturn(1L);
        when(captures.get("c",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,skus,images,List.of(),Map.of(),Map.of()));
    }
    CommerceBatchCreativePlanService.Request sourceRequest(int version,List<CommerceBatchCreativePlanValidator.Selection> selected) {
        return new CommerceBatchCreativePlanService.Request(version,"surface-snapshot",selected,4,null,"SOURCE_PRODUCT");
    }
    CommerceBatchCreativePlanMapper.Row stored(CommerceBatchCreativePlanValidator.Plan plan,int version) {
        return new CommerceBatchCreativePlanMapper.Row("p","org","b",version,json.writeValueAsString(plan),"u",Instant.now());
    }

    @Test void savesOneFrozenTargetPerOriginalColorAcrossSizesAndIgnoresClientTargets() {
        surfaceDraft(SurfaceTemplateFixture.multipleArtwork());
        var images=new ArrayList<ProductCaptureImage>();images.add(image("main","MAIN","https://example.com/main.png"));
        var skus=new ArrayList<ProductCaptureSku>();
        for(String color:List.of("Black","White","Blue")) {
            var reference=image(color,"VARIANT","https://example.com/"+color+".png");images.add(reference);
            for(String size:List.of("S","M","L"))skus.add(sku(color+"-"+size,color,size,reference.getOriginalUrl(),"SKU",skus.size()));
        }
        sourceSkus(skus,images);
        var forged=new CommerceSourceColorResolver.Color("sku-color:red","Red",List.of("foreign-sku"),"foreign-image",true);
        var requested=new CommerceBatchCreativePlanValidator.Selection("c",1,"main",List.of(),List.of(forged));
        var result=service.save("b",sourceRequest(0,List.of(requested)),user);
        assertEquals("SOURCE_SKU_COLORS",result.plan().outputPolicy());assertEquals(4,result.plan().variantsPerPrint());
        var frozen=result.plan().sources().getFirst();assertEquals("main",frozen.imageId());assertEquals(1,frozen.version());
        assertEquals(List.of("Black","White","Blue"),frozen.colorTargets().stream().map(CommerceSourceColorResolver.Color::label).toList());
        for(var color:frozen.colorTargets()) {
            assertEquals(color.label(),color.imageId());assertTrue(color.hasColorReference());
            assertEquals(List.of(color.label()+"-S",color.label()+"-M",color.label()+"-L"),color.skuKeys());
        }
        verify(captures,times(1)).get("c",user);
        var inserted=org.mockito.ArgumentCaptor.forClass(CommerceBatchCreativePlanMapper.Row.class);verify(plans).insert(inserted.capture());
        assertEquals(result.plan(),json.readValue(inserted.getValue().planJson(),CommerceBatchCreativePlanValidator.Plan.class));
    }

    @Test void missingExplicitColorReferenceStopsBeforeSavingInsteadOfUsingSelectedMainPhoto() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        var main=image("main","MAIN","https://example.com/main.png");
        sourceSkus(List.of(sku("black-s","Black","S",main.getOriginalUrl(),"PRODUCT_MAIN",0)),List.of(main));
        var error=assertThrows(BusinessException.class,()->service.save("b",sourceRequest(0,List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"main"))),user));
        assertTrue(error.getMessage().contains("Black"));assertTrue(error.getMessage().contains("不会猜测颜色"));
        verify(plans,never()).insert(any());verify(runs,never()).insert(any());
    }

    @Test void foreignAndUnreadableImagesCannotSupplyAColorReference() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        var main=image("main","MAIN","https://example.com/main.png");
        var wrongCapture=image("foreign-capture","VARIANT","https://example.com/black.png");when(wrongCapture.getCaptureId()).thenReturn("other");
        var wrongOrg=image("foreign-org","VARIANT",wrongCapture.getOriginalUrl());when(wrongOrg.getOrganizationId()).thenReturn("other");
        var unreadable=image("unreadable","VARIANT"," ");
        sourceSkus(List.of(sku("black-s","Black","S",wrongCapture.getOriginalUrl(),"SKU",0)),List.of(main,wrongCapture,wrongOrg,unreadable));
        assertTrue(assertThrows(BusinessException.class,()->service.save("b",sourceRequest(0,List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"main"))),user)).getMessage().contains("缺少本商品"));
        sourceSkus(List.of(sku("black-s","Black","S"," ","SKU",0)),List.of(main,unreadable));
        assertThrows(BusinessException.class,()->service.save("b",sourceRequest(0,List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"main"))),user));
        verify(plans,never()).insert(any());
    }

    @Test void noColorDimensionKeepsOneSelectedOriginalPhotoDespiteManySizesAndPhotos() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        var first=image("first","MAIN","https://example.com/first.png");var selected=image("selected","DETAIL","https://example.com/selected.png");
        sourceSkus(List.of(sku("s",null,"S",first.getOriginalUrl(),"SKU",0),sku("m",null,"M",first.getOriginalUrl(),"SKU",1)),List.of(first,selected));
        var result=service.save("b",sourceRequest(0,List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"selected"))),user);
        assertEquals(List.of(new CommerceSourceColorResolver.Color(CommerceSourceColorResolver.DEFAULT_KEY,
                CommerceSourceColorResolver.DEFAULT_LABEL,List.of("s","m"),"selected",true)),result.plan().sources().getFirst().colorTargets());
    }

    @Test void resavingHistoricalDraftExplicitlyFreezesNewColorPolicyAndIncrementsVersion() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        var source=new CommerceBatchCreativePlanValidator.Selection("c",1,"image");
        var old=new CommerceBatchCreativePlanValidator.Plan("surface-snapshot",List.of(source),2,null,"SOURCE_PRODUCT");
        when(plans.latest("org","b")).thenReturn(stored(old,7));
        var result=service.save("b",sourceRequest(7,List.of(source)),user);
        assertEquals(8,result.version());assertEquals("SOURCE_SKU_COLORS",result.plan().outputPolicy());
        assertEquals(1,result.plan().sources().getFirst().colorTargets().size());
        assertNull(old.outputPolicy());assertTrue(old.sources().getFirst().colorTargets().isEmpty());
    }

    @Test void startingHistoricalPlanDoesNotUpgradeOrRequireNewSkuColorReferences() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        var main=image("main","MAIN","https://example.com/main.png");
        sourceSkus(List.of(sku("black-s","Black","S",main.getOriginalUrl(),"PRODUCT_MAIN",0)),List.of(main));
        var plan=new CommerceBatchCreativePlanValidator.Plan("surface-snapshot",List.of(new CommerceBatchCreativePlanValidator.Selection("c",1,"main")),2,null,"SOURCE_PRODUCT");
        var row=stored(plan,3);when(plans.latest("org","b")).thenReturn(row);
        assertSame(row,service.prepareStart("b",3,user));
        assertNull(json.readValue(row.planJson(),CommerceBatchCreativePlanValidator.Plan.class).outputPolicy());
        verify(plans,never()).insert(any());
    }

    @Test void startingColorPlanPreservesFrozenGroupsAndRejectsLostImageReferences() {
        surfaceDraft(SurfaceTemplateFixture.configuration());
        var main=image("main","MAIN","https://example.com/main.png");var black=image("black","VARIANT","https://example.com/black.png");
        sourceSkus(List.of(sku("new-white","White","L",main.getOriginalUrl(),"SKU",0)),List.of(main,black));
        var color=new CommerceSourceColorResolver.Color("sku-color:black","Black",List.of("original-black-s","original-black-m"),"black",true);
        var selection=new CommerceBatchCreativePlanValidator.Selection("c",1,"main",List.of(),List.of(color));
        var plan=new CommerceBatchCreativePlanValidator.Plan("surface-snapshot",List.of(selection),2,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS");
        var row=stored(plan,2);when(plans.latest("org","b")).thenReturn(row);
        assertSame(row,service.prepareStart("b",2,user));
        assertEquals(plan,json.readValue(row.planJson(),CommerceBatchCreativePlanValidator.Plan.class));
        sourceSkus(List.of(),List.of(main));
        assertTrue(assertThrows(BusinessException.class,()->service.prepareStart("b",2,user)).getMessage().contains("Black"));
        verify(plans,never()).insert(any());
    }
}
