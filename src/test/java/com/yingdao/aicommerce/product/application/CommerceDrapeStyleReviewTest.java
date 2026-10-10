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

class CommerceDrapeStyleReviewTest {
    @TempDir Path temp;
    static final ObjectMapper JSON = new ObjectMapper();
    final byte[] guide = image(160, 160, Color.RED);
    final byte[] scene = image(160, 80, Color.GREEN);
    final byte[] master = image(80, 160, Color.BLUE);

    @Test void springCreatesTheServiceWithoutAProvidedRestClientBuilder() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("app.pi-runtime-url=http://127.0.0.1:1", "app.pi-runtime-token=test-only")
                .withBean(ModelConfigService.class, () -> mock(ModelConfigService.class))
                .withUserConfiguration(CommerceDrapeStyleReview.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(CommerceDrapeStyleReview.class));
                });
    }

    @Test void submitsAllReferencesInFixedLabeledOrderAndBindsTheVerdictToOriginalBytes() {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andExpect(header("X-Internal-Token", "test-internal"))
                .andExpect(jsonPath("$.context.analysisType").value("COMMERCE_DRAPE_STYLE_V1"))
                .andExpect(jsonPath("$.context.panels[0].role").value("GUIDE"))
                .andExpect(jsonPath("$.context.panels[1].role").value("SOURCE"))
                .andExpect(jsonPath("$.context.panels[2].role").value("MASTER"))
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
        var report = h.service.review("tenant-a", guide, scene, master);
        assertEquals(true, report.get("passed"));
        assertEquals("COMMERCE_DRAPE_STYLE_V1", report.get("contractVersion"));
        assertEquals(CommerceImageArchive.sha(guide), report.get("guideSha256"));
        assertEquals(CommerceImageArchive.sha(scene), report.get("sceneSha256"));
        assertEquals(CommerceImageArchive.sha(master), report.get("masterSha256"));
        assertEquals("服装完整，背景和搭放褶皱可见。", report.get("reason"));
        assertFalse(report.containsKey("model"));
        assertFalse(JSON.writeValueAsString(report).contains("secret-test"));
        verify(h.models).driverConnection("tenant-a");
        h.server.verify();
    }

    @Test void anyExplicitFailureRejectsTheMasterWithoutThrowingOrRetrying() {
        for (String field : CommerceDrapeStyleReview.CHECKS) {
            var h = new Harness();
            var result = accepted();
            result.put(field, false);
            result.put("passed", true); // A model cannot override the six required observations.
            result.put("reason", "候选母图缺少要求的细节：" + field);
            h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                    .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
            var report = h.service.review("tenant-a", guide, scene, master);
            assertEquals(false, report.get("passed"), field);
            assertEquals(false, report.get(field));
            h.server.verify();
        }
    }
    @Test void V2RequiresAnIndependentNecklineVerdictAndDoesNotReuseV1Cache() {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andExpect(jsonPath("$.context.analysisType").value("COMMERCE_DRAPE_STYLE_V1"))
                .andRespond(withSuccess(response(accepted()), MediaType.APPLICATION_JSON));
        var changed = accepted();changed.put("preservesNeckline",false);
        changed.put("preservesSleeveBadge", true);
        changed.put("preservesSideLabel", true);
        changed.put("reason","领口被加深，领标位置错误");
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andExpect(jsonPath("$.context.analysisType").value("COMMERCE_DRAPE_STYLE_V4"))
                .andExpect(request -> {
                    var payload = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    var sheet = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(
                            payload.path("image").path("data").asText())));
                    assertEquals(1920, sheet.getWidth());
                    assertEquals(1168, sheet.getHeight());
                    // The inset makes the original MASTER image edge visible, so
                    // the model cannot confuse the contact-sheet crop with a sleeve.
                    colorClose(sheet, 1285, 368, new Color(236, 236, 236));
                    colorClose(sheet, 1319, 368, new Color(236, 236, 236));
                    colorClose(sheet, 1600, 368, Color.BLUE);
                    colorClose(sheet, 1600, 65, new Color(236, 236, 236));
                    colorClose(sheet, 240, 1050, Color.RED);
                    colorClose(sheet, 720, 1050, Color.BLUE);
                })
                .andRespond(withSuccess(response(changed), MediaType.APPLICATION_JSON));
        assertEquals(true, h.service.review("tenant-a",guide,scene,master).get("passed"));
        var verdict=h.service.review("tenant-a",guide,scene,master,true);
        assertEquals(false,verdict.get("passed"));
        assertEquals(false,verdict.get("preservesNeckline"));
        assertEquals(CommerceDrapeStyleReview.NECKLINE_CONTRACT_VERSION,verdict.get("contractVersion"));
        h.server.verify();
    }

    @Test void missingSleeveOrSideVerdictCannotPassV3() {
        for (String field : List.of("preservesSleeveBadge", "preservesSideLabel")) {
            var h = new Harness();
            var result = accepted();
            result.put("preservesNeckline", true);
            result.put("preservesSleeveBadge", true);
            result.put("preservesSideLabel", true);
            result.remove(field);
            h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                    .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
            assertThrows(CommerceDrapeStyleReview.ReviewUnavailableException.class,
                    () -> h.service.review("tenant-a", guide, scene, master, true));
            h.server.verify();
        }
    }

    @Test void localEvidenceOverridesModelsFalsePositiveAndBindsExactBytes() {
        var model = mock(ModelConfigService.class);
        var connection = new ModelConfigService.RuntimeModelConnection("model-id", "https://model.example",
                "vision-model", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        when(model.driverConnection("tenant-a")).thenReturn(Optional.of(connection));
        var visionBuilder = RestClient.builder().baseUrl("http://runtime");
        var vision = MockRestServiceServer.bindTo(visionBuilder).build();
        var detectorBuilder = RestClient.builder().baseUrl("http://detector");
        var detector = MockRestServiceServer.bindTo(detectorBuilder).build();
        var result = accepted();
        result.put("preservesNeckline", true);
        result.put("preservesSleeveBadge", true);
        result.put("preservesSideLabel", true);
        vision.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
        detector.expect(requestTo("http://detector/drape-detail-check"))
                .andExpect(jsonPath("$.guide").value(Base64.getEncoder().encodeToString(guide)))
                .andExpect(jsonPath("$.master").value(Base64.getEncoder().encodeToString(master)))
                .andRespond(withSuccess(JSON.writeValueAsString(Map.of(
                        "algorithm", "DRAPE_DETAIL_GUARD_V1", "status", "REJECTED",
                        "guideSha256", CommerceImageArchive.sha(guide),
                        "masterSha256", CommerceImageArchive.sha(master),
                        "geometryFullyVisible", true,
                        "borderMargins", Map.of("left", 30, "right", 30, "top", 30, "bottom", 30),
                        "sleevePresent", true, "sidePresent", false)), MediaType.APPLICATION_JSON));
        var service = new CommerceDrapeStyleReview(model, visionBuilder.build(), detectorBuilder.build(), temp.resolve("guarded"));
        var report = service.review("tenant-a", guide, scene, master, true);
        assertEquals(false, report.get("passed"));
        assertEquals(true, report.get("preservesSleeveBadge"));
        assertEquals(false, report.get("preservesSideLabel"));
        assertEquals("REJECTED", report.get("detailGuardStatus"));
        vision.verify();
        detector.verify();
    }

    @Test void localBoundaryEvidenceCorrectsFalseCropVerdictButRetainsModelsFinding() {
        var model = mock(ModelConfigService.class);
        var connection = new ModelConfigService.RuntimeModelConnection("model-id", "https://model.example",
                "vision-model", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        when(model.driverConnection("tenant-a")).thenReturn(Optional.of(connection));
        var visionBuilder = RestClient.builder().baseUrl("http://runtime");
        var vision = MockRestServiceServer.bindTo(visionBuilder).build();
        var detectorBuilder = RestClient.builder().baseUrl("http://detector");
        var detector = MockRestServiceServer.bindTo(detectorBuilder).build();
        var result = accepted();
        result.put("preservesNeckline", true);
        result.put("preservesSleeveBadge", true);
        result.put("preservesSideLabel", true);
        result.put("fullyVisible", false);
        vision.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
        detector.expect(requestTo("http://detector/drape-detail-check"))
                .andExpect(request -> assertTrue(request.getHeaders().getContentLength() > 0))
                .andRespond(withSuccess(JSON.writeValueAsString(Map.of(
                        "algorithm", "DRAPE_DETAIL_GUARD_V1", "status", "PASSED",
                        "guideSha256", CommerceImageArchive.sha(guide),
                        "masterSha256", CommerceImageArchive.sha(master),
                        "geometryFullyVisible", true,
                        "sleevePresent", true, "sidePresent", true,
                        "borderMargins", Map.of("left", 48, "right", 39, "top", 42, "bottom", 30))), MediaType.APPLICATION_JSON));
        var service = new CommerceDrapeStyleReview(model, visionBuilder.build(), detectorBuilder.build(), temp.resolve("boundary"));
        var report = service.review("tenant-a", guide, scene, master, true);
        assertEquals(true, report.get("passed"));
        assertEquals(false, report.get("modelFullyVisible"));
        assertEquals(true, report.get("fullyVisible"));
        assertEquals("LOCAL_GARMENT_BORDER_V1", report.get("visibilityEvidence"));
        vision.verify();
        detector.verify();
    }

    @Test void exactDetailEvidenceCanCorrectAFalseBadgeWithoutInventingANewNecklineVerdict() {
        var model = mock(ModelConfigService.class);
        var connection = new ModelConfigService.RuntimeModelConnection("model-id", "https://model.example",
                "vision-model", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        when(model.driverConnection("tenant-a")).thenReturn(Optional.of(connection));
        var visionBuilder = RestClient.builder().baseUrl("http://runtime");
        var vision = MockRestServiceServer.bindTo(visionBuilder).build();
        var detectorBuilder = RestClient.builder().baseUrl("http://detector");
        var detector = MockRestServiceServer.bindTo(detectorBuilder).build();
        var result = accepted();
        result.put("preservesNeckline", true);
        result.put("preservesSleeveBadge", false);
        result.put("preservesSideLabel", true);
        result.put("reason", "领口相符；袖口织标看不清");
        vision.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
        detector.expect(requestTo("http://detector/drape-detail-check"))
                .andRespond(withSuccess(JSON.writeValueAsString(Map.of(
                        "algorithm", "DRAPE_DETAIL_GUARD_V1", "status", "PASSED",
                        "guideSha256", CommerceImageArchive.sha(guide),
                        "masterSha256", CommerceImageArchive.sha(master),
                        "geometryFullyVisible", true,
                        "sleevePresent", true, "sidePresent", true,
                        "borderMargins", Map.of("left", 48, "right", 39, "top", 42, "bottom", 30))), MediaType.APPLICATION_JSON));
        var service = new CommerceDrapeStyleReview(model, visionBuilder.build(), detectorBuilder.build(), temp.resolve("details"));
        var report = service.review("tenant-a", guide, scene, master, true);
        assertEquals(true, report.get("passed"));
        assertEquals(true, report.get("preservesNeckline"));
        assertEquals(true, report.get("preservesSleeveBadge"));
        assertEquals(false, report.get("modelPreservesSleeveBadge"));
        assertEquals("领口相符；袖口织标看不清", report.get("modelReason"));
        vision.verify();
        detector.verify();
    }

    @Test void aSlowLocalDetailCheckReusesTheCompletedModelVerdictAfterRestart() throws IOException {
        var model = mock(ModelConfigService.class);
        var connection = new ModelConfigService.RuntimeModelConnection("model-id", "https://model.example",
                "vision-model", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        when(model.driverConnection("tenant-a")).thenReturn(Optional.of(connection));
        var visionBuilder = RestClient.builder().baseUrl("http://runtime");
        var vision = MockRestServiceServer.bindTo(visionBuilder).build();
        var detectorBuilder = RestClient.builder().baseUrl("http://detector");
        var detector = MockRestServiceServer.bindTo(detectorBuilder).build();
        var result = accepted();
        result.put("preservesNeckline", true);
        result.put("preservesSleeveBadge", true);
        result.put("preservesSideLabel", true);
        vision.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
        detector.expect(requestTo("http://detector/drape-detail-check"))
                .andRespond(withException(new IOException("local queue delayed")));
        detector.expect(requestTo("http://detector/drape-detail-check"))
                .andRespond(withSuccess(JSON.writeValueAsString(Map.of(
                        "algorithm", "DRAPE_DETAIL_GUARD_V1", "status", "PASSED",
                        "guideSha256", CommerceImageArchive.sha(guide),
                        "masterSha256", CommerceImageArchive.sha(master),
                        "geometryFullyVisible", true, "sleevePresent", true, "sidePresent", true,
                        "borderMargins", Map.of("left", 48, "right", 39, "top", 42, "bottom", 30))), MediaType.APPLICATION_JSON));
        var cache = temp.resolve("model-checkpoint");
        var first = new CommerceDrapeStyleReview(model, visionBuilder.build(), detectorBuilder.build(), cache);
        assertThrows(CommerceDrapeStyleReview.DetailGuardUnavailableException.class,
                () -> first.review("tenant-a", guide, scene, master, true));
        try (var files = Files.list(cache)) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("vision-")).count());
        }
        var restarted = new CommerceDrapeStyleReview(model, visionBuilder.build(), detectorBuilder.build(), cache);
        assertEquals(true, restarted.review("tenant-a", guide, scene, master, true).get("passed"));
        vision.verify();
        detector.verify();
    }

    @Test void missingNullStringsAndNumericVerdictsAreUnavailableRatherThanQualityRejections() {
        for (String field : CommerceDrapeStyleReview.CHECKS) {
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
            var failure = assertThrows(CommerceDrapeStyleReview.ReviewUnavailableException.class,
                    () -> h.service.review("tenant-a", guide, scene, master));
            assertFalse(failure.getMessage().contains("provider-body"));
            assertNull(failure.getCause());
            h.server.verify();
        }
    }

    @Test void networkReadFailureRemainsUnavailableWithNoAutomaticProviderRetry() {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withException(new IOException("upstream connection interrupted")));
        assertThrows(CommerceDrapeStyleReview.ReviewUnavailableException.class,
                () -> h.service.review("tenant-a", guide, scene, master));
        h.server.verify();
    }

    @Test void passedReviewIsAtomicallyReusedAfterAServiceRestartWithoutAnotherModelRead() throws IOException {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(accepted()), MediaType.APPLICATION_JSON));
        var first = h.service.review("tenant-a", guide, scene, master);
        assertEquals(first, h.service.review("tenant-a", guide, scene, master));
        var builder = RestClient.builder().baseUrl("http://runtime");
        var noRequests = MockRestServiceServer.bindTo(builder).build();
        var restarted = new CommerceDrapeStyleReview(h.models, builder.build(), h.cache);
        assertEquals(first, restarted.review("tenant-a", guide, scene, master));
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
        var original = h.service.review("tenant-a", guide, scene, master);
        assertNotEquals(original.get("guideSha256"),
                h.service.review("tenant-a", image(160, 160, Color.CYAN), scene, master).get("guideSha256"));
        assertNotEquals(original.get("sceneSha256"),
                h.service.review("tenant-a", guide, image(160, 80, Color.CYAN), master).get("sceneSha256"));
        assertNotEquals(original.get("masterSha256"),
                h.service.review("tenant-a", guide, scene, image(80, 160, Color.CYAN)).get("masterSha256"));
        when(h.models.driverConnection("tenant-b")).thenReturn(Optional.of(h.model));
        assertNotEquals(original.get("organizationSha256"),
                h.service.review("tenant-b", guide, scene, master).get("organizationSha256"));
        var changed = new ModelConfigService.RuntimeModelConnection("model-id", "https://model.example",
                "different-vision", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        when(h.models.driverConnection("tenant-a")).thenReturn(Optional.of(changed));
        assertNotEquals(original.get("modelFingerprint"),
                h.service.review("tenant-a", guide, scene, master).get("modelFingerprint"));
        h.server.verify();
    }

    @Test void negativeVerdictIsNotPersistedAsAnApprovedCacheEntry() throws IOException {
        var h = new Harness();
        var result = accepted();
        result.put("hasNaturalDrape", false);
        h.server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response(result), MediaType.APPLICATION_JSON));
        assertEquals(false, h.service.review("tenant-a", guide, scene, master).get("passed"));
        assertEquals(false, h.service.review("tenant-a", guide, scene, master).get("passed"));
        assertFalse(Files.exists(h.cache));
        h.server.verify();
    }

    @Test void damagedOrMismatchedCacheNeverSilentlyBecomesAnApproval() throws IOException {
        for (String damage : List.of("contractVersion", "masterSha256", "hasNaturalDrape", "not-json")) {
            var h = new Harness();
            h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                    .andRespond(withSuccess(response(accepted()), MediaType.APPLICATION_JSON));
            h.service.review("tenant-a", guide, scene, master);
            Path saved;
            try (var files = Files.list(h.cache)) { saved = files.findFirst().orElseThrow(); }
            if ("not-json".equals(damage)) Files.writeString(saved, "incomplete response");
            else {
                var report = JSON.readValue(Files.readString(saved), Map.class);
                report.put(damage, "changed");
                Files.writeString(saved, JSON.writeValueAsString(report));
            }
            assertThrows(CommerceDrapeStyleReview.ReviewUnavailableException.class,
                    () -> h.service.review("tenant-a", guide, scene, master));
            h.server.verify();
        }
    }

    @Test void absentModelOrUnreadableReferencesNeverSubmitAnAnalysis() {
        var h = new Harness();
        when(h.models.driverConnection("tenant-a")).thenReturn(Optional.empty());
        assertThrows(CommerceDrapeStyleReview.ReviewUnavailableException.class,
                () -> h.service.review("tenant-a", guide, scene, master));
        when(h.models.driverConnection("tenant-a")).thenReturn(Optional.of(h.model));
        assertThrows(IllegalArgumentException.class,
                () -> h.service.review("tenant-a", guide, new byte[]{1, 2, 3}, master));
        assertThrows(IllegalArgumentException.class,
                () -> h.service.review("", guide, scene, master));
        h.server.verify();
    }

    private void assertUnavailable(String response) {
        var h = new Harness();
        h.server.expect(requestTo("http://runtime/v1/product/analyze"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(CommerceDrapeStyleReview.ReviewUnavailableException.class,
                () -> h.service.review("tenant-a", guide, scene, master));
        h.server.verify();
    }

    private static Map<String, Object> accepted() {
        var result = new LinkedHashMap<String, Object>();
        CommerceDrapeStyleReview.CHECKS.forEach(field -> result.put(field, true));
        result.put("reason", "服装完整，背景和搭放褶皱可见。");
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
        final CommerceDrapeStyleReview service;
        final Path cache = temp.resolve(java.util.UUID.randomUUID().toString());
        final ModelConfigService.RuntimeModelConnection model = new ModelConfigService.RuntimeModelConnection("model-id", "https://model.example",
                "vision-model", "OPENAI_COMPATIBLE", "CUSTOM", "secret-test");
        Harness() {
            var builder = RestClient.builder().baseUrl("http://runtime")
                    .defaultHeader("X-Internal-Token", "test-internal");
            server = MockRestServiceServer.bindTo(builder).build();
            service = new CommerceDrapeStyleReview(models, builder.build(), cache);
            when(models.driverConnection("tenant-a")).thenReturn(Optional.of(model));
        }
    }
}
