package com.yingdao.aicommerce.finance;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Map;

/** Public provider callback, authenticated by a one-time, expiring finance state. */
@RestController
public class FinanceAdAuthorizationCallback {
    private final FinanceAdAuthorization authorization;
    public FinanceAdAuthorizationCallback(FinanceAdAuthorization authorization){this.authorization=authorization;}
    @GetMapping("/api/v1/shop-authorizations/callback/tiktok_ads")
    public ResponseEntity<Void> callback(@RequestParam Map<String,String> query){
        String redirect;
        try{redirect=authorization.complete(query.get("state"),query.getOrDefault("auth_code",query.get("code")),
                query.getOrDefault("error_description",query.get("error")));}
        catch(com.yingdao.aicommerce.common.exception.BusinessException e){
            redirect=authorization.redirect(false,"广告授权链接已过期或无效，请在财务店铺管理重新生成");
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect))
                .header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").build();
    }
}
