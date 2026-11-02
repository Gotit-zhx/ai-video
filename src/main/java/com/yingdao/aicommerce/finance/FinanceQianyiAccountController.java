package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/finance/qianyi")
public class FinanceQianyiAccountController {
    final FinanceQianyiAccount account;final FinanceQianyiController permissions;final FinanceService finance;
    public FinanceQianyiAccountController(FinanceQianyiAccount account,FinanceQianyiController permissions,FinanceService finance){
        this.account=account;this.permissions=permissions;this.finance=finance;
    }
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal principal,HttpServletRequest request){permissions.authorize(principal,request);}
    @GetMapping public ApiResponse<?> get(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(account.configuration(finance.authorize(p)));}
    @PutMapping public ApiResponse<?> save(@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){
        account.save(finance.authorize(p),body);return ApiResponse.ok("公司千易配置已保存，后台自动关联全部财务店铺");
    }
    @GetMapping("/shops") public ApiResponse<?> shops(@RequestParam(defaultValue="1") int page,@AuthenticationPrincipal PlatformPrincipal p){
        return ApiResponse.ok(account.shops(finance.authorize(p),page));
    }
    @PostMapping("/sync") public ApiResponse<?> sync(@AuthenticationPrincipal PlatformPrincipal p){
        account.sync(finance.authorize(p));return ApiResponse.ok("已安排自动关联和售后同步，原有进度保留");
    }
}
