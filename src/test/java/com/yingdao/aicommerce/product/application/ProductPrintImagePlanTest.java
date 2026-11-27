package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductPrintImagePlanTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProductCapture capture = mock(ProductCapture.class);

    @Test void scopedPostersAndTemplateColorsReachUploadWithOnlyMatchedSourceVariants() {
        var black = sku("black-m", "BLACK/1", "Medium", "319", 0);
        var sameBlack = sku("second-print-m", "BLACK/2", "Medium", "319", 0);
        var red = sku("red-l", "RED", "Large", "269", 8);
        var blue = sku("blue-s", "Blue", "Small", "499", 3);
        var detail = detail(List.of(black, sameBlack, red, blue));
        var plan = ProductPrintImagePlan.create(detail, Map.of("name", "风格模板", "images", List.of(
                Map.of("storageKey", "pose", "width", 1536, "height", 1024, "analysis", ProductMainImageStylePolicyTest.analysis()),
                Map.of("storageKey", "size-sheet", "width", 1024, "height", 1024, "analysis", ProductMainImageStylePolicyTest.originalAnalysis()),
                Map.of("storageKey", "detail", "width", 1024, "height", 1536, "analysis", ProductMainImageStylePolicyTest.analysis())
        )), style(), "", json);
        assertEquals(List.of("pose", "detail", "size-sheet"), plan.slots().subList(0, 3).stream()
                .map(ProductPrintImagePlan.Slot::referenceKey).toList());
        assertEquals("PRINT_1536x1024", plan.slots().getFirst().size());
        assertTrue(plan.slots().get(2).original());
        assertEquals(List.of("颜色 · 黑", "颜色 · 红"), plan.slots().subList(3, 5).stream().map(ProductPrintImagePlan.Slot::label).toList());
        assertTrue(plan.slots().get(4).prompt().contains("#81151B"));

        var upload = upload(plan, detail.skus());
        assertEquals(List.of("template:A01-BK-M-AB12CD34", "template:A01-RED-L-AB12CD34"),
                upload.stream().map(ProductCaptureSku::getSkuKey).toList());
        assertEquals(List.of("A01-BK-M-AB12CD34", "A01-RED-L-AB12CD34"),
                upload.stream().map(ProductCaptureSku::getSellerSku).toList());
        assertEquals(List.of(0, 8), upload.stream().map(ProductCaptureSku::getStock).toList());
        assertEquals(black.getPrice(), upload.getFirst().getPrice());
        assertEquals(red.getPrice(), upload.getLast().getPrice());
        assertEquals("黑", json.readTree(upload.getFirst().getAttributesJson()).get("Color").asText());
        assertEquals("Large", json.readTree(upload.getLast().getAttributesJson()).get("Size").asText());
    }

    private List<ProductCaptureSku> upload(ProductPrintImagePlan plan, List<ProductCaptureSku> sourceSkus) {
        var db = mock(JdbcTemplate.class);
        var archive = mock(CommerceImageArchive.class);
        var assets = new ProductMainImageAssets(db, archive, json, mock(ProductMainImageTemplateService.class));
        var workflow = "87654321-1234-1234-1234-123456789012";
        var request = Map.<String,Object>of("printDirectListing", true, "shopId", "shop",
                "destinationPlatform", "SHOPEE", "idempotencyKey", "wf-" + workflow + "-publish");
        when(db.queryForList(contains("AND shop_id=? AND destination_platform=?"), eq(workflow), eq("org"),
                eq("capture"), eq("shop"), eq("SHOPEE"))).thenReturn(List.of(Map.of("request_json",
                json.writeValueAsString(Map.of("printDirectListing", true, ProductPrintImagePlan.SNAPSHOT, plan)))));
        for (int index = 0; index < plan.slots().size(); index++) {
            String id = plan.jobId(capture, index, json);
            when(db.queryForList(contains("status='READY'"), eq(id), eq("org"), eq("capture")))
                    .thenReturn(List.of(Map.of("storage_key", id)));
            when(archive.read("org", id)).thenReturn(new CommerceDesignImageGateway.Reference("image/png", "aW1hZ2U="));
        }

        List<ProductCaptureImage> gallery = assets.gallery(capture, request);
        assertEquals(plan.slots().stream().map(slot -> slot.variants().isEmpty() ? "MAIN" : "VARIANT").toList(),
                gallery.stream().map(ProductCaptureImage::getRoleName).toList());
        return assets.assignPrintColorImages(sourceSkus, gallery);
    }

    @Test void missingTemplateColorInheritsSourceSizesAndOffers() {
        var detail = detail(List.of(
                sku("black-m", "BLACK", "M", "249", 0),
                sku("black-xl", "BLACK", "XL(65-75KG)", "259", 8),
                sku("white-m", "WHITE", "M", "219", 3)
        ));
        var plan = ProductPrintImagePlan.create(detail, Map.of(), style(), "", json);
        assertEquals(List.of("商品主图", "颜色 · 黑", "颜色 · 红"),
                plan.slots().stream().map(ProductPrintImagePlan.Slot::label).toList());
        var upload = upload(plan, detail.skus());
        assertEquals(List.of("A01-BK-M-AB12CD34", "A01-BK-XL-AB12CD34", "A01-RED-M-AB12CD34", "A01-RED-XL-AB12CD34"),
                upload.stream().map(ProductCaptureSku::getSellerSku).toList());
        assertEquals(4, upload.stream().map(ProductCaptureSku::getSkuKey).distinct().count());
        assertEquals(List.of(0, 8, 0, 8), upload.stream().map(ProductCaptureSku::getStock).toList());
        assertEquals(List.of(249, 259, 249, 259), upload.stream().map(sku -> sku.getPrice().intValueExact()).toList());
        assertEquals(upload.get(2).getImageUrl(), upload.get(3).getImageUrl());
        assertNotEquals(upload.getFirst().getImageUrl(), upload.getLast().getImageUrl());
    }

    @Test void missingTemplateSizeUsesSameSizeThenSameColorThenFirstSourceOffer() {
        var base = style();
        var xl = new ProductCaptureTemplateService.Size("xl", "XL", "XL", null, null, null, null);
        var xxl = new ProductCaptureTemplateService.Size("xxl", "XXL", "XXL", null, null, null, null);
        var style = new ProductCaptureTemplateService.VariantView(base.id(), base.name(), null, null, null,
                base.colors(), "A01", List.of(xl, xxl));
        var detail = detail(List.of(
                sku("white-xl", "WHITE", "XL(65-75KG)", "219", 2),
                sku("black-m", "BLACK", "M", "249", 0)
        ));
        var plan = ProductPrintImagePlan.create(detail, Map.of(), style, "", json);
        var variants = plan.slots().stream().flatMap(slot -> slot.variants().stream()).toList();
        assertEquals(List.of("white-xl", "black-m", "white-xl", "white-xl"),
                variants.stream().map(ProductPrintImagePlan.Variant::offerKey).toList());
        var upload = upload(plan, detail.skus());
        assertEquals(List.of("A01-BK-XL-AB12CD34", "A01-BK-XXL-AB12CD34", "A01-RED-XL-AB12CD34", "A01-RED-XXL-AB12CD34"),
                upload.stream().map(ProductCaptureSku::getSellerSku).toList());
        assertEquals(List.of(2, 0, 2, 2), upload.stream().map(ProductCaptureSku::getStock).toList());
        assertEquals(List.of(219, 249, 219, 219), upload.stream().map(sku -> sku.getPrice().intValueExact()).toList());
        assertEquals(Map.of("Color", "红", "Size", "XXL"),
                json.readValue(upload.getLast().getAttributesJson(), Map.class));
    }

    @Test void legacySnapshotsUseTheirExistingSourceSkuKey() {
        var old = json.readValue("""
                {"key":"black-m","sku":"A01-BK-M-AB12CD34","color":"黑","size":"M","attributes":{"Color":"黑","Size":"M"}}
                """, ProductPrintImagePlan.Variant.class);
        assertEquals("black-m", old.offerKey());
    }

    @Test void matchingSourcePrintsUsePriceAndStockFromTheFirstCollectedOffer() {
        var black = sku("black", "BLACK/1", "M", "319", 0);
        var sameBlack = sku("second-print", "BLACK/2", "M", "389", 7);
        var detail = detail(List.of(black, sameBlack));
        var plan = ProductPrintImagePlan.create(detail, Map.of(), style(), "", json);
        var upload = upload(plan, detail.skus());
        assertEquals(List.of("A01-BK-M-AB12CD34", "A01-RED-M-AB12CD34"),
                upload.stream().map(ProductCaptureSku::getSellerSku).toList());
        assertEquals(List.of(0, 0), upload.stream().map(ProductCaptureSku::getStock).toList());
        assertEquals(List.of(319, 319), upload.stream().map(sku -> sku.getPrice().intValueExact()).toList());
    }

    @Test void onlyTheTemplateSizeIsUploadedWithItsShortCode() {
        var base = style();
        var xl = new ProductCaptureTemplateService.Size("xl", "xl", "XL", null, null, null, null);
        var style = new ProductCaptureTemplateService.VariantView(base.id(), base.name(), null, null, null,
                base.colors(), "A01", List.of(xl));
        var detail = detail(List.of(
                sku("black-s", "BLACK/1", "S(35-40KG)", "319", 3),
                sku("black-xl", "BLACK/1", "XL(65-75KG)", "389", 0),
                sku("black-xl-other-print", "BLACK/2", "XL (65-75KG)", "389", 0),
                sku("red-xl", "RED", "XL(65-75KG)", "269", 8),
                sku("red-xxl", "RED", "XXL(75-85KG)", "419", 4)
        ));
        var plan = ProductPrintImagePlan.create(detail, Map.of(), style, "", json);
        var selected = plan.slots().stream().flatMap(slot -> slot.variants().stream()).toList();
        assertEquals(List.of("black-xl", "red-xl"), selected.stream().map(ProductPrintImagePlan.Variant::offerKey).toList());
        assertEquals(List.of("A01-BK-XL-AB12CD34", "A01-RED-XL-AB12CD34"),
                selected.stream().map(ProductPrintImagePlan.Variant::sku).toList());
        assertTrue(selected.stream().allMatch(sku -> "XL".equals(sku.attributes().get("Size"))));
        assertEquals("S", ProductPrintImagePlan.sizeToken("S(35-40KG)"));
        assertEquals("XXL", ProductPrintImagePlan.sizeToken("XXL(75-85KG)"));
        assertEquals("XXXL", ProductPrintImagePlan.sizeToken("XXXL(85-100KG)"));
    }

    private ProductCaptureService.CaptureDetail detail(List<ProductCaptureSku> skus) {
        when(capture.getId()).thenReturn("capture");
        when(capture.getOrganizationId()).thenReturn("org");
        when(capture.getTitle()).thenReturn("Target garment");
        when(capture.getIdempotencyKey()).thenReturn("print-production-test");
        when(capture.getRawData()).thenReturn(json.writeValueAsString(Map.of(
                "printProductionTemplateId", "capture-template", "printSkuSuffix", "AB12CD34", "printSkuOrder", "STYLE_COLOR_SIZE")));
        return new ProductCaptureService.CaptureDetail(capture, skus, List.of(), List.of(), Map.of(), Map.of());
    }

    private ProductCaptureTemplateService.VariantView style() {
        return new ProductCaptureTemplateService.VariantView("style", "Tee", null, null, null, List.of(
                new ProductCaptureTemplateService.Color("black", "黑", "#0A0A0A", "BK"),
                new ProductCaptureTemplateService.Color("red", "红", "#81151B", "RED")), "A01",
                List.of());
    }

    private ProductCaptureSku sku(String key, String color, String size, String price, int stock) {
        return new ProductCaptureSku("org", "capture", key, key, "A01-UNK-M-AB12CD34",
                color + " " + size, new BigDecimal(price), null, stock, null, "", json.writeValueAsString(
                Map.of("Color", color, "Size", size)), "{}", 0);
    }
}
