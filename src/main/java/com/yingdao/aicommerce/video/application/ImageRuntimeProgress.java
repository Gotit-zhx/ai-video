package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observes actual runtime HTTP attempts, including retries hidden inside one generation call. */
final class ImageRuntimeProgress {
    private final AiVideoTaskItemMapper mapper;
    private final RestClient client;
    private final Map<String, TrackedRequest> active = new ConcurrentHashMap<>();
    ImageRuntimeProgress(AiVideoTaskItemMapper mapper, RestClient client) {
        this.mapper = mapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        this.client = client.mutate().requestFactory(factory).build();
    }
    String begin(String itemId, String assetType) {
        String id = UUID.randomUUID().toString();
        active.put(id, new TrackedRequest(itemId, assetType));
        ImageExecutionProgress.record(mapper, itemId, assetType, "GENERATION_QUEUED");
        return id;
    }
    void poll() {
        active.forEach((id, request) -> {
            if (request.polling.compareAndSet(false, true)) {
                Thread.startVirtualThread(() -> {
                    try { refresh(id, request, false); }
                    finally { request.polling.set(false); }
                });
            }
        });
    }
    void finish(String id) {
        TrackedRequest request = active.get(id);
        if (request == null) return;
        refresh(id, request, true);
        active.remove(id, request);
    }
    private void refresh(String id, TrackedRequest request, boolean finish) {
        synchronized (request) {
            if (request.finished) return;
            try {
                Snapshot snapshot = client.get().uri("/v1/images/progress?requestId={id}", id)
                        .retrieve().body(Snapshot.class);
                if (snapshot != null) request.apply(mapper, snapshot);
            } catch (RuntimeException ignored) {
                // Telemetry is best-effort and cannot change the paid request's outcome.
            } finally {
                if (finish) request.finished = true;
            }
        }
    }
    record Snapshot(String stage, int attempts, long updatedAt) {}
    static final class TrackedRequest {
        final String itemId, assetType;
        final AtomicBoolean polling = new AtomicBoolean();
        int attempts;
        String stage = "";
        boolean finished;
        TrackedRequest(String itemId, String assetType) { this.itemId = itemId; this.assetType = assetType; }
        void apply(AiVideoTaskItemMapper mapper, Snapshot value) {
            // Absolute runtime counters make repeated polls idempotent. Record only observed POSTs.
            int observed = Math.max(0, Math.min(value.attempts(), 1000));
            while (attempts < observed) {
                mapper.recordImageStep(itemId, assetType, "GENERATING");
                attempts++;
                stage = "GENERATING";
            }
            if (!value.stage().equals(stage) && !"GENERATING".equals(value.stage())) {
                mapper.recordImageStep(itemId, assetType, value.stage());
                stage = value.stage();
            }
        }
    }
}
