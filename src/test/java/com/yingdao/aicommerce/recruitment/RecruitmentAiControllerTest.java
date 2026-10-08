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
    private List<Map<String,Object>> sourcingConditions() {
        return List.of(Map.of("id","k1","text","相关或可迁移工作经历","weight",50,"required",false),
            Map.of("id","k2","text","文件整理或办公事务","weight",30,"required",false),
            Map.of("id","k3","text","商务接待或协调","weight",20,"required",false));
    }
    private Map<String,Object> conditionCandidate(String a,String b,String c) {
        return Map.of("id","c1","summary","有前台接待和对账经验，文件工作需核实","checks",List.of(
            Map.of("criterionId","k1","status",a,"evidence","担任前台并处理收银对账"),
            Map.of("criterionId","k2","status",b,"evidence","卡片未说明文件整理"),
            Map.of("criterionId","k3","status",c,"evidence","负责顾客接待引导")));
    }
    @Test void conditionScoringSeparatesMissingDetailsFromContradictoryEvidence() {
        var partial=RecruitmentAiController.validateConditions(conditionCandidate("matched","unknown","matched"),sourcingConditions());
        assertEquals(100d,partial.get("score"));
        assertEquals("matched",partial.get("matchState"));
        assertEquals(2,partial.get("matchedConditions"));assertEquals(1,partial.get("pendingConditions"));
        assertEquals(70d,partial.get("knownWeight"));assertEquals(100,partial.get("totalWeight"));
        var unknown=RecruitmentAiController.validateConditions(conditionCandidate("unknown","unknown","unknown"),sourcingConditions());
        assertEquals("needs_info",unknown.get("matchState"));assertEquals(0d,unknown.get("score"));
        var lower=RecruitmentAiController.validateConditions(conditionCandidate("unmatched","unknown","matched"),sourcingConditions());
        assertEquals("below_threshold",lower.get("matchState"));
        assertEquals(28.6,lower.get("score"));
    }
    @Test void requiredContradictionBlocksEvenAHighKnownScoreAndInvalidChecksFail() {
        var conditions=List.<Map<String,Object>>of(
            Map.of("id","k1","text","相关工作经历","weight",90,"required",false),
            Map.of("id","k2","text","有实际驾驶经验","weight",5,"required",true),
            Map.of("id","k3","text","接待经验","weight",5,"required",false));
        var result=RecruitmentAiController.validateConditions(conditionCandidate("matched","unmatched","matched"),conditions);
        assertEquals(95d,result.get("score"));assertEquals("required_mismatch",result.get("matchState"));
        var wrong=new HashMap<>(conditionCandidate("matched","unknown","matched"));
        wrong.put("checks",List.of(Map.of("criterionId","k1","status","matched","evidence","经历"),
            Map.of("criterionId","k1","status","matched","evidence","重复"),
            Map.of("criterionId","k3","status","matched","evidence","经历")));
        assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateConditions(wrong,conditions));
        assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateConditions(conditionCandidate("yes","unknown","matched"),conditions));
    }
    @Test void conditionContractsKeepExactCandidateBindingAndDropModelMadeUpScores() {
        var input=Map.<String,Object>of("criteria",sourcingConditions(),"candidates",List.of(Map.of("id","c1")));
        var row=new HashMap<>(conditionCandidate("matched","unknown","matched"));row.put("score",1);
        var result=RecruitmentAiController.validateSourcing(Map.of("candidates",List.of(row),"limitations",List.of("仅依据卡片")),input);
        assertTrue(json.writeValueAsString(result).contains("\"score\":100.0"));
        row.put("id","invented");
        assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateSourcing(Map.of("candidates",List.of(row)),input));
    }
    @Test void sourcingRejectsUntrustedCandidateIdsDuplicatesAndNonfiniteScores() {
        var input=Map.<String,Object>of("candidates",List.of(Map.of("id","a"),Map.of("id","b")));
        var a=Map.<String,Object>of("id","a","score",75,"summary","经历相关","evidence",List.of("开发记录"),"gaps",List.of("需核实"));
        var b=new HashMap<>(a);b.put("id","b");
        var raw=new HashMap<String,Object>();raw.put("candidates",List.of(a,b));raw.put("limitations",List.of("仅供参考"));
        assertEquals(true,RecruitmentAiController.validateSourcing(raw,input).get("referenceOnly"));
        b.put("id","invented");assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateSourcing(raw,input));
        b.put("id","a");assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateSourcing(raw,input));
        b.put("id","b");b.put("score",Double.NaN);assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateSourcing(raw,input));
    }
    @Test void sourcingRequiresAuthorizedUserAndBoundedReferenceInputsBeforeModelCall() {
        var controller=new RecruitmentAiController(models,gate,json);
        assertThrows(BusinessException.class,()->controller.sourcingProfile(user("13900000000"),Map.of()));
        assertThrows(BusinessException.class,()->controller.sourcingProfile(user("13800000000"),
                Map.of("jobName","工程师","jobDescription","岗位职责","references",List.of())));
        assertThrows(BusinessException.class,()->controller.sourcingMatch(user("13800000000"),
                Map.of("jobDescription","岗位职责","profile","画像","candidates",Collections.nCopies(41,Map.of("id","a","evidence","资料")))));
        verifyNoInteractions(models,gate);
    }
    @Test void resumeMatchingIsReferenceOnlyAndDropsActionFields() {
        var raw=new HashMap<String,Object>();
        raw.put("score",75);raw.put("summary","具备相关工作经验，成果待面试核实");
        for(String field:List.of("strengths","gaps","questions","limitations"))raw.put(field,List.of("待核实"));
        raw.put("evidence",List.of(Map.of("requirement","运营经验","evidence","简历记录五年运营","assessment","经历相关")));
        raw.put("decision","reject");raw.put("sendMessage",true);
        var result=RecruitmentAiController.validateMatch(raw);
        assertEquals(true,result.get("referenceOnly"));assertFalse(result.containsKey("decision"));assertFalse(result.containsKey("sendMessage"));
        raw.put("score",Double.NaN);assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateMatch(raw));
        raw.put("score",75);raw.put("evidence",List.of());assertThrows(IllegalArgumentException.class,()->RecruitmentAiController.validateMatch(raw));
    }
    @Test void resumeMatchRequiresHumanProvidedInputsAndExistingHrAccess() {
        var controller=new RecruitmentAiController(models,gate,json);
        assertThrows(BusinessException.class,()->controller.resumeMatch(user("13900000000"),Map.of()));
        assertThrows(BusinessException.class,()->controller.resumeMatch(user("13800000000"),Map.of("resumeText","too short")));
        verifyNoInteractions(models,gate);
    }
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
        server.createContext("/v1/recruitment/analyze",exchange->{
            calls.incrementAndGet();
            assertEquals("internal-fixture",exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            payload.set(json.readValue(exchange.getRequestBody().readAllBytes(),Map.class));
            byte[] response=(json.writeValueAsString(Map.of("event","result","data",generated()))+"\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try{
            when(models.driverConnection("org-1")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "driver","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","gpt-5.5","OPENAI_COMPATIBLE","test","secret")));
            var permit=mock(RedisConcurrencyGate.Permit.class);when(gate.tryAcquireModelCall()).thenReturn(permit);
            var withReferences=new HashMap<String,Object>(demand());
            withReferences.put("referenceResumes",List.of("参考员工：负责工程交付与团队协调。"));
            Object reply=runtimeController(server).generate(user("13800000000"),withReferences);
            assertEquals(1,calls.get());assertEquals("gpt-5.5",((Map)payload.get().get("model")).get("modelId"));
            assertFalse(payload.get().containsKey("tools"));assertFalse(((Map)payload.get().get("model")).containsKey("fallbackModels"));
            assertFalse(payload.get().containsKey("max_tokens"));assertEquals("demand",payload.get().get("mode"));
            assertTrue(json.writeValueAsString(payload.get()).contains("参考员工：负责工程交付与团队协调"));
            String serialized=json.writeValueAsString(reply);
            assertTrue(serialized.contains("\"modelType\":\"DRIVER\""));assertFalse(serialized.contains("secret"));
            verify(permit).close();
        }finally{server.stop(0);}
    }
    @Test void streamedAnalysisCollectsTextAndValidatesAllThreeSections() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();
        String chunks=json.writeValueAsString(Map.of("event","progress","data",Map.of("phase","generating","receivedChars",200)))+"\n"
                +json.writeValueAsString(Map.of("event","result","data",generated()))+"\n";
        server.createContext("/v1/recruitment/analyze",exchange->{
            calls.incrementAndGet();
            Map payload=json.readValue(exchange.getRequestBody().readAllBytes(),Map.class);
            assertEquals("demand",payload.get("mode"));
            byte[] response=chunks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            when(models.driverConnection("org-1")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "driver","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","gpt-5.5","OPENAI_COMPATIBLE","test","secret")));
            when(gate.tryAcquireModelCall()).thenReturn(mock(RedisConcurrencyGate.Permit.class));
            var events=new ArrayList<Map<String,Object>>();
            runtimeController(server).streamDemand(user("13800000000"),demand(),events::add);
            String reply=json.writeValueAsString(events);
            assertEquals(1,calls.get());assertTrue(reply.contains("interviewQuestions"));
            assertFalse(reply.contains("internal reasoning"));assertTrue(reply.contains("负责增长"));
            assertTrue(reply.contains("\"receivedChars\":200"));assertEquals("result",events.getLast().get("event"));
        } finally { server.stop(0); }
    }
    private RecruitmentAiController runtimeController(HttpServer server) {
        return new RecruitmentAiController(models,gate,json,"http://127.0.0.1:"+server.getAddress().getPort(),"internal-fixture");
    }
    private String frame(Map<String,Object> choice) {
        return "data: "+json.writeValueAsString(Map.of("choices",List.of(choice)))+"\n\n";
    }
    @Test void compatibleGatewayNullAndEmptyToolFieldsAreNotToolCalls() {
        var controller=new RecruitmentAiController(models,gate,json);
        for(String tools:List.of("null","[]")) {
            String streamed="data: {\"error\":null,\"choices\":[{\"delta\":{\"content\":\"{}\",\"tool_calls\":"
                +tools+"},\"finish_reason\":null}]}\n\n"
                +frame(Map.of("delta",Map.of(),"finish_reason","stop"))
                +"data: {\"choices\":[],\"usage\":{\"completion_tokens\":2}}\n\n"
                +"data: [DONE]\n\n";
            assertEquals("{}",controller.completionContent(streamed,"text/event-stream"));
            String buffered="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\",\"tool_calls\":"
                +tools+"}}]}";
            assertEquals("{}",controller.completionContent(buffered,"application/json"));
        }
    }
    @Test void serializedConditionBatchUsesGatewayStreamAndComputesScoresWithoutRetry() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();
        server.createContext("/v1/recruitment/analyze",exchange->{
            calls.incrementAndGet();
            Map payload=json.readValue(exchange.getRequestBody().readAllBytes(),Map.class);
            assertEquals("sourcing",payload.get("mode"));
            var input=(Map)payload.get("input");
            var output=new ArrayList<Map<String,Object>>();
            for(Object item:(List<?>)input.get("candidates")) {
                var candidate=new HashMap<>(conditionCandidate("matched","unknown","matched"));
                candidate.put("id",((Map<?,?>)item).get("id"));output.add(candidate);
            }
            String response=json.writeValueAsString(Map.of("event","result","data",Map.of("candidates",output,"limitations",List.of("仅供参考"))))+"\n";
            exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));exchange.close();
        });server.start();
        try {
            when(models.driverConnection("org-1")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "driver","http://127.0.0.1:"+server.getAddress().getPort(),"model","OPENAI_COMPATIBLE","test","secret")));
            when(gate.tryAcquireModelCall()).thenReturn(mock(RedisConcurrencyGate.Permit.class));
            var candidates=new ArrayList<Map<String,String>>();
            for(int i=1;i<=5;i++)candidates.add(Map.of("id","c"+i,"evidence","前台接待及办公事务经验"));
            Map input=json.readValue(json.writeValueAsString(Map.of("jobDescription","接待与协调","profile","已确认工作条件",
                    "criteria",sourcingConditions(),"candidates",candidates)),Map.class);
            String reply=json.writeValueAsString(runtimeController(server).sourcingMatch(user("13800000000"),input));
            assertEquals(1,calls.get());assertTrue(reply.contains("\"score\":100.0"));
            assertTrue(reply.contains("\"id\":\"c5\""));assertTrue(reply.contains("\"scoreBasis\":\"known_conditions\""));
        } finally {server.stop(0);}
    }
    @Test void interruptedTruncatedFilteredAndErrorStreamsAreNeverPublishedAsSuccess() {
        var controller=new RecruitmentAiController(models,gate,json);
        String content=frame(Map.of("delta",Map.of("content","{}")));
        for(String body:List.of(
                content,
                content+frame(Map.of("finish_reason","stop")),
                content+frame(Map.of("finish_reason","length"))+"data: [DONE]\n\n",
                content+frame(Map.of("finish_reason","content_filter"))+"data: [DONE]\n\n",
                content+"data: {\"error\":{\"message\":\"private-key\"}}\n\n",
                content+frame(Map.of("delta",Map.of("tool_calls",List.of(Map.of("name","send"))))))) {
            var error=assertThrows(BusinessException.class,()->controller.completionContent(body,"text/event-stream"));
            assertFalse(error.getMessage().contains("private-key"));
        }
    }
    @Test void providerFailureIsRedactedAndNeverAutomaticallyRetried() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();
        server.createContext("/v1/recruitment/analyze",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] text=(json.writeValueAsString(Map.of("event","error","data",Map.of("code","HR_AI_UPSTREAM_HTTP",
                    "upstreamStatus",502,"message","sensitive-provider-key")))+"\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,text.length);exchange.getResponseBody().write(text);exchange.close();
        });server.start();
        try{
            when(models.driverConnection("org-1")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection(
                    "driver","http://127.0.0.1:"+server.getAddress().getPort(),"model","OPENAI_COMPATIBLE","test","secret")));
            when(gate.tryAcquireModelCall()).thenReturn(mock(RedisConcurrencyGate.Permit.class));
            var failure=assertThrows(BusinessException.class,()->runtimeController(server).generate(user("13800000000"),demand()));
            assertFalse(failure.getMessage().contains("sensitive"));assertEquals(1,calls.get());
            assertTrue(failure.getMessage().contains("HTTP 502"));
        }finally{server.stop(0);}
    }
}
