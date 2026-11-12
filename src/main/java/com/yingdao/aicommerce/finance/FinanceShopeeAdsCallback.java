package com.yingdao.aicommerce.finance;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Map;

@RestController
public class FinanceShopeeAdsCallback {
    private final FinanceShopeeAds ads;
    public FinanceShopeeAdsCallback(FinanceShopeeAds ads){this.ads=ads;}
    @GetMapping("/api/v1/shop-authorizations/callback/shopee_ads")
    public ResponseEntity<Void> callback(@RequestParam Map<String,String> query){
        String redirect;
        try{redirect=ads.complete(query.get("state"),query.get("code"),query,query.getOrDefault("error_description",query.get("error")));}
        catch(RuntimeException invalid){redirect=ads.redirect(false);}
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").build();
    }
}
