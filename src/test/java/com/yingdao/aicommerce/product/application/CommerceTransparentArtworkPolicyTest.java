package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommerceTransparentArtworkPolicyTest {
    private static final String FAILURE = "模型未返回可用的原生透明印花，已保留原图，未继续套版";

    @Test void nativeWhiteArtworkAndPartialAlphaAreAcceptedWithoutChangingReceivedBytes() throws Exception {
        var image = canvas(80, 64, 0x00fdfdfd);
        fill(image, 20, 12, 40, 40, 0xffffffff);
        fill(image, 28, 20, 14, 15, 0x70452218);
        byte[] original = png(image), copy = original.clone();
        assertDoesNotThrow(() -> CommerceTransparentArtworkPolicy.requireNative(original));
        assertArrayEquals(copy, original, "Validation must never erase white ink or modify authored alpha");
        assertEquals(0xffffffff, image.getRGB(20, 20));
        assertEquals(0x70452218, image.getRGB(30, 25));
    }

    @Test void anEnclosedWhiteDesignIsNotSemanticallyClassifiedAsAnUnremovedBackground() throws Exception {
        var image = canvas(80, 80, 0);
        fill(image, 16, 16, 48, 48, 0xff222222);
        fill(image, 20, 20, 40, 40, 0xffffffff);
        byte[] original = png(image), copy = original.clone();
        assertDoesNotThrow(() -> CommerceTransparentArtworkPolicy.requireNative(original));
        assertArrayEquals(copy, original);
    }

    @Test void opaqueArgbAndRgbPngCannotBeMistakenForNativeTransparency() throws Exception {
        var argb = canvas(64, 64, 0xffffffff);
        fill(argb, 14, 14, 36, 36, 0xffb53322);
        rejected(png(argb));
        var rgb = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        fill(rgb, 0, 0, 64, 64, 0xffffffff);
        fill(rgb, 14, 14, 36, 36, 0xffb53322);
        rejected(png(rgb));
    }

    @Test void jpegIsRejectedRegardlessOfHavingAnApparentlyCleanWhiteBackground() throws Exception {
        var rgb = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        fill(rgb, 0, 0, 64, 64, 0xffffffff);
        fill(rgb, 12, 12, 40, 40, 0xff000000);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(rgb, "jpeg", bytes));
        rejected(bytes.toByteArray());
    }

    @Test void isolatedTransparentCornerAndInteriorPixelsDoNotSatisfyTheGate() throws Exception {
        for (int coordinate : new int[] {0, 30}) {
            var image = canvas(64, 64, 0xffffffff);
            fill(image, 12, 12, 40, 40, 0xff000000);
            image.setRGB(coordinate, coordinate, 0);
            byte[] bytes = png(image);
            assertTrue(CommerceArtworkTransparency.inspect(bytes).verified(), "The factual probe alone is deliberately insufficient");
            rejected(bytes);
        }
    }

    @Test void aThinTransparentFrameAroundAnOpaqueWhiteCanvasIsRejected() throws Exception {
        var image = canvas(80, 80, 0);
        fill(image, 1, 1, 78, 78, 0xffffffff);
        fill(image, 25, 25, 30, 30, 0xff446622);
        rejected(png(image));
    }

    @Test void substantialTransparencyAwayFromTheCanvasEdgesIsNotEnough() throws Exception {
        var image = canvas(80, 80, 0xffffffff);
        fill(image, 10, 10, 60, 60, 0);
        fill(image, 25, 25, 30, 30, 0xff445599);
        rejected(png(image));
    }

    @Test void entirelyTransparentOrAlmostInvisibleArtworkIsRejected() throws Exception {
        rejected(png(canvas(64, 64, 0)));
        rejected(png(canvas(64, 64, 0x08ffffff)));
        var dot = canvas(64, 64, 0);
        dot.setRGB(32, 32, 0xff112233);
        rejected(png(dot));
    }

    @Test void mostlyOpaqueAlphaCannotPretendThatTheWhiteCanvasHasBeenRemoved() throws Exception {
        var image = canvas(64, 64, 0xfeffffff);
        fill(image, 12, 12, 40, 40, 0xff000000);
        image.setRGB(0, 0, 0);
        rejected(png(image));
    }

    @Test void invalidAndOversizedInputAlwaysUsesTheActionableStopMessage() throws Exception {
        rejected(null);
        rejected(new byte[0]);
        rejected(new byte[] {1, 2, 3});
        rejected(new byte[8 * 1024 * 1024 + 1]);
        rejected(Arrays.copyOf(png(canvas(64, 64, 0xffffffff)), 24));
    }

    private static void rejected(byte[] original) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> CommerceTransparentArtworkPolicy.requireNative(original));
        assertTrue(error.getMessage().startsWith(FAILURE));
    }
    private static BufferedImage canvas(int width, int height, int color) {
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fill(result, 0, 0, width, height, color);
        return result;
    }
    private static void fill(BufferedImage image, int x, int y, int width, int height, int color) {
        for (int yy = y; yy < y + height; yy++) for (int xx = x; xx < x + width; xx++) image.setRGB(xx, yy, color);
    }
    private static byte[] png(BufferedImage image) throws Exception {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }
}
