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
        var model = models.driverConnection(org).orElseThrow(() ->
                new BusinessException(HttpStatus.CONFLICT, "请先配置可用的视觉分析模型"));
        var detail = new LinkedHashMap<>(context);
        detail.put("analysisType", type);
        var input = images.stream().map(image -> Map.of("mimeType", image.mimeType(), "data", image.dataBase64())).toList();
        var body = Map.of("model", model.driverRequestModel(), "context", detail, "image", input.getFirst(),
                "additionalImages", input.subList(1, input.size()));
        var response = client.post().uri("/v1/product/analyze").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
        if (response == null || !(response.get("result") instanceof Map<?, ?> result))
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "主图分析未返回完整结果");
        return json.convertValue(result, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
}
