package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TikTokProductAttributeResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
    private final TikTokProductAttributeResolver resolver = new TikTokProductAttributeResolver(client, mapper);

    @Test
    void readsTheTargetShopLeafDefinitionsWithTheExactCategoryVersion() {
        Shop shop = mock(Shop.class);
        when(shop.getShopCipher()).thenReturn("target-shop-cipher");
        MarketplaceAccess.Connection connection = new MarketplaceAccess.Connection(shop,
                new MarketplaceAccess.TokenSet("target-access-token", "refresh", Instant.now(), Instant.now(), List.of()));
        when(client.apiVersion()).thenReturn("202309");
        when(client.apiGet(anyString(), anyString(), anyString(), anyMap())).thenReturn(Map.of("data", Map.of("attributes", List.of(
                property("material-target", "Material", true, "polyester-target", "Polyester")))));
        List<Map<String, Object>> result = resolver.resolve(connection, capture(Map.of("attributes", Map.of("Bahan", "Poliester"))),
                "601302", "v2", List.of());
        assertThat(result).containsExactly(attribute("material-target", "polyester-target"));
        verify(client).apiGet("/product/202309/categories/601302/attributes", "target-access-token", "target-shop-cipher",
                Map.of("locale", "en-US", "category_version", "v2"));
        verify(client).apiVersion();
        verifyNoMoreInteractions(client);
    }

    @Test
    void mapsTheCollectedMalayShirtFactsToTargetIdsWithoutInferringCottonFromItsTitle() {
        List<Map<String, Object>> definitions = List.of(
                property("material", "Material", true, "poly", "Polyester", "cotton", "Cotton"),
                property("neck", "Neckline", false, "crew", "Crew Neck"),
                property("sleeve", "Sleeve Length", false, "short", "Short Sleeve"),
                property("pattern", "Pattern", false, "plain", "Plain"),
                property("season", "Season", false, "summer", "Summer"),
                property("fit", "Fit", false, "slim", "Slim Fit"),
                property("occasion", "Occasion", false, "casual", "Casual"),
                property("size-type", "Size Type", false, "regular", "Regular"));
        ProductCapture capture = capture(Map.of("attributes", Map.of(
                "Bahan", "Poliester", "Garis Leher", "Leher Krew", "Panjang Lengan Baju", "Lengan pendek",
                "Corak", "Polos", "Musim", "Musim Panas", "Potongan", "Muat ramping", "Peristiwa", "Santai", "Size type", "Biasa")));
        assertThat(resolver.resolveFromDefinitions(capture, definitions, List.of())).containsExactly(
                attribute("material", "poly"), attribute("neck", "crew"), attribute("sleeve", "short"),
                attribute("pattern", "plain"), attribute("season", "summer"), attribute("fit", "slim"),
                attribute("occasion", "casual"), attribute("size-type", "regular"));
    }

    @Test
    void explicitConditionFactsMapToCurrentTargetValuesWithoutReusingSourceIds() {
        Map<String, Object> condition = property("100115", "Product condition", true,
                "target-new", "New", "target-used", "Used", "target-refurbished", "Refurbished");
        for (Map<String, Object> source : List.of(
                Map.<String, Object>of("attributes", Map.of("Condition", "New")),
                Map.<String, Object>of("attributes", Map.of("Keadaan Barang", "Baru")),
                Map.<String, Object>of("product_condition", "Brand New"),
                Map.<String, Object>of("itemCondition", "https://schema.org/NewCondition"),
                Map.<String, Object>of("condition", Map.of("id", "source-new", "name", "全新")))) {
            assertThat(resolver.resolveFromDefinitions(capture(source), List.of(condition), null))
                    .containsExactly(attribute("100115", "target-new"));
        }
        assertThat(resolver.resolveFromDefinitions(capture(Map.of("item_condition", "Terpakai")), List.of(condition), null))
                .containsExactly(attribute("100115", "target-used"));
        assertThat(resolver.resolveFromDefinitions(capture(Map.of("condition", "http://schema.org/RefurbishedCondition")), List.of(condition), null))
                .containsExactly(attribute("100115", "target-refurbished"));
    }

    @Test
    void collected96SkuShirtFactsDoNotInventTheMissingCondition() {
        Map<String, Object> condition = property("100115", "Product condition", true, "target-new", "New");
        ProductCapture actualShape = capture(Map.of("attributes", Map.of("Petite", "No", "Pattern", "Print",
                "Material", "Others", "Occasion", "Casual", "Plus Size", "Yes", "Cropped Top", "No", "Custom Product", "No")));
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(actualShape, List.of(condition), null))
                .hasMessageContaining("Product condition（100115）").hasMessageContaining("未创建商品");
    }

    @Test
    void sourceStatusAndNewInMarketingTextOrUnknownEnumsDoNotSupplyProductCondition() {
        Map<String, Object> condition = property("100115", "Product condition", true,
                "target-new", "New", "target-used", "Used");
        for (Map<String, Object> source : List.of(
                Map.<String, Object>of("title", "Brand New Shirt", "description", "New collection", "status", "NEW", "is_new", true),
                Map.<String, Object>of("condition", 1), Map.<String, Object>of("condition", true),
                Map.<String, Object>of("condition", Map.of("id", "target-new")),
                Map.<String, Object>of("condition", "Used - like new"))) {
            assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(source), List.of(condition), null))
                    .hasMessageContaining("Product condition（100115）");
        }
    }

    @Test
    void conflictingExplicitConditionsDoNotSelectTheMostConvenientOne() {
        Map<String, Object> condition = property("100115", "Product condition", true,
                "target-new", "New", "target-used", "Used");
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of("condition", "NEW",
                "attributes", Map.of("Condition", "Used"))), List.of(condition), null))
                .hasMessageContaining("Product condition（100115）");
        assertThat(resolver.resolveFromDefinitions(capture(Map.of("condition", "New", "attributes", Map.of("Condition", "Baru"))),
                List.of(condition), null)).containsExactly(attribute("100115", "target-new"));
    }

    @Test
    void reportsAllUnmatchedRequiredFactsAndRespectsTheOfficialMisspelledFlag() {
        Map<String, Object> material = property("material", "Material", true, "cotton", "Cotton");
        Map<String, Object> certification = property("cert", "Safety Certification", false, "ce", "CE");
        certification.put("is_required", true);
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of()), List.of(material, certification), List.of()))
                .hasMessageContaining("Material（material）").hasMessageContaining("Safety Certification（cert）").hasMessageContaining("未创建商品");
    }

    @Test
    void sourceIdsAreNeverReusedWhileNamedStructuredSourceValuesCanBeMapped() {
        Map<String, Object> target = property("target-property", "Material", true, "target-value", "Polyester");
        ProductCapture named = capture(Map.of("product_properties", List.of(Map.of(
                "attribute_id", "source-property", "attribute_name", "Bahan",
                "values", List.of(Map.of("id", "source-value", "name", "Poliester"))))));
        assertThat(resolver.resolveFromDefinitions(named, List.of(target), null))
                .containsExactly(attribute("target-property", "target-value"));
        ProductCapture idOnly = capture(Map.of("attributes", Map.of("Material", Map.of("id", "target-value"))));
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(idOnly, List.of(target), null)).hasMessageContaining("Material");
    }

    @Test
    void suppliedTargetValuesAreVerifiedAgainstTheCurrentCategoryAndTakePrecedenceOverSourceFacts() {
        Map<String, Object> target = property("material", "Material", true, "poly", "Polyester", "cotton", "Cotton");
        ProductCapture capture = capture(Map.of("attributes", Map.of("Bahan", "Poliester")));
        assertThat(resolver.resolveFromDefinitions(capture, List.of(target), List.of(attribute("material", "cotton"))))
                .containsExactly(attribute("material", "cotton"));
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture, List.of(target), List.of(attribute("source-property", "poly"))))
                .hasMessageContaining("不属于目标类目");
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture, List.of(target), List.of(attribute("material", "source-value"))))
                .hasMessageContaining("不属于目标类目").hasMessageContaining("Material");
    }

    @Test
    void customValuesRequirePermissionAndRespectThePositiveNumberFormat() {
        Map<String, Object> quantity = property("quantity", "Quantity Per Pack", true);
        quantity.put("is_customizable", true);quantity.put("value_data_format", "POSITIVE_INT_OR_DECIMAL");
        assertThat(resolver.resolveFromDefinitions(capture(Map.of("attributes", Map.of("Kuantiti setiap Pek", 1))), List.of(quantity), null))
                .containsExactly(Map.of("id", "quantity", "values", List.of(Map.of("name", "1"))));
        for (Object invalid : List.of(0, -1, "one", "NaN")) {
            assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of("attributes", Map.of("Kuantiti setiap Pek", invalid))),
                    List.of(quantity), null)).hasMessageContaining("Quantity Per Pack");
        }
        quantity.put("is_customizable", false);
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of("attributes", Map.of("Quantity Per Pack", 1))), List.of(quantity), null))
                .hasMessageContaining("Quantity Per Pack");
    }

    @Test
    void multiSelectMapsAllSourceValuesWithoutSilentlyDroppingUnmatchedValues() {
        Map<String, Object> style = property("style", "Style", true, "basic", "Basic", "cute", "Cute", "korean", "Korean");
        style.put("is_multiple_selection", true);
        ProductCapture capture = capture(Map.of("attributes", Map.of("Gaya", "Asas, Comel, Korea")));
        assertThat(resolver.resolveFromDefinitions(capture, List.of(style), null))
                .containsExactly(Map.of("id", "style", "values", List.of(Map.of("id", "basic"), Map.of("id", "cute"), Map.of("id", "korean"))));
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of("attributes", Map.of("Gaya", "Asas, Unknown Style"))), List.of(style), null))
                .hasMessageContaining("Style");
        style.put("is_multiple_selection", false);
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture, List.of(style), null)).hasMessageContaining("Style");
    }

    @Test
    void duplicateOrConflictingSourceNamesDoNotArbitrarilyChooseOneMaterial() {
        Map<String, Object> material = property("material", "Material", true, "poly", "Polyester", "cotton", "Cotton");
        ProductCapture conflict = capture(Map.of("attributes", Map.of("Bahan", "Poliester"),
                "productAttributes", List.of(Map.of("name", "Material", "value", "Cotton"))));
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(conflict, List.of(material), null)).hasMessageContaining("Material");
        ProductCapture same = capture(Map.of("attributes", Map.of("Bahan", "Poliester", "Material", "Polyester")));
        assertThat(resolver.resolveFromDefinitions(same, List.of(material), null)).containsExactly(attribute("material", "poly"));
    }

    @Test
    void conditionalRequiredPropertiesAreCheckedAgainstResolvedTargetValuesRegardlessOfDefinitionOrder() {
        Map<String, Object> battery = property("battery", "Contains Batteries", true, "yes", "Yes", "no", "No");
        Map<String, Object> type = property("battery-type", "Battery Type", false, "lithium", "Lithium");
        type.put("requirement_conditions", List.of(Map.of("condition_type", "VALUE_ID_MATCH", "attribute_id", "battery", "attribute_value_id", "yes")));
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of("attributes", Map.of("Contains Batteries", "Yes"))),
                List.of(type, battery), null)).hasMessageContaining("Battery Type");
        assertThat(resolver.resolveFromDefinitions(capture(Map.of("attributes", Map.of("Contains Batteries", "No"))), List.of(type, battery), null))
                .containsExactly(attribute("battery", "no"));
        assertThat(resolver.resolveFromDefinitions(capture(Map.of("attributes", Map.of("Contains Batteries", "Yes", "Battery Type", "Lithium"))),
                List.of(type, battery), null)).containsExactly(attribute("battery-type", "lithium"), attribute("battery", "yes"));
    }

    @Test
    void unknownRequiredConditionDoesNotPretendThePropertyIsOptional() {
        Map<String, Object> definition = property("cert", "Safety Certification", false);
        definition.put("requirement_conditions", List.of(Map.of("condition_type", "NEW_RULE")));
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of()), List.of(definition), null))
                .hasMessageContaining("必填条件").hasMessageContaining("Safety Certification");
    }

    @Test
    void salesAttributesStayOutOfProductAttributesAndUnmatchedOptionalPropertiesAreOmitted() {
        Map<String, Object> color = property("color", "Color", true, "red", "Red");color.put("type", "SALES_PROPERTY");
        assertThat(resolver.resolveFromDefinitions(capture(Map.of("attributes", Map.of("Color", "Red", "Material", "Unknown"))),
                List.of(color, property("material", "Material", false, "cotton", "Cotton")), null)).isEmpty();
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of()), List.of(color), List.of(attribute("color", "red"))))
                .hasMessageContaining("不属于目标类目");
    }

    @Test
    void malformedOfficialResponseAndInvalidTargetCategoryFailBeforeGuessing() {
        Shop shop = mock(Shop.class);when(shop.getShopCipher()).thenReturn("cipher");
        MarketplaceAccess.Connection connection = new MarketplaceAccess.Connection(shop,
                new MarketplaceAccess.TokenSet("access", "refresh", Instant.now(), Instant.now(), List.of()));
        when(client.apiVersion()).thenReturn("202309");
        when(client.apiGet(anyString(), anyString(), anyString(), anyMap())).thenReturn(Map.of("data", Map.of()));
        assertThatThrownBy(() -> resolver.resolve(connection, capture(Map.of()), "601302", "v2", null)).hasMessageContaining("缺少 attributes");
        assertThatThrownBy(() -> resolver.resolve(connection, capture(Map.of()), "../invalid", "v2", null)).hasMessageContaining("类目 ID 无效");
        assertThatThrownBy(() -> resolver.resolveFromDefinitions(capture(Map.of()), List.of(Map.of("type", "PRODUCT_PROPERTY")), null))
                .hasMessageContaining("定义不完整");
    }

    private ProductCapture capture(Map<String, Object> normalized) {
        return new ProductCapture("org", "user", "key", "fingerprint", "TIKTOK", "https://shop.tiktok.com/view/product/1730135047201523374",
                "source-shop", "1730135047201523374", "Cherry Women's White Cotton T-Shirt", "Source description", "女士短袖", null,
                "MYR", BigDecimal.ONE, BigDecimal.TEN, mapper.writeValueAsString(normalized), "{}");
    }

    private static Map<String, Object> property(String id, String name, boolean required, String... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);result.put("name", name);result.put("type", "PRODUCT_PROPERTY");
        result.put("is_requried", required);result.put("is_customizable", false);result.put("is_multiple_selection", false);
        var choices = new java.util.ArrayList<Map<String, Object>>();
        for (int index = 0; index < values.length; index += 2) choices.add(Map.of("id", values[index], "name", values[index + 1]));
        result.put("values", choices);
        return result;
    }

    private static Map<String, Object> attribute(String id, String valueId) {
        return Map.of("id", id, "values", List.of(Map.of("id", valueId)));
    }
}
