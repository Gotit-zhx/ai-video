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
        String result = AiVideoGenerationService.gridStoryboardSafetyPrompt("原提示词");

        assertTrue(result.contains("图2是3×3九宫格时间索引表"));
        assertTrue(result.contains("零输出权限"));
        assertTrue(result.contains("严禁整张复现、局部复现"));
        assertTrue(result.contains("恰好两条手臂和两只手"));
        assertTrue(result.endsWith("原提示词"));
    }
}
