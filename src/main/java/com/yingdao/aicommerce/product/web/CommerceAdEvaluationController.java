package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceAdEvaluator;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/commerce-plans/ads")
public class CommerceAdEvaluationController {
    private final CommerceAdEvaluator evaluator;
    public CommerceAdEvaluationController(CommerceAdEvaluator evaluator){this.evaluator=evaluator;}
    @PostMapping("/evaluate")
    public ApiResponse<CommerceAdEvaluator.Result> evaluate(@RequestBody CommerceAdEvaluator.Request request){return ApiResponse.ok(evaluator.evaluate(request));}
}
