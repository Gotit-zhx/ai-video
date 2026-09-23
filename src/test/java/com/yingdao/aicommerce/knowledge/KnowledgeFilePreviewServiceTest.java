package com.yingdao.aicommerce.knowledge;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeFilePreviewServiceTest {
    private HttpServer server;
    private String endpoint;
    private final byte[] source={'P','K',3,4,1,2,3};
    private final byte[] pdf="%PDF-1.7\nprivate converted slides\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private final String sourceLocation="oss://dayan-test/heyi/knowledge/org-a/source.pptx";
    private final String sourceSha=KnowledgeFileService.digest(source);
    private final OssStorageService oss=mock(OssStorageService.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<OssStorageService> storage=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<StringRedisTemplate> redisProvider=mock(ObjectProvider.class);
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String,String> values=mock(ValueOperations.class);
    private final Map<String,String> pointers=new ConcurrentHashMap<>();
    private final Map<String,byte[]> objects=new ConcurrentHashMap<>();
    private final AtomicInteger calls=new AtomicInteger(),responseStatus=new AtomicInteger(200);
    private final AtomicReference<String> receivedAuth=new AtomicReference<>(),receivedQuery=new AtomicReference<>(),responseType=new AtomicReference<>("application/pdf");
    private final AtomicReference<byte[]> receivedBody=new AtomicReference<>(),responseBody=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        responseBody.set(pdf);objects.put(sourceLocation,source);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/convert",exchange->{try {
            calls.incrementAndGet();receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));receivedQuery.set(exchange.getRequestURI().getQuery());receivedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body=responseBody.get();exchange.getResponseHeaders().set("Content-Type",responseType.get());exchange.sendResponseHeaders(responseStatus.get(),body.length);exchange.getResponseBody().write(body);
        }finally{exchange.close();}});server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        when(storage.getIfAvailable()).thenReturn(oss);when(redisProvider.getIfAvailable()).thenReturn(redis);when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call->pointers.get(call.getArgument(0,String.class)));
        doAnswer(call->{pointers.put(call.getArgument(0),call.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
        when(oss.isOssLocation(anyString())).thenAnswer(call->call.getArgument(0,String.class).startsWith("oss://dayan-test/"));
        when(oss.readAllBytes(sourceLocation,KnowledgeFileService.MAX_BYTES)).thenReturn(source);
        when(oss.store(any(byte[].class),eq("application/pdf"),anyString(),eq("preview.pdf"))).thenAnswer(call->{byte[] content=call.getArgument(0);String location="oss://dayan-test/heyi/"+call.getArgument(2)+"/pdf-sha/preview.pdf";objects.put(location,content.clone());return new OssStorageService.StoredObject(location,content.length);});
        when(oss.download(anyString())).thenAnswer(call->{String location=call.getArgument(0);byte[] content=objects.get(location);if(content==null)throw new IllegalStateException("missing");return new OssStorageService.Download(new ByteArrayResource(content),content.length,"application/pdf");});
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    private KnowledgeFilePreviewService service(String token){return new KnowledgeFilePreviewService(storage,redisProvider,endpoint,token,"dayan-test");}
    @Test void sendsPrivateOriginalToInternalConverterAndReusesVerifiedOssPdfCache() throws Exception {
        var service=service("internal-secret");var first=service.convert("org-a",sourceLocation,sourceSha,"pptx");
        assertArrayEquals(pdf,first.resource().getInputStream().readAllBytes());assertEquals(1,calls.get());assertEquals("Bearer internal-secret",receivedAuth.get());assertEquals("format=pptx",receivedQuery.get());assertArrayEquals(source,receivedBody.get());
        var second=service.convert("org-a",sourceLocation,sourceSha,"pptx");assertEquals(first.size(),second.size());assertEquals(1,calls.get());
        assertEquals(1,pointers.size());assertTrue(pointers.values().iterator().next().startsWith("oss://dayan-test/heyi/knowledge-preview/org-a/"+sourceSha+"/"));
        verify(oss,times(1)).store(any(byte[].class),eq("application/pdf"),anyString(),eq("preview.pdf"));assertArrayEquals(source,objects.get(sourceLocation));
    }
    @Test void missingTokenNeverSendsOrWritesButExistingPrivateCacheStillWorks() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,()->service("").convert("org-a",sourceLocation,sourceSha,"pptx")).getStatus());assertEquals(0,calls.get());
        service("secret").convert("org-a",sourceLocation,sourceSha,"pptx");assertNotNull(service("").convert("org-a",sourceLocation,sourceSha,"pptx"));assertEquals(1,calls.get());
    }
    @Test void busyAndInvalidResponsesAreRetryableAndNeverCached() {
        var service=service("secret");responseStatus.set(429);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,assertThrows(BusinessException.class,()->service.convert("org-a",sourceLocation,sourceSha,"pptx")).getStatus());assertTrue(pointers.isEmpty());
        responseStatus.set(200);responseType.set("text/html");responseBody.set("<html>failure</html>".getBytes());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,()->service.convert("org-a",sourceLocation,sourceSha,"pptx")).getStatus());assertTrue(pointers.isEmpty());
        responseType.set("application/pdf");responseBody.set("not a pdf".getBytes());assertThrows(BusinessException.class,()->service.convert("org-a",sourceLocation,sourceSha,"pptx"));assertTrue(pointers.isEmpty());
        responseBody.set(pdf);assertNotNull(service.convert("org-a",sourceLocation,sourceSha,"pptx"));assertEquals(1,pointers.size());
    }
    @Test void cacheCannotPointAtAnotherTenantAndSourceHashMustMatch() throws Exception {
        pointers.put("heyi:knowledge:preview:libreoffice-v1:org-a:"+sourceSha,"oss://dayan-test/heyi/knowledge-preview/org-b/other.pdf");
        service("secret").convert("org-a",sourceLocation,sourceSha,"pptx");assertEquals(1,calls.get());
        verify(oss,never()).download("oss://dayan-test/heyi/knowledge-preview/org-b/other.pdf");
        assertThrows(BusinessException.class,()->service("secret").convert("org-a",sourceLocation,"0".repeat(64),"pptx"));assertEquals(1,calls.get());
        assertThrows(BusinessException.class,()->service("secret").convert("org-a","https://external.example/file",sourceSha,"pptx"));
    }
    @Test void redisOutageDoesNotHideSuccessfulPrivateConversion() {
        when(redisProvider.getIfAvailable()).thenThrow(new IllegalStateException("redis unavailable"));
        assertNotNull(service("secret").convert("org-a",sourceLocation,sourceSha,"pptx"));assertEquals(1,calls.get());
    }
    @Test void boundedResponseSubscriberCancelsBeforeOversizedAllocation() {
        var body=new KnowledgeFilePreviewService.LimitedBody();var subscription=mock(Flow.Subscription.class);body.onSubscribe(subscription);
        // The subscriber checks ByteBuffer.remaining() before allocating or copying the oversized body.
        body.onNext(List.of(ByteBuffer.allocate(KnowledgeFileService.MAX_BYTES+1)));
        verify(subscription).cancel();assertThrows(CompletionException.class,()->body.getBody().toCompletableFuture().join());
    }
}
