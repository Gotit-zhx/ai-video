package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.mapper.CommerceBatchExecutionMapper;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CommerceBatchExecutionWorker {
    private static final Logger log = LoggerFactory.getLogger(CommerceBatchExecutionWorker.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private final CommerceBatchExecutionMapper runs;
    private final PlatformUserMapper users;
    private final Map<CommerceBatchExecution.Stage, CommerceBatchStageHandler> handlers =
            new EnumMap<>(CommerceBatchExecution.Stage.class);
    private final CommerceBatchExecutionCheckpoint checkpoint;
    private final Clock clock;
    private final int concurrency;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();
    private boolean accepting = true;
    private boolean heartbeatStarted;

    @Autowired
    public CommerceBatchExecutionWorker(CommerceBatchExecutionMapper runs, PlatformUserMapper users,
            List<CommerceBatchStageHandler> handlers, CommerceBatchExecutionCheckpoint checkpoint,
            @Value("${app.batch-execution.concurrency:2}") int concurrency) {
        this(runs, users, handlers, checkpoint, Clock.systemUTC(), checkedConcurrency(concurrency),
                Executors.newFixedThreadPool(checkedConcurrency(concurrency), threads("commerce-batch-")),
                Executors.newSingleThreadScheduledExecutor(threads("commerce-batch-heartbeat-")));
    }

    CommerceBatchExecutionWorker(CommerceBatchExecutionMapper runs, PlatformUserMapper users,
            List<CommerceBatchStageHandler> handlers, CommerceBatchExecutionCheckpoint checkpoint,
            Clock clock, int concurrency, ExecutorService executor, ScheduledExecutorService heartbeatExecutor) {
        this.runs = runs;
        this.users = users;
        this.checkpoint = checkpoint;
        this.clock = clock;
        this.concurrency = checkedConcurrency(concurrency);
        this.executor = executor;
        this.heartbeatExecutor = heartbeatExecutor;
        for (var handler : handlers) {
            if (this.handlers.put(handler.stage(), handler) != null) throw new IllegalArgumentException("重复阶段处理器");
        }
    }

    private static int checkedConcurrency(int value) {
        if (value < 1 || value > 8) throw new IllegalArgumentException("批次并发数必须为 1 到 8");
        return value;
    }

    private static ThreadFactory threads(String prefix) {
        var sequence = new AtomicInteger();
        return task -> {
            var thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PostConstruct
    public synchronized void startHeartbeat() {
        if (!accepting || heartbeatStarted) return;
        heartbeatStarted = true;
        // Separate from Spring's scheduler and model I/O: neither can delay lease renewal.
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_INTERVAL.toMillis(),
                HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Scheduled(initialDelayString = "${app.batch-execution.initial-delay:5000}",
            fixedDelayString = "${app.batch-execution.poll-delay:2000}")
    public synchronized void poll() {
        if (!accepting) return;
        runs.expire(clock.instant());
        if (inFlight.size() >= concurrency) return;
        for (String id : runs.queued()) {
            if (inFlight.size() >= concurrency) break;
            // Retain the slot until the actual request returns, even if its lease was lost.
            if (inFlight.containsKey(id)) continue;
            var flight = new InFlight(id, UUID.randomUUID().toString());
            var now = clock.instant();
            if (runs.claim(id, flight.token, now, now.plus(LEASE_DURATION)) != 1) continue;
            inFlight.put(id, flight);
            try {
                executor.execute(() -> {
                    try {
                        execute(flight);
                    } catch (Exception failure) {
                        // A lost database connection must not cause a second external submission.
                        log.warn("Batch execution {} could not save its checkpoint; lease recovery will reconcile it", id, failure);
                    } finally {
                        release(flight);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                try {
                    var run = runs.byId(id);
                    if (run != null) checkpoint.finish(id, flight.token, run.stage(), run.stage(), "PAUSED",
                            "后台执行器未接收任务，已暂停；可手动继续", clock.instant());
                } finally {
                    release(flight);
                }
            }
        }
    }

    void heartbeat() {
        for (var flight : inFlight.values()) {
            if (flight.leaseLost) continue;
            try {
                var now = clock.instant();
                if (runs.renew(flight.id, flight.token, now, now.plus(LEASE_DURATION)) != 1) {
                    flight.leaseLost = true;
                    log.warn("Batch execution {} lost its lease; any in-flight result may be saved but will not be resubmitted", flight.id);
                }
            } catch (Exception failure) {
                // A transient heartbeat failure may retry only while the database lease is still live.
                log.warn("Could not renew batch execution {} lease", flight.id, failure);
            }
        }
    }

    private void execute(InFlight flight) {
        var run = runs.byId(flight.id);
        if (flight.leaseLost || run == null || !flight.token.equals(run.leaseToken())
                || run.leaseUntil() == null || !run.leaseUntil().isAfter(clock.instant())
                || !("RUNNING".equals(run.status()) || "PAUSING".equals(run.status()))) return;
        String stage = run.stage(), status, message;
        try {
            var user = users.selectById(run.createdBy());
            var permissions = user == null ? List.<String>of() : users.selectPermissionCodes(user.getId());
            if ("PAUSING".equals(run.status())) {
                status = "PAUSED";
                message = "已暂停后续调用";
            } else if (user == null || !run.organizationId().equals(user.getOrganizationId())
                    || !"ACTIVE".equals(user.getStatus()) || permissions == null || !permissions.contains("product:publish")) {
                status = "WAITING_INPUT";
                message = "启动者权限或账号状态已变化";
            } else {
                var handler = handlers.get(CommerceBatchExecution.Stage.valueOf(stage));
                if (handler == null) {
                    status = "WAITING_CAPABILITY";
                    message = "当前阶段执行能力尚未接入：" + stage;
                } else {
                    var principal = new PlatformPrincipal(user.getId(), user.getOrganizationId(), "batch",
                            user.getUsername(), user.getDisplayName(), "", true, user.getRegistrationSource(), List.of(), permissions);
                    var result = handler.execute(run, principal);
                    message = result.message();
                    if (result.complete()) {
                        var next = stage.equals("REVIEW") && runs.creationOnly(run.id()) ? null
                                : CommerceBatchExecution.Stage.valueOf(stage).next();
                        status = next == null ? "COMPLETED" : "QUEUED";
                        if (next != null) stage = next.name();
                    } else status = result.pendingStatus();
                }
            }
        } catch (Exception failure) {
            status = "UNKNOWN";
            message = "阶段执行异常，结果需核查；不会自动重复提交";
        }
        if (message == null) message = "阶段结果无说明";
        if (message.length() > 1000) message = message.substring(0, 1000);
        if (!flight.leaseLost) checkpoint.finish(flight.id, flight.token, run.stage(), stage, status, message, clock.instant());
    }

    private synchronized void release(InFlight flight) {
        inFlight.remove(flight.id, flight);
        if (!accepting && inFlight.isEmpty()) heartbeatExecutor.shutdown();
    }

    boolean hasInFlight(String id) { return inFlight.containsKey(id); }

    @PreDestroy
    public synchronized void close() {
        if (!accepting) return;
        accepting = false;
        for (var flight : inFlight.values()) {
            if (flight.leaseLost) continue;
            try {
                // The existing submission ledger sees PAUSING before any next model call.
                runs.drain(flight.id, flight.token, clock.instant());
            } catch (Exception failure) {
                log.warn("Could not pause batch execution {} during shutdown", flight.id, failure);
            }
        }
        // Do not interrupt paid calls, revive lost leases, or enqueue unresolved requests.
        executor.shutdown();
        if (inFlight.isEmpty()) heartbeatExecutor.shutdown();
    }

    private static final class InFlight {
        private final String id;
        private final String token;
        private volatile boolean leaseLost;
        private InFlight(String id, String token) { this.id = id; this.token = token; }
    }
}
