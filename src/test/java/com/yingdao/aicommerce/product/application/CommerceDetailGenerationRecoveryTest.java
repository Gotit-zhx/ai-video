package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceDetailGenerationRecoveryTest {
    @Test void completedRuntimeResultRequeuesOnlyArchivalAndKeepsOtherCheckMetadata() throws Exception {
        for(String kind:List.of("MODEL_DETAIL_7","MODEL_DETAIL_GRID_2")) {
            var h=new Harness(kind);
            h.check("{\"passed\":false,\"sourceDetailImageId\":\"style\"}");
            h.operation(new CommerceDesignImageGateway.Operation("COMPLETED",false,h.time,h.response(),null));
            h.service.recover(h.candidate);
            assertEquals("RESULT_RECEIVED",h.job.get("status"));assertEquals("QUEUED",h.run.get("status"));
            var recovered=h.json.readValue((String)h.job.get("result_json"),CommerceDesignImageGateway.Response.class);
            assertTrue(CommerceDetailGenerationRecovery.usable(recovered));
            var report=h.json.readTree(h.report);assertFalse(report.path("passed").asBoolean());assertEquals("style",report.path("sourceDetailImageId").asText());
            assertEquals("RESULT_RECOVERED",report.path("generationRecovery").path("status").asText());
            assertFalse(report.path("generationRecovery").path("detailRetryEligible").asBoolean());
            verify(h.gateway).operation("job-id");verifyNoMoreInteractions(h.gateway);
            assertEquals(1,h.tx.commits);assertEquals(0,h.tx.rollbacks);
        }
    }

    @Test void failed502BecomesBudgetedRetryEligibleWithoutChangingTheStoredResponse() {
        var h=new Harness("MODEL_DETAIL_7");String existing="{\"oldProviderNote\":\"keep me\"}";h.job.put("result_json",existing);
        h.check("{\"sourceDetailImageId\":\"style\",\"generationRecovery\":{\"unavailableChecks\":1}}");
        h.operation(failed(Map.of("providerStatus",502,"failureKind","NETWORK","failureCode","IMAGE_GATEWAY_HTTP_502","requestState","ACCEPTED_OR_UNKNOWN","retryAuthorized",true)));
        h.service.recover(h.candidate);
        assertEquals("FAILED",h.job.get("status"));assertEquals("QUEUED",h.run.get("status"));assertEquals(existing,h.job.get("result_json"));
        var report=h.json.readTree(h.report);assertEquals("style",report.path("sourceDetailImageId").asText());
        var recovery=report.path("generationRecovery");assertTrue(recovery.path("detailRetryEligible").asBoolean());assertTrue(recovery.path("terminalOperation").asBoolean());
        assertEquals("IMAGE_GATEWAY_HTTP_502",recovery.path("reasonCode").asText());assertEquals(1,recovery.path("unavailableChecks").asInt());
        assertFalse(recovery.has("nextAttemptAt"));verifyNoMoreInteractionsExceptLookup(h);
    }

    @Test void retryClassificationAcceptsDefinedTransientFailuresButRejectsPolicyOrUnverifiedFailures() {
        for(int code:List.of(502,503,504))assertTrue(CommerceDetailGenerationRecovery.retryable(Map.of("providerStatus",code,"failureKind","NETWORK")));
        assertTrue(CommerceDetailGenerationRecovery.retryable(Map.of("providerStatus",429,"failureKind","CONCURRENCY")));
        assertTrue(CommerceDetailGenerationRecovery.retryable(Map.of("failureKind","NETWORK_TIMEOUT","retryAuthorized",true,"requestState","RESPONSE_TIMEOUT")));
        for(String kind:List.of("COPYRIGHT","POLICY","AUTHORIZATION","AUTHENTICATION","PROVIDER"))
            assertFalse(CommerceDetailGenerationRecovery.retryable(Map.of("failureKind",kind,"providerStatus",502,"retryAuthorized",true,"requestState","ACCEPTED_OR_UNKNOWN")));
        assertFalse(CommerceDetailGenerationRecovery.retryable(Map.of("failureKind","NETWORK","providerStatus",401,"retryAuthorized",true,"requestState","ACCEPTED_OR_UNKNOWN")));
        assertFalse(CommerceDetailGenerationRecovery.retryable(Map.of("failureKind","NETWORK","retryAuthorized","true","requestState","ACCEPTED_OR_UNKNOWN")));
        assertFalse(CommerceDetailGenerationRecovery.retryable(Map.of("failureKind","NETWORK","requestState","ACCEPTED_OR_UNKNOWN")));
    }

    @Test void permanentPolicyFailureStopsClearlyAndDoesNotLeakUpstreamText() {
        var h=new Harness("MODEL_DETAIL_GRID_1");
        h.operation(failed(Map.of("failureKind","POLICY","providerStatus",403,"failureCode","url=https://private/?token=secret","message","private signature")));
        h.service.recover(h.candidate);
        assertEquals("FAILED",h.job.get("status"));assertEquals("FAILED",h.run.get("status"));
        assertTrue(h.message.contains("内容审核拒绝"));assertFalse(h.message.contains("private"));assertFalse(h.report.contains("secret"));
        assertFalse(h.json.readTree(h.report).path("generationRecovery").path("detailRetryEligible").asBoolean());
    }

    @Test void activeOperationIsOnlyObservedAndNeverCancelledOrResubmitted() {
        var h=new Harness("MODEL_DETAIL_GRID_1");h.operation(new CommerceDesignImageGateway.Operation("RUNNING",true,h.time,null,null));
        h.service.recover(h.candidate);
        assertEquals("UNKNOWN",h.job.get("status"));assertEquals("UNKNOWN",h.run.get("status"));
        var recovery=h.json.readTree(h.report).path("generationRecovery");assertEquals("ACTIVE",recovery.path("status").asText());
        assertEquals(0,recovery.path("unavailableChecks").asInt(0));assertTrue(recovery.path("nextAttemptAt").asLong()>h.time);
        h.refreshCandidate();h.service.recover(h.candidate);verify(h.gateway,times(1)).operation("job-id");
        assertEquals(0,h.terminalMutations);assertEquals(1,h.tx.commits);
    }

    @Test void absentRuntimeOperationUsesPersistentBoundedBackoffThenClearlyStops() {
        var h=new Harness("MODEL_DETAIL_1");h.operation(null);
        for(int n=1;n<=3;n++) {
            h.refreshCandidate();h.service.recover(h.candidate);
            var recovery=h.json.readTree(h.report).path("generationRecovery");assertEquals(n,recovery.path("unavailableChecks").asInt());
            if(n<3) {
                assertEquals("UNKNOWN",h.run.get("status"));assertEquals("LOOKUP_RETRY_PENDING",recovery.path("status").asText());
                h.time=recovery.path("nextAttemptAt").asLong()+1;
                h.recreateService(); // Counters survive a service restart.
            }
        }
        assertEquals("FAILED",h.run.get("status"));assertEquals("FAILED",h.job.get("status"));assertTrue(h.message.contains("连续3次"));
        assertFalse(h.json.readTree(h.report).path("generationRecovery").path("terminalOperation").asBoolean());
        assertFalse(h.json.readTree(h.report).path("generationRecovery").path("detailRetryEligible").asBoolean());
        verify(h.gateway,times(3)).operation("job-id");
    }

    @Test void activeWaitHasAPersistentThirtyMinuteLimitWithoutAuthorizingAnotherSubmission() {
        for(boolean agedRuntime:List.of(false,true)) {
            var h=new Harness("MODEL_DETAIL_GRID_1");
            long start=h.time;
            if(agedRuntime)h.time+=CommerceDetailGenerationRecovery.MAX_ACTIVE_WAIT_MS+1;
            h.operation(new CommerceDesignImageGateway.Operation("RUNNING",true,start,null,null));
            h.service.recover(h.candidate);
            if(!agedRuntime) {
                assertEquals("UNKNOWN",h.run.get("status"));
                h.time+=CommerceDetailGenerationRecovery.MAX_ACTIVE_WAIT_MS+1;h.refreshCandidate();h.recreateService();
                h.operation(new CommerceDesignImageGateway.Operation("RUNNING",true,h.time,null,null));
                h.service.recover(h.candidate);
            }
            assertEquals("FAILED",h.run.get("status"));assertEquals("FAILED",h.job.get("status"));
            var recovery=h.json.readTree(h.report).path("generationRecovery");
            assertEquals("ACTIVE_OPERATION_TIMEOUT",recovery.path("reasonCode").asText());assertEquals("job-id",recovery.path("operationId").asText());
            assertFalse(recovery.path("terminalOperation").asBoolean());assertFalse(recovery.path("detailRetryEligible").asBoolean());
            assertTrue(h.message.contains("不重复提交"));
        }
    }

    @Test void getFailureAndInactiveRunningAndInvalidCompletedResultsShareBoundedUnknownHandling() {
        for(String state:List.of("HTTP_ERROR","RUNNING","COMPLETED","NOT_FOUND")) {
            var h=new Harness("MODEL_DETAIL_1");
            if(state.equals("HTTP_ERROR"))doThrow(new IllegalStateException("https://secret/?token=private")).when(h.gateway).operation("job-id");
            else h.operation(new CommerceDesignImageGateway.Operation(state,false,h.time,null,null));
            h.check("{\"generationRecovery\":{\"unavailableChecks\":2,\"nextAttemptAt\":0}}");h.service.recover(h.candidate);
            assertEquals("FAILED",h.run.get("status"),state);assertFalse(h.message.contains("private"));assertFalse(h.report.contains("token"));
        }
    }

    @Test void preservedLocalResultWinsOverRemoteFailureAndDoesNotGetOverwritten() throws Exception {
        var h=new Harness("MODEL_DETAIL_1");String prior=h.json.writeValueAsString(h.response());h.job.put("result_json",prior);
        h.operation(failed(Map.of("failureKind","NETWORK","providerStatus",502)));
        h.service.recover(h.candidate);
        assertEquals("RESULT_RECEIVED",h.job.get("status"));assertEquals(prior,h.job.get("result_json"));assertEquals("QUEUED",h.run.get("status"));
        assertFalse(h.json.readTree(h.report).path("generationRecovery").path("detailRetryEligible").asBoolean());
    }

    @Test void completedResponsePreservesUnrelatedMetadataAndConflictingExistingFieldsStop() throws Exception {
        var h=new Harness("MODEL_DETAIL_1");h.job.put("result_json","{\"oldProviderNote\":\"keep\"}");
        h.operation(new CommerceDesignImageGateway.Operation("COMPLETED",false,h.time,h.response(),null));h.service.recover(h.candidate);
        assertEquals("keep",h.json.readTree((String)h.job.get("result_json")).path("oldProviderNote").asText());
        assertTrue(CommerceDetailGenerationRecovery.usable(h.json.readValue((String)h.job.get("result_json"),CommerceDesignImageGateway.Response.class)));
        for(String existing:List.of("[]","{\"artifact\":{\"dataBase64\":\"corrupt-but-retained\"}}")) {
            var conflict=new Harness("MODEL_DETAIL_1");conflict.job.put("result_json",existing);
            conflict.operation(new CommerceDesignImageGateway.Operation("COMPLETED",false,conflict.time,conflict.response(),null));conflict.service.recover(conflict.candidate);
            assertEquals(existing,conflict.job.get("result_json"));assertEquals("FAILED",conflict.run.get("status"));
            assertEquals("RESULT_METADATA_CONFLICT",conflict.json.readTree(conflict.report).path("generationRecovery").path("reasonCode").asText());
        }
    }

    @Test void liveLeaseOrChangedExecutionOrJobAfterHttpCannotBeOverwritten() throws Exception {
        for(String race:List.of("LEASE","EXECUTION_STATE","EXECUTION_TIME","JOB_STATE","JOB_TIME","CHECK")) {
            var h=new Harness("MODEL_DETAIL_1");
            h.operation(new CommerceDesignImageGateway.Operation("COMPLETED",false,h.time,h.response(),null));
            if(race.equals("LEASE"))h.liveLease=true;
            if(race.equals("EXECUTION_STATE"))h.run.put("status","RUNNING");
            if(race.equals("EXECUTION_TIME"))h.run.put("updated_at",Timestamp.from(java.time.Instant.EPOCH));
            if(race.equals("JOB_STATE"))h.job.put("status","READY");
            if(race.equals("JOB_TIME"))h.job.put("updated_at",Timestamp.from(java.time.Instant.EPOCH));
            if(race.equals("CHECK"))h.report="{\"generationRecovery\":{\"checkedAt\":42}}";
            h.service.recover(h.candidate);assertEquals(0,h.terminalMutations,race);assertEquals(0,h.checkWrites,race);
        }
    }

    @Test void mutationsRollbackTogetherWhenCheckJobOrExecutionCompareAndSetFails() throws Exception {
        for(String failedWrite:List.of("CHECK","JOB","EXECUTION")) {
            var h=new Harness("MODEL_DETAIL_1");h.rejectWrite=failedWrite;
            h.operation(new CommerceDesignImageGateway.Operation("COMPLETED",false,h.time,h.response(),null));
            assertThrows(IllegalStateException.class,()->h.service.recover(h.candidate));
            assertEquals(0,h.tx.commits);assertEquals(1,h.tx.rollbacks,failedWrite);
        }
    }

    @Test void schedulerScopeIsExplicitAndOtherJobsNeverCallTheRuntime() {
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("e.stage='MOCKUP'"));
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("e.status IN ('UNKNOWN','WAITING_INPUT')"));
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("j.status IN ('UNKNOWN','SUBMITTING','RESULT_RECEIVED')"));
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("lease_until<=NOW(6)"));
        for(String kind:List.of("VARIATE","DRAPE_MASTER","MODEL_DETAIL_0","MODEL_DETAIL_GRID_0","MODEL_DETAIL_1_EXTRA")) {
            var h=new Harness(kind);h.service.recover(h.candidate);verifyNoInteractions(h.gateway);assertEquals(0,h.tx.commits);
        }
        for(String state:List.of("RUNNING","PAUSED","FAILED","QUEUED")) {
            var h=new Harness("MODEL_DETAIL_1");h.candidate.put("execution_status",state);h.service.recover(h.candidate);verifyNoInteractions(h.gateway);
        }
        var h=new Harness("MODEL_DETAIL_1");h.candidate.put("job_status","FAILED");h.service.recover(h.candidate);verifyNoInteractions(h.gateway);
    }

    @Test void validatesReturnedImageOrAbsoluteDownloadUrlWithoutFetchingIt() throws Exception {
        var h=new Harness("MODEL_DETAIL_1");assertTrue(CommerceDetailGenerationRecovery.usable(h.response()));
        assertFalse(CommerceDetailGenerationRecovery.usable(new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact("YQ==","image/png",null,null,null),null)));
        for(String url:List.of("file:///tmp/output","/relative.png","http://user:secret@example.com/image","not a url"))
            assertFalse(CommerceDetailGenerationRecovery.usable(new CommerceDesignImageGateway.Response(null,new CommerceDesignImageGateway.PendingResult(url,"image/png",null,null,null))));
        assertTrue(CommerceDetailGenerationRecovery.usable(new CommerceDesignImageGateway.Response(null,new CommerceDesignImageGateway.PendingResult("https://example.com/image?signature=private","image/png",null,null,null))));
    }

    @Test void largeGridResponseCanBeRecoveredWithoutApplyingTheSingleImageEightMiBLimit() throws Exception {
        var noisy=new java.awt.image.BufferedImage(2048,2048,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random=new Random(31);for(int y=0;y<2048;y++)for(int x=0;x<2048;x++)noisy.setRGB(x,y,random.nextInt(0x1000000));
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(noisy,"png",bytes);
        assertTrue(bytes.size()>8*1024*1024);
        var response=new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(bytes.toByteArray()),"image/png","grok","grid",null),null);
        assertFalse(CommerceDetailGenerationRecovery.usable(response));assertTrue(CommerceDetailGenerationRecovery.usable(response,true));
        var h=new Harness("MODEL_DETAIL_GRID_1");h.operation(new CommerceDesignImageGateway.Operation("COMPLETED",false,h.time,response,null));h.service.recover(h.candidate);
        assertEquals("RESULT_RECEIVED",h.job.get("status"));assertEquals("QUEUED",h.run.get("status"));
        assertEquals(response.artifact().dataBase64(),h.json.readTree((String)h.job.get("result_json")).path("artifact").path("dataBase64").asText(),"retain original paid response; collection owns normalization");
    }

    @Test void receivedDetailsGetFourPersistedArchiveAttemptsWithBoundedBackoffAndNoGenerationLookup() throws Exception {
        for(String kind:List.of("MODEL_DETAIL_1","MODEL_DETAIL_GRID_1")) {
            var h=new Harness(kind);String original=h.json.writeValueAsString(h.response());h.job.put("result_json",original);
            h.job.put("status","RESULT_RECEIVED");h.run.put("status","WAITING_INPUT");
            long[] delays={15_000,30_000,60_000,120_000};
            for(int n=0;n<4;n++) {
                long failedAt=h.time;h.job.put("updated_at",new Timestamp(failedAt));h.refreshCandidate();
                h.service.recover(h.candidate);
                var waiting=h.json.readTree(h.report).path("generationRecovery");
                assertEquals("WAITING_INPUT",h.run.get("status"));assertEquals(failedAt+delays[n],waiting.path("nextAttemptAt").asLong());
                assertEquals(n,waiting.path("archiveAttempts").asInt(0));
                h.time=failedAt+delays[n]-1;h.refreshCandidate();int writes=h.checkWrites;h.service.recover(h.candidate);assertEquals(writes,h.checkWrites);
                h.time+=2;h.recreateService();h.refreshCandidate();h.service.recover(h.candidate);
                assertEquals("QUEUED",h.run.get("status"));assertEquals(n+1,h.json.readTree(h.report).path("generationRecovery").path("archiveAttempts").asInt());
                assertEquals("RESULT_RECEIVED",h.job.get("status"));assertEquals(original,h.job.get("result_json"));
                h.run.put("status","WAITING_INPUT");h.time+=1000;
            }
            h.refreshCandidate();h.service.recover(h.candidate);
            assertEquals("FAILED",h.run.get("status"));assertEquals("RESULT_RECEIVED",h.job.get("status"));assertEquals(original,h.job.get("result_json"));
            assertTrue(h.message.contains("原结果已保存，归档失败"));
            assertEquals("ARCHIVE_RETRIES_EXHAUSTED",h.json.readTree(h.report).path("generationRecovery").path("reasonCode").asText());
            assertEquals(0,h.terminalMutations);verifyNoInteractions(h.gateway);
        }
    }

    @Test void archivalRecoveryIsPerJobAndRunningTaskCasStillWins() throws Exception {
        var exhausted=new Harness("MODEL_DETAIL_1");exhausted.job.put("status","RESULT_RECEIVED");exhausted.job.put("result_json",exhausted.json.writeValueAsString(exhausted.response()));exhausted.run.put("status","WAITING_INPUT");
        exhausted.check("{\"passed\":true,\"generationRecovery\":{\"archiveAttempts\":4}}");exhausted.refreshCandidate();exhausted.service.recover(exhausted.candidate);
        assertEquals("FAILED",exhausted.run.get("status"));assertTrue(exhausted.json.readTree(exhausted.report).path("passed").asBoolean());
        var other=new Harness("MODEL_DETAIL_2");other.job.put("status","RESULT_RECEIVED");other.job.put("result_json",other.json.writeValueAsString(other.response()));other.run.put("status","WAITING_INPUT");other.refreshCandidate();other.service.recover(other.candidate);
        assertEquals("QUEUED",other.run.get("status"));assertEquals(1,other.json.readTree(other.report).path("generationRecovery").path("archiveAttempts").asInt());
        var raced=new Harness("MODEL_DETAIL_GRID_1");raced.job.put("status","RESULT_RECEIVED");raced.run.put("status","WAITING_INPUT");raced.refreshCandidate();raced.run.put("status","QUEUED");raced.service.recover(raced.candidate);
        assertEquals(0,raced.checkWrites);assertEquals(0,raced.terminalMutations);
        verifyNoInteractions(exhausted.gateway,other.gateway,raced.gateway);
    }

    @Test void savedProviderResponseReplaysTheSameJobThreeTimesWithPersistentBackoffAndRetainsOriginalRequest() {
        for(String kind:List.of("MODEL_DETAIL_7","MODEL_DETAIL_GRID_2")) {
            var h=new Harness(kind);
            h.job.putAll(Map.of("result_json","{\"partialResult\":\"retained\"}","request_key","original-key",
                    "reference_keys_json","[\"original-reference\"]","prompt","original prompt","attempt",1));
            h.check("{\"sourceDetailImageId\":\"original-detail\"}");
            var requestBefore=new HashMap<>(h.job);requestBefore.remove("status");requestBefore.remove("updated_at");
            h.operation(new CommerceDesignImageGateway.Operation("RECOVERABLE",false,h.time,null,Map.of("savedResponseAvailable",true)));
            long[] delays={15_000,30_000,60_000};
            for(int n=0;n<3;n++) {
                h.job.put("status",n%2==0?"UNKNOWN":"SUBMITTING");h.run.put("status",n%2==0?"UNKNOWN":"WAITING_INPUT");
                long failedAt=h.time;h.job.put("updated_at",new Timestamp(failedAt));h.refreshCandidate();h.service.recover(h.candidate);
                var pending=h.json.readTree(h.report).path("generationRecovery");
                assertEquals("SAVED_RESPONSE_RETRY_PENDING",pending.path("status").asText());
                assertEquals(failedAt+delays[n],pending.path("nextAttemptAt").asLong());assertEquals(n,pending.path("savedResponseReplays").asInt(0));
                h.time=failedAt+delays[n]-1;h.refreshCandidate();int lookups=mockingDetails(h.gateway).getInvocations().size();
                h.service.recover(h.candidate);assertEquals(lookups,mockingDetails(h.gateway).getInvocations().size());
                h.time+=2;h.recreateService();h.refreshCandidate();h.service.recover(h.candidate);
                assertEquals("PREPARED",h.job.get("status"));assertEquals("QUEUED",h.run.get("status"));
                var recovery=h.json.readTree(h.report).path("generationRecovery");assertEquals(n+1,recovery.path("savedResponseReplays").asInt());
                assertFalse(recovery.path("detailRetryEligible").asBoolean());assertFalse(recovery.has("nextAttemptAt"));
                var requestAfter=new HashMap<>(h.job);requestAfter.remove("status");requestAfter.remove("updated_at");assertEquals(requestBefore,requestAfter);
                assertEquals("original-detail",h.json.readTree(h.report).path("sourceDetailImageId").asText());h.time+=1000;
            }
            h.job.put("status","UNKNOWN");h.run.put("status","UNKNOWN");h.refreshCandidate();h.service.recover(h.candidate);
            assertEquals("FAILED",h.job.get("status"));assertEquals("FAILED",h.run.get("status"));
            assertEquals("SAVED_RESPONSE_REPLAY_EXHAUSTED",h.json.readTree(h.report).path("generationRecovery").path("reasonCode").asText());
            assertEquals("{\"partialResult\":\"retained\"}",h.job.get("result_json"));assertTrue(h.message.contains("原响应已保存"));
            assertEquals(3,h.preparedMutations);verify(h.gateway,times(7)).operation("job-id");verifyNoMoreInteractions(h.gateway);
        }
    }

    @Test void onlyAnInactiveRecoverableOperationWithExplicitPersistedResponseEvidenceCanBecomePrepared() {
        for(var evidence:List.of(Map.<String,Object>of(),Map.<String,Object>of("savedResponseAvailable",false),Map.<String,Object>of("savedResponseAvailable","true"))) {
            var h=new Harness("MODEL_DETAIL_GRID_1");h.check("{\"generationRecovery\":{\"unavailableChecks\":2}}");
            h.operation(new CommerceDesignImageGateway.Operation("RECOVERABLE",false,h.time,null,evidence));h.service.recover(h.candidate);
            assertEquals(0,h.preparedMutations);assertEquals("FAILED",h.run.get("status"));
        }
        for(String state:List.of("FAILED","RUNNING","COMPLETED")) {
            var h=new Harness("MODEL_DETAIL_1");h.operation(new CommerceDesignImageGateway.Operation(state,false,h.time,null,Map.of("savedResponseAvailable",true)));
            h.service.recover(h.candidate);assertEquals(0,h.preparedMutations);
        }
        var active=new Harness("MODEL_DETAIL_GRID_1");active.operation(new CommerceDesignImageGateway.Operation("RECOVERABLE",true,active.time,null,Map.of("savedResponseAvailable",true)));
        active.service.recover(active.candidate);assertEquals(0,active.preparedMutations);assertEquals("ACTIVE",active.json.readTree(active.report).path("generationRecovery").path("status").asText());
    }

    @Test void validLocalSavedResultTakesPriorityOverRecoverableProviderResponse() throws Exception {
        var h=new Harness("MODEL_DETAIL_GRID_1");String result=h.json.writeValueAsString(h.response());h.job.put("result_json",result);
        h.operation(new CommerceDesignImageGateway.Operation("RECOVERABLE",false,h.time,null,Map.of("savedResponseAvailable",true)));
        h.service.recover(h.candidate);assertEquals("RESULT_RECEIVED",h.job.get("status"));assertEquals(result,h.job.get("result_json"));
        assertEquals("QUEUED",h.run.get("status"));assertEquals(0,h.preparedMutations);
    }

    @Test void savedResponseRequeueHonorsConcurrentChangesAndRollsBackAnyFailedWrite() {
        for(String failedWrite:List.of("CHECK","JOB","EXECUTION")) {
            var h=new Harness("MODEL_DETAIL_GRID_1");h.rejectWrite=failedWrite;
            h.operation(new CommerceDesignImageGateway.Operation("RECOVERABLE",false,h.time,null,Map.of("savedResponseAvailable",true)));
            assertThrows(IllegalStateException.class,()->h.service.recover(h.candidate));assertEquals(1,h.tx.rollbacks);assertEquals(0,h.tx.commits);
        }
        for(String race:List.of("LEASE","JOB","CHECK")) {
            var h=new Harness("MODEL_DETAIL_7");h.operation(new CommerceDesignImageGateway.Operation("RECOVERABLE",false,h.time,null,Map.of("savedResponseAvailable",true)));
            if(race.equals("LEASE"))h.liveLease=true;
            if(race.equals("JOB"))h.job.put("status","READY");
            if(race.equals("CHECK"))h.report="{\"generationRecovery\":{\"savedResponseReplays\":3}}";
            h.service.recover(h.candidate);assertEquals(0,h.preparedMutations);assertEquals(0,h.checkWrites);
        }
    }

    @Test void crashedExecutionWithPersistedResponseImmediatelyResumesArchivalWithoutLookupOrRegeneration() throws Exception {
        for(String kind:List.of("MODEL_DETAIL_7","MODEL_DETAIL_GRID_2")) {
            var h=new Harness(kind);String original=h.json.writeValueAsString(h.response());
            h.job.put("status","RESULT_RECEIVED");h.job.put("result_json",original);h.job.put("updated_at",new Timestamp(h.time));
            h.run.put("status","UNKNOWN");h.refreshCandidate();h.service.recover(h.candidate);
            assertEquals("QUEUED",h.run.get("status"));assertEquals("RESULT_RECEIVED",h.job.get("status"));assertEquals(original,h.job.get("result_json"));
            assertEquals(1,h.json.readTree(h.report).path("generationRecovery").path("archiveAttempts").asInt());
            assertEquals(0,h.preparedMutations);assertEquals(0,h.terminalMutations);verifyNoInteractions(h.gateway);
        }
    }

    @Test void archivedDetailsResumeAnExpiredExecutionForSplitOrReviewWithoutChangingAnyJob() {
        for(String kind:List.of("MODEL_DETAIL_9","MODEL_DETAIL_GRID_7")) {
            var h=new Harness(kind);h.job.put("status","READY");h.job.put("storage_key","archived-grid");h.job.put("result_json","original metadata");
            h.check("{\"passed\":true,\"sourceDetailImageId\":\"original-detail\"}");h.refreshCandidate();
            var original=new HashMap<>(h.job);h.service.recover(h.candidate);
            assertEquals("QUEUED",h.run.get("status"));assertEquals(original,h.job);
            var report=h.json.readTree(h.report);assertTrue(report.path("passed").asBoolean());assertEquals("original-detail",report.path("sourceDetailImageId").asText());
            assertEquals("READY_CONTINUED",report.path("generationRecovery").path("status").asText());
            verify(h.db).queryForList(CommerceDetailGenerationRecovery.READY_BLOCKERS_LOCK,"run","org");
            verifyNoInteractions(h.gateway);assertEquals(0,h.preparedMutations);assertEquals(0,h.terminalMutations);
        }
    }

    @Test void anOldReadyDetailCannotMaskAnyPendingJobAndLookupRecoveryRemainsAvailable() {
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("NOT EXISTS"));
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("pending.execution_id=j.execution_id AND pending.organization_id=j.organization_id"));
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("pending.status NOT IN ('READY','RETRIED','FAILED','REJECTED','INVALID_IMAGE_REJECTED','TRANSPARENCY_REJECTED')"));
        for(String state:List.of("UNKNOWN","SUBMITTING","PREPARED","RESULT_RECEIVED","FUTURE_IN_PROGRESS")) {
            var h=new Harness("MODEL_DETAIL_1");h.job.put("status","READY");h.job.put("storage_key","old-ready-1");h.refreshCandidate();
            h.readyBlockers=List.of(Map.of("id","new-detail-7","status",state));h.service.recover(h.candidate);
            assertEquals("UNKNOWN",h.run.get("status"));assertEquals(0,h.checkWrites);assertEquals(0,h.terminalMutations);verifyNoInteractions(h.gateway);
        }
        var unknown=new Harness("MODEL_DETAIL_7");unknown.operation(failed(Map.of("failureKind","NETWORK","providerStatus",502)));
        unknown.service.recover(unknown.candidate);assertEquals("QUEUED",unknown.run.get("status"));assertEquals("FAILED",unknown.job.get("status"));
        verify(unknown.db,never()).queryForList(eq(CommerceDetailGenerationRecovery.READY_BLOCKERS_LOCK),any(Object[].class));
    }

    @Test void readyContinuationRequiresAnUnchangedStorageKeyAndExpiredUnknownExecution() {
        for(String condition:List.of("NO_KEY","BLANK_KEY","CHANGED_KEY","WAITING_INPUT","LIVE_LEASE","JOB_TIMESTAMP")) {
            var h=new Harness("MODEL_DETAIL_GRID_1");h.job.put("status","READY");h.job.put("storage_key","archived-grid");
            if(condition.equals("NO_KEY"))h.job.remove("storage_key");
            if(condition.equals("BLANK_KEY"))h.job.put("storage_key"," ");
            if(condition.equals("WAITING_INPUT"))h.run.put("status","WAITING_INPUT");
            h.refreshCandidate();
            if(condition.equals("CHANGED_KEY"))h.job.put("storage_key","new-grid");
            if(condition.equals("LIVE_LEASE"))h.liveLease=true;
            if(condition.equals("JOB_TIMESTAMP"))h.job.put("updated_at",new Timestamp(h.time+1));
            h.service.recover(h.candidate);assertEquals(0,h.checkWrites,condition);assertEquals(0,h.terminalMutations,condition);verifyNoInteractions(h.gateway);
        }
    }

    @Test void readyContinuationRetainsArchivedImageOnCorruptMetadataAndRollsBackFailedWrites() {
        var corrupt=new Harness("MODEL_DETAIL_1");corrupt.job.put("status","READY");corrupt.job.put("storage_key","archived-image");
        corrupt.check("[]");corrupt.refreshCandidate();corrupt.service.recover(corrupt.candidate);
        assertEquals("FAILED",corrupt.run.get("status"));assertEquals("READY",corrupt.job.get("status"));assertEquals("archived-image",corrupt.job.get("storage_key"));
        assertEquals(0,corrupt.terminalMutations);verifyNoInteractions(corrupt.gateway);
        for(String failedWrite:List.of("CHECK","EXECUTION")) {
            var h=new Harness("MODEL_DETAIL_GRID_1");h.job.put("status","READY");h.job.put("storage_key","archived-grid");h.refreshCandidate();h.rejectWrite=failedWrite;
            assertThrows(IllegalStateException.class,()->h.service.recover(h.candidate));assertEquals(1,h.tx.rollbacks);assertEquals(0,h.tx.commits);
            assertEquals("READY",h.job.get("status"));assertEquals(0,h.terminalMutations);
        }
    }

    @Test void preparedDetailAfterCheckpointCrashContinuesTheExactOriginalRequestWithoutJobWrites() {
        for(String kind:List.of("MODEL_DETAIL_8","MODEL_DETAIL_GRID_7")) {
            var h=new Harness(kind);h.job.put("status","PREPARED");h.job.put("request_key","original-reserved-key");
            h.job.put("prompt","frozen prompt");h.job.put("reference_keys","[\"original-product\",\"original-style\"]");
            h.job.put("model_config_id","frozen-model");h.job.put("result_json","{\"preserved\":true}");h.refreshCandidate();
            var original=new HashMap<>(h.job);h.service.recover(h.candidate);
            assertEquals("QUEUED",h.run.get("status"));assertEquals(original,h.job);
            assertEquals("PREPARED_CONTINUED",h.json.readTree(h.report).path("generationRecovery").path("status").asText());
            verify(h.db).queryForList(CommerceDetailGenerationRecovery.PREPARED_BLOCKERS_LOCK,"run","org");
            verify(h.db,never()).update(startsWith("UPDATE commerce_batch_artwork_jobs"),any(Object[].class));
            verify(h.db,never()).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"),any(Object[].class));
            verify(h.db,never()).update(contains("commerce_batch_model"),any(Object[].class));
            verifyNoInteractions(h.gateway);assertEquals(0,h.preparedMutations);assertEquals(0,h.terminalMutations);
        }
    }

    @Test void preparedContinuationNeverMasksUnknownOrOtherUnresolvedExternalRequests() {
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("j.status='PREPARED' AND e.status='UNKNOWN'"));
        assertTrue(CommerceDetailGenerationRecovery.CANDIDATES.contains("unfinished.execution_id=j.execution_id AND unfinished.organization_id=j.organization_id"));
        assertTrue(CommerceDetailGenerationRecovery.PREPARED_BLOCKERS_LOCK.contains("'PREPARED','RESULT_RECEIVED') FOR UPDATE"));
        for(String state:List.of("UNKNOWN","SUBMITTING","RUNNING","PENDING","QUEUED","FUTURE_EXTERNAL_STATE")) {
            var h=new Harness("MODEL_DETAIL_8");h.job.put("status","PREPARED");h.job.put("request_key","original-key");h.refreshCandidate();
            h.preparedBlockers=List.of(Map.of("id","another-image-request","status",state));h.service.recover(h.candidate);
            assertEquals("UNKNOWN",h.run.get("status"));assertEquals("PREPARED",h.job.get("status"));
            assertEquals(0,h.checkWrites);assertEquals(0,h.preparedMutations);assertEquals(0,h.terminalMutations);verifyNoInteractions(h.gateway);
        }
    }

    @Test void preparedContinuationRequiresExpiredUnknownExecutionAndUnchangedValidRequestIdentity() {
        for(String race:List.of("MISSING_KEY","BLANK_KEY","CHANGED_KEY","LIVE_LEASE","WAITING_INPUT","RUNNING","QUEUED","JOB_STATUS","JOB_TIME","RUN_TIME","CHECK")) {
            var h=new Harness("MODEL_DETAIL_GRID_1");h.job.put("status","PREPARED");h.job.put("request_key","original-key");
            if(race.equals("MISSING_KEY"))h.job.remove("request_key");
            if(race.equals("BLANK_KEY"))h.job.put("request_key"," ");
            if(Set.of("WAITING_INPUT","RUNNING","QUEUED").contains(race))h.run.put("status",race);
            h.refreshCandidate();
            if(race.equals("CHANGED_KEY"))h.job.put("request_key","changed-key");
            if(race.equals("LIVE_LEASE"))h.liveLease=true;
            if(race.equals("JOB_STATUS"))h.job.put("status","SUBMITTING");
            if(race.equals("JOB_TIME"))h.job.put("updated_at",new Timestamp(h.time+1));
            if(race.equals("RUN_TIME"))h.run.put("updated_at",new Timestamp(h.time+1));
            if(race.equals("CHECK"))h.report="{\"generationRecovery\":{\"checkedAt\":42}}";
            h.service.recover(h.candidate);assertEquals(0,h.checkWrites,race);assertEquals(0,h.preparedMutations,race);assertEquals(0,h.terminalMutations,race);verifyNoInteractions(h.gateway);
        }
    }

    @Test void preparedContinuationPreservesTheOriginalJobIfRecoveryMetadataIsInvalidOrWritesFail() {
        var corrupt=new Harness("MODEL_DETAIL_8");corrupt.job.put("status","PREPARED");corrupt.job.put("request_key","original-key");
        corrupt.check("[]");corrupt.refreshCandidate();var original=new HashMap<>(corrupt.job);corrupt.service.recover(corrupt.candidate);
        assertEquals("FAILED",corrupt.run.get("status"));assertEquals(original,corrupt.job);verifyNoInteractions(corrupt.gateway);
        for(String failedWrite:List.of("CHECK","EXECUTION")) {
            var h=new Harness("MODEL_DETAIL_8");h.job.put("status","PREPARED");h.job.put("request_key","original-key");h.refreshCandidate();h.rejectWrite=failedWrite;
            assertThrows(IllegalStateException.class,()->h.service.recover(h.candidate));assertEquals(1,h.tx.rollbacks);assertEquals(0,h.tx.commits);
            assertEquals("PREPARED",h.job.get("status"));assertEquals("original-key",h.job.get("request_key"));assertEquals(0,h.preparedMutations);assertEquals(0,h.terminalMutations);
        }
    }

    private static CommerceDesignImageGateway.Operation failed(Map<String,Object> error) {return new CommerceDesignImageGateway.Operation("FAILED",false,1,null,error);}
    private static void verifyNoMoreInteractionsExceptLookup(Harness h){verify(h.gateway).operation("job-id");verifyNoMoreInteractions(h.gateway);}

    static final class Harness {
        final JdbcTemplate db=mock(JdbcTemplate.class);
        final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);
        final ObjectMapper json=new ObjectMapper();
        final Map<String,Object> candidate=new HashMap<>(),run=new HashMap<>(),job=new HashMap<>();
        final Tx tx=new Tx();
        CommerceDetailGenerationRecovery service;
        long time=2_000_000;
        String report, message, rejectWrite;
        boolean liveLease;
        List<Map<String,Object>> readyBlockers=List.of();
        List<Map<String,Object>> preparedBlockers=List.of();
        int terminalMutations,preparedMutations,checkWrites;
        Harness(String kind) {
            var timestamp=new Timestamp(1_000_000);
            candidate.putAll(Map.of("job_id","job-id","execution_id","run","organization_id","org","kind",kind,"job_status","UNKNOWN","execution_status","UNKNOWN","stage","MOCKUP","job_updated_at",timestamp,"execution_updated_at",timestamp));
            run.putAll(Map.of("id","run","stage","MOCKUP","status","UNKNOWN","updated_at",timestamp));
            job.putAll(Map.of("id","job-id","kind",kind,"status","UNKNOWN","updated_at",timestamp));
            when(db.queryForList(CommerceDetailGenerationRecovery.EXECUTION_LOCK,"run","org")).thenAnswer(call->{assertTrue(tx.active.get());return liveLease?List.of():List.of(run);});
            when(db.queryForList(CommerceDetailGenerationRecovery.JOB_LOCK,"job-id","run","org")).thenReturn(List.of(job));
            when(db.queryForList(CommerceDetailGenerationRecovery.CHECK_LOCK,"job-id")).thenAnswer(call->report==null?List.of():List.of(Map.of("report_json",report)));
            when(db.queryForList(CommerceDetailGenerationRecovery.READY_BLOCKERS_LOCK,"run","org")).thenAnswer(call->{assertTrue(tx.active.get());return readyBlockers;});
            when(db.queryForList(CommerceDetailGenerationRecovery.PREPARED_BLOCKERS_LOCK,"run","org")).thenAnswer(call->{assertTrue(tx.active.get());return preparedBlockers;});
            doAnswer(call->{assertTrue(tx.active.get());if("CHECK".equals(rejectWrite))return 0;report=call.getArgument(2);checkWrites++;return 1;})
                    .when(db).update(startsWith("INSERT INTO commerce_batch_artwork_checks"),any(Object[].class));
            doAnswer(call->{assertTrue(tx.active.get());if("JOB".equals(rejectWrite))return 0;job.put("status","RESULT_RECEIVED");job.put("result_json",call.getArgument(1));message=call.getArgument(2);terminalMutations++;return 1;})
                    .when(db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='RESULT_RECEIVED'"),any(Object[].class));
            doAnswer(call->{assertTrue(tx.active.get());if("JOB".equals(rejectWrite))return 0;job.put("status","FAILED");message=call.getArgument(1);terminalMutations++;return 1;})
                    .when(db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='FAILED'"),any(Object[].class));
            doAnswer(call->{assertTrue(tx.active.get());if("JOB".equals(rejectWrite))return 0;job.put("status","PREPARED");message=call.getArgument(1);preparedMutations++;return 1;})
                    .when(db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='PREPARED'"),any(Object[].class));
            doAnswer(call->{assertTrue(tx.active.get());if("EXECUTION".equals(rejectWrite))return 0;run.put("status",call.getArgument(1));message=call.getArgument(2);return 1;})
                    .when(db).update(startsWith("UPDATE commerce_batch_executions SET status=?"),any(Object[].class));
            recreateService();
        }
        void recreateService(){service=new CommerceDetailGenerationRecovery(db,gateway,new TransactionTemplate(tx),json,()->time);}
        void operation(CommerceDesignImageGateway.Operation value){doAnswer(call->{assertFalse(tx.active.get(),"HTTP must run outside the short database transaction");return value;}).when(gateway).operation("job-id");}
        void check(String value){report=value;candidate.put("report_json",value);}
        void refreshCandidate(){candidate.put("report_json",report);candidate.put("job_status",job.get("status"));candidate.put("storage_key",job.get("storage_key"));candidate.put("request_key",job.get("request_key"));candidate.put("execution_status",run.get("status"));candidate.put("job_updated_at",job.get("updated_at"));candidate.put("execution_updated_at",run.get("updated_at"));}
        CommerceDesignImageGateway.Response response() throws Exception {
            byte[] bytes=SavedArtworkFixture.image(0xffaabbcc,false);
            if(((String)candidate.get("kind")).startsWith("MODEL_DETAIL_GRID_"))bytes=CommerceModelDetailGridComposer.compose(List.of(bytes));
            return new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(bytes),"image/png","grok","detail",null),null);
        }
    }
    static final class Tx implements PlatformTransactionManager {
        final AtomicBoolean active=new AtomicBoolean();int commits,rollbacks;
        public TransactionStatus getTransaction(TransactionDefinition definition){assertFalse(active.getAndSet(true));return new SimpleTransactionStatus();}
        public void commit(TransactionStatus status){assertTrue(active.getAndSet(false));commits++;}
        public void rollback(TransactionStatus status){assertTrue(active.getAndSet(false));rollbacks++;}
    }
}
