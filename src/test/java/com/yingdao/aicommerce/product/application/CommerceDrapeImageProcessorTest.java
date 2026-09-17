package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CommerceDrapeImageProcessorTest {
    record Client(CommerceDrapeImageProcessor processor,MockRestServiceServer server){}
    Client client(){var builder=RestClient.builder().baseUrl("http://local-only");var server=MockRestServiceServer.bindTo(builder).build();var p=new CommerceDrapeImageProcessor("http://local-only","x".repeat(24));ReflectionTestUtils.setField(p,"client",builder.build());return new Client(p,server);}
    void responds(Client c,String path,Object body){c.server.expect(requestTo("http://local-only"+path)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess(new tools.jackson.databind.ObjectMapper().writeValueAsString(body),MediaType.APPLICATION_JSON));}
    Map<String,Object> render(CommerceDrapeReuseWorkflowTest.Harness h){var r=new LinkedHashMap<String,Object>();r.put("passed",true);r.put("sourceSha256",CommerceImageArchive.sha(h.master));r.put("blankSha256",CommerceImageArchive.sha(h.blank));r.put("alphaSha256",CommerceImageArchive.sha(h.alpha));r.put("artworkSha256",h.inks.get(1).substring(65));r.put("outputSha256",CommerceImageArchive.sha(h.output));r.put("originalColor",false);r.put("targetRgb","#abcdef");r.put("outsideGarmentChangedPixels",0);return new LinkedHashMap<>(Map.of("algorithm",CommerceDrapeImageProcessor.VERSION,"data",CommerceDrapeReuseWorkflowTest.Harness.encoded(h.output),"report",r));}
    @Test void prepareAcceptsOnlyMatchingFixedInputsAndPassedLocalCheck()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var c=client();responds(c,"/drape-prepare",h.prepared());
        assertEquals(CommerceDrapeImageProcessor.VERSION,c.processor.prepare(h.master,h.guide,h.uv,h.assets.get(h.inks.get(1)),List.of("front")).get("algorithm"));c.server.verify();
        for(String field:List.of("algorithm","sourceSha256","guideSha256","uvSha256","artworkSha256")){
            var changed=new LinkedHashMap<>(h.prepared());changed.put(field,"different");var bad=client();responds(bad,"/drape-prepare",changed);
            assertThrows(IllegalArgumentException.class,()->bad.processor.prepare(h.master,h.guide,h.uv,h.assets.get(h.inks.get(1)),List.of("front")),field);bad.server.verify();
        }
    }
    @Test void prepareRejectsMismatchedMaskOrBlankAndDoesNotRetryTheLocalRequest()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();
        for(String field:List.of("sourceSha256","guideSha256","uvSha256","artworkSha256","blankSha256","alphaSha256")){
            var changed=new LinkedHashMap<>(h.prepared());var mapping=new LinkedHashMap<>((Map<String,Object>)changed.get("mapping"));mapping.put(field,"wrong");changed.put("mapping",mapping);
            var c=client();responds(c,"/drape-prepare",changed);assertThrows(IllegalArgumentException.class,()->c.processor.prepare(h.master,h.guide,h.uv,h.assets.get(h.inks.get(1)),List.of("front")),field);c.server.verify();
        }
    }
    @Test void renderChecksEveryInputOutputAndTargetColorBinding()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var valid=client();responds(valid,"/drape-render",render(h));
        assertNotNull(valid.processor.render(h.master,h.blank,h.alpha,Map.of(),h.assets.get(h.inks.get(1)),"#ABCDEF",false));valid.server.verify();
        for(String field:List.of("sourceSha256","blankSha256","alphaSha256","artworkSha256","outputSha256","targetRgb","originalColor")){
            var changed=render(h);var check=new LinkedHashMap<>((Map<String,Object>)changed.get("report"));check.put(field,"wrong");changed.put("report",check);
            var c=client();responds(c,"/drape-render",changed);assertThrows(IllegalArgumentException.class,()->c.processor.render(h.master,h.blank,h.alpha,Map.of(),h.assets.get(h.inks.get(1)),"#ABCDEF",false),field);c.server.verify();
        }
    }
    @Test void falsePassedCannotBecomeAUsableImageEvenWithValidHashes()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var body=render(h);var report=new LinkedHashMap<>((Map<String,Object>)body.get("report"));report.put("passed",false);body.put("report",report);
        var c=client();responds(c,"/drape-render",body);assertThrows(IllegalArgumentException.class,()->c.processor.render(h.master,h.blank,h.alpha,Map.of(),h.assets.get(h.inks.get(1)),"#abcdef",false));c.server.verify();
    }
    @Test void structuredLocalFailureKeepsCodeAndStatesThatOnlyLocalWorkCanRetry()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var c=client();c.server.expect(requestTo("http://local-only/drape-render")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"TRACKING_LOW\"}"));
        var failure=assertThrows(IllegalArgumentException.class,()->c.processor.render(h.master,h.blank,h.alpha,Map.of(),h.assets.get(h.inks.get(1)),"#abcdef",false));
        assertTrue(failure.getMessage().contains("TRACKING_LOW"));assertTrue(failure.getMessage().contains("不追加生图"));c.server.verify();
    }
    @Test void localTrackingAndGeometryErrorsExplainWhyItStoppedWithoutRetrying()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();
        var reasons=Map.of(
                "FEATURE_TRACKING_FAILED","印花与参考图未能可靠匹配",
                "GUIDE_ARTWORK_MISMATCH","位置参考不一致",
                "MAPPING_CHECKSUM_INVALID","定位数据未通过完整性检查",
                "GARMENT_CROPPED","衣服区域触及图片边缘",
                "WHITE_MASTER_REQUIRED","布料不符合中性白色条件",
                "FEATURE_TRACKING_UNSTABLE","定位结果不一致",
                "GARMENT_MASK_UNCERTAIN","衣服轮廓分离不可靠",
                "PRINT_OUTSIDE_GARMENT","越过可靠布料区域",
                "PRINT_RESIDUE_DETECTED","原印花尚未清理干净",
                "LOCAL_TIME_LIMIT","本地印花定位处理超时");
        for(var reason:reasons.entrySet()){
            var c=client();c.server.expect(requestTo("http://local-only/drape-prepare"))
                    .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\""+reason.getKey()+"\",\"message\":\"untrusted provider payload\"}"));
            var failure=assertThrows(IllegalArgumentException.class,()->c.processor.prepare(h.master,h.guide,h.uv,h.assets.get(h.inks.get(1)),List.of("front")));
            assertTrue(failure.getMessage().contains(reason.getValue()),failure.getMessage());
            assertTrue(failure.getMessage().contains("保留母图与印花"));assertTrue(failure.getMessage().contains("恢复仅重做本地处理，不追加生图"));
            assertFalse(failure.getMessage().contains(reason.getKey()));assertFalse(failure.getMessage().contains("untrusted provider payload"));
            c.server.verify();
        }
    }
    @Test void malformedErrorPayloadCannotLeakResponseTextIntoStopReason()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();
        for(String response:List.of("not-json secret-provider-detail","{\"code\":\"<script>secret-provider-detail</script>\",\"message\":\"secret-provider-detail\"}")){
            var c=client();c.server.expect(requestTo("http://local-only/drape-prepare"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON).body(response));
            var failure=assertThrows(IllegalArgumentException.class,()->c.processor.prepare(h.master,h.guide,h.uv,h.assets.get(h.inks.get(1)),List.of("front")));
            assertTrue(failure.getMessage().startsWith("本地搭放检查未通过"));assertFalse(failure.getMessage().contains("secret-provider-detail"));
            assertTrue(failure.getMessage().contains("不追加生图"));c.server.verify();
        }
    }
    @Test void unavailableServiceAndMalformedImagesFailClosed()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var p=new CommerceDrapeImageProcessor("http://unused","");
        assertThrows(IllegalArgumentException.class,()->p.prepare(h.master,h.guide,h.uv,h.assets.get(h.inks.get(1)),List.of("front")));
        for(Object value:List.of("not-base64",Base64.getEncoder().encodeToString(new byte[]{1,2,3}),17))assertThrows(IllegalArgumentException.class,()->CommerceDrapeImageProcessor.decoded(value));
    }
}
