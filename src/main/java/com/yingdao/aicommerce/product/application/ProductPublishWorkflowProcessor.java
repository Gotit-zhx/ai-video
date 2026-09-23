package com.yingdao.aicommerce.product.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductImageProcessingJob;
import com.yingdao.aicommerce.product.domain.ProductPublishAudit;
import com.yingdao.aicommerce.product.domain.ProductPublishWorkflow;
import com.yingdao.aicommerce.product.application.provider.VideoUploadPendingException;
import com.yingdao.aicommerce.product.mapper.ProductImageProcessingJobMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishAuditMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishWorkflowMapper;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class ProductPublishWorkflowProcessor {
    private static final Logger log = LoggerFactory.getLogger(ProductPublishWorkflowProcessor.class);
    private static final int MAX_AUTOMATIC_ATTEMPTS = 3;
    private static final long MAX_MEDIA_WAIT_SECONDS = 30 * 60;
    private static final String EXPIRED_EXTERNAL_SUBMISSION_MESSAGE =
            "外部平台创建请求可能已经发出，但执行节点租约已过期；系统已停止自动重试，请先到目标店铺核查";

    private final ProductPublishWorkflowMapper workflowMapper;
    private final ProductImageProcessingJobMapper imageJobMapper;
    private final ProductPublishAuditMapper publishAuditMapper;
    private final PlatformUserMapper userMapper;
    private final ProductCaptureService captureService;
    private final ProductPublishWorkflowService workflowService;
    private final ProductPublishAttemptGuard publishAttemptGuard;
    private final Executor executor;
    private final int batchSize;
    private final long leaseSeconds;

    public ProductPublishWorkflowProcessor(
            ProductPublishWorkflowMapper workflowMapper,
            ProductImageProcessingJobMapper imageJobMapper,
            ProductPublishAuditMapper publishAuditMapper,
            PlatformUserMapper userMapper,
            ProductCaptureService captureService,
            ProductPublishWorkflowService workflowService,
            ProductPublishAttemptGuard publishAttemptGuard,
            @Qualifier("productPublishWorkflowExecutor") Executor executor,
            @Value("${app.product-publish-workflow.batch-size:10}") int configuredBatchSize,
            @Value("${app.product-publish-workflow.lease-seconds:3600}") long configuredLeaseSeconds) {
        this.workflowMapper = workflowMapper;
        this.imageJobMapper = imageJobMapper;
        this.publishAuditMapper = publishAuditMapper;
        this.userMapper = userMapper;
        this.captureService = captureService;
        this.workflowService = workflowService;
        this.publishAttemptGuard = publishAttemptGuard;
        this.executor = executor;
        this.batchSize = Math.max(1, Math.min(configuredBatchSize, 100));
        this.leaseSeconds = Math.max(60, Math.min(configuredLeaseSeconds, 7200));
    }

    @Scheduled(initialDelayString = "${app.product-publish-workflow.initial-delay:1000}",
            fixedDelayString = "${app.product-publish-workflow.poll-delay:1000}")
    public void poll() {
        Instant now = Instant.now();
        List<String> candidates;
        try {
            int uncertain = workflowMapper.failExpiredUncertainPublications(
                    now, EXPIRED_EXTERNAL_SUBMISSION_MESSAGE);
            if (uncertain > 0) {
                log.warn("Stopped automatic retry for {} expired LIVE publication workflow(s)", uncertain);
            }
            candidates = workflowMapper.findClaimableIds(now, batchSize);
        } catch (RuntimeException exception) {
            log.warn("Unable to poll product publish workflows", exception);
            return;
        }
        for (String id : candidates) {
            String worker = UUID.randomUUID().toString();
            if (workflowMapper.claim(id, worker, now, now.plusSeconds(leaseSeconds)) != 1) continue;
            try {
                executor.execute(() -> process(id, worker));
            } catch (RejectedExecutionException rejected) {
                workflowMapper.retry(id, worker, "后台执行队列繁忙，稍后自动重试",
                        now.plusSeconds(2), now);
            }
        }
    }

    void process(String id, String worker) {
        ProductPublishWorkflow workflow = workflowMapper.selectById(id);
        if (workflow == null || !worker.equals(workflow.getLockedBy())
                || !"RUNNING".equals(workflow.getStatus())) return;
        SubmissionState submission = new SubmissionState(
                workflow.isPublicationAttempted() && workflowService.liveExecution(workflow));
        try {
            if (submission.externalCreateMayHaveStarted()) {
                failUncertain(workflow, worker, "外部平台创建请求的既有结果尚未确认");
                return;
            }
            PlatformPrincipal principal = workflowPrincipal(workflow);
            // Freeze Dry Run vs LIVE semantics at creation and validate the selected shop before
            // any paid image processing or other workflow mutation is allowed to start.
            workflowService.validateExecutionTarget(workflow);
            ProductCapture capture = captureService.get(workflow.getCaptureId(), principal).capture();
            if (!versionIsExpectedOrOwned(workflow, capture)) {
                fail(workflow, worker, "商品草稿在一键上传执行期间已被修改，请重新确认后发起新流程");
                return;
            }
            if (Boolean.TRUE.equals(workflowService.request(workflow).get("visionWatermarkCheck"))
                    && workflow.getImageJobId() == null && !"IMAGE_PROCESSING".equals(capture.getStatus())) {
                processImages(workflow,worker,workflowPrincipal(workflow,"asset:watermark"),capture);
                return;
            }
            switch (capture.getStatus()) {
                case "CAPTURED", "FAILED" -> processImages(workflow, worker,
                        workflowPrincipal(workflow, "asset:watermark"), capture);
                case "IMAGE_PROCESSING" -> waitForImageProcessing(workflow, worker, capture);
                case "READY" -> approve(workflow, worker,
                        workflowPrincipal(workflow, "product:edit"), capture);
                case "APPROVED", "PUBLISHED" -> publish(workflow, worker,
                        workflowPrincipal(workflow), capture, submission);
                default -> fail(workflow, worker, "当前商品状态不能继续一键上传：" + capture.getStatus());
            }
        } catch (LostClaimException ignored) {
            // The current worker no longer owns the workflow. If the durable external-create
            // boundary was crossed, the lease recovery sweep quarantines it instead of retrying.
        } catch (BusinessException exception) {
            if (submission.externalCreateMayHaveStarted()) {
                failUncertain(workflow, worker, exception.getMessage());
                return;
            }
            if (exception.getStatus().is5xxServerError()
                    && workflow.getRetryCount() < MAX_AUTOMATIC_ATTEMPTS) {
                retry(workflow, worker, exception.getMessage());
            } else {
                fail(workflow, worker, exception.getMessage());
            }
        } catch (RuntimeException exception) {
            log.warn("Product publish workflow {} failed at {}", id, workflow.getCurrentStep(), exception);
            if (submission.externalCreateMayHaveStarted()) {
                failUncertain(workflow, worker, safeMessage(exception));
                return;
            }
            if (workflow.getRetryCount() < MAX_AUTOMATIC_ATTEMPTS) {
                retry(workflow, worker, safeMessage(exception));
            } else {
                fail(workflow, worker, safeMessage(exception));
            }
        }
    }

    private void processImages(ProductPublishWorkflow workflow, String worker,
                               PlatformPrincipal principal, ProductCapture capture) {
        owned(workflowMapper.markStep(workflow.getId(), worker, "IMAGES",
                capture.getVersion(), Instant.now()));
        Map<String, Object> request = imageRequest(workflow);
        // A user retry can be explicitly bound to this workflow after the original
        // image job failed. Resume that exact job instead of reopening the old key.
        ProductImageProcessingJob boundJob = workflow.getImageJobId() == null
                || workflow.getImageJobId().isBlank() ? null : ownImageJob(workflow);
        String key = boundJob == null ? imageIdempotencyKey(workflow) : boundJob.getIdempotencyKey();
        ProductCaptureService.ImageProcessResult result = captureService.processImages(
                workflow.getCaptureId(), request, key, principal);
        ProductCapture updated = result.capture().capture();
        if ("FAILED".equals(result.job().getStatus()) || "FAILED".equals(updated.getStatus())) {
            fail(workflow, worker, firstNonBlank(result.job().getErrorMessage(),
                    "商品图片处理失败"));
            return;
        }
        if (!"SUCCEEDED".equals(result.job().getStatus()) || "IMAGE_PROCESSING".equals(updated.getStatus())) {
            wait(workflow, worker, "IMAGES", result.job().getId(), null,
                    updated.getVersion(), 5, null);
            return;
        }
        String next = "READY".equals(updated.getStatus()) ? "APPROVE" : derivedStep(updated.getStatus());
        Instant now = Instant.now();
        owned(workflowMapper.imageSucceeded(workflow.getId(), worker, result.job().getId(),
                updated.getVersion(), next, now, now));
    }

    private void waitForImageProcessing(ProductPublishWorkflow workflow, String worker,
                                        ProductCapture capture) {
        ProductImageProcessingJob job = ownImageJob(workflow);
        if (job != null && "AI_WATERMARK_V1".equals(job.getProvider()) && "RUNNING".equals(job.getStatus())) {
            processImages(workflow,worker,workflowPrincipal(workflow,"asset:watermark"),capture);
            return;
        }
        if (job != null && "FAILED".equals(job.getStatus())) {
            fail(workflow, worker, firstNonBlank(job.getErrorMessage(), "商品图片处理失败"));
            return;
        }
        wait(workflow, worker, "IMAGES", job == null ? null : job.getId(), null,
                capture.getVersion(), 5, "图片仍在处理中");
    }

    private void approve(ProductPublishWorkflow workflow, String worker,
                         PlatformPrincipal principal, ProductCapture capture) {
        owned(workflowMapper.markStep(workflow.getId(), worker, "APPROVE",
                capture.getVersion(), Instant.now()));
        ProductCapture updated = captureService.approve(workflow.getCaptureId(), principal).capture();
        if (!List.of("APPROVED", "PUBLISHED").contains(updated.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "商品审核未进入可发布状态");
        }
        Instant now = Instant.now();
        owned(workflowMapper.approvalSucceeded(workflow.getId(), worker,
                updated.getVersion(), now, now));
    }

    private void publish(ProductPublishWorkflow workflow, String worker,
                         PlatformPrincipal principal, ProductCapture capture,
                         SubmissionState submission) {
        // Re-check immediately before the irreversible marketplace call in case configuration or
        // shop authorization changed while an earlier step was executing.
        workflowService.validateExecutionTarget(workflow);
        owned(workflowMapper.markStep(workflow.getId(), worker, "PUBLISH",
                capture.getVersion(), Instant.now()));
        Map<String, Object> request = workflowService.request(workflow);
        ProductCaptureService.PublishResult result;
        try {
            result = captureService.publish(
                workflow.getCaptureId(), request, publishIdempotencyKey(workflow), principal, () -> {
                    if (!publishAttemptGuard.enterExternalCreateBoundary(workflow.getId(), worker)) {
                        throw new LostClaimException();
                    }
                    submission.externalCreateStarted();
                });
        } catch (VideoUploadPendingException pending) {
            // A completed upload may still be transcoding or owned by another workflow.
            // Persist a separate clock across restarts; ordinary transient failures retain
            // their own retry budget. Never replay an uncertain product creation.
            if (submission.externalCreateMayHaveStarted()) throw pending;
            Instant now = Instant.now();
            if (workflow.getMediaWaitStartedAt() != null
                    && !now.isBefore(workflow.getMediaWaitStartedAt().plusSeconds(MAX_MEDIA_WAIT_SECONDS))) {
                fail(workflow, worker, "视频处理等待超过 30 分钟，商品尚未创建；上传进度已保留，可稍后重试：" + pending.getMessage());
            } else {
                owned(workflowMapper.waitForMediaProgress(workflow.getId(), worker,
                        capture.getVersion(), truncate(pending.getMessage()), now.plusSeconds(15), now));
            }
            return;
        }
        ProductPublishAudit audit = result.audit();
        ProductCapture updated = result.capture().capture();
        switch (audit.getStatus()) {
            case "PUBLISHED", "DRY_RUN_SUCCEEDED" -> {
                Instant now = Instant.now();
                owned(workflowMapper.complete(workflow.getId(), worker, audit.getId(),
                        updated.getVersion(), "PUBLISHED".equals(audit.getStatus()), now));
            }
            case "PENDING", "PUBLISHING" -> {
                if (submission.externalCreateMayHaveStarted()) {
                    failUncertain(workflow, worker, "外部平台尚未返回确定的创建结果");
                } else {
                    wait(workflow, worker, "PUBLISH", null,
                            audit.getId(), updated.getVersion(), 5, "发布接口仍在处理中");
                }
            }
            case "FAILED" -> {
                String message = firstNonBlank(audit.getErrorMessage(), "上传目标店铺失败");
                if (submission.externalCreateMayHaveStarted()) failUncertain(workflow, worker, message);
                else fail(workflow, worker, message);
            }
            default -> fail(workflow, worker, "未知的发布审计状态：" + audit.getStatus());
        }
    }

    private boolean versionIsExpectedOrOwned(ProductPublishWorkflow workflow,
                                             ProductCapture capture) {
        if (workflow.getExpectedCaptureVersion() == capture.getVersion()) return true;
        return switch (workflow.getCurrentStep()) {
            case "IMAGES" -> ownImageJob(workflow) != null;
            // Approval and publication only advance state; user edits always reset a draft to
            // CAPTURED. Accepting a later APPROVED/PUBLISHED version therefore lets independent
            // workflows publish the same approved product to different shops without mistaking a
            // prior shop publication for a content edit.
            case "APPROVE" -> capture.getVersion() > workflow.getExpectedCaptureVersion()
                    && List.of("APPROVED", "PUBLISHED").contains(capture.getStatus());
            case "PUBLISH" -> ownPublishAudit(workflow) != null
                    || capture.getVersion() > workflow.getExpectedCaptureVersion()
                    && "PUBLISHED".equals(capture.getStatus());
            default -> false;
        };
    }

    private ProductImageProcessingJob ownImageJob(ProductPublishWorkflow workflow) {
        String boundId = workflow.getImageJobId();
        if (boundId != null && !boundId.isBlank()) {
            ProductImageProcessingJob bound = imageJobMapper.selectById(boundId);
            if (bound == null || !boundId.equals(bound.getId())
                    || !workflow.getOrganizationId().equals(bound.getOrganizationId())
                    || !workflow.getCaptureId().equals(bound.getCaptureId())
                    || bound.getIdempotencyKey() == null || bound.getIdempotencyKey().isBlank()) {
                throw new BusinessException(HttpStatus.CONFLICT, "工作流绑定的图片任务不存在或不属于当前商品，已停止处理");
            }
            return bound;
        }
        return imageJobMapper.selectOne(new LambdaQueryWrapper<ProductImageProcessingJob>()
                .eq(ProductImageProcessingJob::getOrganizationId, workflow.getOrganizationId())
                .eq(ProductImageProcessingJob::getCaptureId, workflow.getCaptureId())
                .eq(ProductImageProcessingJob::getIdempotencyKey, imageIdempotencyKey(workflow))
                .last("LIMIT 1"));
    }

    private ProductPublishAudit ownPublishAudit(ProductPublishWorkflow workflow) {
        return publishAuditMapper.selectOne(new LambdaQueryWrapper<ProductPublishAudit>()
                .eq(ProductPublishAudit::getOrganizationId, workflow.getOrganizationId())
                .eq(ProductPublishAudit::getCaptureId, workflow.getCaptureId())
                .eq(ProductPublishAudit::getIdempotencyKey, publishIdempotencyKey(workflow))
                .last("LIMIT 1"));
    }

    private Map<String, Object> imageRequest(ProductPublishWorkflow workflow) {
        Map<String, Object> full = workflowService.request(workflow);
        Map<String, Object> result = new LinkedHashMap<>();
        Object options = full.get("imageProcessing");
        if (options instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        result.put("workflowId", workflow.getId());
        return result;
    }

    private void wait(ProductPublishWorkflow workflow, String worker, String step,
                      String imageJobId, String publishAuditId, long captureVersion,
                      long delaySeconds, String message) {
        Instant now = Instant.now();
        owned(workflowMapper.waitForProgress(workflow.getId(), worker, step, imageJobId,
                publishAuditId, captureVersion, now.plusSeconds(delaySeconds), now));
    }

    private void retry(ProductPublishWorkflow workflow, String worker, String message) {
        int exponent = Math.max(1, Math.min(workflow.getRetryCount() + 1, 5));
        long delay = Math.min(60, 1L << exponent);
        Instant now = Instant.now();
        owned(workflowMapper.retry(workflow.getId(), worker, truncate(message),
                now.plusSeconds(delay), now));
    }

    private void fail(ProductPublishWorkflow workflow, String worker, String message) {
        owned(workflowMapper.fail(workflow.getId(), worker, truncate(message), Instant.now()));
    }

    // Only an exact ADD_ITEM validation rejection is safe to release. Timeouts, transport
    // failures, later variation errors and unknown API failures retain the duplicate guard.
    static boolean isConfirmedBrandRejection(String detail) {
        return "Shopee 官方接口 /api/v2/product/add_item 失败：Brand information required".equals(detail);
    }

    static boolean isConfirmedStockRejection(String detail) {
        return "Shopee 官方接口 /api/v2/product/add_item 失败：Product Description : invalid field seller_stock, value must Not Null".equals(detail);
    }

    static boolean isConfirmedTitleRejection(String detail) {
        return detail != null && detail.matches("Shopee 官方接口 /api/v2/product/add_item 失败：Product name length must be between [0-9]{1,4} and [0-9]{1,4} characters[.] Please adjust it[.]");
    }

    private void failUncertain(ProductPublishWorkflow workflow, String worker, String detail) {
        if (isConfirmedBrandRejection(detail) || isConfirmedStockRejection(detail) || isConfirmedTitleRejection(detail)
                || "Shopee 官方接口 /api/v2/product/add_item 失败：Parcel size is required. Please fill in it.".equals(detail)) {
            owned(workflowMapper.failRejectedPublication(workflow.getId(), worker,
                    "Shopee 已拒绝创建商品，请使用修正后的参数重新提交：" + detail, Instant.now()));
            return;
        }
        String message = "外部平台创建请求可能已经发出，系统已停止自动重试；请先到目标店铺核查："
                + firstNonBlank(detail, "平台结果未知");
        owned(workflowMapper.failUncertainPublication(
                workflow.getId(), worker, truncate(message), Instant.now()));
    }

    private String derivedStep(String status) {
        return switch (status) {
            case "READY" -> "APPROVE";
            case "APPROVED", "PUBLISHED" -> "PUBLISH";
            default -> "IMAGES";
        };
    }

    private PlatformPrincipal workflowPrincipal(ProductPublishWorkflow workflow,
                                                String... additionalPermissions) {
        PlatformUser user = userMapper.selectById(workflow.getCreatedBy());
        if (user == null) {
            throw forbidden("一键上传创建者已不存在，工作流已停止");
        }
        if (!workflow.getOrganizationId().equals(user.getOrganizationId())) {
            throw forbidden("一键上传创建者已不属于该企业，工作流已停止");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw forbidden("一键上传创建者已停用，工作流已停止");
        }
        List<String> roles = safeList(userMapper.selectRoleCodes(user.getId()));
        List<String> permissions = safeList(userMapper.selectPermissionCodes(user.getId()));
        requirePermission(permissions, "product:publish", "商品发布");
        for (String permission : additionalPermissions) {
            String label = "asset:watermark".equals(permission) ? "商品图片去水印" : "商品审核";
            requirePermission(permissions, permission, label);
        }
        return new PlatformPrincipal(user.getId(), user.getOrganizationId(), "workflow",
                user.getUsername(), user.getDisplayName(), user.getPasswordHash(), true,
                user.getRegistrationSource(), roles, permissions);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private void requirePermission(List<String> permissions, String required, String label) {
        if (!permissions.contains(required)) {
            throw forbidden("一键上传创建者的" + label + "权限已撤销，工作流已停止");
        }
    }

    private BusinessException forbidden(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, message);
    }

    private String imageIdempotencyKey(ProductPublishWorkflow workflow) {
        return "wf-" + workflow.getId() + "-images";
    }

    private String publishIdempotencyKey(ProductPublishWorkflow workflow) {
        return "wf-" + workflow.getId() + "-publish";
    }

    private void owned(int rows) {
        if (rows != 1) throw new LostClaimException();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String safeMessage(Throwable exception) {
        return firstNonBlank(exception.getMessage(), "一键上传后台任务执行失败");
    }

    private String truncate(String message) {
        String safe = firstNonBlank(message, "一键上传后台任务执行失败");
        return safe.substring(0, Math.min(safe.length(), 2000));
    }

    private static final class LostClaimException extends RuntimeException {}

    private static final class SubmissionState {
        private boolean externalCreateMayHaveStarted;

        private SubmissionState(boolean externalCreateMayHaveStarted) {
            this.externalCreateMayHaveStarted = externalCreateMayHaveStarted;
        }

        private boolean externalCreateMayHaveStarted() {
            return externalCreateMayHaveStarted;
        }

        private void externalCreateStarted() {
            externalCreateMayHaveStarted = true;
        }
    }
}
