package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/finance/imports/bills")
class FinanceBillImportController {
    private final FinanceController guard;
    private final FinanceBillImports jobs;

    FinanceBillImportController(FinanceController guard,FinanceBillImports jobs){
        this.guard=guard;
        this.jobs=jobs;
    }

    @ModelAttribute
    void authorize(@AuthenticationPrincipal PlatformPrincipal principal,HttpServletRequest request){
        guard.authorize(principal,request);
    }

    @GetMapping
    ApiResponse<?> list(@AuthenticationPrincipal PlatformPrincipal principal){
        var owner=guard.personal(principal);
        return ApiResponse.ok(Map.of("enabled",jobs.enabled(owner),"items",jobs.list(owner)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> upload(@AuthenticationPrincipal PlatformPrincipal principal,@RequestParam("file") MultipartFile file)throws IOException{
        return ApiResponse.ok(jobs.upload(guard.personal(principal),file.getOriginalFilename(),file.getSize(),file.getInputStream()));
    }

    @GetMapping("/{id}")
    ApiResponse<?> status(@AuthenticationPrincipal PlatformPrincipal principal,@PathVariable("id") String id){
        return ApiResponse.ok(jobs.status(guard.personal(principal),id));
    }

    @DeleteMapping("/{id}")
    ApiResponse<?> discard(@AuthenticationPrincipal PlatformPrincipal principal,@PathVariable("id") String id){
        jobs.discard(guard.personal(principal),id);
        return ApiResponse.ok("已放弃这份文件");
    }

    record Confirmation(String shopId,Map<String,Object> mapping) {}

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> confirm(@AuthenticationPrincipal PlatformPrincipal principal,@PathVariable("id") String id,@RequestBody Confirmation request){
        return ApiResponse.ok(jobs.confirm(guard.personal(principal),id,request.shopId(),request.mapping()));
    }
}
