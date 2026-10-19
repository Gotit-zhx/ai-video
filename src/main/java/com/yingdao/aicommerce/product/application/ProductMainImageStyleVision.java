package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.*;

@Service
public class ProductMainImageStyleVision {
    private final ModelConfigService models;
    private final RestClient client;
    private final ObjectMapper json;

    public ProductMainImageStyleVision(ModelConfigService models, ObjectMapper json,
            @Value("${app.pi-runtime-url}") String url, @Value("${app.pi-runtime-token}") String token) {
        this.models = models;
        this.json = json;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(300000);
        client = RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token", token).requestFactory(factory).build();
    }

    public Map<String, Object> analyze(String org, String type, List<CommerceDesignImageGateway.Reference> images,
                                       Map<String, Object> context) {
        return analyze(org, type, images, context, progress -> {});
    }

    public Map<String, Object> analyze(String org, String type, List<CommerceDesignImageGateway.Reference> images,
                                       Map<String, Object> context, Consumer<Map<String, Object>> onProgress) {
        var model = models.driverConnection(org).orElseThrow(() ->
                new BusinessException(HttpStatus.CONFLICT, "请先配置可用的视觉分析模型"));
        var detail = new LinkedHashMap<>(context);
        detail.put("analysisType", type);
        var input = images.stream().map(image -> Map.of("mimeType", image.mimeType(), "data", image.dataBase64())).toList();
        var body = Map.of("model", model.driverRequestModel(), "context", detail, "image", input.getFirst(),
                "additionalImages", input.subList(1, input.size()));
        try {
            return client.post().uri("/v1/product/analyze").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.parseMediaType("application/x-ndjson")).body(body).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful())
                    throw new BusinessException(HttpStatus.BAD_GATEWAY, "Pi Agent 分析服务连接失败，请检查服务状态");
                try (var reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null;) {
                        if (line.isBlank()) continue;
                        var event = json.readValue(line, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        if ("progress".equals(event.get("type"))) {
                            var stage = String.valueOf(event.get("stage"));
                            if (Set.of("MODEL_WAITING", "MODEL_STREAMING", "VALIDATING").contains(stage)) {
                                onProgress.accept(Map.of("stage", stage,
                                        "outputCharacters", boundedNumber(event.get("outputCharacters"), 50000),
                                        "elapsedSeconds", boundedNumber(event.get("elapsedSeconds"), 600)));
                            }
                        } else if ("error".equals(event.get("type"))) {
                            throw new BusinessException(HttpStatus.BAD_GATEWAY, safeFailure(event));
                        } else if ("result".equals(event.get("type")) && event.get("result") instanceof Map<?, ?> result) {
                            return json.convertValue(result, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        }
                    }
                }
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "Pi Agent 分析连接中断，未收到完整结果，请重试");
            });
        } catch (org.springframework.web.client.ResourceAccessException failure) {
            throw new BusinessException(HttpStatus.GATEWAY_TIMEOUT, "Pi Agent 分析服务连接超时或中断，请重试");
        }
    }

    private static int boundedNumber(Object value, int maximum) {
        return value instanceof Number number ? Math.max(0, Math.min(maximum, number.intValue())) : 0;
    }

    private static String safeFailure(Map<String, Object> event) {
        return switch (String.valueOf(event.get("failureCode"))) {
            case "POSTER_ANALYSIS_TIMEOUT" -> "Pi Agent 分析等待超时，未自动重复调用，可重试此图";
            case "POSTER_MODEL_RATE_LIMITED" -> "视觉模型请求受限，请稍后重试";
            case "POSTER_MODEL_AUTH" -> "视觉模型认证失败，请检查模型配置";
            case "POSTER_OUTPUT_INCOMPLETE", "POSTER_OUTPUT_EMPTY" -> "Pi Agent 未返回完整分析结果，请重试";
            case "PRODUCT_MAIN_IMAGE_STYLE_INVALID" -> "Pi Agent 未能完整识别产品主体或海报版式，请检查参考图后重试";
            default -> "Pi Agent 视觉模型连接失败或响应中断，请检查模型配置后重试";
        };
    }
}
