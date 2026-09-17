package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchDesignAssetService;
import com.yingdao.aicommerce.product.domain.CommerceDesignBatchTarget;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/commerce-designs/{id}/batch-targets")
public class CommerceDesignBatchTargetController {
    private final CommerceBatchDesignAssetService service;
    public CommerceDesignBatchTargetController(CommerceBatchDesignAssetService service){this.service=service;}
    @GetMapping public ApiResponse<List<CommerceDesignBatchTarget>> targets(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.targets(id,user));}
}
