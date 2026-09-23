package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.mapper.CommerceBatchExecutionMapper;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceBatchExecutionWorkerTest {
    final CommerceBatchExecutionMapper runs = mock(CommerceBatchExecutionMapper.class);
    final PlatformUserMapper users = mock(PlatformUserMapper.class);
    final CommerceBatchStageHandler handler = mock(CommerceBatchStageHandler.class);
    final ScheduledExecutorService heartbeatExecutor = mock(ScheduledExecutorService.class);
    final org.springframework.transaction.PlatformTransactionManager checkpointTransactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
    final Map<String, CommerceBatchExecution> rows = new ConcurrentHashMap<>();
    final MutableClock clock = new MutableClock();
    final ControlledExecutor executor = new ControlledExecutor();
    final PlatformUser user = new PlatformUser("org", null, "tester", "tester", null, null, null, null, "hash");
    Function<CommerceBatchExecution, CommerceBatchStageHandler.Result> body = run -> new CommerceBatchStageHandler.Result(true, "saved");
    CommerceBatchExecutionWorker worker;
    Runnable scheduledHeartbeat;

    @BeforeEach void mapperFixture() {
        when(checkpointTransactions.getTransaction(any())).thenAnswer(call -> new org.springframework.transaction.support.SimpleTransactionStatus());
        when(handler.stage()).thenReturn(CommerceBatchExecution.Stage.EXTRACT);
        when(handler.execute(any(), any())).thenAnswer(call -> body.apply(call.getArgument(0)));
        when(users.selectById(user.getId())).thenReturn(user);
        when(users.selectPermissionCodes(user.getId())).thenReturn(List.of("product:publish"));
        when(runs.queued()).thenAnswer(call -> rows.values().stream().filter(r -> r.status().equals("QUEUED"))
                .map(CommerceBatchExecution::id).sorted().toList());
        when(runs.byId(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(runs.claim(anyString(), anyString(), any(), any())).thenAnswer(call -> {
            String id = call.getArgument(0), token = call.getArgument(1);
            var changed = new AtomicBoolean();
            rows.computeIfPresent(id, (key, row) -> {
                if (!row.status().equals("QUEUED")) return row;
                changed.set(true);
                return copy(row, row.stage(), "RUNNING", token, call.getArgument(3));
            });
            return changed.get() ? 1 : 0;
        });
        when(runs.renew(anyString(), anyString(), any(), any())).thenAnswer(call -> {
            var changed = new AtomicBoolean();
            rows.computeIfPresent(call.getArgument(0), (key, row) -> {
                if (!owns(row, call.getArgument(1), call.getArgument(2))) return row;
                changed.set(true);
                Instant until = call.getArgument(3);
                return copy(row, row.stage(), row.status(), row.leaseToken(), row.leaseUntil().isAfter(until) ? row.leaseUntil() : until);
            });
            return changed.get() ? 1 : 0;
        });
        when(runs.finish(anyString(), anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            var changed = new AtomicBoolean();
            rows.computeIfPresent(call.getArgument(0), (key, row) -> {
                if (!owns(row, call.getArgument(1), call.getArgument(5))) return row;
                changed.set(true);
                String status = call.getArgument(3);
                if (row.status().equals("PAUSING") && status.equals("QUEUED")) status = "PAUSED";
                return new CommerceBatchExecution(row.id(),row.organizationId(),row.batchId(),row.createdBy(),
                        call.getArgument(2),status,call.getArgument(4),null,null,row.createdAt(),call.getArgument(5));
            });
            return changed.get() ? 1 : 0;
        });
        when(runs.drain(anyString(), anyString(), any())).thenAnswer(call -> {
            var changed = new AtomicBoolean();
            rows.computeIfPresent(call.getArgument(0), (key, row) -> {
                if (!owns(row, call.getArgument(1), call.getArgument(2)) || !row.status().equals("RUNNING")) return row;
                changed.set(true);
                return copy(row, row.stage(), "PAUSING", row.leaseToken(), row.leaseUntil());
            });
            return changed.get() ? 1 : 0;
        });
        when(runs.expire(any())).thenAnswer(call -> {
            var count = new AtomicInteger();
            rows.replaceAll((id, row) -> {
                if (!Set.of("RUNNING", "PAUSING").contains(row.status()) || row.leaseUntil().isAfter(call.getArgument(0))) return row;
                count.incrementAndGet();
                return copy(row, row.stage(), "UNKNOWN", null, null);
            });
            return count.get();
        });
    }

    void start(int capacity, ExecutorService background) {
        worker = new CommerceBatchExecutionWorker(runs, users, List.of(handler),
                new CommerceBatchExecutionCheckpoint(runs,checkpointTransactions), clock, capacity, background, heartbeatExecutor);
        worker.startHeartbeat();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(heartbeatExecutor).scheduleWithFixedDelay(callback.capture(), eq(30_000L), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        scheduledHeartbeat = callback.getValue();
    }
    void enqueue(String id) {
        rows.put(id, new CommerceBatchExecution(id, "org", "batch-" + id, user.getId(), "EXTRACT", "QUEUED", null,
                null, null, clock.instant(), clock.instant()));
    }
    boolean owns(CommerceBatchExecution row, String token, Instant now) {
        return Set.of("RUNNING", "PAUSING").contains(row.status()) && Objects.equals(row.leaseToken(), token)
                && row.leaseUntil() != null && row.leaseUntil().isAfter(now);
    }
    CommerceBatchExecution copy(CommerceBatchExecution r, String stage, String status, String token, Instant until) {
        return new CommerceBatchExecution(r.id(), r.organizationId(), r.batchId(), r.createdBy(), stage, status,
                r.message(), token, until, r.createdAt(), r.updatedAt());
    }
    @AfterEach void shutdown() { if (worker != null) worker.close(); }

    @Test void sevenMinuteModelCallRenewsIndependentlyAndSavesItsNextStage() {
        enqueue("one"); start(2, executor);
        body = run -> {
            for (int i = 0; i < 14; i++) {
                clock.advance(Duration.ofSeconds(30));
                scheduledHeartbeat.run();
                assertTrue(rows.get(run.id()).leaseUntil().isAfter(clock.instant()));
            }
            return new CommerceBatchStageHandler.Result(true, "long result saved");
        };
        worker.poll();
        verify(handler, never()).execute(any(), any());
        executor.next();
        assertEquals(Instant.parse("2026-09-14T00:07:00Z"), clock.instant());
        assertEquals("QUEUED", rows.get("one").status());
        assertEquals("VARIATE", rows.get("one").stage());
        verify(runs, times(14)).renew(eq("one"), anyString(), any(), any());
        verify(runs).event(anyString(), eq("one"), eq("EXTRACT"), eq("QUEUED"), eq("long result saved"), eq(clock.instant()));
    }

    @Test void checkpointDeadlockRetriesTheSavedStageResultWithoutRunningTheModelHandlerAgain() {
        enqueue("one"); start(1, executor);
        var snapshot = new java.util.concurrent.atomic.AtomicReference<CommerceBatchExecution>();
        when(checkpointTransactions.getTransaction(any())).thenAnswer(call -> {
            snapshot.set(rows.get("one"));
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        });
        doAnswer(call -> { rows.put("one", snapshot.get()); return null; }).when(checkpointTransactions).rollback(any());
        when(runs.event(anyString(), eq("one"), eq("EXTRACT"), eq("QUEUED"), eq("saved"), any()))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("audit deadlock")).thenReturn(1);
        worker.poll(); executor.next();
        assertEquals("QUEUED", rows.get("one").status());
        assertEquals("VARIATE", rows.get("one").stage());
        verify(handler, times(1)).execute(any(), any());
        verify(runs, times(2)).finish(eq("one"), anyString(), eq("VARIATE"), eq("QUEUED"), eq("saved"), any());
        verify(checkpointTransactions).rollback(any()); verify(checkpointTransactions).commit(any());
        assertFalse(worker.hasInFlight("one"));
    }

    @Test void fullCapacityDoesNotClaimOrQueueAnotherBatch() {
        enqueue("one"); enqueue("two"); enqueue("three"); start(2, executor);
        worker.poll(); assertEquals(2, executor.tasks.size());
        clearInvocations(runs);
        worker.poll();
        verify(runs, never()).queued();
        verify(runs, never()).claim(anyString(), anyString(), any(), any());
        assertEquals(2, rows.values().stream().filter(r -> r.status().equals("RUNNING")).count());
    }

    @Test void anotherBatchRunsWhileFirstModelRequestIsStillBlocked() throws Exception {
        enqueue("one"); enqueue("two");
        var firstStarted = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var background = Executors.newFixedThreadPool(2);
        start(2, background);
        body = run -> {
            if (run.id().equals("one")) firstStarted.countDown(); else secondStarted.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("request was not released"); }
            catch (InterruptedException e) { throw new AssertionError("request was interrupted", e); }
            return new CommerceBatchStageHandler.Result(true, "saved");
        };
        try {
            assertTimeout(Duration.ofSeconds(2), worker::poll);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertEquals("RUNNING", rows.get("one").status());
            assertEquals("RUNNING", rows.get("two").status());
        } finally {
            release.countDown();
            worker.close();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void expiredLeaseCannotBeRevivedAndLateResultCannotFinishOrFreeCapacityEarly() {
        enqueue("one"); start(1, executor);
        body = run -> {
            clock.advance(Duration.ofMinutes(6));
            scheduledHeartbeat.run();
            scheduledHeartbeat.run();
            clearInvocations(runs);
            enqueue("two"); worker.poll();
            verify(runs, never()).claim(anyString(), anyString(), any(), any());
            verify(runs, never()).renew(anyString(), anyString(), any(), any());
            return new CommerceBatchStageHandler.Result(true, "late result archived");
        };
        worker.poll(); executor.next();
        assertEquals("UNKNOWN", rows.get("one").status());
        assertEquals("QUEUED", rows.get("two").status());
        verify(runs, never()).finish(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(runs, never()).event(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        worker.poll(); assertEquals("RUNNING", rows.get("two").status());
        verify(handler, times(1)).execute(any(), any());
    }

    @Test void changedOwnerStopsRenewalAndCannotCommitFormerOwnersResult() {
        enqueue("one"); start(1, executor);
        body = run -> {
            rows.put("one", copy(rows.get("one"), "EXTRACT", "RUNNING", "new-owner", clock.instant().plusSeconds(500)));
            clock.advance(Duration.ofSeconds(30));
            scheduledHeartbeat.run(); scheduledHeartbeat.run();
            return new CommerceBatchStageHandler.Result(true, "old request returned");
        };
        worker.poll(); executor.next();
        assertEquals("new-owner", rows.get("one").leaseToken());
        verify(runs, times(1)).renew(eq("one"), anyString(), any(), any());
        verify(runs, never()).finish(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test void expiredCheckpointIsFencedEvenBeforeHeartbeatNotices() {
        enqueue("one"); start(1, executor);
        body = run -> { clock.advance(Duration.ofMinutes(6)); return new CommerceBatchStageHandler.Result(true, "late"); };
        worker.poll(); executor.next(); worker.poll();
        assertEquals("UNKNOWN", rows.get("one").status());
        verify(runs, never()).event(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(handler, times(1)).execute(any(), any());
    }

    @Test void pausingBeforeBackgroundDispatchNeverStartsModelHandler() {
        enqueue("one"); start(1, executor); worker.poll();
        var row = rows.get("one"); rows.put("one", copy(row, row.stage(), "PAUSING", row.leaseToken(), row.leaseUntil()));
        clock.advance(Duration.ofSeconds(30)); scheduledHeartbeat.run(); executor.next();
        assertEquals("PAUSED", rows.get("one").status());
        verify(handler, never()).execute(any(), any());
    }

    @Test void shutdownKeepsPendingCallUninterruptedAndBlocksNextStage() {
        enqueue("one"); start(1, executor);
        body = run -> {
            worker.close();
            assertTrue(executor.shutdown);
            assertEquals("PAUSING", rows.get("one").status());
            verify(heartbeatExecutor, never()).shutdown();
            clock.advance(Duration.ofSeconds(30)); scheduledHeartbeat.run();
            enqueue("two"); clearInvocations(runs); worker.poll();
            verify(runs, never()).claim(anyString(), anyString(), any(), any());
            assertFalse(executor.interrupted);
            return new CommerceBatchStageHandler.Result(true, "current result saved");
        };
        worker.poll(); executor.next();
        assertEquals("PAUSED", rows.get("one").status());
        assertEquals("VARIATE", rows.get("one").stage());
        assertEquals("QUEUED", rows.get("two").status());
        verify(heartbeatExecutor).shutdown();
        assertEquals(0, executor.tasks.size());
    }

    @Test void rejectedBackgroundDispatchPausesClaimWithoutSubmittingOrRequeueing() {
        enqueue("one"); start(1, executor); executor.shutdown(); worker.poll();
        assertEquals("PAUSED", rows.get("one").status());
        worker.poll();
        verify(handler, never()).execute(any(), any());
        verify(runs, times(1)).claim(eq("one"), anyString(), any(), any());
    }

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-14T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    static final class ControlledExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown, interrupted;
        void next() { tasks.removeFirst().run(); }
        @Override public void execute(Runnable task) { if (shutdown) throw new RejectedExecutionException(); tasks.addLast(task); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { interrupted = true; shutdown = true; return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
