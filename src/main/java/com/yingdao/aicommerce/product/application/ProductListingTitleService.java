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
import java.util.List;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;

@Service
public class ProductListingTitleService {
    private final ModelConfigService models;
    private final RestClient pi;
    private final ObjectMapper json;
    private final ProductListingTitleImages images;
    public ProductListingTitleService(ModelConfigService models, ObjectMapper json, ProductListingTitleImages images,
            @Value("${app.pi-runtime-url}") String url, @Value("${app.pi-runtime-token}") String token) {
        this.models = models; this.json = json; this.images = images;
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(100));
        this.pi = RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token", token).requestFactory(factory).build();
    }
    public String generate(ProductCapture capture, List<ProductCaptureImage> capturedImages, String platform) {
        Map<String,Object> normalized;
        try { normalized = json.readValue(capture.getNormalizedData(), Map.class); }
        catch(Exception e) { normalized = Map.of(); }
        var facts = new LinkedHashMap<String,Object>(normalized);
        facts.put("categoryName", capture.getCategoryName());
        if (!facts.containsKey("listingTemplate") && !facts.containsKey("description")) facts.put("description", capture.getDescription());
        return request(capture.getOrganizationId(), facts, images.stored(capture,capturedImages), platform);
    }
    public String generate(String organizationId, Map<String,Object> collected, String platform) {
        return request(organizationId, collected, images.captured(collected.get("images")), platform);
    }
    private String request(String organizationId, Map<String,Object> collected, List<Map<String,Object>> photos, String platform) {
        var input = new LinkedHashMap<String, Object>();
        input.put("category", collected.get("categoryName"));
        input.put("platform", platform);
        input.put("brand", collected.get("brandName"));
        Object description = collected.containsKey("originalDescription") ? collected.get("originalDescription")
                : collected.containsKey("listingTemplate") ? "" : collected.get("description");
        input.put("description", description == null ? "" : String.valueOf(description).substring(0, Math.min(4000,String.valueOf(description).length())));
        Object attributes = collected.get("attributes");
        input.put("attributes", attributes == null ? Map.of() : attributes);
        if (collected.get("skus") instanceof List<?> skus) input.put("skuOptions", skus.stream()
                .filter(Map.class::isInstance).map(Map.class::cast)
                .map(sku -> sku.get("attributes") == null ? String.valueOf(sku.getOrDefault("name","")) : sku.get("attributes"))
                .distinct().limit(40).toList());
        try {
            var model = models.driverConnection(organizationId).orElseThrow(() ->
                    new BusinessException(HttpStatus.CONFLICT, "请先配置并启用驱动模型以生成商品标题"));
            PiResponse result = pi.post().uri("/v1/product-listing/title").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model.driverRequestModel(), "input", input, "images", photos)).retrieve().body(PiResponse.class);
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
