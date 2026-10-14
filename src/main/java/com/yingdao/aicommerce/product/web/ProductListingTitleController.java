package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/product-captures")
public class ProductListingTitleController {
    private final ProductCaptureService captures;
    private final ProductListingTitleService titles;
    public ProductListingTitleController(ProductCaptureService captures, ProductListingTitleService titles) {
        this.captures = captures; this.titles = titles;
    }
    @PostMapping("/{id}/ai-title")
    public ApiResponse<Map<String, String>> generate(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body, @AuthenticationPrincipal PlatformPrincipal principal) {
        ProductListingTemplateService.requireAccess(principal, true);
        var detail = captures.get(id, principal);
        var capture = detail.capture();
        String platform = body == null ? capture.getSourcePlatform() : String.valueOf(body.getOrDefault("platform", capture.getSourcePlatform()));
        if (!java.util.Set.of("SHOPEE", "TIKTOK", "TIKTOK_SHOP").contains(platform))
            throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "请选择目标平台");
        return ApiResponse.ok(Map.of("title", titles.generate(capture, detail.images(), platform), "originalTitle", capture.getTitle()));
    }
}
