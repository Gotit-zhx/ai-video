package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.domain.AiVideoTask;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiVideoTaskServiceTest {

    @Test
    void copyExactRejectsAnIncompleteVideoStream() {
        byte[] partial = new byte[]{1, 2, 3};

        IOException failure = assertThrows(IOException.class, () ->
                AiVideoTaskService.copyExact(new ByteArrayInputStream(partial), new ByteArrayOutputStream(), 5));

        assertEquals("视频文件读取不完整：期望 5 字节，实际 3 字节", failure.getMessage());
    }

    @Test
    void copyExactAcceptsACompleteVideoStream() throws Exception {
        byte[] video = new byte[]{1, 2, 3, 4};
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertEquals(video.length,
                AiVideoTaskService.copyExact(new ByteArrayInputStream(video), output, video.length));
        assertArrayEquals(video, output.toByteArray());
    }

    @Test
    void scriptVideoReplacementAppliesToEveryScriptModeWhenOriginalVideoIsEnabled() {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.isUseScriptReferenceVideo()).thenReturn(true);

        when(task.getGenerationMode()).thenReturn("MODEL_WEAR");
        assertTrue(AiVideoTaskService.isScriptVideoReplacement(task));
        when(task.getGenerationMode()).thenReturn("PRODUCT_ONLY");
        assertTrue(AiVideoTaskService.isScriptVideoReplacement(task));
        when(task.getGenerationMode()).thenReturn("CAST_PRODUCT");
        assertTrue(AiVideoTaskService.isScriptVideoReplacement(task));

        when(task.isUseScriptReferenceVideo()).thenReturn(false);
        assertFalse(AiVideoTaskService.isScriptVideoReplacement(task));
    }

    @Test
    void gridStoryboardSafetyPromptKeepsTheGridAsInputButRemovesItsLayoutFromOutput() {
        String result = AiVideoGenerationService.gridStoryboardSafetyPrompt("原提示词", "1:1");

        assertTrue(result.contains("图2是按左到右、上到下读取01→09的时间索引"));
        assertTrue(result.contains("必须采用图2每格的动作与场景"));
        assertTrue(result.contains("铺满1:1画布"));
        assertTrue(result.contains("禁止输出九宫格布局、画中画、分屏、拼贴"));
        assertTrue(result.contains("转场只服从后附时间轴"));
        assertTrue(result.contains("张嘴、说话或口型"));
        assertTrue(result.contains("成片人物每帧自然闭口"));
        assertTrue(result.contains("被遮挡或出画的肢体无需补出"));
        assertTrue(result.contains("纯商品镜头可以无人无手"));
        assertTrue(result.length() < 700);
        assertTrue(result.endsWith("原提示词"));
    }

    @Test
    void finalNarrationGuardMovesSavedDialogueOffCameraWithoutRemovingUploadedMusic() {
        String oldPrompt = "画面：模特展示衣服\n"
                + "【音频】已上传音乐是全片唯一BGM并保持连续；按时间轴生成指定对白/旁白。\n"
                + "【人声】逐字说时间轴文字，并同步口型。\n"
                + "【旁白语言锁定】全部对白使用泰语。\n"
                + "对白 / 旁白：现在开始介绍\n"
                + "动作：轻轻转身";

        String result = AiVideoGenerationService.offCameraNarrationPrompt(oldPrompt, true, true, "泰语");

        assertTrue(result.contains("画面：模特展示衣服"));
        assertTrue(result.contains("动作：轻轻转身"));
        assertTrue(result.contains("已上传音乐是唯一 BGM"));
        assertTrue(result.contains("从0秒开始贯穿成片结尾"));
        assertTrue(result.contains("清晰主旋律和稳定节拍"));
        assertTrue(result.contains("嘴唇静止"));
        assertTrue(result.contains("不出镜的画外配音者"));
        assertTrue(result.contains("不增删、不改写、不重复"));
        assertTrue(result.contains("旁白语言锁定为泰语"));
        assertTrue(result.contains("画外旁白（画面人物闭口）：现在开始介绍"));
        assertFalse(result.contains("按时间轴生成指定对白"));
        assertFalse(result.contains("并同步口型"));
        assertFalse(result.contains("对白 / 旁白："));
        assertTrue(result.length() <= 20_000);
    }

    @Test
    void finalNarrationGuardForEmptyNarrationKeepsInstrumentalBgmAndForbidsAllVoices() {
        String result = AiVideoGenerationService.offCameraNarrationPrompt(
                "x".repeat(20_000), false, false, "泰国市场对应的主要消费语言");

        assertTrue(result.contains("纯器乐 BGM"));
        assertTrue(result.contains("从0秒开始贯穿成片结尾"));
        assertTrue(result.contains("清晰主旋律和稳定节拍"));
        assertTrue(result.contains("任何可辨识人声"));
        assertTrue(result.contains("本任务无画外旁白"));
        assertTrue(result.length() <= 20_000);
    }

    @Test
    void localizedNarrationAlwaysUsesAnOffCameraPrefix() {
        assertEquals("旁白：Sawadee",
                NarrationLocalizationService.preservePrefix("对白：你好", "Sawadee"));
        assertEquals("旁白：今天看新款",
                NarrationLocalizationService.preservePrefix("口播：旧文", "旁白：今天看新款"));
    }

    @Test
    void productOnlyHandDirectiveDoesNotTurnOneHandIntoAForcedPair() {
        String oneHand = AiVideoTaskService.productOnlyHandDirective("单手拿起商品", "商品近景");
        String genericHand = AiVideoTaskService.productOnlyHandDirective("拿取商品", "桌面展示");
        String twoHands = AiVideoTaskService.productOnlyHandDirective("双手展开商品", "正面展示");
        String handheldCamera = AiVideoTaskService.productOnlyHandDirective("手持镜头缓慢横移", "商品近景");

        assertTrue(oneHand.contains("只出现一只自然手"));
        assertTrue(oneHand.contains("不新增第二只手"));
        assertTrue(genericHand.contains("只出现一只自然手"));
        assertTrue(twoHands.contains("最多出现两只自然手"));
        assertTrue(handheldCamera.contains("不出现人物、手臂、手掌或手指"));
        assertFalse(AiVideoTaskService.handInteractionRequired("handheld camera moves closer"));
        assertFalse(AiVideoTaskService.handInteractionRequired("手持拍摄并轻微环绕"));
        assertTrue(AiVideoTaskService.handInteractionRequired("手持商品靠近镜头"));
        assertTrue(AiVideoTaskService.handInteractionRequired("手持镜头后拿起商品"));
    }

    @Test
    void closeFramingLockKeepsNearCompositionWithoutForcingHiddenHands() {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getGenerationMode()).thenReturn("MODEL_WEAR");
        StringBuilder prompt = new StringBuilder();

        AiVideoTaskService.appendCloseFramingLock(prompt, task);

        String result = prompt.toString();
        assertTrue(result.contains("头顶至大腿中下段"));
        assertTrue(result.contains("正面或侧脸可见时"));
        assertTrue(result.contains("头部轮廓高度不低于 15%"));
        assertTrue(result.contains("不得为满足人脸条件强行补画五官或把人物转正"));
        assertTrue(result.contains("本镜头按时间轴可见的当前商品部位"));
        assertTrue(result.contains("被遮挡或出画的肢体无需补出"));
        assertTrue(result.contains("禁止远景、全景、完整全身"));
        assertFalse(result.contains("头顶、完整五官"));
        assertFalse(result.contains("胸前商品"));
        assertFalse(result.contains("动作所需双手必须清楚"));
    }
}
