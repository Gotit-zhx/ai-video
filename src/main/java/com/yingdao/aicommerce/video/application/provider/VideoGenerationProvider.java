package com.yingdao.aicommerce.video.application.provider;

import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface VideoGenerationProvider {
    String code();
    SubmitResult submit(RuntimeModelConnection connection, GenerationRequest request);
    QueryResult query(RuntimeModelConnection connection, String providerTaskId);

    record Reference(String url, String mediaType) {}
    record GenerationRequest(String prompt, List<Reference> references, int durationSeconds,
                             String aspectRatio, String resolution, String audioUrl) {}
    record SubmitResult(String providerTaskId, String status) {}
    record QueryResult(String status, String outputUrl, BigDecimal cost, Map<String, Object> usage,
                       String errorMessage) {}
}
