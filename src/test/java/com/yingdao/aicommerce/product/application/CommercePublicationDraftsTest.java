package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
class CommercePublicationDraftsTest {
 private byte[] image(int color)throws Exception{var i=new BufferedImage(80,100,BufferedImage.TYPE_INT_RGB);var g=i.createGraphics();g.setColor(new java.awt.Color(color));g.fillRect(0,0,80,100);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(i,"png",out);return out.toByteArray();}
 @Test void packsActualFrontAndBackWithoutMirroringOrCropping()throws Exception{
  var pair=ImageIO.read(new ByteArrayInputStream(CommercePublicationDrafts.pair(image(0xff0000),image(0x0000ff))));
  assertEquals(1600,pair.getWidth());assertEquals(1000,pair.getHeight());assertEquals(0xff0000,pair.getRGB(400,500)&0xffffff);assertEquals(0x0000ff,pair.getRGB(1200,500)&0xffffff);
 }
 @Test void invalidImageStopsPacking(){assertThrows(IllegalArgumentException.class,()->CommercePublicationDrafts.pair(new byte[12],new byte[12]));}

 @Test void descriptionKeepsTextAndSizeTablesButRemovesSourceDisplayMedia(){
  String measurements="<h3>Size chart (cm)</h3><table><tr><th>Size</th><th>Chest</th><th>Length</th></tr><tr><td>M</td><td>96</td><td>66</td></tr></table>";
  String source="<p>Soft cotton &amp; relaxed fit.</p>"+measurements
   +"<a href='https://old/product.jpg'><IMG src='https://old/product.jpg?x=1>0' /></a>"
   +"<picture><source srcset='https://old/large.jpg 2x'><img src=\"https://old/small.jpg\"></picture>"
   +"<VIDEO poster='https://old/poster.jpg'><source src='https://old/product.mp4'>Old product video</VIDEO>"
   +"<iframe src='https://old/player'>Old video embed</iframe><object data='https://old/old.svg'><img src='https://old/fallback.jpg'></object>";
  assertEquals("<p>Soft cotton &amp; relaxed fit.</p>"+measurements,CommercePublicationDrafts.publicationDescription(source));
 }

 @Test void descriptionDropsBackgroundPhotosWithoutRemovingStyledText(){
  assertEquals("<div><p style='color:black'>Size: M</p></div>",CommercePublicationDrafts.publicationDescription(
   "<div background='https://old/photo.jpg' style=\"background-image: url('https://old/photo.jpg')\"><p style='color:black'>Size: M</p></div>"));
  assertEquals("beforeafter",CommercePublicationDrafts.publicationDescription("before<embed src='old.mp4'/><video src='old.mp4'/>after"));
  assertNull(CommercePublicationDrafts.publicationDescription(null));
  assertEquals("Chest 96 cm\nLength 66 cm",CommercePublicationDrafts.publicationDescription("Chest 96 cm\nLength 66 cm"));
  assertEquals("Printed text background=image is preserved",CommercePublicationDrafts.publicationDescription("Printed text background=image is preserved"));
 }

 @Test void normalizedCopyDropsSourceMediaAndStaleChartIdWhilePreservingProductFacts(){
  var facts=java.util.Map.of("material","cotton","product condition","new");
  var source=new java.util.LinkedHashMap<String,Object>();
  for(String field:java.util.List.of("video","videos","videoUrl","video_url","videoInfo","video_list","mainVideo","images","imageUrls","skus","sizeChartImageId"))source.put(field,"old-media");
  source.put("attributes",facts);source.put("weightGrams",180);source.put("productDescription","<p>Chest 96 cm</p><img src='old.jpg'>");
  var copy=CommercePublicationDrafts.publicationData(source);
  assertEquals(java.util.Map.of("attributes",facts,"weightGrams",180,"productDescription","<p>Chest 96 cm</p>"),copy);
  assertEquals("old-media",source.get("videos"));
  assertTrue(source.get("productDescription").toString().contains("<img"));
 }

 @Test void preparingDraftRearchivesExplicitSizeChartAndRemapsItsId()throws Exception{
  var db=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  var service=org.mockito.Mockito.mock(ProductCaptureService.class);
  var captureMapper=org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureMapper.class);
  var imageMapper=org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper.class);
  var skuMapper=org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper.class);
  var archive=org.mockito.Mockito.mock(CommerceImageArchive.class);
  var downloader=org.mockito.Mockito.mock(com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader.class);
  var user=org.mockito.Mockito.mock(com.yingdao.aicommerce.auth.PlatformPrincipal.class);
  org.mockito.Mockito.when(user.organizationId()).thenReturn("org");org.mockito.Mockito.when(user.userId()).thenReturn("user");
  var json=new tools.jackson.databind.ObjectMapper();
  var source=new com.yingdao.aicommerce.product.domain.ProductCapture("org","user","source-key","fingerprint","SHOPEE","https://source/product",null,"product-id","Original title","<p>Size chart (cm): M 96 66</p><img src='https://source/product.jpg'>","T-shirts","Brand","PHP",java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,"{}","{}");
  var chart=new com.yingdao.aicommerce.product.domain.ProductCaptureImage("org",source.getId(),"https://source/chart.png","DETAIL","User selected chart",3,"image/png","{}");chart.processed("/api/v1/protected-watermark/chart");
  var originalPhoto=new com.yingdao.aicommerce.product.domain.ProductCaptureImage("org",source.getId(),"https://source/product.jpg","MAIN","Old print",0,"image/png","{}");originalPhoto.processed("https://source/product.jpg");
  source.updateDraft(source.getTitle(),source.getDescription(),source.getCategoryName(),source.getBrandName(),source.getCurrency(),source.getPriceMin(),source.getPriceMax(),json.writeValueAsString(java.util.Map.of("sizeChartImageId",chart.getId(),"videos",java.util.List.of("old.mp4"))));
  var detail=new ProductCaptureService.CaptureDetail(source,java.util.List.of(),java.util.List.of(originalPhoto,chart),java.util.List.of(),java.util.Map.of(),java.util.Map.of());
  org.mockito.Mockito.when(service.get(source.getId(),user)).thenReturn(detail);
  java.util.List<java.util.Map<String,Object>> manifest=java.util.List.of(java.util.Map.of("frontKey","front","colorLabel","Black","skuKeys",java.util.List.of()));
  org.mockito.Mockito.when(db.queryForMap("SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? FOR UPDATE","org","exec","item",1)).thenReturn(java.util.Map.of("manifest_json",json.writeValueAsString(manifest)));
  byte[] png=image(0xffffff);
  org.mockito.Mockito.when(downloader.downloadProductImage(chart.getProcessedUrl(),chart)).thenReturn(new com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader.ImageBinary(png,"image/png",".png"));
  org.mockito.Mockito.when(archive.store("org",png)).thenReturn(new CommerceImageArchive.Archived("archived-chart","hash","image/png",png.length));
  var drafts=new CommercePublicationDrafts(db,service,captureMapper,imageMapper,skuMapper,json,archive);
  org.springframework.test.util.ReflectionTestUtils.setField(drafts,"imageDownloader",downloader);
  var item=new com.yingdao.aicommerce.product.domain.CommerceBatchItem("item","org","batch",source.getId(),0,"shop","shop",0,"DRAFT",java.time.Instant.now());
  String draftId=drafts.prepare("exec",item,1,manifest,user);
  var draftCaptor=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.ProductCapture.class);
  org.mockito.Mockito.verify(captureMapper).insert(draftCaptor.capture());
  var draft=draftCaptor.getValue();
  assertEquals(draftId,draft.getId());assertEquals("Original title",draft.getTitle());assertFalse(draft.getDescription().contains("<img"));
  var imageCaptor=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.ProductCaptureImage.class);
  org.mockito.Mockito.verify(imageMapper,org.mockito.Mockito.times(2)).insert(imageCaptor.capture());
  var copied=imageCaptor.getAllValues().stream().filter(i->"SIZE_CHART".equals(i.getRoleName())).findFirst().orElseThrow();
  assertEquals(draftId,copied.getCaptureId());assertEquals(CommercePublicationDrafts.PREFIX+"archived-chart",copied.getProcessedUrl());
  assertNotEquals(chart.getId(),copied.getId());
  var normalized=json.readTree(draft.getNormalizedData());assertEquals(copied.getId(),normalized.path("sizeChartImageId").asText());assertFalse(normalized.has("videos"));
  org.mockito.Mockito.verify(downloader,org.mockito.Mockito.times(1)).downloadProductImage(chart.getProcessedUrl(),chart);
  assertEquals("/api/v1/protected-watermark/chart",chart.getProcessedUrl());
 }

 @Test void repeatedPreparationReusesTheExistingDraftWithoutCreatingAnother(){
  var db=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  var service=org.mockito.Mockito.mock(ProductCaptureService.class);
  var captures=org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureMapper.class);
  var user=org.mockito.Mockito.mock(com.yingdao.aicommerce.auth.PlatformPrincipal.class);
  org.mockito.Mockito.when(user.organizationId()).thenReturn("org");
  var json=new tools.jackson.databind.ObjectMapper();
  java.util.List<java.util.Map<String,Object>> manifest=java.util.List.of(java.util.Map.of("frontKey","front"));
  org.mockito.Mockito.when(db.queryForMap("SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? FOR UPDATE","org","exec","item",1)).thenReturn(java.util.Map.of("capture_id","existing","manifest_json",json.writeValueAsString(manifest)));
  var drafts=new CommercePublicationDrafts(db,service,captures,org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper.class),org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper.class),json,org.mockito.Mockito.mock(CommerceImageArchive.class));
  var item=new com.yingdao.aicommerce.product.domain.CommerceBatchItem("item","org","batch","source",0,"shop","shop",0,"DRAFT",java.time.Instant.now());
  assertEquals("existing",drafts.prepare("exec",item,1,manifest,user));
  org.mockito.Mockito.verifyNoInteractions(service,captures);
  assertThrows(IllegalArgumentException.class,()->drafts.prepare("exec",item,1,java.util.List.of(java.util.Map.of("frontKey","different")),user));
 }
}
