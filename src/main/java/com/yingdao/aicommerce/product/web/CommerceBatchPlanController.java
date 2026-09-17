package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchPlanner;
import com.yingdao.aicommerce.product.application.CommerceTemplateShopResolver;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/commerce-plans")
public class CommerceBatchPlanController {
    private final CommerceBatchPlanner planner;
    private final CommerceTemplateShopResolver shops;
    public CommerceBatchPlanController(CommerceBatchPlanner planner, CommerceTemplateShopResolver shops) {
        this.planner = planner; this.shops=shops;
    }

    @PostMapping("/preview")
    public ApiResponse<CommerceBatchPlanner.Plan> preview(@RequestBody CommerceBatchPlanner.Request request,
            @AuthenticationPrincipal PlatformPrincipal user) {
        if(user==null || user.organizationId()==null || user.organizationId().isBlank()
                || !(user.permissionCodes().contains("product:view") || user.permissionCodes().contains("product:publish")))
            throw new BusinessException(HttpStatus.FORBIDDEN,"无权访问本公司模板规划");
        return ApiResponse.ok(planner.preview(shops.normalize(request,user.organizationId())));
    }
}
