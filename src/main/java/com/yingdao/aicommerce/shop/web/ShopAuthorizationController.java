package com.yingdao.aicommerce.shop.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.finance.FinanceShopAuthorization;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/shop-authorizations")
public class ShopAuthorizationController {
    private final ShopAuthorizationService service;
    private final FinanceShopAuthorization finance;

    public ShopAuthorizationController(ShopAuthorizationService service) {
        this(service,null);
    }
    @Autowired
    public ShopAuthorizationController(ShopAuthorizationService service,FinanceShopAuthorization finance) {
        this.service = service;this.finance=finance;
    }

    @PostMapping("/{platform}/start")
    public ApiResponse<ShopAuthorizationService.AuthorizationStart> start(
            @PathVariable String platform,
            @RequestBody(required = false) StartRequest request,
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.start(platform,
                request == null ? null : request.returnPath(),
                request == null ? null : request.market(), principal));
    }

    @GetMapping("/callback/{platform}")
    public ResponseEntity<Void> callback(
            @PathVariable String platform,
            @RequestParam Map<String, String> parameters) {
        String state = parameters.get("state");
        String code = first(parameters, "code", "auth_code");
        String providerError = first(parameters, "error_description", "error", "message");
        if(FinanceShopAuthorization.financeState(state)&&finance!=null){
            try{return redirect(finance.complete(platform,state,code,new LinkedHashMap<>(parameters),providerError));}
            catch(BusinessException exception){return redirect(finance.redirect(false,"财务授权已过期或无效，请回到财务店铺管理重新连接"));}
        }
        try {
            if (providerError != null) {
                String message = "平台拒绝了店铺授权：" + providerError;
                service.reject(platform, state, message);
                return redirect(service.callbackErrorRedirect(platform, message));
            }
            var result = service.complete(platform, state, code,
                    new LinkedHashMap<>(parameters));
            return redirect(service.callbackRedirect(result));
        } catch (BusinessException exception) {
            return redirect(service.callbackErrorRedirect(platform, exception.getMessage()));
        }
    }

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, URI.create(url).toASCIIString())
                .build();
    }

    private String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public record StartRequest(String returnPath, String market) {
        public StartRequest(String returnPath) { this(returnPath, null); }
    }
}
