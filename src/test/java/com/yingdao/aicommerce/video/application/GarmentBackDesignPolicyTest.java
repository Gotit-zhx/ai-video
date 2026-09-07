package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GarmentBackDesignPolicyTest {
    private final ProductFunctionAnalysisService service = new ProductFunctionAnalysisService(
            mock(AiVideoTaskItemMapper.class), mock(ModelConfigService.class), new ObjectMapper(),
            "http://127.0.0.1:1", "unused-test-token");

    @Test
    void garmentImageAndGridContractKeepStructuralEvidenceAndTheBackDesignPolicy() {
        String block = service.promptBlock(garmentTask(), """
                {"version":"GARMENT_TOPOLOGY_V2","imageRightSleeve":"原图可见右侧短袖袖口",
                 "seamConnections":["背部中缝"],"fixedStructures":["保留斜领"]}
                """);

        assertTrue(block.contains("原图可见右侧短袖袖口"));
        assertTrue(block.contains("背部中缝"));
        assertTrue(block.contains(GarmentBackDesignPolicy.PROMPT));
        assertTrue(block.indexOf(GarmentBackDesignPolicy.PROMPT)
                < block.indexOf("【服装结构与穿法契约结束】"));
    }

    @Test
    void legacyOrInvalidAnalysisDoesNotOmitTheUserBackDesignRuleOrInventEvidence() {
        for (String analysis : new String[] {null, "", "{invalid", "{\"version\":\"PRODUCT_FUNCTION_V1\"}"}) {
            String block = service.promptBlock(garmentTask(), analysis);
            assertTrue(block.contains(GarmentBackDesignPolicy.PROMPT));
            assertFalse(block.contains("原图画面右侧袖："));
        }
    }

    @Test
    void videoPromptAddsTheSamePolicyOnceBeforeTheTimelineAndRefreshesExistingBlocks() {
        AiVideoTask task = garmentTask();
        String original = "生成同一商品视频。\n【时间轴】\n第03格：转身展示后背。";
        String first = service.applyToVideoPrompt(task, original, "{\"version\":\"GARMENT_TOPOLOGY_V2\"}");
        String reapplied = service.applyToVideoPrompt(task, first, "{\"version\":\"GARMENT_TOPOLOGY_V2\"}");

        // Existing block removal normalizes paragraph separators. Check instruction stability,
        // not whether the original prompt used one or two newlines before the injected block.
        assertEquals(first.replaceAll("\\n{2,}", "\n"), reapplied.replaceAll("\\n{2,}", "\n"));
        assertEquals(1, first.split("【背面图案来源规则】", -1).length - 1);
        assertEquals(1, reapplied.split("【背面图案来源规则】", -1).length - 1);
        assertTrue(first.indexOf("【背面图案来源规则】") < first.indexOf("【时间轴】"));
        assertTrue(first.contains("第03格：转身展示后背。"));
    }

    @Test
    void noBackReferenceDefaultsToUnprintedWithoutErasingRealRearReferencesOrStructure() {
        String policy = GarmentBackDesignPolicy.PROMPT;
        assertTrue(policy.contains("没有明确的背面图案参考或无法确认时，生成的后背不添加图案"));
        assertTrue(policy.contains("禁止把正面内容复制、镜像、环绕延续到后背"));
        assertTrue(policy.contains("同一张正背多视角合成图中可明确定位的背面区域"));
        assertTrue(policy.contains("不得根据图片数量猜测是否提供了背面"));
        assertTrue(policy.contains("不得抹掉用户已明确提供的真实背面花纹"));
        assertTrue(policy.contains("保留商品面料、底色、真实织物肌理、缝线、剪裁、开口、肩带"));
        assertTrue(policy.contains("不是用户新增的背面图案授权"));
        assertTrue(policy.contains("不要求正面印花出现在背面"));
    }

    @Test
    void nonGarmentProductsDoNotReceiveGarmentBackInstructions() {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getGenerationMode()).thenReturn("PRODUCT_DISPLAY");
        when(task.getProductCategory()).thenReturn("日用品");

        assertEquals("", service.promptBlock(task, null));
        assertFalse(service.promptBlock(task, """
                {"version":"PRODUCT_FUNCTION_V1","functional":true,"productType":"清洁刷"}
                """).contains("【背面图案来源规则】"));
        assertEquals("", service.promptBlock(null, null));
    }

    private AiVideoTask garmentTask() {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getGenerationMode()).thenReturn("MODEL_WEAR");
        when(task.getProductCategory()).thenReturn("服装");
        return task;
    }
}
