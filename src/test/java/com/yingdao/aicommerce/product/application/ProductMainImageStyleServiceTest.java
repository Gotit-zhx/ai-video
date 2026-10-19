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
        when(vision.analyze(eq("org"),eq("PRODUCT_MAIN_IMAGE_REVIEW_V1"),anyList(),anyMap())).thenReturn(result);
        assertFalse(service.ensureReady(workflow,capture));
        verify(db).update(contains("SET status='READY'"),eq(json.writeValueAsString(result)),eq(job.get("id")),eq(0));
        verifyNoInteractions(gateway,downloader);
    }
    @Test void qualityMismatchSchedulesOneCorrectionAndRetainsOriginalPaidOutput() {
        job.put("status","RESULT_RECEIVED");job.put("result_json","{\"artifact\":null,\"result\":null}");
        var result = new LinkedHashMap<String,Object>();
        ProductMainImageStylePolicy.CHECKS.forEach(key -> result.put(key,true));result.put("layoutMatched",false);result.put("reason","Title misplaced");
        when(vision.analyze(eq("org"),eq("PRODUCT_MAIN_IMAGE_REVIEW_V1"),anyList(),anyMap())).thenReturn(result);
        assertFalse(service.ensureReady(workflow,capture));
        verify(db).update(contains("attempt_count=1,history_json"),contains("\"storageKey\":\"output\""),contains("Title misplaced"),eq(job.get("id")));
        verifyNoInteractions(gateway);
    }
    @Test void secondQualityMismatchStopsInsteadOfLoopingPaidGenerations() {
        job.put("status","RESULT_RECEIVED");job.put("attempt_count",1);
        var result = new LinkedHashMap<String,Object>();
        ProductMainImageStylePolicy.CHECKS.forEach(key -> result.put(key,false));result.put("reason","Wrong product");
        when(vision.analyze(eq("org"),eq("PRODUCT_MAIN_IMAGE_REVIEW_V1"),anyList(),anyMap())).thenReturn(result);
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class, () -> service.ensureReady(workflow,capture));
        verify(db).update(contains("SET status='FAILED'"),contains("Wrong product"),eq(job.get("id")));
        verifyNoInteractions(gateway);
    }
    private ModelConfigService.RuntimeModelConnection configuredModel() {
        var model = new ModelConfigService.RuntimeModelConnection("model","https://unit.invalid","image-model","OPENAI_COMPATIBLE","CUSTOM","test-only");
        when(models.commerceMockupConnectionById("org","model")).thenReturn(Optional.of(model));
        return model;
    }
}
