package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CommerceModelDetailStyleGuideTest {
    @TempDir Path temp;
    static final ObjectMapper JSON = new ObjectMapper();
    final byte[] original = image(Color.RED), output = image(Color.BLUE);

    @Test void springInstantiatesServiceWithConfiguredRuntimeOnly() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("app.pi-runtime-url=http://127.0.0.1:1", "app.pi-runtime-token=test")
                .withBean(ModelConfigService.class, () -> mock(ModelConfigService.class))
                .withUserConfiguration(CommerceModelDetailStyleGuide.class)
                .run(context -> { assertNull(context.getStartupFailure()); assertNotNull(context.getBean(CommerceModelDetailStyleGuide.class)); });
    }

    @Test void guideUsesUndistortedOriginalCoordinatesAndBindsDesignAndEveryOldPrintRegion() {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andExpect(header("X-Internal-Token", "test-internal"))
                .andExpect(jsonPath("$.context.analysisType").value(CommerceModelDetailStyleGuide.CONTRACT_VERSION))
                .andExpect(request -> {
                    var node = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(node.path("image").path("data").asText())));
                    assertEquals(160, image.getWidth()); assertEquals(80, image.getHeight(), "No square padding changes normalized mask coordinates");
                }).andRespond(withSuccess(response(guideResult()), MediaType.APPLICATION_JSON));
        var guide = h.service.prepare("org", original);
        assertEquals("SEATED_MODEL", guide.shotType()); assertEquals(2, guide.regions().size()); assertFalse(guide.printMacro());
        assertEquals(CommerceImageArchive.sha(original), guide.sourceSha256());
        assertTrue(guide.description().contains("咖啡馆")); assertTrue(guide.description().contains("坐姿"));
        assertEquals(new CommerceModelDetailStyleGuide.Region(.1, .4, .1, .1), guide.regions().get(1));
        assertThrows(UnsupportedOperationException.class, () -> guide.regions().clear());
        assertFalse(JSON.writeValueAsString(guide).contains("secret-test")); h.server.verify();
    }

    @Test void guideIsAtomicallyCachedAcrossRestartAndFrozenRecordRoundTripsWithoutCurrentModel() throws Exception {
        var h = new Harness(); h.guide();
        var first = h.service.prepare("org", original);
        var builder = RestClient.builder().baseUrl("http://runtime"); var noNetwork = MockRestServiceServer.bindTo(builder).build();
        var restarted = new CommerceModelDetailStyleGuide(h.models, builder.build(), h.cache);
        assertEquals(first, restarted.prepare("org", original));
        var frozen = JSON.readValue(JSON.writeValueAsString(first), CommerceModelDetailStyleGuide.Guide.class);
        assertSame(frozen, CommerceModelDetailStyleGuide.validateFrozen(frozen));
        assertEquals(CommerceModelDetailStyleGuide.sha(first), CommerceModelDetailStyleGuide.sha(frozen));
        assertEquals(1, Files.list(h.cache).filter(p -> p.toString().endsWith(".json")).count());
        noNetwork.verify(); h.server.verify();
    }

    @Test void changedSourceTenantAndModelEachRequireTheirOwnGuide() {
        var h = new Harness();
        h.server.expect(org.springframework.test.web.client.ExpectedCount.times(4), requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(guideResult()), MediaType.APPLICATION_JSON));
        var first = h.service.prepare("org", original);
        assertNotEquals(first.sourceSha256(), h.service.prepare("org", output).sourceSha256());
        when(h.models.driverConnection("other")).thenReturn(Optional.of(h.model));
        assertNotEquals(first.organizationSha256(), h.service.prepare("other", original).organizationSha256());
        when(h.models.driverConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection("id", "https://vision.invalid", "changed", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test")));
        assertNotEquals(first.modelFingerprint(), h.service.prepare("org", original).modelFingerprint()); h.server.verify();
    }

    @Test void timeoutSendsOneRequestAndNextWorkerReusesSuccessfulCachedResult() {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withException(new IOException("private connection message")));
        h.guide();
        var network=assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class,()->h.service.prepare("org",original));
        assertTrue(network.retryable());assertFalse(network.invalidResponse());assertEquals("STYLE_NETWORK",network.reasonCode());
        assertNotNull(h.service.prepare("org", original));assertNotNull(h.service.prepare("org", original));h.server.verify();
        var failed = new Harness();
        failed.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).body("private provider body"));
        var exception = assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class, () -> failed.service.prepare("org", original));
        assertTrue(exception.retryable());assertEquals("STYLE_HTTP_504",exception.reasonCode());assertFalse(exception.getMessage().contains("private"));assertNull(exception.getCause());
        assertFalse(Files.exists(failed.cache));failed.server.verify();
    }

    @Test void runtimeCodesSeparateTemporaryTransportInvalidOutputAndPermanentConfiguration() {
        for(var example:List.of(new Object[]{"CHAT_TIMEOUT",true,false},new Object[]{"CHAT_NETWORK_INTERRUPTED",true,false},
                new Object[]{"CHAT_RATE_LIMITED",true,false},new Object[]{"CHAT_UPSTREAM_FAILED",true,false},
                new Object[]{"COMMERCE_MODEL_DETAIL_STYLE_INVALID",false,true},new Object[]{"CHAT_OUTPUT_TRUNCATED",false,true},
                new Object[]{"CHAT_PARAMETERS_REJECTED",false,false},new Object[]{"CHAT_AUTHENTICATION_FAILED",false,false})){
            var h=new Harness();h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(Map.of("failureCode",example[0],"retryable",example[1],"message","secret upstream credentials"))));
            var error=assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class,()->h.service.prepare("org",original));
            assertEquals(example[0],error.reasonCode());assertEquals(example[1],error.retryable());assertEquals(example[2],error.invalidResponse());
            assertFalse(error.getMessage().contains("secret"));assertNull(error.getCause());h.server.verify();
        }
    }

    @Test void storedGuideCannotBeReusedAcrossChangedDriverConfiguration() {
        var h=new Harness();h.guide();var guide=h.service.prepare("org",original);h.service.requireCurrentModel("org",guide);
        when(h.models.driverConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection("changed","https://vision.invalid","new-model","OPENAI_COMPATIBLE","CUSTOM","new-secret")));
        var error=assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class,()->h.service.requireCurrentModel("org",guide));
        assertEquals("STYLE_GUIDE_BINDING_INVALID",error.reasonCode());assertFalse(error.retryable());h.server.verify();
    }

    @Test void permanentFailureAndMalformedGuideAreNotRetriedOrReplacedWithFullImageBlur() {
        var permanent = new Harness(); permanent.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withBadRequest());
        assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class, () -> permanent.service.prepare("org", original)); permanent.server.verify();
        for (var mutation : List.<Map.Entry<String, Object>>of(Map.entry("complete", false), Map.entry("complete", "true"),
                Map.entry("hasVisiblePrint", "true"), Map.entry("shotType", "UNKNOWN"), Map.entry("lighting", ""), Map.entry("pose", "x".repeat(181)))) {
            var h = new Harness(); var result = guideResult(); result.put(mutation.getKey(), mutation.getValue());
            h.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
            var exception = assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class, () -> h.service.prepare("org", original));
            assertTrue(exception.getMessage().contains("不回退全图模糊")); assertFalse(Files.exists(h.cache)); h.server.verify();
        }
    }

    @Test void regionsAreFiniteBoundedLimitedAndCannotCoverTheScene() {
        for (var region : List.of(new double[]{-.1, 0, .1, .1}, new double[]{0, 0, Double.NaN, .1}, new double[]{.9, 0, .2, .1}, new double[]{0, 0, 0, .1}))
            assertThrows(IllegalArgumentException.class, () -> new CommerceModelDetailStyleGuide.Region(region[0], region[1], region[2], region[3]));
        var badLists = List.of(List.of(Map.of("x", 0, "y", 0, "width", 1, "height", 1)),
                Collections.nCopies(9, Map.of("x", .1, "y", .1, "width", .1, "height", .1)),
                List.of(Map.of("x", 0, "y", 0, "width", .5, "height", .8), Map.of("x", .5, "y", 0, "width", .5, "height", .8)),
                List.of(Map.of("x", "0.1", "y", .1, "width", .1, "height", .1)));
        for (var regions : badLists) {
            var h = new Harness(); var result = guideResult(); result.put("regions", regions);
            h.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
            assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class, () -> h.service.prepare("org", original)); h.server.verify();
        }
    }

    @Test void explicitMacroRetainsCloseupDesignWithOneFullFrameRegion() {
        var h = new Harness(); var result = guideResult(); result.put("shotType", "PRINT_MACRO");
        result.put("regions", List.of(Map.of("x", 0, "y", 0, "width", 1, "height", 1)));
        h.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
        var guide = h.service.prepare("org", original); assertTrue(guide.printMacro());
        assertTrue(guide.description().contains("不要求露脸或全身")); assertTrue(guide.description().contains("不改为全身站姿"));
        assertEquals(List.of(new CommerceModelDetailStyleGuide.Region(0, 0, 1, 1)), guide.regions()); h.server.verify();
    }

    @Test void noPrintHasNoMasksAndOverlappingSafeMasksUseUnionArea() {
        var h = new Harness(); var plain = guideResult(); plain.put("hasVisiblePrint", false); plain.put("regions", List.of());
        var overlap = guideResult(); overlap.put("regions", Collections.nCopies(8, Map.of("x", .1, "y", .1, "width", .5, "height", .5)));
        h.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withSuccess(response(plain), MediaType.APPLICATION_JSON));
        h.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withSuccess(response(overlap), MediaType.APPLICATION_JSON));
        assertTrue(h.service.prepare("org", original).regions().isEmpty()); assertEquals(8, h.service.prepare("org", output).regions().size()); h.server.verify();
    }

    @Test void styleMatchIsBoundToBothImagesFrozenGuideAndModelThenCachedOnSuccess() {
        var h = new Harness(); h.guide();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andExpect(jsonPath("$.context.analysisType").value(CommerceModelDetailStyleGuide.MATCH_VERSION))
                .andExpect(jsonPath("$.context.panels[0].role").value("ORIGINAL"))
                .andExpect(jsonPath("$.context.panels[1].role").value("OUTPUT"))
                .andExpect(jsonPath("$.context.guide.shotType").value("SEATED_MODEL"))
                .andRespond(withSuccess(response(matchResult()), MediaType.APPLICATION_JSON));
        var guide = h.service.prepare("org", original); var match = h.service.compare("org", original, output, guide);
        assertTrue(match.passed()); assertTrue(match.matchesShotType());
        assertEquals(CommerceModelDetailStyleGuide.sha(guide), match.guideSha256()); assertEquals(CommerceImageArchive.sha(output), match.outputSha256());
        assertEquals(match, h.service.compare("org", original, output, guide)); h.server.verify();
    }

    @Test void poseOnlyReviewHasSeparateCacheAndPolicyBindingFromLegacyStyle() throws Exception {
        var h = new Harness(); h.guide();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andExpect(jsonPath("$.context.renderPolicy").doesNotExist())
                .andRespond(withSuccess(response(matchResult()), MediaType.APPLICATION_JSON));
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andExpect(jsonPath("$.context.renderPolicy").value(CommerceModelDetailStyleGuide.POSE_ONLY_POLICY))
                .andRespond(withSuccess(response(matchResult()), MediaType.APPLICATION_JSON));
        var guide=h.service.prepare("org",original);
        var legacy=h.service.compare("org",original,output,guide);
        var pose=h.service.compare("org",original,output,guide,CommerceModelDetailStyleGuide.POSE_ONLY_POLICY);
        assertNull(legacy.renderPolicy()); assertEquals(CommerceModelDetailStyleGuide.POSE_ONLY_POLICY,pose.renderPolicy());
        assertEquals(legacy,h.service.compare("org",original,output,guide));
        assertEquals(pose,h.service.compare("org",original,output,guide,CommerceModelDetailStyleGuide.POSE_ONLY_POLICY));
        assertEquals(3,Files.list(h.cache).filter(p->p.toString().endsWith(".json")).count());
        assertThrows(IllegalArgumentException.class,()->h.service.compare("org",original,output,guide,"UNREVIEWED_POLICY"));
        h.server.verify();
    }

    @Test void explicitStyleDifferenceReturnsFalseWithoutAnotherModelRequestOrApprovedCache() throws Exception {
        var h = new Harness(); h.guide(); var failed = matchResult(); failed.put("matchesPose", false); failed.put("reason", "原坐姿被改为全身站姿");
        h.server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(failed), MediaType.APPLICATION_JSON));
        var guide = h.service.prepare("org", original);
        for (int i = 0; i < 2; i++) { var report = h.service.compare("org", original, output, guide); assertFalse(report.passed()); assertFalse(report.matchesPose()); }
        assertEquals(1, Files.list(h.cache).filter(p -> p.toString().endsWith(".json")).count()); h.server.verify();
    }

    @Test void comparisonRejectsMismatchedSourceAndTenantBeforeAnyAnalysis() {
        var h = new Harness(); h.guide(); var guide = h.service.prepare("org", original);
        assertThrows(IllegalArgumentException.class, () -> h.service.compare("org", output, original, guide));
        assertThrows(IllegalArgumentException.class, () -> h.service.compare("other", original, output, guide)); h.server.verify();
    }

    @Test void malformedComparisonIsUnavailableAndCannotCoerceFlagsIntoSuccess() {
        var h = new Harness(); h.guide(); var malformed = matchResult(); malformed.put("matchesSetting", "true");
        h.server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withSuccess(response(malformed), MediaType.APPLICATION_JSON));
        var guide = h.service.prepare("org", original);
        assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class, () -> h.service.compare("org", original, output, guide)); h.server.verify();
    }

    @Test void corruptedCachedGeometryOrBindingsFailClosedWithoutReanalyzing() throws Exception {
        var h = new Harness(); h.guide(); h.service.prepare("org", original);
        Path path; try (var files = Files.list(h.cache)) { path = files.findFirst().orElseThrow(); }
        var saved = JSON.readValue(Files.readString(path), Map.class); saved.put("sourceSha256", "f".repeat(64)); Files.writeString(path, JSON.writeValueAsString(saved));
        assertThrows(CommerceModelDetailStyleGuide.UnavailableException.class, () -> h.service.prepare("org", original)); h.server.verify();
    }

    private class Harness {
        final ModelConfigService models = mock(ModelConfigService.class);
        final ModelConfigService.RuntimeModelConnection model = new ModelConfigService.RuntimeModelConnection("id", "https://vision.invalid", "vision", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        final Path cache = temp.resolve(UUID.randomUUID().toString());
        final RestClient.Builder builder = RestClient.builder().baseUrl("http://runtime").defaultHeader("X-Internal-Token", "test-internal");
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final CommerceModelDetailStyleGuide service = new CommerceModelDetailStyleGuide(models, builder.build(), cache);
        Harness() { when(models.driverConnection("org")).thenReturn(Optional.of(model)); }
        void guide() { server.expect(requestTo("http://runtime/v1/product/analyze")).andRespond(withSuccess(response(guideResult()), MediaType.APPLICATION_JSON)); }
    }
    private static Map<String, Object> guideResult() {
        var result = new LinkedHashMap<String, Object>(); result.put("shotType", "SEATED_MODEL"); result.put("composition", "半身坐姿，人物居中");
        result.put("pose", "坐在咖啡桌旁，身体略向右"); result.put("setting", "咖啡馆桌椅与街边"); result.put("lighting", "温暖自然侧光");
        result.put("hasVisiblePrint", true); result.put("complete", true);
        result.put("regions", List.of(Map.of("x", .3, "y", .3, "width", .3, "height", .4), Map.of("x", .1, "y", .4, "width", .1, "height", .1))); return result;
    }
    private static Map<String, Object> matchResult() { return new LinkedHashMap<>(Map.of("matchesShotType", true, "matchesPose", true, "matchesSetting", true, "reason", "保持原坐姿中景、咖啡馆和光线")); }
    private static String response(Map<String, Object> result) { return JSON.writeValueAsString(Map.of("result", result)); }
    private static byte[] image(Color color) {
        try { var image = new BufferedImage(160, 80, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
            try { g.setColor(color); g.fillRect(0, 0, 160, 80); } finally { g.dispose(); }
            var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
        } catch (IOException error) { throw new AssertionError(error); }
    }
}
