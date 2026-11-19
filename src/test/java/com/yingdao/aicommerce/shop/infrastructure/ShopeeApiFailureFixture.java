package com.yingdao.aicommerce.shop.infrastructure;

import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.*;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class ShopeeApiFailureFixture {
    private ShopeeApiFailureFixture() {}
    public static MarketplaceApiException failure(String path,String code,String message){
        var http=new MarketplaceHttp(new ObjectMapper()){
            @Override public Map<String,Object> postJson(URI uri,Map<String,String> headers,String body){return Map.of("error",code,"message",message,"request_id","test-only-request");}
        };
        var api=new ShopeeOfficialClient(http,true,"1234567","test-only-key","https://open.shopee.com","https://partner.shopeemobile.com","SHOP");
        var shop=new Shop("org",MarketplacePlatform.SHOPEE,"10001",null,null,"SELLER","Shop","PH","[]","owner","SHOP","10001");
        var connection=new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("test-only-access",null,Instant.now().plusSeconds(3600),null,List.of()));
        return assertThrows(MarketplaceApiException.class,()->api.shopPost(path,connection,Map.of()));
    }
}
