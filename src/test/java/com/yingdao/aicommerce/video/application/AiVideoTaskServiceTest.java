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
        assertTrue(result.contains("被遮挡或出画的肢体无需补出"));
        assertTrue(result.contains("纯商品镜头可以无人无手"));
        assertTrue(result.length() < 700);
        assertTrue(result.endsWith("原提示词"));
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
        assertTrue(result.contains("脸从发际线到下巴的高度在每一帧都不得低于画面高度 15%"));
        assertTrue(result.contains("被遮挡或出画的肢体无需补出"));
        assertTrue(result.contains("禁止远景、全景、完整全身"));
        assertFalse(result.contains("动作所需双手必须清楚"));
    }
}
