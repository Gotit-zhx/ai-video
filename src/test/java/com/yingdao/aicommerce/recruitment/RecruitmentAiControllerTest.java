package com.yingdao.aicommerce.recruitment;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.workflow.application.RedisConcurrencyGate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecruitmentAiControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final RedisConcurrencyGate gate = mock(RedisConcurrencyGate.class);
    private PlatformPrincipal user(String phone) {
        return new PlatformPrincipal("admin","org-1","heyi",phone,"管理员","",true,List.of("ADMIN"),List.of());
    }
    private Map<String,Object> demand() {
        return Map.of("position","电商运营","department","电商项目组","responsibilities","负责店铺经营与复盘",
                "requirements","本科及三年电商经验","education","本科","years",3,"headcount",2);
    }
    private Map<String,Object> generated() {
        return Map.of("portrait",Map.of("summary","负责增长的电商运营","mustHave",List.of("三年经验"),
                        "niceToHave",List.of(),"experience",List.of("电商经历"),"evaluationFocus",List.of("实际增长指标")),
                "competencies",Collections.nCopies(4,Map.of("name","复盘","description","分析业务数据","evidence","复盘报告","level","独立完成")),
                "interviewQuestions",Collections.nCopies(6,Map.of("competency","复盘","question","如何分析转化下降？",
                        "followUps",List.of("如何验证？"),"evaluationCriteria","1分无依据，3分有数据，5分能验证闭环",
                        "strongAnswerSignals",List.of("实验验证"))));
    }
    @Test void forbidsUnrelatedUsersBeforeLookingUpModels() {
        var controller=new RecruitmentAiController(models,gate,json);
        assertThrows(BusinessException.class,()->controller.generate(user("13900000000"),demand()));
        verifyNoInteractions(models,gate);
    }
    @Test void requiresConfiguredDriverAndDoesNotSilentlyUseOtherModels() {
        when(models.driverConnection("org-1")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class,()->new RecruitmentAiController(models,gate,json).generate(user("13800000000"),demand()));
        verifyNoInteractions(gate);
    }
    @Test void validatesAllThreeStructuredSectionsAndDropsUnknownFields() {
        Map<String,Object> raw=new HashMap<>(generated());raw.put("apiKey","never-return");
        Map<String,Object> clean=RecruitmentAiController.validateResult(raw);
        assertFalse(clean.containsKey("apiKey"));
        assertEquals(Set.of("portrait","competencies","interviewQuestions"),clean.keySet());
        raw.put("interviewQuestions",List.of());
        assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateResult(raw));
    }
    @Test void callsConfiguredTenantDriverExactlyOnceWithoutToolsAndClosesPermit() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();var payload=new AtomicReference<Map>();
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();
            payload.set(json.readValue(exchange.getRequestBody().readAllBytes(),Map.class));
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop",
                    "message",Map.of("content",json.writeValueAsString(generated()))))));
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try{
            when(models.driverConnection("org-1")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "driver","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","gpt-5.5","OPENAI_COMPATIBLE","test","secret")));
            var permit=mock(RedisConcurrencyGate.Permit.class);when(gate.tryAcquireModelCall()).thenReturn(permit);
            Object reply=new RecruitmentAiController(models,gate,json).generate(user("13800000000"),demand());
            assertEquals(1,calls.get());assertEquals("gpt-5.5",payload.get().get("model"));
            assertFalse(payload.get().containsKey("tools"));assertFalse((Boolean)payload.get().get("stream"));
            assertEquals(10000,payload.get().get("max_completion_tokens"));
            String serialized=json.writeValueAsString(reply);
            assertTrue(serialized.contains("\"modelType\":\"DRIVER\""));assertFalse(serialized.contains("secret"));
            verify(permit).close();
        }finally{server.stop(0);}
    }
    @Test void providerFailureIsRedactedAndNeverAutomaticallyRetried() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();
        server.createContext("/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] text="sensitive-provider-key".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502,text.length);exchange.getResponseBody().write(text);exchange.close();
        });server.start();
        try{
            when(models.driverConnection("org-1")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "driver","http://127.0.0.1:"+server.getAddress().getPort(),"model","OPENAI_COMPATIBLE","test","secret")));
            when(gate.tryAcquireModelCall()).thenReturn(mock(RedisConcurrencyGate.Permit.class));
            var failure=assertThrows(BusinessException.class,()->new RecruitmentAiController(models,gate,json).generate(user("13800000000"),demand()));
            assertFalse(failure.getMessage().contains("sensitive"));assertEquals(1,calls.get());
        }finally{server.stop(0);}
    }
}
