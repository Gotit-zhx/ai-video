package com.yingdao.aicommerce.shop.infrastructure;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopeeAdsExactMoneyTest {
    @Test void signedShopReportPreservesOfficialNumericExpenseWithoutDoubleRoundTrip()throws Exception{
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        byte[] body="{\"response\":[{\"date\":\"01-10-2026\",\"expense\":123456789012345678.123456}]}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/api/v2/ads/get_all_cpc_ads_daily_performance",exchange->{try{assertTrue(exchange.getRequestURI().getQuery().contains("shop_id=10001"));assertTrue(exchange.getRequestURI().getQuery().contains("partner_id=1234567"));exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        try(var http=HttpClient.newHttpClient()){
            var api=new ShopeeOfficialClient(new MarketplaceHttp(new ObjectMapper(),http),true,"1234567","fake-key-for-http-test-only","https://open.shopee.com","http://localhost:"+server.getAddress().getPort(),"SHOP");
            var shop=new Shop("org",MarketplacePlatform.SHOPEE,"10001",null,null,"SELLER","Shop","PH","[]","owner","SHOP","10001");
            var response=api.shopGetExact("/api/v2/ads/get_all_cpc_ads_daily_performance",new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("test-only-access",null,Instant.now().plusSeconds(3600),null,List.of())),Map.of("start_date","01-10-2026","end_date","07-10-2026"));
            assertEquals(new BigDecimal("123456789012345678.123456"),((Map<?,?>)((List<?>)response.get("response")).getFirst()).get("expense"));
        }finally{server.stop(0);}
    }
}
