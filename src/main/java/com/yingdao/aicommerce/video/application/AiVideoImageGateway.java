package com.yingdao.aicommerce.video.application;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/** Submit once; wait using read-only operation lookups, including after a lost HTTP connection. */
final class AiVideoImageGateway {
    private final RestClient client;
    private final AiVideoImageAttempts attempts;
    private final long pollMillis;

    AiVideoImageGateway(RestClient base, ObjectMapper mapper) {
        this(client(base), new AiVideoImageAttempts(mapper), 3000);
    }
    AiVideoImageGateway(RestClient client, AiVideoImageAttempts attempts, long pollMillis) {
        this.client = client; this.attempts = attempts; this.pollMillis = pollMillis;
    }
    private static RestClient client(RestClient base) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return base.mutate().requestFactory(factory).build();
    }
    record Operation(String status, boolean active, long updatedAt,
                     Map<String, Object> response, Map<String, Object> error) {}

    Map<String, Object> generate(Path ledger, String hash, int historicalAttempts,
                                 Map<String, Object> input, ImageRuntimeProgress progress,
                                 String itemId, String assetType) {
        var reservation = attempts.reserve(ledger, hash, historicalAttempts);
        String id = reservation.attempt().id();
        Map<String, Object> body = new LinkedHashMap<>(input);
        body.put("progressRequestId", id);
        body.put("asyncResult", true);
        body.put("singleProviderAttempt", true);
        // Retain the original wire fingerprint when recovering a pre-stream request.
        if (Boolean.TRUE.equals(reservation.attempt().stream())) body.put("streamImages", true);
        progress.begin(itemId, assetType, id, reservation.fresh() ? 0 : 1);
        try {
            if (reservation.fresh()) submit(body);
            int recoveries = 0;
            while (true) {
                Operation operation = lookup(id);
                if (operation == null) {
                    // Reposting after missing durable data could create another paid image.
                    throw new IllegalStateException("IMAGE_RESULT_UNKNOWN: 原生图请求记录暂不可核对，已停止新增生图，请检查图片服务");
                }
                if ("COMPLETED".equals(operation.status()) && operation.response() != null)
                    return operation.response();
                if ("FAILED".equals(operation.status())) {
                    var error = operation.error() == null ? Map.<String,Object>of() : operation.error();
                    String state = String.valueOf(error.get("requestState"));
                    String kind = String.valueOf(error.get("failureKind"));
                    if (!operation.active() && Boolean.TRUE.equals(error.get("retryAuthorized"))
                            && ("NETWORK_TIMEOUT".equals(kind) || "PROVIDER_TIMEOUT".equals(kind))
                            && ("RESPONSE_TIMEOUT".equals(state) || "REJECTED".equals(state))) {
                        // Retry only after the runtime's request has fully ended. This consumes
                        // another durable slot, including when the first call may have been billed.
                        attempts.rejected(ledger, id);
                        throw new IllegalStateException("图片通道响应超时，原请求已结束；将在剩余生图次数内恢复，已消耗次数不清零");
                    }
                    if ("REJECTED".equals(state) || "NOT_SENT".equals(state)) {
                        attempts.rejected(ledger, id);
                        if ("CONCURRENCY".equals(kind) || "NOT_SENT".equals(state))
                            throw new IllegalStateException("图片服务暂不可用，网络超时或并发限制；将在剩余次数内恢复");
                    }
                    throw new IllegalStateException("IMAGE_RESULT_UNKNOWN: "
                            + ("RESPONSE_TIMEOUT".equals(state) ? "图片通道等待响应超时，未收到最终图片；" : "")
                            + "生图服务未返回可用图片（"
                            + kind + " / " + String.valueOf(error.get("failureCode"))
                            + "）；已保留原请求，不会自动重复付费生成");
                }
                if ("RECOVERABLE".equals(operation.status())
                        || "RUNNING".equals(operation.status()) && !operation.active()) {
                    // Runtime can decode its saved response. singleProviderAttempt prevents
                    // replay of a provider POST interrupted by a runtime restart.
                    if (++recoveries > 2) throw new IllegalStateException(
                            "IMAGE_RESULT_UNKNOWN: 原图片响应暂时无法恢复，已保留结果记录；未新增生图");
                    submit(body);
                } else if (!"RUNNING".equals(operation.status())) {
                    throw new IllegalStateException("IMAGE_RESULT_UNKNOWN: 图片服务返回未知任务状态，已停止新增生图");
                }
                try { Thread.sleep(pollMillis); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("图片任务已停止，原请求记录已保留", stopped);
                }
            }
        } finally { progress.finish(id); }
    }

    private void submit(Map<String, Object> body) {
        // A lost acknowledgement does not mean generation failed. The caller's next retry
        // reuses the persisted ID and only looks up the same operation.
        client.post().uri("/v1/images/generate").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toBodilessEntity();
    }
    private Operation lookup(String id) {
        try { return client.get().uri("/v1/images/operations?requestId={id}", id)
                .retrieve().body(Operation.class); }
        catch (ResourceAccessException error) {
            throw new IllegalStateException("图片结果查询网络超时，保留原请求等待恢复；不会重新生图", error);
        }
        catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return null;
            throw error;
        }
    }
}
