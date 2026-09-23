package com.yingdao.aicommerce.product.application.provider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ShopeeAttributeFactsTest {
    Map<String,Object> def(int id,String name,String value){return Map.of("attribute_id",id,"name",name,"attribute_value_list",List.of(Map.of("value_id",id*10,"name",value)));}
    @Test void resolvesMalayFieldsWithoutModel() {
        var ds=List.of(def(1,"Neckline","Round Neck"),def(2,"Sleeve Length","Short Sleeves"),def(3,"Plus Size","No"));
        var source=Map.<String,Object>of("attributes",Map.of("Garis Leher","Leher Krew","Panjang Lengan Baju","Lengan pendek","Size type","Biasa"));
        assertThat(ShopeeAttributeFacts.resolve(List.of(),ds,source,"")).hasSize(3);
    }
    @Test void doesNotInventNegativeOrResolveConflictingSource() {
        assertThat(ShopeeAttributeFacts.resolve(List.of(),List.of(def(1,"Cropped Top","No")),Map.of(),"T shirt")).isEmpty();
        assertThat(ShopeeAttributeFacts.resolve(List.of(),List.of(def(1,"Neckline","Round Neck")),
                Map.of("attributes",Map.of("Neckline","V Neck","Garis Leher","Leher Krew")),"Round Neck")).isEmpty();
    }
    @Test void matchesTitleButPreservesExisting() {
        var ds=List.of(def(1,"Neckline","Round Neck"),def(2,"Sleeve Length","Short Sleeves"));
        assertThat(ShopeeAttributeFacts.resolve(List.of(),ds,Map.of(),"Short Sleeve Round Neck Tee")).hasSize(2);
        var old=List.of(Map.of("attribute_id",1,"attribute_value_list",List.of(Map.of("value_id",99))));
        assertThat(ShopeeAttributeFacts.resolve(old,ds,Map.of(),"")).isEqualTo(old);
    }
}
