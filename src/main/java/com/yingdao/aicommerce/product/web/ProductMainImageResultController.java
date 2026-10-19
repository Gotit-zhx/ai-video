package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.application.ProductMainImageAssets;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Base64;

@RestController
@RequestMapping("/api/v1/product-main-image-results")
public class ProductMainImageResultController {
    private final ProductMainImageAssets assets;
    public ProductMainImageResultController(ProductMainImageAssets assets) { this.assets = assets; }
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) {
        var image = assets.preview(user.organizationId(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType())).cacheControl(CacheControl.noStore())
                .body(Base64.getDecoder().decode(image.dataBase64()));
    }
}
