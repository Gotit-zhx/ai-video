package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceDrapeLocalRecoveryTest {
    private CommerceDrapeReuseWorkflow restarted(CommerceDrapeReuseWorkflowTest.Harness h){return new CommerceDrapeReuseWorkflow(h.db,h.archive,h.json,h.processor,h.local,null,null,h.clock::get);}
    private CommerceDrapeImageProcessor.LocalProcessingException timeout(){return new CommerceDrapeImageProcessor.LocalProcessingException("LOCAL_TIME_LIMIT",true,"本地处理超时",null);}
    private String masterId(CommerceDrapeReuseWorkflowTest.Harness h){return (String)h.find(CommerceDrapeReuseWorkflow.MASTER,0,null).get("id");}
    private JsonNode saved(CommerceDrapeReuseWorkflowTest.Harness h){return h.json.readTree(h.checks.get(masterId(h)));}
    private JsonNode operation(CommerceDrapeReuseWorkflowTest.Harness h,String op){
        for(var entry:saved(h).path("localRecovery").path("operations"))if(op.equals(entry.path("op").asText()))return entry;
        throw new AssertionError("No operation "+op);
    }
    @Test void attemptIsReservedBeforeRequestAndRestartKeepsThreeTotalAttemptsAndBackoff()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var mother=new LinkedHashMap<>(h.find(CommerceDrapeReuseWorkflow.MASTER,0,null));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenAnswer(call->{
            assertEquals(calls.incrementAndGet(),operation(h,"prepare").path("attempts").asInt());
            assertEquals("RUNNING",operation(h,"prepare").path("status").asText());throw timeout();
        });
        assertEquals("QUEUED",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertEquals(h.clock.get()+15000,operation(h,"prepare").path("nextAttemptAt").asLong());
        assertEquals("QUEUED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());assertEquals(1,calls.get());
        h.clock.addAndGet(15000);assertEquals("QUEUED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertEquals(h.clock.get()+45000,operation(h,"prepare").path("nextAttemptAt").asLong());
        h.clock.addAndGet(45000);assertEquals("FAILED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        h.clock.addAndGet(900000);assertEquals("FAILED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertEquals(3,calls.get());assertEquals("FAILED",operation(h,"prepare").path("status").asText());
        assertEquals(mother,h.find(CommerceDrapeReuseWorkflow.MASTER,0,null));assertNull(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null));
    }
    @Test void crashAfterReservationCannotResetAttemptBudget()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenThrow(new IllegalStateException("simulated process crash"));
        assertThrows(IllegalStateException.class,()->h.workflow.finish(h.run,h.source,h.config,h.jobs));
        assertEquals(1,operation(h,"prepare").path("attempts").asInt());
        assertEquals("QUEUED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        h.clock.addAndGet(105000);assertThrows(IllegalStateException.class,()->restarted(h).finish(h.run,h.source,h.config,h.jobs));
        assertEquals(2,operation(h,"prepare").path("attempts").asInt());
        h.clock.addAndGet(135000);assertThrows(IllegalStateException.class,()->restarted(h).finish(h.run,h.source,h.config,h.jobs));
        h.clock.addAndGet(135000);assertEquals("FAILED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        verify(h.processor,times(3)).prepare(any(),any(),any(),any(),anyList());
    }
    @Test void successfulStyleIsReusedAcrossLocalRetryAndMetadataSurvivesStyleSave()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();var style=new CommerceDrapeStyleWorkflowTest();var workflow=style.current(h);
        h.checks.put(masterId(h),h.json.writeValueAsString(Map.of("localRecovery",Map.of("retainedEvidence","keep"))));
        when(style.reviewer.review(eq("org"),any(),any(),any())).thenReturn(style.report(h));
        when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenThrow(timeout()).thenAnswer(call->h.prepared());
        assertEquals("QUEUED",workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertEquals("keep",saved(h).path("localRecovery").path("retainedEvidence").asText());
        assertTrue(saved(h).path("passed").asBoolean());assertEquals(1,operation(h,"prepare").path("attempts").asInt());
        assertEquals("QUEUED",style.current(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        h.clock.addAndGet(15000);assertEquals("QUEUED",style.current(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertEquals(2,operation(h,"prepare").path("attempts").asInt());assertEquals("SUCCEEDED",operation(h,"prepare").path("status").asText());
        verify(style.reviewer,times(1)).review(eq("org"),any(),any(),any());verifyNoInteractions(style.retry);
        assertEquals(style.report(h),h.reports.get(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null).get("id")).get("styleReview"));
    }
    @Test void deterministicSourcePrintAndValidationFailuresNeverLoop()throws Exception{
        for(RuntimeException error:List.of(new CommerceDrapeImageProcessor.LocalProcessingException("SOURCE_PRINT_UNSUITABLE",false,"fixed source print",null),new IllegalArgumentException("invalid hash"))){
            var h=new CommerceDrapeReuseWorkflowTest.Harness();when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenThrow(error);
            assertEquals("FAILED",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
            h.clock.addAndGet(900000);assertEquals("FAILED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
            assertEquals(1,operation(h,"prepare").path("attempts").asInt());verify(h.processor,times(1)).prepare(any(),any(),any(),any(),anyList());
        }
    }
    @Test void renderAttemptsArePerVariantAndColorAndReadyOutputsStayReused()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();h.workflow.finish(h.run,h.source,h.config,h.jobs);h.workflow.finish(h.run,h.source,h.config,h.jobs);
        assertNotNull(h.find(CommerceDrapeReuseWorkflow.OUTPUT,1,"black"));
        doThrow(timeout()).doReturn(Map.of("data",CommerceDrapeReuseWorkflowTest.Harness.encoded(h.output),"report",Map.of("passed",true)))
                .when(h.processor).render(any(),any(),any(),any(),any(),eq("#ee7788"),eq(false));
        assertEquals("QUEUED",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertEquals(1,operation(h,"prepare").path("attempts").asInt());assertEquals(1,operation(h,"render:1:black").path("attempts").asInt());
        h.clock.addAndGet(15000);assertEquals("QUEUED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertEquals(2,operation(h,"render:1:pink").path("attempts").asInt());
        h.workflow.finish(h.run,h.source,h.config,h.jobs);h.workflow.finish(h.run,h.source,h.config,h.jobs);assertNull(h.workflow.finish(h.run,h.source,h.config,h.jobs));
        assertEquals(1,operation(h,"render:2:pink").path("attempts").asInt());
        verify(h.processor,times(1)).prepare(any(),any(),any(),any(),anyList());verify(h.processor,times(5)).render(any(),any(),any(),any(),any(),anyString(),anyBoolean());
    }
    @Test void corruptedBindingCannotResetSavedAttempts()throws Exception{
        for(String field:List.of("inputHash","strategyVersion","op")){
            var h=new CommerceDrapeReuseWorkflowTest.Harness();when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenThrow(timeout());
            h.workflow.finish(h.run,h.source,h.config,h.jobs);var state=saved(h).deepCopy();
            for(var entry:state.path("localRecovery").path("operations"))((tools.jackson.databind.node.ObjectNode)entry).put(field,"wrong");
            h.checks.put(masterId(h),h.json.writeValueAsString(state));h.clock.addAndGet(15000);
            assertEquals("FAILED",restarted(h).finish(h.run,h.source,h.config,h.jobs).pendingStatus());verify(h.processor,times(1)).prepare(any(),any(),any(),any(),anyList());
        }
    }
    @Test void lostLeaseOrConcurrentReportStopsBeforeLocalRequestAndSqlFencesIdentity()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();h.allowWrites=false;
        assertEquals("UNKNOWN",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());verifyNoInteractions(h.processor);
        var sql=mockingDetails(h.db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).findFirst().orElseThrow().getArgument(0,String.class);
        for(String guard:List.of("j.organization_id=?","e.organization_id=?","e.created_by=?","e.stage='MOCKUP'","e.lease_token=?","e.lease_until>NOW(6)","j.status='READY'","j.content_sha256=?","j.capture_version=?","newer.status<>'RETRIED'","b.status='READY'"))assertTrue(sql.contains(guard),guard);
        assertTrue(h.checks.isEmpty());
    }
    @Test void stateChangingDuringProcessingCannotArchiveResultOrBecomeNetworkRetry()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenAnswer(call->{h.allowWrites=false;return h.prepared();});
        assertEquals("UNKNOWN",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());assertNull(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null));
        assertEquals("RUNNING",operation(h,"prepare").path("status").asText());
        verify(h.processor,times(1)).prepare(any(),any(),any(),any(),anyList());
    }
    @Test void successfulCacheHitNeverConsumesAnAttemptOrTouchesRecoveryMetadata()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();h.checks.put(masterId(h),h.json.writeValueAsString(Map.of("localRecovery",Map.of("preserved","evidence"))));
        String before=h.checks.get(masterId(h));
        doReturn(CommerceBatchStageHandler.Result.progress("cached"))
                .when(h.local).local(eq(h.run),eq(h.source),anyString(),anyInt(),any(),anyList(),anyString(),any(),any(),anyString());
        assertEquals("QUEUED",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        verifyNoInteractions(h.processor);assertEquals(before,h.checks.get(masterId(h)));
        verify(h.db,never()).update(anyString(),any(Object[].class));
    }
    @Test void archiveFailureOutsideProducerDoesNotBecomeARetryableLocalFailure()throws Exception{
        var h=new CommerceDrapeReuseWorkflowTest.Harness();
        doAnswer(call->{Supplier<CommerceLocalPhotoWorkflow.Produced> producer=call.getArgument(8);producer.get();throw new IllegalStateException("archive ownership changed");})
                .when(h.local).local(eq(h.run),eq(h.source),anyString(),anyInt(),any(),anyList(),anyString(),any(),any(),anyString());
        assertThrows(IllegalStateException.class,()->h.workflow.finish(h.run,h.source,h.config,h.jobs));
        assertEquals("SUCCEEDED",operation(h,"prepare").path("status").asText());assertNull(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null));
    }
}
