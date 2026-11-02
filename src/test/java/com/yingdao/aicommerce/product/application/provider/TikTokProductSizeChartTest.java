package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.application.CommerceImageArchive;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TikTokProductSizeChartTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path archiveDirectory;
    private static final Map<String,Object> REQUIRED = Map.of("data", Map.of("size_chart",Map.of("is_supported",true,"is_required",true)));
    private static final String REAL_TABLE = "Size (weight), length, chest circumference, sleeve length, shoulder width S(35-40kg)68cm 47cm 19cm 46cm M(40-55kg)70cm 50cm 20cm 48cm L(55-65kg)72cm 53cm 21cm 50cm XL(65-75kg)74cm 56cm 22cm 52cm XXL(75-85kg)76cm 59cm 23cm 54cm XXXL(85-100kg)78cm 62cm 24cm 56cm";
    private final TikTokShopOfficialClient client = mock(TikTokShopOfficialClient.class);
    private final MarketplaceImageDownloader downloader = mock(MarketplaceImageDownloader.class);
    private final TikTokProductSizeChart helper = new TikTokProductSizeChart(client,downloader,new ObjectMapper());
    private static final Map<String,String> SOURCE_ATTRIBUTES = Map.of(
            "Size (INT)","Shoulder(cm) Bust(cm) Sleeve Length(cm) Length(cm)",
            "L","46 98 21 65","M","44 94 20 63","XL","48 102 21 67",
            "XXL","50 106 22 69","XXXL","52 110 22 71","Material","Cotton");
    @Test void capturedAttributeTableKeepsAllSourceMeasurementsAndSortsSizes() {
        assertThat(TikTokProductSizeChart.sourceAttributeLines(SOURCE_ATTRIBUTES)).containsExactly(
                "Size (INT) Shoulder(cm) Bust(cm) Sleeve Length(cm) Length(cm)",
                "M  44 94 20 63","L  46 98 21 65","XL  48 102 21 67",
                "XXL  50 106 22 69","XXXL  52 110 22 71");
        var capture=capture("Only weight recommendations; no dimensions in description");
        capture.supplementCategory(capture.getCategoryName(),new ObjectMapper().writeValueAsString(
                Map.of("attributes",SOURCE_ATTRIBUTES)));
        stub();
        assertThat(helper.prepare(capture,List.of(),connection(),REQUIRED).sourceType()).isEqualTo("SOURCE_ATTRIBUTES");
        verifyNoInteractions(downloader);
    }
    @Test void malformedAttributeTablesNeverSupplyMissingOrInventedMeasurements() {
        for(var replacement:List.of(
                Map.of("M","44 94 20"),
                Map.of("M","0 94 20 63"),
                Map.of("M","44 94 20 63 garbage"),
                Map.of("Size (INT)","Shoulder Bust Sleeve Length Length"),
                Map.of("Size (INT)","Weight(kg) Recommended height(cm)"),
                Map.of("2XL","50 106 22 69"))) {
            var attributes=new java.util.LinkedHashMap<>(SOURCE_ATTRIBUTES);attributes.putAll(replacement);
            assertThat(TikTokProductSizeChart.sourceAttributeLines(attributes)).as(replacement.toString()).isEmpty();
        }
        assertThat(TikTokProductSizeChart.sourceAttributeLines(Map.of("S","40-50KG","M","50-55KG"))).isEmpty();
    }
    @Test void selectedBatchChartOverridesSourceAndTemplateWithTenantVerifiedBytes() throws Exception {
        var archive=new CommerceImageArchive(archiveDirectory.toString());
        var helper=new TikTokProductSizeChart(client,downloader,new ObjectMapper(),archive);
        var selected=archive.store("org-1",png(800,600).bytes());
        var capture=capture("No dimensions");
        capture.supplementCategory(capture.getCategoryName(),"{\"sizeChartImageId\":\"removed-old-image\"}");
        stub();
        var result=helper.prepare(capture,List.of(),connection(),REQUIRED,
                Map.of("sizeChartImageKey",selected.storageKey(),"listingTemplateSnapshot",
                        Map.of("enabled",true,"sizeChartImageKey","invalid-template-key")));
        assertThat(result.sourceType()).isEqualTo("SELECTED_IMAGE");
        assertThat(result.payload()).isEqualTo(Map.of("image",Map.of("uri","tos-source/size-chart")));
        verifyNoInteractions(downloader);
    }
    @Test void enabledTemplateChartIsUsedWhenNoExplicitSourceChartExists() throws Exception {
        var archive=new CommerceImageArchive(archiveDirectory.toString());
        var helper=new TikTokProductSizeChart(client,downloader,new ObjectMapper(),archive);
        var selected=archive.store("org-1",png(1200,1200).bytes());
        stub();
        var result=helper.prepare(capture("No dimensions"),List.of(),connection(),REQUIRED,
                Map.of("listingTemplateSnapshot",Map.of("enabled",true,"sizeChartImageKey",selected.storageKey())));
        assertThat(result.sourceType()).isEqualTo("TEMPLATE_IMAGE");verifyNoInteractions(downloader);
    }
    @Test void invalidOrForeignSelectedChartsFailBeforeAnyPlatformCall() throws Exception {
        var archive=new CommerceImageArchive(archiveDirectory.toString());
        var helper=new TikTokProductSizeChart(client,downloader,new ObjectMapper(),archive);
        var foreign=archive.store("other-org",png(1200,1200).bytes());
        for(String key:List.of(foreign.storageKey(),"../chart","https://example.com/chart")){
            assertThatThrownBy(()->helper.prepare(capture(REAL_TABLE),List.of(),connection(),REQUIRED,Map.of("sizeChartImageKey",key)))
                    .hasMessageContaining("所选尺码图不可读取");
        }
        verifyNoInteractions(client,downloader);
    }
    @Test void disabledTemplateChartIsNotSilentlyApplied() throws Exception {
        var archive=new CommerceImageArchive(archiveDirectory.toString());
        var helper=new TikTokProductSizeChart(client,downloader,new ObjectMapper(),archive);
        var selected=archive.store("org-1",png(1200,1200).bytes());
        assertThatThrownBy(()->helper.prepare(capture("Sizes S M L"),List.of(),connection(),REQUIRED,
                Map.of("listingTemplateSnapshot",Map.of("enabled",false,"sizeChartImageKey",selected.storageKey()))))
                .hasMessageContaining("没有可确认的尺码表");
        verifyNoInteractions(client,downloader);
    }
    @Test void originalMeasurementsSurviveListingDescriptionReplacement() {
        var capture=capture("Generic listing template without size measurements");
        capture.supplementCategory(capture.getCategoryName(),new ObjectMapper().writeValueAsString(Map.of("originalDescription",REAL_TABLE)));
        stub();
        assertThat(helper.prepare(capture,List.of(),connection(),REQUIRED).sourceType()).isEqualTo("SOURCE_MEASUREMENTS");
    }
    @Test void templateMeasurementsCannotReplaceMissingOriginalMeasurements() {
        var capture=capture(REAL_TABLE);
        capture.supplementCategory(capture.getCategoryName(),"{\"originalDescription\":\"Sizes S M L\"}");
        assertThatThrownBy(()->helper.prepare(capture,List.of(),connection(),REQUIRED)).hasMessageContaining("没有可确认的尺码表");
        verifyNoInteractions(client,downloader);
    }
    @Test void officialPayloadUsesSizeChartUploadUriAndVerifiedCaptureImage() throws Exception {
        var capture=capture("No textual measurements"); var image=image(capture,"DETAIL");
        capture.supplementCategory(capture.getCategoryName(), "{\"sizeChartImageId\":\""+image.getId()+"\"}");
        stub(); when(downloader.downloadProductImage(image.getProcessedUrl(),image)).thenReturn(png(800,600));
        var result=helper.prepare(capture,List.of(image),connection(),REQUIRED);
        assertThat(result.payload()).isEqualTo(Map.of("image",Map.of("uri","tos-source/size-chart")));
        assertThat(result.sourceImageId()).isEqualTo(image.getId());
        assertThat(result.sourceType()).isEqualTo("SOURCE_IMAGE");
        var bytes=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(client).apiMultipart(eq("/product/202309/images/upload"),any(),eq(Map.of()),eq("size-chart.png"),eq("image/png"),bytes.capture(),eq(Map.of("use_case","SIZE_CHART_IMAGE")));
        var decoded=ImageIO.read(new java.io.ByteArrayInputStream(bytes.getValue()));
        assertThat(Math.min(decoded.getWidth(),decoded.getHeight())).isGreaterThanOrEqualTo(1024);
        verify(downloader,never()).download(anyString());
    }
    @Test void exactSourceAltLabelCanIdentifyChartWithoutInventingARole() throws Exception {
        var capture=capture("No textual dimensions");
        var chart=new ProductCaptureImage("org-1",capture.getId(),"https://original.example/chart","DETAIL","Size Chart",0,"image/png","{}");
        chart.processed("/api/v1/product-watermark/assets/chart");
        stub(); when(downloader.downloadProductImage(chart.getProcessedUrl(),chart)).thenReturn(png(1200,1200));
        assertThat(helper.prepare(capture,List.of(chart),connection(),REQUIRED).sourceImageId()).isEqualTo(chart.getId());
        for(String label:List.of("size chart","SIZE GUIDE","尺码表","尺寸表","尺碼表 (cm)")) assertThat(TikTokProductSizeChart.explicitSizeChartLabel(label)).as(label).isTrue();
        for(String label:List.of("Summer T-shirt size chart","Size S M L XL","please check the size chart")) assertThat(TikTokProductSizeChart.explicitSizeChartLabel(label)).as(label).isFalse();
    }
    @Test void conflictingSourceLabelsNeedAnExplicitVerifiedSelection() {
        var capture=capture("No textual dimensions");
        var a=new ProductCaptureImage("org-1",capture.getId(),"https://original.example/a","DETAIL","Size Chart",0,"image/png","{}");
        var b=new ProductCaptureImage("org-1",capture.getId(),"https://original.example/b","DETAIL","尺码表",1,"image/png","{}");
        a.processed("/api/v1/product-watermark/assets/a");b.processed("/api/v1/product-watermark/assets/b");
        assertThatThrownBy(()->helper.prepare(capture,List.of(a,b),connection(),REQUIRED)).hasMessageContaining("多张不同尺码表");
        verifyNoInteractions(client,downloader);
    }
    @Test void extractsEveryActualMeasurementFromTheReal96SkuSingleLineDescription() {
        var lines=TikTokProductSizeChart.sourceMeasurementLines("Delivery info. "+REAL_TABLE+" Size description: (in centimeters) with an error of 1-3CM. Due to manual measurement. Other item text.");
        assertThat(lines).hasSize(8);
        assertThat(lines.getFirst()).isEqualTo("Size (weight), length, chest circumference, sleeve length, shoulder width");
        assertThat(lines.get(1)).isEqualTo("S(35-40kg)68cm 47cm 19cm 46cm");
        assertThat(lines.get(6)).isEqualTo("XXXL(85-100kg)78cm 62cm 24cm 56cm");
        assertThat(String.join(" ",lines)).doesNotContain("Delivery","Other item");
    }
    @Test void automaticTextFallbackRunsForAnyCaptureAndRendersWithoutDownloadOrInventedMeasurements() throws Exception {
        stub(); var capture=capture(REAL_TABLE);
        var result=helper.prepare(capture,List.of(),connection(),REQUIRED);
        assertThat(result.sourceType()).isEqualTo("SOURCE_MEASUREMENTS");
        assertThat(result.sourceImageId()).isNull();
        verifyNoInteractions(downloader);
        var image=TikTokProductSizeChart.renderSourceMeasurements(TikTokProductSizeChart.sourceMeasurementLines(REAL_TABLE));
        var decoded=ImageIO.read(new java.io.ByteArrayInputStream(image.bytes()));
        assertThat(Math.min(decoded.getWidth(),decoded.getHeight())).isGreaterThanOrEqualTo(1024);
    }
    @Test void supportsMultilineAnd2xlSpellingWithoutChangingSourceValues() {
        assertThat(TikTokProductSizeChart.sourceMeasurementLines("Size, Length, Chest\nXL 70cm 50cm\n2XL 72cm 52cm\n3XL 74cm 54cm"))
                .containsExactly("Size, Length, Chest","XL 70cm 50cm","2XL 72cm 52cm","3XL 74cm 54cm");
    }
    @Test void supportsChineseHeadersAndMeasurementsWithoutGuessingUnit() {
        assertThat(TikTokProductSizeChart.sourceMeasurementLines("尺码 衣长 胸围\nS 68厘米 47厘米\nM 70厘米 50厘米"))
                .containsExactly("尺码 衣长 胸围","S 68厘米 47厘米","M 70厘米 50厘米");
        assertThat(TikTokProductSizeChart.sourceMeasurementLines("尺码\nS 衣长68cm 胸围47cm\nM 衣长70cm 胸围50cm"))
                .hasSize(3);
        assertThat(TikTokProductSizeChart.sourceMeasurementLines("尺码 衣长(cm) 胸围(cm)\nS 68 47\nM 70 50"))
                .containsExactly("尺码 衣长(cm) 胸围(cm)","S 68 47","M 70 50");
        assertThat(TikTokProductSizeChart.sourceMeasurementLines("尺码 衣长 胸围\nS 68 47\nM 70 50")).isEmpty();
    }
    @Test void sizesAloneAndMarketingSizeChartMentionsAreNotMeasurements() {
        for(String text:List.of("Sizes: S, M, L, XL","Please check size chart. S 1 piece M 2 pieces", "Size S(35-40kg) M(40-55kg)","Length 68cm chest 47cm"))
            assertThat(TikTokProductSizeChart.sourceMeasurementLines(text)).as(text).isEmpty();
    }
    @Test void rejectsIncompleteRowsRatherThanPublishingOnlyTheFirstGoodRows() {
        assertThat(TikTokProductSizeChart.sourceMeasurementLines("Size, Length, Chest\nS 68cm 47cm\nM 70cm 50cm\nL 72cm")).isEmpty();
        assertThat(TikTokProductSizeChart.sourceMeasurementLines("Size, Length, Chest, Sleeve\nS 68cm 47cm 19cm\nM 70cm 50cm")).isEmpty();
    }
    @Test void ordinaryDetailIsNeverAutomaticallyUsedAsSizeChart() {
        var capture=capture("Please refer to the size chart");
        assertThatThrownBy(()->helper.prepare(capture,List.of(image(capture,"DETAIL")),connection(),REQUIRED))
                .hasMessageContaining("没有可确认的尺码表"); verifyNoInteractions(client,downloader);
    }
    @Test void unsupportedOrOptionalMissingChartDoesNotRequireFictionalData() {
        var capture=capture("No measurements");
        assertThat(helper.prepare(capture,List.of(),connection(),Map.of("size_chart",Map.of("is_supported",false,"is_required",false))).present()).isFalse();
        assertThat(helper.prepare(capture,List.of(),connection(),Map.of("size_chart",Map.of("is_supported",true,"is_required",false))).present()).isFalse();
        verifyNoInteractions(client,downloader);
    }
    @Test void staleOrForeignImageIdsCannotBypassTenantAndCaptureOwnership() {
        var capture=capture("No measurements"); var foreign=image(capture("another"),"DETAIL");
        capture.supplementCategory(capture.getCategoryName(),"{\"sizeChartImageId\":\""+foreign.getId()+"\"}");
        assertThatThrownBy(()->helper.prepare(capture,List.of(foreign),connection(),REQUIRED)).hasMessageContaining("不属于当前商品");
        assertThatThrownBy(()->helper.prepare(capture,List.of(),connection(),REQUIRED)).hasMessageContaining("不存在");
        verifyNoInteractions(client,downloader);
    }
    @Test void uploadResponseMustContainUriNotAUrl() throws Exception {
        stub();when(client.apiMultipart(anyString(),any(),anyMap(),anyString(),anyString(),any(byte[].class),anyMap())).thenReturn(Map.of("data",Map.of("url","https://example.com/chart")));
        assertThatThrownBy(()->helper.prepare(capture(REAL_TABLE),List.of(),connection(),REQUIRED)).hasMessageContaining("URI");
    }
    private void stub(){when(client.apiVersion()).thenReturn("202309");when(client.apiMultipart(anyString(),any(),anyMap(),anyString(),anyString(),any(byte[].class),eq(Map.of("use_case","SIZE_CHART_IMAGE")))).thenReturn(Map.of("data",Map.of("uri","tos-source/size-chart")));}
    private ProductCapture capture(String text){return new ProductCapture("org-1","user","key","fingerprint","SHOPEE","https://example.com/item","1","2","T-shirt",text,"T-shirts","","MYR",BigDecimal.ONE,BigDecimal.ONE,"{}","{}");}
    private ProductCaptureImage image(ProductCapture c,String role){var image=new ProductCaptureImage("org-1",c.getId(),"https://original.example/chart",role,null,0,"image/png","{}");image.processed("/api/v1/product-watermark/assets/one");return image;}
    private MarketplaceAccess.Connection connection(){return new MarketplaceAccess.Connection(new Shop("org-1",MarketplacePlatform.TIKTOK_SHOP,"external","merchant","cipher","LOCAL","Demo","MY","[]"),new MarketplaceAccess.TokenSet("fake-token","fake-refresh",Instant.now().plusSeconds(3600),null,List.of()));}
    private MarketplaceImageDownloader.ImageBinary png(int width,int height)throws Exception{var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"png",bytes);return new MarketplaceImageDownloader.ImageBinary(bytes.toByteArray(),"image/png",".png");}
}
