package com.yingdao.aicommerce.product.application.provider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ShopeeMaterialDefaultsTest {
    private final Map<String,Object> material = Map.of("attribute_id",100134,"name","Material",
            "attribute_value_list",List.of(Map.of("value_id",1149,"name","Cotton")));
    @Test void fillsCottonFromOfficialOptions() {
        assertThat(ShopeeMaterialDefaults.resolve(List.of(),List.of(material)).toString())
                .contains("100134","1149","Cotton");
    }
    @Test void preservesExplicitMaterialAndOtherAttributes() {
        List<?> existing=List.of(Map.of("attribute_id",100134L,"attribute_value_list",List.of(Map.of("value_id",1165))),
                Map.of("attribute_id",5,"attribute_value_list",List.of(Map.of("value_id",9))));
        assertThat(ShopeeMaterialDefaults.resolve(existing,List.of(material))).isEqualTo(existing);
    }
    @Test void replacesEmptyMaterialWithoutDuplicate() {
        assertThat(ShopeeMaterialDefaults.resolve(List.of(Map.of("attribute_id",100134,"attribute_value_list",List.of())),
                List.of(material))).hasSize(1);
    }
    @Test void doesNotInventUnsupportedCotton() {
        assertThatThrownBy(()->ShopeeMaterialDefaults.resolve(List.of(),List.of(
                Map.of("attribute_id",1,"name","Material","attribute_value_list",List.of())))).hasMessageContaining("不支持");
        assertThat(ShopeeMaterialDefaults.resolve(List.of(),List.of())).isEmpty();
    }
}
