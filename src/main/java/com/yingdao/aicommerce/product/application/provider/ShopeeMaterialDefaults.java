package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Resolve merchant-requested cotton default against the destination category. */
final class ShopeeMaterialDefaults {
    static List<?> prepare(ShopeeOfficialClient client, MarketplaceAccess.Connection connection,
                           long categoryId, Map<String,Object> request) {
        Object supplied = request.get("productAttributes");
        if (!(supplied instanceof List<?> list) || list.isEmpty()) {
            Object payload = request.get("shopeePayload");
            if (payload instanceof Map<?,?> map) supplied = map.get("attribute_list");
        }
        Map<String,Object> response = client.shopGet("/api/v2/product/get_attribute_tree", connection,
                Map.of("category_id_list", categoryId, "language", "en"));
        Object body = response.get("response");
        if (!(body instanceof Map<?,?> map) || !(map.get("list") instanceof List<?> categories))
            throw failure("Shopee 类目属性响应不完整，无法确认默认材质");
        for (Object entry : categories) {
            if (entry instanceof Map<?,?> category && String.valueOf(categoryId).equals(String.valueOf(category.get("category_id")))
                    && category.get("attribute_tree") instanceof List<?> definitions)
                return resolve(supplied instanceof List<?> values ? values : List.of(), definitions);
        }
        throw failure("Shopee 未返回目标类目属性，无法确认默认材质");
    }

    static List<?> resolve(List<?> supplied, List<?> definitions) {
        List<Object> result = new ArrayList<>(supplied);
        for (Object entry : definitions) {
            if (!(entry instanceof Map<?,?> attribute) || !"Material".equalsIgnoreCase(String.valueOf(attribute.get("name"))))
                continue;
            Object id = attribute.get("attribute_id");
            if (!(id instanceof Number)) throw failure("Shopee 材质属性编号无效");
            for (Object existing : supplied) {
                if (existing instanceof Map<?,?> value && String.valueOf(id).equals(String.valueOf(value.get("attribute_id")))
                        && value.get("attribute_value_list") instanceof List<?> values && !values.isEmpty())
                    return result;
            }
            Object cotton = null;
            if (attribute.get("attribute_value_list") instanceof List<?> values) {
                for (Object value : values) if (value instanceof Map<?,?> option &&
                        "Cotton".equalsIgnoreCase(String.valueOf(option.get("name"))))
                    cotton = option.get("value_id");
            }
            if (!(cotton instanceof Number)) throw failure("Shopee 当前类目不支持默认棉材质，请选择可用材质");
            result.removeIf(value -> value instanceof Map<?,?> map &&
                    String.valueOf(id).equals(String.valueOf(map.get("attribute_id"))));
            result.add(Map.of("attribute_id", id, "attribute_value_list",
                    List.of(Map.of("value_id", cotton, "original_value_name", "Cotton"))));
            return result;
        }
        return result; // Categories without Material do not receive an inapplicable attribute.
    }
    private static BusinessException failure(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }
}
