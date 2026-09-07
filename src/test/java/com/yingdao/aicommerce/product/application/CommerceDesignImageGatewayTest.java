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
}
