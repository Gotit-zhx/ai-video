package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CommerceSampleServiceTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path archiveDirectory;
 @Test void mockupSeparatesBaseFromPositionGuideAndPreservesWhiteDesign(){String p=CommerceSampleService.mockupPrompt("FRONT");assertTrue(p.contains("图片1是唯一商品底图"));assertTrue(p.contains("图片2"));assertTrue(p.contains("纯白矩形是画布背景"));assertTrue(p.contains("不要把图案内部"));assertTrue(p.contains("同一位置、大小"));assertThrows(RuntimeException.class,()->CommerceSampleService.mockupPrompt("SIDE"));}
 @Test void blankPromptPreservesShapeAndDoesNotApplyNewArtwork(){String p=CommerceSampleService.blankPrompt();assertTrue(p.contains("旧装饰印花及叠加水印"));assertTrue(p.contains("尺码标记"));assertTrue(p.contains("斜肩不得变成无袖"));assertTrue(p.contains("禁止把新印花贴上去"));assertTrue(p.contains("需要检查"));}
 @Test void extractedPrintRequestsAnActualTransparentArtworkWithoutThePhotographedProduct(){
  var p=CommerceSampleService.printPrompt("PRINT_EXTRACT",0);
  assertTrue(p.contains("校正透视"));assertTrue(p.contains("真实 alpha 透明通道"));assertTrue(p.contains("不带底图"));
  assertTrue(p.contains("不得把实物衣服、抱枕"));assertTrue(p.contains("图案角色自身的服装和道具属于设计内容"));
  assertTrue(p.contains("保留原设计本来具有的白色填充、白色文字"));
  assertFalse(p.contains("干净纯白背景"));assertFalse(p.contains("不代表透明图"));
 }
 @Test void variationWithoutParentCannotReachModel(){
  var models=org.mockito.Mockito.mock(com.yingdao.aicommerce.saas.application.ModelConfigService.class);
  var service=new CommerceSampleService(null,models,null,null,null,null);
  var user=new com.yingdao.aicommerce.auth.PlatformPrincipal("u","org","c","u","u","",true,java.util.List.of(),java.util.List.of("product:edit"));
  assertThrows(RuntimeException.class,()->service.generate(new CommerceSampleService.Request("CUSHION",1,true,null),"sample-test-key",user));
  org.mockito.Mockito.verifyNoInteractions(models);
 }
 @Test void fixedPromptsRejectUnknownProductsAndVariants(){assertThrows(RuntimeException.class,()->CommerceSampleService.prompt("bad",0));assertThrows(RuntimeException.class,()->CommerceSampleService.prompt("CUSHION",4));}
 @Test void variantsRemainSingleOriginalExample(){for(int i=0;i<4;i++){var prompt=CommerceSampleService.prompt("CUSHION",i);assertTrue(prompt.contains("禁止拼贴、九宫格"));assertTrue(prompt.contains("概念示例"));}assertNotEquals(CommerceSampleService.prompt("CUSHION",0),CommerceSampleService.prompt("CUSHION",1));}

 @Test void sourceStyleVariationsCanChangeSpeciesWithoutForcingOriginalSubjectOrOnlyRecoloring(){
  for(int i=0;i<4;i++){
   String prompt=CommerceSampleService.printPrompt("PRINT_VARIANT",i);
   assertTrue(prompt.contains("来源商品"));assertTrue(prompt.contains("只作为画风参考"));
   assertTrue(prompt.contains("必须进行大幅自由二创"));
   assertTrue(prompt.contains("至少同时重设计核心主体或形状、画面构图骨架"));
   assertFalse(prompt.contains("保留原设计本来具有的白色填充"));
   assertTrue(prompt.contains("猫可以变成狗"));assertTrue(prompt.contains("画风与原印花统一"));
   assertFalse(prompt.contains("适度二创"));assertFalse(prompt.contains("保留可辨识的主题"));
   assertFalse(prompt.contains("本次侧重暖色配色"));
   assertTrue(prompt.contains("不要画表示透明背景的棋盘底格"));
   assertTrue(prompt.contains("原印花设计自身的格纹或棋盘元素可以作为图案"));
   assertTrue(prompt.contains("印花 PNG"));assertTrue(prompt.contains("真实 alpha 透明通道"));
   assertTrue(prompt.contains("没有设计内容的空隙应透明"));
   assertTrue(prompt.contains("不要额外绘制白色底板"));
   assertTrue(prompt.contains("不要把这些白色设计元素当成背景删除"));
   assertTrue(prompt.contains("图案本身可有卡通明暗层次"));
   assertFalse(prompt.contains("干净纯白背景"));assertFalse(prompt.contains("不得包含衣服"));
   assertFalse(prompt.contains("不代表透明图"));
  }
 assertThrows(RuntimeException.class,()->CommerceSampleService.printPrompt("BAD",1));
 }

 @Test void batchVariationBriefsDoNotRepeatEveryThreeDesignsAndGroupsTakeDifferentDirections(){
  for(int group=1;group<=3;group++){
   var directions=new java.util.HashSet<String>();
   for(int variant=1;variant<=20;variant++){
    assertTrue(directions.add(CommerceBatchArtworkService.variationDirection(variant,group)));
    assertTrue(CommerceBatchArtworkService.groupVariationPrompt(variant,group).contains("真实 alpha 透明通道"));
   }
   assertEquals(20,directions.size());
  }
  assertNotEquals(CommerceBatchArtworkService.variationDirection(1,1),CommerceBatchArtworkService.variationDirection(4,1));
  assertNotEquals(CommerceBatchArtworkService.variationDirection(1,1),CommerceBatchArtworkService.variationDirection(1,2));
  assertTrue(CommerceBatchArtworkService.surfacePrompt("FRONT").contains("不得再次更换图案主体"));
  assertThrows(IllegalArgumentException.class,()->CommerceBatchArtworkService.variationPrompt(21));
 }

 @Test void paidOpaquePrintIsArchivedBeforeRejectionAndSameRequestNeverCallsTheModelAgain()throws Exception{
  for(String kind:java.util.List.of("PRINT_EXTRACT","PRINT_VARIANT")){
   var fixture=new ReceivedSample(kind,png(false,false),archiveDirectory.resolve(kind));
   String received=(String)fixture.db.row.get("result_json");
   var rejected=assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,fixture::replay);
   assertTrue(rejected.getMessage().contains("原始生成结果已归档保留"));assertTrue(rejected.getMessage().contains("不会再次调用生图模型"));
   assertEquals("TRANSPARENCY_REJECTED",fixture.db.row.get("status"));assertNotNull(fixture.db.row.get("storage_key"));
   assertTrue(fixture.db.writes.getFirst().contains("SET storage_key="));
   assertArrayEquals(fixture.bytes,java.util.Base64.getDecoder().decode(fixture.archive.read("org",(String)fixture.db.row.get("storage_key")).dataBase64()));
   assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,fixture::replay);
   assertEquals(received,fixture.db.row.get("result_json"));
   assertEquals(1,fixture.db.writes.stream().filter(sql->sql.contains("SET storage_key=")).count());
   assertTrue(fixture.db.writes.stream().noneMatch(sql->sql.contains("SET status='READY'")));
   org.mockito.Mockito.verifyNoInteractions(fixture.models,fixture.gateway,fixture.downloader);
  }
 }

 @Test void genuinelyTransparentPrintPassesWithoutRemovingWhiteArtworkAndOrdinaryOpaquePhotoIsUnaffected()throws Exception{
  var print=new ReceivedSample("PRINT_VARIANT",png(true,false),archiveDirectory.resolve("print"));
  assertEquals("READY",print.replay().status());
  assertArrayEquals(print.bytes,java.util.Base64.getDecoder().decode(print.service.printImage("sample",print.user).dataBase64()));
  var photograph=new ReceivedSample("PRODUCT_IMAGE",png(false,false),archiveDirectory.resolve("photo"));
  assertEquals("READY",photograph.replay().status());assertNotNull(photograph.replay().imageUrl());
  org.mockito.Mockito.verifyNoInteractions(print.gateway,photograph.gateway);
 }

 @Test void emptyTransparentPrintCannotBeReadyAndStoredFailureRemainsReadableWithoutAnImageClaim()throws Exception{
  var fixture=new ReceivedSample("PRINT_VARIANT",png(true,true),archiveDirectory.resolve("empty"));
  assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,fixture::replay);
  assertEquals("TRANSPARENCY_REJECTED",fixture.db.row.get("status"));
  assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->fixture.service.printImage("sample",fixture.user));
  var view=new CommerceSampleService.View("sample","CUSHION",1,"TRANSPARENCY_REJECTED","model",null,"parent","PRINT_VARIANT");
  assertTrue(view.message().contains("原生透明 PNG"));assertNull(view.imageUrl());
 }

 @Test void archiveFailureIsNotRelabelledAsTransparencyRejectionAndRetainsPaidResponse()throws Exception{
  var fixture=new ReceivedSample("PRINT_VARIANT",png(false,false),archiveDirectory.resolve("storage-failure"));
  var failedArchive=org.mockito.Mockito.mock(CommerceImageArchive.class);
  org.mockito.Mockito.when(failedArchive.store(org.mockito.ArgumentMatchers.eq("org"),org.mockito.ArgumentMatchers.any(byte[].class))).thenThrow(new IllegalStateException("磁盘不可用"));
  var service=new CommerceSampleService(fixture.db,fixture.models,fixture.gateway,failedArchive,fixture.downloader,fixture.json);
  assertEquals("磁盘不可用",assertThrows(IllegalStateException.class,()->service.generate(fixture.request,"sample-request",fixture.user)).getMessage());
  assertEquals("RESULT_RECEIVED",fixture.db.row.get("status"));assertFalse(fixture.db.row.containsKey("storage_key"));
  assertTrue(fixture.db.writes.isEmpty());assertNotNull(fixture.db.row.get("result_json"));
  org.mockito.Mockito.verifyNoInteractions(fixture.models,fixture.gateway,fixture.downloader);
 }

 @Test void replayOfDefiniteTransparentCapabilityRefusalPreservesReasonWithoutModelCalls()throws Exception{
  var fixture=new ReceivedSample("PRINT_VARIANT",png(true,false),archiveDirectory.resolve("refused"));
  fixture.db.row.put("status","REJECTED");fixture.db.row.put("result_json","{\"message\":\"当前图片模型不支持原生透明 PNG，未调用生图\"}");
  var failure=assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,fixture::replay);
  assertTrue(failure.getMessage().contains("不支持原生透明 PNG"));assertEquals("REJECTED",fixture.db.row.get("status"));
  assertTrue(fixture.db.writes.isEmpty());org.mockito.Mockito.verifyNoInteractions(fixture.models,fixture.gateway,fixture.downloader);
 }

 @Test void uploadedPrintsUseImg2OnlyAndNeverReceiveTheBlankGarmentPrompt()throws Exception{
  for(String kind:java.util.List.of("PRINT_EXTRACT","PRINT_VARIANT")){
   var fixture=new SubmittedSample("UPLOADED",kind,archiveDirectory.resolve("submitted-"+kind));
   var result=fixture.submit();
   assertEquals("READY",result.status());assertEquals("gpt-image-2",result.model());
   assertTrue(fixture.db.row.get("prompt").toString().contains("真实 alpha 透明通道"));
   assertFalse(fixture.db.row.get("prompt").toString().contains("像隐形人台自然撑起"));
   verify(fixture.models).transparentArtworkConnection("org");verify(fixture.models,never()).imageConnection(anyString());
   verify(fixture.gateway).generateTransparentArtwork(anyString(),anyString(),same(fixture.img2),anyList());
   verify(fixture.gateway,never()).generateSample(anyString(),anyString(),any(),anyList());
   assertEquals("READY",fixture.submit().status());verifyNoMoreInteractions(fixture.models,fixture.gateway);
  }
 }

 @Test void ordinaryPhotosAndUploadedBlankGarmentsKeepTheirExistingImageModel()throws Exception{
  for(String kind:java.util.List.of("PRODUCT_IMAGE","BLANK_PRODUCT")){
   var fixture=new SubmittedSample(kind.equals("BLANK_PRODUCT")?"UPLOADED":"CUSHION",kind,archiveDirectory.resolve("photo-"+kind));
   assertEquals("READY",fixture.submit().status());assertEquals("default-image-model",fixture.db.row.get("model_id"));
   verify(fixture.models).imageConnection("org");verify(fixture.models,never()).transparentArtworkConnection(anyString());
   verify(fixture.gateway).generateSample(anyString(),anyString(),same(fixture.standard),anyList());
   verify(fixture.gateway,never()).generateTransparentArtwork(anyString(),anyString(),any(),anyList());
   if(kind.equals("BLANK_PRODUCT"))assertTrue(fixture.db.row.get("prompt").toString().contains("像隐形人台自然撑起"));
  }
 }

 @Test void missingImg2ConnectionDoesNotFallBackOrStartASampleAttempt()throws Exception{
  var fixture=new SubmittedSample("UPLOADED","PRINT_EXTRACT",archiveDirectory.resolve("missing-img2"));
  when(fixture.models.transparentArtworkConnection("org")).thenReturn(java.util.Optional.empty());
  var failure=assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,fixture::submit);
  assertTrue(failure.getMessage().contains("IMG2.0"));assertTrue(fixture.db.row.isEmpty());
  verify(fixture.models,never()).imageConnection(anyString());verifyNoInteractions(fixture.gateway);
 }

 @Test void definiteSampleRefusalIsPersistedAndReplayedWithoutAnotherCall()throws Exception{
  for(String reason:java.util.List.of("POLICY","COPYRIGHT","UNSUPPORTED_CAPABILITY","AUTH")){
   var fixture=new SubmittedSample("UPLOADED","PRINT_EXTRACT",archiveDirectory.resolve("refusal-"+reason));
   when(fixture.gateway.generateTransparentArtwork(anyString(),anyString(),any(),anyList()))
     .thenThrow(new CommerceImageRejectedException("本次请求已明确拒绝",reason,400));
   assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,fixture::submit);
   assertEquals("REJECTED",fixture.db.row.get("status"));
   assertTrue(fixture.db.row.get("result_json").toString().contains(reason));
   assertTrue(assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,fixture::submit).getMessage().contains("本次请求已明确拒绝"));
   verify(fixture.gateway).generateTransparentArtwork(anyString(),anyString(),same(fixture.img2),anyList());
   verify(fixture.models).transparentArtworkConnection("org");verifyNoMoreInteractions(fixture.gateway,fixture.models);
  }
 }

 @Test void uncertainSampleFailureRemainsUnknownAndNeverAutomaticallyRepeats()throws Exception{
  var fixture=new SubmittedSample("UPLOADED","PRINT_EXTRACT",archiveDirectory.resolve("uncertain-sample"));
  when(fixture.gateway.generateTransparentArtwork(anyString(),anyString(),any(),anyList())).thenThrow(new IllegalStateException("connection lost"));
  assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,fixture::submit);
  assertEquals("UNKNOWN",fixture.db.row.get("status"));assertEquals("UNKNOWN",fixture.submit().status());
  verify(fixture.gateway).generateTransparentArtwork(anyString(),anyString(),same(fixture.img2),anyList());
  verify(fixture.models).transparentArtworkConnection("org");verifyNoMoreInteractions(fixture.gateway,fixture.models);
 }

 private static final class SubmittedSample {
  final SubmissionDb db=new SubmissionDb();
  final com.yingdao.aicommerce.saas.application.ModelConfigService models=mock(com.yingdao.aicommerce.saas.application.ModelConfigService.class);
  final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);
  final com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection img2=new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection("img2","https://image.example","gpt-image-2","OPENAI","CUSTOM","test");
  final com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection standard=new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection("default","https://image.example","default-image-model","OPENAI","CUSTOM","test");
  final com.yingdao.aicommerce.auth.PlatformPrincipal user=new com.yingdao.aicommerce.auth.PlatformPrincipal("u","org","c","u","u","",true,java.util.List.of(),java.util.List.of("product:edit"));
  final CommerceSampleService service;final CommerceSampleService.Request request;
  SubmittedSample(String product,String kind,java.nio.file.Path directory)throws Exception{
   var archive=new CommerceImageArchive(directory.toString());
   boolean print=kind.startsWith("PRINT_");
   var source=archive.store("org",png(kind.equals("PRINT_VARIANT"),false));
   db.parent.putAll(java.util.Map.of("id","parent","product",product,"variant_index",0,"status","READY","asset_kind",kind.equals("PRINT_VARIANT")?"PRINT_EXTRACT":"PRODUCT_IMAGE","storage_key",source.storageKey()));
   service=new CommerceSampleService(db,models,gateway,archive,mock(com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader.class),new tools.jackson.databind.ObjectMapper());
   request=new CommerceSampleService.Request(product,kind.equals("PRINT_VARIANT")?1:0,true,kind.equals("PRODUCT_IMAGE")?null:"parent",kind);
   when(models.transparentArtworkConnection("org")).thenReturn(java.util.Optional.of(img2));when(models.imageConnection("org")).thenReturn(java.util.Optional.of(standard));
   var response=new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(java.util.Base64.getEncoder().encodeToString(png(print,false)),"image/png",print?"gpt-image-2":"default-image-model","EDIT",null),null);
   when(gateway.generateTransparentArtwork(anyString(),anyString(),any(),anyList())).thenReturn(response);
   when(gateway.generateSample(anyString(),anyString(),any(),anyList())).thenReturn(response);
  }
  CommerceSampleService.View submit(){return service.generate(request,"submitted-sample-request",user);}
 }

 private static final class SubmissionDb extends org.springframework.jdbc.core.JdbcTemplate {
  final java.util.Map<String,Object> row=new java.util.LinkedHashMap<>(),parent=new java.util.LinkedHashMap<>();
  @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql,Object... arguments){
   if(sql.contains("WHERE id=?")&&"parent".equals(arguments[0]))return java.util.List.of(parent);
   return row.isEmpty()?java.util.List.of():java.util.List.of(row);
  }
  @Override public java.util.Map<String,Object> queryForMap(String sql,Object... arguments){return row;}
  @Override public int update(String sql,Object... arguments){
   if(sql.startsWith("INSERT IGNORE")){
    row.put("id",arguments[0]);row.put("product",arguments[4]);row.put("variant_index",arguments[5]);row.put("model_id",arguments[6]);
    row.put("prompt",arguments[7]);row.put("parent_id",arguments[8]);row.put("asset_kind",arguments[9]);row.put("status","PREPARED");
   }else if(sql.contains("SET storage_key="))row.put("storage_key",arguments[0]);
   else if(sql.contains("SET status='SUBMITTING'"))row.put("status","SUBMITTING");
   else if(sql.contains("SET status='RESULT_RECEIVED'")){row.put("status","RESULT_RECEIVED");row.put("result_json",arguments[0]);}
   else if(sql.contains("SET status='REJECTED'")){row.put("status","REJECTED");row.put("result_json",arguments[0]);}
   else if(sql.contains("SET status='UNKNOWN'"))row.put("status","UNKNOWN");
   else if(sql.contains("SET status='READY'"))row.put("status","READY");
   else throw new AssertionError("Unexpected sample write: "+sql);
   return 1;
  }
 }

 private static byte[] png(boolean transparent,boolean empty)throws Exception{
  var image=new java.awt.image.BufferedImage(128,128,transparent?java.awt.image.BufferedImage.TYPE_INT_ARGB:java.awt.image.BufferedImage.TYPE_INT_RGB);
  var graphics=image.createGraphics();
  if(!transparent){graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,128,128);}
  if(!empty){graphics.setColor(java.awt.Color.BLACK);graphics.fillRect(32,32,64,64);graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(48,48,32,32);}
  graphics.dispose();var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);return output.toByteArray();
 }
 private static final class SampleDb extends org.springframework.jdbc.core.JdbcTemplate{
  final java.util.Map<String,Object> row=new java.util.LinkedHashMap<>();final java.util.List<String> writes=new java.util.ArrayList<>();
  @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql,Object... arguments){return java.util.List.of(row);}
  @Override public int update(String sql,Object... arguments){
   writes.add(sql);
   if(sql.contains("SET storage_key="))row.put("storage_key",arguments[0]);
   else if(sql.contains("SET status='TRANSPARENCY_REJECTED'"))row.put("status","TRANSPARENCY_REJECTED");
   else if(sql.contains("SET status='READY'"))row.put("status","READY");
   else throw new AssertionError("Received result must not submit a new request: "+sql);
   return 1;
  }
 }
 private static final class ReceivedSample{
  final SampleDb db=new SampleDb();final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();final byte[] bytes;
  final com.yingdao.aicommerce.saas.application.ModelConfigService models=org.mockito.Mockito.mock(com.yingdao.aicommerce.saas.application.ModelConfigService.class);
  final CommerceDesignImageGateway gateway=org.mockito.Mockito.mock(CommerceDesignImageGateway.class);
  final com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader downloader=org.mockito.Mockito.mock(com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader.class);
  final com.yingdao.aicommerce.auth.PlatformPrincipal user=new com.yingdao.aicommerce.auth.PlatformPrincipal("u","org","c","u","u","",true,java.util.List.of(),java.util.List.of("product:edit"));
  final CommerceImageArchive archive;final CommerceSampleService service;final CommerceSampleService.Request request;
  ReceivedSample(String kind,byte[] bytes,java.nio.file.Path directory){
   this.bytes=bytes;archive=new CommerceImageArchive(directory.toString());service=new CommerceSampleService(db,models,gateway,archive,downloader,json);
   int variant="PRODUCT_IMAGE".equals(kind)?0:1;String parent="PRODUCT_IMAGE".equals(kind)?null:"parent";
   request=new CommerceSampleService.Request("CUSHION",variant,true,parent,kind);
   db.row.put("id","sample");db.row.put("product","CUSHION");db.row.put("variant_index",variant);db.row.put("parent_id",parent);
   db.row.put("status","RESULT_RECEIVED");db.row.put("asset_kind",kind);db.row.put("model_id","model");
   db.row.put("result_json",json.writeValueAsString(new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(java.util.Base64.getEncoder().encodeToString(bytes),"image/png","model","EDIT",null),null)));
  }
  CommerceSampleService.View replay(){return service.generate(request,"sample-request",user);}
 }
}
