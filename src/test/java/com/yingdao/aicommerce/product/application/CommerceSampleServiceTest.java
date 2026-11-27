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

 @Test void capturePrintUsesTestedModelAndPromptForNewAndPreparedRequests() throws Exception {
  var model = new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection(
    "capture-model", "https://image.example", "gpt-image-2.6", "OPENAI_COMPATIBLE", "CUSTOM", "test");
  String prompt = "将参考照片中 T 恤胸前的主印花整理为一张正视、独立的平面图稿。仅呈现清楚可见的图形、线条、原有文字和配色，保留设计中的白色细节。输出单张带 alpha 通道的透明 PNG。";
  for (boolean prepared : new boolean[]{false, true}) {
   var fixture = new SubmittedSample("UPLOADED", "PRINT_EXTRACT", archiveDirectory.resolve("capture-" + prepared));
   fixture.db.parent.put("prompt", "来源采集商品 capture-a / image-a");
   if (prepared) {
    fixture.db.row.putAll(java.util.Map.of("id", "earlier-request", "product", "UPLOADED",
      "variant_index", 0, "parent_id", "parent", "asset_kind", "PRINT_EXTRACT",
      "status", "PREPARED", "model_id", "gpt-image-2", "prompt", "old prompt"));
   }
   when(fixture.models.requireCapturePrintConnection("org")).thenReturn(model);
   var output = new CommerceDesignImageGateway.Artifact(java.util.Base64.getEncoder().encodeToString(png(true, false)),
     "image/png", "gpt-image-2.6", "EDIT", null);
   when(fixture.gateway.generateCapturePrintArtwork(eq(prompt), anyString(), same(model), anyList()))
     .thenReturn(new CommerceDesignImageGateway.Response(output, null));

   var result = fixture.service.extractCapturePrint("request-id", "capture-a", "image-a",
     () -> fail("The source is already archived"), fixture.user);
   assertEquals("READY", result.status());
   assertEquals("gpt-image-2.6", result.model());
   assertEquals(prompt, fixture.db.row.get("prompt"));
   if (prepared) assertEquals("earlier-request", result.id());
   assertEquals(result, fixture.service.extractCapturePrint("request-id", "capture-a", "image-a",
     () -> fail("Replay must reuse the source"), fixture.user));
   verify(fixture.models).requireCapturePrintConnection("org");
   var archived = fixture.service.image("parent", fixture.user);
   verify(fixture.gateway).generateCapturePrintArtwork(eq(prompt), eq(result.id()), same(model),
     eq(java.util.List.of(archived)));
   verifyNoMoreInteractions(fixture.models, fixture.gateway);
  }
 }

 @Test void capturePrintRemainsViewableWhileArchiveIsUnavailableAndReusesTheSamePaidResult() throws Exception {
  var fixture = new SubmittedSample("UPLOADED", "PRINT_EXTRACT", archiveDirectory.resolve("capture-archive"));
  var model = new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection(
    "capture", "https://image.example", "gpt-image-2.6", "OPENAI_COMPATIBLE", "CUSTOM", "test");
  byte[] bytes = png(true, false);
  String encoded = java.util.Base64.getEncoder().encodeToString(bytes);
  var result = new CommerceDesignImageGateway.Response(
    new CommerceDesignImageGateway.Artifact(encoded, "image/png", "gpt-image-2.6", "EDIT", null), null);
  fixture.db.parent.put("prompt", "来源采集商品 capture-a / image-a");
  when(fixture.models.requireCapturePrintConnection("org")).thenReturn(model);
  when(fixture.gateway.generateCapturePrintArtwork(anyString(), anyString(), same(model), anyList())).thenReturn(result);
  doThrow(new IllegalStateException("storage unavailable")).doCallRealMethod().when(fixture.archive).store(eq("org"), any(byte[].class));

  var pending = fixture.service.extractCapturePrint("old-key-a", "capture-a", "image-a", () -> fail("already archived"), fixture.user);
  assertEquals("ARCHIVE_PENDING", pending.status());
  assertTrue(pending.imageUrl().endsWith("/" + pending.id() + "/image"));
  assertEquals("RESULT_RECEIVED", fixture.db.row.get("status"));
  assertNull(fixture.db.row.get("storage_key"));
  assertEquals(encoded, fixture.service.captureResultImage(pending.id(), fixture.user).dataBase64());
  var ready = fixture.service.extractCapturePrint("old-key-a", "capture-a", "image-a", () -> fail("already archived"), fixture.user);
  assertEquals(pending.id(), ready.id());
  assertEquals("READY", ready.status());
  verify(fixture.gateway, times(1)).generateCapturePrintArtwork(anyString(), eq(pending.id()), same(model), anyList());
  verify(fixture.models, times(1)).requireCapturePrintConnection("org");
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

 @Test void refreshingCaptureRecoversTheOriginalCompletedRequest() throws Exception {
  var fixture = pendingCapture();
  var encoded = java.util.Base64.getEncoder().encodeToString(png(true, false));
  var result = new CommerceDesignImageGateway.Response(
    new CommerceDesignImageGateway.Artifact(encoded, "image/png", "gpt-image-2.6", "EDIT", null), null);
  when(fixture.gateway.operation("capture-request"))
    .thenReturn(new CommerceDesignImageGateway.Operation("RUNNING", true, 1L, null, null),
      new CommerceDesignImageGateway.Operation("COMPLETED", false, 2L, result, null));

  assertEquals("UNKNOWN", fixture.service.archiveCaptureResult("capture-request", fixture.user).status());
  var recovered = fixture.service.archiveCaptureResult("capture-request", fixture.user);
  assertEquals("capture-request", recovered.id());
  assertEquals("READY", recovered.status());
  assertNotNull(recovered.imageUrl());
  assertEquals(encoded, fixture.service.captureResultImage(recovered.id(), fixture.user).dataBase64());
  verify(fixture.gateway, times(2)).operation("capture-request");
  verifyNoMoreInteractions(fixture.gateway);
  verifyNoInteractions(fixture.models);
 }

 @Test void refreshingCapturePreservesAConfirmedContentRefusalWithoutResubmitting() throws Exception {
  var fixture = pendingCapture();
  when(fixture.gateway.operation("capture-request"))
    .thenReturn(new CommerceDesignImageGateway.Operation("FAILED", false, 1L, null,
        java.util.Map.of("requestState", "REJECTED", "failureKind", "COPYRIGHT", "providerStatus", 400)));
  var status = fixture.service.archiveCaptureResult("capture-request", fixture.user);
  assertEquals("REJECTED", status.status());
  assertTrue(fixture.db.row.get("result_json").toString().contains("第三方内容限制"));
  assertNull(status.imageUrl());
  verify(fixture.gateway).operation("capture-request");
  verifyNoMoreInteractions(fixture.gateway);
  verifyNoInteractions(fixture.models);
 }

 @Test void explicitPrimaryRefusalSwitchesOnceAndPreservesTheOriginalBananaOutput() throws Exception {
  var fixture = new SubmittedSample("UPLOADED","PRINT_EXTRACT",archiveDirectory.resolve("banana-claim"));
  fixture.db.parent.put("prompt", "来源采集商品 capture-a / image-a");
  when(fixture.models.requireCapturePrintConnection("org")).thenReturn(CAPTURE_MODEL);
  when(fixture.models.capturePrintBananaConnection("org")).thenReturn(java.util.Optional.of(BANANA_MODEL));
  when(fixture.gateway.generateCapturePrintArtwork(anyString(),anyString(),same(CAPTURE_MODEL),anyList()))
    .thenThrow(new CommerceImageRejectedException("第三方内容限制","COPYRIGHT",400));
  var raw = java.util.Base64.getEncoder().encodeToString(png(false,false));
  when(fixture.gateway.generateCapturePrintWithBanana(anyString(),anyString(),same(BANANA_MODEL),anyList()))
    .thenReturn(bananaResponse(raw));
  var result = fixture.service.extractCapturePrint("capture-fallback-key","capture-a","image-a",() -> fail("already archived"),fixture.user);
  assertEquals("READY",result.status());
  assertEquals(BANANA_MODEL.modelId(),result.model());
  var saved = new tools.jackson.databind.ObjectMapper().readTree((String)fixture.db.row.get("result_json"));
  assertEquals(raw,fixture.archive.read("org",saved.path("originalStorageKey").asText()).dataBase64());
  var converted = java.util.Base64.getDecoder().decode(fixture.service.image(result.id(),fixture.user).dataBase64());
  CommerceTransparentArtworkPolicy.requireNative(converted);
  var prepared = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(converted));
  assertEquals(0,prepared.getRGB(0,0));
  assertEquals(0xffffffff,prepared.getRGB(63,63));
  assertEquals("READY",fixture.service.retryCaptureWithBanana(result.id(),true,fixture.user).status());
  verify(fixture.gateway).generateCapturePrintArtwork(anyString(),eq(result.id()),same(CAPTURE_MODEL),anyList());
  verify(fixture.gateway).generateCapturePrintWithBanana(anyString(),eq(result.id()+"_banana"),same(BANANA_MODEL),anyList());
  verifyNoMoreInteractions(fixture.gateway);
 }

 @Test void unknownPrimaryDoesNotSwitchModelsAndUnknownBananaLooksUpItsOwnAttempt() throws Exception {
  var fixture = new SubmittedSample("UPLOADED","PRINT_EXTRACT",archiveDirectory.resolve("banana-unknown"));
  fixture.db.parent.put("prompt","来源采集商品 capture-a / image-a");
  when(fixture.models.requireCapturePrintConnection("org")).thenReturn(CAPTURE_MODEL);
  when(fixture.gateway.generateCapturePrintArtwork(anyString(),anyString(),same(CAPTURE_MODEL),anyList()))
    .thenThrow(new IllegalStateException("response timed out"));
  assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,() -> fixture.service.extractCapturePrint(
    "capture-unknown-key","capture-a","image-a",() -> fail("already archived"),fixture.user));
  assertEquals("UNKNOWN",fixture.db.row.get("status"));
  verify(fixture.models,never()).capturePrintBananaConnection(anyString());
  verify(fixture.gateway,never()).generateCapturePrintWithBanana(anyString(),anyString(),any(),anyList());

  var fallback = pendingCapture();
  fallback.db.row.put("model_id",BANANA_MODEL.modelId());
  var raw = java.util.Base64.getEncoder().encodeToString(png(false,false));
  when(fallback.gateway.operation("capture-request_banana"))
    .thenReturn(new CommerceDesignImageGateway.Operation("COMPLETED",false,1L,bananaResponse(raw),null));
  assertEquals("READY",fallback.service.archiveCaptureResult("capture-request",fallback.user).status());
  verify(fallback.gateway).operation("capture-request_banana");
  verifyNoMoreInteractions(fallback.gateway);
 }

 @Test void aHistoricRefusalRequiresConfirmationAndAFailedMatteDoesNotResubmit() throws Exception {
  var fixture = pendingCapture();
  fixture.db.row.put("status","REJECTED");
  fixture.db.row.put("result_json","{\"message\":\"第三方内容限制\",\"failureKind\":\"COPYRIGHT\",\"providerStatus\":400}");
  assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
    () -> fixture.service.retryCaptureWithBanana("capture-request",false,fixture.user));
  verifyNoInteractions(fixture.gateway,fixture.models);
  when(fixture.models.capturePrintBananaConnection("org")).thenReturn(java.util.Optional.of(BANANA_MODEL));
  var blank = java.util.Base64.getEncoder().encodeToString(png(false,true));
  when(fixture.gateway.generateCapturePrintWithBanana(anyString(),eq("capture-request_banana"),same(BANANA_MODEL),anyList()))
    .thenReturn(bananaResponse(blank));
  assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
    () -> fixture.service.retryCaptureWithBanana("capture-request",true,fixture.user));
  assertEquals("TRANSPARENCY_REJECTED",fixture.db.row.get("status"));
  assertNull(fixture.db.row.get("storage_key"));
  var saved = new tools.jackson.databind.ObjectMapper().readTree((String)fixture.db.row.get("result_json"));
  assertEquals(blank,fixture.archive.read("org",saved.path("originalStorageKey").asText()).dataBase64());
  assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
    () -> fixture.service.retryCaptureWithBanana("capture-request",true,fixture.user));
  verify(fixture.gateway).generateCapturePrintWithBanana(anyString(),eq("capture-request_banana"),same(BANANA_MODEL),anyList());
  verifyNoMoreInteractions(fixture.gateway);
 }

 private static final com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection CAPTURE_MODEL =
   new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection("capture","https://image.example","gpt-image-2.6","OPENAI_COMPATIBLE","CUSTOM","test");
 private static final com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection BANANA_MODEL =
   new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection("banana","https://image.example","gemini-nano-banana-2.1","OPENAI_COMPATIBLE","CUSTOM","test");

 private static CommerceDesignImageGateway.Response bananaResponse(String encoded) {
  return new CommerceDesignImageGateway.Response(
   new CommerceDesignImageGateway.Artifact(encoded,"image/png",BANANA_MODEL.modelId(),"EDIT",null),null);
 }

 private SubmittedSample pendingCapture() throws Exception {
  var fixture = new SubmittedSample("UPLOADED", "PRINT_EXTRACT", archiveDirectory.resolve("pending-capture"));
  fixture.db.row.putAll(java.util.Map.of("id", "capture-request", "product", "UPLOADED", "variant_index", 0,
      "status", "UNKNOWN", "asset_kind", "PRINT_EXTRACT", "request_key", "capture-v2-art-test",
      "model_id", "gpt-image-2.6", "parent_id", "parent"));
  return fixture;
 }

 private static final class SubmittedSample {
  final SubmissionDb db=new SubmissionDb();
  final com.yingdao.aicommerce.saas.application.ModelConfigService models=mock(com.yingdao.aicommerce.saas.application.ModelConfigService.class);
  final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);
  final com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection img2=new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection("img2","https://image.example","gpt-image-2","OPENAI","CUSTOM","test");
  final com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection standard=new com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection("default","https://image.example","default-image-model","OPENAI","CUSTOM","test");
  final com.yingdao.aicommerce.auth.PlatformPrincipal user=new com.yingdao.aicommerce.auth.PlatformPrincipal("u","org","c","u","u","",true,java.util.List.of(),java.util.List.of("product:edit"));
  final CommerceImageArchive archive;
  final CommerceSampleService service;final CommerceSampleService.Request request;
  SubmittedSample(String product,String kind,java.nio.file.Path directory)throws Exception{
   archive=spy(new CommerceImageArchive(directory.toString()));
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
   if(sql.contains("request_key=?")&&arguments[2].toString().startsWith("capture-v2-src-"))
    return java.util.List.of(parent);
   return row.isEmpty()?java.util.List.of():java.util.List.of(row);
  }
  @Override public java.util.Map<String,Object> queryForMap(String sql,Object... arguments){return row;}
  @Override public int update(String sql,Object... arguments){
   if(sql.startsWith("INSERT IGNORE")){
    if(!row.isEmpty())return 0;
    row.put("id",arguments[0]);row.put("request_key",arguments[3]);row.put("product",arguments[4]);row.put("variant_index",arguments[5]);row.put("model_id",arguments[6]);
    row.put("prompt",arguments[7]);row.put("parent_id",arguments[8]);row.put("asset_kind",arguments[9]);row.put("status","PREPARED");
   }else if(sql.contains("SET storage_key=")){
    row.put("storage_key",arguments[0]);
    if(sql.contains("status='READY'"))row.put("status","READY");
   }
   else if(sql.contains("SET status='SUBMITTING'")){
    String previous = sql.contains("status='REJECTED'") ? "REJECTED" : "PREPARED";
    if(!previous.equals(row.get("status")))return 0;
    row.put("status","SUBMITTING");
    if(sql.contains("model_id=?")){
     row.put("model_id",arguments[0]);row.put("prompt",arguments[1]);
    }
   }
   else if(sql.contains("SET status='RESULT_RECEIVED'")){row.put("status","RESULT_RECEIVED");row.put("result_json",arguments[0]);}
   else if(sql.contains("SET status='REJECTED'")){row.put("status","REJECTED");row.put("result_json",arguments[0]);}
   else if(sql.contains("SET status='UNKNOWN'"))row.put("status","UNKNOWN");
   else if(sql.contains("SET status='READY'"))row.put("status","READY");
   else if(sql.contains("SET status='TRANSPARENCY_REJECTED'"))row.put("status","TRANSPARENCY_REJECTED");
   else if(sql.contains("SET result_json=JSON_SET")){
    var mapper = new tools.jackson.databind.ObjectMapper();
    var payload = (tools.jackson.databind.node.ObjectNode)mapper.readTree((String)row.get("result_json"));
    payload.put("originalStorageKey",(String)arguments[0]);
    row.put("result_json",mapper.writeValueAsString(payload));
   }
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
