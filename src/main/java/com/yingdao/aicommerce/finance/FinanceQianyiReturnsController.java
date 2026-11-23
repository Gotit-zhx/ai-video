package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/finance/qianyi/returns")
public class FinanceQianyiReturnsController {
    final FinanceQianyiReturns returns;final FinanceService finance;final FinanceTableAccess tableAccess;
    public FinanceQianyiReturnsController(FinanceQianyiReturns returns,FinanceService finance,FinanceTableAccess tableAccess){
        this.returns=returns;this.finance=finance;this.tableAccess=tableAccess;
    }
    @GetMapping public ApiResponse<?> list(@RequestParam Map<String,String> query,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=finance.authorize(p);
        String accessRevision=tableAccess.revision(p);
        var result=new LinkedHashMap<>(returns.list(scope,query));
        result.put("dataVersion",FinanceData.hash(returns.repo.write(result)));
        result.put("accessRevision",accessRevision);
        result.put("updatedAt",java.time.Instant.now().toString());
        return ApiResponse.ok(result);
    }
    @GetMapping("/detail") public ApiResponse<?> detail(@RequestParam String shopId,@RequestParam String returnNumber,@AuthenticationPrincipal PlatformPrincipal p){
        return ApiResponse.ok(returns.detail(finance.authorize(p),shopId,returnNumber));
    }
    @GetMapping("/export") public org.springframework.http.ResponseEntity<byte[]> export(@RequestParam Map<String,String> query,@AuthenticationPrincipal PlatformPrincipal p){
        var bytes=returns.export(finance.authorize(p),query);
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition","attachment; filename=\"qianyi-returns.xlsx\"")
            .header("Cache-Control","no-store")
            .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
}
