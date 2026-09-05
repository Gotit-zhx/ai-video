package com.yingdao.aicommerce.product.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductImageProcessingJob;
import com.yingdao.aicommerce.product.domain.ProductPublishAudit;
import com.yingdao.aicommerce.product.domain.ProductPublishWorkflow;
import com.yingdao.aicommerce.product.mapper.ProductCaptureMapper;
import com.yingdao.aicommerce.product.mapper.ProductImageProcessingJobMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishAuditMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishWorkflowMapper;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.application.ShopIntegrationSettings;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class ProductPublishWorkflowService {
    private static final int MAX_REQUEST_JSON_LENGTH = 2_000_000;
    private static final String IDEMPOTENCY_PATTERN = "[A-Za-z0-9._:-]{8,128}";
    private static final String DRY_RUN_SHOP = "dry-run-shop";
    private static final String MODE_DRY_RUN = "DRY_RUN";
    private static final String MODE_LIVE = "LIVE";
    private static final String REMOTE_STATUS_PATTERN = "[A-Z][A-Z0-9_]{0,63}";

    private final ProductPublishWorkflowMapper workflowMapper;
    private final ProductCaptureMapper captureMapper;
    private final ProductImageProcessingJobMapper imageJobMapper;
    private final ProductPublishAuditMapper publishAuditMapper;
    private final ShopMapper shopMapper;
    private final ProductCaptureService captureService;
    private final ShopIntegrationSettings shopSettings;
    private final ObjectMapper objectMapper;

    public ProductPublishWorkflowService(ProductPublishWorkflowMapper workflowMapper,
                                         ProductCaptureMapper captureMapper,
                                         ProductImageProcessingJobMapper imageJobMapper,
                                         ProductPublishAuditMapper publishAuditMapper,
                                         ShopMapper shopMapper,
                                         ProductCaptureService captureService,
                                         ShopIntegrationSettings shopSettings,
                                         ObjectMapper objectMapper) {
        this.workflowMapper = workflowMapper;
        this.captureMapper = captureMapper;
        this.imageJobMapper = imageJobMapper;
        this.publishAuditMapper = publishAuditMapper;
        this.shopMapper = shopMapper;
        this.captureService = captureService;
        this.shopSettings = shopSettings;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreateResult create(String captureId, Map<String, Object> request,
                               String idempotencyKey, PlatformPrincipal principal) {
        String key = requireIdempotencyKey(idempotencyKey);
        Map<String, Object> body = mutableMap(request);
        if (!confirmed(body.get("confirmed"))) {
            throw badRequest("一键上传前必须明确提交 confirmed=true");
        }
        String destination = destination(body.get("destinationPlatform"));
        String requestedShopId = requiredShopId(body.get("shopId"));
        Object bodyKey = body.get("idempotencyKey");
        if (bodyKey != null && !String.valueOf(bodyKey).isBlank()
                && !key.equals(String.valueOf(bodyKey).trim())) {
            throw badRequest("请求体与请求头中的幂等键不一致");
        }
        body.put("confirmed", true);
        body.put("destinationPlatform", destination);
        // Keep the sentinel in the immutable request so ProductCaptureService can execute its
        // existing Dry Run contract, while the relational shop_id remains a truthful nullable FK.
        body.put("shopId", requestedShopId);
        body.put("idempotencyKey", key);

        ProductPublishWorkflow sameKey = workflowMapper.findByIdempotency(
                principal.organizationId(), key);
        if (sameKey != null) {
            body.put("executionMode", executionMode(sameKey));
            String fingerprint = fingerprint(captureId, sameKey.getCaptureVersion(), body);
            verifySameRequest(sameKey, captureId, fingerprint);
            return new CreateResult(view(sameKey), false, false);
        }

        String executionMode = currentExecutionMode();
        // This is server-owned. A browser cannot label a request Dry Run while live publishing is on.
        body.put("executionMode", executionMode);

        ProductCaptureService.CaptureDetail captureDetail = captureService.get(captureId, principal);
        ProductCapture capture = captureDetail.capture();
        String initialStep = initialStep(capture.getStatus());
        requireStagePermissions(initialStep, principal);
        String storedShopId = storedShopId(requestedShopId, executionMode);
        if (storedShopId != null) {
            validateShop(principal.organizationId(), storedShopId, destination);
        }
        String requestJson = writeJson(body);
        if (requestJson.length() > MAX_REQUEST_JSON_LENGTH) {
            throw badRequest("一键上传参数不能超过 2 MB");
        }
        String fingerprint = fingerprint(captureId, capture.getVersion(), body);
        String targetKey = sha256(principal.organizationId() + ":" + captureId + ":"
                + destination + ":" + requestedShopId);

        ProductPublishWorkflow target = workflowMapper.findByPublicationTarget(targetKey);
        CreateResult reused = resolvePublicationTarget(target, captureId, capture.getVersion(), fingerprint);
        if (reused != null) return reused;

        ProductPublishWorkflow workflow = new ProductPublishWorkflow(
                principal.organizationId(), captureId, principal.userId(), key, fingerprint,
                targetKey, destination, storedShopId, capture.getVersion(), requestJson, initialStep
        );
        try {
            workflowMapper.insert(workflow);
            return new CreateResult(view(workflow), true, false);
        } catch (DuplicateKeyException duplicate) {
            ProductPublishWorkflow keyWinner = workflowMapper.findByIdempotency(
                    principal.organizationId(), key);
            if (keyWinner != null) {
                verifySameRequest(keyWinner, captureId, fingerprint);
                return new CreateResult(view(keyWinner), false, false);
            }
            ProductPublishWorkflow targetWinner = workflowMapper.findByPublicationTarget(targetKey);
            CreateResult concurrentTarget = resolvePublicationTarget(
                    targetWinner, captureId, capture.getVersion(), fingerprint);
            if (concurrentTarget != null) return concurrentTarget;
            throw duplicate;
        }
    }

    @Transactional(readOnly = true)
    public WorkflowView get(String captureId, String workflowId, PlatformPrincipal principal) {
        ProductPublishWorkflow workflow = workflowMapper.selectOne(
                new LambdaQueryWrapper<ProductPublishWorkflow>()
                        .eq(ProductPublishWorkflow::getId, workflowId)
                        .eq(ProductPublishWorkflow::getCaptureId, captureId)
                        .eq(ProductPublishWorkflow::getOrganizationId, principal.organizationId())
                        .last("LIMIT 1"));
        if (workflow == null) throw notFound();
        return view(workflow);
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list(String captureId, int requestedLimit,
                                   PlatformPrincipal principal) {
        ProductCapture capture = captureMapper.selectOne(new LambdaQueryWrapper<ProductCapture>()
                .eq(ProductCapture::getId, captureId)
                .eq(ProductCapture::getOrganizationId, principal.organizationId())
                .last("LIMIT 1"));
        if (capture == null) throw new BusinessException(HttpStatus.NOT_FOUND, "商品采集草稿不存在");
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return workflowMapper.selectList(new LambdaQueryWrapper<ProductPublishWorkflow>()
                        .eq(ProductPublishWorkflow::getCaptureId, captureId)
                        .eq(ProductPublishWorkflow::getOrganizationId, principal.organizationId())
                        .orderByDesc(ProductPublishWorkflow::getCreatedAt)
                        .last("LIMIT " + limit))
                .stream().map(this::view).toList();
    }

    Map<String, Object> request(ProductPublishWorkflow workflow) {
        try {
            return new LinkedHashMap<>(objectMapper.readValue(
                    workflow.getRequestJson(), new TypeReference<Map<String, Object>>() {}));
        } catch (Exception exception) {
            throw new IllegalStateException("一键上传工作流参数已损坏", exception);
        }
    }

    boolean liveExecution(ProductPublishWorkflow workflow) {
        return MODE_LIVE.equals(executionMode(workflow));
    }

    void validateExecutionTarget(ProductPublishWorkflow workflow) {
        String createdMode = executionMode(workflow);
        String currentMode = currentExecutionMode();
        if (!createdMode.equals(currentMode)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    MODE_DRY_RUN.equals(createdMode)
                            ? "该流程创建于 Dry Run 模式；当前已切换为真实发布，请重新选择店铺并发起新流程"
                            : "该流程创建于真实发布模式；当前发布模式已变化，请重新确认并发起新流程");
        }
        if (workflow.getShopId() == null) {
            if (MODE_LIVE.equals(createdMode)) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "该流程以 Dry Run 创建且没有真实目标店铺；当前已切换为真实发布，请重新选择店铺并发起新流程");
            }
            if (!DRY_RUN_SHOP.equals(requestedShopId(workflow))) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "Dry Run 工作流缺少有效的目标店铺标记，请重新发起");
            }
            return;
        }
        validateShop(workflow.getOrganizationId(), workflow.getShopId(),
                workflow.getDestinationPlatform());
    }

    private CreateResult resolvePublicationTarget(ProductPublishWorkflow target,
                                                  String captureId, long captureVersion,
                                                  String fingerprint) {
        if (target == null) return null;
        if (!captureId.equals(target.getCaptureId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "目标店铺刊登占位发生冲突");
        }
        if ("SUCCEEDED".equals(target.getStatus())
                || "FAILED".equals(target.getStatus()) && target.isPublicationAttempted()) {
            return new CreateResult(view(target), false, true);
        }
        if (List.of("PENDING", "RUNNING").contains(target.getStatus())) {
            if (target.getCaptureVersion() == captureVersion
                    && target.getRequestFingerprint().equals(fingerprint)) {
                return new CreateResult(view(target), false, true);
            }
            throw new BusinessException(HttpStatus.CONFLICT,
                    "该商品正在上传到所选店铺，请等待现有流程完成");
        }
        return null;
    }

    private void validateShop(String organizationId, String shopId, String destination) {
        String shopPlatform = "TIKTOK".equals(destination) ? "TIKTOK_SHOP" : destination;
        Shop shop = shopMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getId, shopId)
                .eq(Shop::getOrganizationId, organizationId)
                .eq(Shop::getPlatform, shopPlatform)
                .last("LIMIT 1"));
        if (shop == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "目标授权店铺不存在或平台不匹配");
        }
        if (!"ACTIVE".equals(shop.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "目标店铺授权已失效，请重新授权");
        }
    }

    private String initialStep(String captureStatus) {
        return switch (captureStatus == null ? "" : captureStatus.toUpperCase(Locale.ROOT)) {
            case "CAPTURED", "FAILED" -> "IMAGES";
            case "READY" -> "APPROVE";
            case "APPROVED", "PUBLISHED" -> "PUBLISH";
            case "IMAGE_PROCESSING" -> throw new BusinessException(HttpStatus.CONFLICT,
                    "商品图片正在由另一项任务处理，请完成后再一键上传");
            default -> throw new BusinessException(HttpStatus.CONFLICT, "当前商品状态不能一键上传");
        };
    }

    private void requireStagePermissions(String initialStep, PlatformPrincipal principal) {
        if ("IMAGES".equals(initialStep)) {
            requireAuthority(principal, "asset:watermark", "当前账号没有商品图片去水印权限");
            requireAuthority(principal, "product:edit", "当前账号没有商品审核权限");
        } else if ("APPROVE".equals(initialStep)) {
            requireAuthority(principal, "product:edit", "当前账号没有商品审核权限");
        }
    }

    private void requireAuthority(PlatformPrincipal principal, String authority, String message) {
        boolean granted = principal.getAuthorities().stream()
                .anyMatch(item -> authority.equals(item.getAuthority())
                        || "ROLE_ADMIN".equals(item.getAuthority()));
        if (!granted) throw new BusinessException(HttpStatus.FORBIDDEN, message);
    }

    private WorkflowView view(ProductPublishWorkflow workflow) {
        ProductCapture capture = captureMapper.selectOne(new LambdaQueryWrapper<ProductCapture>()
                .eq(ProductCapture::getId, workflow.getCaptureId())
                .eq(ProductCapture::getOrganizationId, workflow.getOrganizationId())
                .last("LIMIT 1"));
        ProductImageProcessingJob imageJob = workflow.getImageJobId() == null ? null
                : imageJobMapper.selectOne(new LambdaQueryWrapper<ProductImageProcessingJob>()
                        .eq(ProductImageProcessingJob::getId, workflow.getImageJobId())
                        .eq(ProductImageProcessingJob::getCaptureId, workflow.getCaptureId())
                        .eq(ProductImageProcessingJob::getOrganizationId, workflow.getOrganizationId())
                        .last("LIMIT 1"));
        ProductPublishAudit audit = workflow.getPublishAuditId() == null ? null
                : publishAuditMapper.selectOne(new LambdaQueryWrapper<ProductPublishAudit>()
                        .eq(ProductPublishAudit::getId, workflow.getPublishAuditId())
                        .eq(ProductPublishAudit::getCaptureId, workflow.getCaptureId())
                        .eq(ProductPublishAudit::getOrganizationId, workflow.getOrganizationId())
                        .last("LIMIT 1"));
        return new WorkflowView(
                workflow.getId(), workflow.getCaptureId(), workflow.getCaptureVersion(),
                capture == null ? null : capture.getVersion(), capture == null ? null : capture.getStatus(),
                workflow.getDestinationPlatform(), workflow.getShopId() == null
                        ? requestedShopId(workflow) : workflow.getShopId(), executionMode(workflow),
                workflow.getStatus(),
                workflow.getCurrentStep(), workflow.getAttemptCount(), workflow.getRetryCount(),
                workflow.isPublicationAttempted(),
                stage(imageJob == null ? null : imageJob.getId(), imageJob == null ? null : imageJob.getStatus(),
                        imageJob == null ? null : imageJob.getErrorMessage()),
                audit(audit), workflow.getErrorMessage(), workflow.getCreatedAt(), workflow.getUpdatedAt(),
                workflow.getCompletedAt()
        );
    }

    private StageView stage(String id, String status, String error) {
        return id == null ? null : new StageView(id, status, error);
    }

    private PublishStageView audit(ProductPublishAudit audit) {
        if (audit == null) return null;
        return new PublishStageView(audit.getId(), audit.getStatus(), audit.isDryRun(),
                audit.getProvider(), audit.getExternalId(), remoteStatus(audit),
                audit.getErrorMessage());
    }

    private String remoteStatus(ProductPublishAudit audit) {
        // A Dry Run can never truthfully have a marketplace-side status, even if old or
        // malformed audit data happens to contain a similarly named field.
        if (audit.isDryRun() || audit.getResponseJson() == null
                || audit.getResponseJson().isBlank()) return null;
        try {
            Map<String, Object> response = objectMapper.readValue(
                    audit.getResponseJson(), new TypeReference<Map<String, Object>>() {});
            Object value = response.get("remoteStatus");
            if (value == null) return null;
            String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            return normalized.matches(REMOTE_STATUS_PATTERN) ? normalized : null;
        } catch (Exception ignored) {
            // Workflow reads must remain available even when a historical provider receipt
            // was written by an older version or became malformed.
            return null;
        }
    }

    private void verifySameRequest(ProductPublishWorkflow workflow, String captureId,
                                   String fingerprint) {
        if (!captureId.equals(workflow.getCaptureId())
                || !fingerprint.equals(workflow.getRequestFingerprint())) {
            throw new BusinessException(HttpStatus.CONFLICT, "幂等键已用于另一份一键上传请求");
        }
    }

    private String fingerprint(String captureId, long captureVersion, Map<String, Object> body) {
        Map<String, Object> request = new LinkedHashMap<>(body);
        // The transport-level key identifies a retry but is not part of the business request.
        // Excluding it lets the publication-target guard coalesce concurrent equivalent requests
        // even when a client regenerated its Idempotency-Key after losing the first response.
        request.remove("idempotencyKey");
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("captureId", captureId);
        envelope.put("captureVersion", captureVersion);
        envelope.put("request", canonical(request));
        return sha256(writeJson(envelope));
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    private Map<String, Object> mutableMap(Map<String, Object> value) {
        if (value == null) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, child) -> result.put(key, mutableValue(child)));
        return result;
    }

    private Object mutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), mutableValue(child)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(child -> result.add(mutableValue(child)));
            return result;
        }
        return value;
    }

    private String destination(Object value) {
        MarketplacePlatform platform = MarketplacePlatform.parse(value == null ? null : String.valueOf(value));
        return platform == MarketplacePlatform.TIKTOK_SHOP ? "TIKTOK" : "SHOPEE";
    }

    private String requiredShopId(Object value) {
        String id = value == null ? "" : String.valueOf(value).trim();
        if (!id.matches("[A-Za-z0-9-]{1,128}")) throw badRequest("一键上传必须指定有效的目标店铺 ID");
        // Internal shop IDs are generated UUIDs. Canonicalising their case prevents the same
        // case-insensitive MySQL row from producing two different publication-target hashes.
        return id.toLowerCase(Locale.ROOT);
    }

    private String storedShopId(String requestedShopId, String executionMode) {
        if (!DRY_RUN_SHOP.equals(requestedShopId)) return requestedShopId;
        if (!MODE_DRY_RUN.equals(executionMode)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "真实发布已启用，请选择已授权的目标店铺");
        }
        return null;
    }

    private String requestedShopId(ProductPublishWorkflow workflow) {
        Object value = request(workflow).get("shopId");
        return value == null ? null : String.valueOf(value).trim();
    }

    private String executionMode(ProductPublishWorkflow workflow) {
        Object value = request(workflow).get("executionMode");
        String mode = value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        if (!List.of(MODE_DRY_RUN, MODE_LIVE).contains(mode)) {
            throw new IllegalStateException("一键上传工作流缺少服务端执行模式");
        }
        return mode;
    }

    private String currentExecutionMode() {
        String provider = shopSettings.publishingProvider();
        if (provider == null || provider.isBlank() || MODE_DRY_RUN.equalsIgnoreCase(provider)) {
            return MODE_DRY_RUN;
        }
        if (shopSettings.effectiveLivePublishing()) return MODE_LIVE;
        throw new BusinessException(HttpStatus.CONFLICT,
                "真实店铺发布供应商已配置，但发布开关尚未启用；系统没有创建上传任务");
    }

    private String requireIdempotencyKey(String value) {
        String key = value == null ? "" : value.trim();
        if (!key.matches(IDEMPOTENCY_PATTERN)) {
            throw badRequest("Idempotency-Key 必须为 8-128 位字母、数字或 . _ : -");
        }
        return key;
    }

    private boolean confirmed(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw badRequest("一键上传参数包含无法保存的内容");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "一键上传工作流不存在");
    }

    public record CreateResult(WorkflowView workflow, boolean created, boolean publicationReused) {}

    public record WorkflowView(String id, String captureId, long requestedCaptureVersion,
                               Long captureVersion, String captureStatus,
                               String destinationPlatform, String shopId, String executionMode,
                               String status,
                               String currentStep, int attemptCount, int retryCount,
                               boolean publicationAttempted,
                               StageView imageProcessing, PublishStageView publication,
                               String errorMessage, Instant createdAt, Instant updatedAt,
                               Instant completedAt) {}

    public record StageView(String id, String status, String errorMessage) {}

    public record PublishStageView(String id, String status, boolean dryRun, String provider,
                                   String externalId, String remoteStatus, String errorMessage) {}
}
