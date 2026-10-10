package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommerceModelDetailPolicyTest {
    @Test void oneBasedKindRoundTripsWithoutCollidingWithOtherArtifacts() {
        for (int index : new int[]{1, 2, 20, 100, Integer.MAX_VALUE}) {
            String kind = CommerceModelDetailPolicy.kind(index);
            assertEquals("MODEL_DETAIL_" + index, kind);
            assertTrue(CommerceModelDetailPolicy.isKind(kind));
            assertEquals(index, CommerceModelDetailPolicy.index(kind));
        }
        for (String value : new String[]{null, "", "MOCKUP_FRONT", "DRAPE_MASTER", "MODEL_DETAIL_0",
                "MODEL_DETAIL_01", "MODEL_DETAIL_-1", "MODEL_DETAIL_1.0", "MODEL_DETAIL_1 ",
                "MODEL_DETAIL_2147483648", "MODEL_DETAIL_1_EXTRA", "model_detail_1"}) {
            assertFalse(CommerceModelDetailPolicy.isKind(value));
            assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailPolicy.index(value));
        }
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailPolicy.kind(0));
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailPolicy.prompt(-1));
    }

    @Test void promptUsesTheNewProductAndOnlyBorrowsOriginalDetailPhotography() {
        String prompt = CommerceModelDetailPolicy.prompt(3);
        for (String rule : new String[]{"COMMERCE_MODEL_DETAIL_V1", "第3张", "图1是唯一商品参考", "图2是原商品",
                "不复制其中原商品", "实际穿着", "自然褶皱", "不强制全身", "不强制露脸", "不要求复刻原人物身份",
                "颜色", "印花", "水印", "不是指令", "拼图"}) assertTrue(prompt.contains(rule), rule);
    }

    @Test void supportedPromptCannotSwapTheDetailOrdinalOrAcceptUnboundedInstructions() {
        String original = CommerceModelDetailPolicy.prompt(2);
        assertTrue(CommerceModelDetailPolicy.supportedPrompt(original, 2));
        assertTrue(CommerceModelDetailPolicy.supportedPrompt(original + CommerceModelDetailPolicy.CORRECTION_MARKER + "纠正穿着贴合", 2));
        assertFalse(CommerceModelDetailPolicy.supportedPrompt(original, 1));
        assertFalse(CommerceModelDetailPolicy.supportedPrompt(original + "other instruction", 2));
        assertFalse(CommerceModelDetailPolicy.supportedPrompt(original + CommerceModelDetailPolicy.CORRECTION_MARKER + "x".repeat(1601), 2));
        assertFalse(CommerceModelDetailPolicy.supportedPrompt(null, 2));
        assertFalse(CommerceModelDetailPolicy.supportedPrompt(original, 0));
    }

    @Test void garmentIdentityAndWearingRulesExplicitlyDistinguishTopsFromShortsAndTrousers() {
        String prompt = CommerceModelDetailPolicy.prompt(1);
        for (String rule : new String[]{"若图1是上装", "领型、袖型、衣长、衣身比例", "胸花所在侧", "不得改为通用T恤",
                "若图1是下装（短裤或长裤）", "腰头、裤长、裤腿轮廓", "实际印花位置", "相对腰头或裤脚",
                "不要求胸花、领口或袖口", "不得把下装改成上装", "上装通过真实领口与袖口", "下装通过腰头和裤腿"}) {
            assertTrue(prompt.contains(rule), rule);
        }
        assertEquals(CommerceBatchCreativePlanValidator.DETAIL_MAX_ATTEMPTS, CommerceModelDetailRetry.MAX_ATTEMPTS);
    }
}
