package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
class CommercePublicationDraftsTest {
 @Test void publicationSkuOrderFollowsSelectedColorsAndSizesAfterReplacingSourceStyles(){
  var json=new tools.jackson.databind.ObjectMapper();
  var drafts=new CommercePublicationDrafts(null,null,null,null,null,json,null);
  var source=new java.util.ArrayList<com.yingdao.aicommerce.product.domain.ProductCaptureSku>();
  int order=0;
  for(String color:java.util.List.of("Pink/2","Black","Pink/1"))
   for(String size:java.util.List.of("M(40-55KG)","S(35-40KG)"))
    source.add(new com.yingdao.aicommerce.product.domain.ProductCaptureSku("org","source",color+"-"+size,null,color+"-"+size,color+" / "+size,
      java.math.BigDecimal.valueOf(263),java.math.BigDecimal.valueOf(538),10,"source","SKU",
      json.writeValueAsString(java.util.Map.of("Size",size,"Color",color)),"{}",order++));
  var manifest=java.util.List.<java.util.Map<String,Object>>of(
    java.util.Map.of("colorLabel","Pink","skuKeys",java.util.List.of("Pink/2-M(40-55KG)","Pink/1-M(40-55KG)","Pink/2-S(35-40KG)","Pink/1-S(35-40KG)")),
    java.util.Map.of("colorLabel","Black","skuKeys",java.util.List.of("Black-M(40-55KG)","Black-S(35-40KG)")));
  var urls=new java.util.HashMap<String,String>();
  source.forEach(sku->urls.put(sku.getSkuKey(),sku.getSkuKey().startsWith("Pink")?"pink-image":"black-image"));
  var planned=drafts.plannedSkus(source,manifest,urls);
  assertEquals(4,planned.size());
  assertEquals(java.util.List.of("Pink / S(35-40KG)","Pink / M(40-55KG)","Black / S(35-40KG)","Black / M(40-55KG)"),
    planned.stream().map(CommercePublicationDrafts.PlannedSku::name).toList());
  assertEquals(java.util.List.of(0,1,2,3),planned.stream().map(CommercePublicationDrafts.PlannedSku::order).toList());
  assertTrue(planned.getFirst().source().getSkuKey().startsWith("Pink/1"));
  assertEquals("Pink",json.readTree(planned.getFirst().attributesJson()).get("Color").asText());
  assertEquals("pink-image",planned.getFirst().imageUrl());
  var declaration=drafts.generatedSkuCompleteness(planned);
  assertEquals(4,declaration.get("expectedCount"));
  assertEquals(4,declaration.get("capturedCount"));
  assertEquals(2,declaration.get("variantDimensionCount"));
  assertEquals(true,declaration.get("complete"));
  assertEquals("COMMERCE_GENERATED",declaration.get("source"));
 }
 @Test void existingDraftChangedManifestDelegatesOnlyToExplicitRestyleGuard(){
  var db=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  var json=new tools.jackson.databind.ObjectMapper();var helper=org.mockito.Mockito.mock(CommerceDetailRestyleRevision.class);
  var drafts=new CommercePublicationDrafts(db,null,null,null,null,json,null);
  org.springframework.test.util.ReflectionTestUtils.setField(drafts,"restyles",helper);
  var user=org.mockito.Mockito.mock(com.yingdao.aicommerce.auth.PlatformPrincipal.class);org.mockito.Mockito.when(user.organizationId()).thenReturn("org");
  var item=new com.yingdao.aicommerce.product.domain.CommerceBatchItem("item","org","batch","source",1,"shop","shop",0,"DRAFT",java.time.Instant.now());
  var row=java.util.Map.<String,Object>of("capture_id","existing-draft","manifest_json","[{\"frontKey\":\"old\"}]");
  java.util.List<java.util.Map<String,Object>> changed=java.util.List.of(java.util.Map.of("frontKey","new"));
  org.mockito.Mockito.when(db.queryForMap("SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? FOR UPDATE","org","run","item",1)).thenReturn(row);
  org.mockito.Mockito.when(helper.refresh("org","run","item",1,row,changed)).thenReturn("existing-draft");
  assertEquals("existing-draft",drafts.prepare("run",item,1,changed,user));
  var order=org.mockito.Mockito.inOrder(helper,db);order.verify(helper).lockExecution("run","org");
  order.verify(db).update(org.mockito.ArgumentMatchers.startsWith("INSERT IGNORE INTO commerce_batch_publications"),org.mockito.ArgumentMatchers.any(Object[].class));
  order.verify(db).queryForMap("SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? FOR UPDATE","org","run","item",1);
  order.verify(helper).refresh("org","run","item",1,row,changed);
 }
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
  assertEquals(2,normalized.path("imageCompleteness").path("capturedCount").asInt());
  assertEquals(1,normalized.path("imageCompleteness").path("requiredSkuImageCount").asInt());
  assertEquals(0,normalized.path("imageCompleteness").path("requiredDetailImageCount").asInt());
  org.mockito.Mockito.verify(downloader,org.mockito.Mockito.times(1)).downloadProductImage(chart.getProcessedUrl(),chart);
  assertEquals("/api/v1/protected-watermark/chart",chart.getProcessedUrl());
 }

 @Test void packsOrderedGeneratedDetailsOnceAheadOfColorGalleryWithoutChangingSkuImages(){
  for(int detailCount:java.util.List.of(8,12)){
  var h=new DraftHarness();
  var detailKeys=java.util.stream.IntStream.rangeClosed(1,detailCount).mapToObj(i->"generated-detail-"+i).toList();
  java.util.List<java.util.Map<String,Object>> manifest=java.util.List.of(
   java.util.Map.of("frontKey","black-front","colorLabel","Black","skuKeys",java.util.List.of("black-sku"),"detailKeys",detailKeys),
   java.util.Map.of("frontKey","white-front","colorLabel","White","skuKeys",java.util.List.of("white-sku")),
   java.util.Map.of("frontKey","pink-front","colorLabel","Pink","skuKeys",java.util.List.of("pink-sku")));
  h.prepare(manifest);
  var imageCaptor=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.ProductCaptureImage.class);
  org.mockito.Mockito.verify(h.images,org.mockito.Mockito.times(detailCount+3)).insert(imageCaptor.capture());
  var packed=imageCaptor.getAllValues();
  var details=packed.stream().filter(i->"DETAIL".equals(i.getRoleName())).toList();
  assertEquals(detailKeys.stream().map(key->CommercePublicationDrafts.PREFIX+key).toList(),details.stream().map(com.yingdao.aicommerce.product.domain.ProductCaptureImage::getProcessedUrl).toList());
  assertEquals(java.util.stream.IntStream.rangeClosed(1,detailCount).boxed().toList(),details.stream().map(com.yingdao.aicommerce.product.domain.ProductCaptureImage::getSortOrder).toList());
  var gallery=packed.stream().sorted(java.util.Comparator.comparingInt(com.yingdao.aicommerce.product.domain.ProductCaptureImage::getSortOrder)).limit(9).toList();
  assertEquals("MAIN",gallery.getFirst().getRoleName());assertEquals("black-front",gallery.getFirst().getProcessedUrl().substring(CommercePublicationDrafts.PREFIX.length()));
  assertEquals(details.subList(0,8),gallery.subList(1,9));
  assertEquals(detailCount+3,packed.stream().map(com.yingdao.aicommerce.product.domain.ProductCaptureImage::getSortOrder).distinct().count());
  assertTrue(packed.stream().noneMatch(i->i.getOriginalUrl().contains("old-detail")));
  var skuCaptor=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.ProductCaptureSku.class);
  org.mockito.Mockito.verify(h.skus,org.mockito.Mockito.times(3)).insert(skuCaptor.capture());
  for(var sku:skuCaptor.getAllValues())assertEquals(CommercePublicationDrafts.PREFIX+sku.getSkuKey().replace("-sku","-front")+"?original",sku.getImageUrl());
  var captureCaptor=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.ProductCapture.class);
  org.mockito.Mockito.verify(h.captures).insert(captureCaptor.capture());
  var complete=h.json.readTree(captureCaptor.getValue().getNormalizedData()).path("imageCompleteness");
  assertEquals(detailCount+3,complete.path("expectedCount").asInt());assertEquals(detailCount+3,complete.path("capturedCount").asInt());
  assertEquals(3,complete.path("requiredSkuImageCount").asInt());assertEquals(3,complete.path("coveredSkuImageCount").asInt());
  assertEquals(detailCount,complete.path("requiredDetailImageCount").asInt());assertEquals(detailCount,complete.path("coveredDetailImageCount").asInt());
  org.mockito.Mockito.verifyNoInteractions(h.archive);
  }
 }

 @Test void preparingDraftPreservesDetectionOnlySizeChartWithoutTurningItIntoAModelDetail()throws Exception{
  var h=new DraftHarness();var source=h.service.get(h.item.captureId(),h.user);
  var chart=source.images().getFirst();
  assertEquals("DETAIL",chart.getRoleName());assertEquals("Old product details",chart.getAltText());
  var ordinary=new com.yingdao.aicommerce.product.domain.ProductCaptureImage("org",source.capture().getId(),"https://ordinary-detail","DETAIL","A close-up photo",4,"image/png","{}");ordinary.processed("https://ordinary-detail");
  org.mockito.Mockito.when(h.service.get(h.item.captureId(),h.user)).thenReturn(new ProductCaptureService.CaptureDetail(source.capture(),source.skus(),java.util.List.of(chart,ordinary),java.util.List.of(),java.util.Map.of(),java.util.Map.of()));
  String detectionSql="SELECT DISTINCT image_id FROM product_watermark_checks WHERE organization_id=? AND capture_id=? AND status='READY' AND JSON_UNQUOTE(JSON_EXTRACT(detection_json,'$.imageType'))='SIZE_CHART'";
  org.mockito.Mockito.when(h.db.queryForList(detectionSql,String.class,"org",source.capture().getId())).thenReturn(java.util.List.of(chart.getId(),"stale-or-other-source-image"));
  var downloader=org.mockito.Mockito.mock(com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader.class);
  org.springframework.test.util.ReflectionTestUtils.setField(h.drafts,"imageDownloader",downloader);
  byte[] png=image(0x102030);
  org.mockito.Mockito.when(downloader.downloadProductImage(chart.getProcessedUrl(),chart)).thenReturn(new com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader.ImageBinary(png,"image/png",".png"));
  org.mockito.Mockito.when(h.archive.store("org",png)).thenReturn(new CommerceImageArchive.Archived("detected-chart","hash","image/png",png.length));
  java.util.List<java.util.Map<String,Object>> manifest=java.util.List.of(
   java.util.Map.of("frontKey","black-front","colorLabel","Black","skuKeys",java.util.List.of("black-sku"),"detailKeys",java.util.List.of("model-detail-1","model-detail-2")),
   java.util.Map.of("frontKey","white-front","colorLabel","White","skuKeys",java.util.List.of("white-sku")),
   java.util.Map.of("frontKey","pink-front","colorLabel","Pink","skuKeys",java.util.List.of("pink-sku")));
  String draftId=h.prepare(manifest);
  org.mockito.Mockito.verify(h.db).queryForList(detectionSql,String.class,"org",source.capture().getId());
  var captor=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.ProductCaptureImage.class);
  org.mockito.Mockito.verify(h.images,org.mockito.Mockito.times(6)).insert(captor.capture());
  var packed=captor.getAllValues();var copied=packed.stream().filter(i->"SIZE_CHART".equals(i.getRoleName())).findFirst().orElseThrow();
  assertEquals(draftId,copied.getCaptureId());assertEquals(5,copied.getSortOrder());
  assertEquals(CommercePublicationDrafts.PREFIX+"detected-chart",copied.getProcessedUrl());
  assertNotEquals(chart.getId(),copied.getId());assertEquals(chart.getAltText(),copied.getAltText());
  assertEquals(java.util.List.of(CommercePublicationDrafts.PREFIX+"model-detail-1",CommercePublicationDrafts.PREFIX+"model-detail-2"),packed.stream().filter(i->"DETAIL".equals(i.getRoleName())).map(com.yingdao.aicommerce.product.domain.ProductCaptureImage::getProcessedUrl).toList());
  org.mockito.Mockito.verify(downloader).downloadProductImage(chart.getProcessedUrl(),chart);
  org.mockito.Mockito.verifyNoMoreInteractions(downloader);
  org.mockito.Mockito.verify(h.archive).store("org",png);org.mockito.Mockito.verifyNoMoreInteractions(h.archive);
  assertEquals("DETAIL",chart.getRoleName());assertEquals("https://old-detail",chart.getProcessedUrl());
  var draftCaptor=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.ProductCapture.class);
  org.mockito.Mockito.verify(h.captures).insert(draftCaptor.capture());
  var complete=h.json.readTree(draftCaptor.getValue().getNormalizedData()).path("imageCompleteness");
  assertEquals(6,complete.path("capturedCount").asInt());assertEquals(2,complete.path("requiredDetailImageCount").asInt());
 }

 @Test void detectedSizeChartsRemainAlongsideAnExplicitSelectionAndAreDeduplicated(){
  var h=new DraftHarness();var source=h.service.get(h.item.captureId(),h.user);var selected=source.images().getFirst();
  var detected=new com.yingdao.aicommerce.product.domain.ProductCaptureImage("org",source.capture().getId(),"https://detected-chart","DETAIL","Image 4",4,"image/png","{}");detected.processed("https://detected-chart");
  var duplicate=new com.yingdao.aicommerce.product.domain.ProductCaptureImage("org",source.capture().getId(),"https://detected-chart?copy","DETAIL","Image 5",5,"image/png","{}");duplicate.processed("https://detected-chart");
  var detail=new ProductCaptureService.CaptureDetail(source.capture(),source.skus(),java.util.List.of(selected,detected,duplicate),java.util.List.of(),java.util.Map.of(),java.util.Map.of());
  assertEquals(java.util.List.of(selected),CommercePublicationDrafts.sourceSizeCharts(detail,selected.getId()),"legacy explicit-selection behavior remains available");
  assertEquals(java.util.List.of(selected,detected),CommercePublicationDrafts.sourceSizeCharts(detail,selected.getId(),java.util.Set.of(detected.getId(),duplicate.getId(),"missing")));
  assertEquals(java.util.List.of(detected),CommercePublicationDrafts.sourceSizeCharts(detail,null,java.util.Set.of(detected.getId(),duplicate.getId())));
 }

 @Test void detectedChartStillRequiresOwnershipAndCompletedImageProcessing(){
  var h=new DraftHarness();var source=h.service.get(h.item.captureId(),h.user);
  for(String[] identity:java.util.List.of(new String[]{"foreign-org",source.capture().getId()},new String[]{"org","foreign-capture"},new String[]{"org",source.capture().getId()})){
   var chart=new com.yingdao.aicommerce.product.domain.ProductCaptureImage(identity[0],identity[1],"https://chart","DETAIL","Image",1,"image/png","{}");
   boolean owned="org".equals(identity[0])&&source.capture().getId().equals(identity[1]);
   if(!owned)chart.processed("https://chart");
   var detail=new ProductCaptureService.CaptureDetail(source.capture(),source.skus(),java.util.List.of(chart),java.util.List.of(),java.util.Map.of(),java.util.Map.of());
   var error=assertThrows(IllegalArgumentException.class,()->CommercePublicationDrafts.sourceSizeCharts(detail,null,java.util.Set.of(chart.getId())));
   assertTrue(error.getMessage().contains(owned?"尚未处理完成":"不属于来源商品"),error.getMessage());
  }
 }

 @Test void rejectsRepeatedDetailsAcrossColorsAndInvalidDetailKeysBeforeCreatingCapture(){
  for(Object invalid:java.util.List.of(java.util.List.of("detail","detail"),java.util.List.of(" "),"detail",java.util.List.of(1))){
   var h=new DraftHarness();
   assertThrows(IllegalArgumentException.class,()->h.prepare(java.util.List.of(java.util.Map.of("frontKey","front","detailKeys",invalid))));
   org.mockito.Mockito.verifyNoInteractions(h.captures,h.images,h.skus);
  }
  var h=new DraftHarness();
  assertThrows(IllegalArgumentException.class,()->h.prepare(java.util.List.of(java.util.Map.of("frontKey","front","detailKeys",java.util.List.of("one")),java.util.Map.of("frontKey","other","detailKeys",java.util.List.of("two")))));
  org.mockito.Mockito.verifyNoInteractions(h.captures,h.images,h.skus);
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

 private static final class DraftHarness{
  final org.springframework.jdbc.core.JdbcTemplate db=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  final ProductCaptureService service=org.mockito.Mockito.mock(ProductCaptureService.class);
  final com.yingdao.aicommerce.product.mapper.ProductCaptureMapper captures=org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureMapper.class);
  final com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper images=org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper.class);
  final com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper skus=org.mockito.Mockito.mock(com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper.class);
  final CommerceImageArchive archive=org.mockito.Mockito.mock(CommerceImageArchive.class);
  final com.yingdao.aicommerce.auth.PlatformPrincipal user=org.mockito.Mockito.mock(com.yingdao.aicommerce.auth.PlatformPrincipal.class);
  final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
  final CommercePublicationDrafts drafts=new CommercePublicationDrafts(db,service,captures,images,skus,json,archive);
  final com.yingdao.aicommerce.product.domain.CommerceBatchItem item;
  DraftHarness(){
   org.mockito.Mockito.when(user.organizationId()).thenReturn("org");org.mockito.Mockito.when(user.userId()).thenReturn("user");
   var source=new com.yingdao.aicommerce.product.domain.ProductCapture("org","user","source-key","fingerprint","SHOPEE","https://source/product",null,"product-id","Original title","Product text","T-shirts","Brand","PHP",java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,"{}","{}");
   var sourceSkus=java.util.List.of("black","white","pink").stream().map(color->new com.yingdao.aicommerce.product.domain.ProductCaptureSku("org",source.getId(),color+"-sku",color+"-external",color+"-seller",color,java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,10,"https://old-"+color,"CAPTURED","{}","{}",0)).toList();
   var oldDetail=new com.yingdao.aicommerce.product.domain.ProductCaptureImage("org",source.getId(),"https://old-detail","DETAIL","Old product details",2,"image/png","{}");oldDetail.processed("https://old-detail");
   org.mockito.Mockito.when(service.get(source.getId(),user)).thenReturn(new ProductCaptureService.CaptureDetail(source,sourceSkus,java.util.List.of(oldDetail),java.util.List.of(),java.util.Map.of(),java.util.Map.of()));
   item=new com.yingdao.aicommerce.product.domain.CommerceBatchItem("item","org","batch",source.getId(),0,"shop","shop",0,"DRAFT",java.time.Instant.now());
  }
  String prepare(java.util.List<java.util.Map<String,Object>> manifest){
   org.mockito.Mockito.when(db.queryForMap("SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? FOR UPDATE","org","exec","item",1)).thenReturn(java.util.Map.of("manifest_json",json.writeValueAsString(manifest)));
   return drafts.prepare("exec",item,1,manifest,user);
  }
 }
}
