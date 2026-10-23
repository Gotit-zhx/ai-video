package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductListingTemplateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/product-listing-templates")
public class ProductListingTemplateController {
    private final ProductListingTemplateService service;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yingdao.aicommerce.product.application.ProductListingLogisticsService logistics;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yingdao.aicommerce.product.application.ProductListingWarehouseService warehouses;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yingdao.aicommerce.product.application.CommerceImageArchive imageArchive;
    public ProductListingTemplateController(ProductListingTemplateService service) { this.service = service; }
    @GetMapping
    public ApiResponse<List<ProductListingTemplateService.Template>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.list(principal));
    }
    @PostMapping(value="/size-chart-image", consumes="multipart/form-data")
    public ApiResponse<Map<String, String>> uploadSizeChart(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal PlatformPrincipal principal) throws java.io.IOException {
        // A batch publisher can upload a chart without permission to edit the shared template.
        if (principal == null || !(principal.roleCodes().contains("ADMIN")
                || principal.permissionCodes().contains("product:publish")
                || principal.permissionCodes().contains("product:edit")))
            throw new com.yingdao.aicommerce.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "没有尺码图上传权限");
        if (file.isEmpty() || file.getSize() > 8 * 1024 * 1024)
            throw new com.yingdao.aicommerce.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "尺码图须为 8 MB 以内的 PNG 或 JPG 图片");
        byte[] bytes = file.getBytes();
        try (var input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException();
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 20000 || height > 20000
                        || (long) width * height > 40_000_000) throw new IllegalArgumentException();
            } finally { reader.dispose(); }
            var saved = imageArchive.store(principal.organizationId(), bytes);
            return ApiResponse.ok(Map.of("storageKey", saved.storageKey()));
        } catch (IllegalArgumentException | java.io.IOException e) {
            throw new com.yingdao.aicommerce.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "尺码图无法读取，请使用清晰的 PNG 或 JPG 图片");
        }
    }
    @GetMapping("/size-chart-image")
    public org.springframework.http.ResponseEntity<byte[]> sizeChartImage(@RequestParam String key,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        ProductListingTemplateService.requireAccess(principal, false);
        var image = imageArchive.read(principal.organizationId(), key);
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(image.mimeType()))
                .body(java.util.Base64.getDecoder().decode(image.dataBase64()));
    }
    @PutMapping("/{platform}")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<ProductListingTemplateService.Template> save(@PathVariable String platform,
            @RequestBody Map<String, Object> body, @AuthenticationPrincipal PlatformPrincipal principal) {
        var template = service.save(platform, body, principal);
        if ("SHOPEE".equals(template.platform()) && body.get("logisticsConfig") instanceof Map<?, ?> config) {
            var shipping = new java.util.LinkedHashMap<String, Object>();
            config.forEach((key, value) -> shipping.put(String.valueOf(key), value));
            logistics.save(shipping, principal);
        }
        if ("TIKTOK_SHOP".equals(template.platform()) && body.get("warehouseConfig") instanceof Map<?,?> config) {
            var selection = new java.util.LinkedHashMap<String,Object>();
            config.forEach((key,value)->selection.put(String.valueOf(key),value));
            warehouses.save(selection,principal);
        }
        return ApiResponse.ok(template);
    }
}
