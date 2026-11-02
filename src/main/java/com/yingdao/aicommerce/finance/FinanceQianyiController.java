package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@RestController
@RequestMapping("/api/v1/finance/shops/{shop}/qianyi")
public class FinanceQianyiController {
    final FinanceQianyi integration;final FinanceService finance;
    public FinanceQianyiController(FinanceQianyi integration,FinanceService finance){this.integration=integration;this.finance=finance;}
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal principal,HttpServletRequest request){
        finance.requireManagement(principal);
        if(Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()))return;
        String site=request.getHeader("Sec-Fetch-Site");
        if(site!=null&&!Set.of("same-origin","none").contains(site))throw new BusinessException(HttpStatus.FORBIDDEN,"请从本站配置千易授权");
        String origin=request.getHeader("Origin");
        if(origin!=null)try{
            if(!request.getServerName().equalsIgnoreCase(java.net.URI.create(origin).getHost()))throw bad("操作来源不正确");
        }catch(IllegalArgumentException e){throw bad("操作来源不正确");}
    }
    @GetMapping public ApiResponse<?> get(@PathVariable String shop,@AuthenticationPrincipal PlatformPrincipal p){
        return ApiResponse.ok(integration.configuration(finance.authorize(p),shop));
    }
    @PostMapping("/shops") public ApiResponse<?> shops(@PathVariable String shop,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){
        return ApiResponse.ok(integration.discover(finance.authorize(p),shop,body));
    }
    @PostMapping("/credentials") public ApiResponse<?> credentials(@PathVariable String shop,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){
        throw bad("千易已改为公司统一配置，请刷新页面后使用财务店铺顶部的「千易全局配置」");
    }
    @PutMapping public ApiResponse<?> save(@PathVariable String shop,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){
        throw bad("千易已改为公司统一配置，请刷新页面后使用财务店铺顶部的「千易全局配置」");
    }
    @PostMapping("/sync") public ApiResponse<?> sync(@PathVariable String shop,@AuthenticationPrincipal PlatformPrincipal p){
        integration.sync(finance.authorize(p),shop);return ApiResponse.ok("已加入千易同步队列");
    }
    @GetMapping("/returns") public ApiResponse<?> returns(@PathVariable String shop,@RequestParam(defaultValue="1") int page,@AuthenticationPrincipal PlatformPrincipal p){
        return ApiResponse.ok(integration.details(finance.authorize(p),shop,page));
    }
}
