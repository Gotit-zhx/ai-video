package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void gridStoryboardSafetyPromptKeepsTheGridAsInputButRemovesItsLayoutFromOutput() {
        String result = AiVideoGenerationService.gridStoryboardSafetyPrompt("原提示词", "1:1");

        assertTrue(result.contains("图2是按左到右、上到下读取01→09的时间索引"));
        assertTrue(result.contains("采用图2每格的动作与场景意图"));
        assertTrue(result.contains("只执行后附时间轴已归一化的动作、景别与转场"));
        assertTrue(result.contains("铺满1:1画布"));
        assertTrue(result.contains("禁止输出九宫格布局、画中画、分屏、拼贴"));
        assertTrue(result.contains(VideoTimelineSemantics.TRANSITION_POLICY));
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

    @Test
    void finalAudioRuleKeepsNarrationOffCameraInTheTargetMarketLanguage() {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getScriptJson()).thenReturn("""
                {"shots":[{"narration":"旁白：这件新品现在登场"}]}
                """);
        when(task.getTargetMarket()).thenReturn("泰国");
        when(task.getMusicMode()).thenReturn("GENERATED");

        String result = AiVideoGenerationService.finalAudioPerformancePrompt(
                "低优先级旧提示：模特自然开口并同步口型，不要生成音乐。",
                task,
                new ObjectMapper());

        int finalRuleStart = result.indexOf("【最终音画硬约束｜最高优先级并覆盖前文】");
        assertTrue(finalRuleStart > result.indexOf("模特自然开口并同步口型"));
        String finalRule = result.substring(finalRuleStart);
        assertTrue(finalRule.contains("所有出镜人物全程保持嘴唇自然闭合、下颌稳定"));
        assertTrue(finalRule.contains("只允许按时间轴生成与画面人物分离的画外旁白"));
        assertTrue(finalRule.contains("目标市场“泰国”对应的本地语言"));
        assertTrue(finalRule.contains("逐字朗读给定原文"));
        assertTrue(finalRule.contains("具有清晰可辨主旋律和稳定节拍的纯器乐伴奏"));
        assertFalse(finalRule.contains("自然开口并同步口型"));
        assertTrue(result.endsWith("与前文任何要求冲突时，只执行本段。"));
    }

    @Test
    void finalAudioRuleForbidsAllVoicesWhenTheTimelineHasNoNarration() {
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getScriptJson()).thenReturn("""
                {"shots":[{"narration":"旁白：   "},{"narration":""}]}
                """);
        when(task.getMusicMode()).thenReturn("GENERATED");

        String result = AiVideoGenerationService.finalAudioPerformancePrompt(
                "原视频提示词",
                task,
                new ObjectMapper());

        assertFalse(AiVideoTaskService.hasVoiceover(task, new ObjectMapper()));
        assertTrue(result.contains("时间轴没有旁白"));
        assertTrue(result.contains("全片禁止对白、旁白、口播、歌声、哼唱、呢喃、乱码或任何无意义人声"));
        assertTrue(result.contains("音轨只允许纯器乐BGM和必要的自然环境声"));
        assertTrue(result.contains("具有清晰可辨主旋律和稳定节拍的纯器乐伴奏"));
        assertTrue(result.endsWith("与前文任何要求冲突时，只执行本段。"));
    }

    @Test
    void everyVideoModeUsesNormalizedTimelineAndOneTransitionPolicy() {
        AiVideoTaskService service = testService(mock(AiVideoTaskMapper.class), mock(AiVideoTaskItemMapper.class),
                mock(ProductFunctionAnalysisService.class));
        for (String mode : List.of("MODEL_WEAR", "PRODUCT_ONLY")) {
            for (boolean direct : List.of(false, true)) {
                AiVideoTask task = mock(AiVideoTask.class);
                when(task.getGenerationMode()).thenReturn(mode);
                when(task.isDirectReferenceVideo()).thenReturn(direct);
                when(task.getScriptJson()).thenReturn("""
                        {"shots":[
                          {"sequence":1,"motionDescription":"左手提起衣摆，镜头拉远展示全身","sceneDescription":"全景镜头，白色房间","cameraMovement":"后退并横移","transitionDescription":"硬切"},
                          {"sequence":2,"motionDescription":"转身展示后背","sceneDescription":"蓝色房间","transitionDescription":"同一镜头连续，无转场，背景切换"},
                          {"sequence":3,"motionDescription":"自然放下手","transitionDescription":"硬切到商品特写"}
                        ]}
                        """);
                StringBuilder prompt = new StringBuilder();

                ReflectionTestUtils.invokeMethod(service, "appendImageToVideoTimeline", prompt, task);

                String result = prompt.toString();
                assertTrue(result.contains(VideoTimelineSemantics.TRANSITION_POLICY));
                assertTrue(result.contains("左手提起衣摆"));
                assertTrue(result.contains("转身展示后背"));
                assertTrue(result.contains("自然放下手"));
                assertTrue(result.contains("进入转场：无"));
                assertTrue(result.contains("进入转场：同镜头连续，无转场"));
                assertTrue(result.contains("进入转场：硬切到商品特写"));
                assertTrue(result.contains("保持固定机位并横移"));
                assertFalse(result.contains("场景明显变化时在边界干净硬切"));
                assertFalse(result.contains("镜头拉远展示全身"));
                assertFalse(result.contains("全景镜头，白色房间"));
                assertFalse(result.contains("进入转场：同一镜头连续，无转场，背景切换"));
                assertEquals(!direct, result.contains("图2中同编号关键帧"));
            }
        }
    }

    @Test
    void storedGeneratedPromptsMigrateToNewVersionWithoutRegeneratingVideoOrTouchingCustomText() {
        AiVideoTaskMapper tasks = mock(AiVideoTaskMapper.class);
        AiVideoTaskItemMapper items = mock(AiVideoTaskItemMapper.class);
        ProductFunctionAnalysisService analysis = mock(ProductFunctionAnalysisService.class);
        AiVideoTask task = mock(AiVideoTask.class);
        when(task.getId()).thenReturn("task-1");
        when(task.getGenerationMode()).thenReturn("MODEL_WEAR");
        when(task.getAspectRatio()).thenReturn("9:16");
        when(task.getScriptJson()).thenReturn("{\"shots\":[]}");
        AiVideoTaskItem old = mock(AiVideoTaskItem.class);
        when(old.getName()).thenReturn("样品");
        when(old.getVideoPromptText()).thenReturn("提示词版本：V11.7 · 模特静默与旋律伴奏版\n旧时间轴");
        AiVideoTaskItem custom = mock(AiVideoTaskItem.class);
        when(custom.getVideoPromptText()).thenReturn("自定义：固定机位展示商品。");
        when(custom.getName()).thenReturn("自定义样品");
        when(tasks.selectList(null)).thenReturn(List.of(task));
        when(items.selectList(any())).thenReturn(List.of(old, custom));
        when(analysis.applyToVideoPrompt(eq(task), anyString(), isNull())).thenAnswer(call -> call.getArgument(1));

        testService(tasks, items, analysis).migrateLegacyGeneratedVideoPrompts();

        verify(old).updateVideoPrompt(argThat(prompt -> prompt.startsWith("提示词版本：V11.8 · 统一转场与近景动作归一")));
        verify(items).updateById(old);
        verify(custom, never()).updateVideoPrompt(anyString());
        verify(items, never()).updateById(custom);
    }

    @SuppressWarnings("unchecked")
    private AiVideoTaskService testService(AiVideoTaskMapper tasks, AiVideoTaskItemMapper items,
                                         ProductFunctionAnalysisService analysis) {
        return new AiVideoTaskService(tasks, items, null, null, null, null, new ObjectMapper(),
                analysis, null, mock(ObjectProvider.class), ".");
    }
}
