package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class CommerceBatchArtworkSurfaceTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final CommerceBatchCreativePlanMapper plans=mock(CommerceBatchCreativePlanMapper.class);
    final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
    final ProductCaptureService captures=mock(ProductCaptureService.class);
    final CommerceImageArchive archive=mock(CommerceImageArchive.class);
    final ModelConfigService models=mock(ModelConfigService.class);
    final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);
    final CommerceBatchArtworkLedger ledger=mock(CommerceBatchArtworkLedger.class);
    final CommerceCaptureSourceArchiveService sourceArchives=mock(CommerceCaptureSourceArchiveService.class);
    final ObjectMapper json=new ObjectMapper();
    final PlatformPrincipal user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:edit","product:publish"));
    final CommerceBatchExecution run=new CommerceBatchExecution("run","org","batch","u","MOCKUP","RUNNING",null,"lease",Instant.now(),Instant.now(),Instant.now());
    final CommerceBatchArtworkService service=new CommerceBatchArtworkService(db,plans,templates,captures,archive,
            mock(MarketplaceImageDownloader.class),models,gateway,ledger,mock(CommerceBatchExecutionService.class),json,
            sourceArchives);

    @org.junit.jupiter.api.BeforeEach void img2Selection(){
        when(models.transparentArtworkConnection("org")).thenAnswer(ignored->models.imageConnection("org"));
    }

    void configure(CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs) throws Exception {
        configure(config,jobs,1,null);
    }
    void configure(CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs,int variants,String artwork) throws Exception {
        configure(config,jobs,variants,artwork,null);
    }
    void configure(CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs,int variants,String artwork,String sourceType) throws Exception {
        var plan=new CommerceBatchCreativePlanValidator.Plan("fixed-snapshot",List.of(new CommerceBatchCreativePlanValidator.Selection("capture",1,"image")),variants,artwork,sourceType);
        when(plans.forExecution("org","run")).thenReturn(new CommerceBatchCreativePlanMapper.Row("plan","org","batch",1,json.writeValueAsString(plan),"u",Instant.now()));
        when(templates.snapshot("org","fixed-snapshot")).thenReturn(new CommerceTemplateVersion("fixed-snapshot","org","template",1,"PRINT_LAYOUT","source garment",json.writeValueAsString(config),"u",Instant.now()));
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(1L);
        var image=mock(ProductCaptureImage.class);when(image.getId()).thenReturn("image");
        when(captures.get("capture",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(image),List.of(),Map.of(),Map.of()));
        byte[] blank=SurfaceTemplateFixture.blank(),visible=insetUv(SurfaceTemplateFixture.visibleUv()),hidden=SurfaceTemplateFixture.hiddenUv();
        byte[] generated=nativeArtwork(blank);
        when(archive.read(eq("org"),anyString())).thenAnswer(call->{String key=call.getArgument(1);return SurfaceTemplateFixture.reference(
                key.endsWith(SurfaceTemplateFixture.hash('e'))?visible:key.endsWith(SurfaceTemplateFixture.hash('f'))?hidden:
                        key.endsWith(SurfaceTemplateFixture.hash('4'))||key.endsWith(SurfaceTemplateFixture.hash('7'))?generated:blank);});
        when(db.queryForList(startsWith("SELECT id,capture_id,kind"),eq("run"),eq("org"))).thenReturn(jobs);
    }

    private static byte[] nativeArtwork(byte[] content) throws Exception {
        var original=CommerceMockupComposer.decode(content);
        int[][] pixels=new int[64][64];
        for(int y=8;y<56;y++)for(int x=8;x<56;x++)
            pixels[y][x]=original.getRGB((x-8)*original.getWidth()/48,(y-8)*original.getHeight()/48);
        return CommerceSurfaceMockupComposerTest.png(pixels);
    }
    private static byte[] insetUv(byte[] bytes) throws Exception {
        var original=CommerceMockupComposer.decode(bytes);
        int[][] pixels=new int[original.getHeight()][original.getWidth()];
        for(int y=0;y<original.getHeight();y++)for(int x=0;x<original.getWidth();x++) {
            int pixel=original.getRGB(x,y);
            if((pixel>>>24)==0)continue;
            // Sample inside the native print's clear margin. The same UV samples still
            // address the original quadrants of uploaded 2x2 fixtures, preserving geometry assertions.
            int u=64+Math.round(((pixel>>>16)&255)*.5f),v=64+Math.round(((pixel>>>8)&255)*.5f);
            pixels[y][x]=(pixel&0xff0000ff)|(u<<16)|(v<<8);
        }
        return CommerceSurfaceMockupComposerTest.png(pixels);
    }
    private static Map<String,byte[]> nativeAssets() throws Exception {
        var assets=RepeatedSurfaceArtworkFixture.assets();
        for(char id:new char[]{'4','7'})assets.put(SurfaceTemplateFixture.hash(id),nativeArtwork(assets.get(SurfaceTemplateFixture.hash(id))));
        for(char id:new char[]{'e','9'})assets.put(SurfaceTemplateFixture.hash(id),insetUv(assets.get(SurfaceTemplateFixture.hash(id))));
        return assets;
    }
    Map<String,Object> variant() {
        return Map.of("id","variant","capture_id","capture","kind","VARIATE","variant_index",1,"status","READY",
                "storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('4')));
    }
    Map<String,Object> uploaded() {
        return Map.of("id","uploaded","capture_id","capture","kind","EXTRACT","variant_index",0,"status","READY",
                "storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('2')));
    }

    @Test void freezesBackUvGuideAndAllFourInputsWithoutCreatingFrontJob() throws Exception {
        configure(SurfaceTemplateFixture.configuration(),List.of(uploaded(),variant()));
        when(models.imageConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                "model","http://unused","gpt-image-2","OPENAI_COMPATIBLE","CUSTOM","unit-only")));
        String guideKey=CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('3'));
        when(archive.store(eq("org"),any())).thenReturn(new CommerceImageArchive.Archived(guideKey,SurfaceTemplateFixture.hash('3'),"image/png",100));
        when(db.queryForMap(startsWith("SELECT * FROM commerce_batch_artwork_jobs"),eq("run"),anyString()))
                .thenReturn(Map.of("id","persisted-job","status","UNKNOWN"));
        var result=service.execute(run,user);
        assertEquals("UNKNOWN",result.pendingStatus());
        var insertion=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("INSERT IGNORE INTO commerce_batch_artwork_jobs")).findFirst().orElseThrow();
        Object[] arguments=insertion.getArguments();
        assertEquals("MOCKUP_BACK",arguments[6]);
        assertTrue(((String)arguments[9]).contains("背面"));
        var refs=json.readTree((String)arguments[12]);
        assertEquals(4,refs.size());
        assertEquals(CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('a')),refs.get(0).asText());
        assertEquals(CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('b')),refs.get(1).asText());
        assertEquals(guideKey,refs.get(2).asText());
        assertEquals(variant().get("storage_key"),refs.get(3).asText());
        verify(templates).snapshot("org","fixed-snapshot");
        verifyNoInteractions(gateway,ledger);
    }

    @Test void unknownBackSubmissionKeepsItsExistingGuideAndDoesNotSubmitAgain() throws Exception {
        configure(SurfaceTemplateFixture.configuration(),List.of(uploaded(),variant(),Map.of("id","job","capture_id","capture",
                "kind","MOCKUP_BACK","variant_index",1,"status","UNKNOWN")));
        var result=service.execute(run,user);
        assertEquals("UNKNOWN",result.pendingStatus());
        verify(archive,never()).store(anyString(),any());
        verifyNoInteractions(models,gateway,ledger);
    }

    @Test void topOnlyPrintCreatesTopJobWithFrontAppearanceAndGuideCamera() throws Exception {
        var uv=new HashMap<>(SurfaceTemplateFixture.sides(SurfaceTemplateFixture.hash('f')));uv.put("TOP",SurfaceTemplateFixture.hash('e'));
        configure(SurfaceTemplateFixture.withUvViews(SurfaceTemplateFixture.configuration(),uv),List.of(uploaded(),variant()));
        when(models.imageConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                "model","http://unused","gpt-image-2","OPENAI_COMPATIBLE","CUSTOM","unit-only")));
        String guideKey=CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('3'));
        when(archive.store(eq("org"),any())).thenReturn(new CommerceImageArchive.Archived(guideKey,SurfaceTemplateFixture.hash('3'),"image/png",100));
        when(db.queryForMap(startsWith("SELECT * FROM commerce_batch_artwork_jobs"),eq("run"),anyString()))
                .thenReturn(Map.of("id","persisted-job","status","UNKNOWN"));
        assertEquals("UNKNOWN",service.execute(run,user).pendingStatus());
        var insertion=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("INSERT IGNORE INTO commerce_batch_artwork_jobs")).findFirst().orElseThrow();
        Object[] args=insertion.getArguments();assertEquals("MOCKUP_TOP",args[6]);
        assertTrue(((String)args[9]).contains("不是当前俯视或仰视机位的照片"));
        var refs=json.readTree((String)args[12]);assertEquals(4,refs.size());
        assertEquals(CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('b')),refs.get(1).asText());
        assertEquals(guideKey,refs.get(2).asText());verifyNoInteractions(gateway,ledger);
    }

    @Test void supplementalSamplePromptDoesNotInventMatchingPhotosOrAFourthReference() {
        for(String side:List.of("TOP","BOTTOM")){
            String prompt=CommerceBatchArtworkService.surfacePrompt(side,false);
            assertTrue(prompt.contains("正面纯色图，仅作为版型、颜色和材质参考"));
            assertTrue(prompt.contains("以图3相同方位和画面构图输出"));
            assertFalse(prompt.contains("当前方位的纯色图"));assertFalse(prompt.contains("参考图4"));
        }
    }

    @Test void staleSurfaceSnapshotStopsBeforeSourceReadOrPaidModelCall() throws Exception {
        configure(SurfaceTemplateFixture.binding(SurfaceTemplateFixture.configuration(),"stale"),List.of());
        var result=service.execute(run,user);
        assertFalse(result.complete());assertTrue(result.message().contains("重新保存"));
        verify(captures,never()).get(anyString(),any());
        verifyNoInteractions(models,gateway,ledger);
    }
    @Test void surfaceExtractUsesUploadedArtworkWithoutCallingExtractionModel() throws Exception {
        configure(SurfaceTemplateFixture.configuration(),List.of());
        var extractRun=new CommerceBatchExecution(run.id(),run.organizationId(),run.batchId(),run.createdBy(),"EXTRACT",run.status(),null,run.leaseToken(),run.leaseUntil(),run.createdAt(),run.updatedAt());
        var result=service.execute(extractRun,user);
        assertEquals("QUEUED",result.pendingStatus());assertTrue(result.message().contains("模板上传印花"));
        var insertion=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("ARCHIVED_ARTWORK")).findFirst().orElseThrow();
        Object[] args=insertion.getArguments();
        assertEquals(CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('2')),args[10]);
        assertEquals(SurfaceTemplateFixture.hash('2'),args[11]);
        verifyNoInteractions(models,gateway,ledger);
        verify(archive,never()).store(anyString(),any());
    }
    @Test void capturedPhotoExtractCannotOverrideUploadedArtworkInExistingRun() throws Exception {
        var old=new HashMap<>(uploaded());old.put("storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('6')));
        configure(SurfaceTemplateFixture.configuration(),List.of(old,variant()));
        var result=service.execute(run,user);
        assertTrue(result.message().contains("上传印花不一致"));
        verifyNoInteractions(models,gateway,ledger);
    }

    @Test void frontAndBackRequestsUseIdenticalArchivedVariantAndKeepIndependentVariantsAndUnknownRequests() throws Exception {
        var jobs=new ArrayList<Map<String,Object>>(List.of(uploaded(),variant(),Map.of(
                "id","variant-2","capture_id","capture","kind","VARIATE","variant_index",2,"status","READY",
                "storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('7')))));
        var config=RepeatedSurfaceArtworkFixture.configuration();
        configure(config,jobs,2,RepeatedSurfaceArtworkFixture.CHECKER);
        var assets=nativeAssets();
        when(archive.read(eq("org"),anyString())).thenAnswer(call->
                SurfaceTemplateFixture.reference(assets.get(((String)call.getArgument(1)).substring(65))));
        when(archive.store(eq("org"),any())).thenAnswer(call->{
            byte[] bytes=call.getArgument(1);String sha=CommerceImageArchive.sha(bytes);
            return new CommerceImageArchive.Archived(CommerceSurfaceTemplateInputs.key("org",sha),sha,"image/png",bytes.length);
        });
        when(models.imageConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                "model","http://unused","gpt-image-2","OPENAI_COMPATIBLE","CUSTOM","unit-only")));
        when(db.queryForMap(startsWith("SELECT * FROM commerce_batch_artwork_jobs"),eq("run"),anyString()))
                .thenReturn(Map.of("id","persisted-job","status","UNKNOWN"));
        var variantKeys=new ArrayList<String>();
        for(int variant=1;variant<=2;variant++)for(String side:List.of("FRONT","BACK")) {
            clearInvocations(db,archive,models);
            assertEquals("UNKNOWN",service.execute(run,user).pendingStatus());
            var insertion=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                    &&((String)i.getArgument(0)).contains("INSERT IGNORE INTO commerce_batch_artwork_jobs")).findFirst().orElseThrow();
            Object[] args=insertion.getArguments();
            assertEquals("MOCKUP_"+side,args[6]);assertEquals(variant,args[7]);
            var references=json.readTree((String)args[12]);
            assertEquals(4,references.size());
            String variantKey=CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash(variant==1?'4':'7'));
            assertEquals(variantKey,references.get(3).asText());variantKeys.add(references.get(3).asText());
            var stored=mockingDetails(archive).getInvocations().stream().filter(i->i.getMethod().getName().equals("store")).findFirst().orElseThrow();
            byte[] guide=stored.getArgument(1);
            assertEquals(CommerceSurfaceTemplateInputs.key("org",CommerceImageArchive.sha(guide)),references.get(2).asText());
            var image=CommerceMockupComposer.decode(guide);
            assertEquals(variant==1?0xffff0000:0xff00ff00,image.getRGB(0,0));
            if(side.equals("FRONT"))assertEquals(image.getRGB(0,0),image.getRGB(3,0));
            assertEquals(0xff0000ff,image.getRGB(6,0));

            // A resumed unknown face retains its existing request instead of regenerating the shared artwork or face.
            var pending=new HashMap<String,Object>();
            pending.put("id","pending-"+side+variant);pending.put("capture_id","capture");
            pending.put("kind","MOCKUP_"+side);pending.put("variant_index",variant);pending.put("status","UNKNOWN");
            jobs.add(pending);clearInvocations(db,archive,models);
            assertEquals("UNKNOWN",service.execute(run,user).pendingStatus());
            verify(archive,never()).store(anyString(),any());verifyNoInteractions(models,gateway,ledger);
            pending.put("status","READY");
        }
        assertEquals(variantKeys.get(0),variantKeys.get(1));
        assertEquals(variantKeys.get(2),variantKeys.get(3));
        assertNotEquals(variantKeys.get(0),variantKeys.get(2));
        assertTrue(service.execute(run,user).complete());
        verifyNoInteractions(gateway,ledger);
    }

    CommerceBatchExecution stage(String stage) {
        return new CommerceBatchExecution(run.id(),run.organizationId(),run.batchId(),run.createdBy(),stage,run.status(),null,run.leaseToken(),run.leaseUntil(),run.createdAt(),run.updatedAt());
    }
    Map<String,Object> sourceArtwork() {
        return Map.of("id","product-extract","capture_id","capture","kind","EXTRACT","variant_index",0,"status","READY",
                "storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('8')),"model_config_id","model");
    }
    void configureSourceProduct(List<Map<String,Object>> jobs) throws Exception {
        configure(RepeatedSurfaceArtworkFixture.configuration(),jobs,2,null,"SOURCE_PRODUCT");
        var assets=nativeAssets();
        assets.put(SurfaceTemplateFixture.hash('8'),nativeArtwork(CommerceSurfaceMockupComposerTest.png(new int[][]{{0xff996633}})));
        when(archive.read(eq("org"),anyString())).thenAnswer(call->
                SurfaceTemplateFixture.reference(assets.get(((String)call.getArgument(1)).substring(65))));
        when(models.imageConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                "model","http://unused","gpt-image-2","OPENAI_COMPATIBLE","CUSTOM","unit-only")));
        when(db.queryForMap(startsWith("SELECT * FROM commerce_batch_artwork_jobs"),eq("run"),anyString()))
                .thenReturn(Map.of("id","persisted-job","status","UNKNOWN"));
        when(archive.store(eq("org"),any())).thenAnswer(call->{
            byte[] bytes=call.getArgument(1);String sha=CommerceImageArchive.sha(bytes);
            return new CommerceImageArchive.Archived(CommerceSurfaceTemplateInputs.key("org",sha),sha,"image/png",bytes.length);
        });
    }
    Object[] insertedJob() {
        return mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("INSERT IGNORE INTO commerce_batch_artwork_jobs")).findFirst().orElseThrow().getArguments();
    }
    @Test void sourceProductExtractionUsesSelectedProductPhotoRatherThanTestArtwork() throws Exception {
        configureSourceProduct(List.of());
        String original=CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('8'));
        when(sourceArchives.find("capture","image",1,user)).thenReturn(original);
        assertEquals("UNKNOWN",service.execute(stage("EXTRACT"),user).pendingStatus());
        Object[] args=insertedJob();
        assertEquals("EXTRACT",args[6]);
        var references=json.readTree((String)args[12]);
        assertEquals(1,references.size());assertEquals(original,references.get(0).asText());
        assertNotEquals("ARCHIVED_ARTWORK",args[10]);verifyNoInteractions(gateway,ledger);
    }
    @Test void sourceProductExecutionCannotOverwritePreviouslyArchivedTemplateArtwork() throws Exception {
        var legacy=new HashMap<>(uploaded());legacy.put("model_config_id","ARCHIVED_ARTWORK");
        configureSourceProduct(List.of(legacy));clearInvocations(models);
        var result=service.execute(stage("EXTRACT"),user);
        assertFalse(result.complete());assertTrue(result.message().contains("不能覆盖原任务"));
        verifyNoInteractions(models,gateway,ledger);verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void sourceProductCreatesOneRequestPerIndependentGroupAndVariantUsingOnlyProductStyleReference() throws Exception {
        var jobs=new ArrayList<Map<String,Object>>(List.of(sourceArtwork()));configureSourceProduct(jobs);
        var requestKeys=new HashSet<String>();
        for(int variant=1;variant<=2;variant++)for(int group=1;group<=2;group++) {
            clearInvocations(db,archive,models);
            assertEquals("UNKNOWN",service.execute(stage("VARIATE"),user).pendingStatus());
            Object[] args=insertedJob();String kind="VARIATE_G"+group;
            assertEquals(kind,args[6]);assertEquals(variant,args[7]);assertTrue(requestKeys.add((String)args[8]));
            assertTrue(((String)args[9]).contains("图案组 "+group));
            var references=json.readTree((String)args[12]);assertEquals(1,references.size());
            assertEquals(sourceArtwork().get("storage_key"),references.get(0).asText());
            var pending=new HashMap<String,Object>();pending.put("id","group-"+group+"-"+variant);
            pending.put("capture_id","capture");pending.put("kind",kind);pending.put("variant_index",variant);pending.put("status","UNKNOWN");
            jobs.add(pending);clearInvocations(db,archive,models);
            assertEquals("UNKNOWN",service.execute(stage("VARIATE"),user).pendingStatus());
            verifyNoInteractions(models,gateway,ledger);verify(archive,never()).store(anyString(),any());
            pending.put("status","READY");pending.put("storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash(group==1?'4':'7')));
        }
        assertTrue(service.execute(stage("VARIATE"),user).complete());verifyNoInteractions(gateway,ledger);
    }
    @Test void sourceProductMockupUsesAllGeneratedGroupsOnBothFacesAndKeepsVariantsIndependent() throws Exception {
        var jobs=new ArrayList<Map<String,Object>>(List.of(sourceArtwork()));
        for(int variant=1;variant<=2;variant++)for(int group=1;group<=2;group++)
            jobs.add(Map.of("id","variant-"+variant+"-"+group,"capture_id","capture","kind","VARIATE_G"+group,
                    "variant_index",variant,"status","READY","storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash(variant==group?'4':'7'))));
        configureSourceProduct(jobs);
        for(int variant=1;variant<=2;variant++)for(String side:List.of("FRONT","BACK")) {
            clearInvocations(db,archive,models);
            assertEquals("UNKNOWN",service.execute(run,user).pendingStatus());
            Object[] args=insertedJob();assertEquals("MOCKUP_"+side,args[6]);assertEquals(variant,args[7]);
            var references=json.readTree((String)args[12]);assertEquals(3,references.size());
            assertFalse(((String)args[9]).contains("参考图4"));
            var stored=mockingDetails(archive).getInvocations().stream().filter(i->i.getMethod().getName().equals("store")).findFirst().orElseThrow();
            byte[] guide=stored.getArgument(1);
            assertEquals(CommerceSurfaceTemplateInputs.key("org",CommerceImageArchive.sha(guide)),references.get(2).asText());
            var image=CommerceMockupComposer.decode(guide);
            assertEquals(variant==1?0xffff0000:0xff00ff00,image.getRGB(0,0));
            assertEquals(variant==1?0xffff00ff:0xffffff00,image.getRGB(6,0));
            if(side.equals("FRONT"))assertEquals(image.getRGB(0,0),image.getRGB(3,0));
            jobs.add(Map.of("id","done-"+side+variant,"capture_id","capture","kind","MOCKUP_"+side,"variant_index",variant,"status","READY"));
        }
        assertTrue(service.execute(run,user).complete());verifyNoInteractions(gateway,ledger);
    }

    @Test void sourceProductMockupCannotLeaveTestArtworkWhenAGroupHasNotFinished() throws Exception {
        configureSourceProduct(List.of(sourceArtwork(),Map.of("id","group-one","capture_id","capture","kind","VARIATE_G1",
                "variant_index",1,"status","READY","storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('4')))));
        clearInvocations(models);
        var result=service.execute(run,user);assertFalse(result.complete());assertTrue(result.message().contains("不能套版或保留测试图案"));
        verifyNoInteractions(models,gateway,ledger);verify(archive,never()).store(anyString(),any());
    }

    Map<String,Object> preparedExtraction() {
        var model=new ModelConfigService.RuntimeModelConnection("model","http://unused","gpt-image-2","OPENAI_COMPATIBLE","CUSTOM","unit-only");
        var job=new HashMap<String,Object>();
        job.put("id","extraction-job");job.put("capture_id","capture");job.put("kind","EXTRACT");job.put("variant_index",0);
        job.put("status","PREPARED");job.put("prompt","extract the product artwork");job.put("request_key","prepared-extract-key");
        job.put("model_config_id",model.id());job.put("model_fingerprint",CommerceBatchArtworkService.fingerprint(model));
        job.put("reference_keys",json.writeValueAsString(List.of(CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('8')))));
        return job;
    }
    @Test void sourcePrintGroupUsesTransparentArtworkWithoutChangingProductPreviewRoute() throws Exception {
        var job=preparedExtraction();job.put("kind","VARIATE_G1");job.put("variant_index",1);
        configureSourceProduct(List.of(sourceArtwork(),job));
        when(ledger.claim(stage("VARIATE"),"extraction-job","prepared-extract-key")).thenReturn("CLAIMED");
        when(gateway.generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList()))
                .thenThrow(new CommerceImageRejectedException("测试拒绝","POLICY",422));
        assertEquals("FAILED",service.execute(stage("VARIATE"),user).pendingStatus());
        verify(gateway).generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList());
        verify(gateway,never()).generateArtwork(anyString(),anyString(),any(),anyList());
        verify(gateway,never()).generateProductPreview(anyString(),anyString(),any(),anyList());
    }
    @Test void productMockupKeepsOpaquePreviewRouteWhenPrintGenerationUsesAlpha() throws Exception {
        var job=preparedExtraction();job.put("kind","MOCKUP_FRONT");job.put("variant_index",1);
        configureSourceProduct(List.of(sourceArtwork(),job,
                Map.of("id","group-1","capture_id","capture","kind","VARIATE_G1","variant_index",1,"status","READY","storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('4'))),
                Map.of("id","group-2","capture_id","capture","kind","VARIATE_G2","variant_index",1,"status","READY","storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('7')))));
        when(ledger.claim(run,"extraction-job","prepared-extract-key")).thenReturn("CLAIMED");
        when(gateway.generateProductPreview(anyString(),eq("extraction-job"),any(),anyList()))
                .thenThrow(new CommerceImageRejectedException("测试拒绝","POLICY",422));
        assertEquals("FAILED",service.execute(run,user).pendingStatus());
        verify(gateway).generateProductPreview(anyString(),eq("extraction-job"),any(),anyList());
        verify(gateway,never()).generateTransparentArtwork(anyString(),anyString(),any(),anyList());
        verify(gateway,never()).generateArtwork(anyString(),anyString(),any(),anyList());
    }
    @Test void collectionRetainsOpaquePaidOutputAsRejectedAndOnlyMakesQualifiedNativeAlphaReady() throws Exception {
        var jobs=new ArrayList<Map<String,Object>>();configureSourceProduct(jobs);
        for(boolean transparent:List.of(false,true)) {
            clearInvocations(db,archive,gateway,ledger);
            byte[] opaque=CommerceSurfaceMockupComposerTest.png(new int[][]{{0xffff0000,0xffffffff},{0xffffffff,0xff000000}});
            byte[] bytes=transparent?nativeArtwork(opaque):opaque;
            var job=preparedExtraction();job.put("status","RESULT_RECEIVED");job.put("execution_id","run");
            job.put("result_json",json.writeValueAsString(new CommerceDesignImageGateway.Response(
                    new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(bytes),"image/png","gpt-image-2","edit",""),null)));
            jobs.clear();jobs.add(job);
            var result=service.execute(stage("EXTRACT"),user);
            assertEquals(transparent?"QUEUED":"FAILED",result.pendingStatus());
            var check=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                    &&((String)i.getArgument(0)).contains("INTO commerce_batch_artwork_checks")).findFirst().orElseThrow();
            assertEquals(transparent?"VERIFIED_ALPHA":"OPAQUE",json.readTree((String)check.getArgument(2)).path("transparency").path("status").asText());
            assertEquals(transparent,mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")
                    &&((String)i.getArgument(0)).contains("SET status='READY'")));
            if(!transparent) {
                var rejected=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                        &&((String)i.getArgument(0)).contains("SET status='TRANSPARENCY_REJECTED'")).findFirst().orElseThrow();
                Object[] args=rejected.getArguments();
                assertEquals(CommerceSurfaceTemplateInputs.key("org",CommerceImageArchive.sha(bytes)),args[1]);
                assertEquals(CommerceImageArchive.sha(bytes),args[2]);assertEquals("extraction-job",args[4]);
                assertEquals("RESULT_RECEIVED",args[5]);assertTrue(result.message().contains("原始结果已保留，未继续套版"));
            }
            verify(archive,atLeastOnce()).store(eq("org"),aryEq(bytes));
            verifyNoInteractions(gateway,ledger);
        }
    }
    @Test void opaqueCollectedPrintIsArchivedAndRejectedBeforeQueuingAnotherAttempt() throws Exception {
        byte[] bytes=CommerceSurfaceMockupComposerTest.png(new int[][]{{0xffff0000,0xffffffff},{0xffffffff,0xff000000}});
        var job=preparedExtraction();job.put("status","RESULT_RECEIVED");job.put("execution_id","run");
        job.put("result_json",json.writeValueAsString(new CommerceDesignImageGateway.Response(
                new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(bytes),"image/png","gpt-image-2","edit",""),null)));
        configureSourceProduct(List.of(job));
        var retry=mock(CommerceTransparentArtworkRetry.class);
        ReflectionTestUtils.setField(service,"automaticArtworkRetry",retry);
        when(retry.retry(stage("EXTRACT"),job)).thenReturn(new CommerceTransparentArtworkRetry.Result("QUEUED","retry-job",2,"已安排第 2 次透明印花生成"));
        var result=service.execute(stage("EXTRACT"),user);
        assertEquals("QUEUED",result.pendingStatus());assertEquals("已安排第 2 次透明印花生成",result.message());
        var order=inOrder(archive,db,retry);
        order.verify(archive,atLeastOnce()).store(eq("org"),aryEq(bytes));
        order.verify(db).update(contains("SET status='TRANSPARENCY_REJECTED'"),
                eq(CommerceSurfaceTemplateInputs.key("org",CommerceImageArchive.sha(bytes))),eq(CommerceImageArchive.sha(bytes)),
                anyString(),eq("extraction-job"),eq("RESULT_RECEIVED"));
        order.verify(retry).retry(eq(stage("EXTRACT")),same(job));
        verifyNoMoreInteractions(retry);verifyNoInteractions(gateway,ledger);
    }
    @Test void missingImageResponseIsRejectedBeforeQueuingAnotherAttempt() throws Exception {
        var job=preparedExtraction();job.put("status","RESULT_RECEIVED");job.put("execution_id","run");
        job.put("result_json",json.writeValueAsString(new CommerceDesignImageGateway.Response(null,null)));
        configureSourceProduct(List.of(job));
        var retry=mock(CommerceTransparentArtworkRetry.class);
        ReflectionTestUtils.setField(service,"automaticArtworkRetry",retry);
        when(retry.retry(stage("EXTRACT"),job)).thenReturn(new CommerceTransparentArtworkRetry.Result("QUEUED","retry-job",2,"已安排第 2 次透明印花生成"));
        var result=service.execute(stage("EXTRACT"),user);
        assertEquals("QUEUED",result.pendingStatus());
        var order=inOrder(db,retry);
        order.verify(db).update(contains("SET status='INVALID_IMAGE_REJECTED'"),contains("模型已返回但没有图片内容"),eq("extraction-job"));
        order.verify(retry).retry(eq(stage("EXTRACT")),same(job));
        verifyNoMoreInteractions(retry);verify(archive,never()).store(anyString(),any());verifyNoInteractions(gateway,ledger);
    }
    @Test void confirmedProviderRefusalQueuesOnlyWhenRetryPolicyAllowsIt() throws Exception {
        var job=preparedExtraction();configureSourceProduct(List.of(job));
        var retry=mock(CommerceTransparentArtworkRetry.class);
        ReflectionTestUtils.setField(service,"automaticArtworkRetry",retry);
        var extractionRun=stage("EXTRACT");
        when(ledger.claim(extractionRun,"extraction-job","prepared-extract-key")).thenReturn("CLAIMED");
        for(boolean permanent:List.of(false,true)) {
            clearInvocations(db,gateway,ledger,retry);
            doThrow(new CommerceImageRejectedException(permanent?"服务商内容策略拒绝本次请求":"服务商并发繁忙",permanent?"POLICY":"CONCURRENCY",permanent?422:429))
                    .when(gateway).generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList());
            when(retry.retry(extractionRun,job)).thenReturn(new CommerceTransparentArtworkRetry.Result(
                    permanent?"NOT_RETRYABLE":"QUEUED",permanent?"extraction-job":"retry-job",permanent?1:2,
                    permanent?"内容策略拒绝，不能自动重试":"已安排第 2 次透明印花生成"));
            var result=service.execute(extractionRun,user);
            assertEquals(permanent?"FAILED":"QUEUED",result.pendingStatus());
            assertTrue(result.message().contains(permanent?"内容策略拒绝，不能自动重试":"已安排第 2 次透明印花生成"));
            var order=inOrder(db,retry);
            order.verify(db).update(contains("SET status='REJECTED'"),anyString(),anyString(),eq("extraction-job"));
            order.verify(retry).retry(eq(extractionRun),same(job));
            verifyNoMoreInteractions(retry);
            verify(gateway).generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList());
            verifyNoMoreInteractions(gateway);
        }
    }
    @Test void opaqueGeneratedExtractionAlreadyMarkedReadyCannotBeReused() throws Exception {
        rejectsCachedGeneratedPrint("EXTRACT",SurfaceTemplateFixture.blank());
    }
    @Test void opaqueGeneratedVariantAlreadyMarkedReadyCannotReachComposition() throws Exception {
        rejectsCachedGeneratedPrint("VARIATE_G1",SurfaceTemplateFixture.blank());
    }
    @Test void corruptGeneratedVariantDoesNotTriggerAutomaticGenerationOrClearItsArchive() throws Exception {
        rejectsCachedGeneratedPrint("VARIATE_G1",new byte[]{1,2,3});
    }
    private void rejectsCachedGeneratedPrint(String kind,byte[] bytes) throws Exception {
        var invalid=new HashMap<>(kind.equals("EXTRACT")?sourceArtwork():variant());
        invalid.put("kind",kind);
        var jobs=new ArrayList<Map<String,Object>>();
        if(!kind.equals("EXTRACT"))jobs.add(sourceArtwork());jobs.add(invalid);
        configureSourceProduct(jobs);
        String stored=(String)invalid.get("storage_key");
        doReturn(SurfaceTemplateFixture.reference(bytes)).when(archive).read("org",stored);
        clearInvocations(db,archive,models,gateway,ledger);
        var result=service.execute(stage(kind.equals("EXTRACT")?"EXTRACT":"VARIATE"),user);
        assertEquals("FAILED",result.pendingStatus());assertTrue(result.message().contains("原生透明印花"));
        var rejected=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("SET status='TRANSPARENCY_REJECTED'")).findFirst().orElseThrow();
        Object[] args=rejected.getArguments();
        assertFalse(((String)args[0]).contains("storage_key="),"Existing paid storage must not be cleared or overwritten");
        assertEquals(invalid.get("id"),args[2]);assertEquals("READY",args[3]);
        verify(archive,never()).store(anyString(),any());verifyNoInteractions(models,gateway,ledger);
        invalid.put("status","TRANSPARENCY_REJECTED");invalid.put("message",result.message());
        clearInvocations(db,archive,models,gateway,ledger);
        var resumed=service.execute(stage(kind.equals("EXTRACT")?"EXTRACT":"VARIATE"),user);
        assertEquals("FAILED",resumed.pendingStatus());assertEquals(result.message(),resumed.message());
        assertEquals(stored,invalid.get("storage_key"));verifyNoInteractions(models,gateway,ledger);
        assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")));
    }
    @Test void uploadedExtractionAndOrdinaryOpaqueProductImagesAreNotNativeAlphaGated() throws Exception {
        var jobs=List.of(uploaded(),Map.<String,Object>of("id","product-photo","capture_id","capture","kind","MOCKUP_BACK",
                "variant_index",1,"status","READY","storage_key",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('d'))));
        configure(SurfaceTemplateFixture.configuration(),jobs);
        assertFalse(CommerceArtworkTransparency.inspect(Base64.getDecoder().decode(archive.read("org",(String)uploaded().get("storage_key")).dataBase64())).verified());
        clearInvocations(db,archive,models,gateway,ledger);
        assertTrue(service.execute(stage("EXTRACT"),user).complete());
        verifyNoInteractions(models,gateway,ledger);
        assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("TRANSPARENCY_REJECTED")));
    }
    @Test void definiteProviderRejectionStopsBatchWithReadableReasonAndCannotBeResubmitted() throws Exception {
        var job=preparedExtraction();configureSourceProduct(List.of(job));
        var extractionRun=stage("EXTRACT");when(ledger.claim(extractionRun,"extraction-job","prepared-extract-key")).thenReturn("CLAIMED");
        when(gateway.generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList()))
                .thenThrow(new CommerceImageRejectedException("服务商内容策略拒绝本次请求","POLICY",422));
        var result=service.execute(extractionRun,user);
        assertFalse(result.complete());assertEquals("FAILED",result.pendingStatus());
        assertTrue(result.message().startsWith("图片模型拒绝生成："));assertTrue(result.message().contains("内容策略"));
        assertTrue(result.message().endsWith("本批次已停止，未生成本张图片，不会自动重试"));
        var update=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("SET status='REJECTED'")).findFirst().orElseThrow();
        Object[] args=update.getArguments();assertEquals(result.message(),args[1]);assertEquals("extraction-job",args[3]);
        var metadata=json.readTree((String)args[2]);assertEquals("REJECTED",metadata.path("requestState").asText());
        assertEquals("POLICY",metadata.path("failureKind").asText());assertEquals(422,metadata.path("providerStatus").asInt());
        verify(ledger).claim(extractionRun,"extraction-job","prepared-extract-key");verifyNoMoreInteractions(ledger);
        verify(gateway).generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList());
        verify(gateway,never()).generateArtwork(anyString(),anyString(),any(),anyList());
        verify(gateway,never()).generateProductPreview(anyString(),anyString(),any(),anyList());
        assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("commerce_batch_model_")),"The original model call reservation must not be released");

        // A later worker turn sees the persisted terminal rejection and returns exactly the same reason.
        job.put("status","REJECTED");job.put("message",result.message());
        clearInvocations(db,models,gateway,ledger,archive);
        var resumed=service.execute(extractionRun,user);
        assertEquals("FAILED",resumed.pendingStatus());assertEquals(result.message(),resumed.message());
        verifyNoInteractions(models,gateway,ledger);verify(archive,never()).store(anyString(),any());
        assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")));
    }
    @Test void unknownNetworkFailureRemainsUnknownAndRetainsReservedAttempt() throws Exception {
        var job=preparedExtraction();configureSourceProduct(List.of(job));
        var retry=mock(CommerceTransparentArtworkRetry.class);
        ReflectionTestUtils.setField(service,"automaticArtworkRetry",retry);
        var extractionRun=stage("EXTRACT");when(ledger.claim(extractionRun,"extraction-job","prepared-extract-key")).thenReturn("CLAIMED");
        when(gateway.generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("socket closed before a definitive response"));
        var result=service.execute(extractionRun,user);assertEquals("UNKNOWN",result.pendingStatus());
        assertTrue(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("SET status='UNKNOWN'")));
        assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")
                &&(((String)i.getArgument(0)).contains("REJECTED")||((String)i.getArgument(0)).contains("commerce_batch_model_"))));
        verify(ledger).claim(extractionRun,"extraction-job","prepared-extract-key");verifyNoMoreInteractions(ledger);
        verify(gateway).generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList());
        verify(gateway,never()).generateArtwork(anyString(),anyString(),any(),anyList());
        verify(gateway,never()).generateProductPreview(anyString(),anyString(),any(),anyList());
        job.put("status","UNKNOWN");clearInvocations(models,gateway,ledger);
        assertEquals("UNKNOWN",service.execute(extractionRun,user).pendingStatus());verifyNoInteractions(models,gateway,ledger,retry);
    }
    @Test void rejectionWithoutProviderStatusKeepsSafeMetadataAndBoundedMessage() throws Exception {
        var job=preparedExtraction();configureSourceProduct(List.of(job));
        when(ledger.claim(stage("EXTRACT"),"extraction-job","prepared-extract-key")).thenReturn("CLAIMED");
        when(gateway.generateTransparentArtwork(anyString(),eq("extraction-job"),any(),anyList()))
                .thenThrow(new CommerceImageRejectedException("安全原因\n"+"说明".repeat(500),"POLICY",null));
        var result=service.execute(stage("EXTRACT"),user);assertEquals("FAILED",result.pendingStatus());
        assertTrue(result.message().length()<1000);assertFalse(result.message().contains("\n"));
        var update=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")
                &&((String)i.getArgument(0)).contains("SET status='REJECTED'")).findFirst().orElseThrow();
        assertTrue(json.readTree((String)update.getArgument(2)).path("providerStatus").isNull());
    }
    @Test void historicalRejectedAttemptIsTerminalWithoutReclaimingItsModelReservation() throws Exception {
        var job=preparedExtraction();job.put("status","REJECTED");
        job.put("message","图片模型拒绝生成：服务商内容策略拒绝本次请求；本次请求不会自动重试");
        configureSourceProduct(List.of(job));clearInvocations(models);
        var result=service.execute(stage("EXTRACT"),user);
        assertEquals("FAILED",result.pendingStatus());assertTrue(result.message().endsWith("本批次已停止，未生成本张图片，不会自动重试"));
        verifyNoInteractions(models,gateway,ledger);
        assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")));
    }
}
