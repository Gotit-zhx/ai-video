package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceModelDetailWorkflowTest {
    @TempDir Path temp;
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final ObjectMapper json = new ObjectMapper();
    final CommerceModelDetailReview reviewer = mock(CommerceModelDetailReview.class);
    final CommerceModelDetailRetry retry = mock(CommerceModelDetailRetry.class);
    final Map<String, Map<String, Object>> reports = new HashMap<>();
    final CommerceBatchExecution run = new CommerceBatchExecution("run", "org", "batch", "owner", "MOCKUP", "RUNNING",
            null, "lease", Instant.now().plusSeconds(600), Instant.now(), Instant.now());
    final CommerceBatchCreativePlanValidator.Selection source = new CommerceBatchCreativePlanValidator.Selection(
            "capture", 7, "main-source", List.of(),
            List.of(new CommerceSourceColorResolver.Color("black", "黑色", List.of("s", "m"), "main-source", true),
                    new CommerceSourceColorResolver.Color("white", "白色", List.of("s", "m"), "white-source", true)),
            List.of("original-detail-1", "original-detail-2"));
    CommerceImageArchive archive;
    CommerceModelDetailWorkflow service;
    final java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    String product;
    List<String> styles;
    List<String> outputs;
    Map<String, Object> main;
    List<Map<String, Object>> details;
    List<Map<String, Object>> jobs;

    @BeforeEach void setup() throws Exception {
        archive = spy(new CommerceImageArchive(temp.toString()));
        product = archive.store("org", image(0x123456)).storageKey();
        styles = List.of(archive.store("org", image(0x345678)).storageKey(), archive.store("org", image(0x567890)).storageKey());
        outputs = List.of(archive.store("org", image(0x789012)).storageKey(), archive.store("org", image(0x901234)).storageKey());
        service = new CommerceModelDetailWorkflow(db, archive, json, reviewer, retry);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"now",(java.util.function.LongSupplier)now::get);
        main = new HashMap<>(Map.of("id", "main", "kind", "DRAPED_FRONT", "capture_id", "capture", "capture_version", 7L,
                "variant_index", 1, "color_key", "black", "status", "READY", "storage_key", product));
        details = List.of(detail(1), detail(2));
        // Database row order is deliberately unrelated to the frozen source order.
        jobs = new ArrayList<>(List.of(details.get(1), main, details.get(0)));
        reports.put("detail-1", boundReport(1)); reports.put("detail-2", boundReport(2));
        when(db.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            var report = reports.get(invocation.getArgument(1));
            return report == null ? List.of() : List.of(Map.of("report_json", json.writeValueAsString(report)));
        });
        when(db.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            Map<String, Object> report = json.readValue(invocation.<String>getArgument(1),
                    json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            String sql=invocation.getArgument(0),id=invocation.getArgument(2);
            if(sql.startsWith("INSERT IGNORE")&&reports.containsKey(id))return 0;
            if(sql.startsWith("UPDATE")){
                String expected=invocation.getArgument(invocation.getArguments().length-1);
                if(!json.readTree(json.writeValueAsString(reports.get(id))).equals(json.readTree(expected)))return 0;
            }
            reports.put(id, report); return 1;
        });
        clearInvocations(archive, db);
    }

    @Test void historicalPlansWithoutFrozenDetailsRemainUnchanged() {
        var historical = new CommerceBatchCreativePlanValidator.Selection("capture", 7, "main-source");
        assertEquals(List.of(), CommerceModelDetailWorkflow.requiredKeys(historical, 1, List.of(), db, json, archive, "org"));
        verifyNoInteractions(db, archive, reviewer, retry);
    }

    @Test void publicationRequiresAllOriginalDetailsInFrozenOrderRegardlessOfColorCount() {
        var retired = new HashMap<>(details.getFirst()); retired.put("id", "retired"); retired.put("status", "RETRIED");
        jobs.addFirst(retired);
        var keys = required();
        assertEquals(outputs, keys);
        assertEquals(source.detailImageIds().size(), keys.size());
        assertThrows(UnsupportedOperationException.class, () -> keys.add("extra"));
        verifyNoInteractions(reviewer, retry);
    }

    @Test void missingDuplicateOrUnfinishedDetailCannotSilentlyDisappearFromPublication() {
        jobs.remove(details.get(1)); assertThrows(IllegalArgumentException.class, this::required); jobs.add(details.get(1));
        jobs.add(new HashMap<>(details.getFirst())); assertThrows(IllegalArgumentException.class, this::required); jobs.removeLast();
        for (String state : List.of("PREPARED", "PENDING", "RUNNING", "UNKNOWN", "RESULT_RECEIVED", "FAILED", "RETRIED")) {
            details.getFirst().put("status", state);
            assertThrows(IllegalArgumentException.class, this::required, state);
        }
    }

    @Test void staleWrongVariantForeignCaptureAndColorSpecificJobsDoNotSatisfyFrozenDetail() {
        var job = details.getFirst();
        for (var mutation : List.<Map.Entry<String, Object>>of(Map.entry("capture_version", 6L), Map.entry("variant_index", 2),
                Map.entry("capture_id", "other-capture"), Map.entry("color_key", "black"), Map.entry("storage_key", ""))) {
            Object original = job.put(mutation.getKey(), mutation.getValue());
            assertThrows(IllegalArgumentException.class, this::required, mutation.getKey());
            job.put(mutation.getKey(), original);
        }
    }

    @Test void productReferenceMustBeReadyCurrentVersionArchivedAndUnique() {
        for (var mutation : List.<Map.Entry<String, Object>>of(Map.entry("capture_version", 6L), Map.entry("variant_index", 2),
                Map.entry("color_key", "white"), Map.entry("status", "RUNNING"), Map.entry("storage_key", ""))) {
            Object original = main.put(mutation.getKey(), mutation.getValue());
            assertThrows(IllegalArgumentException.class, this::required, mutation.getKey());
            main.put(mutation.getKey(), original);
        }
        jobs.add(new HashMap<>(main)); assertThrows(IllegalArgumentException.class, this::required);
    }

    @Test void mainReferenceUsesDrapedThenFrontThenLegacyWithoutAcceptingAnOldVersion() {
        var front = new HashMap<>(main); front.put("kind", "MOCKUP_FRONT"); front.put("storage_key", outputs.getFirst());
        var legacy = new HashMap<>(main); legacy.put("kind", "MOCKUP"); legacy.put("storage_key", outputs.get(1));
        jobs.addAll(List.of(front, legacy));
        assertEquals(product, CommerceModelDetailWorkflow.productKey(source, 1, jobs));
        main.put("capture_version", 6L);
        assertEquals(outputs.getFirst(), CommerceModelDetailWorkflow.productKey(source, 1, jobs));
        front.put("status", "PENDING");
        assertEquals(outputs.get(1), CommerceModelDetailWorkflow.productKey(source, 1, jobs));
    }

    @Test void everyReviewFlagMustBeExplicitlyTrueAndReportMustExist() {
        reports.remove("detail-1"); assertThrows(IllegalArgumentException.class, this::required);
        for (String flag : List.of("passed", "hasModel", "preservesGarment", "preservesPrint", "preservesColor", "coherentPose", "usableComposition")) {
            for (Object value : List.of(false, "true", 1)) {
                var report = boundReport(1); report.put(flag, value); reports.put("detail-1", report);
                assertThrows(IllegalArgumentException.class, this::required, flag + "=" + value);
            }
            var report = boundReport(1); report.remove(flag); reports.put("detail-1", report);
            assertThrows(IllegalArgumentException.class, this::required, "missing " + flag);
        }
    }

    @Test void reviewCannotBeReusedForAnotherContractSourceOrdinalVariantOrImage() {
        for (var mutation : List.<Map.Entry<String, Object>>of(Map.entry("contractVersion", "old"), Map.entry("reason", ""),
                Map.entry("captureId", "other"), Map.entry("variant", 2), Map.entry("detailIndex", 2),
                Map.entry("sourceDetailImageId", "original-detail-2"), Map.entry("productSha256", "f".repeat(64)),
                Map.entry("styleSha256", "f".repeat(64)), Map.entry("outputSha256", "f".repeat(64)))) {
            var report = boundReport(1); report.put(mutation.getKey(), mutation.getValue()); reports.put("detail-1", report);
            assertThrows(IllegalArgumentException.class, this::required, mutation.getKey());
        }
    }

    @Test void referencesAndPromptMustMatchTheExactProductAndDetailOrdinal() {
        var job = details.getFirst();
        for (var refs : List.of(List.of(product), List.of(styles.getFirst(), product), List.of(product, styles.get(1)))) {
            job.put("reference_keys", json.writeValueAsString(refs));
            assertThrows(IllegalArgumentException.class, this::required);
        }
        job.put("reference_keys", json.writeValueAsString(List.of(product, styles.getFirst())));
        job.put("prompt", CommerceModelDetailPolicy.prompt(2)); assertThrows(IllegalArgumentException.class, this::required);
    }

    @Test void evenPassingReviewCannotPublishMissingOrChangedArchivedBytes() throws Exception {
        Files.write(temp.resolve(outputs.getFirst()), image(0xabcdef));
        assertThrows(IllegalStateException.class, this::required);
        Files.delete(temp.resolve(outputs.getFirst()));
        assertThrows(IllegalStateException.class, this::required);
    }

    @Test void validSavedReviewSkipsExternalReviewAndRegeneration() {
        assertNull(reviewFirst());
        verifyNoInteractions(reviewer, retry, archive);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void successfulReviewIsPersistedWithFrozenIdentityBeforePublicationCanContinue() {
        reports.remove("detail-1");
        when(reviewer.review(eq("org"), any(byte[].class), any(byte[].class), any(byte[].class))).thenReturn(rawReport(1));
        var result = reviewFirst();
        assertEquals("QUEUED", result.pendingStatus()); assertFalse(result.complete());
        assertEquals(boundReport(1), reports.get("detail-1"));
        assertEquals(outputs, required());
        verify(reviewer).review(eq("org"), eq(image(0x123456)), eq(image(0x345678)), eq(image(0x789012)));
        verifyNoInteractions(retry);
        verify(db,times(2)).update(contains("e.lease_token=? AND e.lease_until>NOW(6)"),any(Object[].class));
    }

    @Test void reviewNetworkFailureContinuesAfterThreeWithoutRegenerating() {
        reports.remove("detail-1");
        when(reviewer.review(eq("org"), any(byte[].class), any(byte[].class), any(byte[].class)))
                .thenThrow(new CommerceModelDetailReview.ReviewUnavailableException("network timeout"));
        assertEquals("QUEUED", reviewFirst().pendingStatus());
        assertEquals("REVIEW_RETRY_PENDING", reports.get("detail-1").get("status"));
        assertEquals(1, reports.get("detail-1").get("readAttempts"));
        assertEquals("QUEUED", reviewFirst().pendingStatus());
        verify(reviewer, times(1)).review(eq("org"), any(byte[].class), any(byte[].class), any(byte[].class));
        reports.get("detail-1").put("nextAttemptAt", 0);
        assertEquals("QUEUED", reviewFirst().pendingStatus());
        assertEquals(2, reports.get("detail-1").get("readAttempts"));
        reports.get("detail-1").put("nextAttemptAt", 0);
        assertEquals("QUEUED", reviewFirst().pendingStatus());
        assertEquals("REVIEW_RETRY_PENDING", reports.get("detail-1").get("status"));
        assertEquals(3, reports.get("detail-1").get("readAttempts"));
        verify(reviewer, times(3)).review(eq("org"), any(byte[].class), any(byte[].class), any(byte[].class));
        verifyNoInteractions(retry);
        assertThrows(IllegalArgumentException.class, this::required);
    }

    @Test void explicitQualityFailureDelegatesTheBoundReportToOneCorrection() {
        reports.remove("detail-1"); var failed = rawReport(1); failed.put("passed", false); failed.put("hasModel", false);
        failed.put("reason", "衣服未真实穿在模特身上");
        when(reviewer.review(eq("org"), any(byte[].class), any(byte[].class), any(byte[].class))).thenReturn(failed);
        when(retry.retry(eq(run), eq(details.getFirst()), anyMap()))
                .thenReturn(new CommerceModelDetailRetry.Result("QUEUED", "detail-corrected", 2, "自动纠正详情图"));
        var result = reviewFirst();
        assertEquals("QUEUED", result.pendingStatus()); assertEquals("自动纠正详情图", result.message());
        verify(retry).retry(eq(run), eq(details.getFirst()), argThat(report ->
                Boolean.FALSE.equals(report.get("passed")) && "original-detail-1".equals(report.get("sourceDetailImageId"))
                        && Integer.valueOf(1).equals(report.get("variant")) && Integer.valueOf(1).equals(report.get("detailIndex"))
                        && "capture".equals(report.get("captureId")) && hash(outputs.getFirst()).equals(report.get("outputSha256"))));
        assertThrows(IllegalArgumentException.class, this::required);
    }

    @Test void exhaustedQualityCorrectionStopsBeforePublication() {
        reports.remove("detail-1"); var failed = rawReport(1); failed.put("passed", false); failed.put("preservesPrint", false);
        failed.put("reason", "印花与当前新款不一致");
        when(reviewer.review(eq("org"), any(byte[].class), any(byte[].class), any(byte[].class))).thenReturn(failed);
        when(retry.retry(eq(run), eq(details.getFirst()), anyMap()))
                .thenReturn(new CommerceModelDetailRetry.Result("EXHAUSTED", "detail-1", 2, "已达到纠正次数上限"));
        var result = reviewFirst();
        assertEquals("WAITING_INPUT", result.pendingStatus()); assertFalse(result.complete());
        assertTrue(result.message().contains("已达到纠正次数上限")); assertTrue(result.message().contains("印花与当前新款不一致"));
        verify(retry, times(1)).retry(eq(run), eq(details.getFirst()), anyMap());
        assertThrows(IllegalArgumentException.class, this::required);
    }

    @Test void lostLeaseCannotReportReviewSuccessOrStartQualityCorrection() {
        reports.remove("detail-1");
        doReturn(0).when(db).update(anyString(), any(Object[].class));
        var failed = rawReport(1); failed.put("passed", false); failed.put("preservesPrint", false);
        when(reviewer.review(eq("org"), any(byte[].class), any(byte[].class), any(byte[].class)))
                .thenReturn(rawReport(1), failed);
        for (int attempt = 0; attempt < 2; attempt++) {
            var error = assertThrows(IllegalStateException.class, this::reviewFirst);
            assertTrue(error.getMessage().contains("租约、素材或检查点已变化"));
            assertFalse(reports.containsKey("detail-1"));
            assertThrows(IllegalArgumentException.class, this::required);
        }
        verifyNoInteractions(retry);
        verifyNoInteractions(reviewer);
    }

    @Test void lostLeaseCannotClaimThatANetworkReviewRetryWasScheduled() {
        reports.remove("detail-1");
        doReturn(0).when(db).update(anyString(), any(Object[].class));
        when(reviewer.review(eq("org"), any(byte[].class), any(byte[].class), any(byte[].class)))
                .thenThrow(new CommerceModelDetailReview.ReviewUnavailableException("network timeout"));
        var error = assertThrows(IllegalStateException.class, this::reviewFirst);
        assertTrue(error.getMessage().contains("租约、素材或检查点已变化"));
        assertFalse(reports.containsKey("detail-1"));
        verifyNoInteractions(retry);
    }

    @Test void reviewOnlyReturnsImmutableBoundCachedSuccessWithoutCallingRetry() {
        var outcome=reviewOnlyFirst();
        assertEquals("PASSED",outcome.status());assertTrue(outcome.cached());assertEquals(boundReport(1),outcome.report());
        assertThrows(UnsupportedOperationException.class,()->outcome.report().put("passed",false));
        verifyNoInteractions(reviewer,retry,archive);
    }

    @Test void reviewOnlyPersistsAndReusesBoundRejectionWithoutStartingASingleImageCorrection() {
        reports.remove("detail-1");var failed=rawReport(1);failed.put("passed",false);failed.put("preservesPrint",false);
        when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(failed);
        var outcome=reviewOnlyFirst();
        assertEquals("REJECTED",outcome.status());assertFalse(outcome.cached());
        assertEquals("capture",outcome.report().get("captureId"));assertEquals("original-detail-1",outcome.report().get("sourceDetailImageId"));
        assertEquals(1,outcome.report().get("variant"));assertEquals(1,outcome.report().get("detailIndex"));
        assertEquals(hash(outputs.getFirst()),outcome.report().get("outputSha256"));
        assertEquals(reports.get("detail-1"),outcome.report());
        assertTrue(reviewOnlyFirst().cached());verify(reviewer,times(1)).review(eq("org"),any(),any(),any());
        verifyNoInteractions(retry);
    }

    @Test void reviewOnlyRejectsUnboundOrMalformedCachedNegativeReports() {
        var failed=boundReport(1);failed.put("passed",false);failed.put("preservesPrint",false);
        for(String field:List.of("captureId","variant","detailIndex","sourceDetailImageId","productSha256","styleSha256","outputSha256",
                "contractVersion","passed","hasModel","preservesGarment","preservesPrint","preservesColor","coherentPose","usableComposition","reason")) {
            var incomplete=new LinkedHashMap<>(failed);incomplete.remove(field);reports.put("detail-1",incomplete);
            // Missing passed is not a cached decision, so exercise it as a malformed fresh result instead.
            if("passed".equals(field)) {
                when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(incomplete);
            }
            assertThrows(IllegalArgumentException.class,this::reviewOnlyFirst,field);
        }
        var wrongImage=new LinkedHashMap<>(failed);wrongImage.put("outputSha256","f".repeat(64));reports.put("detail-1",wrongImage);
        assertThrows(IllegalArgumentException.class,this::reviewOnlyFirst);
        var fakeBoolean=new LinkedHashMap<>(failed);fakeBoolean.put("preservesPrint","false");reports.put("detail-1",fakeBoolean);
        assertThrows(IllegalArgumentException.class,this::reviewOnlyFirst);
        verifyNoInteractions(retry);verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void reviewOnlyReturnsPersistentBackoffBeyondThreeUnavailableReads() {
        reports.remove("detail-1");
        when(reviewer.review(eq("org"),any(),any(),any())).thenThrow(new CommerceModelDetailReview.ReviewUnavailableException("unavailable"));
        for(int count=1;count<=6;count++) {
            var outcome=reviewOnlyFirst();
            assertEquals("REVIEW_RETRY_PENDING",outcome.status());
            assertEquals(count,outcome.report().get("readAttempts"));assertFalse(outcome.cached());
            assertTrue(reviewOnlyFirst().cached());
            reports.get("detail-1").put("nextAttemptAt",0);
        }
        assertEquals("REVIEW_RETRY_PENDING",reports.get("detail-1").get("status"));
        verify(reviewer,times(6)).review(eq("org"),any(),any(),any());verifyNoInteractions(retry);
    }

    @Test void legacyUnavailableThreeContinuesAndReservationPrecedesTheRequest() {
        reports.put("detail-1",new LinkedHashMap<>(Map.of("status","REVIEW_UNAVAILABLE","readAttempts",3,"nextAttemptAt",0)));
        when(reviewer.review(eq("org"),any(),any(),any())).thenAnswer(call->{
            assertEquals("REVIEW_RUNNING",reports.get("detail-1").get("status"));assertEquals(4,reports.get("detail-1").get("readAttempts"));
            throw new CommerceModelDetailReview.ReviewUnavailableException("CHAT_TIMEOUT",true,false,"private transport body");
        });
        assertEquals("REVIEW_RETRY_PENDING",reviewOnlyFirst().status());assertEquals(now.get()+300000,reports.get("detail-1").get("nextAttemptAt"));
        assertEquals("CHAT_TIMEOUT",reports.get("detail-1").get("reasonCode"));assertFalse(json.writeValueAsString(reports.get("detail-1")).contains("private"));
        service=new CommerceModelDetailWorkflow(db,archive,json,reviewer,retry);assertTrue(reviewOnlyFirst().cached());verify(reviewer,times(1)).review(eq("org"),any(),any(),any());verifyNoInteractions(retry);
    }

    @Test void invalidResponsesStopAtThreeAndConfigurationFailuresStopOnceWithoutQualityCorrection() {
        reports.remove("detail-1");when(reviewer.review(eq("org"),any(),any(),any()))
                .thenThrow(new CommerceModelDetailReview.ReviewUnavailableException("COMMERCE_MODEL_DETAIL_INVALID_SCHEMA",false,true,"private invalid reply"));
        for(int i=1;i<=3;i++){
            assertEquals(i<3?"REVIEW_RETRY_PENDING":"REVIEW_UNAVAILABLE",reviewOnlyFirst().status());reports.get("detail-1").put("nextAttemptAt",0);
        }
        assertTrue(reviewOnlyFirst().cached());assertEquals("FAILED",reviewFirst().pendingStatus());verify(reviewer,times(3)).review(eq("org"),any(),any(),any());verifyNoInteractions(retry);
        reports.remove("detail-1");reset(reviewer);when(reviewer.review(eq("org"),any(),any(),any()))
                .thenThrow(new CommerceModelDetailReview.ReviewUnavailableException("CHAT_AUTHENTICATION_FAILED",false,false,"private failure"));
        assertEquals("REVIEW_UNAVAILABLE",reviewOnlyFirst().status());assertTrue(reviewOnlyFirst().cached());verify(reviewer,times(1)).review(eq("org"),any(),any(),any());
    }

    @Test void freshIdentityPreservesExistingStyleMatchAndUnrelatedEvidence() {
        var match=Map.of("passed",true,"guideSha256","a".repeat(64));reports.put("detail-1",new LinkedHashMap<>(Map.of("sourceStyleMatch",match,"retainedEvidence","keep")));
        when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(rawReport(1));
        assertEquals("PASSED",reviewOnlyFirst().status());assertEquals(match,reports.get("detail-1").get("sourceStyleMatch"));assertEquals("keep",reports.get("detail-1").get("retainedEvidence"));
        assertFalse(reports.get("detail-1").containsKey("reviewInputHash"));assertTrue(reviewOnlyFirst().cached());verifyNoInteractions(retry);
    }

    @Test void activeSameLeaseNeverStartsAnotherRequestAfterRestartCooldown() {
        reports.remove("detail-1");when(reviewer.review(eq("org"),any(),any(),any())).thenThrow(new IllegalStateException("in-flight"));
        assertThrows(IllegalStateException.class,this::reviewOnlyFirst);now.addAndGet(3600000);
        assertEquals("REVIEW_RETRY_PENDING",reviewOnlyFirst().status());verify(reviewer,times(1)).review(eq("org"),any(),any(),any());verifyNoInteractions(retry);
    }

    @Test void concurrentEvidenceCannotBeOverwrittenByAStaleIdentityResult() {
        reports.remove("detail-1");when(reviewer.review(eq("org"),any(),any(),any())).thenAnswer(call->{reports.get("detail-1").put("newerEvidence","keep");return rawReport(1);});
        assertThrows(IllegalStateException.class,this::reviewOnlyFirst);assertEquals("keep",reports.get("detail-1").get("newerEvidence"));assertFalse(reports.get("detail-1").containsKey("passed"));verifyNoInteractions(retry);
    }

    @Test void reviewOnlyCannotReturnFreshRejectionAfterLosingItsLease() {
        reports.remove("detail-1");var failed=rawReport(1);failed.put("passed",false);failed.put("preservesPrint",false);
        when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(failed);
        doReturn(0).when(db).update(anyString(),any(Object[].class));
        assertThrows(IllegalStateException.class,this::reviewOnlyFirst);verifyNoInteractions(retry);
    }

    @Test void v3PublicationRequiresPositiveStyleChecksBoundToTheActualSourceOutputAndFrozenGuide() {
        var guide=styleGuideForFirst();bindStyleParent(guide);
        assertThrows(IllegalArgumentException.class,this::required,"Product identity alone cannot approve a V3 detail");
        var match=styleMatchForFirst(guide);reports.get("detail-1").put("sourceStyleMatch",match);
        assertEquals(outputs,required());
        for(String flag:List.of("passed","matchesShotType","matchesPose","matchesSetting")) {
            for(Object invalid:List.of(false,"true",1)) {
                var broken=new LinkedHashMap<>(match);broken.put(flag,invalid);reports.get("detail-1").put("sourceStyleMatch",broken);
                assertThrows(IllegalArgumentException.class,this::required,flag);
            }
        }
        for(String field:List.of("sourceSha256","outputSha256","guideSha256")) {
            var broken=new LinkedHashMap<>(match);broken.put(field,"f".repeat(64));reports.get("detail-1").put("sourceStyleMatch",broken);
            assertThrows(IllegalArgumentException.class,this::required,field);
        }
        verifyNoInteractions(reviewer,retry);
    }

    @Test void v3PublicationRejectsWrongParentSourceOrdinalAndGridHashEvenWithPassingStyleReport() {
        var guide=styleGuideForFirst();var parent=bindStyleParent(guide);
        reports.get("detail-1").put("sourceStyleMatch",styleMatchForFirst(guide));
        for(var mutation:List.<Map.Entry<String,Object>>of(Map.entry("capture_id","foreign"),Map.entry("capture_version",6L),
                Map.entry("variant_index",2),Map.entry("kind","MODEL_DETAIL_GRID_2"),Map.entry("content_sha256","a".repeat(64)),
                Map.entry("reference_keys",json.writeValueAsString(List.of(product,styles.get(1)))))) {
            Object before=parent.put(mutation.getKey(),mutation.getValue());
            assertThrows(IllegalArgumentException.class,this::required,mutation.getKey());parent.put(mutation.getKey(),before);
        }
        var origin=json.readValue((String)details.getFirst().get("result_json"),Map.class);origin.put("slot",1);
        details.getFirst().put("result_json",json.writeValueAsString(origin));assertThrows(IllegalArgumentException.class,this::required);
    }

    @Test void templateReferenceIgnoresDrapedGeometryAndRequiresUniqueMatchingFront() {
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailWorkflow.templateProductKey(source,1,jobs));
        var front=new HashMap<>(main);front.put("id","template");front.put("kind","MOCKUP_FRONT");front.put("storage_key",outputs.getFirst());jobs.add(front);
        assertEquals(outputs.getFirst(),CommerceModelDetailWorkflow.templateProductKey(source,1,jobs));
        assertEquals(product,CommerceModelDetailWorkflow.productKey(source,1,jobs));
        front.put("color_key","white");assertThrows(IllegalArgumentException.class,()->CommerceModelDetailWorkflow.templateProductKey(source,1,jobs));
        front.put("color_key","black");jobs.add(new HashMap<>(front));assertThrows(IllegalArgumentException.class,()->CommerceModelDetailWorkflow.templateProductKey(source,1,jobs));
    }

    @Test void v4RequiresCurrentIdentityPolicyPoseOnlyMatchAndTemplateBinding() {
        var guide=styleGuideForFirst();var parent=bindStyleParent(guide);
        var front=new HashMap<>(main);front.put("id","template");front.put("kind","MOCKUP_FRONT");jobs.add(front);
        parent.put("prompt",CommerceModelDetailGridPolicy.stylePrompt(List.of(1),new CommerceModelDetailGridPolicy.StylePlan(List.of(guide),1,"")));
        var origin=json.readValue((String)details.getFirst().get("result_json"),Map.class);origin.put("algorithm",CommerceModelDetailGridPolicy.TEMPLATE_VERSION);
        details.getFirst().put("result_json",json.writeValueAsString(origin));
        var match=styleMatchForFirst(guide);reports.get("detail-1").put("sourceStyleMatch",match);
        assertThrows(IllegalArgumentException.class,this::required);
        reports.get("detail-1").put("reviewPolicyVersion",CommerceModelDetailReview.REVIEW_POLICY_VERSION);
        assertThrows(IllegalArgumentException.class,this::required);
        match.put("renderPolicy",CommerceModelDetailGridPolicy.RENDER_POLICY);assertEquals(outputs,required());
        front.put("storage_key",outputs.getFirst());assertThrows(IllegalArgumentException.class,this::required);
    }

    @Test void v4RechecksObsoletePositiveIdentityAndKeepsItsEvidence() {
        details.getFirst().put("result_json",json.writeValueAsString(Map.of("algorithm",CommerceModelDetailGridPolicy.TEMPLATE_VERSION)));
        var current=rawReport(1);current.put("reviewPolicyVersion",CommerceModelDetailReview.REVIEW_POLICY_VERSION);
        when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(current);
        assertEquals("PASSED",reviewOnlyFirst().status());
        assertTrue(reports.get("detail-1").containsKey("previousIdentityReview"));
        assertEquals(CommerceModelDetailReview.REVIEW_POLICY_VERSION,reports.get("detail-1").get("reviewPolicyVersion"));
        verify(reviewer,times(1)).review(eq("org"),any(),any(),any());
        assertTrue(reviewOnlyFirst().cached());verifyNoInteractions(retry);
    }

    private CommerceModelDetailStyleGuide.Guide styleGuideForFirst() {
        return new CommerceModelDetailStyleGuide.Guide(CommerceModelDetailStyleGuide.CONTRACT_VERSION,hash(styles.getFirst()),"a".repeat(64),
                CommerceImageArchive.sha("org".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"SEATED_MODEL","腰部以上","坐姿","白墙","侧光",true,
                List.of(new CommerceModelDetailStyleGuide.Region(.2,.2,.2,.2)),"景别=SEATED_MODEL；构图=腰部以上；姿态=坐姿；场景=白墙；光线=侧光");
    }
    private Map<String,Object> bindStyleParent(CommerceModelDetailStyleGuide.Guide guide) {
        details.getFirst().put("result_json",json.writeValueAsString(Map.of("algorithm",CommerceModelDetailGridPolicy.STYLE_VERSION,
                "parentJobId","style-parent","parentSha256","e".repeat(64),"slot",0)));
        var parent=new LinkedHashMap<String,Object>();parent.put("prompt",CommerceModelDetailGridPolicy.stylePrompt(List.of(1),
                new CommerceModelDetailGridPolicy.StylePlan(List.of(guide),1,""),CommerceModelDetailGridPolicy.STYLE_VERSION));
        parent.put("kind",CommerceModelDetailGridPolicy.kind(1));parent.put("capture_id","capture");parent.put("capture_version",7L);
        parent.put("variant_index",1);parent.put("reference_keys",json.writeValueAsString(List.of(product,styles.getFirst())));parent.put("content_sha256","e".repeat(64));
        when(db.queryForList(startsWith("SELECT p.prompt,p.kind"),eq("style-parent"),eq("detail-1"),eq("org"))).thenReturn(List.of(parent));
        return parent;
    }
    private Map<String,Object> styleMatchForFirst(CommerceModelDetailStyleGuide.Guide guide) {
        return json.convertValue(new CommerceModelDetailStyleGuide.StyleMatch(true,true,true,true,"保留原景别、姿态及场景",
                CommerceModelDetailStyleGuide.MATCH_VERSION,hash(styles.getFirst()),hash(outputs.getFirst()),CommerceModelDetailStyleGuide.sha(guide),
                "a".repeat(64),CommerceImageArchive.sha("org".getBytes(java.nio.charset.StandardCharsets.UTF_8))),Map.class);
    }
    private CommerceModelDetailWorkflow.ReviewOnly reviewOnlyFirst() {
        return service.reviewOnly(run,source,1,1,details.getFirst(),product,styles.getFirst());
    }
    private List<String> required() {
        return CommerceModelDetailWorkflow.requiredKeys(source, 1, jobs, db, json, archive, "org");
    }
    private CommerceBatchStageHandler.Result reviewFirst() {
        return service.review(run, source, 1, 1, details.getFirst(), product, styles.getFirst());
    }
    private Map<String, Object> detail(int index) {
        var job = new HashMap<String, Object>();
        job.put("id", "detail-" + index); job.put("kind", CommerceModelDetailPolicy.kind(index));
        job.put("capture_id", "capture"); job.put("capture_version", 7L); job.put("variant_index", 1);
        job.put("color_key", null); job.put("status", "READY"); job.put("storage_key", outputs.get(index - 1));
        job.put("content_sha256", hash(outputs.get(index - 1)));
        job.put("reference_keys", json.writeValueAsString(List.of(product, styles.get(index - 1))));
        job.put("prompt", CommerceModelDetailPolicy.prompt(index)); return job;
    }
    private Map<String, Object> rawReport(int index) {
        var report = new LinkedHashMap<String, Object>(); report.put("passed", true);
        CommerceModelDetailReview.CHECKS.forEach(flag -> report.put(flag, true));
        report.put("contractVersion", CommerceModelDetailPolicy.PROMPT_VERSION); report.put("reason", "商品、印花和真实穿着一致");
        report.put("productSha256", hash(product)); report.put("styleSha256", hash(styles.get(index - 1)));
        report.put("outputSha256", hash(outputs.get(index - 1))); return report;
    }
    private Map<String, Object> boundReport(int index) {
        var report = rawReport(index); report.put("captureId", "capture"); report.put("variant", 1);
        report.put("detailIndex", index); report.put("sourceDetailImageId", "original-detail-" + index); return report;
    }
    private static String hash(String key) { return key.substring(key.lastIndexOf('/') + 1); }
    private static byte[] image(int rgb) {
        try {
            var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) image.setRGB(x, y, rgb);
            var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
