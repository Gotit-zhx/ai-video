package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import com.yingdao.aicommerce.product.application.CommerceModelDetailStyleGuide.Region;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class CommerceModelDetailGridComposerTest {
    @Test void fourOriginalsKeepExactRowOrderWithoutLabelsOrGridLines() throws Exception {
        var colors = List.of(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW);
        var sources = colors.stream().map(color -> encoded(solid(80, 80, color), "png")).toList();
        var grid = read(CommerceModelDetailGridComposer.compose(sources));
        int cell = CommerceModelDetailGridComposer.CELL_SIZE;
        assertEquals(cell * 2, grid.getWidth()); assertEquals(cell * 2, grid.getHeight());
        for (int index = 0; index < 4; index++) {
            int x = index % 2 * cell, y = index / 2 * cell;
            assertAllPixels(grid.getSubimage(x, y, cell, cell), colors.get(index).getRGB());
        }
    }

    @Test void wideAndTallReferencesAreContainedAndCenteredWithoutStretchingOrCropping() throws Exception {
        var wide = solid(400, 200, Color.RED);
        wide.setRGB(0, 0, Color.BLACK.getRGB()); wide.setRGB(399, 199, Color.BLUE.getRGB());
        var tall = solid(200, 400, Color.GREEN);
        var grid = read(CommerceModelDetailGridComposer.compose(List.of(encoded(wide, "png"), encoded(tall, "png"))));
        int cell = CommerceModelDetailGridComposer.CELL_SIZE;
        assertEquals(Color.WHITE.getRGB(), grid.getRGB(cell / 2, cell / 4 - 1));
        assertEquals(Color.RED.getRGB(), grid.getRGB(cell / 2, cell / 4));
        assertEquals(Color.RED.getRGB(), grid.getRGB(cell / 2, cell * 3 / 4 - 1));
        assertEquals(Color.WHITE.getRGB(), grid.getRGB(cell / 2, cell * 3 / 4));
        assertEquals(Color.BLACK.getRGB(), grid.getRGB(0, cell / 4));
        assertEquals(Color.BLUE.getRGB(), grid.getRGB(cell - 1, cell * 3 / 4 - 1));
        assertEquals(Color.WHITE.getRGB(), grid.getRGB(cell + cell / 4 - 1, cell / 2));
        assertEquals(Color.GREEN.getRGB(), grid.getRGB(cell + cell / 4, cell / 2));
        assertEquals(Color.GREEN.getRGB(), grid.getRGB(cell + cell * 3 / 4 - 1, cell / 2));
        assertEquals(Color.WHITE.getRGB(), grid.getRGB(cell + cell * 3 / 4, cell / 2));
    }

    @Test void unusedSlotsRemainEntirelyWhiteForEveryPartialGroup() throws Exception {
        byte[] source = encoded(solid(10, 10, Color.RED), "png");
        int cell = CommerceModelDetailGridComposer.CELL_SIZE;
        for (int count = 1; count < 4; count++) {
            var grid = read(CommerceModelDetailGridComposer.compose(java.util.Collections.nCopies(count, source)));
            for (int index = count; index < 4; index++)
                assertAllPixels(grid.getSubimage(index % 2 * cell, index / 2 * cell, cell, cell), Color.WHITE.getRGB());
        }
    }

    @Test void transparentOriginalsAreCompositedOnWhiteAndInputBytesAreUntouched() throws Exception {
        var source = new BufferedImage(768, 768, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80ff0000);
        byte[] bytes = encoded(source, "png"), original = bytes.clone();
        var grid = read(CommerceModelDetailGridComposer.compose(List.of(bytes)));
        assertFalse(grid.getColorModel().hasAlpha());
        assertEquals(0xffff7f7f, grid.getRGB(0, 0));
        assertEquals(Color.WHITE.getRGB(), grid.getRGB(767, 767));
        assertArrayEquals(original, bytes);
    }

    @Test void jpegReferencesAreAcceptedAndBothOperationsEmitPng() throws Exception {
        byte[] reference = CommerceModelDetailGridComposer.compose(List.of(encoded(solid(16, 16, Color.RED), "jpeg")));
        assertPng(reference);
        var result = CommerceModelDetailGridComposer.split(reference, 1);
        assertPng(result.getFirst()); assertEquals(768, read(result.getFirst()).getWidth());
    }

    @Test void generatedCellsAreLosslessPixelCropsInRowOrderWithoutBoundaryLeakage() throws Exception {
        var grid = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        // Vary every channel and alpha across the seams to detect rescaling, overlap or RGB flattening.
        for (int y = 0; y < 1024; y++) for (int x = 0; x < 1024; x++)
            grid.setRGB(x, y, ((x + y) & 255) << 24 | (x & 255) << 16 | (y & 255) << 8 | ((x / 512 + y / 512 * 2) * 60));
        byte[] bytes = encoded(grid, "png"), original = bytes.clone();
        var results = CommerceModelDetailGridComposer.split(bytes, 4);
        assertEquals(4, results.size());
        for (int index = 0; index < 4; index++) {
            var crop = read(results.get(index));
            assertEquals(512, crop.getWidth()); assertEquals(512, crop.getHeight());
            assertTrue(crop.getColorModel().hasAlpha()); assertPng(results.get(index));
            int[] expected = grid.getRGB(index % 2 * 512, index / 2 * 512, 512, 512, null, 0, 512);
            assertArrayEquals(expected, crop.getRGB(0, 0, 512, 512, null, 0, 512));
        }
        assertArrayEquals(original, bytes);
        assertThrows(UnsupportedOperationException.class, () -> results.add(new byte[0]));
    }

    @Test void splittingReturnsOnlyActualCountEvenIfUnusedSlotsContainImageData() throws Exception {
        var grid = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        var colors = List.of(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW);
        var graphics = grid.createGraphics();
        try {
            for (int index = 0; index < 4; index++) {
                graphics.setColor(colors.get(index)); graphics.fillRect(index % 2 * 512, index / 2 * 512, 512, 512);
            }
        } finally { graphics.dispose(); }
        byte[] bytes = encoded(grid, "png");
        for (int count = 1; count <= 4; count++) {
            var results = CommerceModelDetailGridComposer.split(bytes, count);
            assertEquals(count, results.size());
            for (int index = 0; index < count; index++) assertAllPixels(read(results.get(index)), colors.get(index).getRGB());
        }
    }

    @Test void sourceAndActualCountsMustBeOneThroughFour() {
        byte[] source = encoded(solid(1, 1, Color.RED), "png");
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.compose(null));
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.compose(List.of()));
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.compose(java.util.Collections.nCopies(5, source)));
        for (int count : new int[]{-1, 0, 5, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.split(source, count));
    }

    @Test void missingInvalidTruncatedAndUnsupportedImagesAreRejected() {
        byte[] valid = encoded(solid(16, 16, Color.RED), "png");
        byte[] gif = encoded(solid(16, 16, Color.RED), "gif");
        for (byte[] value : Arrays.asList(null, new byte[0], new byte[16], Arrays.copyOf(valid, 33), gif)) {
            assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.compose(Arrays.asList(value)));
            assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.split(value, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.compose(Arrays.asList(valid, null)));
    }

    @Test void oversizedEncodedPayloadIsRejectedBeforeDecoding() {
        byte[] huge = new byte[32 * 1024 * 1024 + 1];
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.compose(List.of(huge)))
                .getMessage().contains("32MiB"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.split(huge, 1))
                .getMessage().contains("32MiB"));
    }

    @Test void oversizedDimensionsAndPixelCountsAreRejectedFromHeadersBeforeDecompression() {
        for (int[] dimensions : List.of(new int[]{8193, 1}, new int[]{1, 8193}, new int[]{4097, 4097},
                new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE})) {
            byte[] header = pngHeader(dimensions[0], dimensions[1]);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.compose(List.of(header)))
                    .getMessage().contains("像素"));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.split(header, 1))
                    .getMessage().contains("像素"));
        }
    }

    @Test void generatedGridRequiresSquareEvenDimensionsAndAtLeast512PixelsPerCell() {
        for (int[] dimensions : List.of(new int[]{1024, 1026}, new int[]{1025, 1025}, new int[]{1022, 1022}, new int[]{512, 512})) {
            byte[] header = pngHeader(dimensions[0], dimensions[1]);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.split(header, 1))
                    .getMessage().contains("每格至少512"));
        }
    }

    @Test void normalizationPreservesSmallValidProviderBytesButStillChecksGridDimensions() {
        byte[] png = encoded(solid(1024, 1024, Color.RED), "png");
        byte[] jpeg = encoded(solid(1024, 1024, Color.RED), "jpeg");
        assertSame(png, CommerceModelDetailGridComposer.normalizeGenerated(png));
        assertSame(jpeg, CommerceModelDetailGridComposer.normalizeGenerated(jpeg));
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.normalizeGenerated(
                encoded(solid(512, 512, Color.RED), "png")));
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.normalizeGenerated(null));
    }

    @Test void highEntropy2048GridIsCompressedToArchiveLimitWithoutReducingDimensions() throws Exception {
        var image = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(20260922L);
        int[] pixels = new int[2048 * 2048];
        for (int index = 0; index < pixels.length; index++) pixels[index] = random.nextInt(1 << 24);
        image.setRGB(0, 0, 2048, 2048, pixels, 0, 2048);
        byte[] original = encoded(image, "png");
        assertTrue(original.length > CommerceModelDetailGridComposer.ARCHIVE_MAX_BYTES);
        byte[] normalized = CommerceModelDetailGridComposer.normalizeGenerated(original);
        assertTrue(normalized.length <= CommerceModelDetailGridComposer.ARCHIVE_MAX_BYTES);
        assertEquals((byte) 0xff, normalized[0]); assertEquals((byte) 0xd8, normalized[1]);
        var decoded = read(normalized);
        assertEquals(2048, decoded.getWidth()); assertEquals(2048, decoded.getHeight());
        var cells = CommerceModelDetailGridComposer.split(normalized, 3);
        assertEquals(3, cells.size());
        for (byte[] cell : cells) {
            assertPng(cell); assertEquals(1024, read(cell).getWidth());
            assertTrue(cell.length <= CommerceModelDetailGridComposer.ARCHIVE_MAX_BYTES);
        }
        assertPng(original);
    }

    @Test void productGridRepeatsTheClearNewProductOnlyInActiveSlots() throws Exception {
        var product = solid(768, 768, Color.PINK);
        // A sharp unique print marker must remain exact in all active product cells.
        product.setRGB(517, 221, Color.BLUE.getRGB());
        byte[] bytes = encoded(product, "png");
        var grid = read(CommerceModelDetailGridComposer.productGrid(bytes, 3));
        assertEquals(1536, grid.getWidth()); assertEquals(1536, grid.getHeight());
        for (int index = 0; index < 3; index++) {
            assertEquals(Color.BLUE.getRGB(), grid.getRGB(index % 2 * 768 + 517, index / 2 * 768 + 221));
            assertEquals(Color.PINK.getRGB(), grid.getRGB(index % 2 * 768 + 516, index / 2 * 768 + 221));
        }
        assertAllPixels(grid.getSubimage(768, 768, 768, 768), Color.WHITE.getRGB());
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.productGrid(bytes, 0));
    }

    @Test void ambienceRemovesHighFrequencyOldPrintWithoutTurningSceneColorsToGray() throws Exception {
        var source = new BufferedImage(768, 768, BufferedImage.TYPE_INT_RGB);
        // Dense dark/pink print-like marks disappear into the original pink scene's coarse tone.
        int dark = 0xff301020, pink = 0xffffb0c0;
        for (int y = 0; y < 768; y++) for (int x = 0; x < 768; x++) source.setRGB(x, y, (x + y) % 2 == 0 ? dark : pink);
        byte[] bytes = encoded(source, "png"), unchanged = bytes.clone();
        var ambience = read(CommerceModelDetailGridComposer.styleAmbience(bytes));
        assertEquals(768, ambience.getWidth()); assertEquals(768, ambience.getHeight());
        int first = ambience.getRGB(0, 0);
        assertAllPixels(ambience, first);
        int red = (first >>> 16) & 255, green = (first >>> 8) & 255, blue = first & 255;
        assertTrue(red > green + 30); assertTrue(blue > green);
        assertArrayEquals(unchanged, bytes);
    }

    @Test void ambienceRetainsCoarseLayoutAndContainFramingAfterStrongLowPass() throws Exception {
        var source = solid(768, 384, Color.RED); var graphics = source.createGraphics();
        try { graphics.setColor(Color.BLUE); graphics.fillRect(384, 0, 384, 384); } finally { graphics.dispose(); }
        var ambience = read(CommerceModelDetailGridComposer.styleAmbience(encoded(source, "png")));
        assertEquals(Color.WHITE.getRGB(), ambience.getRGB(384, 0));
        assertEquals(Color.WHITE.getRGB(), ambience.getRGB(384, 767));
        int left = ambience.getRGB(96, 384), right = ambience.getRGB(672, 384);
        assertTrue(((left >>> 16) & 255) > 230 && (left & 255) < 25);
        assertTrue((right & 255) > 230 && ((right >>> 16) & 255) < 25);
        // The central border must be smooth, not a preserved sharp printed edge.
        int middle = ambience.getRGB(384, 384);
        assertTrue(((middle >>> 16) & 255) > 80 && (middle & 255) > 80);
    }

    @Test void ambienceGridKeepsOrdinalColorsAndBlankUnusedSlotWithoutChangingLegacyCompose() throws Exception {
        var originals = List.of(encoded(solid(64, 64, Color.RED), "png"), encoded(solid(64, 64, Color.GREEN), "png"), encoded(solid(64, 64, Color.BLUE), "png"));
        var grid = read(CommerceModelDetailGridComposer.styleAmbienceGrid(originals));
        assertEquals(1536, grid.getWidth()); assertEquals(1536, grid.getHeight());
        for (int index = 0; index < 4; index++) assertAllPixels(grid.getSubimage(index % 2 * 768, index / 2 * 768, 768, 768),
                List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE).get(index).getRGB());
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.styleAmbienceGrid(List.of()));
        assertThrows(IllegalArgumentException.class, () -> CommerceModelDetailGridComposer.styleAmbience(null));
        // Existing compose remains sharp: changing its implementation would mutate persisted V1 requests.
        var exact = solid(768, 768, Color.RED); exact.setRGB(5, 5, Color.BLUE.getRGB());
        assertEquals(Color.BLUE.getRGB(), read(CommerceModelDetailGridComposer.compose(List.of(encoded(exact, "png")))).getRGB(5, 5));
    }

    @Test void selectiveStyleKeepsEveryOutsidePixelAndAlphaWhileStronglySuppressingOnlyOldPrint() throws Exception {
        var source=new BufferedImage(256,192,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<192;y++)for(int x=0;x<256;x++) {
            int rgb=x>=64&&x<192&&y>=48&&y<144?((x+y)%2==0?0xff000000:0xffffffff):
                    (((x*7+y*3)&255)<<24)|((x&255)<<16)|((y&255)<<8)|((x*13+y*17)&255);
            source.setRGB(x,y,rgb);
        }
        byte[] bytes=encoded(source,"png"),unchanged=bytes.clone();
        var result=read(CommerceModelDetailGridComposer.selectiveStyle(bytes,List.of(new Region(.25,.25,.5,.5))));
        assertEquals(256,result.getWidth());assertEquals(192,result.getHeight());
        for(int y=0;y<192;y++)for(int x=0;x<256;x++) {
            assertEquals(source.getRGB(x,y)>>>24,result.getRGB(x,y)>>>24);
            if(x<64||x>=192||y<48||y>=144)assertEquals(source.getRGB(x,y),result.getRGB(x,y));
        }
        int center=result.getRGB(128,96)&255;
        assertTrue(center>110&&center<145,"Old high-frequency print should reduce to its local mean");
        int edgeChange=Math.abs((source.getRGB(64,96)&255)-(result.getRGB(64,96)&255));
        int centerChange=Math.abs((source.getRGB(128,96)&255)-center);
        assertTrue(edgeChange<centerChange/8,"The transition must blend inward inside the marked region");
        assertArrayEquals(unchanged,bytes);
    }

    @Test void selectiveStyleWithNoRegionsKeepsOriginalBytesAndDoesNotBlurWholeImage() throws Exception {
        var original=solid(79,123,Color.RED);original.setRGB(7,9,Color.BLUE.getRGB());
        byte[] bytes=encoded(original,"png");
        assertArrayEquals(bytes,CommerceModelDetailGridComposer.selectiveStyle(bytes,List.of()));
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridComposer.selectiveStyle(bytes,null));
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridComposer.selectiveStyle(bytes,Arrays.asList((Region)null)));
    }

    @Test void poseOnlyStyleKeepsGeometryAndAlphaButRemovesSourceColorWithoutChangingV3() throws Exception {
        var source=new BufferedImage(79,123,BufferedImage.TYPE_INT_ARGB);
        source.setRGB(7,9,0x80ff0000);source.setRGB(60,100,0xff0000ff);
        byte[] original=encoded(source,"png"),unchanged=original.clone();
        var pose=read(CommerceModelDetailGridComposer.poseOnlyStyle(original,List.of()));
        assertEquals(79,pose.getWidth());assertEquals(123,pose.getHeight());
        assertEquals(0x804c4c4c,pose.getRGB(7,9));assertEquals(0xff1d1d1d,pose.getRGB(60,100));
        assertArrayEquals(unchanged,original);
        assertArrayEquals(original,CommerceModelDetailGridComposer.selectiveStyle(original,List.of()));
    }

    @Test void poseOnlyReferencesKeepPrintSuppressionAndOrderedWhiteUnusedSlots() throws Exception {
        var source=solid(256,256,Color.RED);
        for(int y=64;y<192;y++)for(int x=64;x<192;x++)source.setRGB(x,y,(x+y)%2==0?Color.BLACK.getRGB():Color.WHITE.getRGB());
        byte[] original=encoded(source,"png");
        var masked=read(CommerceModelDetailGridComposer.poseOnlyStyle(original,List.of(new Region(.25,.25,.5,.5))));
        int center=masked.getRGB(128,128)&255;
        assertTrue(center>110&&center<145);assertEquals(0xff4c4c4c,masked.getRGB(0,0));
        var inputs=List.of(original,encoded(solid(256,256,Color.GREEN),"png"));
        var grid=read(CommerceModelDetailGridComposer.poseOnlyStyleGrid(inputs,List.of(List.of(new Region(.25,.25,.5,.5)),List.of())));
        assertEquals(0xff4c4c4c,grid.getRGB(0,0));assertEquals(0xff969696,grid.getRGB(768,0));
        assertAllPixels(grid.getSubimage(0,768,1536,768),Color.WHITE.getRGB());
        for(int y=0;y<grid.getHeight();y+=31)for(int x=0;x<grid.getWidth();x+=29){
            int value=grid.getRGB(x,y);assertEquals((value>>>16)&255,(value>>>8)&255);assertEquals((value>>>8)&255,value&255);
        }
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridComposer.poseOnlyStyleGrid(inputs,List.of(List.of())));
    }

    @Test void onlyAnExplicitFullFrameRegionCanReduceTheWholeMacroToLowFrequency() throws Exception {
        var source=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<256;y++)for(int x=0;x<256;x++)source.setRGB(x,y,(x+y)%2==0?Color.BLACK.getRGB():Color.WHITE.getRGB());
        var result=read(CommerceModelDetailGridComposer.selectiveStyle(encoded(source,"png"),List.of(new Region(0,0,1,1))));
        assertAllPixels(result,result.getRGB(128,128));
        assertTrue((result.getRGB(128,128)&255)>110&&(result.getRGB(128,128)&255)<145);
    }

    @Test void selectiveStyleKeepsPixelsOutsideMultipleDisjointRegionsIncludingFractionalEdges() throws Exception {
        var source=new BufferedImage(101,83,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<83;y++)for(int x=0;x<101;x++)source.setRGB(x,y,(x+y)%2==0?Color.BLACK.getRGB():Color.WHITE.getRGB());
        var regions=List.of(new Region(.105,.111,.2,.3),new Region(.65,.5,.25,.35));
        var result=read(CommerceModelDetailGridComposer.selectiveStyle(encoded(source,"png"),regions));
        int changed=0;
        for(int y=0;y<83;y++)for(int x=0;x<101;x++) {
            final double xx=(x+.5)/101,yy=(y+.5)/83;
            boolean inside=regions.stream().anyMatch(r->xx>=r.x()&&xx<r.x()+r.width()&&yy>=r.y()&&yy<r.y()+r.height());
            if(!inside)assertEquals(source.getRGB(x,y),result.getRGB(x,y));
            else if(source.getRGB(x,y)!=result.getRGB(x,y))changed++;
        }
        assertTrue(changed>100);
    }

    @Test void selectiveGridPreservesDistinctReferenceDetailsAndWhiteUnusedSlot() throws Exception {
        var originals=List.of(encoded(solid(768,768,Color.RED),"png"),encoded(solid(768,768,Color.GREEN),"png"),encoded(solid(768,768,Color.BLUE),"png"));
        var regions=List.of(List.of(new Region(.3,.3,.4,.4)),List.<Region>of(),List.of(new Region(.2,.2,.3,.3)));
        var grid=read(CommerceModelDetailGridComposer.selectiveStyleGrid(originals,regions));
        for(int slot=0;slot<4;slot++)assertAllPixels(grid.getSubimage(slot%2*768,slot/2*768,768,768),
                List.of(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE).get(slot).getRGB());
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridComposer.selectiveStyleGrid(originals,List.of()));
        assertThrows(IllegalArgumentException.class,()->CommerceModelDetailGridComposer.selectiveStyleGrid(List.of(),List.of()));
    }

    private static BufferedImage solid(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(color); graphics.fillRect(0, 0, width, height); }
        finally { graphics.dispose(); }
        return image;
    }
    private static byte[] encoded(BufferedImage image, String format) {
        try {
            var bytes = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, format, bytes)); return bytes.toByteArray();
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private static BufferedImage read(byte[] bytes) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(bytes)); assertNotNull(image); return image;
    }
    private static void assertAllPixels(BufferedImage image, int expected) {
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        for (int pixel : pixels) if (pixel != expected) { assertEquals(expected, pixel); }
    }
    private static void assertPng(byte[] bytes) {
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, Arrays.copyOf(bytes, 8));
    }
    private static byte[] pngHeader(int width, int height) {
        byte[] header = Arrays.copyOf(encoded(solid(1, 1, Color.RED), "png"), 33);
        ByteBuffer.wrap(header).putInt(16, width).putInt(20, height);
        var crc = new CRC32(); crc.update(header, 12, 17); ByteBuffer.wrap(header).putInt(29, (int) crc.getValue());
        return header;
    }
}
