package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class WatermarkDetectionViewTest {
    @Test void whiteStudioBackgroundIsEligibleButBusyBackgroundIsNot() throws Exception {
        var studio = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = studio.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 400, 400);
            graphics.setColor(Color.DARK_GRAY); graphics.fillRect(100, 50, 200, 300);
        } finally { graphics.dispose(); }
        var eligible = WatermarkDetectionView.assessBackground(png(studio));
        assertTrue(WatermarkDetectionView.permitsSkipping(eligible));
        assertEquals(.625, (double) eligible.get("nearWhiteFraction"), .000001);
        assertEquals(1, (double) eligible.get("borderNearWhiteFraction"), .000001);
        for (int y = 0; y < 400; y++) for (int x = 0; x < 400; x++) studio.setRGB(x, y, (x / 10 + y / 10) % 2 == 0 ? 0x303030 : 0x909090);
        var complex = WatermarkDetectionView.assessBackground(png(studio));
        assertFalse(WatermarkDetectionView.permitsSkipping(complex));
        assertEquals(0, (double) complex.get("nearWhiteFraction"), .000001);
    }

    @Test void brightCenterWithDarkOuterBorderCannotSkipEditing() throws Exception {
        var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(Color.WHITE); graphics.fillRect(8, 8, 84, 84); }
        finally { graphics.dispose(); }
        var result = WatermarkDetectionView.assessBackground(png(image));
        assertTrue((double) result.get("nearWhiteFraction") > .5);
        assertEquals(0, (double) result.get("borderNearWhiteFraction"), .000001);
        assertFalse(WatermarkDetectionView.permitsSkipping(result));
    }

    @Test void nearWhitePixelThresholdsAndSkipRatiosAreInclusiveAndValidated() throws Exception {
        var image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        for (int color : new int[]{0xebebfa, 0xeaeaea, 0xebebfb}) {
            for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) image.setRGB(x, y, color);
            assertEquals(color == 0xebebfa, WatermarkDetectionView.permitsSkipping(WatermarkDetectionView.assessBackground(png(image))));
        }
        var boundary = new java.util.HashMap<String,Object>();
        boundary.put("method", WatermarkDetectionView.BACKGROUND_METHOD); boundary.put("eligibleToSkip", true);
        boundary.put("nearWhiteFraction", .5); boundary.put("borderNearWhiteFraction", .85);
        assertTrue(WatermarkDetectionView.permitsSkipping(boundary));
        boundary.put("nearWhiteFraction", Math.nextDown(.5)); assertFalse(WatermarkDetectionView.permitsSkipping(boundary));
        boundary.put("nearWhiteFraction", .5); boundary.put("borderNearWhiteFraction", Math.nextDown(.85)); assertFalse(WatermarkDetectionView.permitsSkipping(boundary));
        boundary.put("borderNearWhiteFraction", Double.NaN); assertFalse(WatermarkDetectionView.permitsSkipping(boundary));
    }

    @Test void faintOverlayInShadowsBecomesBrighterAndMoreVisibleWithoutChangingSource() throws Exception {
        var original = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(new Color(22, 22, 22)); graphics.fillRect(0, 0, 400, 300);
            graphics.setColor(new Color(28, 28, 28)); graphics.fillRect(170, 0, 60, 300);
        } finally { graphics.dispose(); }
        byte[] bytes = png(original), unchanged = bytes.clone();
        var result = decode(WatermarkDetectionView.create(bytes));
        assertArrayEquals(unchanged, bytes);
        assertEquals(400, result.getWidth()); assertEquals(300, result.getHeight());
        assertTrue(gray(result, 120, 150) > 22, "Dark background should be lifted");
        assertTrue(gray(result, 200, 150) - gray(result, 120, 150) > 6, "Faint overlay contrast should increase");
    }

    @Test void largePortraitKeepsFullCanvasAndCornerLocations() throws Exception {
        var original = new BufferedImage(1200, 2400, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.DARK_GRAY); graphics.fillRect(0, 0, 1200, 2400);
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 200, 200);
            graphics.setColor(Color.WHITE); graphics.fillRect(1000, 2200, 200, 200);
        } finally { graphics.dispose(); }
        var result = decode(WatermarkDetectionView.create(png(original)));
        assertEquals(480, result.getWidth()); assertEquals(960, result.getHeight());
        assertTrue(gray(result, 20, 20) > 240);
        assertTrue(gray(result, 460, 940) > 240);
        assertTrue(gray(result, 460, 20) < 200);
        assertTrue(gray(result, 20, 940) < 200);
    }

    @Test void noisyImageStaysWithinPixelAndEncodedByteLimits() throws Exception {
        var original = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
        var random = new Random(73);
        for (int y = 0; y < original.getHeight(); y++) for (int x = 0; x < original.getWidth(); x++) original.setRGB(x, y, random.nextInt());
        var view = WatermarkDetectionView.create(png(original));
        assertEquals("image/jpeg", view.mimeType());
        assertTrue(view.bytes().length <= WatermarkDetectionView.MAX_BYTES);
        var result = decode(view);
        assertTrue(result.getWidth() <= WatermarkDetectionView.MAX_EDGE);
        assertTrue(result.getHeight() <= WatermarkDetectionView.MAX_EDGE);
        assertEquals(1.4, (double) result.getWidth() / result.getHeight(), .003);
    }

    @Test void tinyTransparentImageDoesNotUpscaleOrInventBlackBackground() throws Exception {
        var original = new BufferedImage(1, 2, BufferedImage.TYPE_INT_ARGB);
        var result = decode(WatermarkDetectionView.create(png(original)));
        assertEquals(1, result.getWidth()); assertEquals(2, result.getHeight());
        assertTrue(gray(result, 0, 0) > 240);
    }

    @Test void invalidAndOversizedInputFailsBeforeEncoding() {
        assertThrows(IllegalArgumentException.class, () -> WatermarkDetectionView.create(null));
        assertThrows(IllegalArgumentException.class, () -> WatermarkDetectionView.create(new byte[100]));
        assertThrows(IllegalArgumentException.class, () -> WatermarkDetectionView.create(new byte[8 * 1024 * 1024 + 1]));
    }

    private static byte[] png(BufferedImage image) throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }
    private static BufferedImage decode(WatermarkDetectionView.View view) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(view.bytes()));
    }
    private static int gray(BufferedImage image, int x, int y) { return image.getRGB(x, y) & 255; }
}
