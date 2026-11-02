package com.yingdao.aicommerce.finance;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceQianyiClientTest {
    final FinanceRepository repo=mock(FinanceRepository.class);
    final ObjectMapper json=new ObjectMapper();
    final FinanceQianyiClient client=new FinanceQianyiClient(repo);
    FinanceQianyiClientTest(){
        when(repo.write(any())).thenAnswer(a->json.writeValueAsString(a.getArgument(0)));
        when(repo.read(anyString())).thenAnswer(a->json.readValue((String)a.getArgument(0),Map.class));
    }
    @Test void matchesOfficialUtf8AlgorithmUsingIndependentlyCalculatedDigest(){
        // Expected value calculated separately with Python hashlib from the documented canonical string.
        var business="{\"asnSkuVOList\":[{\"expectQuantity\":\"100\",\"sku\":\"SKU1230\"}],\"warehouseName\":\"测试仓库\"}";
        assertEquals("46bc640fe3d0e2866afde2980fe85392",FinanceQianyiClient.sign(
            "openApi_TEST",business,"CREATE_ASN_ORDER",1662448537275L,"openApi_TOKEN"));
        assertNotEquals(FinanceQianyiClient.sign("app","{}","QUERY_SHOP_LIST",1,"key"),
            FinanceQianyiClient.sign("app","{ }","QUERY_SHOP_LIST",1,"key"),"sign the exact transmitted JSON");
    }
    @Test void sendsFiveMultipartFieldsToOfficialHttpsEndpointAndNeverSendsSecret()throws Exception{
        var credentials=new FinanceQianyiClient.Credentials("CN","test-app","private-test-secret");
        var request=client.request(credentials,"QUERY_SHOP_LIST",Map.of("page",1,"pageSize",200),1662448537275L);
        assertEquals("https://www.qianyierp.com/api/v1/shop",request.uri().toString());
        assertEquals("POST",request.method());
        var subscriber=new FinanceQianyiClient.BoundedBody();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription value){subscriber.onSubscribe(value);}
            public void onNext(ByteBuffer value){subscriber.onNext(List.of(value));}
            public void onError(Throwable failure){subscriber.onError(failure);}
            public void onComplete(){subscriber.onComplete();}
        });
        String body=new String(subscriber.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS),StandardCharsets.UTF_8);
        var fields=new HashMap<String,String>();
        var matcher=java.util.regex.Pattern.compile("name=\"([^\"]+)\"\\r\\nContent-Type: text/plain; charset=UTF-8\\r\\n\\r\\n(.*?)\\r\\n",java.util.regex.Pattern.DOTALL).matcher(body);
        while(matcher.find())fields.put(matcher.group(1),matcher.group(2));
        assertEquals(Set.of("appId","serviceType","bizParam","timestamp","sign"),fields.keySet());
        assertEquals("test-app",fields.get("appId"));assertEquals("1662448537275",fields.get("timestamp"));
        assertEquals(Map.of("page",1,"pageSize",200),json.readValue(fields.get("bizParam"),Map.class));
        assertEquals(FinanceQianyiClient.sign(fields.get("appId"),fields.get("bizParam"),
            fields.get("serviceType"),Long.parseLong(fields.get("timestamp")),credentials.secret()),fields.get("sign"));
        assertFalse(body.contains(credentials.secret()));assertFalse(credentials.toString().contains(credentials.secret()));
        assertTrue(request.headers().firstValue("Content-Type").orElseThrow().startsWith("multipart/form-data; boundary="));
        assertEquals("asia.qianyierp.com",client.request(new FinanceQianyiClient.Credentials("ASIA","a","s"),"QUERY_RETURN_ORDER_LIST",Map.of(),1).uri().getHost());
        assertEquals("/api/v1/inventory",client.request(credentials,"QUERY_INVENTORY_LOG_LIST",Map.of(),1).uri().getPath());
        assertThrows(RuntimeException.class,()->client.request(credentials,"DELETE_ORDER",Map.of(),1));
        assertThrows(RuntimeException.class,()->client.request(new FinanceQianyiClient.Credentials("https://other-host","a","s"),"QUERY_SHOP_LIST",Map.of(),1));
    }
    String envelope(Object content){return json.writeValueAsString(Map.of("state","success","errorCode","0","bizContent",content));}
    @Test void decodesStringOrObjectPayloadWithoutInventingMandatoryInnerState(){
        for(Object payload:List.of(Map.of("result",List.of(Map.of("shopId","586208660231587825")),"total",1),
            json.writeValueAsString(Map.of("state","success","notSuccess",false,"result",List.of(Map.of("shopId","586208660231587825")),"total",1)))){
            var result=client.decode(envelope(payload));
            assertEquals(1,result.total());assertEquals("586208660231587825",result.items().getFirst().get("shopId"));
        }
    }
    @Test void rejectsExplicitBusinessErrorsAndMalformedOrIncompletePagination(){
        for(var payload:List.of(
            Map.of("state","failure","errorCode","AUTH_001","result",List.of(),"total",0),
            Map.of("state","success","notSuccess",true,"result",List.of(),"total",0),
            Map.of("state","success","errorCode","AUTH_002","result",List.of(),"total",0),
            Map.of("result",List.of("bad-row"),"total",1),
            Map.of("result",List.of(),"total","invalid"),
            Map.of("result",List.of(),"total","0.5"),
            Map.of("result",List.of(Map.of()),"total",0),
            Map.of("result",Collections.nCopies(201,Map.of()),"total",201),
            Map.of("total",0))){
            assertThrows(RuntimeException.class,()->client.decode(envelope(payload)));
        }
        assertThrows(RuntimeException.class,()->client.decode(envelope("{invalid")));
        var failure=assertThrows(RuntimeException.class,()->client.decode(
            "{\"state\":\"failure\",\"errorCode\":\"AUTH_001\",\"errorMsg\":\"sensitive remote body\"}"));
        assertTrue(failure.getMessage().contains("AUTH_001"));assertFalse(failure.getMessage().contains("sensitive"));
    }
    @Test void cancelsOversizedResponsesBeforeBufferingTheExtraChunk(){
        var body=new FinanceQianyiClient.BoundedBody();var subscription=mock(Flow.Subscription.class);body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[FinanceQianyiClient.MAX_RESPONSE_BYTES])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        verify(subscription).cancel();
        assertEquals(FinanceQianyiClient.MAX_RESPONSE_BYTES,body.bytes.size());
        assertThrows(CompletionException.class,()->body.getBody().toCompletableFuture().join());
    }
    @Test void upstreamThrottlingPausesAllLanesAndReducesTheirSharedRequestRate(){
        long before=System.nanoTime();
        var failure=assertThrows(RuntimeException.class,()->client.decode(
            "{\"state\":\"failure\",\"errorCode\":\"REQUEST_IS_THROTTLED\"}"));
        assertTrue(failure.getMessage().contains("限流"));
        assertFalse(failure.getMessage().contains("鉴权"));
        assertTrue(client.nextRequest>=before+TimeUnit.SECONDS.toNanos(60));
        assertEquals(1500,client.requestIntervalMillis);
        client.throttled();assertEquals(2000,client.requestIntervalMillis);
    }
}
