package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(result.contains("必须采用图2每格的动作与场景"));
        assertTrue(result.contains("铺满1:1画布"));
        assertTrue(result.contains("禁止输出九宫格布局、画中画、分屏、拼贴"));
        assertTrue(result.contains("转场只服从后附时间轴"));
        assertTrue(result.contains("被遮挡或出画的肢体无需补出"));
        assertTrue(result.contains("纯商品镜头可以无人无手"));
        assertTrue(result.length() < 700);
        assertTrue(result.endsWith("原提示词"));
    }
}
