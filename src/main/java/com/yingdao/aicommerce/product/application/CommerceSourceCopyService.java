package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Explicitly importing a source freezes a private copy for this batch. */
@Service
public class CommerceSourceCopyService {
    private final JdbcTemplate db;
    private final ProductCaptureService source;
    private final ProductCaptureMapper captures;
    private final ProductCaptureImageMapper images;
    private final ProductCaptureSkuMapper skus;
    private final ObjectMapper json;
    private final CommerceImageArchive archive;
    private final MarketplaceImageDownloader downloader;

    public CommerceSourceCopyService(JdbcTemplate db, ProductCaptureService source,
            ProductCaptureMapper captures, ProductCaptureImageMapper images,
            ProductCaptureSkuMapper skus, ObjectMapper json, CommerceImageArchive archive,
            MarketplaceImageDownloader downloader) {
        this.db=db; this.source=source; this.captures=captures; this.images=images;
        this.skus=skus; this.json=json; this.archive=archive; this.downloader=downloader;
    }

    @Transactional
    public ProductCapture copy(String batch, String sourceId, PlatformPrincipal user) {
        return copy(batch,sourceId,user,CommerceModule.FISSION);
    }

    @Transactional
    public ProductCapture copy(String batch, String sourceId, PlatformPrincipal user, String module) {
        var prior=db.queryForList("""
                SELECT capture_id FROM commerce_source_copies
                WHERE organization_id=? AND batch_id=? AND source_capture_id=?
                """, String.class, user.organizationId(), batch, sourceId);
        if (!prior.isEmpty()) return source.get(prior.getFirst(), user).capture();
        var detail=source.get(sourceId,user);
        var original=detail.capture();
        if ("IMAGE_PROCESSING".equals(original.getStatus()))
            throw new com.yingdao.aicommerce.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT,"来源图片正在处理中，请完成后再复制到裂变");
        var copy=new ProductCapture(user.organizationId(),user.userId(),
                "module-source-"+UUID.randomUUID(),original.getRequestFingerprint(),
                original.getSourcePlatform(),original.getSourceUrl(),original.getSourceShopId(),
                original.getSourceProductId(),original.getTitle(),original.getDescription(),
                original.getCategoryName(),original.getBrandName(),original.getCurrency(),
                original.getPriceMin(),original.getPriceMax(),original.getNormalizedData(),original.getRawData());
        copy.assignModule(module);
        Map<String,String> ids=new HashMap<>();
        var clonedImages=new LinkedHashMap<ProductCaptureImage,ProductCaptureImage>();
        var sizeCharts=new HashSet<>(db.queryForList("""
                SELECT DISTINCT image_id FROM product_watermark_checks
                WHERE organization_id=? AND capture_id=? AND status='READY'
                AND JSON_UNQUOTE(JSON_EXTRACT(detection_json,'$.imageType'))='SIZE_CHART'
                """,String.class,user.organizationId(),sourceId));
        for (var image:detail.images()) {
            String originalUrl=image.getOriginalUrl(), processed=image.getProcessedUrl();
            // A protected, mutable processing URL must not keep depending on the source row.
            if (processed!=null && (processed.startsWith("/") || processed.contains("/api/v1/"))) {
                var binary=downloader.downloadProductImage(processed,image);
                String key=archive.store(user.organizationId(),binary.bytes()).storageKey();
                String frozen=CommercePublicationDrafts.PREFIX+key;
                processed=frozen;
            }
            var cloned=new ProductCaptureImage(user.organizationId(),copy.getId(),originalUrl,
                    sizeCharts.contains(image.getId())?"SIZE_CHART":image.getRoleName(),
                    image.getAltText(),image.getSortOrder(),image.getMimeType(),image.getSourceJson());
            if ("PROCESSED".equals(image.getStatus()) && processed!=null) cloned.processed(processed);
            ids.put(image.getId(),cloned.getId());
            clonedImages.put(image,cloned);
        }
        var normalized=json.readValue(original.getNormalizedData(),Map.class);
        copy.updateDraft(copy.getTitle(),copy.getDescription(),copy.getCategoryName(),copy.getBrandName(),
                copy.getCurrency(),copy.getPriceMin(),copy.getPriceMax(),json.writeValueAsString(remap(normalized,ids)));
        if (!detail.images().isEmpty() && detail.images().stream().allMatch(image->"PROCESSED".equals(image.getStatus()))) copy.imagesReady();
        captures.insert(copy);
        for(var entry:clonedImages.entrySet()) {
            var image=entry.getKey();var cloned=entry.getValue();
            images.insert(cloned);
            db.update("INSERT INTO commerce_source_copy_images(organization_id,capture_id,source_image_id,image_id) VALUES(?,?,?,?)",
                    user.organizationId(),copy.getId(),image.getId(),cloned.getId());
            db.update("""
                    INSERT INTO commerce_capture_source_archives
                    (organization_id,capture_id,capture_version,image_id,storage_key,content_sha256,provenance,created_by,created_at)
                    SELECT organization_id,?,?,?,storage_key,content_sha256,provenance,?,created_at
                    FROM commerce_capture_source_archives
                    WHERE organization_id=? AND capture_id=? AND capture_version=? AND image_id=?
                    """,copy.getId(),copy.getVersion(),cloned.getId(),user.userId(),user.organizationId(),sourceId,
                    original.getVersion(),image.getId());
        }
        for (var sku:detail.skus())
            skus.insert(new ProductCaptureSku(user.organizationId(),copy.getId(),sku.getSkuKey(),
                    sku.getExternalSkuId(),sku.getSellerSku(),sku.getVariationName(),sku.getPrice(),
                    sku.getOriginalPrice(),sku.getStock(),sku.getImageUrl(),
                    sku.getImageSource(),sku.getAttributesJson(),sku.getSourceJson(),sku.getSortOrder()));
        db.update("""
                INSERT INTO product_capture_field_sources
                (id,organization_id,capture_id,field_name,source_type,source_path,source_value,confidence,created_at)
                SELECT UUID(),organization_id,?,field_name,source_type,source_path,source_value,confidence,created_at
                FROM product_capture_field_sources WHERE organization_id=? AND capture_id=?
                """,copy.getId(),user.organizationId(),sourceId);
        db.update("""
                INSERT INTO product_capture_videos
                (id,organization_id,capture_id,source_url,mime_type,byte_length,content_sha256,created_at,source_asset_key)
                SELECT UUID(),organization_id,?,source_url,mime_type,byte_length,content_sha256,created_at,source_asset_key
                FROM product_capture_videos WHERE organization_id=? AND capture_id=? AND superseded_by IS NULL
                """,copy.getId(),user.organizationId(),sourceId);
        db.update("""
                INSERT INTO commerce_source_copies(organization_id,batch_id,source_capture_id,capture_id)
                VALUES(?,?,?,?)
                """,user.organizationId(),batch,sourceId,copy.getId());
        return copy;
    }

    static Object remap(Object value, Map<String,String> ids) {
        if (value instanceof String text) return ids.getOrDefault(text,text);
        if (value instanceof List<?> list) return list.stream().map(item->remap(item,ids)).toList();
        if (value instanceof Map<?,?> map) {
            var result=new LinkedHashMap<String,Object>();
            map.forEach((key,item)->result.put(String.valueOf(key),remap(item,ids)));
            return result;
        }
        return value;
    }

    public String resolve(String org,String batch,String input) {
        var rows=db.queryForList("""
                SELECT capture_id FROM commerce_source_copies
                WHERE organization_id=? AND batch_id=? AND source_capture_id=?
                """,String.class,org,batch,input);
        return rows.isEmpty()?input:rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> remapRequest(String org,String capture,Map<String,Object> request) {
        var ids=new HashMap<String,String>();
        for(var row:db.queryForList("SELECT source_image_id,image_id FROM commerce_source_copy_images WHERE organization_id=? AND capture_id=?",org,capture))
            ids.put((String)row.get("source_image_id"),(String)row.get("image_id"));
        return (Map<String,Object>)remap(request,ids);
    }
}
