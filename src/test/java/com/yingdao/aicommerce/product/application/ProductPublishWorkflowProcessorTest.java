package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductImageProcessingJob;
import com.yingdao.aicommerce.product.domain.ProductPublishAudit;
import com.yingdao.aicommerce.product.domain.ProductPublishWorkflow;
import com.yingdao.aicommerce.product.application.provider.ProductPublishingProvider;
import com.yingdao.aicommerce.product.application.provider.VideoUploadPendingException;
import com.yingdao.aicommerce.product.mapper.ProductImageProcessingJobMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishAuditMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishWorkflowMapper;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductPublishWorkflowProcessorTest {
    private ProductPublishWorkflowMapper workflowMapper;
    private ProductImageProcessingJobMapper imageJobMapper;
    private ProductPublishAuditMapper publishAuditMapper;
    private PlatformUserMapper userMapper;
    private PlatformUser workflowUser;
    private ProductCaptureService captureService;
    private ProductPublishWorkflowService workflowService;
    private ProductPublishAttemptGuard publishAttemptGuard;
    private ProductPublishWorkflowProcessor processor;
    private ProductPublishWorkflow workflow;
    private ProductCapture capture;

    @BeforeEach
    void setUp() {
        workflowMapper = mock(ProductPublishWorkflowMapper.class);
        imageJobMapper = mock(ProductImageProcessingJobMapper.class);
        publishAuditMapper = mock(ProductPublishAuditMapper.class);
        userMapper = mock(PlatformUserMapper.class);
        workflowUser = mock(PlatformUser.class);
        captureService = mock(ProductCaptureService.class);
        workflowService = mock(ProductPublishWorkflowService.class);
        publishAttemptGuard = mock(ProductPublishAttemptGuard.class);
        processor = new ProductPublishWorkflowProcessor(workflowMapper, imageJobMapper,
                publishAuditMapper, userMapper, captureService, workflowService,
                publishAttemptGuard, Runnable::run, 10, 1800);
        when(userMapper.selectById("user-1")).thenReturn(workflowUser);
        when(workflowUser.getId()).thenReturn("user-1");
        when(workflowUser.getOrganizationId()).thenReturn("org-a");
        when(workflowUser.getStatus()).thenReturn("ACTIVE");
        when(workflowUser.getUsername()).thenReturn("publisher");
        when(workflowUser.getDisplayName()).thenReturn("Publisher");
        when(workflowUser.getRegistrationSource()).thenReturn("LOCAL");
        when(userMapper.selectRoleCodes("user-1")).thenReturn(List.of());
        when(userMapper.selectPermissionCodes("user-1")).thenReturn(List.of(
                "asset:watermark", "product:edit", "product:publish"));
        capture = capture();
        workflow = runningWorkflow("IMAGES", 0);
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        when(captureService.get(eq(capture.getId()), any(PlatformPrincipal.class)))
                .thenReturn(detail(capture));
        when(workflowMapper.markStep(any(), any(), any(), anyLong(), any())).thenReturn(1);
        when(workflowMapper.imageSucceeded(any(), any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(1);
        when(workflowMapper.approvalSucceeded(any(), any(), anyLong(), any(), any())).thenReturn(1);
        when(workflowMapper.complete(any(), any(), any(), anyLong(), anyBoolean(), any()))
                .thenReturn(1);
        when(workflowMapper.fail(any(), any(), any(), any())).thenReturn(1);
        when(workflowMapper.failUncertainPublication(any(), any(), any(), any())).thenReturn(1);
        when(workflowMapper.retry(any(), any(), any(), any(), any())).thenReturn(1);
        when(workflowMapper.waitForProgress(any(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(1);
        when(workflowMapper.waitForMediaProgress(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(1);
    }

    @Test
    void selectedStyleWaitsForAutomaticGenerationBeforeApproval() {
        capture.imagesReady();
        workflow = runningWorkflow("APPROVE", capture.getVersion());
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        when(workflowService.request(workflow)).thenReturn(Map.of("mainImageStyleTemplateId","12345678-1234-1234-1234-123456789012"));
        var styles = mock(ProductMainImageStyleService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "mainImageStyles", styles);
        when(styles.ensureReady(workflow,capture)).thenReturn(false);
        processor.process(workflow.getId(),"worker-1");
        verify(workflowMapper).waitForProgress(eq(workflow.getId()),eq("worker-1"),eq("STYLE_IMAGES"),any(),any(),eq(capture.getVersion()),any(),any());
        verify(captureService,never()).approve(any(),any());
        verify(captureService,never()).publish(any(),any(),any(),any(),any());
    }

    @Test
    void selectedStyleAutomaticallyContinuesAfterGenerationWithoutAnotherConfirmation() {
        capture.imagesReady();
        workflow = runningWorkflow("STYLE_IMAGES", capture.getVersion());
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        when(workflowService.request(workflow)).thenReturn(Map.of("mainImageStyleTemplateId","12345678-1234-1234-1234-123456789012"));
        var styles = mock(ProductMainImageStyleService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "mainImageStyles", styles);
        when(styles.ensureReady(workflow,capture)).thenReturn(true);
        when(captureService.approve(eq(capture.getId()),any())).thenAnswer(invocation -> {capture.approve("user-1");return detail(capture);});
        processor.process(workflow.getId(),"worker-1");
        verify(captureService).approve(eq(capture.getId()),any());
        verify(workflowMapper).approvalSucceeded(eq(workflow.getId()),eq("worker-1"),eq(capture.getVersion()),any(),any());
    }

    @Test
    void noStyleChoiceLeavesTheOriginalApprovalPathUntouched() {
        capture.imagesReady();
        workflow = runningWorkflow("APPROVE", capture.getVersion());
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        when(workflowService.request(workflow)).thenReturn(Map.of());
        var styles = mock(ProductMainImageStyleService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(processor, "mainImageStyles", styles);
        when(captureService.approve(eq(capture.getId()),any())).thenAnswer(invocation -> {capture.approve("user-1");return detail(capture);});
        processor.process(workflow.getId(),"worker-1");
        org.mockito.Mockito.verifyNoInteractions(styles);
        verify(captureService).approve(eq(capture.getId()),any());
    }

    @Test
    void advancesCapturedProductThroughImageStageUsingStableDownstreamKey() {
        ProductImageProcessingJob job = new ProductImageProcessingJob(
                "org-a", capture.getId(), "user-1", "job-key", "DRY_RUN", 1, "{}");
        job.start();
        job.succeed("{}");
        ProductCapture ready = capture();
        ready.imagesReady();
        String workflowId = workflow.getId();
        when(workflowService.request(workflow)).thenReturn(Map.of());
        when(captureService.processImages(eq(capture.getId()), any(),
                eq("wf-" + workflowId + "-images"), any(PlatformPrincipal.class)))
                .thenReturn(new ProductCaptureService.ImageProcessResult(job, detail(ready), true, "DRY_RUN"));

        processor.process(workflowId, "worker-1");

        verify(workflowMapper).imageSucceeded(eq(workflowId), eq("worker-1"), eq(job.getId()),
                eq(ready.getVersion()), eq("APPROVE"), any(), any());
    }

    @Test
    void boundImageRetryUsesItsOwnKeyInsteadOfTheFailedOriginalWorkflowJob() {
        capture.startImageProcessing();
        workflow = runningWorkflow("IMAGES", capture.getVersion());
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        ProductImageProcessingJob original = new ProductImageProcessingJob("org-a", capture.getId(),
                "user-1", "wf-" + workflow.getId() + "-images", "AI_WATERMARK_V1", 17, "{}");
        original.fail("original interrupted request", "{}");
        ProductImageProcessingJob retryJob = new ProductImageProcessingJob("org-a", capture.getId(),
                "user-1", "user-authorized-image-retry", "AI_WATERMARK_V1", 17, "{}");
        retryJob.start();
        when(workflow.getImageJobId()).thenReturn(retryJob.getId());
        when(imageJobMapper.selectById(retryJob.getId())).thenReturn(retryJob);
        when(imageJobMapper.selectOne(any())).thenReturn(original);
        when(workflowService.request(workflow)).thenReturn(Map.of("visionWatermarkCheck", true));
        when(captureService.processImages(eq(capture.getId()), any(), eq(retryJob.getIdempotencyKey()), any()))
                .thenAnswer(invocation -> {
                    retryJob.succeed("{}");
                    capture.imagesReady();
                    return new ProductCaptureService.ImageProcessResult(retryJob, detail(capture), false, "AI_WATERMARK_V1");
                });

        processor.process(workflow.getId(), "worker-1");

        verify(captureService).processImages(eq(capture.getId()), any(), eq("user-authorized-image-retry"), any());
        verify(workflowMapper).imageSucceeded(eq(workflow.getId()), eq("worker-1"), eq(retryJob.getId()),
                eq(capture.getVersion()), eq("APPROVE"), any(), any());
        verify(imageJobMapper, never()).selectOne(any());
        verify(workflowMapper, never()).fail(any(), any(), any(), any());
        assertEquals("FAILED", original.getStatus());
        assertEquals("original interrupted request", original.getErrorMessage());
    }

    @Test
    void imageJobBoundToAnotherOrganizationIsRejectedWithoutLegacyFallback() {
        rejectInvalidBoundImageJob("org-other", capture.getId(), false);
    }

    @Test
    void imageJobBoundToAnotherCaptureIsRejectedWithoutLegacyFallback() {
        rejectInvalidBoundImageJob("org-a", "another-capture", false);
    }

    @Test
    void missingBoundImageJobIsRejectedWithoutLegacyFallback() {
        rejectInvalidBoundImageJob("org-a", capture.getId(), true);
    }

    private void rejectInvalidBoundImageJob(String organizationId, String captureId, boolean missing) {
        capture.startImageProcessing();
        workflow = runningWorkflow("IMAGES", capture.getVersion());
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        ProductImageProcessingJob invalid = new ProductImageProcessingJob(organizationId, captureId,
                "user-1", "foreign-retry-key", "AI_WATERMARK_V1", 17, "{}");
        invalid.start();
        when(workflow.getImageJobId()).thenReturn(invalid.getId());
        // Returning the actual foreign entity checks ownership in code, independently
        // of how a Mockito stub treats a LambdaQueryWrapper's SQL conditions.
        when(imageJobMapper.selectById(invalid.getId())).thenReturn(missing ? null : invalid);

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"),
                org.mockito.ArgumentMatchers.contains("不属于当前商品"), any());
        verify(imageJobMapper, never()).selectOne(any());
        verify(captureService, never()).processImages(any(), any(), any(), any());
        verify(workflowMapper, never()).retry(any(), any(), any(), any(), any());
    }

    @Test
    void providerValidationFailureBeforeExternalBoundaryReleasesTargetForRetry() {
        workflow = runningWorkflow("PUBLISH", 0);
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        capture.imagesReady();
        capture.approve("reviewer");
        when(captureService.get(eq(capture.getId()), any(PlatformPrincipal.class)))
                .thenReturn(detail(capture));
        when(workflowService.request(workflow)).thenReturn(Map.of(
                "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1"));
        ProductPublishAudit audit = new ProductPublishAudit("org-a", capture.getId(), "user-1",
                "audit-key", "SHOPEE", "shop-1", "DRY_RUN", true, "{}");
        audit.failed("provider rejected", "{}");
        String workflowId = workflow.getId();
        when(captureService.publish(eq(capture.getId()), any(),
                eq("wf-" + workflowId + "-publish"), any(PlatformPrincipal.class), any()))
                .thenReturn(new ProductCaptureService.PublishResult(audit, detail(capture)));

        processor.process(workflowId, "worker-1");

        verify(workflowMapper).fail(eq(workflowId), eq("worker-1"),
                eq("provider rejected"), any());
        verify(publishAttemptGuard, never()).enterExternalCreateBoundary(any(), any());
        verify(workflowMapper, never()).complete(any(), any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    void captureVersionDriftStopsBeforeAnyExternalStage() {
        workflow = runningWorkflow("IMAGES", 8);
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        when(imageJobMapper.selectOne(any())).thenReturn(null);

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"), any(), any());
        verify(captureService, never()).processImages(any(), any(), any(), any());
        verify(captureService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void disabledCreatorStopsBeforeReadingOrMutatingTheCapture() {
        when(workflowUser.getStatus()).thenReturn("DISABLED");

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"),
                eq("一键上传创建者已停用，工作流已停止"), any());
        verify(captureService, never()).get(any(), any());
        verify(captureService, never()).processImages(any(), any(), any(), any());
        verify(captureService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void creatorMovedToAnotherOrganizationCannotRunTheWorkflow() {
        when(workflowUser.getOrganizationId()).thenReturn("org-other");

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"),
                eq("一键上传创建者已不属于该企业，工作流已停止"), any());
        verify(captureService, never()).get(any(), any());
        verify(workflowService, never()).validateExecutionTarget(any());
    }

    @Test
    void revokedPublishPermissionStopsBeforeAnyCaptureOrProviderCall() {
        when(userMapper.selectPermissionCodes("user-1")).thenReturn(
                List.of("asset:watermark", "product:edit"));

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"),
                eq("一键上传创建者的商品发布权限已撤销，工作流已停止"), any());
        verify(captureService, never()).get(any(), any());
        verify(captureService, never()).processImages(any(), any(), any(), any());
    }

    @Test
    void revokedWatermarkPermissionStopsBeforeImageProviderCall() {
        when(userMapper.selectPermissionCodes("user-1")).thenReturn(
                List.of("product:edit", "product:publish"));

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"),
                eq("一键上传创建者的商品图片去水印权限已撤销，工作流已停止"), any());
        verify(captureService, never()).processImages(any(), any(), any(), any());
        verify(workflowMapper, never()).markStep(any(), any(), any(), anyLong(), any());
    }

    @Test
    void revokedEditPermissionStopsBeforeApproveCall() {
        capture.imagesReady();
        workflow = runningWorkflow("APPROVE", capture.getVersion());
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        when(captureService.get(eq(capture.getId()), any(PlatformPrincipal.class)))
                .thenReturn(detail(capture));
        when(userMapper.selectPermissionCodes("user-1")).thenReturn(
                List.of("asset:watermark", "product:publish"));

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"),
                eq("一键上传创建者的商品审核权限已撤销，工作流已停止"), any());
        verify(captureService, never()).approve(any(), any());
        verify(workflowMapper, never()).markStep(any(), any(), any(), anyLong(), any());
    }

    @Test
    void executionModeChangeStopsBeforeReadingOrProcessingCapture() {
        doThrow(new BusinessException(HttpStatus.CONFLICT,
                "该流程创建于 Dry Run 模式，请重新选择店铺"))
                .when(workflowService).validateExecutionTarget(workflow);

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"),
                eq("该流程创建于 Dry Run 模式，请重新选择店铺"), any());
        verify(captureService, never()).get(any(), any());
        verify(captureService, never()).processImages(any(), any(), any(), any());
        verify(captureService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void priorPublicationToAnotherShopDoesNotInvalidatePublishStage() {
        workflow = runningWorkflow("PUBLISH", 4);
        String workflowId = workflow.getId();
        String captureId = capture.getId();
        when(workflowMapper.selectById(workflowId)).thenReturn(workflow);
        ProductCapture published = mock(ProductCapture.class);
        when(published.getId()).thenReturn(captureId);
        when(published.getVersion()).thenReturn(5L);
        when(published.getStatus()).thenReturn("PUBLISHED");
        when(captureService.get(eq(captureId), any(PlatformPrincipal.class)))
                .thenReturn(detail(published));
        when(workflowService.request(workflow)).thenReturn(Map.of(
                "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-2"));
        ProductPublishAudit audit = new ProductPublishAudit("org-a", captureId, "user-1",
                "audit-key", "SHOPEE", "shop-2", "OFFICIAL", false, "{}");
        audit.publishing();
        audit.completed("external-product-2", "{}");
        when(captureService.publish(eq(captureId), any(),
                eq("wf-" + workflowId + "-publish"), any(PlatformPrincipal.class), any()))
                .thenReturn(new ProductCaptureService.PublishResult(audit, detail(published)));

        processor.process(workflowId, "worker-1");

        verify(captureService).publish(eq(captureId), any(),
                eq("wf-" + workflowId + "-publish"), any(PlatformPrincipal.class), any());
        verify(workflowMapper).complete(eq(workflowId), eq("worker-1"), eq(audit.getId()),
                eq(5L), eq(true), any());
    }

    @Test
    void transportBeforeCreateRetriesWithBoundedBudget() {
        for (int retries : new int[] {0, 3}) {
            org.mockito.Mockito.clearInvocations(workflowMapper);
            prepareVideoPendingWorkflow();
            String workflowId = workflow.getId();
            when(workflow.getRetryCount()).thenReturn(retries);
            when(captureService.publish(eq(capture.getId()), any(),
                    eq("wf-" + workflowId + "-publish"), any(PlatformPrincipal.class), any()))
                    .thenThrow(new com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException(
                            "店铺官方接口请求失败", new java.io.IOException("reset")));
            processor.process(workflow.getId(), "worker-1");
            if (retries == 0) {
                verify(workflowMapper).retry(eq(workflow.getId()), eq("worker-1"), any(), any(), any());
                verify(workflowMapper, never()).fail(any(), any(), any(), any());
            } else {
                verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"), any(), any());
                verify(workflowMapper, never()).retry(any(), any(), any(), any(), any());
            }
            verify(workflowMapper, never()).failUncertainPublication(any(), any(), any(), any());
        }
    }

    @Test
    void confirmedTitleRejectionReleasesDuplicateGuardButDoesNotAutoLoop() {
        workflow = runningWorkflow("PUBLISH", 0);
        String workflowId = workflow.getId();
        when(workflowMapper.selectById(workflowId)).thenReturn(workflow);
        capture.imagesReady();
        capture.approve("reviewer");
        when(captureService.get(eq(capture.getId()), any(PlatformPrincipal.class)))
                .thenReturn(detail(capture));
        when(workflowService.request(workflow)).thenReturn(Map.of(
                "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1"));
        when(publishAttemptGuard.enterExternalCreateBoundary(workflowId, "worker-1"))
                .thenReturn(true);
        when(captureService.publish(eq(capture.getId()), any(),
                eq("wf-" + workflowId + "-publish"), any(PlatformPrincipal.class), any()))
                .thenAnswer(invocation -> {
                    ProductPublishingProvider.ExternalCreateBoundary boundary = invocation.getArgument(4);
                    boundary.enter();
                    throw new IllegalStateException("Shopee 官方接口 /api/v2/product/add_item 失败：Product name length must be between 20 and 100 characters. Please adjust it.");
                });

        when(workflowMapper.failRejectedPublication(any(), any(), any(), any())).thenReturn(1);
        processor.process(workflowId, "worker-1");

        verify(publishAttemptGuard).enterExternalCreateBoundary(workflowId, "worker-1");
        verify(workflowMapper).failRejectedPublication(eq(workflowId), eq("worker-1"),
                org.mockito.ArgumentMatchers.contains("拒绝创建商品"), any());
        verify(workflowMapper, never()).retry(any(), any(), any(), any(), any());
        verify(workflowMapper, never()).fail(any(), any(), any(), any());
    }

    @Test
    void externalCreateTimeoutIsQuarantinedAndNeverScheduledForRetry() {
        workflow = runningWorkflow("PUBLISH", 0);
        String workflowId = workflow.getId();
        when(workflowMapper.selectById(workflowId)).thenReturn(workflow);
        capture.imagesReady();
        capture.approve("reviewer");
        when(captureService.get(eq(capture.getId()), any(PlatformPrincipal.class)))
                .thenReturn(detail(capture));
        when(workflowService.request(workflow)).thenReturn(Map.of(
                "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1"));
        when(publishAttemptGuard.enterExternalCreateBoundary(workflowId, "worker-1"))
                .thenReturn(true);
        when(captureService.publish(eq(capture.getId()), any(),
                eq("wf-" + workflowId + "-publish"), any(PlatformPrincipal.class), any()))
                .thenAnswer(invocation -> {
                    ProductPublishingProvider.ExternalCreateBoundary boundary = invocation.getArgument(4);
                    boundary.enter();
                    throw new IllegalStateException("create request timed out");
                });

        processor.process(workflowId, "worker-1");

        verify(publishAttemptGuard).enterExternalCreateBoundary(workflowId, "worker-1");
        verify(workflowMapper).failUncertainPublication(eq(workflowId), eq("worker-1"),
                org.mockito.ArgumentMatchers.contains("停止自动重试"), any());
        verify(workflowMapper, never()).retry(any(), any(), any(), any(), any());
        verify(workflowMapper, never()).fail(any(), any(), any(), any());
    }

    @Test
    void transcodingWaitDoesNotConsumeExhaustedGeneralRetryBudget() {
        prepareVideoPendingWorkflow();
        when(workflow.getRetryCount()).thenReturn(3);
        // The workflow can have spent hours generating images before video starts.
        when(workflow.getCreatedAt()).thenReturn(Instant.now().minusSeconds(7200));
        when(captureService.publish(eq(capture.getId()), any(), any(), any(), any()))
                .thenThrow(new VideoUploadPendingException("视频仍在转码"));

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).waitForMediaProgress(eq(workflow.getId()), eq("worker-1"),
                eq(capture.getVersion()), eq("视频仍在转码"), any(), any());
        verify(workflowMapper, never()).retry(any(), any(), any(), any(), any());
        verify(workflowMapper, never()).fail(any(), any(), any(), any());
        verify(publishAttemptGuard, never()).enterExternalCreateBoundary(any(), any());
    }

    @Test
    void persistedMediaWaitDeadlineStopsEndlessPollingAfterRestart() {
        prepareVideoPendingWorkflow();
        when(workflow.getMediaWaitStartedAt()).thenReturn(Instant.now().minusSeconds(1801));
        when(captureService.publish(eq(capture.getId()), any(), any(), any(), any()))
                .thenThrow(new VideoUploadPendingException("视频仍在转码"));

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).fail(eq(workflow.getId()), eq("worker-1"),
                org.mockito.ArgumentMatchers.contains("上传进度已保留"), any());
        verify(workflowMapper, never()).retry(any(), any(), any(), any(), any());
        verify(workflowMapper, never()).waitForMediaProgress(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void mediaPendingAfterExternalBoundaryCannotScheduleAnotherCreation() {
        prepareVideoPendingWorkflow();
        when(publishAttemptGuard.enterExternalCreateBoundary(workflow.getId(), "worker-1"))
                .thenReturn(true);
        when(captureService.publish(eq(capture.getId()), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ProductPublishingProvider.ExternalCreateBoundary boundary = invocation.getArgument(4);
                    boundary.enter();
                    throw new VideoUploadPendingException("结果尚未确认");
                });

        processor.process(workflow.getId(), "worker-1");

        verify(workflowMapper).failUncertainPublication(eq(workflow.getId()), eq("worker-1"), any(), any());
        verify(workflowMapper, never()).retry(any(), any(), any(), any(), any());
        verify(workflowMapper, never()).waitForMediaProgress(any(), any(), anyLong(), any(), any(), any());
    }

    private void prepareVideoPendingWorkflow() {
        capture.imagesReady();
        capture.approve("reviewer");
        workflow = runningWorkflow("PUBLISH", capture.getVersion());
        when(workflowMapper.selectById(workflow.getId())).thenReturn(workflow);
        when(workflowService.request(workflow)).thenReturn(Map.of(
                "confirmed", true, "destinationPlatform", "SHOPEE", "shopId", "shop-1"));
    }

    @Test
    void pollQuarantinesExpiredLiveSubmissionBeforeClaimingRetryableWork() {
        when(workflowMapper.failExpiredUncertainPublications(any(), any())).thenReturn(1);
        when(workflowMapper.findClaimableIds(any(), eq(10))).thenReturn(List.of());

        processor.poll();

        verify(workflowMapper).failExpiredUncertainPublications(any(),
                eq("外部平台创建请求可能已经发出，但执行节点租约已过期；系统已停止自动重试，请先到目标店铺核查"));
        verify(workflowMapper, never()).claim(any(), any(), any(), any());
    }

    private ProductPublishWorkflow runningWorkflow(String step, long expectedVersion) {
        ProductPublishWorkflow value = mock(ProductPublishWorkflow.class);
        when(value.getId()).thenReturn("workflow-1");
        when(value.getOrganizationId()).thenReturn("org-a");
        when(value.getCaptureId()).thenReturn(capture == null ? "capture-1" : capture.getId());
        when(value.getCreatedBy()).thenReturn("user-1");
        when(value.getStatus()).thenReturn("RUNNING");
        when(value.getLockedBy()).thenReturn("worker-1");
        when(value.getCurrentStep()).thenReturn(step);
        when(value.getExpectedCaptureVersion()).thenReturn(expectedVersion);
        when(value.getRequestJson()).thenReturn("{}");
        return value;
    }

    private ProductCapture capture() {
        return new ProductCapture("org-a", "user-1", "capture-key", "fingerprint", "SHOPEE",
                "https://shopee.ph/item-i.123.456", "123", "456", "Product", "Description",
                "Category", "Brand", "PHP", BigDecimal.TEN, BigDecimal.TEN, "{}", "{}");
    }

    private ProductCaptureService.CaptureDetail detail(ProductCapture value) {
        return new ProductCaptureService.CaptureDetail(value, List.of(), List.of(), List.of(), Map.of(), Map.of());
    }
}
