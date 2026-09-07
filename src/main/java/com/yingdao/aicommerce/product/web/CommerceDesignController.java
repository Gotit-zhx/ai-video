package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceDesignService;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/commerce-designs")
public class CommerceDesignController {
    private final CommerceDesignService service;
    public CommerceDesignController(CommerceDesignService service){this.service=service;}
    @GetMapping public ApiResponse<List<CommerceDesignDraft>> list(@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.list(u));}
    @PostMapping public ApiResponse<List<CommerceDesignDraft>> create(@RequestBody CommerceDesignService.Request r,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.create(r,key,u));}
}
