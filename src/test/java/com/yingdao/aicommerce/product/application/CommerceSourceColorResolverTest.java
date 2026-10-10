package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceSourceColorResolverTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void sharedOriginalStyleNumbersDoNotDuplicateNewArtworkColorsOrLoseSkus() {
        var image=image("shirt",0);var skus=new ArrayList<ProductCaptureSku>();
        for(String color:List.of("Black","Pink","White"))for(int style=1;style<=3;style++)
            skus.add(sku(color+style,Map.of("Color",color+"/"+style,"Size","M"),image.getOriginalUrl(),"SKU",skus.size()));
        skus.add(sku("pure",Map.of("Color","PURE WHITE"),image.getOriginalUrl(),"SKU",skus.size()));
        var result=CommerceSourceColorResolver.resolve(detail(skus,List.of(image)));
        assertEquals(List.of("Black","Pink","White"),result.stream().map(CommerceSourceColorResolver.Color::label).toList());
        assertEquals(10,result.stream().mapToInt(c->c.skuKeys().size()).sum());
        assertEquals(4,result.get(2).skuKeys().size());
    }

    @Test void unrelatedShadeNumbersAreNotAutomaticallyStripped() {
        var image=image("shirt",0);
        var result=CommerceSourceColorResolver.resolve(detail(List.of(
            sku("a",Map.of("Color","Blue/1"),image.getOriginalUrl(),"SKU",0),
            sku("b",Map.of("Color","Blue/2"),image.getOriginalUrl(),"SKU",1)),List.of(image)));
        assertEquals(List.of("Blue/1","Blue/2"),result.stream().map(CommerceSourceColorResolver.Color::label).toList());
    }

    @Test void ninetySixSkusWithTwelveColorsAndEightSizesProduceOnlyTwelveGroups() {
        var skus = new ArrayList<ProductCaptureSku>();
        var images = new ArrayList<ProductCaptureImage>();
        for (int color = 1; color <= 12; color++) {
            var image = image("color-" + color, color);
            images.add(image);
            for (int size = 1; size <= 8; size++) skus.add(sku("c" + color + "-s" + size,
                    Map.of("Color", "Color " + color, "Size", "Size " + size), image.getOriginalUrl(), "MODEL", skus.size()));
        }
        Collections.reverse(skus);
        var result = CommerceSourceColorResolver.resolve(detail(skus, images));
        assertEquals(12, result.size());
        for (int color = 1; color <= 12; color++) {
            var group = result.get(color - 1);
            assertEquals(CommerceImageArchive.sha(("sku-color:color " + color).getBytes(StandardCharsets.UTF_8)), group.key());
            assertEquals("Color " + color, group.label());
            assertEquals(8, group.skuKeys().size());
            assertEquals("c" + color + "-s1", group.skuKeys().getFirst());
            assertEquals(images.get(color - 1).getId(), group.imageId());
            assertTrue(group.hasColorReference());
        }
    }

    @Test void dimensionAliasesShareTheExistingChineseEnglishAndIndonesianNormalization() {
        List<String> names = List.of("Color", "colour", "Colors", "colours", "Warna", "颜色", "顏色", "色", " ＣＯＬＯＲ ");
        var image = image("black", 0);
        var skus = new ArrayList<ProductCaptureSku>();
        for (int i = 0; i < names.size(); i++) {
            assertTrue(CommerceSourceColorResolver.isColorDimension(names.get(i)));
            skus.add(sku("sku-" + i, Map.of(names.get(i), "Black"), image.getOriginalUrl(), "SKU", i));
        }
        var groups = CommerceSourceColorResolver.resolve(detail(skus, List.of(image)));
        assertEquals(1, groups.size());assertEquals(names.size(), groups.getFirst().skuKeys().size());
        for (String name : List.of("Size", "Style", "款式", "尺码", "规格", "Colorful", "颜色分类"))
            assertFalse(CommerceSourceColorResolver.isColorDimension(name));
        assertFalse(CommerceSourceColorResolver.isColorDimension(null));
    }

    @Test void normalizesColorIdentityWhileKeepingOriginalNameAndCompositeColorsIntact() {
        var image = image("colors", 0);
        var result = CommerceSourceColorResolver.resolve(detail(List.of(
                sku("first", Map.of("Color", "Ｂｌａｃｋ"), image.getOriginalUrl(), "SKU", 0),
                sku("second", Map.of("颜色", " black "), image.getOriginalUrl(), "SKU", 1),
                sku("two-tone", Map.of("Warna", "Black / White"), image.getOriginalUrl(), "SKU", 2)), List.of(image)));
        assertEquals(2, result.size());assertEquals(CommerceImageArchive.sha("sku-color:black".getBytes(StandardCharsets.UTF_8)), result.getFirst().key());
        assertEquals("Ｂｌａｃｋ", result.getFirst().label());assertEquals(List.of("first", "second"), result.getFirst().skuKeys());
        assertEquals("Black / White", result.get(1).label());
    }

    @Test void sharedExplicitSkuImageDoesNotMergeDifferentColorNamesOrInventMissingEvidence() {
        var image = image("shared", 0);
        var result = CommerceSourceColorResolver.resolve(detail(List.of(
                sku("black", Map.of("Color", "Black"), image.getOriginalUrl(), "SKU", 0),
                sku("white", Map.of("Color", "White"), image.getOriginalUrl(), "VARIANT_OPTION", 1)), List.of(image)));
        assertEquals(List.of("Black", "White"), result.stream().map(CommerceSourceColorResolver.Color::label).toList());
        assertTrue(result.stream().allMatch(CommerceSourceColorResolver.Color::hasColorReference));
        assertTrue(result.stream().allMatch(color -> image.getId().equals(color.imageId())));
    }

    @Test void inheritedOrUnknownImageSourcesNeverBecomeColorSwatchesEvenWhenTheyMatchTheMainImage() {
        var image = image("main", 0);
        for (String source : Arrays.asList("PRODUCT_MAIN", "PRODUCT_MAIN_PAGE", "UNKNOWN", null)) {
            var color = CommerceSourceColorResolver.resolve(detail(List.of(
                    sku("red", Map.of("Color", "Red"), image.getOriginalUrl(), source, 0)), List.of(image))).getFirst();
            assertEquals("Red", color.label());assertNull(color.imageId());assertFalse(color.hasColorReference());
        }
        var inherited = new ProductCaptureSku("org", "capture", "red", null, null, "Red", BigDecimal.ONE,
                null, 1, image.getOriginalUrl(), "SKU", "{\"Color\":\"Red\"}", "{\"imageInherited\":true}", 0);
        assertFalse(CommerceSourceColorResolver.resolve(detail(List.of(inherited), List.of(image))).getFirst().hasColorReference());
    }

    @Test void prefersAReadableDirectSkuImageAndCanUseAnOptionImageWhenDirectReferencesAreMissing() {
        var option = image("option", 0);var direct = image("direct", 1);
        var skus = List.of(
                sku("m", Map.of("Color", "Blue", "Size", "M"), option.getOriginalUrl(), "VARIANT_OPTION", 0),
                sku("l", Map.of("Color", "Blue", "Size", "L"), "https://cdn.example.com/not-captured.png", "SKU", 1),
                sku("xl", Map.of("Color", "Blue", "Size", "XL"), direct.getOriginalUrl(), "MODEL", 2));
        assertEquals(direct.getId(), CommerceSourceColorResolver.resolve(detail(skus, List.of(option, direct))).getFirst().imageId());
        assertEquals(option.getId(), CommerceSourceColorResolver.resolve(detail(skus, List.of(option))).getFirst().imageId());
    }

    @Test void missingCapturedImageIsReportedInsteadOfSubstitutingAnyMainPhoto() {
        var main = image("main", 0);
        var color = CommerceSourceColorResolver.resolve(detail(List.of(
                sku("green", Map.of("颜色", "绿色"), "https://cdn.example.com/missing-green.png", "SKU", 0)), List.of(main))).getFirst();
        assertEquals("绿色", color.label());assertNull(color.imageId());assertFalse(color.hasColorReference());
    }

    @Test void reconnectsTikTokCdnAliasesToTheSameCapturedAsset() {
        String asset = "/tos-useast5-i-abc/abcdefghijklmnopqrstuvwx";
        var image = new ProductCaptureImage("org", "capture", "https://p16-oec-va.ibyteimg.com" + asset + "~tplv-a.webp",
                "VARIANT", "Blue", 0, "image/webp", "{}");
        var sku = sku("blue", Map.of("colour", "Blue"), "https://p19-oec-va.byteimg.com" + asset + "~tplv-b.webp", "VARIANT_OPTION", 0);
        assertEquals(image.getId(), CommerceSourceColorResolver.resolve(detail(List.of(sku), List.of(image))).getFirst().imageId());
    }

    @Test void onlySourceCaptureAndOrganizationAssetsMaySupplyReferences() {
        var wrongCapture = new ProductCaptureImage("org", "another-capture", "https://cdn.example.com/black.png", "VARIANT", null, 0, "image/png", "{}");
        var wrongOrg = new ProductCaptureImage("another-org", "capture", wrongCapture.getOriginalUrl(), "VARIANT", null, 1, "image/png", "{}");
        var foreignSku = new ProductCaptureSku("another-org", "capture", "foreign", null, null, "Red", BigDecimal.ONE,
                null, 1, wrongCapture.getOriginalUrl(), "SKU", "{\"Color\":\"Red\"}", "{}", 0);
        var result = CommerceSourceColorResolver.resolve(detail(List.of(foreignSku,
                sku("black", Map.of("Color", "Black"), wrongCapture.getOriginalUrl(), "SKU", 1)), List.of(wrongCapture, wrongOrg)));
        assertEquals(1, result.size());assertEquals("Black", result.getFirst().label());assertFalse(result.getFirst().hasColorReference());
    }

    @Test void noColorDimensionProducesOneExplicitDefaultWithoutTreatingSizeStyleOrImageCountAsColors() {
        var first = image("first", 0);var second = image("second", 1);
        var result = CommerceSourceColorResolver.resolve(detail(List.of(
                sku("s", Map.of("Size", "S", "Style", "Black cat"), first.getOriginalUrl(), "SKU", 0),
                sku("m", Map.of("尺码", "M", "款式", "White dog"), second.getOriginalUrl(), "SKU", 1)), List.of(first, second)));
        assertEquals(1, result.size());var fallback = result.getFirst();
        assertEquals(CommerceSourceColorResolver.DEFAULT_KEY, fallback.key());
        assertEquals(CommerceSourceColorResolver.DEFAULT_LABEL, fallback.label());assertEquals("原图色", fallback.label());
        assertEquals(List.of("s", "m"), fallback.skuKeys());assertNull(fallback.imageId());assertFalse(fallback.hasColorReference());
        assertEquals(1, CommerceSourceColorResolver.resolve(detail(List.of(), List.of(first))).size());
    }

    @Test void incompleteOrConflictingColorDimensionsFailClearlyRatherThanDroppingSkus() {
        var image = image("main", 0);
        var partial = detail(List.of(
                sku("red", Map.of("Color", "Red"), image.getOriginalUrl(), "SKU", 0),
                sku("unknown", Map.of("Size", "M"), image.getOriginalUrl(), "SKU", 1)), List.of(image));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CommerceSourceColorResolver.resolve(partial)).getMessage().contains("unknown"));
        for (Map<String, Object> attributes : List.of(Map.<String, Object>of("Color", " "),
                Map.<String, Object>of("Color", List.of("Red", "Blue")), Map.<String, Object>of("Color", "Red", "颜色", "Blue"))) {
            assertThrows(IllegalArgumentException.class, () -> CommerceSourceColorResolver.resolve(detail(List.of(
                    sku("bad", attributes, image.getOriginalUrl(), "SKU", 0)), List.of(image))));
        }
    }

    @Test void resultAndSkuMembershipAreImmutableAndDuplicateMembershipIsNotRepeated() {
        var sku = sku("black", Map.of("Color", "Black"), null, "SKU", 0);
        var result = CommerceSourceColorResolver.resolve(detail(List.of(sku, sku), List.of()));
        assertEquals(List.of("black"), result.getFirst().skuKeys());
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.getFirst()));
        assertThrows(UnsupportedOperationException.class, () -> result.getFirst().skuKeys().add("later"));
    }

    @Test void hashesStableColorKeysWithoutTruncationAndRejectsLabelsBeyondTheStorageLimit() {
        String label = "深".repeat(160);
        var color = CommerceSourceColorResolver.resolve(detail(List.of(
                sku("long", Map.of("Color", label), null, "SKU", 0)), List.of())).getFirst();
        assertEquals(label, color.label());assertTrue(color.key().matches("[0-9a-f]{64}"));
        assertEquals(color.key(), CommerceSourceColorResolver.resolve(detail(List.of(
                sku("same", Map.of("Warna", label), null, "SKU", 0)), List.of())).getFirst().key());
        var failure = assertThrows(IllegalArgumentException.class, () -> CommerceSourceColorResolver.resolve(detail(List.of(
                sku("too-long", Map.of("Color", label + "色"), null, "SKU", 0)), List.of())));
        assertTrue(failure.getMessage().contains("160"));assertTrue(failure.getMessage().contains("too-long"));
        String unicodeLabel = "🟦".repeat(160);
        assertEquals(unicodeLabel, CommerceSourceColorResolver.resolve(detail(List.of(
                sku("unicode", Map.of("Color", unicodeLabel), null, "SKU", 0)), List.of())).getFirst().label());
    }

    private ProductCaptureService.CaptureDetail detail(List<ProductCaptureSku> skus, List<ProductCaptureImage> images) {
        var capture = mock(ProductCapture.class);when(capture.getId()).thenReturn("capture");when(capture.getOrganizationId()).thenReturn("org");
        return new ProductCaptureService.CaptureDetail(capture, skus, images, List.of(), Map.of(), Map.of());
    }

    private ProductCaptureSku sku(String key, Map<String, ?> attributes, String url, String source, int order) {
        return new ProductCaptureSku("org", "capture", key, null, null, key, BigDecimal.ONE, null,
                1, url, source, json.writeValueAsString(attributes), "{}", order);
    }

    private ProductCaptureImage image(String name, int order) {
        return new ProductCaptureImage("org", "capture", "https://cdn.example.com/" + name + ".png",
                "VARIANT", name, order, "image/png", "{}");
    }
}
