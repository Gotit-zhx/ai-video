package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommerceModelDetailGridPolicyTest {
    @Test void persistedProductionV1RequestStillValidatesWithoutChangingItsBody() throws Exception {
        try(var input=getClass().getResourceAsStream("/commerce/detail-grid-v1-prompt.txt")) {
            assertNotNull(input);
            String prompt=new String(input.readAllBytes(),StandardCharsets.UTF_8).strip();
            assertEquals(List.of(7,8,9),CommerceModelDetailGridPolicy.indices(prompt));
            assertFalse(CommerceModelDetailGridPolicy.usesAmbience(prompt));
        }
    }
    @Test void newGroupsUseVersionedProductCanvasAndAmbienceOnlyReferences() {
        String prompt=CommerceModelDetailGridPolicy.prompt(List.of(1,2,3,4));
        assertEquals(List.of(1,2,3,4),CommerceModelDetailGridPolicy.indices(prompt));
        assertTrue(prompt.startsWith("【COMMERCE_MODEL_DETAIL_GRID_V2:"));
        assertTrue(CommerceModelDetailGridPolicy.usesAmbience(prompt));
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridPolicy.indices(prompt+"changed"));
    }
    @Test void onlyNewQualityCorrectionsOptIntoAmbienceProcessing() {
        String original=CommerceModelDetailPolicy.prompt(7);
        String legacy=original+CommerceModelDetailPolicy.CORRECTION_MARKER+"保持旧请求不变";
        assertFalse(CommerceModelDetailGridPolicy.usesAmbience(original));
        assertFalse(CommerceModelDetailGridPolicy.usesAmbience(legacy));
        String revised=original+CommerceModelDetailPolicy.CORRECTION_MARKER+CommerceModelDetailGridPolicy.correctionReferenceInstructions();
        assertTrue(CommerceModelDetailPolicy.supportedPrompt(revised,7));
        assertTrue(CommerceModelDetailGridPolicy.usesAmbience(revised));
    }
    @Test void sourceStylePlanIsFrozenPerCellAndTamperingCannotChangePaidBody() {
        var seated=guide("SEATED_MODEL","坐姿中景","椅子上侧坐","户外咖啡馆","自然侧光");
        var standing=guide("STANDING_MODEL","全身纵向构图","手提包站立","咖啡馆街边","自然侧光");
        var plan=new CommerceModelDetailGridPolicy.StylePlan(List.of(seated,standing),1,"");
        String prompt=CommerceModelDetailGridPolicy.stylePrompt(List.of(7,8),plan);
        assertEquals(List.of(7,8),CommerceModelDetailGridPolicy.indices(prompt));
        assertEquals(plan,CommerceModelDetailGridPolicy.stylePlan(prompt));
        assertEquals(List.of(seated,standing),CommerceModelDetailGridPolicy.styles(prompt));
        assertTrue(CommerceModelDetailGridPolicy.usesSourceStyle(prompt));
        assertTrue(CommerceModelDetailGridPolicy.requiresTemplateProduct(prompt));
        assertEquals(CommerceModelDetailGridPolicy.TEMPLATE_VERSION,CommerceModelDetailGridPolicy.sourceStyleVersion(prompt));
        assertFalse(CommerceModelDetailGridPolicy.usesAmbience(prompt));
        assertTrue(prompt.contains("坐姿中景"));assertTrue(prompt.contains("手提包站立"));
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridPolicy.indices(prompt+"改成一样"));
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridPolicy.stylePrompt(List.of(7),plan));
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridPolicy.stylePrompt(List.of(7,8),
                new CommerceModelDetailGridPolicy.StylePlan(plan.guides(),4,"")));
    }

    @Test void oldV3RequestsAndTheirCorrectionsKeepTheirExactVersionAndOriginalInstructions() {
        var plan=new CommerceModelDetailGridPolicy.StylePlan(List.of(guide("SEATED_MODEL","坐姿中景","侧坐","咖啡馆","侧光")),1,"");
        String original=CommerceModelDetailGridPolicy.stylePrompt(List.of(7),plan,CommerceModelDetailGridPolicy.STYLE_VERSION);
        assertEquals("COMMERCE_MODEL_DETAIL_GRID_V3",CommerceModelDetailGridPolicy.STYLE_VERSION);
        assertEquals(List.of(7),CommerceModelDetailGridPolicy.indices(original));
        assertTrue(CommerceModelDetailGridPolicy.usesSourceStyle(original));assertFalse(CommerceModelDetailGridPolicy.requiresTemplateProduct(original));
        assertTrue(original.contains("其他场景、姿态、机位、构图完整保留"));assertFalse(original.contains("MOCKUP_FRONT"));
        String corrected=CommerceModelDetailGridPolicy.stylePrompt(List.of(7),new CommerceModelDetailGridPolicy.StylePlan(plan.guides(),2,"修正衣服"),CommerceModelDetailGridPolicy.sourceStyleVersion(original));
        assertTrue(corrected.startsWith("【COMMERCE_MODEL_DETAIL_GRID_V3:"));assertEquals(List.of(7),CommerceModelDetailGridPolicy.indices(corrected));
    }

    @Test void v4TemplateIdentityOverridesSourceAdsHoldingPoseAndCollages() {
        var plan=new CommerceModelDetailGridPolicy.StylePlan(List.of(guide("OTHER","三人拼图和海报","手持衣服","不应沿用的场景值","不应沿用的光线值")),1,"");
        String prompt=CommerceModelDetailGridPolicy.stylePrompt(List.of(1),plan);
        assertEquals(List.of(1),CommerceModelDetailGridPolicy.indices(prompt));
        assertTrue(prompt.startsWith("【COMMERCE_MODEL_DETAIL_GRID_V4:"));
        for(String phrase:List.of("精确MOCKUP_FRONT套版图","黑白姿势参考","肩宽与肩线","衣长与衣宽比例","拉长成长裙","单人真实实穿照片","禁止手持","促销文案","图标","格内再拼图","一个主人物","相近景别的真实穿着展示"))
            assertTrue(prompt.contains(phrase),phrase);
        assertFalse(prompt.contains("不应沿用的场景值"));assertFalse(prompt.contains("不应沿用的光线值"));
        assertTrue(prompt.contains("任何冲突都以图1商品版型和单人实穿规则优先"));
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridPolicy.stylePrompt(List.of(1),plan,"V5"));
    }
    private static CommerceModelDetailStyleGuide.Guide guide(String shot,String composition,String pose,String setting,String lighting){
        return new CommerceModelDetailStyleGuide.Guide(CommerceModelDetailStyleGuide.CONTRACT_VERSION,"a".repeat(64),"b".repeat(64),"c".repeat(64),
                shot,composition,pose,setting,lighting,true,List.of(new CommerceModelDetailStyleGuide.Region(.3,.3,.2,.2)),
                "景别="+shot+"；构图="+composition+"；姿态="+pose+"；场景="+setting+"；光线="+lighting);
    }
}
