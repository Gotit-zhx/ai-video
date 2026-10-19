package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class CommerceDesignImageGatewayTest {
    @Test void springCanConstructGatewayWithoutAnAutoConfiguredRestClientBuilder(){
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withPropertyValues("app.pi-runtime-url=http://127.0.0.1:1","app.pi-runtime-token=test-only")
            .withUserConfiguration(CommerceDesignImageGateway.class)
            .run(context->{assertNull(context.getStartupFailure());assertNotNull(context.getBean(CommerceDesignImageGateway.class));});
    }
    final ModelConfigService.RuntimeModelConnection model=new ModelConfigService.RuntimeModelConnection("model","https://model.example","image-model","IMAGES","CUSTOM","test-key");
    final ModelConfigService.RuntimeModelConnection img2=new ModelConfigService.RuntimeModelConnection("img2","https://model.example","gpt-image-2","OPENAI_COMPATIBLE","CUSTOM","test-key");
    final CommerceDesignImageGateway gateway=new CommerceDesignImageGateway(RestClient.builder(),"http://127.0.0.1:1","test-token");
    CommerceDesignDraft draft(String mode){return new CommerceDesignDraft("id","org","capture",1,"request","hash",3,mode,"背景换成浅灰","R","[]","DRAFT","u",Instant.now());}
    @Test void stockPromptPreservesGarmentTopologyAndDoesNotInventBackPrint(){String p=CommerceDesignImageGateway.prompt(draft("STOCK_MARKETING"));assertTrue(p.contains("斜肩结构"));assertTrue(p.contains("不得把正面图案复制到背面"));assertTrue(p.contains("禁止九宫格"));assertTrue(p.contains("不是要求输出多张图片"));}
    @Test void podCandidateIsNotProductionApproval(){assertTrue(CommerceDesignImageGateway.prompt(draft("POD_DESIGN")).contains("不把候选图当作可生产文件"));}
    @Test void refusesSilentReferenceTruncation(){var ref=new CommerceDesignImageGateway.Reference("image/png","aGVsbG8=");assertThrows(RuntimeException.class,()->gateway.generate(draft("STOCK_MARKETING"),Collections.nCopies(5,ref),"attempt_1",model));}
    @Test void missingReferenceCannotBecomeTextOnlyGeneration(){assertThrows(RuntimeException.class,()->gateway.generate(draft("STOCK_MARKETING"),List.of(),"attempt_1",model));}
    @Test void missingConfirmedModelBlocksNetworkCall(){assertThrows(RuntimeException.class,()->gateway.generate(draft("STOCK_MARKETING"),List.of(new CommerceDesignImageGateway.Reference("image/png","aGVsbG8=")),"attempt_1",null));}
    @Test void submitsOneSquareImageAndPreservesPendingDownloadWithoutRetry(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new CommerceDesignImageGateway(builder,"http://runtime","internal-test");
        server.expect(requestTo("http://runtime/v1/images/generate")).andExpect(header("X-Internal-Token","internal-test"))
            .andExpect(jsonPath("$.model.modelId").value("image-model")).andExpect(jsonPath("$.model.baseUrl").value("https://model.example"))
            .andExpect(jsonPath("$.size").value("1024x1024")).andExpect(jsonPath("$.references.length()").value(1))
            .andExpect(jsonPath("$.progressRequestId").value("attempt_1"))
            .andRespond(withSuccess("{\"result\":{\"url\":\"https://result.example/image.png\",\"mimeType\":\"image/png\"}}",MediaType.APPLICATION_JSON));
        var result=adapter.generate(draft("STOCK_MARKETING"),List.of(new CommerceDesignImageGateway.Reference("image/png","aGVsbG8=")),"attempt_1",model);
        assertNull(result.artifact());assertEquals("https://result.example/image.png",result.result().url());server.verify();
    }
    @Test void forwardsConfiguredBackupWithItsOwnKeyAndPreservesActualResultModel(){
        var backup=new ModelConfigService.RuntimeModelConnection("backup","https://model.example","grok-imagine-image-2.0","OPENAI_COMPATIBLE","AUTO","backup-test-key");
        var primary=new ModelConfigService.RuntimeModelConnection("primary","https://model.example","gpt-image-2","OPENAI_COMPATIBLE","AUTO","primary-test-key",List.of(backup));
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new CommerceDesignImageGateway(builder,"http://runtime","internal-test");
        server.expect(requestTo("http://runtime/v1/images/generate"))
            .andExpect(jsonPath("$.model.modelId").value("gpt-image-2"))
            .andExpect(jsonPath("$.model.apiKey").value("primary-test-key"))
            .andExpect(jsonPath("$.model.copyrightFallbackModels[0].modelId").value("grok-imagine-image-2.0"))
            .andExpect(jsonPath("$.model.copyrightFallbackModels[0].apiKey").value("backup-test-key"))
            .andExpect(jsonPath("$.prompt").value("保留刺绣，清理叠加水印"))
            .andExpect(jsonPath("$.references.length()").value(1))
            .andRespond(withSuccess("{\"result\":{\"url\":\"https://result.example/grok.png\",\"modelId\":\"grok-imagine-image-2.0\"}}",MediaType.APPLICATION_JSON));
        var result=adapter.generateArtwork("保留刺绣，清理叠加水印","attempt_2",primary,List.of(new CommerceDesignImageGateway.Reference("image/png","aGVsbG8=")));
        assertEquals("grok-imagine-image-2.0",result.result().modelId());server.verify();
    }
    @Test void transparentArtworkDeclaresPngAlphaIntentAndRetainsPaidOpaqueResponse(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new CommerceDesignImageGateway(builder,"http://runtime","internal-test");
        server.expect(requestTo("http://runtime/v1/images/generate"))
            .andExpect(jsonPath("$.model.modelId").value("gpt-image-2"))
            .andExpect(jsonPath("$.model.copyrightFallbackModels.length()").value(0))
            .andExpect(jsonPath("$.background").value("transparent"))
            .andExpect(jsonPath("$.outputFormat").value("png"))
            .andExpect(jsonPath("$.prompt").value("独立印花，真实alpha透明，无底板"))
            .andExpect(jsonPath("$.progressRequestId").value("print_alpha_1"))
            .andRespond(withSuccess("{\"artifact\":{\"dataBase64\":\"aGVsbG8=\",\"mimeType\":\"image/png\",\"modelId\":\"grok-imagine-image-2.0\"}}",MediaType.APPLICATION_JSON));
        // A provider may return invalid bytes despite the pinned IMG2 request. Persist it before quality checks;
        // rejecting here would incorrectly lose the completed result and mark the paid call UNKNOWN.
        var result=adapter.generateTransparentArtwork("独立印花，真实alpha透明，无底板","print_alpha_1",img2,List.of());
        assertEquals("aGVsbG8=",result.artifact().dataBase64());
        assertFalse(CommerceArtworkTransparency.inspect(Base64.getDecoder().decode(result.artifact().dataBase64())).verified());
        server.verify();
    }
    @Test void transparentArtworkKeepsUrlPendingUntilBytesCanBeVerified(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new CommerceDesignImageGateway(builder,"http://runtime","internal-test");
        server.expect(requestTo("http://runtime/v1/images/generate"))
            .andExpect(jsonPath("$.model.modelId").value("gpt-image-2"))
            .andExpect(jsonPath("$.model.copyrightFallbackModels.length()").value(0))
            .andExpect(jsonPath("$.background").value("transparent"))
            .andExpect(jsonPath("$.outputFormat").value("png"))
            .andRespond(withSuccess("{\"result\":{\"url\":\"https://result.example/opaque-despite-name.png\",\"mimeType\":\"image/png\"}}",MediaType.APPLICATION_JSON));
        var result=adapter.generateTransparentArtwork("独立印花","print_alpha_url",img2,List.of());
        assertNull(result.artifact());
        assertEquals("https://result.example/opaque-despite-name.png",result.result().url());
        server.verify();
    }
    @Test void transparentArtworkCannotBeSubmittedThroughAnotherModelOrFallback(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new CommerceDesignImageGateway(builder,"http://runtime","internal-test");
        var grok=new ModelConfigService.RuntimeModelConnection("grok","https://model.example","grok-imagine-image-2.0","OPENAI_COMPATIBLE","CUSTOM","unit-only");
        var withBackup=new ModelConfigService.RuntimeModelConnection(img2.id(),img2.baseUrl(),img2.modelId(),img2.apiFormat(),img2.provider(),img2.apiKey(),List.of(grok));
        for(var connection:List.of(model,grok,withBackup)){
            var failure=assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->adapter.generateTransparentArtwork("透明印花","img2_only_check",connection,List.of()));
            assertTrue(failure.getMessage().contains("固定使用已配置的 IMG2.0"));
        }
        server.verify();
    }
    @Test void ordinaryGarmentReferencesSamplesAndProductPhotosDoNotRequestTransparency(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new CommerceDesignImageGateway(builder,"http://runtime","internal-test");
        for(int i=0;i<3;i++)server.expect(requestTo("http://runtime/v1/images/generate"))
            .andExpect(jsonPath("$.background").doesNotExist())
            .andExpect(jsonPath("$.outputFormat").doesNotExist())
            .andRespond(withSuccess("{\"result\":{\"url\":\"https://result.example/garment.jpg\"}}",MediaType.APPLICATION_JSON));
        adapter.generateArtwork("白色摄影背景上的服装","ordinary_garment",model,List.of());
        adapter.generateSample("普通商品示例","ordinary_sample",model,List.of());
        adapter.generateProductPreview("白底商品效果图","ordinary_preview",model,List.of());
        server.verify();
    }
    @Test void detailGridHasItsOwnTwoReferenceHighResolutionContract(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new CommerceDesignImageGateway(builder,"http://runtime","internal-test");
        var refs=List.of(new CommerceDesignImageGateway.Reference("image/png","cHJvZHVjdA=="),new CommerceDesignImageGateway.Reference("image/jpeg","Z3JpZA=="));
        server.expect(requestTo("http://runtime/v1/images/generate"))
            .andExpect(header("X-Internal-Token","internal-test"))
            .andExpect(jsonPath("$.size").value("2048x2048"))
            .andExpect(jsonPath("$.quality").value("medium"))
            .andExpect(jsonPath("$.inputFidelity").value("high"))
            .andExpect(jsonPath("$.productMockup").value(true))
            .andExpect(jsonPath("$.detailGrid").value(true))
            .andExpect(jsonPath("$.progressRequestId").value("detail_grid_1"))
            .andExpect(jsonPath("$.references.length()").value(2))
            .andExpect(jsonPath("$.references[0].dataBase64").value(refs.getFirst().dataBase64()))
            .andExpect(jsonPath("$.references[1].dataBase64").value(refs.get(1).dataBase64()))
            .andRespond(withSuccess("{\"result\":{\"url\":\"https://result.example/grid.png\"}}",MediaType.APPLICATION_JSON));
        assertEquals("https://result.example/grid.png",adapter.generateProductDetailGrid("固定四格","detail_grid_1",model,refs).result().url());
        for(int count:List.of(0,1,3,5))assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
            ()->adapter.generateProductDetailGrid("invalid","detail_grid_2",model,Collections.nCopies(count,refs.getFirst())));
        server.verify();
    }
    @Test void mainImagePosterPreservesOrderedProductAndPosterReferencesAtHighQuality(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new CommerceDesignImageGateway(builder,"http://runtime","internal-test");
        var refs=List.of(new CommerceDesignImageGateway.Reference("image/png","cHJvZHVjdA=="),new CommerceDesignImageGateway.Reference("image/jpeg","cG9zdGVy"));
        server.expect(requestTo("http://runtime/v1/images/generate"))
            .andExpect(jsonPath("$.size").value("1024x1536"))
            .andExpect(jsonPath("$.quality").value("high"))
            .andExpect(jsonPath("$.inputFidelity").value("high"))
            .andExpect(jsonPath("$.progressRequestId").value("main_poster_1"))
            .andExpect(jsonPath("$.references.length()").value(2))
            .andExpect(jsonPath("$.references[0].dataBase64").value(refs.getFirst().dataBase64()))
            .andExpect(jsonPath("$.references[1].dataBase64").value(refs.get(1).dataBase64()))
            .andRespond(withSuccess("{\"result\":{\"url\":\"https://result.example/poster.png\"}}",MediaType.APPLICATION_JSON));
        assertEquals("https://result.example/poster.png",adapter.generateMainImagePoster("Poster","main_poster_1",model,refs,"1024x1536").result().url());
        for(int count:List.of(0,1,3))assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
            ()->adapter.generateMainImagePoster("Poster","main_poster_2",model,Collections.nCopies(count,refs.getFirst()),"1024x1024"));
        server.verify();
    }
}
