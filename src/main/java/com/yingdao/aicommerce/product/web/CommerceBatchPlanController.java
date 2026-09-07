package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchPlanner;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/commerce-plans")
public class CommerceBatchPlanController {
    private final CommerceBatchPlanner planner;
    public CommerceBatchPlanController(CommerceBatchPlanner planner) { this.planner = planner; }

    @PostMapping("/preview")
    public ApiResponse<CommerceBatchPlanner.Plan> preview(@RequestBody CommerceBatchPlanner.Request request) {
        return ApiResponse.ok(planner.preview(request));
    }
}
