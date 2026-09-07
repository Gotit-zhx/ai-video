package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.CookieManager;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/session/SQL/archive pipeline; only external downloader/model boundaries are mocked. */
@EnabledIfEnvironmentVariable(named="HEYI_DESIGN_HTTP_DB",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_design_http_test.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommerceDesignHttpIntegrationTest {
    @LocalServerPort int port;
    @MockitoBean MarketplaceImageDownloader downloader;
    @MockitoBean CommerceDesignImageGateway gateway;
    @MockitoBean ModelConfigService models;
    static Path archive;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{
        archive=Files.createTempDirectory("heyi-design-http-");
        r.add("spring.datasource.url",()->System.getenv("HEYI_DESIGN_HTTP_DB"));r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"");
        r.add("spring.data.redis.host",()->"127.0.0.1");r.add("spring.data.redis.port",()->System.getenv("HEYI_DESIGN_HTTP_REDIS_PORT"));r.add("spring.data.redis.password",()->"");
        r.add("app.commerce-image-storage",()->archive.toString());r.add("app.commerce-design-generation-enabled",()->true);
        r.add("app.dispatch.enabled",()->false);r.add("app.shop-auth.live-publishing-enabled",()->false);
        r.add("server.servlet.session.cookie.secure",()->false);r.add("APP_ADMIN_PASSWORD",()->"disposable-test-only");
    }
    @AfterAll static void cleanup()throws Exception{if(archive!=null)try(var paths=Files.walk(archive)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(5)).build();
    JsonNode call(String path,Object body,int expected)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
        if(body!=null)b.header("Idempotency-Key","http_design_test_123").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(b.build(),HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),path+" "+response.body());
        return json.readTree(response.body()).path("data");
    }
    @Test void capturedProductGeneratesOnceAndReviewPinsActualArchivedBytes()throws Exception{
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB),"png",bytes);byte[] png=bytes.toByteArray();
        when(downloader.download("https://cdn.example.com/ref.png")).thenReturn(new MarketplaceImageDownloader.ImageBinary(png,"image/png",".png"));
        when(models.imageConnection(anyString())).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection("model","https://mock-model.invalid","image-model","IMAGES","CUSTOM","test-only")));
        when(gateway.generate(any(),anyList(),anyString(),any())).thenReturn(new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(png),"image/png","image-model","edit",""),null));
        call("/api/v1/commerce-designs",null,401);
        call("/api/v1/auth/login",Map.of("organizationCode","heyi","phone","13800000000","password","disposable-test-only"),200);
        var capture=call("/api/v1/product-captures",Map.of("sourceUrl","https://shopee.ph/a-i.123.456","title","QA商品","images",List.of(Map.of("url","https://cdn.example.com/ref.png","role","MAIN"))),201);
        String captureId=capture.path("id").asText();assertFalse(captureId.isBlank());
        var created=call("/api/v1/commerce-designs",Map.of("captureId",captureId,"captureVersion",capture.path("version").asLong(),"mode","STOCK_MARKETING","variants",1,"brief","保留服装结构","rightsEvidence","QA使用权记录"),200);
        String design=created.get(0).path("id").asText(),imageId=capture.path("images").get(0).path("id").asText();String generation="/api/v1/commerce-designs/"+design+"/generation";
        var request=Map.of("imageIds",List.of(imageId),"confirmed",true);
        assertEquals("OUTPUT_ARCHIVED",call(generation,request,200).path("status").asText());
        assertEquals("OUTPUT_ARCHIVED",call(generation,request,200).path("status").asText());
        verify(gateway,times(1)).generate(any(),anyList(),anyString(),any());verify(downloader,times(1)).download(anyString());
        String outputPath="/api/v1/commerce-designs/"+design+"/output";var output=call(outputPath,null,200);
        assertEquals("PENDING_REVIEW",output.path("status").asText());
        var imageResponse=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+outputPath+"/image")).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,imageResponse.statusCode());assertArrayEquals(png,imageResponse.body());assertTrue(imageResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var checks=Map.of("singleImage",true,"noAnatomyArtifacts",true,"matchesDesignScope",true,"noInventedBackPrint",true);
        call(outputPath+"/review",Map.of("contentSha256","wrong","decision","APPROVE","checks",checks,"notes","QA审核","confirmed",true),409);
        var review=Map.of("contentSha256",output.path("contentSha256").asText(),"decision","APPROVE","checks",checks,"notes","QA审核","confirmed",true);
        call(outputPath+"/review",review,200);call(outputPath+"/review",review,200);
        assertEquals("QUALITY_APPROVED",call(outputPath,null,200).path("status").asText());
        call(outputPath+"/review",Map.of("contentSha256",output.path("contentSha256").asText(),"decision","REJECT","checks",checks,"notes","覆盖历史","confirmed",true),409);
    }
}
