package com.yingdao.aicommerce.video.application;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVideoImageProcessorTest {
    @Test
    void cropsTopLeftStoryboardCellAsFirstFrame() {
        BufferedImage storyboard = new BufferedImage(300, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = storyboard.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 100, 200);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(100, 0, 200, 600);
        } finally {
            graphics.dispose();
        }

        BufferedImage firstFrame = AiVideoImageProcessor.cropFirstStoryboardCell(storyboard);

        assertEquals(100, firstFrame.getWidth());
        assertEquals(200, firstFrame.getHeight());
        assertEquals(Color.RED.getRGB(), firstFrame.getRGB(50, 100));
    }

    @Test
    void closerPortraitCompositionAllocatesEnoughPixelsToFace() throws Exception {
        String requirement = privateStaticString("CLOSE_PORTRAIT_COMPOSITION_REQUIREMENT");

        assertTrue(requirement.contains("推进约二分之一距离"));
        assertTrue(requirement.contains("约 575 像素"));
        assertTrue(requirement.contains("膝盖、小腿、双脚和鞋子必须位于画面之外且不可见"));
        assertTrue(requirement.contains("不能为了保留双脚或展示整套穿搭而缩小人物"));
        assertFalse(requirement.contains("双脚完整入镜"));
    }

    @Test
    void storyboardGridUsesCloserFramingInsteadOfMandatoryFullBody() throws Exception {
        String requirement = privateStaticString("GRID_FACE_PIXEL_REQUIREMENT");

        assertTrue(requirement.contains("约 190 像素"));
        assertTrue(requirement.contains("膝盖、小腿、双脚和鞋子不可见"));
        assertFalse(requirement.contains("鞋底全部可见"));
    }

    private static String privateStaticString(String fieldName) throws Exception {
        Field field = AiVideoImageProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
