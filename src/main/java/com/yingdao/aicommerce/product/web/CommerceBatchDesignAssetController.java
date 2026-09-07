package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchDesignAssetService;
import com.yingdao.aicommerce.product.domain.CommerceBatchDesignAsset;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/commerce-batches/{id}/design-assets")
public class CommerceBatchDesignAssetController {
    private final CommerceBatchDesignAssetService service;
    public CommerceBatchDesignAssetController(CommerceBatchDesignAssetService service){this.service=service;}
    @GetMapping public ApiResponse<List<CommerceBatchDesignAsset>> list(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.list(id,user));}
    @GetMapping("/candidates") public ApiResponse<List<CommerceBatchDesignAssetService.Candidate>> candidates(@PathVariable String id,@RequestParam String itemId,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.candidates(id,itemId,user));}
    @PostMapping public ApiResponse<CommerceBatchDesignAsset> bind(@PathVariable String id,@RequestBody CommerceBatchDesignAssetService.Request request,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.bind(id,request,user));}
}
