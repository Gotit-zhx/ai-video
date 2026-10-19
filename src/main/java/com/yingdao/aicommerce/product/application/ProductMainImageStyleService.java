package com.yingdao.aicommerce.product.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductPublishWorkflow;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Each slot and paid attempt is durable; original capture images are never mutated. */
@Service
public class ProductMainImageStyleService {
    private final JdbcTemplate db;
    private final ProductCaptureImageMapper images;
    private final CommerceImageArchive archive;
    private final MarketplaceImageDownloader downloader;
    private final CommerceDesignImageGateway gateway;
    private final ModelConfigService models;
    private final ObjectMapper json;
    private final ProductMainImageTemplateService templates;

    public record Progress(String templateName, int totalImages, int processedImages, List<String> imageUrls,
                           List<ImageProgress> images) {}
    public record ImageProgress(int index, String status, String stage, String mode, int attempt,
                                Long elapsedSeconds, int outputCharacters, String errorMessage, String imageUrl) {}

    public ProductMainImageStyleService(JdbcTemplate db, ProductCaptureImageMapper images, CommerceImageArchive archive,
            MarketplaceImageDownloader downloader, CommerceDesignImageGateway gateway,
            ModelConfigService models, ObjectMapper json, ProductMainImageTemplateService templates) {
        this.db = db; this.images = images; this.archive = archive; this.downloader = downloader;
        this.gateway = gateway; this.models = models; this.json = json; this.templates = templates;
    }

    public boolean selected(ProductPublishWorkflow workflow) {
        return ProductMainImageStylePolicy.templateId(request(workflow)) != null;
    }

    public Progress progress(ProductPublishWorkflow workflow) {
        if (!selected(workflow)) return null;
        Map<String, Object> template;
        try { template = current(workflow); }
        catch (BusinessException unavailable) { return new Progress("模板不可用", 0, 0, List.of(), List.of()); }
        var slots = slots(template);
        var jobs = currentJobs(workflow, slots);
        var progress = new ArrayList<ImageProgress>();
        for (int index = 0; index < slots.size(); index++) {
            int position = index;
            var job = jobs.stream().filter(row -> ((Number) row.get("sort_order")).intValue() == position).findFirst().orElse(null);
            var analysis = json.convertValue(slots.get(index).get("analysis"), new TypeReference<Map<String, Object>>() {});
            progress.add(imageProgress(index, job, ProductMainImageStylePolicy.preservesOriginal(analysis), workflow));
        }
        var ready = progress.stream().filter(image -> "READY".equals(image.status())).toList();
        return new Progress(Objects.toString(template.get("name"), ""), slots.size(), ready.size(),
                ready.stream().map(ImageProgress::imageUrl).toList(), List.copyOf(progress));
    }

    private ImageProgress imageProgress(int index, Map<String, Object> job, boolean original, ProductPublishWorkflow workflow) {
        String status = job == null ? "WAITING" : Objects.toString(job.get("status"), "WAITING");
        int attempt = job == null ? 0 : boundedNumber(job.get("attempt_count"), 1);
        String stage = switch (status) {
            case "PREPARED" -> "QUEUED";
            case "SUBMITTING" -> "GENERATING";
            case "RESULT_RECEIVED" -> "SAVING";
            case "READY" -> original ? "PRESERVED" : "READY";
            case "FAILED" -> "FAILED";
            default -> "WAITING";
        };
        Long elapsed = null;
        if ("SUBMITTING".equals(status)) {
            Instant updated = jobInstant(job.get("updated_at"));
            if (updated != null) elapsed = Math.max(0, java.time.Duration.between(updated, Instant.now()).getSeconds());
        }
        String error = job == null ? null : (String) job.get("error_message");
        if ("FAILED".equals(workflow.getStatus()) && !"READY".equals(status)) {
            stage = "FAILED".equals(status) ? "FAILED" : "STOPPED";
            if (error == null && Set.of("SUBMITTING", "RESULT_RECEIVED", "FAILED").contains(status)) error = workflow.getErrorMessage();
        }
        return new ImageProgress(index, status, stage, original ? ProductMainImageStylePolicy.PRESERVE_ORIGINAL : "REPLACE_PRODUCT",
                attempt, elapsed, 0, error, "READY".equals(status) ? "/api/v1/product-main-image-results/" + job.get("id") : null);
    }

    /** Process at most one slot per workflow claim, releasing the worker between slots. */
    public boolean ensureReady(ProductPublishWorkflow workflow, ProductCapture capture) {
        if (!selected(workflow)) return true;
        var slots = slots(current(workflow));
        prepare(workflow, capture, slots);
        var currentJobs = currentJobs(workflow, slots);
        if (currentJobs.size() != slots.size()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "风格主图任务尚未完整保存");
        var pending = currentJobs.stream().filter(job -> !"READY".equals(job.get("status"))).findFirst();
        if (pending.isEmpty()) return true;
        var job = pending.get();
        String id = (String) job.get("id"), org = workflow.getOrganizationId();
        String status = (String) job.get("status");
        int attempt = ((Number) job.get("attempt_count")).intValue();
        String attemptId = id + (attempt == 0 ? "" : "_r" + attempt);
        if ("FAILED".equals(status)) throw conflict(Objects.toString(job.get("error_message"), "风格主图生成失败"));
        if ("PREPARED".equals(status)) {
            var model = models.commerceMockupConnectionById(org, (String) job.get("model_config_id"))
                    .orElseThrow(() -> conflict("原风格主图模型已停用，请恢复模型配置后重试"));
            var references = List.of(archive.read(org, (String) job.get("source_key")), archive.read(org, (String) job.get("reference_key")));
            if (db.update("UPDATE product_main_image_jobs SET status='SUBMITTING',updated_at=NOW(6) WHERE id=? AND status='PREPARED' AND attempt_count=?", id, attempt) != 1) return false;
            try {
                var result = gateway.generateMainImagePoster((String) job.get("prompt"), attemptId, model, references, (String) job.get("size"));
                if (result == null) return false;
                received(id, attempt, result);
            } catch (CommerceImageRejectedException rejected) {
                fail(id, "风格主图模型拒绝本次生成：" + rejected.getMessage());
                throw conflict("风格主图模型拒绝本次生成：" + rejected.getMessage());
            } catch (ImageGenerationNotSentException notSent) {
                db.update("UPDATE product_main_image_jobs SET status='PREPARED',updated_at=NOW(6) WHERE id=? AND status='SUBMITTING' AND attempt_count=?", id, attempt);
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, notSent.getMessage());
            } catch (RuntimeException uncertain) {
                // The next claim reads the original runtime operation. Never submit a new paid call on timeout.
                return false;
            }
            return false;
        }
        if ("SUBMITTING".equals(status)) {
            var operation = gateway.operation(attemptId);
            if (operation != null && "COMPLETED".equals(operation.status()) && operation.response() != null) {
                received(id, attempt, operation.response());
            } else if (operation != null && "RECOVERABLE".equals(operation.status()) && operation.error() != null
                    && Boolean.TRUE.equals(operation.error().get("savedResponseAvailable"))) {
                recoverSavedResponse(job, org, attemptId, attempt);
            } else if (operation != null && "FAILED".equals(operation.status())) {
                fail(id, "图片服务确认风格主图生成失败，尚未上传");
                throw conflict("图片服务确认风格主图生成失败，尚未上传");
            } else if (expired(job)) {
                fail(id, "风格主图结果超过30分钟未确认，已停止重复调用，请核查模型任务");
                throw conflict("风格主图结果超过30分钟未确认，已停止重复调用");
            }
            return false;
        }
        if (!"RESULT_RECEIVED".equals(status)) throw conflict("风格主图任务状态无效");
        String storageKey = (String) job.get("storage_key");
        if (storageKey == null) {
            var result = json.readValue((String) job.get("result_json"), CommerceDesignImageGateway.Response.class);
            byte[] bytes;
            if (result.artifact() != null && result.artifact().dataBase64() != null) {
                if (result.artifact().dataBase64().length() > 11_184_812) throw conflict("风格主图超过8MiB");
                bytes = Base64.getDecoder().decode(result.artifact().dataBase64());
            } else if (result.result() != null && result.result().url() != null) {
                bytes = downloader.download(result.result().url()).bytes();
            } else {
                fail(id, "图片模型没有返回可用图片");
                throw conflict("图片模型没有返回可用图片");
            }
            try { ProductMainImageTemplateService.dimensions(bytes); }
            catch (Exception invalid) { fail(id, "模型返回的主图无法解码"); throw conflict("模型返回的主图无法解码"); }
            storageKey = archive.store(org, bytes).storageKey();
            db.update("UPDATE product_main_image_jobs SET storage_key=?,updated_at=NOW(6) WHERE id=? AND attempt_count=? AND status='RESULT_RECEIVED'", storageKey, id, attempt);
        } else {
            // A saved result can resume without another model call or another download.
            archive.read(org, storageKey);
        }
        // Return the model's archived bytes as-is. No AI review or automatic redraw.
        db.update("""
                UPDATE product_main_image_jobs SET status='READY',review_json=NULL,error_message=NULL,updated_at=NOW(6)
                WHERE id=? AND status='RESULT_RECEIVED' AND attempt_count=? AND storage_key=?
                """, id, attempt, storageKey);
        return false;
    }

    private void recoverSavedResponse(Map<String, Object> job, String org, String attemptId, int attempt) {
        String id = (String) job.get("id");
        int count = ((Number) job.getOrDefault("recovery_count", 0)).intValue();
        if (count >= 3) {
            fail(id, "原风格主图结果恢复连续3次失败，已保留原任务，未重复生图");
            throw conflict("原风格主图结果恢复连续3次失败，已保留原任务，未重复生图");
        }
        var model = models.commerceMockupConnectionById(org, (String) job.get("model_config_id"))
                .orElseThrow(() -> conflict("原风格主图模型已停用，请恢复模型配置后重试"));
        var references = List.of(archive.read(org, (String) job.get("source_key")), archive.read(org, (String) job.get("reference_key")));
        if (db.update("""
                UPDATE product_main_image_jobs SET recovery_count=recovery_count+1,updated_at=NOW(6)
                WHERE id=? AND status='SUBMITTING' AND attempt_count=? AND recovery_count=?
                """, id, attempt, count) != 1) return;
        // Only a confirmed cached response permits replay. The same operation ID retains its input fingerprint.
        try {
            var result = gateway.generateMainImagePoster((String) job.get("prompt"), attemptId, model, references, (String) job.get("size"));
            if (result == null) throw new IllegalStateException("原图片结果暂不可读");
            received(id, attempt, result);
        } catch (RuntimeException failure) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "原风格主图结果已缓存，正在恢复读取（" + (count + 1) + "/3）");
        }
    }

    private void prepare(ProductPublishWorkflow workflow, ProductCapture capture, List<Map<String, Object>> slots) {
        var existing = jobs(workflow);
        var current = currentJobs(workflow, slots);
        if (current.size() == slots.size()) return;
        String org = workflow.getOrganizationId();
        var generated = existing.stream().filter(job -> job.get("model_config_id") instanceof String id && !id.isBlank()).findFirst();
        String sourceKey = generated.map(job -> (String) job.get("source_key")).orElse(null);
        String modelId = null;
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            String fingerprint = ProductMainImageStylePolicy.fingerprint(slot, json);
            int slotIndex = index;
            if (current.stream().anyMatch(job -> ((Number) job.get("sort_order")).intValue() == slotIndex
                    && fingerprint.equals(job.get("template_fingerprint")))) continue;
            var analysis = json.convertValue(slot.get("analysis"), new TypeReference<Map<String, Object>>() {});
            ProductMainImageStylePolicy.validateAnalysis(analysis);
            if (ProductMainImageStylePolicy.preservesOriginal(analysis)) {
                String referenceKey = (String) slot.get("storageKey");
                archive.read(org, referenceKey);
                // Preserve the archived bytes and slot order; no model or generated-image review is involved.
                db.update("""
                        INSERT IGNORE INTO product_main_image_jobs(id,organization_id,workflow_id,capture_id,sort_order,
                        source_key,reference_key,prompt,size,model_config_id,template_fingerprint,status,storage_key,review_json,updated_at)
                        VALUES(?,?,?,?,?,?,?,'','ORIGINAL','',?,'READY',?,?,NOW(6))
                        """, UUID.randomUUID().toString(), org, workflow.getId(), capture.getId(), index,
                        referenceKey, referenceKey, fingerprint, referenceKey,
                        json.writeValueAsString(Map.of("mode", ProductMainImageStylePolicy.PRESERVE_ORIGINAL)));
                continue;
            }
            if (modelId == null) {
                modelId = models.imageConnection(org).orElseThrow(() -> conflict("请先配置可用的图片生成模型")).id();
            }
            if (sourceKey == null) {
                var source = images.selectList(new LambdaQueryWrapper<ProductCaptureImage>()
                        .eq(ProductCaptureImage::getOrganizationId, org).eq(ProductCaptureImage::getCaptureId, capture.getId())
                        .orderByAsc(ProductCaptureImage::getSortOrder)).stream()
                        .filter(image -> !Set.of("DETAIL", "VARIANT", "SIZE_CHART").contains(Objects.toString(image.getRoleName(), "")))
                        .findFirst().orElseThrow(() -> conflict("商品缺少可供风格生成的主体图片"));
                String url = source.getProcessedUrl() == null ? source.getOriginalUrl() : source.getProcessedUrl();
                if (url == null || url.contains("dry-run")) url = source.getOriginalUrl();
                sourceKey = archive.store(org, downloader.downloadProductImage(url, source).bytes()).storageKey();
            }
            String prompt = ProductMainImageStylePolicy.prompt(Objects.toString(capture.getTitle(), ""), analysis);
            db.update("""
                    INSERT IGNORE INTO product_main_image_jobs(id,organization_id,workflow_id,capture_id,sort_order,
                    source_key,reference_key,prompt,size,model_config_id,template_fingerprint,status,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,'PREPARED',NOW(6))
                    """, UUID.randomUUID().toString(), org, workflow.getId(), capture.getId(), index, sourceKey, slot.get("storageKey"),
                    prompt, ProductMainImageStylePolicy.size(((Number) slot.get("width")).intValue(), ((Number) slot.get("height")).intValue()), modelId,
                    fingerprint);
        }
    }
    private void received(String id, int attempt, CommerceDesignImageGateway.Response result) {
        db.update("UPDATE product_main_image_jobs SET status='RESULT_RECEIVED',result_json=?,updated_at=NOW(6) WHERE id=? AND attempt_count=? AND status='SUBMITTING'",
                json.writeValueAsString(result), id, attempt);
    }
    private static int boundedNumber(Object value, int maximum) {
        return value instanceof Number number ? (int) Math.max(0, Math.min(maximum, number.longValue())) : 0;
    }
    private static Instant jobInstant(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant()
                : value instanceof java.time.LocalDateTime date ? date.atZone(java.time.ZoneId.systemDefault()).toInstant()
                : value instanceof Instant instant ? instant : null;
    }
    private void fail(String id, String reason) {
        db.update("UPDATE product_main_image_jobs SET status='FAILED',error_message=?,updated_at=NOW(6) WHERE id=? AND status<>'READY'",
                reason.substring(0, Math.min(1000, reason.length())), id);
    }
    private boolean expired(Map<String, Object> job) {
        Instant instant = jobInstant(job.get("updated_at"));
        return instant != null && instant.plusSeconds(1800).isBefore(Instant.now());
    }
    private Map<String, Object> current(ProductPublishWorkflow workflow) {
        return templates.current(ProductMainImageStylePolicy.templateId(request(workflow)), workflow.getOrganizationId());
    }
    private List<Map<String, Object>> slots(Map<String, Object> template) {
        var value = template.get("images");
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > ProductMainImageStylePolicy.MAX_IMAGES)
            throw conflict("主图模板图集数量无效");
        return json.convertValue(value, new TypeReference<List<Map<String, Object>>>() {});
    }
    private List<Map<String, Object>> jobs(ProductPublishWorkflow workflow) {
        return db.queryForList("SELECT * FROM product_main_image_jobs WHERE workflow_id=? AND organization_id=? ORDER BY sort_order",
                workflow.getId(), workflow.getOrganizationId());
    }
    private List<Map<String, Object>> currentJobs(ProductPublishWorkflow workflow, List<Map<String, Object>> slots) {
        return jobs(workflow).stream().filter(job -> {
            int index = ((Number) job.get("sort_order")).intValue();
            return index >= 0 && index < slots.size()
                    && ProductMainImageStylePolicy.fingerprint(slots.get(index), json).equals(job.get("template_fingerprint"));
        }).toList();
    }
    private Map<String, Object> request(ProductPublishWorkflow workflow) {
        return json.readValue(workflow.getRequestJson(), new TypeReference<Map<String, Object>>() {});
    }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
}
