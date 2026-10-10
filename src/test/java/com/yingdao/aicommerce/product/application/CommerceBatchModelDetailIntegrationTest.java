package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the actual artwork service and detail workflow with durable in-memory JDBC rows. */
class CommerceBatchModelDetailIntegrationTest {
    @Test void everyFrozenDetailIsGeneratedOncePerVariantRatherThanOncePerColor() throws Exception {
        var h = new Harness(2, 3, true);
        var requestKeys = new HashSet<String>();
        for (int n = 0; n < 12; n++) {
            var result = h.execute("MOCKUP");
            assertFalse(result.complete(), result.message());
            assertEquals("QUEUED", result.pendingStatus(), result.message());
        }
        assertTrue(h.execute("MOCKUP").complete());
        assertEquals(6, h.detailJobs().size(), "2 variants × 3 source details; the 2 colors must not multiply details");
        for (var job : h.detailJobs()) {
            assertNull(job.get("color_key"));
            int variant = ((Number) job.get("variant_index")).intValue();
            int index = CommerceModelDetailPolicy.index((String) job.get("kind"));
            assertTrue(requestKeys.add((String) job.get("request_key")));
            assertEquals(h.f.json.writeValueAsString(List.of(h.product(variant), h.styles.get(index - 1))), job.get("reference_keys"));
            var check = h.f.json.readTree(h.checks.get(job.get("id")));
            assertEquals("detail-" + index, check.path("sourceDetailImageId").asText());
            assertEquals(variant, check.path("variant").asInt());
            assertTrue(check.path("passed").asBoolean());
        }
        assertTrue(h.execute("REVIEW").complete());
        for (int n = 0; n < 3; n++) assertTrue(h.execute("MOCKUP").complete());
        verify(h.f.gateway, times(6)).generateProductPreview(anyString(), anyString(), eq(CommerceGrokSceneWorkflowTest.GROK), anyList(), eq(true));
        verify(h.reviewer, times(6)).review(eq("org"), any(), any(), any());
        for (int i = 1; i <= 3; i++) verify(h.f.sourceArchives, times(2)).find("capture", "detail-" + i, 1, h.f.user);
        verifyNoInteractions(h.retry);
    }

    @Test void missingOrUnreviewedFrozenDetailsBlockReviewInBothRenderModes() throws Exception {
        for (boolean local : List.of(true, false)) {
            var h = new Harness(1, 2, local);
            var missing = h.execute("REVIEW");
            assertFalse(missing.complete(), missing.message());
            assertTrue(missing.message().contains("详情"), missing.message());
            h.addDetail(1, 1, "READY");
            h.addDetail(1, 2, "READY");
            var unchecked = h.execute("REVIEW");
            assertFalse(unchecked.complete(), unchecked.message());
            assertTrue(unchecked.message().contains("检查"), unchecked.message());
            verifyNoInteractions(h.f.gateway, h.f.models, h.f.ledger, h.reviewer, h.retry);
        }
    }

    @Test void receivedResultRecoversOnlyArchivalAndReviewWithoutAnotherGeneration() throws Exception {
        var h = new Harness(1, 1, true);
        var job = h.addDetail(1, 1, "RESULT_RECEIVED");
        job.put("result_json", h.f.json.writeValueAsString(h.response));
        assertEquals("QUEUED", h.execute("MOCKUP").pendingStatus());
        assertEquals("READY", job.get("status"));
        assertEquals("QUEUED", h.execute("MOCKUP").pendingStatus());
        assertTrue(h.execute("MOCKUP").complete());
        assertTrue(h.execute("REVIEW").complete());
        verifyNoInteractions(h.f.gateway, h.f.models, h.f.ledger, h.f.sourceArchives, h.retry);
        verify(h.reviewer, times(1)).review(eq("org"), any(), any(), any());
    }

    @Test void unknownOrSubmittingDetailCannotTriggerANewPaidRequest() throws Exception {
        for (String state : List.of("UNKNOWN", "SUBMITTING")) {
            var h = new Harness(1, 1, true);
            var job = h.addDetail(1, 1, state);
            for (int n = 0; n < 3; n++) {
                var result = h.execute("MOCKUP");
                assertEquals("UNKNOWN", result.pendingStatus(), result.message());
            }
            assertEquals(state, job.get("status"));
            assertEquals(1, h.detailJobs().size());
            verifyNoInteractions(h.f.gateway, h.f.models, h.f.ledger, h.f.sourceArchives, h.reviewer, h.retry);
        }
    }

    @Test void savedCorrectionSuccessorResumesOnceAndNeverResubmitsRetiredOriginal() throws Exception {
        var h = new Harness(1, 1, true);
        var original = h.addDetail(1, 1, "RETRIED");
        original.put("id", "rejected-original");
        var successor = h.addDetail(1, 1, "PREPARED");
        successor.put("id", "saved-correction");
        successor.put("request_key", CommerceModelDetailRetry.nextKey((String) original.get("request_key")));
        successor.put("prompt", CommerceModelDetailPolicy.prompt(1) + CommerceModelDetailPolicy.CORRECTION_MARKER + "Correct the print alignment.");
        assertEquals("QUEUED", h.execute("MOCKUP").pendingStatus());
        assertEquals("QUEUED", h.execute("MOCKUP").pendingStatus());
        assertTrue(h.execute("MOCKUP").complete());
        assertTrue(h.execute("MOCKUP").complete());
        assertEquals("RETRIED", original.get("status"));
        assertEquals("READY", successor.get("status"));
        assertEquals(2, h.detailJobs().size());
        verify(h.f.gateway, times(1)).generateProductPreview(eq((String) successor.get("prompt")), eq("saved-correction"), eq(CommerceGrokSceneWorkflowTest.GROK), anyList(), eq(true));
        verify(h.f.models, never()).selectCommerceMockupRuntimeConnection(any());
        verifyNoInteractions(h.retry);
    }

    @Test void reviewTransportFailureResumesReviewWithoutRegeneratingArchivedImage() throws Exception {
        var h = new Harness(1, 1, true);
        var job = h.addDetail(1, 1, "READY");
        doThrow(new CommerceModelDetailReview.ReviewUnavailableException("temporary response failure"))
                .doAnswer(call -> h.pass(call.getArgument(1), call.getArgument(2), call.getArgument(3)))
                .when(h.reviewer).review(eq("org"), any(), any(), any());
        assertEquals("QUEUED", h.execute("MOCKUP").pendingStatus());
        assertEquals("QUEUED", h.execute("MOCKUP").pendingStatus(), "backoff turn only waits");
        var pending = h.f.json.readTree(h.checks.get(job.get("id")));
        ((tools.jackson.databind.node.ObjectNode) pending).put("nextAttemptAt", 0);
        h.checks.put((String) job.get("id"), pending.toString());
        assertEquals("QUEUED", h.execute("MOCKUP").pendingStatus());
        assertTrue(h.execute("MOCKUP").complete());
        verify(h.reviewer, times(2)).review(eq("org"), any(), any(), any());
        verifyNoInteractions(h.f.gateway, h.f.models, h.f.ledger, h.retry);
        assertEquals(1, h.detailJobs().size());
    }

    static final class Harness {
        final CommerceBatchArtworkSurfaceTest f = new CommerceBatchArtworkSurfaceTest();
        final CommerceModelDetailReview reviewer = mock(CommerceModelDetailReview.class);
        final CommerceModelDetailRetry retry = mock(CommerceModelDetailRetry.class);
        final List<Map<String,Object>> jobs = new ArrayList<>();
        final Map<String,byte[]> assets = new HashMap<>();
        final Map<String,String> checks = new HashMap<>();
        final List<String> styles = new ArrayList<>();
        final List<CommerceSourceColorResolver.Color> colors = List.of(
                new CommerceSourceColorResolver.Color("black", "Black", List.of(), "black-image", true),
                new CommerceSourceColorResolver.Color("blue", "Blue", List.of(), "blue-image", true));
        final CommerceDesignImageGateway.Response response;
        final Selection source;
        final String output;

        Harness(int variants, int details, boolean local) throws Exception {
            var config = new CommercePrintTemplateService.Configuration("T_SHIRT", List.of(new CommercePrintTemplateService.Area("front", "FRONT", .3, .3, .3, .3, 240, 240, 300)), Map.of("FRONT", "b".repeat(64)));
            f.configure(config, jobs);
            var ids = new ArrayList<String>();
            var images = new ArrayList<ProductCaptureImage>();
            for (String id : List.of("image", "black-image", "blue-image")) images.add(image(id));
            for (int i = 1; i <= details; i++) {
                String id = "detail-" + i; ids.add(id); images.add(image(id));
                String style = store(SavedArtworkFixture.image(0xff005500 + i, false)); styles.add(style);
                when(f.sourceArchives.find("capture", id, 1, f.user)).thenReturn(style);
            }
            source = new Selection("capture", 1, "image", List.of(), colors, ids);
            var plan = new Plan("fixed-snapshot", List.of(source), variants, null, "SOURCE_PRODUCT", "SOURCE_SKU_COLORS", local ? LOCAL_PHOTO_POLICY : null);
            when(f.plans.forExecution("org", "run")).thenReturn(new CommerceBatchCreativePlanMapper.Row("plan", "org", "batch", 1, f.json.writeValueAsString(plan), "u", Instant.now()));
            var capture = f.captures.get("capture", f.user).capture();
            when(f.captures.get("capture", f.user)).thenReturn(new ProductCaptureService.CaptureDetail(capture, List.of(), images, List.of(), Map.of(), Map.of()));
            for (var color : colors) add("BASE_COLOR", 0, color.key(), store(SavedArtworkFixture.image(color.key().equals("black") ? 0xff222222 : 0xff0000aa, false)));
            for (int v = 1; v <= variants; v++) {
                String matte = store(SavedArtworkFixture.image(0xffcc0000 + v, true));
                add("MATTE", v, null, matte);
                // Legacy AI mockups require their frozen transparent variant too.
                if (!local) add("VARIATE", v, null, matte);
                for (var color : colors) {
                    var base = jobs.stream().filter(j -> "BASE_COLOR".equals(j.get("kind")) && color.key().equals(j.get("color_key"))).findFirst().orElseThrow();
                    var main = add("MOCKUP", v, color.key(), store(SavedArtworkFixture.image(0xff220000 + v * 100 + color.key().length(), false)));
                    main.put("reference_keys", f.json.writeValueAsString(List.of(base.get("storage_key"), matte)));
                    main.put("model_config_id", LOCAL_PHOTO_POLICY);
                    String report = f.json.writeValueAsString(Map.of("status", "LOCAL_PHOTO_REVIEW_REQUIRED", "method", LOCAL_PHOTO_POLICY,
                            "colorKey", color.key(), "colorLabel", color.label(), "baseKey", base.get("storage_key"), "artworkKeys", Map.of("front", matte),
                            "photoCheck", Map.of("mappedPixels", 100, "protectedChangedPixels", 0)));
                    main.put("result_json", report); checks.put((String) main.get("id"), report);
                }
            }
            byte[] png = SavedArtworkFixture.image(0xffaabbcc, false); output = store(png);
            response = new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(png), "image/png", "grok", "detail", null), null);
            when(f.archive.read(eq("org"), anyString())).thenAnswer(call -> {
                byte[] bytes = assets.get((String) call.getArgument(1));
                if (bytes == null) throw new IllegalArgumentException("missing archive " + call.getArgument(1));
                return SurfaceTemplateFixture.reference(bytes);
            });
            when(f.archive.store(eq("org"), any())).thenAnswer(call -> {
                byte[] bytes = call.getArgument(1); String key = store(bytes);
                return new CommerceImageArchive.Archived(key, key.substring(65), "image/png", bytes.length);
            });
            when(f.db.queryForList(startsWith("SELECT id,capture_id,"), eq("run"), eq("org"))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                return jobs.stream().filter(j -> !"RETRIED".equals(j.get("status"))).map(j -> {
                    Map<String,Object> projected = new LinkedHashMap<>(j);
                    if (!sql.substring(0, sql.indexOf(" FROM ")).contains("capture_version")) projected.remove("capture_version");
                    return projected;
                }).toList();
            });
            when(f.db.queryForList(startsWith("SELECT c.report_json FROM commerce_batch_artwork_checks"), anyString(), eq("org")))
                    .thenAnswer(call -> reports(call.getArgument(1)));
            when(f.db.queryForList(eq("SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id=?"), anyString()))
                    .thenAnswer(call -> reports(call.getArgument(1)));
            when(f.db.queryForList(eq("SELECT result_json FROM commerce_batch_artwork_jobs WHERE id=? AND organization_id=?"), anyString(), eq("org")))
                    .thenAnswer(call -> List.of(Map.of("result_json", byId(call.getArgument(1)).get("result_json"))));
            when(f.db.queryForObject(startsWith("SELECT COUNT(*)>0 FROM commerce_batch_creation_modes"), eq(Boolean.class), eq("batch"), eq("org"))).thenReturn(true);
            when(f.db.queryForMap(eq("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND request_key=?"), eq("run"), anyString()))
                    .thenAnswer(call -> jobs.stream().filter(j -> call.getArgument(2).equals(j.get("request_key"))).findFirst().orElseThrow());
            when(f.db.queryForMap(eq("SELECT * FROM commerce_batch_artwork_jobs WHERE id=?"), anyString())).thenAnswer(call -> byId(call.getArgument(1)));
            doAnswer(call -> {
                var job = new LinkedHashMap<String,Object>();
                String[] names = {"id", "organization_id", "execution_id", "capture_id", "capture_version", "kind", "variant_index", "request_key", "prompt", "model_config_id", "model_fingerprint", "reference_keys"};
                for (int i = 0; i < names.length; i++) job.put(names[i], call.getArgument(i + 1));
                job.put("status", "PREPARED"); jobs.add(job); return 1;
            }).when(f.db).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_jobs"), any(Object[].class));
            doAnswer(call -> {var j = byId(call.getArgument(2)); j.put("status", "RESULT_RECEIVED"); j.put("result_json", call.getArgument(1)); return 1;})
                    .when(f.db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='RESULT_RECEIVED'"), any(Object[].class));
            doAnswer(call -> {var j = byId(call.getArgument(4)); j.put("status", "READY"); j.put("storage_key", call.getArgument(1)); j.put("content_sha256", call.getArgument(2)); return 1;})
                    .when(f.db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='READY'"), any(Object[].class));
            doAnswer(call -> {checks.put(call.getArgument(2), call.getArgument(1)); return 1;})
                    .when(f.db).update(startsWith("INSERT INTO commerce_batch_artwork_checks"), any(Object[].class));
            when(f.models.selectCommerceMockupRuntimeConnection(f.user)).thenReturn(Optional.of(CommerceGrokSceneWorkflowTest.GROK));
            when(f.models.commerceMockupConnectionById("org", "grok")).thenReturn(Optional.of(CommerceGrokSceneWorkflowTest.GROK));
            when(f.ledger.claim(any(), anyString(), anyString())).thenAnswer(call -> {byId(call.getArgument(1)).put("status", "SUBMITTING"); return "CLAIMED";});
            when(f.gateway.generateProductPreview(anyString(), anyString(), eq(CommerceGrokSceneWorkflowTest.GROK), anyList(), eq(true))).thenReturn(response);
            when(reviewer.review(eq("org"), any(), any(), any())).thenAnswer(call -> pass(call.getArgument(1), call.getArgument(2), call.getArgument(3)));
            ReflectionTestUtils.setField(f.service, "modelDetails", new CommerceModelDetailWorkflow(f.db, f.archive, f.json, reviewer, retry));
        }
        CommerceBatchStageHandler.Result execute(String stage) {return f.service.execute(f.stage(stage), f.user);}
        List<Map<String,Object>> detailJobs() {return jobs.stream().filter(j -> CommerceModelDetailPolicy.isKind((String) j.get("kind"))).toList();}
        List<Map<String,Object>> reports(String id) {return checks.containsKey(id) ? List.of(Map.of("report_json", checks.get(id))) : List.of();}
        Map<String,Object> byId(String id) {return jobs.stream().filter(j -> id.equals(j.get("id"))).findFirst().orElseThrow();}
        String product(int variant) {return CommerceModelDetailWorkflow.productKey(source, variant, jobs);}
        String store(byte[] bytes) {String key = CommerceSurfaceTemplateInputs.key("org", CommerceImageArchive.sha(bytes)); assets.put(key, bytes); return key;}
        ProductCaptureImage image(String id) {var image = mock(ProductCaptureImage.class); when(image.getId()).thenReturn(id); return image;}
        Map<String,Object> add(String kind, int variant, String color, String key) {
            var job = new LinkedHashMap<String,Object>(); job.put("id", kind + "-" + variant + "-" + color); job.put("capture_id", "capture"); job.put("capture_version", 1L);
            job.put("kind", kind); job.put("variant_index", variant); job.put("color_key", color); job.put("status", "READY"); job.put("storage_key", key); job.put("content_sha256", key.substring(65));
            job.put("reference_keys", "[]"); job.put("execution_id", "run"); jobs.add(job); return job;
        }
        Map<String,Object> addDetail(int variant, int index, String state) {
            String kind = CommerceModelDetailPolicy.kind(index); var job = add(kind, variant, null, output);
            job.put("status", state); job.put("prompt", CommerceModelDetailPolicy.prompt(index)); job.put("model_config_id", "grok");
            job.put("model_fingerprint", CommerceBatchArtworkService.fingerprint(CommerceGrokSceneWorkflowTest.GROK));
            job.put("request_key", CommerceBatchArtworkService.requestKey("run", "capture", kind, variant, null));
            job.put("reference_keys", f.json.writeValueAsString(List.of(product(variant), styles.get(index - 1)))); return job;
        }
        Map<String,Object> pass(byte[] product, byte[] style, byte[] output) {
            var report = new LinkedHashMap<String,Object>(); CommerceModelDetailReview.CHECKS.forEach(flag -> report.put(flag, true));
            report.put("passed", true); report.put("reason", "The actual new garment and print are worn naturally."); report.put("contractVersion", CommerceModelDetailPolicy.PROMPT_VERSION);
            report.put("productSha256", CommerceImageArchive.sha(product)); report.put("styleSha256", CommerceImageArchive.sha(style)); report.put("outputSha256", CommerceImageArchive.sha(output)); return report;
        }
    }
}
