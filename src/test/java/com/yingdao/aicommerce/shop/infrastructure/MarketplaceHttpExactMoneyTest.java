package com.yingdao.aicommerce.shop.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MarketplaceHttpExactMoneyTest {
    @Test void optInFinancialHttpResponsesPreserveEveryDecimalDigit()throws Exception{
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        byte[] body="{\"amount\":123456789012345678.123456,\"nested\":{\"cash_amount\":0.123456}}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/cost",exchange->{try{exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});
        server.start();
        try(var client=HttpClient.newHttpClient()){
            var http=new MarketplaceHttp(new ObjectMapper(),client);
            URI uri=URI.create("http://localhost:"+server.getAddress().getPort()+"/cost");
            var exact=http.getExact(uri,Map.of());
            assertInstanceOf(BigDecimal.class,exact.get("amount"));
            assertEquals(new BigDecimal("123456789012345678.123456"),exact.get("amount"));
            assertEquals(new BigDecimal("0.123456"),((Map<?,?>)exact.get("nested")).get("cash_amount"));
            assertInstanceOf(Double.class,http.get(uri,Map.of()).get("amount"),"other marketplace responses retain their existing reader");
        }finally{server.stop(0);}
    }
}
