package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceDesignReviewService;
import com.yingdao.aicommerce.product.domain.CommerceDesignReview;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
import java.util.Base64;
@RestController
@RequestMapping("/api/v1/commerce-designs/{design}/output")
public class CommerceDesignReviewController {
    private final CommerceDesignReviewService service;
    public CommerceDesignReviewController(CommerceDesignReviewService service){this.service=service;}
    @GetMapping public ApiResponse<CommerceDesignReviewService.View> view(@PathVariable String design,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.view(design,u));}
    @GetMapping("/image") public ResponseEntity<byte[]> image(@PathVariable String design,@AuthenticationPrincipal PlatformPrincipal u){var image=service.image(design,u);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(image.mimeType())).body(Base64.getDecoder().decode(image.dataBase64()));}
    @PostMapping("/review") public ApiResponse<CommerceDesignReview> review(@PathVariable String design,@RequestBody CommerceDesignReviewService.Request r,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.review(design,r,u));}
}
