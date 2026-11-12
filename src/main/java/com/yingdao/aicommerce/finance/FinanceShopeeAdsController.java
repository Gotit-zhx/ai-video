package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/finance/shopee-ads")
public class FinanceShopeeAdsController {
    private final FinanceShopeeAds ads;private final FinanceController guard;
    public FinanceShopeeAdsController(FinanceShopeeAds ads,FinanceController guard){this.ads=ads;this.guard=guard;}
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal principal,HttpServletRequest request){guard.authorize(principal,request);}
    @GetMapping public ApiResponse<?> status(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(ads.status(guard.s(p)));}
    record Company(String organizationId) {}
    @PostMapping("/authorize") public ApiResponse<?> start(@RequestBody Company body,@AuthenticationPrincipal PlatformPrincipal p){if(!p.organizationId().equals(body.organizationId()))throw FinanceData.bad("登录公司已变化，请重新打开授权窗口");return ApiResponse.ok(ads.start(guard.s(p)));}
    record Toggle(String organizationId,boolean enabled) {}
    @PutMapping("/{external}") public ApiResponse<?> pause(@PathVariable String external,@RequestBody Toggle body,@AuthenticationPrincipal PlatformPrincipal p){if(!p.organizationId().equals(body.organizationId()))throw FinanceData.bad("登录公司已变化，请刷新后重试");ads.pause(guard.s(p),external,body.enabled());return ApiResponse.ok("已保存 Shopee 广告采集设置");}
}
