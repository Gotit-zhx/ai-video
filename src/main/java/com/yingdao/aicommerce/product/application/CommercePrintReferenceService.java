package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable single-source → one AI sheet → four archived views. Browser polling never calls a model. */
@Service
public class CommercePrintReferenceService {
    public record Request(String sourceImageId, boolean alreadyPlain, String requestId) {}
    public record View(String id, String status, String stage, String referenceSetId, String originalImageId,
                       Map<String,String> baseImages, String message, boolean retryable, String sheetImageId,
                       boolean alreadyPlain, String promptVersion) {}
    private final CommercePrintReferenceRepository jobs;
    private final ModelConfigService models;
    private final CommerceDesignImageGateway gateway;
    private final CommerceImageArchive archive;
    private final MarketplaceImageDownloader downloader;
    private final ObjectMapper json;

    public CommercePrintReferenceService(CommercePrintReferenceRepository jobs, ModelConfigService models,
            CommerceDesignImageGateway gateway, CommerceImageArchive archive, MarketplaceImageDownloader downloader, ObjectMapper json) {
        this.jobs = jobs; this.models = models; this.gateway = gateway; this.archive = archive; this.downloader = downloader; this.json = json;
    }
    @Transactional
    public View start(Request request, PlatformPrincipal user) {
        access(user, true);
        if (request == null || request.sourceImageId() == null || !request.sourceImageId().matches("[a-f0-9]{64}")
                || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{8,80}"))
            throw error(HttpStatus.BAD_REQUEST, "请上传原始图片并提供有效请求编号");
        var binding = jobs.requestKey(user.organizationId(), request.requestId());
        if (binding != null) {
            validateBinding(binding, request);
            return reuse(required(user.organizationId(), binding.jobId()));
        }
        var existing = jobs.byRequest(user.organizationId(), request.requestId());
        if (existing != null) {
            if (!request.sourceImageId().equals(existing.originalImageId()) || request.alreadyPlain() != existing.alreadyPlain())
                throw error(HttpStatus.CONFLICT, "同一请求编号不能更换原图或处理方式");
            bind(user.organizationId(), request, existing.id());
            return reuse(existing);
        }
        // Source/version de-duplication also protects a page reload that accidentally creates a new request id.
        existing = jobs.bySource(user.organizationId(), request.sourceImageId(), CommercePrintReferencePrompt.VERSION);
        if (existing != null) { bind(user.organizationId(), request, existing.id()); return reuse(existing); }
        source(user.organizationId(), request.sourceImageId());
        var model = models.imageConnection(user.organizationId()).orElseThrow(() -> error(HttpStatus.CONFLICT, "当前公司没有可用的图片模型"));
        String id = UUID.randomUUID().toString();
        jobs.insert(new CommercePrintReferenceRepository.Job(id, user.organizationId(), user.userId(), request.requestId(),
                request.sourceImageId(), request.alreadyPlain(), CommercePrintReferencePrompt.VERSION, CommercePrintReferencePrompt.build(request.alreadyPlain()),
                model.id(), model.modelId(), "QUEUED", "QUEUED", "等待生成同款四面参考图", null, null, null, null, 0));
        // Unique constraints arbitrate concurrent tabs before any provider call is possible.
        var stored = jobs.byRequest(user.organizationId(), request.requestId());
        if (stored != null && (!request.sourceImageId().equals(stored.originalImageId()) || request.alreadyPlain() != stored.alreadyPlain()))
            throw error(HttpStatus.CONFLICT, "同一请求编号不能更换原图或处理方式");
        if (stored == null) stored = jobs.bySource(user.organizationId(), request.sourceImageId(), CommercePrintReferencePrompt.VERSION);
        if (stored == null) throw error(HttpStatus.CONFLICT, "参考任务未保存，请使用同一请求编号重试");
        bind(user.organizationId(), request, stored.id());
        return view(stored);
    }
    private void bind(String org, Request request, String job) {
        var key = jobs.bindRequest(org, request.requestId(), request.sourceImageId(), request.alreadyPlain(), job);
        validateBinding(key, request);
        if (!job.equals(key.jobId())) throw error(HttpStatus.CONFLICT, "请求已关联另一个任务，请使用原请求查询");
    }
    private static void validateBinding(CommercePrintReferenceRepository.RequestKey key, Request request) {
        if (key == null || !request.sourceImageId().equals(key.originalImageId()) || request.alreadyPlain() != key.alreadyPlain())
            throw error(HttpStatus.CONFLICT, "同一请求编号不能更换原图或处理方式");
    }
    public View get(String id, PlatformPrincipal user) { access(user, false); return view(required(user.organizationId(), id)); }
    public View retry(String id, PlatformPrincipal user) {
        access(user, true); var job = required(user.organizationId(), id);
        if (!retryable(job)) throw error(HttpStatus.CONFLICT, "当前任务不能重复提交；未知生图结果需要核查");
        return reuse(job);
    }
    /** Internal, organization-qualified provenance lookup for template validation. */
    public View readyReferenceSet(String organizationId, String id) {
        var job = required(organizationId, id);
        if (!"READY".equals(job.status())) throw error(HttpStatus.CONFLICT, "同组四面参考图尚未完成");
        return view(job);
    }
    private View reuse(CommercePrintReferenceRepository.Job existing) {
        if (retryable(existing)) jobs.retrySafe(existing);
        return view(required(existing.organizationId(), existing.id()));
    }
    private CommercePrintReferenceRepository.Job required(String org, String id) {
        if (org == null || org.isBlank() || id == null || !id.matches("[A-Za-z0-9_-]{8,80}")) throw error(HttpStatus.NOT_FOUND, "参考任务不存在");
        var job = jobs.find(org, id);
        if (job == null) throw error(HttpStatus.NOT_FOUND, "参考任务不存在");
        return job;
    }
    private CommerceDesignImageGateway.Reference source(String org, String imageId) {
        try {
            var reference = archive.read(org, CommerceImageArchive.sha(org.getBytes(StandardCharsets.UTF_8)) + "/" + imageId);
            CommercePrintReferenceSheet.decode(Base64.getDecoder().decode(reference.dataBase64()));
            return reference;
        } catch (RuntimeException error) { throw error(HttpStatus.CONFLICT, "原始图片未归档到当前公司或无法读取，请重新上传"); }
    }
    private View view(CommercePrintReferenceRepository.Job job) {
        var images = new LinkedHashMap<String,String>();
        if ("READY".equals(job.status()) && job.baseImagesJson() != null) {
            var node = json.readTree(job.baseImagesJson());
            for (String side : List.of("FRONT", "BACK", "LEFT", "RIGHT")) {
                String hash = node.path(side).asText();
                if (!hash.matches("[a-f0-9]{64}")) throw error(HttpStatus.CONFLICT, "参考组归档信息不完整");
                images.put(side, hash);
            }
        }
        return new View(job.id(), job.status(), job.stage(), job.id(), job.originalImageId(), Map.copyOf(images), job.message(),
                retryable(job), job.sheetImageId(), job.alreadyPlain(), job.promptVersion());
    }
    private static boolean retryable(CommercePrintReferenceRepository.Job j) {
        return "FAILED".equals(j.status()) && (Set.of("NOT_SENT", "CONFIG_FAILED", "SOURCE_FAILED").contains(j.stage())
                || ("COLLECT_FAILED".equals(j.stage()) && j.resultJson() != null));
    }
    private static void access(PlatformPrincipal user, boolean edit) {
        if (user == null || user.organizationId() == null || !(edit ? user.permissionCodes().contains("product:edit")
                : user.permissionCodes().contains("product:edit") || user.permissionCodes().contains("product:view") || user.permissionCodes().contains("product:publish")))
            throw error(HttpStatus.FORBIDDEN, "无商品参考图权限");
    }
    private static BusinessException error(HttpStatus status, String message) { return new BusinessException(status, message); }

    /** Called only by the bounded background worker. */
    void process(CommercePrintReferenceRepository.Work work, String token) {
        var job = jobs.find(work.organizationId(), work.id());
        if (job == null) return;
        if ("RESULT_RECEIVED".equals(job.status())) { collect(job, token); return; }
        if (!"QUEUED".equals(job.status())) return;
        CommerceDesignImageGateway.Reference input;
        try { input = source(job.organizationId(), job.originalImageId()); }
        catch (RuntimeException error) { jobs.preflightFailed(job, "SOURCE_FAILED", "原始图片无法读取，已保留任务"); return; }
        ModelConfigService.RuntimeModelConnection model;
        try {
            model = models.imageConnection(job.organizationId())
                    .filter(m -> m.id().equals(job.modelConfigId()) && m.modelId().equals(job.modelId())).orElseThrow();
        } catch (RuntimeException error) { jobs.preflightFailed(job, "CONFIG_FAILED", "已选图片模型不可用或配置已变化，请恢复模型配置后重试"); return; }
        if (!jobs.claim(job, token, false)) return;
        try {
            var response = gateway.generateArtwork(job.prompt(), job.id(), model, List.of(input));
            if (response == null || ((response.artifact() == null || response.artifact().dataBase64() == null || response.artifact().dataBase64().isBlank())
                    && (response.result() == null || response.result().url() == null || response.result().url().isBlank()))) {
                jobs.uncertain(job, token); return;
            }
            // Persist the one provider response before any network download or image work.
            jobs.received(job, token, json.writeValueAsString(response));
        } catch (ImageGenerationNotSentException error) {
            jobs.failed(job, token, "NOT_SENT", "图片请求尚未发出，可使用同一任务重试");
        } catch (RuntimeException error) {
            // Deliberately do not serialize provider response bodies, signed URLs, or credentials.
            jobs.uncertain(job, token);
        }
    }
    private void collect(CommercePrintReferenceRepository.Job job, String token) {
        if (!jobs.claim(job, token, true)) return;
        try {
            var response = json.readValue(job.resultJson(), CommerceDesignImageGateway.Response.class);
            byte[] sheet;
            if (response.artifact() != null && response.artifact().dataBase64() != null && !response.artifact().dataBase64().isBlank()) {
                if (response.artifact().dataBase64().length() > 12_000_000) throw new IllegalArgumentException("返回图片过大");
                sheet = Base64.getDecoder().decode(response.artifact().dataBase64());
            } else {
                if (response.result() == null || response.result().url() == null) throw new IllegalArgumentException("缺少模型图片");
                sheet = downloader.download(response.result().url()).bytes();
            }
            var crops = CommercePrintReferenceSheet.split(sheet);
            var archivedSheet = archive.store(job.organizationId(), sheet);
            var images = new LinkedHashMap<String,String>();
            for (var crop : crops.entrySet()) images.put(crop.getKey(), archive.store(job.organizationId(), crop.getValue()).sha256());
            jobs.ready(job, token, archivedSheet.sha256(), json.writeValueAsString(images));
        } catch (IllegalArgumentException error) {
            jobs.failed(job, token, "INVALID_OUTPUT", "模型返回的四宫格无法完整裁切，已保留结果供核查，不会再次收费生成");
        } catch (RuntimeException error) {
            jobs.collectRetry(job, token);
        }
    }
}
