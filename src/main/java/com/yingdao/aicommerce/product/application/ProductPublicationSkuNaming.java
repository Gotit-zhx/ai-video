package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** An explicit upload style changes the outgoing SKU only, never an earlier listing. */
@Service
public class ProductPublicationSkuNaming {
    public static final String FIELD = "merchantStyleCode";
    private final ProductSkuNamingService naming;
    private final ObjectMapper json;

    public ProductPublicationSkuNaming(ProductSkuNamingService naming, ObjectMapper json) {
        this.naming = naming;
        this.json = json;
    }

    public static String style(Object value) {
        String code = Objects.toString(value, "").strip().toUpperCase(Locale.ROOT);
        if (!code.isEmpty() && !code.matches("[A-Z][0-9]{2}"))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "款式编码请输入一个英文字母和两位数字，例如 A01、B12");
        return code;
    }

    public static void normalize(Map<String,Object> request) {
        String code = style(request.get(FIELD));
        if (code.isEmpty()) request.remove(FIELD);
        else request.put(FIELD, code);
    }

    public List<ProductCaptureSku> apply(ProductCapture capture, List<ProductCaptureSku> skus,
                                          Map<String,Object> request) {
        String code = style(request.get(FIELD));
        if (code.isEmpty()) return skus;
        if (skus.isEmpty())
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先补全商品规格，再指定款式编码上传");
        var source = skus.stream().map(sku -> {
            var row = new LinkedHashMap<String,Object>(read(sku.getSourceJson()));
            row.put("externalSkuId", sku.getExternalSkuId());
            row.put("sellerSku", sku.getSellerSku());
            row.put("attributes", read(sku.getAttributesJson()));
            return (Map<String,Object>) row;
        }).toList();
        // The persistent pattern catalog supplies one suffix across colors/sizes,
        // shops and retries, including drafts captured under the earlier policy.
        var named = naming.assign(capture.getOrganizationId(), capture.getRequestFingerprint(), source, source);
        var result = new ArrayList<ProductCaptureSku>();
        var used = new HashSet<String>();
        for (int i = 0; i < skus.size(); i++) {
            @SuppressWarnings("unchecked")
            var meta = (Map<String,Object>) named.get(i).get(ProductSkuNaming.METADATA);
            String merchant = meta.get("color") + "-" + code + "-" + meta.get("size") + "-" + meta.get("patternSuffix");
            if (!used.add(merchant))
                throw new BusinessException(HttpStatus.BAD_REQUEST, "同一图案存在重复颜色和尺码，请核对规格后上传");
            result.add(skus.get(i).forPublication(merchant));
        }
        return result;
    }

    private Map<String,Object> read(String value) {
        if (value == null || value.isBlank()) return Map.of();
        return json.readValue(value, new TypeReference<Map<String,Object>>() {});
    }
}
