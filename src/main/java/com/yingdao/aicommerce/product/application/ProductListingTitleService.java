package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProductListingTitleService {
    private final ModelConfigService models;
    private final RestClient pi;
    private final ObjectMapper json;
    public ProductListingTitleService(ModelConfigService models, ObjectMapper json,
            @Value("${app.pi-runtime-url}") String url, @Value("${app.pi-runtime-token}") String token) {
        this.models = models; this.json = json;
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(100));
        this.pi = RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token", token).requestFactory(factory).build();
    }
    public String generate(ProductCapture capture, String platform) {
        Map<?,?> normalized;
        try { normalized = json.readValue(capture.getNormalizedData(), Map.class); }
        catch(Exception e) { normalized = Map.of(); }
        Object originalTitle=normalized.get("originalTitle");
        return generate(capture.getOrganizationId(), originalTitle == null ? capture.getTitle() : String.valueOf(originalTitle),
                capture.getCategoryName(), normalized.get("attributes"), platform);
    }
    public String generate(String organizationId, String titleSource, String category, Object attributes, String platform) {
        var input = new LinkedHashMap<String, Object>();
        input.put("originalTitle", titleSource); input.put("category", category);
        input.put("platform", platform);
        input.put("attributes", attributes == null ? Map.of() : attributes);
        try {
            var model = models.driverConnection(organizationId).orElseThrow(() ->
                    new BusinessException(HttpStatus.CONFLICT, "请先配置并启用驱动模型以生成商品标题"));
            PiResponse result = pi.post().uri("/v1/product-listing/title").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model.driverRequestModel(), "input", input)).retrieve().body(PiResponse.class);
            String title = result == null || result.result() == null ? "" : String.valueOf(result.result().getOrDefault("title", "")).trim();
            if (title.codePointCount(0, title.length()) < 10 || title.codePointCount(0, title.length()) > 120 || title.matches("(?s).*[<>\\r\\n].*"))
                throw new IllegalStateException();
            return title;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "AI 标题生成失败，请检查驱动模型配置后重试");
        }
    }
    public record PiResponse(Map<String, Object> result) {}
}
