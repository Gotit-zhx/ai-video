package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceAdTemplateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/commerce-ad-templates")
public class CommerceAdTemplateController {
    private final CommerceAdTemplateService service;
    public CommerceAdTemplateController(CommerceAdTemplateService service){this.service=service;}
    @GetMapping public ApiResponse<List<CommerceAdTemplateService.View>> list(@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.list(u));}
    @GetMapping("/{id}/versions") public ApiResponse<List<CommerceAdTemplateService.View>> history(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.history(id,u));}
    @PostMapping public ApiResponse<CommerceAdTemplateService.View> save(@RequestBody CommerceAdTemplateService.Save r,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.save(r,u));}
}
