package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The actual artwork service creates a grid request, archives it and locally materializes detail rows. */
class CommerceBatchDetailGridIntegrationTest {
    private static final int[] OUTPUT_COLORS = {0xffab2314, 0xff159357, 0xff204baa, 0xffc49014};

    @Test void twoVariantsGenerateTwoGridsWithTwoReferencesAndEightIndependentDetailsAcrossBothColors() throws Exception {
        var h = new Harness(2, 4);
        var existing = h.snapshotReady();
        h.finish();

        assertEquals(2, h.grids().size(), "Two garment colors share each variant's detail grid");
        assertEquals(8, h.base.detailJobs().size());
        assertEquals(2, h.sent.size());
        assertEquals(existing, h.snapshotKeys(existing.keySet()), "Existing products and prints must remain immutable");
        for (int variant = 1; variant <= 2; variant++) {
            final int v = variant;
            var parent = h.grids().stream().filter(j -> ((Number) j.get("variant_index")).intValue() == v).findFirst().orElseThrow();
            assertNull(parent.get("color_key"));
            assertEquals(List.of(1, 2, 3, 4), CommerceModelDetailGridPolicy.indices((String) parent.get("prompt")));
            var sent = h.sent.get((String) parent.get("id"));
            assertEquals(2, sent.size());
            var product = ImageIO.read(new ByteArrayInputStream(h.base.assets.get(h.base.product(v))));
            var identity = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(sent.getFirst().dataBase64())));
            assertEquals(1536, identity.getWidth()); assertEquals(1536, identity.getHeight());
            for(int slot=0;slot<4;slot++)assertEquals(product.getRGB(product.getWidth()/2,product.getHeight()/2),identity.getRGB((slot%2)*768+384,(slot/2)*768+384));
            h.assertInputCells(sent.get(1), List.of(1, 2, 3, 4));
            var grid = ImageIO.read(new ByteArrayInputStream(h.base.assets.get(parent.get("storage_key"))));
            assertEquals(2048, grid.getWidth()); assertEquals(2048, grid.getHeight());
            var check = h.base.f.json.readTree(h.base.checks.get(parent.get("id"))).path("gridSplit");
            assertEquals("DONE", check.path("status").asText()); assertEquals(4, check.path("childCount").asInt());
            for (int index = 1; index <= 4; index++) h.assertChild(v, index, index - 1, parent);
        }
        assertTrue(h.base.execute("REVIEW").complete());
        for (int n = 0; n < 3; n++) assertTrue(h.base.execute("MOCKUP").complete());
        verify(h.base.f.gateway, times(2)).generateProductDetailGrid(anyString(), anyString(), eq(CommerceGrokSceneWorkflowTest.GROK), anyList());
        verify(h.base.f.gateway, never()).generateProductPreview(anyString(), anyString(), any(), anyList(), anyBoolean());
        verify(h.base.reviewer, times(8)).review(eq("org"), any(), any(), any());
        verifyNoInteractions(h.base.retry);
        for (int index = 1; index <= 4; index++) verify(h.base.f.sourceArchives, times(2)).find("capture", "detail-" + index, 1, h.base.f.user);
    }

    @Test void terminalSeventhDetailAndMissingEighthAndNinthResumeAsOnePartialGridWithoutTouchingSixSuccesses() throws Exception {
        var h = new Harness(1, 9);
        for (int index = 1; index <= 6; index++) h.approve(h.base.addDetail(1, index, "READY"), index);
        var original = h.base.addDetail(1, 7, "FAILED");
        h.base.checks.put((String) original.get("id"), h.base.f.json.writeValueAsString(Map.of("generationRecovery",
                Map.of("detailRetryEligible", true, "terminalOperation", true, "reasonCode", "NETWORK_EXHAUSTED"))));
        String originalRequest = (String) original.get("request_key");
        var successful = h.snapshotReady();
        h.finish();

        assertEquals(1, h.grids().size());
        var parent = h.grids().getFirst();
        assertEquals(List.of(7, 8, 9), CommerceModelDetailGridPolicy.indices((String) parent.get("prompt")));
        h.assertInputCells(h.sent.get((String) parent.get("id")).get(1), List.of(7, 8, 9));
        assertEquals("RETRIED", original.get("status")); assertEquals(originalRequest, original.get("request_key"));
        assertEquals(successful, h.snapshotKeys(successful.keySet()));
        assertEquals(9, h.base.detailJobs().stream().filter(j -> "READY".equals(j.get("status"))).count());
        for (int index = 7; index <= 9; index++) h.assertChild(1, index, index - 7, parent);
        assertTrue(h.base.execute("REVIEW").complete());
        for (int n = 0; n < 3; n++) assertTrue(h.base.execute("MOCKUP").complete());
        verify(h.base.f.gateway, times(1)).generateProductDetailGrid(anyString(), anyString(), eq(CommerceGrokSceneWorkflowTest.GROK), anyList());
        verify(h.base.f.gateway, never()).generateProductPreview(anyString(), anyString(), any(), anyList(), anyBoolean());
        verify(h.base.reviewer, times(3)).review(eq("org"), any(), any(), any());
        for (int index = 1; index <= 7; index++) verify(h.base.f.sourceArchives, never()).find("capture", "detail-" + index, 1, h.base.f.user);
        for (int index = 8; index <= 9; index++) verify(h.base.f.sourceArchives).find("capture", "detail-" + index, 1, h.base.f.user);
    }

    @Test void policyRejectionWithGatewayStatusDoesNotQueueAnotherGrid() throws Exception {
        assertPermanentGatewayRejection("POLICY");
    }

    @Test void copyrightRejectionWithGatewayStatusDoesNotQueueAnotherGrid() throws Exception {
        assertPermanentGatewayRejection("COPYRIGHT");
    }

    private void assertPermanentGatewayRejection(String kind) throws Exception {
        for (int providerStatus : List.of(502, 504)) {
            var h = new Harness(1, 4);
            when(h.base.f.gateway.generateProductDetailGrid(anyString(), anyString(), eq(CommerceGrokSceneWorkflowTest.GROK), anyList()))
                    .thenThrow(new CommerceImageRejectedException("服务商已明确拒绝", kind, providerStatus));
            for (int turn = 0; turn < 3; turn++) {
                var result = h.base.execute("MOCKUP");
                assertEquals("FAILED", result.pendingStatus(), result.message());
            }
            assertEquals(1, h.grids().size()); assertTrue(h.base.detailJobs().isEmpty());
            var rejected = h.grids().getFirst();
            assertEquals("REJECTED", rejected.get("status"));
            var report = h.base.f.json.readTree(h.base.checks.getOrDefault(rejected.get("id"), "{}"));
            assertFalse(report.path("generationRecovery").path("detailRetryEligible").asBoolean(false));
            var saved = h.base.f.json.readTree((String) rejected.get("result_json"));
            assertEquals(kind, saved.path("failureKind").asText()); assertEquals(providerStatus, saved.path("providerStatus").asInt());
            verify(h.base.f.gateway, times(1)).generateProductDetailGrid(anyString(), anyString(), eq(CommerceGrokSceneWorkflowTest.GROK), anyList());
            verifyNoInteractions(h.base.reviewer, h.base.retry);
        }
    }

    private static final class Harness {
        final CommerceBatchModelDetailIntegrationTest.Harness base;
        final Map<String,List<CommerceDesignImageGateway.Reference>> sent = new LinkedHashMap<>();

        Harness(int variants, int details) throws Exception {
            base = new CommerceBatchModelDetailIntegrationTest.Harness(variants, details, true);
            var f = base.f;
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            var review = new CommerceModelDetailWorkflow(f.db, f.archive, f.json, base.reviewer, base.retry);
            ReflectionTestUtils.setField(f.service, "modelDetailGrids",
                    new CommerceModelDetailGridWorkflow(f.db, f.archive, f.json, new TransactionTemplate(manager), review));
            when(f.db.queryForList(startsWith("SELECT id FROM commerce_batch_executions"), any(Object[].class)))
                    .thenReturn(List.of(Map.of("id", "run")));
            when(f.db.queryForMap(eq("SELECT * FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? FOR UPDATE"), anyString(), eq("run"), eq("org")))
                    .thenAnswer(call -> base.byId(call.getArgument(1)));
            when(f.db.queryForList(eq("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? FOR UPDATE"), eq("run"), eq("org")))
                    .thenAnswer(call -> base.jobs);
            when(f.db.queryForObject(startsWith("SELECT COUNT(*) FROM commerce_batch_artwork_jobs"), eq(Integer.class), any(Object[].class)))
                    .thenAnswer(call -> (int) grids().stream().filter(j -> Objects.equals(j.get("kind"), call.getArgument(6)) && Objects.equals(j.get("variant_index"), call.getArgument(7))).count());
            doAnswer(call -> {
                String[] names = {"id", "organization_id", "execution_id", "capture_id", "capture_version", "kind", "variant_index", "request_key", "prompt", "model_config_id", "model_fingerprint", "reference_keys", "result_json", "storage_key", "content_sha256", "message"};
                var child = new LinkedHashMap<String,Object>();
                for (int i = 0; i < names.length; i++) child.put(names[i], call.getArgument(i + 1));
                child.put("status", "READY"); base.jobs.add(child); return 1;
            }).when(f.db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs\n"), any(Object[].class));
            doAnswer(call -> {base.checks.put(call.getArgument(1), call.getArgument(2)); return 1;})
                    .when(f.db).update(startsWith("INSERT INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES"), any(Object[].class));
            doAnswer(call -> {base.byId(call.getArgument(1)).put("status", "RETRIED"); return 1;})
                    .when(f.db).update(eq("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND status='FAILED'"), any(Object[].class));
            doAnswer(call -> {
                var job = base.byId(call.getArgument(3)); job.put("status", "REJECTED");
                job.put("message", call.getArgument(1)); job.put("result_json", call.getArgument(2)); return 1;
            }).when(f.db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='REJECTED',message=?,result_json=?"), any(Object[].class));
            var response = new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(
                    Base64.getEncoder().encodeToString(generatedGrid()), "image/png", "grok", "detail-grid", null), null);
            when(f.gateway.generateProductDetailGrid(anyString(), anyString(), eq(CommerceGrokSceneWorkflowTest.GROK), anyList()))
                    .thenAnswer(call -> {sent.put(call.getArgument(1), List.copyOf(call.getArgument(3))); return response;});
        }

        void finish() {
            for (int turn = 0; turn < 40; turn++) {
                var result = base.execute("MOCKUP");
                if (result.complete()) return;
                assertEquals("QUEUED", result.pendingStatus(), result.message());
            }
            fail("Grid generation, splitting and reviews must reach completion");
        }
        List<Map<String,Object>> grids() {return base.jobs.stream().filter(j -> CommerceModelDetailGridPolicy.isKind((String) j.get("kind"))).toList();}
        Map<String,String> snapshotReady() {
            var out = new LinkedHashMap<String,String>();
            for (var job : base.jobs) if ("READY".equals(job.get("status"))) out.put((String) job.get("id"), (String) job.get("storage_key"));
            return out;
        }
        Map<String,String> snapshotKeys(Set<String> ids) {
            var out = new LinkedHashMap<String,String>();
            for (String id : ids) out.put(id, (String) base.byId(id).get("storage_key"));
            return out;
        }
        void approve(Map<String,Object> job, int index) {
            var report = base.pass(base.assets.get(base.product(1)), base.assets.get(base.styles.get(index - 1)), base.assets.get(job.get("storage_key")));
            report.put("captureId", "capture"); report.put("variant", 1); report.put("detailIndex", index); report.put("sourceDetailImageId", "detail-" + index);
            base.checks.put((String) job.get("id"), base.f.json.writeValueAsString(report));
        }
        void assertInputCells(CommerceDesignImageGateway.Reference reference, List<Integer> indices) throws Exception {
            var grid = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(reference.dataBase64())));
            assertEquals(CommerceModelDetailGridComposer.GRID_SIZE, grid.getWidth()); assertEquals(grid.getWidth(), grid.getHeight());
            int size = grid.getWidth() / 2;
            for (int slot = 0; slot < 4; slot++) {
                int expected = slot < indices.size() ? 0xff005500 + indices.get(slot) : Color.WHITE.getRGB();
                assertEquals(expected, grid.getRGB((slot % 2) * size + size / 2, (slot / 2) * size + size / 2));
            }
        }
        void assertChild(int variant, int index, int slot, Map<String,Object> parent) throws Exception {
            var child = base.detailJobs().stream().filter(j -> "READY".equals(j.get("status")) && ((Number) j.get("variant_index")).intValue() == variant
                    && CommerceModelDetailPolicy.kind(index).equals(j.get("kind"))).findFirst().orElseThrow();
            assertNull(child.get("color_key"));
            var image = ImageIO.read(new ByteArrayInputStream(base.assets.get(child.get("storage_key"))));
            assertEquals(1024, image.getWidth()); assertEquals(1024, image.getHeight());
            assertEquals(OUTPUT_COLORS[slot], image.getRGB(512, 512));
            var origin = base.f.json.readTree((String) child.get("result_json"));
            assertEquals(parent.get("id"), origin.path("parentJobId").asText()); assertEquals(slot, origin.path("slot").asInt());
            assertEquals("detail-" + index, origin.path("sourceDetailImageId").asText());
            assertEquals(base.f.json.writeValueAsString(List.of(base.product(variant), base.styles.get(index - 1))), child.get("reference_keys"));
            assertTrue(base.f.json.readTree(base.checks.get(child.get("id"))).path("passed").asBoolean());
        }
    }

    private static byte[] generatedGrid() throws Exception {
        var image = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            for (int slot = 0; slot < 4; slot++) {
                graphics.setColor(new Color(OUTPUT_COLORS[slot]));
                graphics.fillRect((slot % 2) * 1024, (slot / 2) * 1024, 1024, 1024);
            }
            var bytes = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, "png", bytes)); return bytes.toByteArray();
        } finally {graphics.dispose(); image.flush();}
    }
}
