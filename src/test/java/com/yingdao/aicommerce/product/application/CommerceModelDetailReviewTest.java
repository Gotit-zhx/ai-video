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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CommerceModelDetailReviewTest {
    @TempDir Path temp;
    static final ObjectMapper JSON = new ObjectMapper();
    final byte[] product = image(160, 160, Color.RED);
    final byte[] style = image(160, 80, Color.GREEN);
    final byte[] outputImage = image(80, 160, Color.BLUE);

    @Test void springCreatesTheServiceWithoutAProvidedRestClientBuilder() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("app.pi-runtime-url=http://127.0.0.1:1", "app.pi-runtime-token=test-only")
                .withBean(ModelConfigService.class, () -> mock(ModelConfigService.class))
                .withUserConfiguration(CommerceModelDetailReview.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(CommerceModelDetailReview.class));
                });
    }

    @Test void submitsAllReferencesInFixedLabeledOrderAndBindsTheVerdictToOriginalBytes() {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andExpect(header("X-Internal-Token", "test-internal"))
                .andExpect(jsonPath("$.context.analysisType").value("COMMERCE_MODEL_DETAIL_V1"))
                .andExpect(jsonPath("$.context.reviewPolicyVersion").value(CommerceModelDetailReview.REVIEW_POLICY_VERSION))
                .andExpect(jsonPath("$.context.panels[0].role").value("PRODUCT"))
                .andExpect(jsonPath("$.context.panels[1].role").value("POSE"))
                .andExpect(jsonPath("$.context.panels[2].role").value("OUTPUT"))
                .andExpect(jsonPath("$.model.modelId").value("vision-model"))
                .andExpect(jsonPath("$.image.mimeType").value("image/jpeg"))
                .andExpect(request -> {
                    var payload = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    byte[] raw = Base64.getDecoder().decode(payload.path("image").path("data").asText());
                    var sheet = ImageIO.read(new ByteArrayInputStream(raw));
                    assertEquals(1920, sheet.getWidth());
                    assertEquals(688, sheet.getHeight());
                    colorClose(sheet, 320, 368, Color.RED);
                    colorClose(sheet, 960, 368, Color.GREEN);
                    colorClose(sheet, 1600, 368, Color.BLUE);
                    // Different aspect ratios remain complete, with gray letterboxing outside them.
                    colorClose(sheet, 960, 70, new Color(236, 236, 236));
                    colorClose(sheet, 1300, 368, new Color(236, 236, 236));
                    assertTrue(raw.length < 8 * 1024 * 1024);
                    assertTrue(hasLabelInk(sheet, 0));
                    assertTrue(hasLabelInk(sheet, 640));
                    assertTrue(hasLabelInk(sheet, 1280));
                })
                .andRespond(withSuccess(response(accepted()), MediaType.APPLICATION_JSON));
        var report = h.service.review("tenant-a", product, style, outputImage);
        assertEquals(true, report.get("passed"));
        assertEquals("COMMERCE_MODEL_DETAIL_V1", report.get("contractVersion"));
        assertEquals(CommerceModelDetailReview.REVIEW_POLICY_VERSION, report.get("reviewPolicyVersion"));
        assertEquals(CommerceImageArchive.sha(product), report.get("productSha256"));
        assertEquals(CommerceImageArchive.sha(style), report.get("styleSha256"));
        assertEquals(CommerceImageArchive.sha(outputImage), report.get("outputSha256"));
        assertEquals("模特实际穿着本款，版型、印花、颜色一致，姿态与构图可用。", report.get("reason"));
        assertFalse(report.containsKey("model"));
        assertFalse(JSON.writeValueAsString(report).contains("secret-test"));
        verify(h.models).driverConnection("tenant-a");
        h.server.verify();
    }

    @Test void anyExplicitFailureRejectsTheMasterWithoutThrowingOrRetrying() {
        for (String field : CommerceModelDetailReview.CHECKS) {
            var h = new Harness();
            var result = accepted();
            result.put(field, false);
            result.put("passed", true); // A model cannot override the six required observations.
            result.put("reason", "候选详情图缺少要求的细节：" + field);
            h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                    .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
            var report = h.service.review("tenant-a", product, style, outputImage);
            assertEquals(false, report.get("passed"), field);
            assertEquals(false, report.get(field));
            h.server.verify();
        }
    }

    @Test void missingNullStringsAndNumericVerdictsAreUnavailableRatherThanQualityRejections() {
        for (String field : CommerceModelDetailReview.CHECKS) {
            for (Object invalid : List.of("missing", "true", "false", 0, 1)) {
                var result = accepted();
                if ("missing".equals(invalid)) result.remove(field);
                else result.put(field, invalid);
                assertUnavailable(response(result));
            }
            var result = accepted();
            result.put(field, null);
            assertUnavailable(response(result));
        }
    }

    @Test void missingEvidenceAndInvalidEnvelopesNeverProduceAPassingOrRejectedReport() {
        for (String invalid : List.of("{}", "null", "{\"result\":null}", "{\"result\":[]}", "not-json"))
            assertUnavailable(invalid);
        for (Object reason : List.of("", "  ", "x".repeat(301), true)) {
            var result = accepted();
            result.put("reason", reason);
            assertUnavailable(response(result));
        }
        var result = accepted();
        result.remove("reason");
        assertUnavailable(response(result));
    }

    @Test void httpFailureIsUnavailableAndCannotTriggerASecondModelRequest() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE)) {
            var h = new Harness();
            h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                    .andRespond(withStatus(status).body("provider-body-must-not-be-recorded"));
            var failure = assertThrows(CommerceModelDetailReview.ReviewUnavailableException.class,
                    () -> h.service.review("tenant-a", product, style, outputImage));
            assertEquals(status!=HttpStatus.BAD_REQUEST,failure.retryable());assertFalse(failure.invalidResponse());
            assertFalse(failure.getMessage().contains("provider-body"));
            assertNull(failure.getCause());
            h.server.verify();
        }
    }

    @Test void typedRuntimeFailuresDoNotConfuseInvalidResultsWithTemporaryConnections() {
        for(var example:List.of(new Object[]{"CHAT_TIMEOUT",true,false},new Object[]{"CHAT_NETWORK_INTERRUPTED",true,false},
                new Object[]{"CHAT_RATE_LIMITED",true,false},new Object[]{"COMMERCE_MODEL_DETAIL_INVALID_SCHEMA",false,true},
                new Object[]{"CHAT_OUTPUT_TRUNCATED",false,true},new Object[]{"CHAT_AUTHENTICATION_FAILED",false,false},
                new Object[]{"COMMERCE_MODEL_DETAIL_INVALID_IMAGE",false,false})){
            var h=new Harness();h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(Map.of("failureCode",example[0],"retryable",example[1],"message","private provider body"))));
            var error=assertThrows(CommerceModelDetailReview.ReviewUnavailableException.class,()->h.service.review("tenant-a",product,style,outputImage));
            assertEquals(example[0],error.reasonCode());assertEquals(example[1],error.retryable());assertEquals(example[2],error.invalidResponse());
            assertFalse(error.getMessage().contains("private"));assertNull(error.getCause());h.server.verify();
        }
    }

    @Test void networkReadFailureRemainsUnavailableWithNoAutomaticProviderRetry() {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withException(new IOException("upstream connection interrupted")));
        assertThrows(CommerceModelDetailReview.ReviewUnavailableException.class,
                () -> h.service.review("tenant-a", product, style, outputImage));
        h.server.verify();
    }

    @Test void passedReviewIsAtomicallyReusedAfterAServiceRestartWithoutAnotherModelRead() throws IOException {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(accepted()), MediaType.APPLICATION_JSON));
        var first = h.service.review("tenant-a", product, style, outputImage);
        assertEquals(first, h.service.review("tenant-a", product, style, outputImage));
        var builder = RestClient.builder().baseUrl("http://runtime");
        var noRequests = MockRestServiceServer.bindTo(builder).build();
        var restarted = new CommerceModelDetailReview(h.models, builder.build(), h.cache);
        assertEquals(first, restarted.review("tenant-a", product, style, outputImage));
        try (var files = Files.list(h.cache)) {
            var saved = files.toList();
            assertEquals(1, saved.size());
            assertTrue(saved.getFirst().getFileName().toString().matches("[a-f0-9]{64}\\.json"));
            assertFalse(Files.readString(saved.getFirst()).contains("secret-test"));
        }
        h.server.verify();
        noRequests.verify();
    }

    @Test void inputModelAndTenantChangesCannotReuseAnUnrelatedPassingReview() {
        var h = new Harness();
        h.server.expect(org.springframework.test.web.client.ExpectedCount.times(6), requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(accepted()), MediaType.APPLICATION_JSON));
        var original = h.service.review("tenant-a", product, style, outputImage);
        assertNotEquals(original.get("productSha256"),
                h.service.review("tenant-a", image(160, 160, Color.CYAN), style, outputImage).get("productSha256"));
        assertNotEquals(original.get("styleSha256"),
                h.service.review("tenant-a", product, image(160, 80, Color.CYAN), outputImage).get("styleSha256"));
        assertNotEquals(original.get("outputSha256"),
                h.service.review("tenant-a", product, style, image(80, 160, Color.CYAN)).get("outputSha256"));
        when(h.models.driverConnection("tenant-b")).thenReturn(Optional.of(h.model));
        assertNotEquals(original.get("organizationSha256"),
                h.service.review("tenant-b", product, style, outputImage).get("organizationSha256"));
        var changed = new ModelConfigService.RuntimeModelConnection("model-id", "https://model.example",
                "different-vision", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        when(h.models.driverConnection("tenant-a")).thenReturn(Optional.of(changed));
        assertNotEquals(original.get("modelFingerprint"),
                h.service.review("tenant-a", product, style, outputImage).get("modelFingerprint"));
        h.server.verify();
    }

    @Test void oldPolicyApprovalCannotBypassTheNewGarmentFidelityReview() throws IOException {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(accepted()), MediaType.APPLICATION_JSON));
        var rejected = accepted();
        rejected.put("preservesGarment", false);
        rejected.put("reason", "右图把模板短袖上衣拉长成连衣裙，衣长比例与下摆不符。");
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(rejected), MediaType.APPLICATION_JSON));
        var first = h.service.review("tenant-a", product, style, outputImage);
        var oldReport = new LinkedHashMap<>(first);
        oldReport.remove("reviewPolicyVersion");
        var oldBinding = new java.util.TreeMap<>(oldReport);
        CommerceModelDetailReview.CHECKS.forEach(oldBinding::remove);
        oldBinding.remove("passed");
        oldBinding.remove("reason");
        String oldKey = CommerceImageArchive.sha(JSON.writeValueAsBytes(oldBinding));
        Path current;
        try (var files = Files.list(h.cache)) { current = files.findFirst().orElseThrow(); }
        Files.delete(current);
        Files.writeString(h.cache.resolve(oldKey + ".json"), JSON.writeValueAsString(oldReport));

        var reviewed = h.service.review("tenant-a", product, style, outputImage);
        assertEquals(false, reviewed.get("passed"));
        assertEquals(CommerceModelDetailReview.REVIEW_POLICY_VERSION, reviewed.get("reviewPolicyVersion"));
        assertFalse(Files.exists(current), "a fresh rejection must not become a passing cache entry");
        h.server.verify();
    }

    @Test void negativeVerdictIsNotPersistedAsAnApprovedCacheEntry() throws IOException {
        var h = new Harness();
        var result = accepted();
        result.put("coherentPose", false);
        h.server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
        assertEquals(false, h.service.review("tenant-a", product, style, outputImage).get("passed"));
        assertEquals(false, h.service.review("tenant-a", product, style, outputImage).get("passed"));
        assertFalse(Files.exists(h.cache));
        h.server.verify();
    }

    @Test void damagedOrMismatchedCacheNeverSilentlyBecomesAnApproval() throws IOException {
        for (String damage : List.of("contractVersion", "reviewPolicyVersion", "outputSha256", "coherentPose", "not-json")) {
            var h = new Harness();
            h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                    .andRespond(withSuccess(response(accepted()), MediaType.APPLICATION_JSON));
            h.service.review("tenant-a", product, style, outputImage);
            Path saved;
            try (var files = Files.list(h.cache)) { saved = files.findFirst().orElseThrow(); }
            if ("not-json".equals(damage)) Files.writeString(saved, "incomplete response");
            else {
                var report = JSON.readValue(Files.readString(saved), Map.class);
                report.put(damage, "changed");
                Files.writeString(saved, JSON.writeValueAsString(report));
            }
            assertThrows(CommerceModelDetailReview.ReviewUnavailableException.class,
                    () -> h.service.review("tenant-a", product, style, outputImage));
            h.server.verify();
        }
    }

    @Test void absentModelOrUnreadableReferencesNeverSubmitAnAnalysis() {
        var h = new Harness();
        when(h.models.driverConnection("tenant-a")).thenReturn(Optional.empty());
        assertThrows(CommerceModelDetailReview.ReviewUnavailableException.class,
                () -> h.service.review("tenant-a", product, style, outputImage));
        when(h.models.driverConnection("tenant-a")).thenReturn(Optional.of(h.model));
        assertThrows(IllegalArgumentException.class,
                () -> h.service.review("tenant-a", product, new byte[]{1, 2, 3}, outputImage));
        assertThrows(IllegalArgumentException.class,
                () -> h.service.review("", product, style, outputImage));
        h.server.verify();
    }

    private void assertUnavailable(String response) {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(CommerceModelDetailReview.ReviewUnavailableException.class,
                () -> h.service.review("tenant-a", product, style, outputImage));
        h.server.verify();
    }

    private static Map<String, Object> accepted() {
        var result = new LinkedHashMap<String, Object>();
        CommerceModelDetailReview.CHECKS.forEach(field -> result.put(field, true));
        result.put("reason", "模特实际穿着本款，版型、印花、颜色一致，姿态与构图可用。");
        return result;
    }

    private static String response(Map<String, Object> result) {
        return JSON.writeValueAsString(Map.of("result", result));
    }

    private static byte[] image(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(color); graphics.fillRect(0, 0, width, height); }
        finally { graphics.dispose(); }
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private static boolean hasLabelInk(BufferedImage sheet, int left) {
        for (int x = left + 10; x < left + 430; x++)
            for (int y = 8; y < 40; y++)
                if ((sheet.getRGB(x, y) & 255) < 80) return true;
        return false;
    }

    private static void colorClose(BufferedImage image, int x, int y, Color expected) {
        var actual = new Color(image.getRGB(x, y));
        assertTrue(Math.abs(actual.getRed() - expected.getRed()) <= 5);
        assertTrue(Math.abs(actual.getGreen() - expected.getGreen()) <= 5);
        assertTrue(Math.abs(actual.getBlue() - expected.getBlue()) <= 5);
    }

    private class Harness {
        final ModelConfigService models = mock(ModelConfigService.class);
        final MockRestServiceServer server;
        final CommerceModelDetailReview service;
        final Path cache = temp.resolve(java.util.UUID.randomUUID().toString());
        final ModelConfigService.RuntimeModelConnection model = new ModelConfigService.RuntimeModelConnection("model-id", "https://model.example",
                "vision-model", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        Harness() {
            var builder = RestClient.builder().baseUrl("http://runtime")
                    .defaultHeader("X-Internal-Token", "test-internal");
            server = MockRestServiceServer.bindTo(builder).build();
            service = new CommerceModelDetailReview(models, builder.build(), cache);
            when(models.driverConnection("tenant-a")).thenReturn(Optional.of(model));
        }
    }
}
