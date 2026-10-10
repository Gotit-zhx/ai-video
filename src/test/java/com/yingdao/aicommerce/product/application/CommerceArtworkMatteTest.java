package com.yingdao.aicommerce.product.application;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommerceArtworkMatteTest {
    @Test void removesOnlyConnectedCanvasAndKeepsWhiteCharacterAndInteriorWhite() throws Exception {
        var image = opaque(80, 80, Color.WHITE);
        fill(image, 12, 12, 56, 56, Color.BLACK.getRGB());
        fill(image, 15, 15, 50, 50, Color.WHITE.getRGB());
        fill(image, 25, 30, 5, 5, Color.BLACK.getRGB());
        fill(image, 50, 30, 5, 5, Color.BLACK.getRGB());
        fill(image, 27, 50, 26, 4, Color.RED.getRGB());
        var result = CommerceArtworkMatte.prepare(png(image));
        var output = decode(result.png());
        assertEquals(0, output.getRGB(0, 0) >>> 24);
        assertEquals(0, output.getRGB(79, 79) >>> 24);
        assertEquals(Color.WHITE.getRGB(), output.getRGB(35, 35), "white character must remain opaque white");
        assertEquals(Color.RED.getRGB(), output.getRGB(30, 51));
        assertEquals(Color.BLACK.getRGB(), output.getRGB(12, 40));
        assertEquals(CommerceArtworkMatte.Mode.EDGE_CONNECTED_WHITE, result.report().mode());
        assertEquals(new CommerceArtworkMatte.Bounds(12, 12, 56, 56), result.report().foregroundBounds());
        assertEquals(80 * 80 - 56 * 56, result.report().backgroundRemovedPixels());
        assertFalse(result.report().warnings().isEmpty(), "unidentifiable unoutlined white art limitation is reported");
    }

    @Test void leavesAnAlreadyTransparentMasterPixelExactIncludingWhiteAndPartialAlpha() throws Exception {
        var image = new BufferedImage(48, 32, BufferedImage.TYPE_INT_ARGB);
        fill(image, 8, 5, 28, 20, Color.WHITE.getRGB());
        fill(image, 10, 7, 8, 8, 0x70452311);
        image.setRGB(1, 1, 0x00223344);
        byte[] input = png(image);
        var result = CommerceArtworkMatte.prepare(input);
        var expected = decode(input);
        var actual = decode(result.png());
        assertArrayEquals(expected.getRGB(0, 0, 48, 32, null, 0, 48), actual.getRGB(0, 0, 48, 32, null, 0, 48));
        assertEquals(CommerceArtworkMatte.Mode.EXISTING_ALPHA, result.report().mode());
        assertEquals(0, result.report().backgroundRemovedPixels());
        assertEquals(0, result.report().edgeAdjustedPixels());
    }

    @Test void oneTransparentCornerCannotMakeAnOpaqueWhiteCanvasPassAsATransparentMaster() throws Exception {
        var image = opaque(80, 80, Color.WHITE);
        fill(image, 15, 15, 50, 50, Color.BLACK.getRGB());
        fill(image, 18, 18, 44, 44, Color.WHITE.getRGB());
        image.setRGB(0, 0, 0x00112233);
        var result = CommerceArtworkMatte.prepare(png(image));
        var output = decode(result.png());
        assertEquals(CommerceArtworkMatte.Mode.EDGE_CONNECTED_WHITE, result.report().mode());
        assertEquals(0, output.getRGB(1, 1) >>> 24);
        assertEquals(0, output.getRGB(79, 79) >>> 24);
        assertEquals(Color.WHITE.getRGB(), output.getRGB(35, 35), "the intentionally white character remains white");
        assertEquals(Color.BLACK.getRGB(), output.getRGB(15, 30));
    }

    @Test void almostOpaqueAlphaCanvasStillGetsItsWhiteBackgroundSeparated() throws Exception {
        var image = opaque(64, 64, Color.WHITE);
        fill(image, 0, 0, 64, 64, 0xfeffffff);
        fill(image, 12, 12, 40, 40, 0xfe000000);
        fill(image, 15, 15, 34, 34, 0xfeffffff);
        var result = CommerceArtworkMatte.prepare(png(image));
        var output = decode(result.png());
        assertEquals(CommerceArtworkMatte.Mode.EDGE_CONNECTED_WHITE, result.report().mode());
        assertEquals(0, output.getRGB(0, 0) >>> 24);
        assertEquals(0xfeffffff, output.getRGB(30, 30));
        assertEquals(0xfe000000, output.getRGB(12, 30));
    }

    @Test void aThinTransparentOutlineDoesNotProveThatTheRemainingWhiteRectangleIsArtwork() throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(image, 1, 1, 62, 62, Color.WHITE.getRGB());
        fill(image, 20, 20, 24, 24, Color.RED.getRGB());
        byte[] original = png(image), unchanged = original.clone();
        assertEquals("ALPHA_CANVAS_UNCERTAIN", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(original)).code());
        assertArrayEquals(unchanged, original, "uncertain intentional white areas must not be deleted");
    }

    @Test void genuineSemitransparentWhiteAndColoredArtworkOnClearCanvasRemainsPixelExact() throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(image, 10, 10, 44, 44, 0x80ffffff);
        fill(image, 20, 20, 16, 16, 0x40772d8a);
        fill(image, 8, 16, 2, 28, 0x20ffffff);
        byte[] original = png(image);
        var result = CommerceArtworkMatte.prepare(original);
        var output = decode(result.png());
        assertEquals(CommerceArtworkMatte.Mode.EXISTING_ALPHA, result.report().mode());
        assertArrayEquals(image.getRGB(0, 0, 64, 64, null, 0, 64), output.getRGB(0, 0, 64, 64, null, 0, 64));
    }

    @Test void partialAlphaWithoutClearCanvasIsRefusedInsteadOfErasingPossiblyIntentionalWhite() throws Exception {
        var image = opaque(64, 64, Color.WHITE);
        fill(image, 15, 15, 30, 30, Color.BLUE.getRGB());
        image.setRGB(0, 0, 0x80ffffff);
        byte[] original = png(image);
        assertEquals("ALPHA_CANVAS_UNCERTAIN", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(original)).code());
    }

    @Test void aSparseTransparentCutInTheDesignMustNotLeakBackgroundRemovalIntoItsWhiteCharacter() throws Exception {
        var image = opaque(64, 64, Color.WHITE);
        fill(image, 12, 12, 40, 40, Color.BLACK.getRGB());
        fill(image, 14, 14, 36, 36, Color.WHITE.getRGB());
        image.setRGB(12, 30, 0);
        image.setRGB(13, 30, 0);
        byte[] original = png(image), copy = original.clone();
        assertEquals("ALPHA_CANVAS_UNCERTAIN", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(original)).code());
        assertArrayEquals(copy, original);
    }

    @Test void repeatUseOfSharedMasterIsDeterministicAndNeverMutatesTheOriginalOrResult() throws Exception {
        var image = opaque(64, 64, Color.WHITE);
        fill(image, 10, 10, 44, 44, Color.BLUE.getRGB());
        byte[] original = png(image), preserved = original.clone();
        var first = CommerceArtworkMatte.prepare(original);
        var second = CommerceArtworkMatte.prepare(original);
        assertArrayEquals(preserved, original);
        assertArrayEquals(first.png(), second.png());
        assertEquals(first.report(), second.report());
        byte[] read = first.png();
        Arrays.fill(read, (byte) 0);
        assertArrayEquals(second.png(), first.png(), "caller cannot corrupt cached shared result");
        assertThrows(UnsupportedOperationException.class, () -> first.report().warnings().add("modified"));
    }

    @Test void rejectsAllWhiteInsteadOfReturningAnEmptySuccessfulMaster() throws Exception {
        byte[] bytes = png(opaque(32, 32, Color.WHITE));
        var failure = assertThrows(CommerceArtworkMatte.MatteException.class, () -> CommerceArtworkMatte.prepare(bytes));
        assertEquals("NO_DISTINCT_ARTWORK", failure.code());
        assertTrue(failure.getMessage().contains("印花"));
    }

    @Test void rejectsCompletelyTransparentInput() throws Exception {
        byte[] bytes = png(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
        assertEquals("EMPTY_ARTWORK", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(bytes)).code());
    }

    @Test void rejectsOpaqueArtworkThatTouchesTheCanvasEdge() throws Exception {
        var image = opaque(80, 80, Color.WHITE);
        fill(image, 0, 30, 35, 10, Color.BLACK.getRGB());
        byte[] bytes = png(image);
        assertEquals("ARTWORK_TOUCHES_EDGE", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(bytes)).code());
    }

    @Test void existingAlphaTouchingEdgeIsPreservedWithAnExplicitCropWarning() throws Exception {
        var image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        fill(image, 0, 8, 16, 8, Color.WHITE.getRGB());
        var result = CommerceArtworkMatte.prepare(png(image));
        assertEquals(Color.WHITE.getRGB(), decode(result.png()).getRGB(0, 10));
        assertTrue(result.report().warnings().getFirst().contains("裁切"));
    }

    @Test void refusesGradientBackgroundInsteadOfPunchingOutPaleArtwork() throws Exception {
        var image = opaque(80, 80, Color.WHITE);
        for (int x = 0; x < 80; x++) {
            int gray = 245 + (10 * x / 79);
            fill(image, x, 0, 1, 80, new Color(gray, gray, gray).getRGB());
        }
        fill(image, 20, 20, 40, 40, Color.RED.getRGB());
        byte[] bytes = png(image);
        assertEquals("BACKGROUND_NOT_UNIFORM", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(bytes)).code());
    }

    @Test void convertsAntialiasedWhiteFringeIntoAlphaAndKeepsItsRecompositedAppearance() throws Exception {
        var image = opaque(64, 64, Color.WHITE);
        fill(image, 12, 12, 40, 40, Color.BLACK.getRGB());
        fill(image, 10, 14, 1, 36, new Color(220, 220, 220).getRGB());
        fill(image, 11, 14, 1, 36, new Color(120, 120, 120).getRGB());
        var result = CommerceArtworkMatte.prepare(png(image));
        var output = decode(result.png());
        int outer = output.getRGB(10, 30), inner = output.getRGB(11, 30);
        assertEquals(35, outer >>> 24, 1);
        assertEquals(135, inner >>> 24, 1);
        assertEquals(0, outer & 0xffffff, "the faint edge should contain black ink, not white RGB contamination");
        assertEquals(0, inner & 0xffffff);
        assertEquals(220, overWhite(outer), 1);
        assertEquals(120, overWhite(inner), 1);
        assertEquals(Color.BLACK.getRGB(), output.getRGB(20, 30));
        assertTrue(result.report().edgeAdjustedPixels() >= 60);
    }

    @Test void doesNotDeleteEnclosedPaleOrWhiteDetailsBesideTheDarkOutline() throws Exception {
        var image = opaque(64, 64, Color.WHITE);
        fill(image, 10, 10, 44, 44, Color.BLACK.getRGB());
        fill(image, 13, 13, 38, 38, new Color(253, 253, 253).getRGB());
        fill(image, 18, 18, 8, 8, Color.WHITE.getRGB());
        var output = decode(CommerceArtworkMatte.prepare(png(image)).png());
        assertEquals(0xfffdfdfd, output.getRGB(13, 30));
        assertEquals(Color.WHITE.getRGB(), output.getRGB(20, 20));
    }

    @Test void confirmedCanvasGapIsRemovedWhileIdenticallyWhiteLetteringStaysOpaque() throws Exception {
        var image = opaque(100, 80, Color.WHITE);
        fill(image, 12, 12, 76, 56, 0xff482d18);
        fill(image, 24, 25, 12, 25, 0xffffffff);
        fill(image, 58, 24, 4, 28, 0xffffffff);
        fill(image, 58, 48, 15, 4, 0xffffffff); // Authored white L, same color as canvas.
        byte[] original = png(image), copy = original.clone();
        var ordinary = decode(CommerceArtworkMatte.prepare(original).png());
        var selected = decode(CommerceArtworkMatte.prepare(original,
                List.of(new CommerceArtworkMatte.BackgroundSeed(29, 34))).png());
        assertEquals(0xffffffff, ordinary.getRGB(29, 34));
        assertEquals(0, selected.getRGB(29, 34) >>> 24);
        for (int y = 22; y < 55; y++) for (int x = 56; x < 76; x++)
            assertEquals(ordinary.getRGB(x, y), selected.getRGB(x, y), "White lettering must remain pixel exact");
        assertArrayEquals(copy, original);
    }

    @Test void confirmedGapReceivesTheSameWhiteEdgeDecontaminationAsOuterCanvas() throws Exception {
        var image = opaque(80, 80, Color.WHITE);
        fill(image, 10, 10, 60, 60, 0xff000000);
        fill(image, 24, 24, 32, 32, 0xffbbbbbb);
        fill(image, 25, 25, 30, 30, 0xffffffff);
        var output = decode(CommerceArtworkMatte.prepare(png(image),
                List.of(new CommerceArtworkMatte.BackgroundSeed(40, 40))).png());
        int edge = output.getRGB(24, 40);
        assertEquals(0, output.getRGB(40, 40) >>> 24);
        assertTrue((edge >>> 24) > 0 && (edge >>> 24) < 255);
        assertEquals(0, edge & 0xffffff);
        assertEquals(187, overWhite(edge), 1);
    }

    @Test void diagonalCanvasPassageDoesNotLeaveAnOpaqueWhiteCurlPocket() throws Exception {
        var image = opaque(64, 64, Color.WHITE);
        fill(image, 12, 12, 40, 40, 0xff000000);
        fill(image, 20, 20, 10, 10, 0xffffffff);
        for (int p = 12; p < 20; p++) image.setRGB(p, p, 0xfffafafa);
        var output = decode(CommerceArtworkMatte.prepare(png(image)).png());
        assertEquals(0, output.getRGB(25, 25) >>> 24);
        assertEquals(0xff000000, output.getRGB(35, 35));
    }

    @Test void generatedResolutionUsesAnEdgeSearchWideEnoughForAResizedWhiteMatte() throws Exception {
        var image = opaque(1024, 1024, Color.WHITE);
        fill(image, 204, 200, 500, 600, 0xff000000);
        for (int x = 200; x < 204; x++)
            fill(image, x, 205, 1, 590, new Color(240 - (x - 200) * 50, 240 - (x - 200) * 50, 240 - (x - 200) * 50).getRGB());
        var output = decode(CommerceArtworkMatte.prepare(png(image)).png());
        for (int x = 200; x < 204; x++) {
            int edge = output.getRGB(x, 400);
            assertTrue((edge >>> 24) < 255);
            assertEquals(0, edge & 0xffffff);
            assertEquals(240 - (x - 200) * 50, overWhite(edge), 1);
        }
        assertEquals(0xff000000, output.getRGB(210, 400));
    }

    @Test void backgroundSeedsCannotEraseColoredInkOrOverrideAuthoredTransparency() throws Exception {
        var image = opaque(64, 64, Color.WHITE);
        fill(image, 12, 12, 40, 40, Color.RED.getRGB());
        var bytes = png(image);
        for (var seed : List.of(new CommerceArtworkMatte.BackgroundSeed(20, 20),
                new CommerceArtworkMatte.BackgroundSeed(-1, 2), new CommerceArtworkMatte.BackgroundSeed(64, 20))) {
            assertEquals("INVALID_CANVAS_GAP", assertThrows(CommerceArtworkMatte.MatteException.class,
                    () -> CommerceArtworkMatte.prepare(bytes, List.of(seed))).code());
        }
        var transparent = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(transparent, 12, 12, 40, 40, Color.WHITE.getRGB());
        var alpha = png(transparent);
        assertEquals("EXISTING_ALPHA_GAPS", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(alpha, List.of(new CommerceArtworkMatte.BackgroundSeed(20, 20)))).code());
        assertEquals("INVALID_CANVAS_GAPS", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(bytes, null)).code());
    }

    @Test void rejectsOversizeAndInvalidInputsBeforeAllocatingFullDecodedArtwork() throws Exception {
        assertEquals("INVALID_IMAGE_SIZE", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(new byte[8 * 1024 * 1024 + 1])).code());
        assertEquals("INVALID_IMAGE", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(new byte[] {1, 2, 3})).code());
        byte[] tooWide = png(opaque(4097, 2, Color.WHITE));
        assertEquals("INVALID_IMAGE_DIMENSIONS", assertThrows(CommerceArtworkMatte.MatteException.class,
                () -> CommerceArtworkMatte.prepare(tooWide)).code());
    }

    private static BufferedImage opaque(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fill(image, 0, 0, width, height, color.getRGB());
        return image;
    }
    private static void fill(BufferedImage image, int x, int y, int width, int height, int argb) {
        for (int yy = y; yy < y + height; yy++) for (int xx = x; xx < x + width; xx++) image.setRGB(xx, yy, argb);
    }
    private static byte[] png(BufferedImage image) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
    private static BufferedImage decode(byte[] bytes) throws Exception { return ImageIO.read(new ByteArrayInputStream(bytes)); }
    private static int overWhite(int argb) { return Math.round(((argb >>> 24) / 255f) * ((argb >>> 16) & 255) + (1 - (argb >>> 24) / 255f) * 255); }
}
