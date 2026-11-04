package com.yingdao.aicommerce.product.application;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductSkuNamingTest {
    private final Map<String,ProductSkuNaming.PatternCode> catalog = new HashMap<>();
    private final Function<String,ProductSkuNaming.PatternCode> styles = key -> catalog.computeIfAbsent(key,
            ignored -> new ProductSkuNaming.PatternCode("K" + String.format("%02d", catalog.size() + 1), String.format("%08X", catalog.size() + 1)));

    Map<String,Object> sku(String id, String color, String size) {
        return new LinkedHashMap<>(Map.of("externalSkuId", id, "sellerSku", "source-" + id,
                "attributes", Map.of("Color", color, "Size", size), "price", new BigDecimal("129.50"),
                "stock", 17, "imageUrl", "https://example.com/" + color + ".jpg"));
    }
    List<Map<String,Object>> assign(List<Map<String,Object>> input, List<Map<String,Object>> old) {
        return ProductSkuNaming.assign("org", "source-product", input, old, styles);
    }
    String code(Map<String,Object> sku) { return sku.get("sellerSku").toString(); }
    String style(Map<String,Object> sku) { return code(sku).split("-")[1]; }

    @Test void onePatternSharesOneCodeAcrossColorsAndSizesWithoutChangingSourceFacts() {
        var source = List.of(sku("1","Black","S"),sku("2","Black","M"),sku("3","White","S"));
        var result = assign(source,List.of());
        assertEquals(1,catalog.size());
        assertEquals(1,result.stream().map(this::style).distinct().count());
        assertEquals(1,result.stream().map(row -> code(row).split("-")[3]).distinct().count());
        assertEquals(3,result.stream().map(this::code).distinct().count());
        for (int i=0;i<source.size();i++) {
            assertTrue(code(result.get(i)).matches("[A-Z]+-K\\d{2}-[SM]-[A-F0-9]{8}"));
            for (String field:List.of("externalSkuId","attributes","price","stock","imageUrl"))
                assertEquals(source.get(i).get(field),result.get(i).get(field));
            assertEquals("source-"+(i+1),source.get(i).get("sellerSku"),"The capture request remains original evidence");
        }
    }

    @Test void recaptureAndReorderingReuseMerchantCodesRatherThanMintingNewOnes() {
        var a=sku("1","Black","M");var b=sku("2","White","S");
        var first=assign(List.of(a,b),List.of());
        var again=assign(List.of(b,a),first);
        assertEquals(code(first.get(0)),code(again.get(1)));
        assertEquals(code(first.get(1)),code(again.get(0)));
        assertEquals(1,catalog.size());
    }

    @Test void priceAndStockUpdatesDoNotRenameAnExistingVariant() {
        var first=assign(List.of(sku("1","Black","M")),List.of());
        var changed=sku("1","Black","M");changed.put("price",new BigDecimal("149.50"));changed.put("stock",9);
        var again=assign(List.of(changed),first);
        assertEquals(code(first.getFirst()),code(again.getFirst()));
        assertEquals(9,again.getFirst().get("stock"));
    }

    @Test void newSizesAndRecaptureWithoutPreviousRowsKeepThePatternsRandomSuffix() {
        var first=assign(List.of(sku("1","Black","M")),List.of());
        var later=assign(List.of(sku("2","White","L"),sku("1","Black","M")),List.of());
        assertEquals(code(first.getFirst()),code(later.get(1)));
        assertEquals(code(first.getFirst()).split("-")[3],code(later.getFirst()).split("-")[3]);
    }

    @Test void legacyVariantSuffixesAreReplacedByOnePersistedPatternSuffix() {
        var old=sku("1","Black","M");
        old.put("sellerSku","BK-K583204-M-12345678");
        old.put(ProductSkuNaming.METADATA,Map.of("version","1","originalSellerSku","supplier-1"));
        var next=assign(List.of(old,sku("2","White","L")),List.of(old));
        assertEquals(1,next.stream().map(row->code(row).split("-")[3]).distinct().count());
        assertEquals("supplier-1",((Map<?,?>)next.getFirst().get(ProductSkuNaming.METADATA)).get("originalSellerSku"));
        assertEquals(3,style(next.getFirst()).length());
    }

    @Test void ambiguousDuplicateColorAndSizeCannotSilentlyReceiveDifferentPatternSuffixes() {
        var error=assertThrows(IllegalArgumentException.class,
                ()->assign(List.of(sku("1","Black","M"),sku("2","黑色","M")),List.of()));
        assertTrue(error.getMessage().contains("重复颜色和尺码"));
    }

    @Test void changedColorOrSizeDoesNotKeepAnIncorrectOldCode() {
        var first=assign(List.of(sku("1","Black","M")),List.of());
        var again=assign(List.of(sku("1","White","L")),first);
        assertTrue(code(again.getFirst()).startsWith("WH-"+style(first.getFirst())+"-L-"));
        assertNotEquals(code(first.getFirst()),code(again.getFirst()));
    }

    @Test void repeatedSourcePatternAxisSharesPatternsAcrossColorsButSeparatesPrints() {
        var result=assign(List.of(sku("1","Black/1","M"),sku("2","Black/2","M"),
                sku("3","White/1","M"),sku("4","White/2","M"),sku("5","Pure white","M")),List.of());
        assertEquals(style(result.get(0)),style(result.get(2)));
        assertEquals(style(result.get(1)),style(result.get(3)));
        assertNotEquals(style(result.get(0)),style(result.get(1)));
        assertNotEquals(style(result.get(4)),style(result.get(0)));
        assertTrue(code(result.getFirst()).startsWith("BK-"));
    }

    @Test void explicitPrintDimensionsSeparateDifferentPatterns() {
        var bear=sku("1","Black","M");bear.put("attributes",Map.of("Color","Black","Size","M","Pattern","Bear"));
        var bearWhite=sku("2","White","L");bearWhite.put("attributes",Map.of("Color","White","Size","L","Pattern","Bear"));
        var flower=sku("3","Black","M");flower.put("attributes",Map.of("Color","Black","Size","M","Pattern","Flower"));
        var result=assign(List.of(bear,bearWhite,flower),List.of());
        assertEquals(style(result.get(0)),style(result.get(1)));
        assertNotEquals(style(result.get(0)),style(result.get(2)));
    }

    @Test void aSingleColorPrintKeepsItsCodeWhenOtherPrintsAndColorsArrive() {
        var first=assign(List.of(sku("1","Black/1","M")),List.of());
        var expanded=assign(List.of(sku("1","Black/1","M"),sku("2","Black/2","M"),
                sku("3","White/1","S")),first);
        assertEquals(code(first.getFirst()),code(expanded.getFirst()));
        assertEquals(style(expanded.get(0)),style(expanded.get(2)));
        assertNotEquals(style(expanded.get(0)),style(expanded.get(1)));
    }

    @Test void pureWhiteOptionDoesNotInheritThePrintedProductsStyle() {
        var result=assign(List.of(sku("1","White","M"),sku("2","Black","M"),
                sku("3","Pure white","M"),sku("4","纯白色","L")),List.of());
        assertEquals(style(result.get(0)),style(result.get(1)));
        assertEquals(style(result.get(2)),style(result.get(3)));
        assertNotEquals(style(result.get(0)),style(result.get(2)));
        var again=assign(List.of(sku("3","Pure white","M")),result);
        assertEquals(code(result.get(2)),code(again.getFirst()));
    }

    @Test void explicitPatternCanLinkPureWhiteToItsVerifiedPrintedGroup() {
        var a=sku("1","Pure white","M");a.put("attributes",Map.of("Color","Pure white","Size","M","Pattern","Bear"));
        var b=sku("2","Black","M");b.put("attributes",Map.of("Color","Black","Size","M","Pattern","Bear"));
        var result=assign(List.of(a,b),List.of());
        assertEquals(style(result.get(0)),style(result.get(1)));
    }

    @Test void independentProductsAndCompaniesDoNotShareUnprovenPatternIdentity() {
        var input=List.of(sku("1","Black","M"));
        var a=ProductSkuNaming.assign("org","one",input,List.of(),styles);
        var b=ProductSkuNaming.assign("org","two",input,List.of(),styles);
        var c=ProductSkuNaming.assign("other","one",input,List.of(),styles);
        assertNotEquals(style(a.getFirst()),style(b.getFirst()));
        assertNotEquals(style(a.getFirst()),style(c.getFirst()));
    }

    @Test void nonEnglishColorsAndSizeAliasesProducePortableAsciiCodes() {
        var result=assign(List.of(sku("1","黑色","均码"),sku("2","merah","XXXL")),List.of());
        assertTrue(code(result.get(0)).startsWith("BK-"));
        assertTrue(code(result.get(0)).contains("-ONESIZE-"));
        assertTrue(code(result.get(1)).startsWith("RD-"));
        assertTrue(code(result.get(1)).contains("-3XL-"));
        assertEquals("UNK",ProductSkuNaming.color("图案甲"));
    }

    @Test void colorAbbreviationsAreStableAcrossLanguagesAndKeepShadesDistinct() {
        for (String black:List.of("black","BLACK","bk"," 黑色 ","hitam"))
            assertEquals("BK",ProductSkuNaming.color(black));
        assertEquals("WH",ProductSkuNaming.color("white"));
        assertEquals("PWH",ProductSkuNaming.color("pure white"));
        assertEquals("GY",ProductSkuNaming.color("grey"));
        assertEquals("LGY",ProductSkuNaming.color("浅灰色"));
        assertEquals("DGY",ProductSkuNaming.color("dark gray"));
        assertNotEquals(ProductSkuNaming.color("ocean mist"),ProductSkuNaming.color("ocean blue"));
        assertEquals(ProductSkuNaming.color("Ocean Mist"),ProductSkuNaming.color("ocean mist"));
        assertTrue(ProductSkuNaming.color("ocean mist").length()<=8);
    }

    @Test void maximumBatchFitsBothPlatformsWithoutTruncatingTheRandomIdentifier() {
        var input=new ArrayList<Map<String,Object>>();
        for(int i=0;i<500;i++)input.add(sku(Integer.toString(i),"Exceptionally Long English Color Name","SIZE-"+i+"-LONG"));
        var result=assign(input,List.of());
        assertEquals(500,result.stream().map(this::code).distinct().count());
        assertTrue(result.stream().allMatch(row->code(row).length()<=50&&code(row).matches("[A-Z0-9-]+")));
    }

    @Test void clientProvidedNamingMetadataCannotAssignItsOwnStyleOrReuseAnArbitraryCode() {
        var source=sku("1","Black","M");
        source.put("sellerSku","BLACK-Z999999-M-12345678");
        source.put(ProductSkuNaming.METADATA,Map.of("version","1","styleCode","Z999999"));
        var result=assign(List.of(source),List.of());
        assertEquals("K01",style(result.getFirst()));
        assertNotEquals(source.get("sellerSku"),result.getFirst().get("sellerSku"));
    }
}
