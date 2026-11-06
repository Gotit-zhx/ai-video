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
    @org.springframework.beans.factory.annotation.Autowired com.yingdao.aicommerce.product.mapper.CommerceBatchMapper batches;
    @org.springframework.beans.factory.annotation.Autowired com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper templates;
    @org.springframework.beans.factory.annotation.Autowired CommerceBatchExecutionWorker batchWorker;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @org.springframework.beans.factory.annotation.Autowired CommerceCaptureSourceArchiveService sourceArchives;
    @LocalServerPort int port;
    @MockitoBean MarketplaceImageDownloader downloader;
    @MockitoBean CommerceDesignImageGateway gateway;
    @MockitoBean ModelConfigService models;
    static Path archive;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{
        archive=Files.createTempDirectory("heyi-design-http-");
        r.add("spring.datasource.url",()->System.getenv("HEYI_DESIGN_HTTP_DB"));r.add("spring.datasource.username",()->System.getenv().getOrDefault("HEYI_TEST_DB_USER","root"));r.add("spring.datasource.password",()->System.getenv().getOrDefault("HEYI_TEST_DB_PASSWORD",""));
        r.add("spring.data.redis.host",()->"127.0.0.1");r.add("spring.data.redis.port",()->System.getenv("HEYI_DESIGN_HTTP_REDIS_PORT"));r.add("spring.data.redis.password",()->"");
        r.add("app.commerce-image-storage",()->archive.toString());r.add("app.commerce-design-generation-enabled",()->true);
        r.add("app.dispatch.enabled",()->false);r.add("app.shop-auth.live-publishing-enabled",()->false);
        r.add("app.batch-execution.initial-delay",()->"3600000");
        r.add("server.servlet.session.cookie.secure",()->false);r.add("APP_ADMIN_PASSWORD",()->"disposable-test-only");
    }
    @AfterAll static void cleanup()throws Exception{if(archive!=null)try(var paths=Files.walk(archive)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    final ObjectMapper json=new ObjectMapper();
    final String requestKey="http_design_test_"+UUID.randomUUID();
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(5)).build();
    JsonNode call(String path,Object body,int expected)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
        if(body!=null)b.header("Idempotency-Key",requestKey).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
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
        // Seed only the batch target fixture; binding itself goes through real HTTP/security/SQL/archive.
        var identity=call("/api/v1/auth/me",null,200);String org=identity.path("organizationId").asText(),actor=identity.path("userId").asText();
        var now=java.time.Instant.now();
        templates.insert(new com.yingdao.aicommerce.product.domain.CommerceTemplateVersion("asset-template",org,"asset-template-root",1,"WORKFLOW","QA","{\"mode\":\"STOCK_MARKETING\"}",actor,now));
        batches.insert(new com.yingdao.aicommerce.product.domain.CommerceBatch("asset-batch",org,"QA","asset-template","asset-key","asset-hash","DRAFT",actor,now));
        batches.insertItem(new com.yingdao.aicommerce.product.domain.CommerceBatchItem("asset-item",org,"asset-batch",captureId,capture.path("version").asLong(),"qa-shop","QA",1,"DRAFT",now));
        String assetsPath="/api/v1/commerce-batches/asset-batch/design-assets";
        String targetsPath="/api/v1/commerce-designs/"+design+"/batch-targets";
        var targets=call(targetsPath,null,200);assertEquals(1,targets.size());assertEquals("asset-item",targets.get(0).path("itemId").asText());assertEquals(1,targets.get(0).path("nextImagePosition").asInt());
        var candidates=call(assetsPath+"/candidates?itemId=asset-item",null,200);assertEquals(design,candidates.get(0).path("designId").asText());
        var binding=Map.of("itemId","asset-item","designId",design,"contentSha256",output.path("contentSha256").asText(),"imagePosition",1,"confirmed",true);
        var bound=call(assetsPath,binding,200);assertEquals(bound.path("id"),call(assetsPath,binding,200).path("id"));
        assertEquals(1,call(assetsPath,null,200).size());
        assertEquals(0,call(targetsPath,null,200).size(),"Already bound output is excluded from discovery");
        call(assetsPath,Map.of("itemId","asset-item","designId",design,"contentSha256",output.path("contentSha256").asText(),"imagePosition",2,"confirmed",true),409);
    }
    @Test void creativePlanPersistsChecksVersionsAndPinsOneConcurrentExecution()throws Exception {
        call("/api/v1/auth/login",Map.of("organizationCode","heyi","phone","13800000000","password","disposable-test-only"),200);
        var identity=call("/api/v1/auth/me",null,200);
        String org=identity.path("organizationId").asText(),actor=identity.path("userId").asText();
        var capture=call("/api/v1/product-captures",Map.of("sourceUrl","https://shopee.ph/a-i.123.789","title","创作配置测试",
                "images",List.of(Map.of("url","https://cdn.example.com/first.png","role","MAIN"),Map.of("url","https://cdn.example.com/second.png","role","DETAIL"))),201);
        String captureId=capture.path("id").asText(); long sourceVersion=capture.path("version").asLong();
        var now=java.time.Instant.now();
        templates.insert(new com.yingdao.aicommerce.product.domain.CommerceTemplateVersion("creative-workflow",org,"creative-workflow-root",1,"WORKFLOW","QA","{}",actor,now));
        templates.insert(new com.yingdao.aicommerce.product.domain.CommerceTemplateVersion("creative-layout-v1",org,"creative-layout-root",1,"PRINT_LAYOUT","QA","{}",actor,now));
        templates.insert(new com.yingdao.aicommerce.product.domain.CommerceTemplateVersion("creative-layout-v2",org,"creative-layout-root",2,"PRINT_LAYOUT","QA","{}",actor,now));
        templates.insert(new com.yingdao.aicommerce.product.domain.CommerceTemplateVersion("creative-other-org","other-org","creative-other-root",1,"PRINT_LAYOUT","QA","{}",actor,now));
        batches.insert(new com.yingdao.aicommerce.product.domain.CommerceBatch("creative-batch",org,"QA","creative-workflow","creative-key","creative-hash","DRAFT",actor,now));
        for(int i=1;i<=2;i++) batches.insertItem(new com.yingdao.aicommerce.product.domain.CommerceBatchItem("creative-item-"+i,org,"creative-batch",captureId,sourceVersion,"shop-"+i,"QA",1,"DRAFT",now));
        String path="/api/v1/commerce-batches/creative-batch/creative-plan",start="/api/v1/commerce-batches/creative-batch/execution";
        var empty=call(path,null,200); assertEquals(0,empty.path("version").asInt()); assertEquals(1,empty.path("sources").size());
        call(start,Map.of("confirmed",true,"creativePlanVersion",0,"maxModelCalls",0),409);
        assertTrue(call(start,null,200).isNull(),"Invalid startup must not insert an execution");
        String image=capture.path("images").get(1).path("id").asText();
        var choices=List.of(Map.of("captureId",captureId,"version",sourceVersion,"imageId",image,"cleanupAreas",List.of(Map.of("x",0.25,"y",0.25,"width",0.5,"height",0.5))));
        var request=new LinkedHashMap<String,Object>(); request.put("expectedVersion",0);request.put("printTemplateSnapshotId","creative-workflow");request.put("sources",choices);request.put("variantsPerPrint",3);
        call(path,request,409);
        request.put("printTemplateSnapshotId","creative-other-org");call(path,request,409);
        request.put("printTemplateSnapshotId","creative-layout-v1");request.put("sources",List.of());call(path,request,409);
        request.put("sources",List.of(Map.of("captureId",captureId,"version",sourceVersion,"imageId","foreign-image")));call(path,request,409);
        request.put("sources",choices);
        var saved=call(path,request,200); assertEquals(1,saved.path("version").asInt());
        var restored=call(path,null,200);assertEquals(image,restored.path("plan").path("sources").get(0).path("imageId").asText());
        assertEquals("creative-layout-v1",restored.path("plan").path("printTemplateSnapshotId").asText(),"Newer template must not replace selected snapshot");
        call(path,request,409); // another tab still has version 0
        request.put("expectedVersion",1);
        var saveRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
        var saveOne=http.sendAsync(saveRequest,HttpResponse.BodyHandlers.ofString());
        var saveTwo=http.sendAsync(saveRequest,HttpResponse.BodyHandlers.ofString());
        assertEquals(Set.of(200,409),Set.of(saveOne.get().statusCode(),saveTwo.get().statusCode()),"Only one concurrent editor can save version 2");
        call(start,Map.of("confirmed",true,"creativePlanVersion",1,"maxModelCalls",0),409);
        var startRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+start)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("confirmed",true,"creativePlanVersion",2,"maxModelCalls",0)))).build();
        var one=http.sendAsync(startRequest,HttpResponse.BodyHandlers.ofString());
        var two=http.sendAsync(startRequest,HttpResponse.BodyHandlers.ofString());
        var first=one.get();var second=two.get();assertEquals(200,first.statusCode());assertEquals(200,second.statusCode());
        assertEquals(json.readTree(first.body()).path("data").path("id"),json.readTree(second.body()).path("data").path("id"));
        var pinned=call(path,null,200);assertTrue(pinned.path("locked").asBoolean());assertEquals(2,pinned.path("version").asInt());
        assertEquals("creative-layout-v1",pinned.path("plan").path("printTemplateSnapshotId").asText());
        request.put("expectedVersion",2);request.put("variantsPerPrint",9);call(path,request,409);
        assertEquals(3,call(path,null,200).path("plan").path("variantsPerPrint").asInt());
        assertEquals(0,call(start+"/limits",null,200).path("maxCalls").asInt());
        verifyNoInteractions(gateway,downloader,models);
    }
    record CreativeFixture(String batch,String run,String sourceImage) {}
    byte[] artworkPng()throws Exception {
        var output=new ByteArrayOutputStream();var image=new BufferedImage(96,96,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,96,96);g.setColor(java.awt.Color.BLUE);g.fillRect(32,32,32,32);g.dispose();
        ImageIO.write(image,"png",output);return output.toByteArray();
    }
    CreativeFixture creativeFixture(int variants,int calls)throws Exception {
        call("/api/v1/auth/login",Map.of("organizationCode","heyi","phone","13800000000","password","disposable-test-only"),200);
        byte[] png=artworkPng();
        when(models.imageConnection(anyString())).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection("batch-model","https://mock-model.invalid","image-model","IMAGES","CUSTOM","test-only")));
        when(downloader.download("https://cdn.example.com/batch-source.png")).thenReturn(new MarketplaceImageDownloader.ImageBinary(png,"image/png",".png"));
        var generated=new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(png),"image/png","image-model","edit",""),null);
        when(gateway.generateArtwork(anyString(),anyString(),any(),anyList())).thenReturn(generated);
        when(gateway.generateTransparentArtwork(anyString(),anyString(),any(),anyList())).thenReturn(generated);
        when(gateway.generateProductPreview(anyString(),anyString(),any(),anyList())).thenReturn(generated);
        var capture=call("/api/v1/product-captures",Map.of("sourceUrl","https://shopee.ph/a-i.123."+(100000+new Random().nextInt(900000000)),"title","自动创作测试","images",List.of(Map.of("url","https://cdn.example.com/batch-source.png","role","MAIN"))),201);
        var area=Map.of("id","front","side","FRONT","x",0.3,"y",0.3,"width",0.4,"height",0.4,"printWidthMm",200,"printHeightMm",200,"dpi",300);
        var template=call("/api/v1/commerce-print-templates",Map.of("expectedVersion",0,"name","自动创作测试模板","configuration",Map.of("product","T_SHIRT","areas",List.of(area))),200);
        var batch=call("/api/v1/commerce-batches/creative-only",Map.of("name","仅创作自动链路","printTemplateSnapshotId",template.path("snapshotId").asText(),"captureIds",List.of(capture.path("id").asText())),200);
        assertTrue(batch.path("items").get(0).path("shopId").isNull(),"Creative mode must not fabricate a shop");
        String id=batch.path("id").asText();
        call("/api/v1/commerce-batches/"+id+"/creative-plan",Map.of("expectedVersion",0,"printTemplateSnapshotId",template.path("snapshotId").asText(),"variantsPerPrint",variants,
                "sources",List.of(Map.of("captureId",capture.path("id").asText(),"version",capture.path("version").asLong(),"imageId",capture.path("images").get(0).path("id").asText(),"cleanupAreas",List.of(Map.of("x",0.25,"y",0.25,"width",0.5,"height",0.5))))),200);
        // This fixture covers recovery of the persisted, model-rendered workflow. Newly saved plans
        // default to local photo composition, whose own integration tests cover the reduced call count.
        db.update("UPDATE commerce_batch_creative_plans SET plan_json=JSON_REMOVE(plan_json,'$.renderPolicy','$.outputPolicy') WHERE batch_id=?",id);
        var run=call("/api/v1/commerce-batches/"+id+"/execution",Map.of("confirmed",true,"creativePlanVersion",1,"maxModelCalls",calls),200);
        return new CreativeFixture(id,run.path("id").asText(),Base64.getEncoder().encodeToString(png));
    }
    void verifyCreativeModelCalls(int variants){
        verify(gateway,times(1+variants)).generateTransparentArtwork(anyString(),anyString(),any(),anyList());
        verify(gateway,times(1)).generateArtwork(anyString(),anyString(),any(),anyList());
        verify(gateway,times(variants)).generateProductPreview(anyString(),anyString(),any(),anyList());
    }
    String state(CreativeFixture f){return db.queryForObject("SELECT status FROM commerce_batch_executions WHERE id=?",String.class,f.run());}
    void drain(CreativeFixture f)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(batchWorker.hasInFlight(f.run())||Set.of("QUEUED","RUNNING","PAUSING").contains(state(f))){
            assertTrue(System.nanoTime()<deadline,"Background batch did not settle: "+state(f));
            batchWorker.poll();Thread.sleep(25);
        }
    }

    @Test void creativeOnlyPipelineRunsEveryStageAndNeverPublishes()throws Exception {
        var f=creativeFixture(2,6);drain(f);
        assertEquals("COMPLETED",state(f));
        assertEquals("REVIEW",db.queryForObject("SELECT stage FROM commerce_batch_executions WHERE id=?",String.class,f.run()));
        var artifacts=call("/api/v1/commerce-batches/"+f.batch()+"/execution/artifacts",null,200);
        assertEquals(6,artifacts.size());
        assertEquals(2,java.util.stream.StreamSupport.stream(artifacts.spliterator(),false).filter(a->a.path("kind").asText().equals("MOCKUP")&&!a.path("check").isNull()&&!a.path("check").path("productionReady").asBoolean()).count());
        verifyCreativeModelCalls(2);
        verify(downloader,times(1)).download("https://cdn.example.com/batch-source.png");
        assertEquals(6,call("/api/v1/commerce-batches/"+f.batch()+"/execution/limits",null,200).path("reservedCalls").asInt());
        batchWorker.poll();verifyCreativeModelCalls(2);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_execution_events WHERE execution_id=? AND stage='PUBLISH'",Integer.class,f.run()));
        var image=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+artifacts.get(0).path("imageUrl").asText())).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,image.statusCode());assertArrayEquals(artworkPng(),image.body());
    }

    @Test void localRepairsAreImmutableIdempotentAndDoNotCallModel()throws Exception {
        var f=creativeFixture(1,4);drain(f);assertEquals("COMPLETED",state(f));
        String path="/api/v1/commerce-batches/"+f.batch()+"/execution/artifacts";
        var original=call(path,null,200);String capture=original.get(0).path("captureId").asText();
        var request=Map.of("captureId",capture,"cleanupAreas",List.of(Map.of("x",0.25,"y",0.25,"width",0.5,"height",0.5)));
        assertEquals(2,call(path+"/repairs",request,200).asInt());call(path+"/repairs",request,200);
        var repaired=call(path,null,200);assertEquals(original.size()+2,repaired.size());
        for(int i=0;i<original.size();i++)assertEquals(original.get(i),repaired.get(i));
        var fixed=java.util.stream.StreamSupport.stream(repaired.spliterator(),false).filter(r->r.path("kind").asText().equals("MOCKUP_REPAIRED")).findFirst().orElseThrow();
        assertEquals(0,fixed.path("check").path("outsideChangedPixels").asLong());assertFalse(fixed.path("check").path("productionReady").asBoolean());
        var download=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+fixed.path("imageUrl").asText())).build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,download.statusCode());assertEquals(1024,ImageIO.read(new java.io.ByteArrayInputStream(download.body())).getWidth());
        call(path+"/repairs",Map.of("captureId",capture,"cleanupAreas",List.of(Map.of("x",-0.1,"y",0.25,"width",0.5,"height",0.5))),409);
        call(path+"/repairs",Map.of("captureId","unrelated","cleanupAreas",List.of()),409);
        var unauth=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path+"/repairs")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,unauth.statusCode());
        verifyCreativeModelCalls(1);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_execution_events WHERE execution_id=? AND stage='PUBLISH'",Integer.class,f.run()));
    }

    @Test void zeroLimitAndChangedModelNeverSubmit()throws Exception {
        var f=creativeFixture(1,0);drain(f);assertEquals("WAITING_INPUT",state(f));verifyNoInteractions(gateway);
        when(models.imageConnection(anyString())).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection("other-model","https://different.invalid","changed","IMAGES","CUSTOM","test-only")));
        call("/api/v1/commerce-batches/"+f.batch()+"/execution/resume",Map.of(),200);drain(f);
        verifyNoInteractions(gateway);assertEquals(0,db.queryForObject("SELECT reserved_calls FROM commerce_batch_model_limits WHERE execution_id=?",Integer.class,f.run()));
    }

    @Test void uncertainSubmissionCannotBeReplayedOrRecoveredBlindly()throws Exception {
        var f=creativeFixture(1,4);
        when(gateway.generateTransparentArtwork(anyString(),anyString(),any(),anyList())).thenThrow(new IllegalStateException("connection closed"));
        drain(f);assertEquals("UNKNOWN",state(f));
        call("/api/v1/commerce-batches/"+f.batch()+"/execution/recover",Map.of(),409);
        call("/api/v1/commerce-batches/"+f.batch()+"/execution/resume",Map.of(),409);
        batchWorker.poll();verify(gateway,times(1)).generateTransparentArtwork(anyString(),anyString(),any(),anyList());
    }

    @Test void archivedLateResponseCanResumeAfterExpiredLeaseWithoutRepeatingModel()throws Exception {
        var f=creativeFixture(1,4);var response=new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(artworkPng()),"image/png","model","edit",""),null);
        when(gateway.generateTransparentArtwork(anyString(),anyString(),any(),anyList())).thenAnswer(invocation->{db.update("UPDATE commerce_batch_executions SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",f.run());return response;}).thenReturn(response);
        drain(f);assertEquals("UNKNOWN",state(f));
        assertEquals("READY",db.queryForObject("SELECT status FROM commerce_batch_artwork_jobs WHERE execution_id=?",String.class,f.run()));
        call("/api/v1/commerce-batches/"+f.batch()+"/execution/recover",Map.of(),200);drain(f);
        assertEquals("COMPLETED",state(f));verifyCreativeModelCalls(1);
    }

    @Test void pauseDuringSubmissionKeepsResultAndResumesRemainingJobs()throws Exception {
        var f=creativeFixture(1,4);var response=new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(artworkPng()),"image/png","model","edit",""),null);
        when(gateway.generateTransparentArtwork(anyString(),anyString(),any(),anyList())).thenAnswer(invocation->{call("/api/v1/commerce-batches/"+f.batch()+"/execution/pause",Map.of(),200);return response;}).thenReturn(response);
        drain(f);assertEquals("PAUSED",state(f));verify(gateway,times(1)).generateTransparentArtwork(anyString(),anyString(),any(),anyList());
        call("/api/v1/commerce-batches/"+f.batch()+"/execution/resume",Map.of(),200);drain(f);assertEquals("COMPLETED",state(f));
        verifyCreativeModelCalls(1);
    }

    @Test void collectingSavedResponseDoesNotResubmitGeneration()throws Exception {
        var f=creativeFixture(1,4);
        var pending=new CommerceDesignImageGateway.Response(null,new CommerceDesignImageGateway.PendingResult("https://cdn.example.com/pending.png","image/png","model","edit",""));
        var done=new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(artworkPng()),"image/png","model","edit",""),null);
        when(gateway.generateTransparentArtwork(anyString(),anyString(),any(),anyList())).thenReturn(pending).thenReturn(done);
        when(downloader.download("https://cdn.example.com/pending.png")).thenThrow(new IllegalStateException("temporary download failure")).thenReturn(new MarketplaceImageDownloader.ImageBinary(artworkPng(),"image/png",".png"));
        drain(f);assertEquals("WAITING_INPUT",state(f));
        assertEquals("RESULT_RECEIVED",db.queryForObject("SELECT status FROM commerce_batch_artwork_jobs WHERE execution_id=?",String.class,f.run()));
        call("/api/v1/commerce-batches/"+f.batch()+"/execution/resume",Map.of(),200);drain(f);assertEquals("COMPLETED",state(f));
        verifyCreativeModelCalls(1);
    }
    @Test void explicitlyArchivedSourceIsImmutableAndDoesNotNeedRemoteDownload()throws Exception {
        var f=creativeFixture(1,4);
        var identity=call("/api/v1/auth/me",null,200);
        var user=new com.yingdao.aicommerce.auth.PlatformPrincipal(identity.path("userId").asText(),identity.path("organizationId").asText(),"heyi","u","u","",true,List.of(),List.of("product:edit","product:view","product:publish"));
        var plan=call("/api/v1/commerce-batches/"+f.batch()+"/creative-plan",null,200).path("plan").path("sources").get(0);
        String capture=plan.path("captureId").asText(),image=plan.path("imageId").asText();long version=plan.path("version").asLong();
        String boundary="boundary"+UUID.randomUUID();var multipart=new ByteArrayOutputStream();
        multipart.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"original.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        multipart.write(artworkPng());multipart.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var upload=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/product-captures/"+capture+"/images/"+image+"/source-file?version="+version))
                .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build();
        assertEquals(401,HttpClient.newHttpClient().send(upload,HttpResponse.BodyHandlers.ofString()).statusCode());
        var uploaded=http.send(upload,HttpResponse.BodyHandlers.ofString());assertEquals(200,uploaded.statusCode(),uploaded.body());
        String saved=json.readTree(uploaded.body()).path("data").path("contentSha256").asText();
        assertEquals(saved,sourceArchives.upload(capture,image,version,artworkPng(),user).contentSha256());
        var other=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(96,96,BufferedImage.TYPE_INT_RGB),"png",other);
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->sourceArchives.upload(capture,image,version,other.toByteArray(),user));
        when(downloader.download(anyString())).thenThrow(new IllegalStateException("remote inaccessible"));
        drain(f);assertEquals("COMPLETED",state(f));verifyNoInteractions(downloader);
    }
}
