package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceDesignGenerationService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
@RestController
@RequestMapping("/api/v1/commerce-designs/{design}/generation")
public class CommerceDesignGenerationController {
    private final CommerceDesignGenerationService service;
    public CommerceDesignGenerationController(CommerceDesignGenerationService service){this.service=service;}
    @GetMapping public ApiResponse<CommerceDesignGenerationService.State> state(@PathVariable String design,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.state(design,u));}
    @PostMapping public ApiResponse<CommerceDesignGenerationService.State> generate(@PathVariable String design,@RequestBody CommerceDesignGenerationService.Request r,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.generate(design,r,u));}
}
