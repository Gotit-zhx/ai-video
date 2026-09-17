package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchService;
import com.yingdao.aicommerce.product.application.CommerceBatchLaunchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/commerce-batches")
public class CommerceBatchController {
    private final CommerceBatchService service;
    private final CommerceBatchLaunchService launch;
    public CommerceBatchController(CommerceBatchService service){this(service,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public CommerceBatchController(CommerceBatchService service,CommerceBatchLaunchService launch){this.service=service;this.launch=launch;}
    @GetMapping public ApiResponse<List<CommerceBatchService.View>> list(@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.list(u));}
    @GetMapping("/{id}") public ApiResponse<CommerceBatchService.View> get(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.get(id,u));}
    @PostMapping public ApiResponse<CommerceBatchService.View> create(@RequestBody CommerceBatchService.CreateRequest r,
            @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.create(r,key,u));}
    @PostMapping("/create-and-start") public ApiResponse<CommerceBatchLaunchService.Result> createAndStart(
            @RequestBody CommerceBatchLaunchService.Request r,@RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(launch.launch(r,key,u));}
}
