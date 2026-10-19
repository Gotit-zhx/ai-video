package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductMainImageStylePolicyTest {
    static Map<String, Object> analysis() {
        var value = new LinkedHashMap<String, Object>();
        value.put("complete", true); value.put("productDescription", "The shirt, not the model");
        value.put("productRegions", List.of(Map.of("x", .1, "y", .2, "width", .5, "height", .6)));
        for (String key : List.of("layout","palette","typography","background","lighting","decorations")) value.put(key, key + " details");
        value.put("prompt", "Use {{product}} with {{title}}");
        return value;
    }
    static Map<String, Object> originalAnalysis() {
        var value = analysis();
        value.put("mode", ProductMainImageStylePolicy.PRESERVE_ORIGINAL);
        value.put("productDescription", "After-sales information with no product");
        value.put("productRegions", List.of());
        value.put("prompt", "");
        return value;
    }
    @Test void originalInformationPagesAreValidButCannotEnterGeneration() {
        var value = originalAnalysis();
        assertDoesNotThrow(() -> ProductMainImageStylePolicy.validateAnalysis(value));
        assertTrue(ProductMainImageStylePolicy.preservesOriginal(value));
        assertThrows(BusinessException.class, () -> ProductMainImageStylePolicy.prompt("Target", value));
        for (var change : List.of(Map.of("complete", false), Map.of("mode", "UNKNOWN"),
                Map.of("productRegions", analysis().get("productRegions")), Map.of("prompt", "{{product}}"))) {
            var invalid = originalAnalysis(); invalid.putAll(change);
            assertThrows(BusinessException.class, () -> ProductMainImageStylePolicy.validateAnalysis(invalid));
        }
        var missingMode = originalAnalysis(); missingMode.remove("mode");
        assertThrows(BusinessException.class, () -> ProductMainImageStylePolicy.validateAnalysis(missingMode));
        var explicitGeneration = analysis(); explicitGeneration.put("mode", "REPLACE_PRODUCT");
        assertDoesNotThrow(() -> ProductMainImageStylePolicy.validateAnalysis(explicitGeneration));
    }
    @Test void omittedOrBlankTemplateIsOptOut() {
        assertNull(ProductMainImageStylePolicy.templateId(Map.of()));
        assertNull(ProductMainImageStylePolicy.templateId(Map.of(ProductMainImageStylePolicy.TEMPLATE, "")));
        assertThrows(BusinessException.class, () -> ProductMainImageStylePolicy.templateId(Map.of(ProductMainImageStylePolicy.TEMPLATE, true)));
    }
    @Test void promptSeparatesProductFromPosterAndKeepsLayout() {
        String prompt = ProductMainImageStylePolicy.prompt("Target shirt", analysis());
        assertTrue(prompt.contains("图片1是唯一的目标商品身份依据"));
        assertTrue(prompt.contains("图片2仅为海报风格与布局参考"));
        assertTrue(prompt.contains("角落小图"));
        assertFalse(prompt.contains("{{product}}"));
        assertTrue(prompt.contains("Target shirt"));
    }
    @Test void incompleteAndOutOfRangeAnalysisIsRejected() {
        var value = analysis();
        value.put("productRegions", List.of(Map.of("x", .8, "y", 0, "width", .5, "height", 1)));
        assertThrows(BusinessException.class, () -> ProductMainImageStylePolicy.validateAnalysis(value));
        value.put("productRegions", List.of(Map.of("x", Double.NaN, "y", 0, "width", .5, "height", 1)));
        assertThrows(BusinessException.class, () -> ProductMainImageStylePolicy.validateAnalysis(value));
    }
    @Test void reviewRequiresAllChecksAndNeverTrustsStringTrue() {
        var review = new LinkedHashMap<String,Object>();
        ProductMainImageStylePolicy.CHECKS.forEach(key -> review.put(key, true));
        review.put("reason", "Match");
        assertTrue(ProductMainImageStylePolicy.passed(review));
        review.put("productPreserved", "true");
        assertFalse(ProductMainImageStylePolicy.passed(review));
        assertThrows(BusinessException.class, () -> ProductMainImageStylePolicy.validateReview(review));
    }
    @Test void currentTemplateEditsInvalidateOnlyChangedSlotsRegardlessOfMapOrder() {
        var json = new ObjectMapper();
        var slot = new LinkedHashMap<String,Object>(Map.of("storageKey","key","analysis",analysis(),"width",1024,"height",1024));
        String original = ProductMainImageStylePolicy.fingerprint(slot,json);
        assertEquals(original, ProductMainImageStylePolicy.fingerprint(new TreeMap<>(slot),json));
        var edited = analysis(); edited.put("prompt","A new layout for {{product}}");
        slot.put("analysis",edited);
        assertNotEquals(original,ProductMainImageStylePolicy.fingerprint(slot,json));
    }
    @Test void posterAspectRatioSelectsMatchingCanvas() {
        assertEquals("1024x1024",ProductMainImageStylePolicy.size(1000,1000));
        assertEquals("1024x1536",ProductMainImageStylePolicy.size(800,1200));
        assertEquals("1536x1024",ProductMainImageStylePolicy.size(1600,900));
    }
}
