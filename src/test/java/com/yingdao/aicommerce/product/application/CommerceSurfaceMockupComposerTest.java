package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommerceSurfaceMockupComposerTest {
    @Test void uvReplacesExactVisibleSlotsWithWebglVerticalConvention() throws Exception {
        byte[] blank = png(new int[][]{{0xff444444, 0xff444444}, {0xff444444, 0xff444444}});
        byte[] texture = png(new int[][]{{0xffff0000, 0xff00ff00}, {0xff0000ff, 0xffffffff}});
        // texture lower-left, upper-right, hidden, lower-right respectively.
        byte[] uv = png(new int[][]{{0xff000001, 0xffffff01}, {0x00000000, 0xffff0001}});
        var output = CommerceMockupComposer.decode(CommerceSurfaceMockupComposer.compose(blank, uv,
                List.of("sleeve"), Map.of("sleeve", texture)));
        assertEquals(0xff0000ff, output.getRGB(0, 0));
        assertEquals(0xff00ff00, output.getRGB(1, 0));
        assertEquals(0xff444444, output.getRGB(0, 1));
        assertEquals(0xffffffff, output.getRGB(1, 1));
        assertEquals(Set.of("sleeve"), CommerceSurfaceMockupComposer.inspect(uv, List.of("sleeve")).visiblePrintIds());
    }

    @Test void explicitIndexBindingKeepsTwoSurfacesDistinctAndTransparencyRevealsBlank() throws Exception {
        byte[] blank = png(new int[][]{{0xff444444, 0xff444444}});
        byte[] uv = png(new int[][]{{0xff000002, 0xff000001}});
        var output = CommerceMockupComposer.decode(CommerceSurfaceMockupComposer.compose(blank, uv,
                List.of("front", "back"), Map.of("front", png(new int[][]{{0x00ff0000}}),
                        "back", png(new int[][]{{0xff00ff00}}))));
        assertEquals(0xff00ff00, output.getRGB(0, 0));
        assertEquals(0xff444444, output.getRGB(1, 0), "Transparent replacement must not retain an old print");
    }

    @Test void invalidOrUnboundUvAndMisalignedBuffersCannotFallBackToFront() throws Exception {
        byte[] blank = png(new int[][]{{0xff444444}}), uv = png(new int[][]{{0xff000002}});
        assertThrows(IllegalArgumentException.class, () -> CommerceSurfaceMockupComposer.inspect(uv, List.of("front")));
        byte[] one = png(new int[][]{{0xff000001}});
        assertThrows(IllegalArgumentException.class, () -> CommerceSurfaceMockupComposer.compose(blank, one,
                List.of("back"), Map.of("front", blank)));
        byte[] wide = png(new int[][]{{0xff444444, 0xff444444}});
        assertThrows(IllegalArgumentException.class, () -> CommerceSurfaceMockupComposer.compose(wide, one,
                List.of("back"), Map.of("back", blank)));
    }

    static byte[] png(int[][] pixels) throws Exception {
        var image = new BufferedImage(pixels[0].length, pixels.length, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pixels.length; y++) for (int x = 0; x < pixels[y].length; x++) image.setRGB(x, y, pixels[y][x]);
        try (var bytes = new ByteArrayOutputStream()) { ImageIO.write(image, "png", bytes); return bytes.toByteArray(); }
    }
}
