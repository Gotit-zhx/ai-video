package com.yingdao.aicommerce.product.application.provider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.yingdao.aicommerce.product.application.*;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.shop.infrastructure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
class ShopeeSizeChartExtractorTest {
 @Test void unitConversionUsesExactInchFactorWithoutChangingSizeLabels(){
  var input=Map.<String,Object>of("sourceUnit","in","rows",List.of(Map.of("size","S","chest",30,"length",20),Map.of("size","M","chest",32,"length",21)));
  var out=ShopeeSizeChartExtractor.normalizeMeasurements(input);
  var first=(Map<?,?>)((List<?>)out.get("rows")).getFirst();
  assertThat(first.get("chestCm")).isEqualTo(new java.math.BigDecimal("76.20"));
  assertThat(first.get("size")).isEqualTo("S");
  assertThatThrownBy(()->ShopeeSizeChartExtractor.normalizeMeasurements(Map.of("sourceUnit","unknown","rows",List.of()))).isInstanceOf(RuntimeException.class);
 }
 @Test void selectionRejectsMissingAndOutOfRangeImages(){
  assertThat(ShopeeSizeChartExtractor.selection(Map.of("found",true,"index",1),2)).isEqualTo(1);
  for(var value:List.of(Map.<String,Object>of("found",false,"index",0),Map.<String,Object>of("found",true,"index",-1),Map.<String,Object>of("found",true,"index",2),Map.<String,Object>of("found",true,"index",0.5)))assertThatThrownBy(()->ShopeeSizeChartExtractor.selection(value,2)).isInstanceOf(RuntimeException.class);
 }
 @Test void rejectsUnclearDuplicateAndImplausibleMeasurements(){
  var s=Map.of("size","S","chestCm",76,"lengthCm",53,"sleeveCm",11);
  var m=Map.of("size","M","chestCm",80,"lengthCm",54,"sleeveCm",12);
  assertThatCode(()->ShopeeSizeChartExtractor.validate(Map.of("found",true,"readable",true,"rows",List.of(s,m)))).doesNotThrowAnyException();
  assertThatThrownBy(()->ShopeeSizeChartExtractor.validate(Map.of("found",true,"readable",false,"rows",List.of(s,m)))).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->ShopeeSizeChartExtractor.validate(Map.of("found",true,"readable",true,"rows",List.of(s,s)))).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->ShopeeSizeChartExtractor.validate(Map.of("found",true,"readable",true,"rows",List.of(s,Map.of("size","M","chestCm",800,"lengthCm",54))))).isInstanceOf(RuntimeException.class);
 }
 @Test void fixedCommerceOriginalUsesAuthorizedArchive() {
  var h=new Images();String url=CommercePublicationDrafts.PREFIX+"tenant/key";var image=h.image("org","capture",url+"?original",url);
  when(h.downloader.downloadProductImage(url,image)).thenReturn(h.binary);
  assertThat(h.extractor.originalImage(h.capture,image)).containsExactly(1,2,3);
  verify(h.downloader).downloadProductImage(url,image);verify(h.downloader,never()).download(anyString());
 }
 @Test void measurementsKeepOriginalPixelsWhenAnEditedImageExists() {
  for(String edited:List.of("https://cdn.example/edited.png",CommercePublicationDrafts.PREFIX+"tenant/key")){
   var h=new Images();var image=h.image("org","capture","https://cdn.example/source-chart.png",edited);
   when(h.downloader.download(image.getOriginalUrl())).thenReturn(h.binary);
   assertThat(h.extractor.originalImage(h.capture,image)).containsExactly(1,2,3);
   verify(h.downloader).download(image.getOriginalUrl());verify(h.downloader,never()).downloadProductImage(anyString(),any());
  }
 }
 @Test void originalWatermarkArchivePrecedesCommerceFallback() {
  var h=new Images();String url=CommercePublicationDrafts.PREFIX+"tenant/key";var image=h.image("org","capture",url+"?original",url);
  when(h.db.queryForList(anyString(),eq("org"),eq("capture"),eq(image.getId()))).thenReturn(List.of(Map.of("source_key","source-key")));
  when(h.archive.read("org","source-key")).thenReturn(new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(new byte[]{4,5,6})));
  assertThat(h.extractor.originalImage(h.capture,image)).containsExactly(4,5,6);verifyNoInteractions(h.downloader);
 }
 @Test void mismatchedCommerceAliasCannotSelectProcessedPixels() {
  var h=new Images();String url=CommercePublicationDrafts.PREFIX+"tenant/key";var image=h.image("org","capture",url+"?original&other=true",url);
  when(h.downloader.download(image.getOriginalUrl())).thenThrow(new MarketplaceApiException("invalid URL"));
  assertThatThrownBy(()->h.extractor.originalImage(h.capture,image)).hasMessageContaining("invalid URL");
  verify(h.downloader,never()).downloadProductImage(anyString(),any());
 }
 @Test void imageOutsideCaptureOrTenantCannotReachArchiveOrNetwork() {
  for(String[] owner:List.of(new String[]{"other-org","capture"},new String[]{"org","other-capture"})){
   var h=new Images();var image=h.image(owner[0],owner[1],"https://cdn.example/source-chart.png",null);
   assertThatThrownBy(()->h.extractor.originalImage(h.capture,image)).hasMessageContaining("归属不一致");
   verifyNoInteractions(h.archive,h.downloader,h.db);
  }
 }
 static class Images {
  final MarketplaceImageDownloader downloader=mock(MarketplaceImageDownloader.class);
  final CommerceImageArchive archive=mock(CommerceImageArchive.class);final JdbcTemplate db=mock(JdbcTemplate.class);
  final ProductCapture capture=mock(ProductCapture.class);
  final MarketplaceImageDownloader.ImageBinary binary=new MarketplaceImageDownloader.ImageBinary(new byte[]{1,2,3},"image/png",".png");
  final ShopeeSizeChartExtractor extractor=new ShopeeSizeChartExtractor(null,downloader,null,new ObjectMapper(),db,archive,"http://unused.invalid","unused",System.getProperty("java.io.tmpdir"));
  Images(){when(capture.getOrganizationId()).thenReturn("org");when(capture.getId()).thenReturn("capture");}
  ProductCaptureImage image(String org,String capture,String original,String processed){var image=new ProductCaptureImage(org,capture,original,"SIZE_CHART","",0,"image/png","{}");if(processed!=null)image.processed(processed);return image;}
 }
}
