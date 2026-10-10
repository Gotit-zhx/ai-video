package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceBatchArtworkColorTest {
    final CommerceBatchArtworkSurfaceTest f = new CommerceBatchArtworkSurfaceTest();
    final CommerceSourceColorResolver.Color red = color("red", "红色", "red-image");
    final CommerceSourceColorResolver.Color blue = color("blue", "蓝色", "blue-image");

    @Test void eachStyleAndColorGetsOneFrontPhotoAndReusesAllArtworkGroups() throws Exception {
        var jobs = sourceJobs(2);
        configureSource(jobs, List.of(red, blue), 2);
        assertTrue(f.service.execute(f.stage("VARIATE"), f.user).complete(), "colors must not multiply design variants");
        var keys = new HashSet<String>();
        var guides = new HashMap<Integer, String>();
        for (int variant = 1; variant <= 2; variant++) for (var color : List.of(red, blue)) {
            clearInvocations(f.db, f.models, f.archive, f.sourceArchives);
            assertEquals("UNKNOWN", f.service.execute(f.run, f.user).pendingStatus());
            Object[] args = f.insertedJob();
            assertEquals("MOCKUP_FRONT", args[6], "FRONT is preferred even though the template also contains a BACK print");
            assertEquals(variant, args[7]);
            assertTrue(keys.add((String) args[8]), "colors and styles need independent durable request keys");
            assertEquals(color.key(), args[13]);
            assertEquals(color.label(), args[14]);
            var refs = f.json.readTree((String) args[12]);
            assertEquals(4, refs.size());
            assertEquals(key('a'), refs.get(0).asText());
            assertEquals(key('b'), refs.get(1).asText());
            assertEquals(color == red ? key('6') : key('9'), refs.get(3).asText());
            String priorGuide = guides.putIfAbsent(variant, refs.get(2).asText());
            if (priorGuide != null) assertEquals(priorGuide, refs.get(2).asText(), "a style's exact placement/design must be shared across colors");
            String prompt = (String) args[9];
            assertTrue(prompt.contains("参考图4仅作为衣服底色的色样"));
            assertTrue(prompt.contains("不改变印花自身配色"));
            assertTrue(prompt.contains("仅输出当前颜色的一张商品图"));
            assertFalse(prompt.contains("参考图4是本次裂变的新印花"));
            jobs.add(mockup("MOCKUP_FRONT", variant, color, "READY", refs.toString()));
        }
        clearInvocations(f.models, f.gateway, f.ledger);
        assertTrue(f.service.execute(f.run, f.user).complete());
        assertEquals(4, keys.size());
        verifyNoInteractions(f.models, f.gateway, f.ledger);
    }

    @Test void completedColorDoesNotHideAnotherColorsPendingOrRejectedJob() throws Exception {
        var jobs = sourceJobs(1);
        jobs.add(mockup("MOCKUP_FRONT", 1, red, "READY", "[]"));
        var pending = mockup("MOCKUP_FRONT", 1, blue, "UNKNOWN", "[]");
        jobs.add(pending);
        configureSource(jobs, List.of(red, blue), 1);
        clearInvocations(f.models, f.gateway, f.ledger, f.archive);
        assertEquals("UNKNOWN", f.service.execute(f.run, f.user).pendingStatus());
        pending.put("status", "REJECTED");
        pending.put("message", "explicit rejection");
        assertEquals("FAILED", f.service.execute(f.run, f.user).pendingStatus());
        verifyNoInteractions(f.models, f.gateway, f.ledger);
        verify(f.archive, never()).store(anyString(), any());
    }

    @Test void reviewChecksOnlyTheSelectedFaceForEveryColorAndLabelsAuthorizedColorDifferences() throws Exception {
        var jobs = sourceJobs(1);
        jobs.add(mockup("MOCKUP_FRONT", 1, red, "READY", "[]"));
        jobs.add(mockup("MOCKUP_FRONT", 1, blue, "READY", "[]"));
        configureSource(jobs, List.of(red, blue), 1);
        when(f.db.queryForObject(startsWith("SELECT COUNT(*)>0 FROM commerce_batch_creation_modes"), eq(Boolean.class), eq("batch"), eq("org"))).thenReturn(true);
        for (var color : List.of(red, blue)) {
            clearInvocations(f.db);
            var result = f.service.execute(f.stage("REVIEW"), f.user);
            assertEquals("QUEUED", result.pendingStatus());
            var insert = mockingDetails(f.db).getInvocations().stream().filter(i -> i.getMethod().getName().equals("update")
                    && ((String) i.getArgument(0)).contains("commerce_batch_artwork_checks")).findFirst().orElseThrow();
            String id = insert.getArgument(1);
            assertEquals("MOCKUP_FRONT-1-" + color.key(), id);
            var report = f.json.readTree((String) insert.getArgument(2));
            assertEquals("SURFACE_COLOR_REVIEW_REQUIRED", report.path("status").asText());
            assertTrue(report.path("limitation").asText().contains("已授权按 SKU 改变衣服底色"));
            assertFalse(report.path("productionReady").asBoolean());
            when(f.db.queryForList("SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id=?", id))
                    .thenReturn(List.of(Map.of("report_json", report.toString())));
        }
        assertTrue(f.service.execute(f.stage("REVIEW"), f.user).complete(), "BACK was never requested and must not block review");
        verifyNoInteractions(f.gateway, f.ledger);
    }

    @Test void absentOrUnreadableFrozenColorReferencesBlockBeforeAProviderSubmission() throws Exception {
        configureSource(sourceJobs(1), List.of(red, color("missing", "绿色", "not-in-capture")), 1);
        // The source list is a snapshot of actual capture image IDs, not one inferred from color labels.
        var detail = f.captures.get("capture", f.user);
        var actualImages = detail.images().stream().filter(i -> !i.getId().equals("not-in-capture")).toList();
        var actualDetail = new ProductCaptureService.CaptureDetail(detail.capture(), List.of(), actualImages, List.of(), Map.of(), Map.of());
        when(f.captures.get("capture", f.user)).thenReturn(actualDetail);
        clearInvocations(f.models, f.archive);
        var invalid = f.service.execute(f.stage("EXTRACT"), f.user);
        assertTrue(invalid.message().contains("缺少本商品可读取"));
        verifyNoInteractions(f.models, f.gateway, f.ledger);
        verify(f.archive, never()).store(anyString(), any());

        configurePlan(List.of(red), 1, "SOURCE_PRODUCT");
        when(f.sourceArchives.find("capture", "red-image", 1, f.user)).thenThrow(new IllegalStateException("archive unreadable"));
        clearInvocations(f.db);
        var unreadable = f.service.execute(f.run, f.user);
        assertTrue(unreadable.message().contains("未提交模型"));
        verify(f.db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(f.gateway, f.ledger);
    }

    @Test void backOnlyTemplateUsesItsActualPrintedSideAndDefaultColorUsesTheSelectedSourcePhoto() throws Exception {
        var defaultColor = new CommerceSourceColorResolver.Color("default", "原商品颜色", List.of(), "image", true);
        f.configure(SurfaceTemplateFixture.configuration(), List.of(f.uploaded(), f.variant()));
        configurePlan(List.of(defaultColor), 1, "TEMPLATE_ARTWORK");
        setupModelAndNewJob();
        when(f.sourceArchives.find("capture", "image", 1, f.user)).thenReturn(key('6'));
        assertEquals("UNKNOWN", f.service.execute(f.run, f.user).pendingStatus());
        var args = f.insertedJob();
        assertEquals("MOCKUP_BACK", args[6]);
        assertEquals("default", args[13]);
        var refs = f.json.readTree((String) args[12]);
        assertEquals(4, refs.size());
        assertEquals(key('6'), refs.get(3).asText());
        assertNotEquals(f.variant().get("storage_key"), refs.get(3).asText(), "the guide carries the replacement artwork and the fourth input is the color photo");
    }

    @Test void legacyNullColorKeysKeepTheOriginalRequestIdentityAndCannotCollideWithColorOrVariantKeys() {
        String old = CommerceImageArchive.sha("run:capture:MOCKUP_FRONT:1".getBytes(StandardCharsets.UTF_8));
        assertEquals(old, CommerceBatchArtworkService.requestKey("run", "capture", "MOCKUP_FRONT", 1, null));
        var distinct = new HashSet<>(List.of(old,
                CommerceBatchArtworkService.requestKey("run", "capture", "MOCKUP_FRONT", 1, "red"),
                CommerceBatchArtworkService.requestKey("run", "capture", "MOCKUP_FRONT", 1, "blue"),
                CommerceBatchArtworkService.requestKey("run", "capture", "MOCKUP_FRONT", 2, "red")));
        assertEquals(4, distinct.size());
    }

    @Test void twoDimensionalColorOutputUsesBaseGuideColorOrderAndKeepsAuthorizedRecoloringDuringArchive() throws Exception {
        var config = new CommercePrintTemplateService.Configuration("T_SHIRT", List.of(new CommercePrintTemplateService.Area("front", "FRONT", .3, .3, .3, .3, 240, 240, 300)), Map.of("FRONT", SurfaceTemplateFixture.hash('b')));
        var jobs = new ArrayList<Map<String,Object>>(List.of(f.sourceArtwork(), f.variant()));
        f.configure(config, jobs);
        configurePlan(List.of(red), 1, "SOURCE_PRODUCT");
        setupModelAndNewJob();
        when(f.sourceArchives.find("capture", "red-image", 1, f.user)).thenReturn(key('6'));
        assertEquals("UNKNOWN", f.service.execute(f.run, f.user).pendingStatus());
        Object[] args = f.insertedJob();
        assertEquals("MOCKUP", args[6]);
        var refs = f.json.readTree((String) args[12]);
        assertEquals(3, refs.size());assertEquals(key('b'), refs.get(0).asText());assertEquals(key('6'), refs.get(2).asText());

        byte[] recolored = CommerceSurfaceMockupComposerTest.png(new int[][]{{0xffff0000, 0xffff0000}, {0xffff0000, 0xffff0000}});
        var response = new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(
                Base64.getEncoder().encodeToString(recolored), "image/png", "test-model", "image", null), null);
        var received = mockup("MOCKUP", 1, red, "RESULT_RECEIVED", refs.toString());
        received.put("execution_id", "run");received.put("result_json", f.json.writeValueAsString(response));jobs.add(received);
        clearInvocations(f.archive, f.db);
        var collected = f.service.execute(f.run, f.user);
        assertEquals("QUEUED", collected.pendingStatus());
        var stores = mockingDetails(f.archive).getInvocations().stream().filter(i -> i.getMethod().getName().equals("store")).toList();
        assertEquals(2, stores.size());
        assertArrayEquals(recolored, (byte[]) stores.getLast().getArgument(1), "old mask preservation must not paint the garment back to its previous color");
        verifyNoInteractions(f.gateway, f.ledger);
        var diagnostic = CommerceBatchArtworkService.colorReport(new CommerceMockupPositionCheck.Report("OUTSIDE_CHANGE_REVIEW", 99, 100, .99, 40, 10, false, "test", null));
        assertEquals("COLOR_REVIEW_REQUIRED", diagnostic.status());assertFalse(diagnostic.productionReady());
    }

    private void configureSource(List<Map<String,Object>> jobs, List<CommerceSourceColorResolver.Color> colors, int variants) throws Exception {
        f.configureSourceProduct(jobs);
        configurePlan(colors, variants, "SOURCE_PRODUCT");
        when(f.sourceArchives.find("capture", "red-image", 1, f.user)).thenReturn(key('6'));
        when(f.sourceArchives.find("capture", "blue-image", 1, f.user)).thenReturn(key('9'));
    }

    private void configurePlan(List<CommerceSourceColorResolver.Color> colors, int variants, String artworkSource) {
        var selected = new CommerceBatchCreativePlanValidator.Selection("capture", 1, "image", List.of(), colors);
        var plan = new CommerceBatchCreativePlanValidator.Plan("fixed-snapshot", List.of(selected), variants, null, artworkSource, "SOURCE_SKU_COLORS");
        when(f.plans.forExecution("org", "run")).thenReturn(new CommerceBatchCreativePlanMapper.Row("plan", "org", "batch", 1, f.json.writeValueAsString(plan), "u", Instant.now()));
        var old = f.captures.get("capture", f.user);
        var images = new ArrayList<ProductCaptureImage>();
        for (String id : java.util.stream.Stream.concat(java.util.stream.Stream.of("image"), colors.stream().map(CommerceSourceColorResolver.Color::imageId)).distinct().toList()) {
            var image = mock(ProductCaptureImage.class);when(image.getId()).thenReturn(id);images.add(image);
        }
        when(f.captures.get("capture", f.user)).thenReturn(new ProductCaptureService.CaptureDetail(old.capture(), List.of(), images, List.of(), Map.of(), Map.of()));
    }

    private void setupModelAndNewJob() {
        when(f.models.imageConnection("org")).thenReturn(Optional.of(new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection(
                "model", "http://unused", "test-model", "OPENAI_COMPATIBLE", "CUSTOM", "unit-only")));
        when(f.db.queryForMap(startsWith("SELECT * FROM commerce_batch_artwork_jobs"), eq("run"), anyString()))
                .thenReturn(Map.of("id", "new-job", "status", "UNKNOWN"));
        when(f.archive.store(eq("org"), any())).thenReturn(new CommerceImageArchive.Archived(key('3'), SurfaceTemplateFixture.hash('3'), "image/png", 100));
    }

    private ArrayList<Map<String,Object>> sourceJobs(int variants) {
        var jobs = new ArrayList<Map<String,Object>>(List.of(f.sourceArtwork()));
        for (int variant = 1; variant <= variants; variant++) for (int group = 1; group <= 2; group++)
            jobs.add(Map.of("id", "group-"+variant+"-"+group, "capture_id", "capture", "kind", "VARIATE_G"+group,
                    "variant_index", variant, "status", "READY", "storage_key", key(variant == group ? '4' : '7')));
        return jobs;
    }

    private static Map<String,Object> mockup(String kind, int variant, CommerceSourceColorResolver.Color color, String status, String refs) {
        var row = new HashMap<String,Object>();
        row.put("id", kind+"-"+variant+"-"+color.key());row.put("capture_id", "capture");row.put("kind", kind);row.put("variant_index", variant);
        row.put("color_key", color.key());row.put("color_label", color.label());row.put("status", status);row.put("storage_key", key('4'));row.put("reference_keys", refs);
        return row;
    }

    private static CommerceSourceColorResolver.Color color(String key, String label, String image) {
        return new CommerceSourceColorResolver.Color(key, label, List.of(key+"-s", key+"-m"), image, true);
    }
    private static String key(char hash) { return CommerceSurfaceTemplateInputs.key("org", SurfaceTemplateFixture.hash(hash)); }
}
