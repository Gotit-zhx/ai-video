package com.yingdao.aicommerce.product.application;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommerceGarmentColorTest {
    @Test void fractionalOutlineCoverageSurvivesLightToBlackRecoloring() throws Exception {
        var blank=garment(100,100,new Color(238,226,202));
        fill(blank,19,25,1,50,new Color(247,241,229).getRGB());
        var result=CommerceGarmentColor.prepare(png(blank),png(garment(100,100,new Color(32,32,32))),"Black",false);
        var out=decode(result.png());int edge=out.getRGB(19,50);
        assertTrue(brightness(edge)>90&&brightness(edge)<210,"edge must retain partial background coverage");
        assertTrue(Math.abs((edge>>16&255)-(edge&255))<5,"no cream fringe");
        assertEquals(Color.WHITE.getRGB(),out.getRGB(18,50));
    }

    @Test void warmUnderarmShadowsAreRecoloredAlongWithTheCloth() throws Exception {
        var blank=garment(128,128,new Color(238,226,202));
        fill(blank,35,40,3,45,new Color(178,149,89).getRGB());
        var result=CommerceGarmentColor.prepare(png(blank),png(garment(100,100,new Color(32,32,31))),"Black",false);
        int p=decode(result.png()).getRGB(36,60);
        assertTrue(brightness(p)<35,"warm seam must become a dark fold");
        assertTrue(Math.abs((p>>16&255)-(p&255))<5,"no original yellow remains");
    }

    @Test void shadedWhiteKeepsMeasuredRgbRatherThanInventingPureWhite() throws Exception {
        var result = CommerceGarmentColor.prepare(png(garment(100,100,new Color(238,226,202))),
                png(garment(100,100,new Color(204,201,196))), "White/1", false);
        assertEquals("#CCC9C4",result.report().observedSourceRgb());
    }
    @Test void alphaEvidenceKeepsBrightFabricAndOnlySuppliesColor() throws Exception {
        var source=garment(100,100,new Color(245,245,245));
        for(int y=0;y<100;y++)for(int x=0;x<100;x++)if(source.getRGB(x,y)==Color.WHITE.getRGB())source.setRGB(x,y,0x00ffffff);
        var result=CommerceGarmentColor.prepare(png(garment(100,100,new Color(238,226,202))),png(source),"White",false);
        assertEquals("#F5F5F5",result.report().observedSourceRgb());
    }

    @Test void undyedWhitePatchCannotPassCoverageGate() {
        int[] original=new int[1600],output=new int[1600];boolean[] mask=new boolean[1600];
        java.util.Arrays.fill(original,0xffeeeeee);java.util.Arrays.fill(output,0xff222222);java.util.Arrays.fill(mask,true);
        assertDoesNotThrow(()->CommerceGarmentColor.verifyCoverage(original,output,mask,40,40,.95,.25));
        for(int y=10;y<18;y++)for(int x=10;x<18;x++)output[y*40+x]=0xffeeeeee;
        var error=assertThrows(CommerceGarmentColor.ColorException.class,()->CommerceGarmentColor.verifyCoverage(original,output,mask,40,40,.95,.25));
        assertEquals("COLOR_COVERAGE_INCOMPLETE",error.code());
    }

    @Test void brightSleeveEdgeIsClothAndMustBeRecolored() throws Exception {
        var blank=garment(160,160,new Color(232,233,237));
        fill(blank,47,24,4,112,new Color(245,245,245).getRGB());
        var result=CommerceGarmentColor.prepare(png(blank),png(garment(160,160,new Color(35,35,35))),"Black",false);
        var out=decode(result.png());
        assertTrue(brightness(out.getRGB(49,60))<100,"white highlight must not remain as white cloth in a black SKU");
        assertEquals(Color.WHITE.getRGB(),out.getRGB(0,0));
    }

    @Test void whiteHighlightMustNotSeverTheSleeveDuringColorSampling() throws Exception {
        var source = garment(160, 160, new Color(232, 233, 237));
        // Bright fabric joins two large regions. The old white-background key removed it.
        fill(source, 47, 24, 4, 112, new Color(245, 245, 245).getRGB());
        byte[] photo = png(source);
        assertThrows(IllegalArgumentException.class, () -> CommercePhotoMockupComposer.foregroundMask(photo),
                "production position mapping retains its stricter contour check");
        var result = CommerceGarmentColor.prepare(png(garment(120, 120, new Color(238, 226, 202))),
                photo, "PURE WHITE", false);
        assertEquals("#E8E9ED", result.report().observedSourceRgb());
        assertTrue(result.report().sourceEvidencePixels() > 7_000);
        assertEquals(Color.WHITE.getRGB(), decode(result.png()).getRGB(0, 0));
    }

    @Test void adaptiveColorSamplingStillRejectsMultipleDisconnectedGarments() throws Exception {
        var source = garment(160, 160, new Color(232, 233, 237));
        fill(source, 77, 24, 7, 112, Color.WHITE.getRGB());
        byte[] photo = png(source), blank = png(garment(100, 100, new Color(238, 226, 202)));
        var failure = assertThrows(CommerceGarmentColor.ColorException.class,
                () -> CommerceGarmentColor.prepare(blank, photo, "White/1", false));
        assertEquals("COLOR_FOREGROUND_UNCERTAIN", failure.code());
        assertTrue(failure.getMessage().contains("White/1"));
    }

    @Test void noExplicitColorReusesTemplateWithoutNeedingOrReadingASkuPicture() throws Exception {
        var image = garment(100, 100, new Color(237, 227, 200));
        byte[] original = png(image);
        var result = CommerceGarmentColor.prepare(original, new byte[] {3, 4}, "原图色", true);
        assertPixels(image, decode(result.png()));
        assertEquals(CommerceGarmentColor.Mode.TEMPLATE_ORIGINAL, result.report().mode());
        assertNull(result.report().observedSourceRgb());
        assertFalse(result.report().productionReady());
    }

    @Test void darkSkuPreservesBackgroundShadowOrderAndNestedWhiteLabelText() throws Exception {
        var blank = garment(128, 128, new Color(238, 226, 202));
        fill(blank, 40, 70, 12, 20, new Color(211, 199, 175).getRGB());
        fill(blank, 52, 30, 24, 14, Color.BLACK.getRGB());
        fill(blank, 58, 35, 12, 4, Color.WHITE.getRGB());
        var source = garment(80, 100, new Color(42, 42, 42));
        var result = CommerceGarmentColor.prepare(png(blank), png(source), "Black", false);
        var output = decode(result.png());
        assertEquals(Color.WHITE.getRGB(), output.getRGB(0, 0));
        assertEquals(Color.WHITE.getRGB(), output.getRGB(19, 65));
        assertEquals(Color.BLACK.getRGB(), output.getRGB(52, 32));
        assertEquals(Color.WHITE.getRGB(), output.getRGB(60, 36), "label whites are not part of the fabric layer");
        assertTrue(brightness(output.getRGB(80, 80)) < 65);
        assertTrue(brightness(output.getRGB(45, 75)) < brightness(output.getRGB(80, 80)), "fold remains darker than surrounding fabric");
        assertEquals("#2A2A2A", result.report().observedSourceRgb());
        assertTrue(result.report().recoloredPixels() > 3_000);
        assertFalse(result.report().productionReady());
    }

    @Test void lightSkuOnADarkTemplateKeepsFoldVariationAndHasActualImageEvidence() throws Exception {
        var blank = garment(100, 100, new Color(70, 70, 70));
        fill(blank, 35, 60, 12, 15, new Color(48, 48, 48).getRGB());
        var source = garment(100, 140, new Color(231, 231, 231));
        var result = CommerceGarmentColor.prepare(png(blank), png(source), "White", false);
        var output = decode(result.png());
        assertTrue(brightness(output.getRGB(65, 70)) > 215);
        assertTrue(brightness(output.getRGB(40, 65)) < brightness(output.getRGB(65, 70)) - 10);
        assertEquals("#E7E7E7", result.report().observedSourceRgb());
        assertTrue(result.report().sourceEvidencePixels() > 1000);
    }

    @Test void smallCenteredPrintDoesNotBecomeTheSampledFabricColor() throws Exception {
        var source = garment(140, 180, new Color(217, 139, 160));
        fill(source, 55, 75, 30, 30, Color.BLACK.getRGB());
        fill(source, 60, 80, 20, 20, Color.WHITE.getRGB());
        var result = CommerceGarmentColor.prepare(png(garment(100, 100, new Color(238, 226, 202))), png(source), "Pink", false);
        assertEquals("#D98BA0", result.report().observedSourceRgb());
        assertTrue(result.report().dominantSampleShare() > .80);
    }

    @Test void refusesLargeCentralGraphicEvenWhenItWouldDominateTheMedian() throws Exception {
        var source = garment(160, 160, new Color(230, 230, 230));
        fill(source, 39, 30, 82, 100, Color.RED.getRGB());
        byte[] sourceBytes = png(source), blank = png(garment(100, 100, new Color(230, 220, 190)));
        assertEquals("SKU_FABRIC_COLOR_UNCERTAIN", assertThrows(CommerceGarmentColor.ColorException.class,
                () -> CommerceGarmentColor.prepare(blank, sourceBytes, "", false)).code());
    }

    @Test void refusesTwoColorGarmentInsteadOfInventingAnAverageColor() throws Exception {
        var source = garment(100, 100, Color.RED);
        fill(source, 50, 15, 30, 70, Color.BLUE.getRGB());
        byte[] sourceBytes = png(source), blank = png(garment(100, 100, new Color(230, 220, 190)));
        assertEquals("SKU_FABRIC_COLOR_UNCERTAIN", assertThrows(CommerceGarmentColor.ColorException.class,
                () -> CommerceGarmentColor.prepare(blank, sourceBytes, "红蓝", false)).code());
    }

    @Test void refusesAContrastingSidePanelEvenWhenOneBaseColorDominatesTheWholeImage() throws Exception {
        var source = garment(100, 100, Color.RED);
        fill(source, 20, 15, 9, 70, Color.BLUE.getRGB());
        byte[] sourceBytes = png(source), blank = png(garment(100, 100, new Color(230, 220, 190)));
        assertEquals("SKU_FABRIC_COLOR_UNCERTAIN", assertThrows(CommerceGarmentColor.ColorException.class,
                () -> CommerceGarmentColor.prepare(blank, sourceBytes, "Red", false)).code());
    }

    @Test void refusesComplexBackgroundAndWhiteClothWithoutAVisibleBoundary() throws Exception {
        var source = garment(100, 100, Color.BLUE);
        for (int x = 0; x < 100; x++) source.setRGB(x, 0, x % 2 == 0 ? Color.WHITE.getRGB() : Color.GREEN.getRGB());
        byte[] sourceBytes = png(source), blank = png(garment(100, 100, new Color(230, 220, 190)));
        assertEquals("COLOR_BACKGROUND_COMPLEX", assertThrows(CommerceGarmentColor.ColorException.class,
                () -> CommerceGarmentColor.prepare(blank, sourceBytes, "Blue", false)).code());
        byte[] allWhite = png(garment(100, 100, Color.WHITE));
        assertEquals("COLOR_FOREGROUND_UNCERTAIN", assertThrows(CommerceGarmentColor.ColorException.class,
                () -> CommerceGarmentColor.prepare(blank, allWhite, "White", false)).code());
    }

    @Test void sparseColoredEdgeSpecksDoNotBlockAnOtherwiseCleanWhitePhoto() throws Exception {
        var blank=garment(512,512,new Color(238,238,238));
        var source=garment(512,512,new Color(32,32,32));
        for(var image:new BufferedImage[]{blank,source}) {
            // Four imperfect crop/compression pixels among 2,044 border pixels.
            for(int x:new int[]{2,120,270,500})image.setRGB(x,0,new Color(201,234,249).getRGB());
            for(int x=5;x<100;x++)image.setRGB(x,511,new Color(253,253,253).getRGB());
        }
        var result=CommerceGarmentColor.prepare(png(blank),png(source),"Black",false);
        assertEquals("#202020",result.report().observedSourceRgb());
        var out=decode(result.png());assertTrue(brightness(out.getRGB(256,256))<45);
        assertEquals(blank.getRGB(2,0),out.getRGB(2,0),"only the garment is recolored, isolated backdrop specks are not made into cloth");
    }

    @Test void mostlyWhiteButRealBorderGradientIsStillRejected() throws Exception {
        var gradient=garment(160,160,new Color(32,32,32));
        for(int y=0;y<160;y++)for(int x=0;x<160;x++)if(x==0||y==0||x==159||y==159){
            int shade=235+(int)Math.round(x*20d/159);gradient.setRGB(x,y,new Color(shade,shade,shade).getRGB());
        }
        byte[] blank=png(garment(160,160,new Color(238,238,238))),source=png(gradient);
        assertEquals("COLOR_BACKGROUND_COMPLEX",assertThrows(CommerceGarmentColor.ColorException.class,
                ()->CommerceGarmentColor.prepare(blank,source,"Black",false)).code());
        assertEquals("COLOR_BACKGROUND_COMPLEX",assertThrows(CommerceGarmentColor.ColorException.class,
                ()->CommerceGarmentColor.prepare(source,blank,"White",false)).code());
    }

    @Test void evenASingleConnectedCroppedGarmentEdgeStillFailsTheContourCheck() throws Exception {
        var clipped=garment(160,160,new Color(32,32,32));
        fill(clipped,80,0,1,25,new Color(32,32,32).getRGB());
        byte[] blank=png(garment(160,160,new Color(238,238,238))),source=png(clipped);
        assertEquals("COLOR_FOREGROUND_UNCERTAIN",assertThrows(CommerceGarmentColor.ColorException.class,
                ()->CommerceGarmentColor.prepare(blank,source,"Black",false)).code());
    }

    @Test void clearOutlineMakesWhiteFabricUsableWithoutTreatingTheWholeBackgroundAsCloth() throws Exception {
        var source = garment(100, 100, new Color(120, 120, 120));
        fill(source, 22, 17, 56, 66, new Color(245, 245, 245).getRGB());
        var result = CommerceGarmentColor.prepare(png(garment(100, 100, new Color(230, 220, 190))), png(source), "White", false);
        assertEquals("#F5F5F5", result.report().observedSourceRgb());
        assertTrue(result.report().sourceEvidencePixels() < 5_000, "white background must never count as color evidence");
    }

    @Test void imageAndKnownColorLabelMustAgreeButTheLabelNeverSuppliesAHex() throws Exception {
        byte[] source = png(garment(100, 100, new Color(30, 70, 170))), blank = png(garment(100, 100, new Color(230, 220, 190)));
        assertEquals("SKU_COLOR_LABEL_MISMATCH", assertThrows(CommerceGarmentColor.ColorException.class,
                () -> CommerceGarmentColor.prepare(blank, source, "红色", false)).code());
        var blue = CommerceGarmentColor.prepare(blank, source, "Navy Blue", false);
        var unknown = CommerceGarmentColor.prepare(blank, source, "SKU 04", false);
        assertEquals("#1E46AA", blue.report().observedSourceRgb());
        assertArrayEquals(blue.png(), unknown.png());
    }

    @Test void requiresColorEvidenceAndDoesNotMutateInputOrReturnedBytes() throws Exception {
        byte[] source = png(garment(100, 100, Color.BLUE)), blank = png(garment(100, 100, new Color(230, 220, 190)));
        var sourceCopy = source.clone(); var blankCopy = blank.clone();
        assertEquals("SKU_COLOR_IMAGE_REQUIRED", assertThrows(CommerceGarmentColor.ColorException.class,
                () -> CommerceGarmentColor.prepare(blank, null, "Blue", false)).code());
        var result = CommerceGarmentColor.prepare(blank, source, "Blue", false);
        var second = CommerceGarmentColor.prepare(blank, source, "Blue", false);
        assertArrayEquals(sourceCopy, source); assertArrayEquals(blankCopy, blank);
        assertArrayEquals(result.png(), second.png());
        byte[] returned = result.png(); Arrays.fill(returned, (byte) 0);
        assertArrayEquals(result.png(), second.png());
    }

    private static BufferedImage garment(int width, int height, Color cloth) {
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fill(result, 0, 0, width, height, Color.WHITE.getRGB());
        fill(result, width / 5, height * 15 / 100, width * 3 / 5, height * 7 / 10, cloth.getRGB());
        return result;
    }
    private static void fill(BufferedImage image, int x, int y, int width, int height, int argb) {
        for (int yy = y; yy < y + height; yy++) for (int xx = x; xx < x + width; xx++) image.setRGB(xx, yy, argb);
    }
    private static byte[] png(BufferedImage image) throws Exception {
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
    private static BufferedImage decode(byte[] bytes) throws Exception { return ImageIO.read(new ByteArrayInputStream(bytes)); }
    private static int brightness(int rgb) { return ((rgb >>> 16 & 255) + (rgb >>> 8 & 255) + (rgb & 255)) / 3; }
    private static void assertPixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth()); assertEquals(expected.getHeight(), actual.getHeight());
        assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
    }
}
