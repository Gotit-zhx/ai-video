package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommercePhotoMockupComposerTest {
    @Test void fourPhotoLayoutsUseExactPixelsForEverySideWithoutSegmentation() throws Exception {
        // All-white cloth cannot be inferred from this photo, but explicit photo positions need no inference.
        var photo=canvas(100,80,0xffffffff);var uv=canvas(100,80,0);uv(uv,12,18,32,30,1);
        var art=canvas(10,10,0);rectangle(art,2,2,6,6,0xffe01040);
        for(String side:List.of("FRONT","BACK","LEFT","RIGHT")) {
            var result=CommercePhotoMockupComposer.composePhoto(side,png(photo),png(photo),png(photo),png(uv),List.of("p"),Map.of("p",png(art)));
            var output=CommerceMockupComposer.decode(result.png());
            assertNotEquals(photo.getRGB(28,33),output.getRGB(28,33),side);
            assertEquals(photo.getRGB(13,19),output.getRGB(13,19),"Transparent artwork retains the photograph");
            for(int y=0;y<80;y++)for(int x=0;x<100;x++)if((uv.getRGB(x,y)>>>24)==0)assertEquals(photo.getRGB(x,y),output.getRGB(x,y),side);
            assertEquals(CommercePhotoMockupComposer.PHOTO_VERSION,result.report().method());assertEquals(0,result.report().protectedChangedPixels());
        }
        assertThrows(IllegalArgumentException.class,()->CommercePhotoMockupComposer.compose("LEFT",png(photo),png(photo),png(uv),List.of("p"),Map.of("p",png(art))),"Old 3D side restrictions remain enforced");
    }

    @Test void photoMappingRejectsMismatchedBlankMissingArtAndInvisiblePrints() throws Exception {
        var photo=canvas(80,80,0xffaaaaaa);var blank=canvas(80,80,0xffaaaaaa);blank.setRGB(1,1,0xffaaaaab);
        var uv=canvas(80,80,0);uv(uv,20,20,20,20,1);var art=png(canvas(8,8,0xffff0000));
        assertThrows(IllegalArgumentException.class,()->CommercePhotoMockupComposer.composePhoto("FRONT",png(photo),png(photo),png(blank),png(uv),List.of("p"),Map.of("p",art)));
        assertThrows(IllegalArgumentException.class,()->CommercePhotoMockupComposer.composePhoto("FRONT",png(photo),png(photo),png(photo),png(uv),List.of("p"),Map.of()));
        assertThrows(IllegalArgumentException.class,()->CommercePhotoMockupComposer.composePhoto("FRONT",png(photo),png(photo),png(photo),png(uv),List.of("p"),Map.of("p",png(canvas(8,8,0)))));
        assertThrows(IllegalArgumentException.class,()->CommercePhotoMockupComposer.composePhoto("FRONT",png(photo),png(photo),png(photo),png(canvas(79,80,0)),List.of("p"),Map.of("p",art)));
    }

    @Test void photoFrontWithBackOnlyPrintRemainsUnchangedAndDoesNotInventFrontArt() throws Exception {
        var photo=canvas(100,80,0xffc8c5c0);var uv=canvas(100,80,0);
        var result=CommercePhotoMockupComposer.composePhoto("FRONT",png(photo),png(photo),png(photo),png(uv),List.of("back"),Map.of());
        assertArrayEquals(png(photo),result.png());assertTrue(result.report().visiblePrintIds().isEmpty());assertEquals(0,result.report().changedPixels());
    }

    @Test void colorDerivativeKeepsFixedGeometryEvenWhenClothMatchesWhiteBackground() throws Exception {
        var geometry=canvas(100,100,0xffffffff);rectangle(geometry,10,10,80,80,0xff999999);
        var white=canvas(100,100,0xffffffff);
        var mapping=canvas(100,100,0);uv(mapping,35,30,25,25,1);
        var result=CommercePhotoMockupComposer.composeRecolored("FRONT",png(white),png(geometry),png(geometry),png(mapping),
                List.of("chest"),Map.of("chest",png(canvas(8,8,0xffaa0000))));
        var image=CommerceMockupComposer.decode(result.png());
        assertNotEquals(white.getRGB(45,40),image.getRGB(45,40));
        assertEquals(white.getRGB(5,5),image.getRGB(5,5));
        assertEquals(0,result.report().protectedChangedPixels());
        assertEquals(0,result.report().rejectedMappingRatio());
        assertThrows(IllegalArgumentException.class,()->CommercePhotoMockupComposer.composeRecolored("FRONT",png(canvas(90,100,0xffffffff)),png(geometry),png(geometry),png(mapping),List.of("chest"),Map.of("chest",png(canvas(8,8,0xffaa0000)))));
    }

    @Test void keepsNarrowDecalsAndHollowLogosVisible() throws Exception {
        var photo=canvas(100,100,0xffffffff);rectangle(photo,10,10,80,80,0xff999999);
        var uv=canvas(100,100,0);uv(uv,20,20,2,65,1);uv(uv,40,30,30,30,2);
        var hollow=canvas(10,10,0);rectangle(hollow,0,0,10,1,0xff00ff00);rectangle(hollow,0,9,10,1,0xff00ff00);
        rectangle(hollow,0,0,1,10,0xff00ff00);rectangle(hollow,9,0,1,10,0xff00ff00);
        var result=CommercePhotoMockupComposer.compose(png(photo),png(photo),png(uv),List.of("stripe","frame"),Map.of("stripe",png(canvas(4,4,0xffff0000)),"frame",png(hollow)));
        var out=CommerceMockupComposer.decode(result.png());
        assertNotEquals(photo.getRGB(20,50),out.getRGB(20,50));
        assertNotEquals(photo.getRGB(40,45),out.getRGB(40,45));
        assertEquals(photo.getRGB(55,45),out.getRGB(55,45));
    }

    @Test void sparseTangentBackFringeDoesNotRequireInvisibleInkButRealNarrowPrintDoes() {
        assertFalse(CommercePhotoMockupComposer.requiresVisibleInk(34.78,540,false,254420));
        assertTrue(CommercePhotoMockupComposer.requiresVisibleInk(160,160,false,254420), "Opaque narrow sleeve print remains mandatory");
        assertTrue(CommercePhotoMockupComposer.requiresVisibleInk(34.78,540,true,254420), "Stable hollow logo remains mandatory");
        assertTrue(CommercePhotoMockupComposer.requiresVisibleInk(34.78,540,false,540), "Only small print cannot be classified relative to an unrelated large slot");
    }
    @Test void uvCoverageAndArtworkAlphaUseTheSameVisibilityThreshold() throws Exception {
        var photo=canvas(100,100,0xffffffff);rectangle(photo,10,10,80,80,0xff999999);
        var uv=canvas(100,100,0);uv(uv,30,30,30,30,1);
        for(int y=35;y<40;y++)uv.setRGB(80,y,0x01008002);
        var result=CommercePhotoMockupComposer.compose(png(photo),png(photo),png(uv),List.of("front","fringe"),
            Map.of("front",png(canvas(8,8,0xffff0000)),"fringe",png(canvas(8,8,0x05dd2255))));
        var out=CommerceMockupComposer.decode(result.png());
        assertNotEquals(photo.getRGB(40,40),out.getRGB(40,40));
        assertEquals(photo.getRGB(80,37),out.getRGB(80,37));
    }

    @Test void translatesAndScalesUvToPhotographInsteadOfCopyingModelPixels() throws Exception {
        var model = canvas(100, 100, 0xffeeeeee); rectangle(model, 20, 10, 60, 80, 0xffaaaaaa);
        var photo = canvas(140, 120, 0xffffffff); rectangle(photo, 50, 20, 60, 80, 0xffddd0ba);
        var uv = canvas(100, 100, 0); uv(uv, 35, 35, 24, 24, 1);
        var result = CommercePhotoMockupComposer.compose(png(photo), png(model), png(uv), List.of("chest"),
                Map.of("chest", png(canvas(12, 12, 0xffff0000))));
        var image = CommerceMockupComposer.decode(result.png());
        assertEquals(140, image.getWidth()); assertEquals(120, image.getHeight());
        assertEquals(photo.getRGB(40, 40), image.getRGB(40, 40), "The old model coordinate must not receive ink");
        assertNotEquals(photo.getRGB(72, 50), image.getRGB(72, 50), "Ink follows the translated photo garment");
        for (int y = 0; y < 120; y++) for (int x = 0; x < 140; x++)
            if (x < 64 || x > 90 || y < 44 || y > 72) assertEquals(photo.getRGB(x, y), image.getRGB(x, y));
        assertEquals(0, result.report().protectedChangedPixels());
        assertEquals(Set.of("chest"), result.report().visiblePrintIds());
        assertFalse(result.report().productionReady());
        assertTrue(result.report().changedPixels() > 300);
    }

    @Test void preservesWhiteInkAndClothShadingOnDarkGarment() throws Exception {
        var photo = canvas(100, 100, 0xffffffff); rectangle(photo, 20, 10, 60, 80, 0xff404040);
        rectangle(photo, 42, 10, 6, 80, 0xff202020);
        var uv = canvas(100, 100, 0); uv(uv, 30, 30, 40, 40, 1);
        var result = CommercePhotoMockupComposer.compose(png(photo), png(photo), png(uv), List.of("p"),
                Map.of("p", png(canvas(8, 8, 0xffffffff))));
        var image = CommerceMockupComposer.decode(result.png());
        int bright = image.getRGB(35, 45) & 255, folded = image.getRGB(45, 45) & 255;
        assertTrue(bright > 220, "White ink cannot disappear under a multiply blend");
        assertTrue(folded > 120 && folded < bright - 25, "The original fold remains visible through the white artwork");
        assertEquals(photo.getRGB(25, 45), image.getRGB(25, 45));
    }

    @Test void transparentTexturePixelsPreservePhotoAndSameArtworkCanServeMultipleInstances() throws Exception {
        var photo = canvas(100, 100, 0xffffffff); rectangle(photo, 15, 10, 70, 80, 0xffaabbaa);
        var texture = canvas(12, 12, 0x00ffffff); rectangle(texture, 3, 3, 6, 6, 0xffff0020);
        var uv = canvas(100, 100, 0); uv(uv, 23, 32, 20, 20, 1); uv(uv, 57, 32, 20, 20, 2);
        var result = CommercePhotoMockupComposer.compose(png(photo), png(photo), png(uv), List.of("left", "right"),
                Map.of("left", png(texture), "right", png(texture)));
        var image = CommerceMockupComposer.decode(result.png());
        assertEquals(photo.getRGB(23, 32), image.getRGB(23, 32));
        assertEquals(image.getRGB(32, 42), image.getRGB(66, 42));
        assertNotEquals(photo.getRGB(32, 42), image.getRGB(32, 42));
        assertEquals(Set.of("left", "right"), result.report().visiblePrintIds());
    }

    @Test void rejectsUnregisteredSideMissingForegroundAndDifferentGarmentSilhouette() throws Exception {
        var model = canvas(100, 100, 0xffffffff); rectangle(model, 20, 10, 60, 80, 0xff999999);
        var uv = canvas(100, 100, 0); uv(uv, 30, 30, 40, 40, 1);
        var replacements = Map.of("p", png(canvas(8, 8, 0xffcc2233)));
        assertThrows(IllegalArgumentException.class, () -> CommercePhotoMockupComposer.compose("LEFT", png(model), png(model), png(uv), List.of("p"), replacements));
        assertThrows(IllegalArgumentException.class, () -> CommercePhotoMockupComposer.compose(png(canvas(100, 100, 0xffffffff)), png(model), png(uv), List.of("p"), replacements));
        var triangle = canvas(100, 100, 0xffffffff);
        for (int y = 10; y < 90; y++) rectangle(triangle, 49 - (y - 10) / 3, y, 2 + 2 * ((y - 10) / 3), 1, 0xff999999);
        assertThrows(IllegalArgumentException.class, () -> CommercePhotoMockupComposer.compose(png(triangle), png(model), png(uv), List.of("p"), replacements));
    }

    @Test void rejectsUvOutsideGarmentIncompleteGroupAndInvisibleMotherArtwork() throws Exception {
        var model = canvas(100, 100, 0xffffffff); rectangle(model, 20, 10, 60, 80, 0xff999999);
        var outside = canvas(100, 100, 0); uv(outside, 1, 1, 10, 10, 1);
        var good = canvas(100, 100, 0); uv(good, 30, 30, 40, 40, 1);
        var red = png(canvas(8, 8, 0xffcc2233));
        assertThrows(IllegalArgumentException.class, () -> CommercePhotoMockupComposer.compose(png(model), png(model), png(outside), List.of("p"), Map.of("p", red)));
        assertThrows(IllegalArgumentException.class, () -> CommercePhotoMockupComposer.compose(png(model), png(model), png(good), List.of("p"), Map.of("other", red)));
        assertThrows(IllegalArgumentException.class, () -> CommercePhotoMockupComposer.compose(png(model), png(model), png(good), List.of("p"), Map.of("p", png(canvas(8, 8, 0)))));
    }

    @Test void planarUsesFrozenPhotoCoordinatesAndRejectsPaintingBackground() throws Exception {
        var photo = canvas(100, 100, 0xffffffff); rectangle(photo, 20, 10, 60, 80, 0xffbbbbbb);
        var area = new CommercePrintTemplateService.Area("p", "FRONT", .35, .3, .3, .3, 150, 150, 150);
        var art = Map.of("p", png(canvas(8, 8, 0xff1234ee)));
        var result = CommercePhotoMockupComposer.composePlanar(png(photo), List.of(area), art);
        var before = CommerceMockupComposer.square(png(photo)); var after = CommerceMockupComposer.decode(result.png());
        assertEquals(before.getRGB(10, 10), after.getRGB(10, 10));
        assertNotEquals(before.getRGB(500, 450), after.getRGB(500, 450));
        var badArea = new CommercePrintTemplateService.Area("p", "FRONT", 0, 0, .3, .3, 150, 150, 150);
        assertThrows(IllegalArgumentException.class, () -> CommercePhotoMockupComposer.composePlanar(png(photo), List.of(badArea), art));
    }

    @Test void foregroundMaskKeepsCoordinateDimensionsAndEnclosedWhiteDetails() throws Exception {
        var photo = canvas(100, 90, 0xffffffff); rectangle(photo, 20, 10, 60, 70, 0xffbbbbbb);
        rectangle(photo, 40, 20, 10, 10, 0xffffffff);
        var mask = CommerceMockupComposer.decode(CommercePhotoMockupComposer.foregroundMask(png(photo)));
        assertEquals(100, mask.getWidth()); assertEquals(90, mask.getHeight());
        assertEquals(0, mask.getRGB(0, 0) >>> 24);
        assertEquals(255, mask.getRGB(45, 25) >>> 24);
        assertEquals(255, mask.getRGB(25, 25) >>> 24);
    }

    @Test void shortSleeveMismatchDoesNotRejectSupportedChestButStillRejectsSleeveInk() throws Exception {
        var photo=canvas(200,220,0xffffffff);var model=canvas(200,220,0xffffffff);
        rectangle(photo,40,20,120,180,0xffaaaaaa);rectangle(model,40,20,120,180,0xffaaaaaa);
        rectangle(photo,10,40,180,40,0xffaaaaaa);rectangle(model,10,40,180,22,0xffaaaaaa);
        var chest=canvas(200,220,0);uv(chest,75,50,50,55,1);
        var art=Map.of("p",png(canvas(8,8,0xffff0000)));
        var result=CommercePhotoMockupComposer.compose(png(photo),png(model),png(chest),List.of("p"),art);
        var out=CommerceMockupComposer.decode(result.png());
        assertNotEquals(photo.getRGB(100,70),out.getRGB(100,70));
        assertEquals(0,result.report().protectedChangedPixels());
        var sleeve=canvas(200,220,0);uv(sleeve,15,45,15,30,1);
        assertThrows(IllegalArgumentException.class,()->CommercePhotoMockupComposer.compose(png(model),png(photo),png(sleeve),List.of("p"),art));
    }

    @Test void sleeveTipRowBranchingCannotLeaveWhiteBandsAcrossTorsoArtwork() throws Exception {
        var model = canvas(100, 100, 0xffffffff); rectangle(model, 30, 10, 40, 80, 0xffbbbbbb);
        rectangle(model, 10, 30, 80, 30, 0xffbbbbbb); rectangle(model, 10, 60, 13, 5, 0xffbbbbbb); rectangle(model, 77, 60, 13, 5, 0xffbbbbbb);
        var photo = canvas(100, 100, 0xffffffff); rectangle(photo, 30, 10, 40, 80, 0xffbbbbbb);
        rectangle(photo, 10, 30, 80, 30, 0xffbbbbbb); rectangle(photo, 10, 60, 13, 3, 0xffbbbbbb); rectangle(photo, 77, 60, 13, 3, 0xffbbbbbb);
        var uv = canvas(100, 100, 0); uv(uv, 37, 40, 25, 35, 1);
        var art = Map.of("p", png(canvas(8, 8, 0xffff0000)));
        var report = CommercePhotoMockupComposer.inspectMapping(png(photo), png(model), png(uv), List.of("p"));
        assertEquals(0, report.protectedChangedPixels());
        var result = CommercePhotoMockupComposer.compose(png(photo), png(model), png(uv), List.of("p"), art);
        var image = CommerceMockupComposer.decode(result.png());
        for (int y = 58; y <= 67; y++) assertNotEquals(photo.getRGB(50, y), image.getRGB(50, y), "Chest ink must remain continuous on row " + y);
    }

    @Test void differingUnderarmTerminationMustNotJumpArtworkHorizontally() throws Exception {
        var model = canvas(100, 100, 0xffffffff); rectangle(model, 20, 10, 60, 80, 0xffbbbbbb);
        rectangle(model, 10, 30, 80, 35, 0xffbbbbbb);
        var photo = canvas(100, 100, 0xffffffff); rectangle(photo, 20, 10, 60, 80, 0xffbbbbbb);
        rectangle(photo, 10, 30, 80, 33, 0xffbbbbbb);
        var uv = canvas(100, 100, 0); uv(uv, 25, 30, 50, 50, 1);
        var artwork = canvas(32, 32, 0); rectangle(artwork, 8, 0, 3, 32, 0xffee0055);
        var result = CommercePhotoMockupComposer.compose(png(photo), png(model), png(uv), List.of("p"), Map.of("p", png(artwork)));
        var image = CommerceMockupComposer.decode(result.png());
        double previous = -1;
        for (int y = 58; y <= 69; y++) {
            double sum = 0; int count = 0;
            for (int x = 20; x < 80; x++) if (image.getRGB(x, y) != photo.getRGB(x, y)) { sum += x; count++; }
            assertTrue(count > 0, "No scanline may disappear");
            double center = sum / count;
            if (previous >= 0) assertTrue(Math.abs(center - previous) <= 1, "Letter strokes must not jump across the underarm on row " + y);
            previous = center;
        }
    }

    @Test void transparentArtAtAnOccludedBackSlotEdgeDoesNotRequireMovingInkOntoFront() throws Exception {
        var photo = canvas(100, 100, 0xffffffff); rectangle(photo, 15, 10, 70, 80, 0xffbbbbbb);
        var uv = canvas(100, 100, 0); uv(uv, 30, 30, 30, 30, 1);
        for (int y = 35; y < 40; y++) uv.setRGB(80, y, 0xff008002);
        var artwork = canvas(16, 16, 0); rectangle(artwork, 4, 4, 8, 8, 0xffdd2255);
        var result = CommercePhotoMockupComposer.compose(png(photo), png(photo), png(uv), List.of("front", "back"),
                Map.of("front", png(artwork), "back", png(artwork)));
        var image = CommerceMockupComposer.decode(result.png());
        assertEquals(photo.getRGB(80, 37), image.getRGB(80, 37), "The hidden artwork edge is genuinely transparent");
        assertTrue(result.report().changedPixels() > 0);
    }

    static BufferedImage canvas(int w, int h, int color) {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); rectangle(image, 0, 0, w, h, color); return image;
    }
    static void rectangle(BufferedImage image, int x, int y, int w, int h, int color) {
        for (int row = y; row < y + h; row++) for (int col = x; col < x + w; col++) image.setRGB(col, row, color);
    }
    static void uv(BufferedImage image, int x, int y, int w, int h, int id) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++)
            image.setRGB(x + col, y + row, 0xff000000 | (int) Math.round(col * 255d / (w - 1)) << 16
                    | (int) Math.round((1 - row / (double) (h - 1)) * 255) << 8 | id);
    }
    static byte[] png(BufferedImage image) throws Exception {
        try (var out = new ByteArrayOutputStream()) { ImageIO.write(image, "png", out); return out.toByteArray(); }
    }
}
