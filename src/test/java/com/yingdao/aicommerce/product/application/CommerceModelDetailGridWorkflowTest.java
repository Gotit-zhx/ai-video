package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceModelDetailGridWorkflowTest {
    @TempDir Path temp;
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final ObjectMapper json = new ObjectMapper();
    final CommerceModelDetailWorkflow review = mock(CommerceModelDetailWorkflow.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final TransactionStatus transaction = mock(TransactionStatus.class);
    final List<Map<String, Object>> jobs = new ArrayList<>();
    final Map<String, Map<String, Object>> reports = new HashMap<>();
    final List<Submitted> submissions = new ArrayList<>();
    final Function<String, String> styles = mock(Function.class);
    final CommerceModelDetailStyleGuide styleGuide = mock(CommerceModelDetailStyleGuide.class);
    final CommerceDetailRestyleRevision revisions = mock(CommerceDetailRestyleRevision.class);
    final Map<Integer, String> frozenStyles = new HashMap<>();
    final CommerceBatchExecution run = new CommerceBatchExecution("run", "org", "batch", "owner", "MOCKUP", "RUNNING",
            null, "lease", Instant.now().plusSeconds(600), Instant.now(), Instant.now());
    final CommerceBatchCreativePlanValidator.Selection source = new CommerceBatchCreativePlanValidator.Selection("capture", 7, "main-source", List.of(),
            List.of(new CommerceSourceColorResolver.Color("black", "黑", List.of("s", "m"), "black-source", true),
                    new CommerceSourceColorResolver.Color("white", "白", List.of("s", "m"), "white-source", true)),
            IntStream.rangeClosed(1, 9).mapToObj(i -> "source-" + i).toList());
    CommerceImageArchive archive;
    CommerceModelDetailGridWorkflow workflow;
    String product;
    final java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    boolean lease = true;
    boolean published;
    int inserts;
    record Submitted(String kind, String prompt, List<String> references, Map<String, Object> existing) {}

    @BeforeEach void setup() {
        archive = new CommerceImageArchive(temp.toString());
        product = archive.store("org", image(16, Color.BLACK)).storageKey();
        var main = base("main", "MOCKUP_FRONT", "READY"); main.put("color_key", "black"); main.put("storage_key", product); jobs.add(main);
        when(styles.apply(anyString())).thenAnswer(call -> style(Integer.parseInt(call.<String>getArgument(0).substring(7))));
        when(manager.getTransaction(any())).thenReturn(transaction);
        workflow = new CommerceModelDetailGridWorkflow(db, archive, json, new TransactionTemplate(manager), review);
        when(db.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("FROM commerce_batch_executions")) return lease ? List.of(Map.of("id", "run")) : List.of();
            if (sql.contains("FROM commerce_batch_artwork_checks")) {
                var report = reports.get(call.getArgument(1));
                return report == null ? List.of() : List.of(Map.of("report_json", json.writeValueAsString(report)));
            }
            if (sql.contains("FROM commerce_batch_publications")) return published ? List.of(Map.of("item_id", "submitted-item")) : List.of();
            if (sql.startsWith("SELECT id,status FROM commerce_batch_artwork_jobs")) {
                String kind = call.getArgument(5);
                return jobs.stream().filter(job -> kind.equals(job.get("kind"))).toList();
            }
            if (sql.startsWith("SELECT * FROM commerce_batch_artwork_jobs")) return new ArrayList<>(jobs);
            throw new AssertionError("Unexpected query " + sql);
        });
        when(db.queryForMap(anyString(), any(Object[].class))).thenAnswer(call -> job(call.getArgument(1)));
        when(db.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); Object[] args = Arrays.copyOfRange(call.getArguments(), 1, call.getArguments().length);
            if (sql.startsWith("INSERT INTO commerce_batch_artwork_checks")) {
                reports.put((String) args[0], json.readValue((String) args[1], Map.class)); return 1;
            }
            if (sql.startsWith("UPDATE commerce_batch_artwork_jobs SET status='RETRIED'")) { job((String) args[0]).put("status", "RETRIED"); return 1; }
            if (sql.startsWith("UPDATE commerce_batch_artwork_jobs SET status='FAILED'")) { job((String) args[1]).put("status", "FAILED"); return 1; }
            if (sql.stripLeading().startsWith("INSERT INTO commerce_batch_artwork_jobs")) {
                inserts++;
                if (sql.contains("capture_version,?,variant_index")) {
                    var copy = new HashMap<>(job((String) args[6]));
                    copy.put("id", args[0]); copy.put("kind", args[1]); copy.put("request_key", args[2]);
                    copy.put("prompt", args[3]); copy.put("reference_keys", args[4]); copy.put("status", "PREPARED");
                    copy.remove("storage_key"); copy.remove("content_sha256"); jobs.add(copy);
                } else if (sql.contains("SELECT ?,organization_id")) {
                    var copy = new HashMap<>(job((String) args[3])); copy.put("id", args[0]); copy.put("request_key", args[1]); copy.put("status", "PREPARED"); jobs.add(copy);
                } else {
                    var child = base((String) args[0], (String) args[5], "READY");
                    child.put("request_key", args[7]); child.put("prompt", args[8]); child.put("reference_keys", args[11]);
                    child.put("result_json", args[12]); child.put("storage_key", args[13]); child.put("content_sha256", args[14]); jobs.add(child);
                }
                return 1;
            }
            throw new AssertionError("Unexpected update " + sql);
        });
    }

    @Test void nineDetailsAreRequestedInThreeGroupsOfFourFourAndOne() {
        execute(); assertSubmitted(List.of(1, 2, 3, 4));
        IntStream.rangeClosed(1, 4).forEach(i -> jobs.add(child(i, "READY")));
        execute(); assertSubmitted(List.of(5, 6, 7, 8));
        IntStream.rangeClosed(5, 8).forEach(i -> jobs.add(child(i, "READY")));
        execute(); assertSubmitted(List.of(9));
        jobs.add(child(9, "READY")); assertNull(execute());
        assertEquals(3, submissions.size());
        for (int index = 1; index <= 9; index++) verify(styles, times(1)).apply("source-" + index);
    }

    @Test void sixSuccessfulDetailsRemainAndEligibleFailureReusesOnlyTheLastThreeStyles() {
        IntStream.rangeClosed(1, 6).forEach(i -> jobs.add(child(i, "READY")));
        var failed = child(7, "FAILED"); jobs.add(failed); eligible(failed);
        execute(); assertSubmitted(List.of(7, 8, 9));
        assertEquals(List.of(product, style(7), style(8), style(9)), submissions.getFirst().references());
        verify(styles, never()).apply("source-7"); verify(styles).apply("source-8"); verify(styles).apply("source-9");
        for (int index = 1; index <= 6; index++) assertEquals("READY", job("detail-" + index).get("status"));
        assertEquals(0, inserts);
    }

    @Test void allSkuColorsShareOneGroupAndUnresolvedParentKeepsItsExactRequest() {
        execute(); var submitted = submissions.getFirst();
        assertEquals(List.of(product, style(1), style(2), style(3), style(4)), submitted.references());
        var pending = group(List.of(1, 2, 3, 4), "UNKNOWN"); jobs.add(pending);
        clearInvocations(styles);
        execute(); var resumed = submissions.getLast();
        assertSame(pending, resumed.existing()); assertEquals(pending.get("prompt"), resumed.prompt());
        assertEquals(submitted.references(), resumed.references()); verifyNoInteractions(styles);
        assertEquals(0, inserts);
    }

    @Test void archivedParentCreatesOnlyThreeChildrenAndReplayIsIdempotent() {
        IntStream.rangeClosed(1, 6).forEach(i -> jobs.add(child(i, "READY")));
        var failed = child(7, "FAILED"); jobs.add(failed); eligible(failed);
        var parent = group(List.of(7, 8, 9), "READY"); jobs.add(parent);
        assertEquals("QUEUED", execute().pendingStatus()); assertEquals(3, inserts);
        assertEquals("RETRIED", failed.get("status"));
        assertEquals("DONE", json.valueToTree(reports.get(parent.get("id"))).path("gridSplit").path("status").asText());
        var cells = jobs.stream().filter(j -> "READY".equals(j.get("status")) && Set.of("MODEL_DETAIL_7", "MODEL_DETAIL_8", "MODEL_DETAIL_9").contains(j.get("kind"))).toList();
        assertEquals(3, cells.size());
        for (var cell : cells) {
            int index = CommerceModelDetailPolicy.index((String) cell.get("kind"));
            assertEquals(List.of(product, style(index)), json.readValue((String) cell.get("reference_keys"), List.class));
            assertEquals(index - 7, json.readTree((String) cell.get("result_json")).path("slot").asInt());
            assertEquals("source-" + index, json.readTree((String) cell.get("result_json")).path("sourceDetailImageId").asText());
            assertTrue(CommerceModelDetailPolicy.supportedPrompt((String) cell.get("prompt"), index));
            assertNotNull(archive.read("org", (String) cell.get("storage_key")));
        }
        assertNull(execute()); assertEquals(3, inserts); assertTrue(submissions.isEmpty());
        verifyNoInteractions(styles);
    }

    @Test void expiredLeaseCannotInsertChildrenOrRecordSplitCompletion() {
        var parent = group(List.of(1, 2, 3), "READY"); jobs.add(parent); lease = false;
        assertThrows(IllegalStateException.class, this::execute);
        assertEquals(0, inserts); assertFalse(reports.containsKey(parent.get("id")));
        verify(manager, atLeastOnce()).rollback(transaction);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void archiveReadParameterFailureRetriesStorageWithoutAnotherPaidGeneration() {
        var parent = group(List.of(1, 2, 3), "READY"); jobs.add(parent);
        var unavailable = spy(archive);
        doThrow(new IllegalArgumentException("storage unavailable")).when(unavailable).read(eq("org"), anyString());
        workflow = new CommerceModelDetailGridWorkflow(db, unavailable, json, new TransactionTemplate(manager), review);
        assertEquals("QUEUED", execute().pendingStatus());
        assertEquals("READY", parent.get("status")); assertEquals(0, inserts); assertTrue(submissions.isEmpty());
        assertEquals("RETRY_PENDING", json.valueToTree(reports.get(parent.get("id"))).path("gridSplit").path("status").asText());
        assertFalse(reports.get(parent.get("id")).containsKey("generationRecovery"));
    }

    @Test void archiveWriteParameterFailurePreservesGridAndOnlyRetriesLocalSplit() {
        var parent = group(List.of(1, 2, 3), "READY"); jobs.add(parent);
        var unavailable = spy(archive);
        doThrow(new IllegalArgumentException("storage unavailable")).when(unavailable).store(eq("org"), any(byte[].class));
        workflow = new CommerceModelDetailGridWorkflow(db, unavailable, json, new TransactionTemplate(manager), review);
        assertEquals("QUEUED", execute().pendingStatus());
        assertEquals("READY", parent.get("status")); assertEquals(0, inserts); assertTrue(submissions.isEmpty());
        assertEquals("RETRY_PENDING", json.valueToTree(reports.get(parent.get("id"))).path("gridSplit").path("status").asText());
        assertFalse(reports.get(parent.get("id")).containsKey("generationRecovery"));
    }

    @Test void parentCannotOverwriteAnySuccessfulOriginalDetail() {
        var successful = child(1, "READY"); jobs.add(successful);
        String oldKey = (String) successful.get("storage_key");
        var parent = group(List.of(1, 2, 3), "READY"); jobs.add(parent);
        assertEquals("QUEUED", execute().pendingStatus());
        assertEquals("READY", successful.get("status")); assertEquals(oldKey, successful.get("storage_key"));
        assertEquals(0, inserts);
        assertEquals("RETRY_PENDING", json.valueToTree(reports.get(parent.get("id"))).path("gridSplit").path("status").asText());
        verify(manager).rollback(transaction);
    }

    @Test void thirdFailedGroupStopsWithPreviouslySuccessfulImagesIntact() {
        IntStream.rangeClosed(1, 6).forEach(i -> jobs.add(child(i, "READY")));
        for (int attempt = 1; attempt <= 3; attempt++) {
            var group = group(List.of(7, 8, 9), attempt == 3 ? "FAILED" : "RETRIED"); group.put("id", "parent-attempt-" + attempt);
            jobs.add(group); eligible(group);
        }
        var result = execute(); assertEquals("FAILED", result.pendingStatus());
        assertTrue(result.message().contains("3次")); assertEquals(0, inserts); assertTrue(submissions.isEmpty());
        for (int index = 1; index <= 6; index++) assertEquals("READY", job("detail-" + index).get("status"));
    }

    @Test void failedParentCreatesOneSuccessorAndLaterResumesItsFrozenReferences() {
        var parent = group(List.of(1, 2, 3, 4), "FAILED"); jobs.add(parent); eligible(parent);
        assertEquals("QUEUED", execute().pendingStatus()); assertEquals(1, inserts); assertEquals("RETRIED", parent.get("status"));
        var successor = jobs.stream().filter(j -> "PREPARED".equals(j.get("status"))).findFirst().orElseThrow();
        assertNotEquals(parent.get("request_key"), successor.get("request_key"));
        assertEquals(parent.get("reference_keys"), successor.get("reference_keys"));
        execute(); assertSame(successor, submissions.getLast().existing()); assertEquals(1, inserts);
        verifyNoInteractions(styles);
    }

    @Test void sourceStyleNetworkRetriesAreIsolatedFromOtherVariantsWithIdenticalPrompts() {
        frozenStyles.put(7, archive.store("org", image(16, Color.BLUE)).storageKey());
        var parent = group(List.of(7), "FAILED");
        parent.put("prompt", CommerceModelDetailGridPolicy.stylePrompt(List.of(7),
                new CommerceModelDetailGridPolicy.StylePlan(List.of(guide(7)), 1, "")));
        jobs.add(parent); eligible(parent);
        for (int variant : List.of(2, 3)) {
            var other = new HashMap<>(parent);
            other.put("id", "successful-variant-" + variant); other.put("variant_index", variant);
            other.put("status", "READY"); jobs.add(other);
        }
        when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            String prompt = call.getArgument(call.getArguments().length - 1);
            return (int) jobs.stream().filter(job -> prompt.equals(job.get("prompt")))
                    .filter(job -> !sql.contains("variant_index=?")
                            || (Objects.equals(job.get("capture_id"), call.getArgument(4))
                                && Objects.equals(job.get("capture_version"), call.getArgument(5))
                                && Objects.equals(job.get("kind"), call.getArgument(6))
                                && Objects.equals(job.get("variant_index"), call.getArgument(7))))
                    .count();
        });

        assertEquals("QUEUED", execute().pendingStatus()); assertEquals(1, inserts);
        assertEquals("RETRIED", parent.get("status"));
        var successor = jobs.stream().filter(job -> "PREPARED".equals(job.get("status"))).findFirst().orElseThrow();
        assertEquals(1, successor.get("variant_index")); assertEquals(parent.get("prompt"), successor.get("prompt"));
        assertEquals(parent.get("reference_keys"), successor.get("reference_keys"));
        assertEquals("READY", job("successful-variant-2").get("status"));
        assertEquals("READY", job("successful-variant-3").get("status"));
        successor.put("status", "FAILED"); eligible(successor);
        assertEquals("QUEUED", execute().pendingStatus()); assertEquals(2, inserts);
        var thirdAttempt = jobs.stream().filter(job -> "PREPARED".equals(job.get("status"))).findFirst().orElseThrow();
        thirdAttempt.put("status", "FAILED"); eligible(thirdAttempt);
        assertEquals("FAILED", execute().pendingStatus()); assertEquals(2, inserts);
        assertTrue(submissions.isEmpty()); verifyNoInteractions(styles, styleGuide);
    }

    @Test void historicalIndividualRequestContinuesWithExactOriginalPromptAndReferences() {
        IntStream.rangeClosed(1, 6).forEach(i -> jobs.add(child(i, "READY")));
        var pending = child(7, "RUNNING");
        pending.put("prompt", CommerceModelDetailPolicy.prompt(7) + CommerceModelDetailPolicy.CORRECTION_MARKER + "纠正穿着"); jobs.add(pending);
        execute(); var submitted = submissions.getFirst();
        assertEquals("MODEL_DETAIL_7", submitted.kind()); assertSame(pending, submitted.existing());
        assertEquals(pending.get("prompt"), submitted.prompt()); assertEquals(List.of(product, style(7)), submitted.references());
        verifyNoInteractions(styles); assertEquals(0, inserts);
    }

    @Test void completedParentDoesNotHideAFailedQualityCorrectionNeedingAFreshGroup() {
        IntStream.rangeClosed(1, 8).forEach(i -> jobs.add(child(i, "READY")));
        var correction = child(9, "FAILED"); correction.put("request_key", "quality-correction-request"); jobs.add(correction); eligible(correction);
        var done = group(List.of(9), "READY"); jobs.add(done);
        reports.put((String) done.get("id"), new HashMap<>(Map.of("gridSplit", Map.of("status", "DONE"))));
        execute(); var submitted = submissions.getFirst();
        assertSubmitted(List.of(9)); assertNull(submitted.existing());
        assertEquals(List.of(product, style(9)), submitted.references()); verifyNoInteractions(styles);
        // ArtworkService owns deriving a new paid request key from this failed correction.
    }

    @Test void pendingIndividualReviewMustFinishBeforeSubmittingAnotherGroup() {
        var ready = child(1, "READY"); jobs.add(ready);
        when(review.review(eq(run), eq(source), eq(1), eq(1), eq(ready), eq(product), eq(style(1))))
                .thenReturn(CommerceBatchStageHandler.Result.progress("审核暂不可用，稍后重试"));
        assertTrue(execute().message().contains("审核暂不可用"));
        assertTrue(submissions.isEmpty()); verifyNoInteractions(styles);
    }

    @Test void sourceStyleCorrectionCombinesOnlyRejectedCellsAndPreservesPassedArchives() {
        var parent = readyStyleGroup(List.of(1, 2, 3, 4), 1);
        reports.get("detail-2").putAll(Map.of("passed", false, "reason", "新衣服印花不一致"));
        cacheStyleMatch(parent, 4, false);
        var first = new HashMap<>(job("detail-1")); var third = new HashMap<>(job("detail-3"));
        var firstReport = new HashMap<>(reports.get("detail-1")); var thirdReport = new HashMap<>(reports.get("detail-3"));

        assertEquals("QUEUED", execute().pendingStatus());
        assertEquals(1, inserts); assertTrue(submissions.isEmpty());
        var correction = jobs.stream().filter(j -> "PREPARED".equals(j.get("status"))).findFirst().orElseThrow();
        assertEquals(List.of(2, 4), CommerceModelDetailGridPolicy.indices((String) correction.get("prompt")));
        assertEquals(CommerceModelDetailGridPolicy.STYLE_VERSION,CommerceModelDetailGridPolicy.sourceStyleVersion((String)correction.get("prompt")));
        var plan = CommerceModelDetailGridPolicy.stylePlan((String) correction.get("prompt"));
        assertEquals(2, plan.attempt()); assertEquals(List.of(guide(2), guide(4)), plan.guides());
        assertTrue(plan.correction().contains("原详情2")); assertTrue(plan.correction().contains("原详情4"));
        assertEquals(List.of(product, style(2), style(4)), json.readValue((String) correction.get("reference_keys"), List.class));
        assertEquals(first, job("detail-1")); assertEquals(third, job("detail-3"));
        assertEquals(firstReport, reports.get("detail-1")); assertEquals(thirdReport, reports.get("detail-3"));
        assertEquals("FAILED", job("detail-2").get("status")); assertEquals("FAILED", job("detail-4").get("status"));
        assertNotNull(archive.read("org", (String) job("detail-2").get("storage_key")));
        assertNotNull(archive.read("org", (String) job("detail-4").get("storage_key")));
        assertEquals("RETRIED", parent.get("status"));
        verifyNoInteractions(styleGuide, styles); verify(review, never()).review(any(), any(), anyInt(), anyInt(), anyMap(), anyString(), anyString());
    }

    @Test void unavailableStyleReviewContinuesAfterThreeWithCappedBackoffWithoutNewGeneration() {
        readyStyleGroup(List.of(1), 1);
        doReturn(new CommerceModelDetailWorkflow.ReviewOnly("REVIEW_RETRY_PENDING", Map.of(), true),
                new CommerceModelDetailWorkflow.ReviewOnly("REVIEW_UNAVAILABLE", Map.of(), true))
                .when(review).reviewOnly(any(), any(), anyInt(), anyInt(), anyMap(), anyString(), anyString());
        assertEquals("QUEUED", execute().pendingStatus()); assertEquals("FAILED", execute().pendingStatus());
        verifyNoInteractions(styleGuide); assertEquals(0, inserts); assertTrue(submissions.isEmpty());

        stubIdentityReviews(); reports.get("detail-1").remove("sourceStyleMatch");
        when(styleGuide.compare(anyString(), any(byte[].class), any(byte[].class), any()))
                .thenThrow(new CommerceModelDetailStyleGuide.UnavailableException("vision unavailable"));
        for (int attempt = 1; attempt <= 6; attempt++) {
            assertEquals("QUEUED", execute().pendingStatus());
            var pending = json.valueToTree(reports.get("detail-1")).path("sourceStyleMatch");
            assertEquals(attempt, pending.path("attempts").asInt());
            long delay=attempt==1?15000:attempt==2?45000:attempt==3?120000:300000;
            assertEquals(clock.get()+delay,pending.path("nextAttemptAt").asLong());
            assertEquals("QUEUED",execute().pendingStatus());
            clock.addAndGet(delay);restartStyleWorkflow();
        }
        verify(styleGuide, times(6)).compare(anyString(), any(byte[].class), any(byte[].class), any());
        assertEquals(0, inserts); assertTrue(submissions.isEmpty()); assertEquals("READY", job("detail-1").get("status"));
    }

    @Test void savedRejectedStyleSurvivesRestartWithoutAnotherComparisonOrDuplicateCorrection() {
        var parent = readyStyleGroup(List.of(1), 1); reports.get("detail-1").remove("sourceStyleMatch");
        when(styleGuide.compare(anyString(), any(byte[].class), any(byte[].class), any())).thenReturn(styleMatch(1, false));
        assertEquals("QUEUED", execute().pendingStatus());
        assertFalse(json.valueToTree(reports.get("detail-1")).path("sourceStyleMatch").path("passed").asBoolean());
        assertEquals(0, inserts); assertTrue(submissions.isEmpty());

        restartStyleWorkflow();
        assertEquals("QUEUED", execute().pendingStatus()); assertEquals(1, inserts);
        assertEquals("RETRIED", parent.get("status")); assertTrue(submissions.isEmpty());
        var successor = jobs.stream().filter(j -> "PREPARED".equals(j.get("status"))).findFirst().orElseThrow();
        restartStyleWorkflow(); execute(); execute();
        assertEquals(1, inserts); assertEquals(2, submissions.size());
        submissions.forEach(submission -> assertSame(successor, submission.existing()));
        assertEquals(submissions.getFirst().prompt(), submissions.getLast().prompt());
        verify(styleGuide, times(1)).compare(anyString(), any(byte[].class), any(byte[].class), any());
        verifyNoInteractions(styles);
    }

    @Test void thirdSourceStyleQualityRoundStopsWithAllImagesAndReviewResultsIntact() {
        var parent = readyStyleGroup(List.of(7, 8, 9), 3); cacheStyleMatch(parent, 9, false);
        var beforeJobs = jobs.stream().map(HashMap::new).toList();
        String beforeReports = json.writeValueAsString(reports);
        var result = execute(); assertEquals("FAILED", result.pendingStatus()); assertTrue(result.message().contains("3轮"));
        assertEquals(beforeJobs, jobs); assertEquals(beforeReports, json.writeValueAsString(reports));
        assertEquals(0, inserts); assertTrue(submissions.isEmpty());
        for (int index : List.of(7, 8, 9)) assertNotNull(archive.read("org", (String) job("detail-" + index).get("storage_key")));
        assertNotNull(archive.read("org", (String) parent.get("storage_key"))); verifyNoInteractions(styleGuide, styles);
    }

    @Test void expiredLeaseCannotSaveSourceStyleCorrectionOrMutateChildren() {
        var parent = readyStyleGroup(List.of(1, 2), 1); cacheStyleMatch(parent, 2, false);
        var beforeJobs = jobs.stream().map(HashMap::new).toList(); String beforeReports = json.writeValueAsString(reports);
        lease = false; assertThrows(IllegalStateException.class, this::execute);
        assertEquals(beforeJobs, jobs); assertEquals(beforeReports, json.writeValueAsString(reports));
        assertEquals(0, inserts); assertTrue(submissions.isEmpty()); verify(manager).rollback(transaction);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void submittedPublicationPreventsSourceStyleCorrectionBeforeAnyMutation() {
        var parent = readyStyleGroup(List.of(1, 2), 1); cacheStyleMatch(parent, 2, false);
        published = true; doThrow(new IllegalStateException("published materials are frozen")).when(revisions).assertMutable(run);
        var beforeJobs = jobs.stream().map(HashMap::new).toList(); String beforeReports = json.writeValueAsString(reports);
        assertThrows(IllegalStateException.class, this::execute);
        verify(revisions).assertMutable(run); verify(manager).rollback(transaction);
        assertEquals(beforeJobs, jobs); assertEquals(beforeReports, json.writeValueAsString(reports));
        assertEquals(0, inserts); assertTrue(submissions.isEmpty()); verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void preparationSavesOneGuidePerWorkerAndNeverReanalyzesCompletedReferences() {
        restartStyleWorkflow();
        var draped=base("draped-main","DRAPED_FRONT","READY");draped.put("color_key","black");
        draped.put("storage_key",archive.store("org",image(16,Color.BLUE)).storageKey());jobs.add(draped);
        when(styleGuide.prepare(eq("org"),any(byte[].class))).thenAnswer(call->{
            String hash=CommerceImageArchive.sha(call.getArgument(1));
            for(int i=1;i<=4;i++)if(archiveHash(style(i)).equals(hash))return guide(i);
            throw new AssertionError("unexpected source");
        });
        reports.put("main",new HashMap<>(Map.of("passed",true,"retainedEvidence","keep")));
        for(int turn=1;turn<=4;turn++){
            assertEquals("QUEUED",execute().pendingStatus());
            verify(styleGuide,times(turn)).prepare(eq("org"),any(byte[].class));
            if(turn<4){assertTrue(submissions.isEmpty());assertEquals(turn,json.valueToTree(reports.get("main")).path("sourceStylePreparation").path("guides").size());restartStyleWorkflow();}
        }
        assertSubmitted(List.of(1,2,3,4));assertEquals("keep",reports.get("main").get("retainedEvidence"));
        assertFalse(reports.get("main").containsKey("sourceStylePreparation"));
        assertEquals(List.of(guide(1),guide(2),guide(3),guide(4)),CommerceModelDetailGridPolicy.stylePlan(submissions.getFirst().prompt()).guides());
        assertTrue(CommerceModelDetailGridPolicy.requiresTemplateProduct(submissions.getFirst().prompt()));
        assertEquals(product,submissions.getFirst().references().getFirst());
        assertFalse(reports.containsKey("draped-main"));
    }

    @Test void persistedV3GroupKeepsItsDrapedReferenceEvenWhenAnExactTemplateIsAvailable() {
        restartStyleWorkflow();job("main").put("kind","DRAPED_FRONT");
        var template=base("exact-template","MOCKUP_FRONT","READY");template.put("color_key","black");
        template.put("storage_key",archive.store("org",image(16,Color.PINK)).storageKey());jobs.add(template);
        var parent=group(List.of(1),"PREPARED");
        parent.put("prompt",CommerceModelDetailGridPolicy.stylePrompt(List.of(1),new CommerceModelDetailGridPolicy.StylePlan(List.of(guide(1)),1,""),CommerceModelDetailGridPolicy.STYLE_VERSION));
        jobs.add(parent);var before=new HashMap<>(parent);
        execute();
        assertEquals(before,parent);assertSame(parent,submissions.getFirst().existing());
        assertEquals(product,submissions.getFirst().references().getFirst());
        assertFalse(CommerceModelDetailGridPolicy.requiresTemplateProduct(submissions.getFirst().prompt()));
        verifyNoInteractions(styleGuide,styles);
    }

    @Test void v4SplitFreezesTheExactTemplateReferenceAndV4ChildOrigin() {
        restartStyleWorkflow();
        var parent=group(List.of(1,2),"READY");
        parent.put("prompt",CommerceModelDetailGridPolicy.stylePrompt(List.of(1,2),new CommerceModelDetailGridPolicy.StylePlan(List.of(guide(1),guide(2)),1,"")));
        jobs.add(parent);assertEquals("QUEUED",execute().pendingStatus());
        var children=jobs.stream().filter(j->CommerceModelDetailPolicy.isKind((String)j.get("kind"))).toList();
        assertEquals(2,children.size());
        for(var child:children){
            assertEquals(CommerceModelDetailGridPolicy.TEMPLATE_VERSION,json.readTree((String)child.get("result_json")).path("algorithm").asText());
            assertEquals(product,json.readTree((String)child.get("reference_keys")).get(0).asText());
        }
        verifyNoInteractions(styleGuide,styles);
    }

    @Test void v4ReviewUsesPoseOnlyPolicyAndKeepsThePolicyInItsSavedEvidence() {
        var parent=readyTemplateStyleGroup(List.of(1),1);reports.get("detail-1").remove("sourceStyleMatch");
        var old=styleMatch(1,true);
        var poseOnly=new CommerceModelDetailStyleGuide.StyleMatch(old.passed(),old.matchesShotType(),old.matchesPose(),old.matchesSetting(),old.reason(),old.contractVersion(),old.sourceSha256(),old.outputSha256(),old.guideSha256(),old.modelFingerprint(),old.organizationSha256(),CommerceModelDetailGridPolicy.RENDER_POLICY);
        when(styleGuide.compare(eq("org"),any(byte[].class),any(byte[].class),eq(guide(1)),eq(CommerceModelDetailGridPolicy.RENDER_POLICY))).thenReturn(poseOnly);
        assertEquals("QUEUED",execute().pendingStatus());assertEquals("READY",parent.get("status"));
        assertEquals(CommerceModelDetailGridPolicy.RENDER_POLICY,json.valueToTree(reports.get("detail-1")).path("sourceStyleMatch").path("renderPolicy").asText());
        verify(styleGuide,never()).compare(anyString(),any(byte[].class),any(byte[].class),any());
        assertEquals(0,inserts);assertTrue(submissions.isEmpty());
    }

    @Test void v4CannotReuseAnOldSourceSceneVerdictAndCorrectionsKeepV4() {
        var parent=readyTemplateStyleGroup(List.of(1),1);
        ((Map<String,Object>)reports.get("detail-1").get("sourceStyleMatch")).remove("renderPolicy");
        assertThrows(IllegalStateException.class,this::execute);
        cacheStyleMatch(parent,1,false);
        assertEquals("QUEUED",execute().pendingStatus());
        var correction=jobs.stream().filter(j->"PREPARED".equals(j.get("status"))).findFirst().orElseThrow();
        assertTrue(CommerceModelDetailGridPolicy.requiresTemplateProduct((String)correction.get("prompt")));
        assertEquals(List.of(product,style(1)),json.readValue((String)correction.get("reference_keys"),List.class));
        verifyNoInteractions(styleGuide,styles);
    }

    @Test void legacyPreparationAtThreeAttemptsContinuesAndReservesBeforeEveryRequest() {
        restartStyleWorkflow();reports.put("main",new HashMap<>(Map.of("passed",true,"sourceStylePreparation",Map.of("attempts",3,"nextAttemptAt",0))));
        when(styleGuide.prepare(anyString(),any(byte[].class))).thenAnswer(call->{
            var current=json.valueToTree(reports.get("main")).path("sourceStylePreparation");assertEquals("RUNNING",current.path("status").asText());
            assertTrue(current.path("attempts").asInt()>=4);throw new CommerceModelDetailStyleGuide.UnavailableException("CHAT_TIMEOUT",true,false,"private upstream body");
        });
        for(int attempt=4;attempt<=7;attempt++){
            assertEquals("QUEUED",execute().pendingStatus());var state=json.valueToTree(reports.get("main")).path("sourceStylePreparation");
            assertEquals(attempt,state.path("attempts").asInt());assertEquals(clock.get()+300000,state.path("nextAttemptAt").asLong());
            assertFalse(json.writeValueAsString(state).contains("private"));assertEquals("CHAT_TIMEOUT",state.path("reasonCode").asText());
            restartStyleWorkflow();assertEquals("QUEUED",execute().pendingStatus());clock.addAndGet(300000);
        }
        verify(styleGuide,times(4)).prepare(anyString(),any(byte[].class));assertTrue(submissions.isEmpty());assertEquals(true,reports.get("main").get("passed"));
    }

    @Test void invalidPreparationStopsAfterThreeAndPermanentConfigurationStopsImmediately() {
        restartStyleWorkflow();when(styleGuide.prepare(anyString(),any(byte[].class)))
                .thenThrow(new CommerceModelDetailStyleGuide.UnavailableException("STYLE_GUIDE_INVALID",false,true,"bad response"));
        for(int i=1;i<=3;i++){
            assertEquals(i<3?"QUEUED":"FAILED",execute().pendingStatus());clock.addAndGet(300000);restartStyleWorkflow();
        }
        assertEquals("FAILED",execute().pendingStatus());verify(styleGuide,times(3)).prepare(anyString(),any(byte[].class));
        assertEquals(3,json.valueToTree(reports.get("main")).path("sourceStylePreparation").path("invalidAttempts").asInt());assertTrue(submissions.isEmpty());
        reports.get("main").remove("sourceStylePreparation");reset(styleGuide);
        when(styleGuide.prepare(anyString(),any(byte[].class))).thenThrow(new CommerceModelDetailStyleGuide.UnavailableException("CHAT_AUTHENTICATION_FAILED",false,false,"private provider"));
        assertEquals("FAILED",execute().pendingStatus());assertEquals("FAILED",execute().pendingStatus());verify(styleGuide,times(1)).prepare(anyString(),any(byte[].class));
    }

    @Test void invalidStyleMatchStopsAfterThreeWithoutCreatingAnotherImage() {
        readyStyleGroup(List.of(1),1);reports.get("detail-1").remove("sourceStyleMatch");
        when(styleGuide.compare(anyString(),any(byte[].class),any(byte[].class),any()))
                .thenThrow(new CommerceModelDetailStyleGuide.UnavailableException("STYLE_MATCH_INVALID",false,true,"private provider"));
        for(int i=1;i<=3;i++){assertEquals(i<3?"QUEUED":"FAILED",execute().pendingStatus());clock.addAndGet(300000);restartStyleWorkflow();}
        assertEquals("FAILED",execute().pendingStatus());verify(styleGuide,times(3)).compare(anyString(),any(byte[].class),any(byte[].class),any());
        assertEquals(0,inserts);assertTrue(submissions.isEmpty());assertEquals("READY",job("detail-1").get("status"));
    }

    @Test void changedPreparationBindingDoesNotUseSavedGuidesOrIssueAnotherRequest() {
        restartStyleWorkflow();when(styleGuide.prepare(anyString(),any(byte[].class))).thenReturn(guide(1));execute();
        var state=(Map<String,Object>)reports.get("main").get("sourceStylePreparation");state.put("inputHash","wrong");
        assertEquals("FAILED",execute().pendingStatus());verify(styleGuide,times(1)).prepare(anyString(),any(byte[].class));assertTrue(submissions.isEmpty());
    }

    @Test void pauseOrLeaseLossStopsBeforeAnalysisAndCannotOverwriteNewerEvidence() {
        restartStyleWorkflow();lease=false;assertThrows(IllegalStateException.class,this::execute);verifyNoInteractions(styleGuide);
        assertFalse(reports.containsKey("main"));lease=true;
        when(styleGuide.prepare(anyString(),any(byte[].class))).thenAnswer(call->{
            reports.get("main").put("unrelatedEvidence","keep");lease=false;return guide(1);
        });
        assertThrows(IllegalStateException.class,this::execute);assertEquals("keep",reports.get("main").get("unrelatedEvidence"));
        assertEquals("RUNNING",json.valueToTree(reports.get("main")).path("sourceStylePreparation").path("status").asText());
        assertTrue(submissions.isEmpty());
    }

    @Test void activeLeaseReservationNeverBecomesASecondRequestWhenCooldownExpires() {
        restartStyleWorkflow();when(styleGuide.prepare(anyString(),any(byte[].class))).thenThrow(new IllegalStateException("original request still active"));
        assertThrows(IllegalStateException.class,this::execute);clock.addAndGet(3600000);restartStyleWorkflow();
        assertEquals("UNKNOWN",execute().pendingStatus());verify(styleGuide,times(1)).prepare(anyString(),any(byte[].class));
        assertEquals("RUNNING",json.valueToTree(reports.get("main")).path("sourceStylePreparation").path("status").asText());assertTrue(submissions.isEmpty());
    }

    @Test void transientFailureAfterOneCompletedGuideKeepsItAndResumesNextSourceOnly() {
        restartStyleWorkflow();when(styleGuide.prepare(eq("org"),any(byte[].class)))
                .thenReturn(guide(1)).thenThrow(new CommerceModelDetailStyleGuide.UnavailableException("CHAT_TIMEOUT",true,false,"timeout")).thenReturn(guide(2));
        execute();execute();var state=json.valueToTree(reports.get("main")).path("sourceStylePreparation");assertEquals(1,state.path("guides").size());
        restartStyleWorkflow();execute();verify(styleGuide,times(2)).prepare(eq("org"),any(byte[].class));
        clock.addAndGet(15000);execute();assertEquals(2,json.valueToTree(reports.get("main")).path("sourceStylePreparation").path("guides").size());
        verify(styleGuide,times(1)).prepare("org",Base64.getDecoder().decode(archive.read("org",style(1)).dataBase64()));
        verify(styleGuide,times(2)).prepare("org",Base64.getDecoder().decode(archive.read("org",style(2)).dataBase64()));assertTrue(submissions.isEmpty());
    }

    private Map<String, Object> readyStyleGroup(List<Integer> indices, int attempt) {
        for (int index : indices) frozenStyles.put(index, archive.store("org", image(16, new Color(index * 23, index * 17, index * 11))).storageKey());
        var parent = group(indices, "READY");
        parent.put("prompt", CommerceModelDetailGridPolicy.stylePrompt(indices,
                new CommerceModelDetailGridPolicy.StylePlan(indices.stream().map(this::guide).toList(), attempt, attempt == 1 ? "" : "保留原景别与人物姿态"),CommerceModelDetailGridPolicy.STYLE_VERSION));
        jobs.add(parent); reports.put((String) parent.get("id"), new HashMap<>(Map.of("gridSplit", Map.of("status", "DONE"))));
        for (int slot = 0; slot < indices.size(); slot++) {
            int index = indices.get(slot); var child = child(index, "READY");
            child.put("content_sha256", archiveHash(product));
            child.put("result_json", json.writeValueAsString(Map.of("algorithm", CommerceModelDetailGridPolicy.STYLE_VERSION,
                    "parentJobId", parent.get("id"), "parentSha256", parent.get("content_sha256"), "slot", slot,
                    "detailIndex", index, "sourceDetailImageId", "source-" + index)));
            jobs.add(child); reports.put((String) child.get("id"), new HashMap<>(Map.of("passed", true, "reason", "新衣服一致")));
            cacheStyleMatch(parent, index, true);
        }
        restartStyleWorkflow(); stubIdentityReviews(); return parent;
    }
    private Map<String,Object> readyTemplateStyleGroup(List<Integer> indices,int attempt){
        var parent=readyStyleGroup(indices,attempt);
        parent.put("prompt",CommerceModelDetailGridPolicy.stylePrompt(indices,CommerceModelDetailGridPolicy.stylePlan((String)parent.get("prompt"))));
        for(int index:indices){
            var child=job("detail-"+index);var origin=json.readTree((String)child.get("result_json"));
            ((tools.jackson.databind.node.ObjectNode)origin).put("algorithm",CommerceModelDetailGridPolicy.TEMPLATE_VERSION);
            child.put("result_json",json.writeValueAsString(origin));cacheStyleMatch(parent,index,true);
        }
        return parent;
    }
    private void restartStyleWorkflow() {
        for(int index=1;index<=9;index++)if(!frozenStyles.containsKey(index))frozenStyles.put(index,archive.store("org",image(16,new Color(index*23,index*17,index*11))).storageKey());
        workflow = new CommerceModelDetailGridWorkflow(db, archive, json, new TransactionTemplate(manager), review);
        ReflectionTestUtils.setField(workflow, "styleGuide", styleGuide); ReflectionTestUtils.setField(workflow, "revisions", revisions);
        ReflectionTestUtils.setField(workflow,"now",(java.util.function.LongSupplier)clock::get);
    }
    private void stubIdentityReviews() {
        doAnswer(call -> {
            Map<String, Object> child = call.getArgument(4); var report = reports.get(child.get("id"));
            return new CommerceModelDetailWorkflow.ReviewOnly(Boolean.TRUE.equals(report.get("passed")) ? "PASSED" : "REJECTED", report, true);
        }).when(review).reviewOnly(any(), any(), anyInt(), anyInt(), anyMap(), anyString(), anyString());
    }
    private void cacheStyleMatch(Map<String, Object> parent, int index, boolean passed) {
        assertTrue(CommerceModelDetailGridPolicy.indices((String) parent.get("prompt")).contains(index));
        var match=json.convertValue(styleMatch(index, passed), Map.class);
        if(CommerceModelDetailGridPolicy.requiresTemplateProduct((String)parent.get("prompt")))match.put("renderPolicy",CommerceModelDetailGridPolicy.RENDER_POLICY);
        reports.get("detail-" + index).put("sourceStyleMatch",match);
    }
    private CommerceModelDetailStyleGuide.StyleMatch styleMatch(int index, boolean passed) {
        var guide = guide(index);
        return new CommerceModelDetailStyleGuide.StyleMatch(passed, true, passed, true, passed ? "原景别姿态场景一致" : "坐姿与原图不一致",
                CommerceModelDetailStyleGuide.MATCH_VERSION, guide.sourceSha256(), (String) job("detail-" + index).get("content_sha256"),
                CommerceModelDetailStyleGuide.sha(guide), "a".repeat(64), guide.organizationSha256());
    }
    private CommerceModelDetailStyleGuide.Guide guide(int index) {
        return new CommerceModelDetailStyleGuide.Guide(CommerceModelDetailStyleGuide.CONTRACT_VERSION,
                archiveHash(style(index)), "a".repeat(64), CommerceImageArchive.sha("org".getBytes(StandardCharsets.UTF_8)),
                "SEATED_MODEL", "主体居中", "坐姿", "室内", "柔光", false, List.of(), "景别=SEATED_MODEL；构图=主体居中；姿态=坐姿；场景=室内；光线=柔光");
    }

    private CommerceBatchStageHandler.Result execute() {
        return workflow.execute(run, source, 1, jobs, styles, (kind, prompt, references, existing) -> {
            submissions.add(new Submitted(kind, prompt, references, existing));
            return CommerceBatchStageHandler.Result.progress("submitted");
        });
    }
    private void assertSubmitted(List<Integer> indices) {
        var submitted = submissions.getLast();
        assertEquals(CommerceModelDetailGridPolicy.kind(indices.getFirst()), submitted.kind());
        assertEquals(indices, CommerceModelDetailGridPolicy.indices(submitted.prompt()));
        assertNull(submitted.existing()); assertEquals(indices.size() + 1, submitted.references().size());
    }
    private Map<String, Object> base(String id, String kind, String state) {
        var job = new HashMap<String, Object>(); job.put("id", id); job.put("kind", kind); job.put("status", state);
        job.put("capture_id", "capture"); job.put("capture_version", 7L); job.put("variant_index", 1); job.put("color_key", null);
        job.put("request_key", "request-" + id); job.put("model_config_id", "model"); job.put("model_fingerprint", "model-hash"); return job;
    }
    private Map<String, Object> child(int index, String state) {
        var job = base("detail-" + index, CommerceModelDetailPolicy.kind(index), state);
        job.put("prompt", CommerceModelDetailPolicy.prompt(index)); job.put("reference_keys", json.writeValueAsString(List.of(product, style(index))));
        job.put("storage_key", product); return job;
    }
    private Map<String, Object> group(List<Integer> indices, String state) {
        var job = base("parent-" + indices.getFirst(), CommerceModelDetailGridPolicy.kind(indices.getFirst()), state);
        job.put("prompt", CommerceModelDetailGridPolicy.prompt(indices));
        var refs = new ArrayList<String>(); refs.add(product); indices.forEach(index -> refs.add(style(index)));
        job.put("reference_keys", json.writeValueAsString(refs));
        var grid = archive.store("org", image(1024, Color.RED)); job.put("storage_key", grid.storageKey()); job.put("content_sha256", grid.sha256()); return job;
    }
    private Map<String, Object> job(String id) { return jobs.stream().filter(j -> id.equals(j.get("id"))).findFirst().orElseThrow(); }
    private void eligible(Map<String, Object> job) { reports.put((String) job.get("id"), new HashMap<>(Map.of("generationRecovery", Map.of("detailRetryEligible", true, "terminalOperation", true)))); }
    private String style(int index) { return frozenStyles.getOrDefault(index, "original-style-" + index); }
    private String archiveHash(String key) { return CommerceImageArchive.sha(Base64.getDecoder().decode(archive.read("org", key).dataBase64())); }
    private static byte[] image(int size, Color color) {
        try {
            var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
            try { graphics.setColor(color); graphics.fillRect(0, 0, size, size); } finally { graphics.dispose(); }
            var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
}
