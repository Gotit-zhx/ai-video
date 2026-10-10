package com.yingdao.aicommerce.product.application;

import java.util.*;
import static com.yingdao.aicommerce.product.application.CommercePrintTemplateService.*;

/** A checker placed twice on the front and once on the back, alongside a separate blue badge. */
final class RepeatedSurfaceArtworkFixture {
    static final String CHECKER = SurfaceTemplateFixture.hash('2');
    static final String BADGE = SurfaceTemplateFixture.hash('5');
    static final List<String> PRINT_IDS = List.of("front-checker", "front-repeat", "back-checker", "fixed-badge");

    static Configuration configuration() {
        var c = SurfaceTemplateFixture.configuration();
        var prints = List.of(print("front-checker", CHECKER, 1), print("front-repeat", CHECKER, 1),
                print("back-checker", CHECKER, -1), print("fixed-badge", BADGE, 1));
        var uv = new HashMap<>(SurfaceTemplateFixture.sides(SurfaceTemplateFixture.hash('f')));
        uv.put("FRONT", SurfaceTemplateFixture.hash('e'));
        uv.put("BACK", SurfaceTemplateFixture.hash('9'));
        var changed = new Configuration(c.product(), c.areas(), c.baseImages(), c.reconstruction(),
                c.sourceImageId(), c.referenceSetId(), prints, c.renderedViews(), c.blankRenderedViews(),
                uv, c.placementMasks(), PRINT_IDS, null);
        return SurfaceTemplateFixture.binding(changed, renderFingerprint(changed));
    }

    private static SurfacePrint print(String id, String artwork, double normalZ) {
        return new SurfacePrint(id, artwork, List.of(0d, 0d, 0d), List.of(0d, 0d, normalZ),
                List.of(1d, 0d, 0d), .3, .4, 0, "outer");
    }

    static Map<String,byte[]> assets() throws Exception {
        var result = new HashMap<String,byte[]>();
        int[][] blank = new int[2][8];
        for (var row : blank) Arrays.fill(row, 0xff444444);
        byte[] blankBytes = CommerceSurfaceMockupComposerTest.png(blank);
        for (char id : new char[]{'a','b','c','d','1'}) result.put(SurfaceTemplateFixture.hash(id), blankBytes);
        result.put(SurfaceTemplateFixture.hash('e'), uv(true));
        result.put(SurfaceTemplateFixture.hash('9'), uv(false));
        result.put(SurfaceTemplateFixture.hash('f'), CommerceSurfaceMockupComposerTest.png(new int[2][8]));
        result.put(CHECKER, checker(0xff111111, 0xffeeeeee));
        result.put(BADGE, CommerceSurfaceMockupComposerTest.png(new int[][]{{0xff0000ff}}));
        result.put(SurfaceTemplateFixture.hash('4'), checker(0xffff0000, 0xffffff00));
        result.put(SurfaceTemplateFixture.hash('7'), checker(0xff00ff00, 0xffff00ff));
        return result;
    }

    private static byte[] uv(boolean front) throws Exception {
        int[][] pixels = new int[2][8];
        sample(pixels, 0, front ? 1 : 3);
        if (front) sample(pixels, 3, 2);
        pixels[0][6] = 0xff000004;
        return CommerceSurfaceMockupComposerTest.png(pixels);
    }

    private static void sample(int[][] pixels, int x, int index) {
        // RG stores UV, B stores the one-based print index, alpha stores visible coverage.
        pixels[0][x] = 0xff00ff00 | index;
        pixels[0][x + 1] = 0xffffff00 | index;
        pixels[1][x] = 0xff000000 | index;
        pixels[1][x + 1] = 0xffff0000 | index;
    }

    private static byte[] checker(int first, int second) throws Exception {
        return CommerceSurfaceMockupComposerTest.png(new int[][]{{first,second},{second,first}});
    }
}
