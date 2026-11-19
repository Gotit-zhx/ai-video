package com.yingdao.aicommerce.video.application.provider;

import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface VideoGenerationProvider {
    String code();
    default boolean supportsReferenceVideo(RuntimeModelConnection connection) { return false; }
    default boolean requiresAuthenticatedDownload() { return false; }
    default String effectiveResolution(String requestedResolution) { return requestedResolution; }
    default Set<String> availableModels(RuntimeModelConnection connection) { return Set.of(connection.modelId()); }
    SubmitResult submit(RuntimeModelConnection connection, GenerationRequest request);
    QueryResult query(RuntimeModelConnection connection, String providerTaskId);

    record Reference(String url, String mediaType) {}
    record GenerationRequest(String prompt, List<Reference> references, int durationSeconds,
                             String aspectRatio, String resolution, String audioUrl, boolean useRequestedOptions) {
        public GenerationRequest(String prompt, List<Reference> references, int durationSeconds,
                                 String aspectRatio, String resolution, String audioUrl) {
            this(prompt, references, durationSeconds, aspectRatio, resolution, audioUrl, false);
        }
    }
    record SubmitResult(String providerTaskId, String status, String requestJson, String responseJson) {}
    record QueryResult(String status, String outputUrl, BigDecimal cost, Map<String, Object> usage,
                       String errorMessage, String requestJson, String responseJson) {}
}
