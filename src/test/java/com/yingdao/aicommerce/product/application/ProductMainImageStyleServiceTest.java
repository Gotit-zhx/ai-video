package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductPublishWorkflow;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductMainImageStyleServiceTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final ProductCaptureImageMapper images = mock(ProductCaptureImageMapper.class);
    private final CommerceImageArchive archive = mock(CommerceImageArchive.class);
    private final MarketplaceImageDownloader downloader = mock(MarketplaceImageDownloader.class);
    private final CommerceDesignImageGateway gateway = mock(CommerceDesignImageGateway.class);
    private final ProductMainImageStyleVision vision = mock(ProductMainImageStyleVision.class);
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final ProductMainImageTemplateService templates = mock(ProductMainImageTemplateService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ProductCapture capture = mock(ProductCapture.class);
    private final ProductPublishWorkflow workflow = mock(ProductPublishWorkflow.class);
    private final ProductMainImageStyleService service = new ProductMainImageStyleService(db, images, archive, downloader, gateway, vision, models, json, templates);
    private final String templateId = "12345678-1234-1234-1234-123456789012";
    private Map<String,Object> slot;
    private Map<String,Object> job;

    @BeforeEach void setup() {
        when(workflow.getId()).thenReturn("workflow");
        when(workflow.getOrganizationId()).thenReturn("org");
        when(capture.getOrganizationId()).thenReturn("org");
        when(capture.getId()).thenReturn("capture");
        when(capture.getTitle()).thenReturn("Target");
        when(workflow.getRequestJson()).thenReturn(json.writeValueAsString(Map.of(ProductMainImageStylePolicy.TEMPLATE,templateId)));
        slot = new LinkedHashMap<>(Map.of("imageId","image","storageKey","reference","width",1024,"height",1024,"analysis",ProductMainImageStylePolicyTest.analysis()));
        when(templates.current(templateId,"org")).thenAnswer(invocation -> Map.of("name","Live template","images",List.of(slot)));
        job = new LinkedHashMap<>(Map.of("id","12345678-aaaa-bbbb-cccc-123456789012","sort_order",0,"status","READY",
                "template_fingerprint",ProductMainImageStylePolicy.fingerprint(slot,json),"attempt_count",0,"source_key","source","reference_key","reference","storage_key","output"));
        job.put("model_config_id","model");job.put("prompt","Prompt");job.put("size","1024x1024");
        when(db.queryForList(anyString(),eq("workflow"),eq("org"))).thenAnswer(invocation -> List.of(job));
        when(archive.read(anyString(),anyString())).thenReturn(new CommerceDesignImageGateway.Reference("image/png","aW1hZ2U="));
    }

    @Test void optOutMakesNoGenerationStorageOrTemplateCalls() {
        when(workflow.getRequestJson()).thenReturn("{}");
        assertTrue(service.ensureReady(workflow,capture));
        assertNull(service.progress(workflow));
        verifyNoInteractions(db,images,archive,downloader,gateway,vision,models,templates);
    }
    @Test void completedSlotsAreReusedWithoutAnotherPaidCall() {
        assertTrue(service.ensureReady(workflow,capture));
        assertEquals(1,service.progress(workflow).processedImages());
        verifyNoInteractions(gateway,vision,models,downloader);
    }
    @Test void originalOnlyTemplateCopiesArchivedReferenceWithoutProductSourceOrAnyModel() {
        slot.put("analysis", ProductMainImageStylePolicyTest.originalAnalysis());
        var persisted = new ArrayList<Map<String,Object>>();
        when(db.queryForList(anyString(),eq("workflow"),eq("org"))).thenReturn(persisted);
        stubOriginalInsert(persisted);
        assertTrue(service.ensureReady(workflow,capture));
        assertTrue(service.ensureReady(workflow,capture));
        assertEquals(1, persisted.size());
        assertEquals("reference", persisted.getFirst().get("storage_key"));
        assertEquals("", persisted.getFirst().get("model_config_id"));
        assertEquals(1, service.progress(workflow).processedImages());
        verify(db, times(1)).update(contains("'ORIGINAL'"), any(Object[].class));
        verify(archive).read("org","reference");
        verifyNoInteractions(images,downloader,gateway,vision,models);
    }
    @Test void mixedTemplateKeepsOriginalAndGeneratedImagesInTheirSlotsWithoutRegeneratingReadyWork() {
        var generatedSlot = new LinkedHashMap<>(slot);
        generatedSlot.put("imageId","generated-image");
        job.put("sort_order",1);
        job.put("template_fingerprint",ProductMainImageStylePolicy.fingerprint(generatedSlot,json));
        slot.put("analysis", ProductMainImageStylePolicyTest.originalAnalysis());
        when(templates.current(templateId,"org")).thenReturn(Map.of("name","Mixed","images",List.of(slot,generatedSlot)));
        var persisted = new ArrayList<>(List.of(job));
        when(db.queryForList(anyString(),eq("workflow"),eq("org"))).thenReturn(persisted);
        stubOriginalInsert(persisted);
        assertTrue(service.ensureReady(workflow,capture));
        var progress = service.progress(workflow);
        assertEquals(2,progress.totalImages());
        assertEquals(2,progress.processedImages());
        assertEquals("/api/v1/product-main-image-results/"+persisted.getFirst().get("id"),progress.imageUrls().getFirst());
        assertEquals("/api/v1/product-main-image-results/"+job.get("id"),progress.imageUrls().getLast());
        verifyNoInteractions(images,downloader,gateway,vision,models);
    }
    @Test void changingAnOriginalSlotToGeneratedDoesNotReuseItsEmptyModelId() {
        var originalSlot = new LinkedHashMap<>(slot);
        originalSlot.put("analysis",ProductMainImageStylePolicyTest.originalAnalysis());
        job.put("model_config_id","");
        job.put("template_fingerprint",ProductMainImageStylePolicy.fingerprint(originalSlot,json));
        when(models.imageConnection("org")).thenReturn(Optional.empty());
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->service.ensureReady(workflow,capture));
        verify(models).imageConnection("org");
        verifyNoInteractions(gateway);
    }
    @Test void editingTheCurrentPromptInvalidatesTheOldCompletedOutput() {
        var changed = ProductMainImageStylePolicyTest.analysis();
        changed.put("prompt","New current layout with {{product}}");
        slot.put("analysis",changed);
        assertEquals(0,service.progress(workflow).processedImages());
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class, () -> service.ensureReady(workflow,capture));
        verify(db).update(contains("INSERT IGNORE INTO product_main_image_jobs"), any(Object[].class));
        verifyNoInteractions(gateway);
    }
    @Test void uncertainSubmissionReadsOriginalOperationAndNeverRegenerates() {
        job.put("status","SUBMITTING");
        job.put("updated_at",java.sql.Timestamp.from(java.time.Instant.now()));
        when(gateway.operation((String)job.get("id"))).thenReturn(new CommerceDesignImageGateway.Operation("RUNNING",true,0,null,null));
        assertFalse(service.ensureReady(workflow,capture));
        verify(gateway).operation((String)job.get("id"));
        verifyNoMoreInteractions(gateway);
    }
    @Test void cachedProviderResponseRecoversWithTheOriginalOperationAndReferences() {
        job.put("status","SUBMITTING");
        var model = configuredModel();
        var response = new CommerceDesignImageGateway.Response(null,
                new CommerceDesignImageGateway.PendingResult("https://result.invalid/image.png","image/png","model","edit",null));
        when(gateway.operation((String)job.get("id"))).thenReturn(new CommerceDesignImageGateway.Operation(
                "RECOVERABLE",false,0,null,Map.of("savedResponseAvailable",true)));
        when(db.update(contains("recovery_count=recovery_count+1"),eq(job.get("id")),eq(0),eq(0))).thenReturn(1);
        when(gateway.generateMainImagePoster(eq("Prompt"),eq((String)job.get("id")),eq(model),anyList(),eq("1024x1024"))).thenReturn(response);
        assertFalse(service.ensureReady(workflow,capture));
        verify(gateway).generateMainImagePoster(eq("Prompt"),eq((String)job.get("id")),eq(model),argThat(refs -> refs.size()==2),eq("1024x1024"));
        verify(db).update(contains("SET status='RESULT_RECEIVED'"),eq(json.writeValueAsString(response)),eq(job.get("id")),eq(0));
        verify(archive).read("org","source");verify(archive).read("org","reference");
    }
    @Test void recoveryWithoutCachedResponseNeverSubmitsAgain() {
        job.put("status","SUBMITTING");
        when(gateway.operation((String)job.get("id"))).thenReturn(new CommerceDesignImageGateway.Operation(
                "RECOVERABLE",false,0,null,Map.of("savedResponseAvailable",false)));
        assertFalse(service.ensureReady(workflow,capture));
        verify(gateway).operation((String)job.get("id"));
        verifyNoMoreInteractions(gateway);
        verifyNoInteractions(models,archive);
    }
    @Test void savedResponseRecoveryIsBoundedAcrossWorkerClaims() {
        job.put("status","SUBMITTING");job.put("recovery_count",3);
        when(gateway.operation((String)job.get("id"))).thenReturn(new CommerceDesignImageGateway.Operation(
                "RECOVERABLE",false,0,null,Map.of("savedResponseAvailable",true)));
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->service.ensureReady(workflow,capture));
        verify(db).update(contains("SET status='FAILED'"),contains("连续3次"),eq(job.get("id")));
        verify(gateway).operation((String)job.get("id"));
        verifyNoMoreInteractions(gateway);
    }
    @Test void confirmedUnsentRequestReturnsToQueueInsteadOfWaitingForAnUnknownOperation() {
        job.put("status","PREPARED");
        configuredModel();
        when(db.update(contains("SET status='SUBMITTING'"),eq(job.get("id")),eq(0))).thenReturn(1);
        when(gateway.generateMainImagePoster(anyString(),anyString(),any(),anyList(),anyString()))
                .thenThrow(new ImageGenerationNotSentException(new IllegalStateException("pool busy")));
        var failure = assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->service.ensureReady(workflow,capture));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,failure.getStatus());
        verify(db).update(contains("SET status='PREPARED'"),eq(job.get("id")),eq(0));
    }
    @Test void receivedOutputIsAutomaticallyReviewedThenApproved() {
        job.put("status","RESULT_RECEIVED");
        var result = new LinkedHashMap<String,Object>();
        ProductMainImageStylePolicy.CHECKS.forEach(key -> result.put(key,true));result.put("reason","Visual match");
        when(vision.analyze(eq("org"),eq("PRODUCT_MAIN_IMAGE_REVIEW_V1"),anyList(),anyMap(),any())).thenReturn(result);
        assertFalse(service.ensureReady(workflow,capture));
        verify(db).update(contains("SET status='READY'"),eq(json.writeValueAsString(result)),eq(job.get("id")),eq(0));
        verifyNoInteractions(gateway,downloader);
    }
    @Test void qualityMismatchSchedulesOneCorrectionAndRetainsOriginalPaidOutput() {
        job.put("status","RESULT_RECEIVED");job.put("result_json","{\"artifact\":null,\"result\":null}");
        var result = new LinkedHashMap<String,Object>();
        ProductMainImageStylePolicy.CHECKS.forEach(key -> result.put(key,true));result.put("layoutMatched",false);result.put("reason","Title misplaced");
        when(vision.analyze(eq("org"),eq("PRODUCT_MAIN_IMAGE_REVIEW_V1"),anyList(),anyMap(),any())).thenReturn(result);
        assertFalse(service.ensureReady(workflow,capture));
        verify(db).update(contains("attempt_count=1,history_json"),contains("\"storageKey\":\"output\""),contains("Title misplaced"),eq(job.get("id")));
        verifyNoInteractions(gateway);
    }
    @Test void secondQualityMismatchStopsInsteadOfLoopingPaidGenerations() {
        job.put("status","RESULT_RECEIVED");job.put("attempt_count",1);
        var result = new LinkedHashMap<String,Object>();
        ProductMainImageStylePolicy.CHECKS.forEach(key -> result.put(key,false));result.put("reason","Wrong product");
        when(vision.analyze(eq("org"),eq("PRODUCT_MAIN_IMAGE_REVIEW_V1"),anyList(),anyMap(),any())).thenReturn(result);
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class, () -> service.ensureReady(workflow,capture));
        verify(db).update(contains("SET status='FAILED'"),contains("Wrong product"),eq(job.get("id")));
        verifyNoInteractions(gateway);
    }
    @Test void progressIncludesEverySlotInOrderAndDoesNotCallModels() {
        var second = new LinkedHashMap<>(slot);
        second.put("imageId", "second");
        when(templates.current(templateId,"org")).thenReturn(Map.of("name","Two","images",List.of(slot,second)));
        job.put("status","SUBMITTING");
        job.put("updated_at",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(42)));
        var progress = service.progress(workflow);
        assertEquals(2,progress.totalImages());
        assertEquals(0,progress.processedImages());
        assertTrue(progress.imageUrls().isEmpty());
        assertEquals("GENERATING",progress.images().getFirst().stage());
        assertTrue(progress.images().getFirst().elapsedSeconds() >= 42);
        assertNull(progress.images().getFirst().imageUrl());
        assertEquals(1,progress.images().getLast().index());
        assertEquals("WAITING",progress.images().getLast().stage());
        assertNull(progress.images().getLast().elapsedSeconds());
        verifyNoInteractions(gateway,vision,models,archive,downloader);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void progressSeparatesOriginalsCorrectionsAndGeneratedQualityChecks() {
        slot.put("analysis",ProductMainImageStylePolicyTest.originalAnalysis());
        job.put("template_fingerprint",ProductMainImageStylePolicy.fingerprint(slot,json));
        assertEquals("PRESERVED",service.progress(workflow).images().getFirst().stage());
        assertEquals("PRESERVE_ORIGINAL",service.progress(workflow).images().getFirst().mode());
        slot.put("analysis",ProductMainImageStylePolicyTest.analysis());
        job.put("template_fingerprint",ProductMainImageStylePolicy.fingerprint(slot,json));
        job.put("status","PREPARED");job.put("attempt_count",1);
        assertEquals("CORRECTING",service.progress(workflow).images().getFirst().stage());
        job.put("status","RESULT_RECEIVED");
        assertEquals("REVIEW_QUEUED",service.progress(workflow).images().getFirst().stage());
        job.put("review_json","{\"progress\":{\"stage\":\"MODEL_STREAMING\",\"elapsedSeconds\":12,\"outputCharacters\":345},\"secret\":\"not exposed\"}");
        var image=service.progress(workflow).images().getFirst();
        assertEquals("REVIEW_MODEL_STREAMING",image.stage());
        assertEquals(12L,image.elapsedSeconds());
        assertEquals(345,image.outputCharacters());
        assertFalse(json.writeValueAsString(image).contains("secret"));
        job.remove("review_json");job.remove("storage_key");
        assertEquals("SAVING",service.progress(workflow).images().getFirst().stage());
    }
    @Test void terminalWorkflowStopsUnfinishedProgressWithoutHidingReadyImages() {
        when(workflow.getStatus()).thenReturn("FAILED");
        when(workflow.getErrorMessage()).thenReturn("Review timed out");
        assertEquals("READY",service.progress(workflow).images().getFirst().stage());
        job.put("status","RESULT_RECEIVED");
        var image=service.progress(workflow).images().getFirst();
        assertEquals("STOPPED",image.stage());
        assertEquals("Review timed out",image.errorMessage());
        assertNull(image.imageUrl());
        job.put("status","FAILED");job.put("error_message","Wrong product");
        assertEquals("FAILED",service.progress(workflow).images().getFirst().stage());
        assertEquals("Wrong product",service.progress(workflow).images().getFirst().errorMessage());
    }
    @Test void changedTemplateNeverShowsStaleSlotProgressOrOutput() {
        slot.put("imageId","changed-image");
        var image=service.progress(workflow).images().getFirst();
        assertEquals("WAITING",image.stage());
        assertNull(image.imageUrl());
        assertEquals(0,service.progress(workflow).processedImages());
    }
    @Test void reviewStreamPersistsOnlyBoundedPublicProgressForCurrentAttempt() {
        job.put("status","RESULT_RECEIVED");job.put("attempt_count",1);
        var result = new LinkedHashMap<String,Object>();
        ProductMainImageStylePolicy.CHECKS.forEach(key -> result.put(key,true));result.put("reason","Match");
        when(vision.analyze(eq("org"),eq("PRODUCT_MAIN_IMAGE_REVIEW_V1"),anyList(),anyMap(),any())).thenAnswer(call -> {
            java.util.function.Consumer<Map<String,Object>> callback=call.getArgument(4);
            callback.accept(Map.of("stage","MODEL_STREAMING","elapsedSeconds",5,"outputCharacters",123,"reasoning","private"));
            callback.accept(Map.of("stage","MODEL_STREAMING","elapsedSeconds",9999L,"outputCharacters",999999L));
            callback.accept(Map.of("stage","UNTRUSTED","elapsedSeconds",1));
            return result;
        });
        assertFalse(service.ensureReady(workflow,capture));
        verify(db,times(3)).update(contains("SET review_json=?,updated_at"),anyString(),eq(job.get("id")),eq(1));
        verify(db).update(contains("SET review_json=?,updated_at"),argThat((String value) ->
                value.contains("\"outputCharacters\":123") && !value.contains("private")),eq(job.get("id")),eq(1));
        verify(db).update(contains("SET review_json=?,updated_at"),argThat((String value) ->
                value.contains("\"outputCharacters\":50000") && value.contains("\"elapsedSeconds\":600")),eq(job.get("id")),eq(1));
        verify(db).update(contains("SET status='READY'"),eq(json.writeValueAsString(result)),eq(job.get("id")),eq(1));
    }
    private ModelConfigService.RuntimeModelConnection configuredModel() {
        var model = new ModelConfigService.RuntimeModelConnection("model","https://unit.invalid","image-model","OPENAI_COMPATIBLE","CUSTOM","test-only");
        when(models.commerceMockupConnectionById("org","model")).thenReturn(Optional.of(model));
        return model;
    }
    private void stubOriginalInsert(List<Map<String,Object>> persisted) {
        when(db.update(contains("'ORIGINAL'"),any(Object[].class))).thenAnswer(call -> {
            var original = new LinkedHashMap<String,Object>();
            original.put("id",call.getArgument(1)); original.put("sort_order",call.getArgument(5));
            original.put("source_key",call.getArgument(6)); original.put("reference_key",call.getArgument(7));
            original.put("template_fingerprint",call.getArgument(8)); original.put("storage_key",call.getArgument(9));
            original.put("review_json",call.getArgument(10)); original.put("status","READY"); original.put("model_config_id","");
            persisted.addFirst(original);
            return 1;
        });
    }
}
