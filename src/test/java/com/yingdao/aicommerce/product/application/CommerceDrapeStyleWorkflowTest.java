package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceDrapeStyleWorkflowTest {
    final CommerceDrapeStyleReview reviewer=mock(CommerceDrapeStyleReview.class);
    final CommerceDrapeMasterRetry retry=mock(CommerceDrapeMasterRetry.class);
    CommerceDrapeReuseWorkflow current(CommerceDrapeReuseWorkflowTest.Harness h) {
        h.find(CommerceDrapeReuseWorkflow.MASTER,0,null).put("prompt",CommerceDrapeReuseWorkflow.PROMPT);
        return new CommerceDrapeReuseWorkflow(h.db,h.archive,h.json,h.processor,h.local,reviewer,retry,h.clock::get);
    }
    Map<String,Object> report(CommerceDrapeReuseWorkflowTest.Harness h) {
        var r=new LinkedHashMap<String,Object>();
        for(String f:List.of("passed","hasScene","hasSupportContact","hasNaturalDrape","preservesGarment","preservesPrintPlacement","fullyVisible"))r.put(f,true);
        r.put("contractVersion",CommerceDrapeStyleReview.CONTRACT_VERSION);r.put("reason","Scene, folds and original print placement are visible");
        r.put("masterSha256",h.masterKey.substring(65));r.put("guideSha256",h.guideKey.substring(65));r.put("sceneSha256",h.originalKey.substring(65));return r;
    }
    @Test void reviewedMotherRetainsBoundEvidenceAcrossEveryLocalColorWithoutAnotherReview()throws Exception {
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var workflow=current(h);
        when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(report(h));
        for(int i=0;i<5;i++)assertEquals("QUEUED",workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertNull(workflow.finish(h.run,h.source,h.config,h.jobs));assertNull(workflow.review(h.run,h.plan,h.config,h.jobs));
        verify(reviewer,times(1)).review(eq("org"),eq(h.guide),eq(h.assets.get(h.originalKey)),eq(h.master));
        verifyNoInteractions(retry);verify(h.processor,times(4)).render(any(),any(),any(),any(),any(),anyString(),anyBoolean());
        assertEquals(report(h),h.reports.get(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null).get("id")).get("styleReview"));
    }
    @Test void missingSceneOrFoldsQueuesCorrectionBeforeAnyLocalCopies()throws Exception {
        for(String field:List.of("hasScene","hasNaturalDrape")) {
            var h=new CommerceDrapeReuseWorkflowTest.Harness();var workflow=current(h);var review=report(h);review.put(field,false);review.put("passed",false);
            when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(review);
            when(retry.retry(eq(h.run),anyMap(),eq(review))).thenReturn(new CommerceDrapeMasterRetry.Result("QUEUED","next",2,"correcting"));
            assertEquals("QUEUED",workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
            verifyNoInteractions(h.processor,h.local);assertNull(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null));
        }
    }
    @Test void reviewOutageRetriesOnlyReadAndNeverCreatesPaidOrLocalCopies()throws Exception {
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var workflow=current(h);
        when(reviewer.review(eq("org"),any(),any(),any())).thenThrow(new CommerceDrapeStyleReview.ReviewUnavailableException("network"));
        assertEquals("QUEUED",workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        verifyNoInteractions(h.processor,h.local,retry);
        var state=new LinkedHashMap<>(Map.<String,Object>of("status","REVIEW_RETRY_PENDING","readAttempts",2,"nextAttemptAt",0));
        h.checks.put((String)h.find(CommerceDrapeReuseWorkflow.MASTER,0,null).get("id"),h.json.writeValueAsString(state));
        assertEquals("WAITING_INPUT",workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        state.put("status","REVIEW_UNAVAILABLE");state.put("readAttempts",3);
        h.checks.put((String)h.find(CommerceDrapeReuseWorkflow.MASTER,0,null).get("id"),h.json.writeValueAsString(state));
        // An explicitly resumed stopped execution starts a fresh read window, using the same paid image.
        assertEquals("QUEUED",workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        verifyNoInteractions(h.processor,h.local,retry);
    }
    @Test void retryBackoffDoesNotCallVisionUntilDue()throws Exception {
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var workflow=current(h);
        var state=Map.of("status","REVIEW_RETRY_PENDING","readAttempts",1,"nextAttemptAt",System.currentTimeMillis()+60000);
        h.checks.put((String)h.find(CommerceDrapeReuseWorkflow.MASTER,0,null).get("id"),h.json.writeValueAsString(state));
        assertEquals("QUEUED",workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());verifyNoInteractions(reviewer,retry,h.processor,h.local);
    }
    @Test void wrongReviewBindingCannotReachBlankOrPassFinalReview()throws Exception {
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var workflow=current(h);var wrong=report(h);wrong.put("sceneSha256","0".repeat(64));
        when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(wrong);
        assertThrows(IllegalArgumentException.class,()->workflow.finish(h.run,h.source,h.config,h.jobs));verifyNoInteractions(h.processor,h.local);
        when(reviewer.review(eq("org"),any(),any(),any())).thenReturn(report(h));
        for(int i=0;i<5;i++)workflow.finish(h.run,h.source,h.config,h.jobs);
        h.reports.get(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null).get("id")).put("styleReview",wrong);
        assertNotNull(workflow.review(h.run,h.plan,h.config,h.jobs));
        assertThrows(IllegalArgumentException.class,()->workflow.finish(h.run,h.source,h.config,h.jobs));
    }
    @Test void newPromptCannotSkipReviewWhenDependencyMissing()throws Exception {
        var h=new CommerceDrapeReuseWorkflowTest.Harness();current(h);
        assertThrows(IllegalArgumentException.class,()->h.workflow.finish(h.run,h.source,h.config,h.jobs));verifyNoInteractions(h.processor,h.local);
    }
}
