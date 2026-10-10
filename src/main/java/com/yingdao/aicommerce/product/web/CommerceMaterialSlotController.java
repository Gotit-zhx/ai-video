package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceMaterialSlotService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/commerce-material-slots")
public class CommerceMaterialSlotController {
    private final CommerceMaterialSlotService service;
    public CommerceMaterialSlotController(CommerceMaterialSlotService service){this.service=service;}
    @GetMapping public ApiResponse<CommerceMaterialSlotService.Page> list(@RequestParam(defaultValue="0")int page,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.list(page,user));}
    @GetMapping("/{id}") public ApiResponse<CommerceMaterialSlotService.Slot> get(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.get(id,user));}
}
