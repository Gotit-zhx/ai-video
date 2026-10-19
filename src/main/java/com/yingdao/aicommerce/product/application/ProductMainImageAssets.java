package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ProductMainImageAssets {
    private final JdbcTemplate db;
    private final CommerceImageArchive archive;
    private final ObjectMapper json;
    private final ProductMainImageTemplateService templates;
    public ProductMainImageAssets(JdbcTemplate db, CommerceImageArchive archive, ObjectMapper json, ProductMainImageTemplateService templates) {
        this.db = db; this.archive = archive; this.json = json; this.templates = templates;
    }

    /** Null means opt-out; a selected but incomplete style must never fall back to original images. */
    public List<ProductCaptureImage> gallery(ProductCapture capture, Map<String, Object> request) {
        String template = ProductMainImageStylePolicy.templateId(request);
        if (template == null) return null;
        String key = Objects.toString(request.get("idempotencyKey"), "");
        if (!key.matches("wf-[a-f0-9-]{36}-publish")) throw conflict("风格主图必须通过已保存的一键上传任务发布");
        String workflowId = key.substring(3, key.length() - 8);
        var workflows = db.queryForList("SELECT request_json FROM product_publish_workflows WHERE id=? AND organization_id=? AND capture_id=?",
                workflowId, capture.getOrganizationId(), capture.getId());
        if (workflows.size() != 1) throw conflict("风格主图工作流不属于当前商品");
        var saved = json.readTree((String) workflows.getFirst().get("request_json"));
        if (!template.equals(saved.path(ProductMainImageStylePolicy.TEMPLATE).asText())) throw conflict("风格主图模板与上传任务不一致");
        var slots = json.convertValue(templates.current(template, capture.getOrganizationId()).get("images"),
                new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        var rows = db.queryForList("SELECT * FROM product_main_image_jobs WHERE workflow_id=? AND organization_id=? AND capture_id=? ORDER BY sort_order",
                workflowId, capture.getOrganizationId(), capture.getId()).stream().filter(row -> {
                    int index = ((Number) row.get("sort_order")).intValue();
                    return index >= 0 && index < slots.size() && ProductMainImageStylePolicy.fingerprint(slots.get(index), json).equals(row.get("template_fingerprint"));
                }).toList();
        if (rows.size() != slots.size() || rows.stream().anyMatch(row -> !"READY".equals(row.get("status"))))
            throw new TemplateChanged();
        var ordered = rows.stream().sorted(Comparator.<Map<String, Object>, Boolean>comparing(row -> {
            var slot = slots.get(((Number) row.get("sort_order")).intValue());
            var analysis = json.convertValue(slot.get("analysis"), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return ProductMainImageStylePolicy.preservesOriginal(analysis);
        }).thenComparingInt(row -> ((Number) row.get("sort_order")).intValue())).toList();
        return java.util.stream.IntStream.range(0, ordered.size()).mapToObj(position -> {
            var row = ordered.get(position);
            var slot = slots.get(((Number) row.get("sort_order")).intValue());
            var analysis = json.convertValue(slot.get("analysis"), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (ProductMainImageStylePolicy.preservesOriginal(analysis)) {
                var review = json.readTree(Objects.toString(row.get("review_json"), "{}"));
                String original = (String) slot.get("storageKey");
                if (!ProductMainImageStylePolicy.PRESERVE_ORIGINAL.equals(review.path("mode").asText())
                        || !original.equals(row.get("storage_key")) || !original.equals(row.get("reference_key"))
                        || !original.equals(row.get("source_key")))
                    throw conflict("原样保留图片与当前模板原图不一致");
            }
            var reference = archive.read(capture.getOrganizationId(), (String) row.get("storage_key"));
            String url = ProductMainImageStylePolicy.PREFIX + row.get("id");
            var image = new ProductCaptureImage(capture.getOrganizationId(), capture.getId(), url + "?original", "MAIN",
                    "风格主图", position, reference.mimeType(), "{}");
            image.processed(url);
            return image;
        }).toList();
    }

    /** Check again after media staging, outside the publishing transaction's earlier read view. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public void verifyCurrentGallery(ProductCapture capture, Map<String, Object> request, List<ProductCaptureImage> staged) {
        if (ProductMainImageStylePolicy.templateId(request) == null) return;
        var current = gallery(capture, request);
        if (staged == null || !current.stream().map(ProductCaptureImage::getProcessedUrl).toList()
                .equals(staged.stream().map(ProductCaptureImage::getProcessedUrl).toList())) throw new TemplateChanged();
    }

    public CommerceDesignImageGateway.Reference read(String org, String capture, String id) {
        var rows = db.queryForList("SELECT storage_key FROM product_main_image_jobs WHERE id=? AND organization_id=? AND capture_id=? AND status='READY'", id, org, capture);
        if (rows.size() != 1) throw new BusinessException(HttpStatus.NOT_FOUND, "风格主图不存在或尚未就绪");
        return archive.read(org, (String) rows.getFirst().get("storage_key"));
    }
    public CommerceDesignImageGateway.Reference preview(String org, String id) {
        var rows = db.queryForList("SELECT capture_id FROM product_main_image_jobs WHERE id=? AND organization_id=? AND status='READY'", id, org);
        if (rows.size() != 1) throw new BusinessException(HttpStatus.NOT_FOUND, "风格主图不存在或尚未就绪");
        return read(org, (String) rows.getFirst().get("capture_id"), id);
    }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
    public static final class TemplateChanged extends BusinessException {
        public TemplateChanged() { super(HttpStatus.SERVICE_UNAVAILABLE, "风格模板已修改，正在按当前模板补齐主图"); }
    }
}
