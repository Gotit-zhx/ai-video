package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CommerceArtworkTransparencyTest {
    @Test void genuineTransparentPngPreservesWhiteArtworkAndReportsActualAlpha() throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 3, 0xffffffff);
        image.setRGB(4, 3, 0x80000000);
        byte[] bytes = png(image), original = bytes.clone();
        var report = CommerceArtworkTransparency.inspect(bytes);
        assertTrue(report.verified());
        assertEquals("VERIFIED_ALPHA", report.status());
        assertEquals(62, report.clearPixels());
        assertEquals(2, report.visiblePixels());
        assertEquals(64, report.totalPixels());
        assertArrayEquals(original, bytes, "Verification must not whiten or strip the artwork");
    }

    @Test void opaqueRgbAndOpaqueAlphaPngAreNotCalledTransparent() throws Exception {
        for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB}) {
            var image = new BufferedImage(8, 8, type);
            for (int y=0;y<8;y++) for (int x=0;x<8;x++) image.setRGB(x,y,0xffffffff);
            var report = CommerceArtworkTransparency.inspect(png(image));
            assertFalse(report.verified());
            assertEquals("OPAQUE", report.status());
            assertEquals(0, report.clearPixels());
            assertEquals(64, report.visiblePixels());
        }
    }

    @Test void drawnCheckerboardIsOpaqueEvenWithPngAlphaChannel() throws Exception {
        var image = new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)image.setRGB(x,y,(x+y)%2==0?0xffffffff:0xffcccccc);
        var report = CommerceArtworkTransparency.inspect(png(image));
        assertTrue(report.alphaChannel());
        assertFalse(report.verified());
        assertEquals("OPAQUE", report.status());
    }

    @Test void emptyCanvasAndUniformSemitransparentBackdropAreNotUsableTransparency() throws Exception {
        var image = new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
        assertEquals("EMPTY_ARTWORK", CommerceArtworkTransparency.inspect(png(image)).status());
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)image.setRGB(x,y,0x80ffffff);
        assertEquals("NO_CLEAR_BACKGROUND", CommerceArtworkTransparency.inspect(png(image)).status());
    }

    @Test void indexedPngTransparencyIsRecognized() throws Exception {
        byte[] colors = {0, (byte)255};
        var palette = new IndexColorModel(8,2,colors,colors,colors,0);
        var image = new BufferedImage(8,8,BufferedImage.TYPE_BYTE_INDEXED,palette);
        image.getRaster().setSample(3,3,0,1);
        assertTrue(CommerceArtworkTransparency.inspect(png(image)).verified());
    }

    @Test void fakePngMetadataJpegAndBrokenBytesNeverBecomeVerifiedAlpha() throws Exception {
        var image = new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image,"jpeg",output));
        assertEquals("NOT_PNG",CommerceArtworkTransparency.inspect(output.toByteArray()).status());
        assertEquals("INVALID_IMAGE",CommerceArtworkTransparency.inspect(new byte[0]).status());
        assertEquals("INVALID_IMAGE",CommerceArtworkTransparency.inspect(new byte[]{(byte)0x89,'P','N','G',13,10,26,10}).status());
        assertFalse(CommerceArtworkTransparency.inspect(null).verified());
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image,"png",output));
        return output.toByteArray();
    }
}
