package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceBatchPublishStageTest {
 private static final String PUBLICATIONS="SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=?";
 private static final String LINK="UPDATE commerce_batch_publications SET workflow_id=? WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? AND workflow_id IS NULL";

 @Test void oldDrapeCollarCannotBeUploadedButBoundV3ReviewCan(){
  var h=new Harness(1,1);
  String guide="tenant/"+"a".repeat(64),scene="tenant/"+"b".repeat(64),masterKey="tenant/"+"c".repeat(64);
  var master=new HashMap<String,Object>();
  master.put("capture_id","source-0");master.put("kind","DRAPE_MASTER");master.put("storage_key",masterKey);
  master.put("reference_keys",new ObjectMapper().writeValueAsString(List.of(guide,scene,"tenant/"+"d".repeat(64))));
  var blank=new HashMap<String,Object>();blank.put("capture_id","source-0");blank.put("kind","DRAPE_BLANK");
  var review=new LinkedHashMap<String,Object>();
  for(String flag:List.of("passed","hasScene","hasSupportContact","hasNaturalDrape","preservesGarment",
    "preservesPrintPlacement","fullyVisible","preservesNeckline","preservesSleeveBadge","preservesSideLabel"))review.put(flag,true);
  review.put("contractVersion",CommerceDrapeStyleReview.NECKLINE_CONTRACT_VERSION);
  review.put("detailGuardVersion","DRAPE_DETAIL_GUARD_V1");review.put("detailGuardStatus","PASSED");
  review.put("reason","领口和版型均与模板相符");
  review.put("guideSha256","a".repeat(64));review.put("sceneSha256","b".repeat(64));review.put("masterSha256","c".repeat(64));
  blank.put("result_json",new ObjectMapper().writeValueAsString(Map.of("styleReview",review)));
  master.put("prompt",CommerceDrapeReuseWorkflow.PROMPT);
  assertThrows(IllegalArgumentException.class,()->h.stage.requireNecklineReview(List.of(master,blank),"source-0"));
  master.put("prompt",CommerceDrapeReuseWorkflow.PROMPT_NECKLINE);
  assertDoesNotThrow(()->h.stage.requireNecklineReview(List.of(master,blank),"source-0"));
  review.put("preservesSideLabel",false);
  blank.put("result_json",new ObjectMapper().writeValueAsString(Map.of("styleReview",review)));
  assertThrows(IllegalArgumentException.class,()->h.stage.requireNecklineReview(List.of(master,blank),"source-0"));
  review.put("preservesSideLabel",true);
  review.put("preservesNeckline",false);
  blank.put("result_json",new ObjectMapper().writeValueAsString(Map.of("styleReview",review)));
  assertThrows(IllegalArgumentException.class,()->h.stage.requireNecklineReview(List.of(master,blank),"source-0"));
 }

 @Test void oldModelOnlyReviewIsRecheckedAndPreservedBeforeDraftSubmission(){
  var h=new Harness(1,1);var reviews=mock(CommerceDrapeStyleReview.class);
  ReflectionTestUtils.setField(h.stage,"drapeReviews",reviews);
  String guide="tenant/"+"a".repeat(64),scene="tenant/"+"b".repeat(64),masterKey="tenant/"+"c".repeat(64);
  var master=new HashMap<String,Object>(Map.of("id","master","organization_id","org","capture_id","source-0",
   "kind","DRAPE_MASTER","storage_key",masterKey,"prompt",CommerceDrapeReuseWorkflow.PROMPT_NECKLINE,
   "reference_keys",new ObjectMapper().writeValueAsString(List.of(guide,scene,"tenant/"+"d".repeat(64)))));
  String previous=new ObjectMapper().writeValueAsString(Map.of("styleReview",Map.of(
   "contractVersion","COMMERCE_DRAPE_STYLE_V3","passed",true,"reason","旧模型判断")));
  var blank=new HashMap<String,Object>(Map.of("id","blank","capture_id","source-0","kind","DRAPE_BLANK","result_json",previous));
  byte[] bytes=new byte[]{1,2,3};
  for(String key:List.of(guide,scene,masterKey))
   when(h.archive.read("org",key)).thenReturn(new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(bytes)));
  var checked=new LinkedHashMap<String,Object>();
  for(String flag:CommerceDrapeStyleReview.NECKLINE_CHECKS)checked.put(flag,true);
  checked.put("passed",true);checked.put("reason","领口、袖标、侧标已核查");
  checked.put("contractVersion",CommerceDrapeStyleReview.NECKLINE_CONTRACT_VERSION);
  checked.put("detailGuardVersion","DRAPE_DETAIL_GUARD_V1");checked.put("detailGuardStatus","PASSED");
  checked.put("guideSha256","a".repeat(64));checked.put("sceneSha256","b".repeat(64));checked.put("masterSha256","c".repeat(64));
  when(reviews.review("org",bytes,bytes,bytes,true)).thenReturn(checked);
  when(h.db.update(startsWith("UPDATE commerce_batch_artwork_jobs SET result_json="),anyString(),eq("blank"),eq("org"),eq(previous))).thenReturn(1);
  assertDoesNotThrow(()->h.stage.requireNecklineReview(List.of(master,blank),"source-0"));
  var saved=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(h.db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET result_json="),saved.capture(),eq("blank"),eq("org"),eq(previous));
  var report=new ObjectMapper().readTree(saved.getValue());
  assertEquals("COMMERCE_DRAPE_STYLE_V3",report.path("previousStyleReview").path("contractVersion").asText());
  assertEquals("DRAPE_DETAIL_GUARD_V1",report.path("styleReview").path("detailGuardVersion").asText());
 }

 @Test void delayedLocalDetailQueueDefersAllVariantsWithoutRepeatingVisualOrCreatingDrafts(){
  var h=new Harness(1,2);var json=new ObjectMapper();
  var reviews=mock(CommerceDrapeStyleReview.class);
  ReflectionTestUtils.setField(h.stage,"drapeReviews",reviews);
  var source=new CommerceBatchCreativePlanValidator.Selection("source-0",1,"image",List.of(),
    List.of(new CommerceSourceColorResolver.Color("black","Black",List.of("black-sku"),"image",true)));
  var plan=new CommerceBatchCreativePlanValidator.Plan("template",List.of(source),2,null,
    "SOURCE_PRODUCT","SOURCE_SKU_COLORS",CommerceBatchCreativePlanValidator.GROK_DRAPE_POLICY);
  when(h.plans.forExecution("org","run")).thenReturn(new CommerceBatchCreativePlanMapper.Row(
    "plan","org","batch",1,json.writeValueAsString(plan),"user",Instant.now()));
  var refs=List.of("guide","scene","art");
  var master=new HashMap<String,Object>(Map.of("id","master","organization_id","org",
    "capture_id","source-0","kind",CommerceDrapeReuseWorkflow.MASTER,"storage_key","master-key",
    "prompt",CommerceDrapeReuseWorkflow.PROMPT_NECKLINE,"reference_keys",json.writeValueAsString(refs)));
  var blank=new HashMap<String,Object>(Map.of("id","blank","capture_id","source-0",
    "kind",CommerceDrapeReuseWorkflow.BLANK,"result_json",json.writeValueAsString(Map.of(
      "styleReview",Map.of("contractVersion","COMMERCE_DRAPE_STYLE_V3")))));
  when(h.db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status='READY'","run","org"))
    .thenReturn(List.of(master,blank));
  for(String key:List.of("guide","scene","master-key"))
   when(h.archive.read("org",key)).thenReturn(new CommerceDesignImageGateway.Reference(
     "image/png",Base64.getEncoder().encodeToString(new byte[]{1,2,3})));
  when(reviews.review(eq("org"),any(),any(),any(),eq(true)))
    .thenThrow(new CommerceDrapeStyleReview.DetailGuardUnavailableException("本地核查排队中"));
  var result=h.execute();
  assertEquals("QUEUED",result.pendingStatus());
  assertTrue(result.message().contains("2 款待提交"));
  verify(reviews,times(1)).review(eq("org"),any(),any(),any(),eq(true));
  verify(h.drafts,never()).prepare(anyString(),any(),anyInt(),anyList(),any());
 }

 @Test void recoveredDuplicateDraftUsesANewKeyWithoutChangingTheOriginalFailedWorkflow(){
  var h=new Harness(1,1);
  var item=h.items.getFirst();
  when(h.db.queryForList(
    "SELECT idempotency_key FROM product_captures WHERE organization_id=? AND id=?",
    String.class,"org","draft-item-0-1")).thenReturn(List.of("commerce-run-item-0-1-duplicate-recovery-1"));
  var pending=workflow("replacement","PENDING",false,null,null);
  when(h.workflows.create(eq("draft-item-0-1"),anyMap(),
    eq("commerce-publish-run-item-0-1-duplicate-recovery-1"),eq(h.user)))
    .thenReturn(new ProductPublishWorkflowService.CreateResult(pending,true,false));
  assertEquals("QUEUED",h.execute().pendingStatus());
  verify(h.workflows).create(eq("draft-item-0-1"),anyMap(),
    eq("commerce-publish-run-item-0-1-duplicate-recovery-1"),eq(h.user));
  verify(h.db).update(eq(LINK),eq("replacement"),eq("org"),eq("run"),eq(item.id()),eq(1));
 }

 @Test void firstPendingWorkflowDoesNotPreventAnotherItemFromBeingSubmitted(){
  var h=new Harness(2,1);h.existing(0,1,workflow("first","RUNNING",false,null,null));
  var result=h.execute();
  assertEquals("QUEUED",result.pendingStatus());assertTrue(result.message().contains("2 款提交中"));
  verify(h.workflows).create(eq("draft-item-1-1"),argThat(options->"category-item-1".equals(options.get("categoryId"))),eq("commerce-publish-run-item-1-1"),eq(h.user));
  verify(h.options,never()).find("org","batch",h.items.getFirst());
 }

 @Test void failedFirstVariantDoesNotPreventSecondVariantOfSameItem(){
  var h=new Harness(1,2);h.existing(0,1,workflow("failed","FAILED",false,"库存格式不正确",null));
  var result=h.execute();
  assertEquals("QUEUED",result.pendingStatus());assertTrue(result.message().contains("库存格式不正确"));assertTrue(result.message().contains("1 款提交中"));
  verify(h.workflows).create(eq("draft-item-0-2"),anyMap(),eq("commerce-publish-run-item-0-2"),eq(h.user));
  verify(h.drafts,never()).prepare(eq("run"),eq(h.items.getFirst()),eq(1),anyList(),eq(h.user));
 }

 @Test void missingConfigurationIsAggregatedWithoutStoppingOtherItem(){
  var h=new Harness(2,1);when(h.options.forSubmission("org","batch",h.items.getFirst())).thenReturn(Map.of());
  var result=h.execute();
  assertEquals("QUEUED",result.pendingStatus());assertTrue(result.message().contains("发布配置缺失"));
  verify(h.workflows).create(eq("draft-item-1-1"),anyMap(),anyString(),eq(h.user));
 }

 @Test void allSettledFailuresExplainEveryAffectedTargetWithoutCreatingAgain(){
  var h=new Harness(2,1);h.existing(0,1,workflow("failed-a","FAILED",false,"类目缺必填属性",null));h.existing(1,1,workflow("failed-b","CANCELLED",false,"仓库已禁用",null));
  var result=h.execute();
  assertEquals("WAITING_INPUT",result.pendingStatus());assertTrue(result.message().contains("类目缺必填属性"));assertTrue(result.message().contains("仓库已禁用"));
  verify(h.workflows,never()).create(anyString(),anyMap(),anyString(),any());verifyNoInteractions(h.drafts,h.options,h.backs);
 }

 @Test void incompleteLegacyOptionsReportAllMissingFieldsBeforePackingOrSubmitting(){
  var h=new Harness(1,1);when(h.options.forSubmission("org","batch",h.items.getFirst())).thenReturn(Map.of("categoryId","123"));
  var result=h.execute();assertEquals("WAITING_INPUT",result.pendingStatus());
  for(String reason:List.of("售价规则缺失","库存无效","包裹重量无效","发货仓库缺失"))assertTrue(result.message().contains(reason),reason);
  verifyNoInteractions(h.drafts,h.backs);verify(h.workflows,never()).create(anyString(),anyMap(),anyString(),any());
 }

 @Test void uncertainExternalSubmissionIsNeverRecreatedWhileOtherReceiptsAreCounted(){
  var h=new Harness(2,1);h.existing(0,1,workflow("uncertain","FAILED",true,"平台响应超时",null));h.existing(1,1,workflow("done","SUCCEEDED",true,null,receipt("remote-product")));
  var result=h.execute();
  assertEquals("UNKNOWN",result.pendingStatus());assertTrue(result.message().contains("1 / 2 款已确认"));assertTrue(result.message().contains("禁止重复创建"));
  verify(h.workflows,never()).create(anyString(),anyMap(),anyString(),any());
  verify(h.db,never()).update("UPDATE commerce_batches SET status='PUBLISHED' WHERE organization_id=? AND id=?","org","batch");
 }

 @Test void completionRequiresRealProductIdsAndSaysDraftUpload(){
  var h=new Harness(1,2);h.existing(0,1,workflow("one","SUCCEEDED",true,null,receipt("id-one")));h.existing(0,2,workflow("two","SUCCEEDED",true,null,receipt("id-two")));
  var result=h.execute();assertTrue(result.complete());assertTrue(result.message().startsWith("草稿上传完成"));assertFalse(result.message().contains("上架完成"));
  assertTrue(h.execute().complete());verify(h.workflows,never()).create(anyString(),anyMap(),anyString(),any());
  verifyNoInteractions(h.options,h.backs,h.drafts);
 }

 @Test void dryRunAndMissingReceiptsCannotCompleteBatch(){
  var h=new Harness(2,1);h.existing(0,1,workflow("dry","SUCCEEDED",false,null,new ProductPublishWorkflowService.PublishStageView("audit","DRY_RUN_SUCCEEDED",true,"DRY_RUN","fake-id",null,null)));h.existing(1,1,workflow("missing","SUCCEEDED",true,null,receipt(" ")));
  var result=h.execute();assertFalse(result.complete());assertEquals("UNKNOWN",result.pendingStatus());assertTrue(result.message().contains("0 / 2 款已确认"));
  verify(h.workflows,never()).create(anyString(),anyMap(),anyString(),any());
 }

 @Test void aLostWorkflowLinkIsRecoveredWithTheSameCreationKey(){
  var h=new Harness(1,1);var item=h.items.getFirst();
  when(h.db.update(eq(LINK),anyString(),eq("org"),eq("run"),eq(item.id()),eq(1))).thenReturn(0);
  var first=h.execute();assertEquals("UNKNOWN",first.pendingStatus());
  h.link(0,1);
  assertEquals("QUEUED",h.execute().pendingStatus());
  verify(h.workflows,times(2)).create(eq("draft-item-0-1"),anyMap(),eq("commerce-publish-run-item-0-1"),eq(h.user));
  // The service's immutable key recovers the original workflow; following polls only read it.
  assertEquals("QUEUED",h.execute().pendingStatus());
  verify(h.workflows,times(2)).create(anyString(),anyMap(),anyString(),any());
 }

 @Test void onlyOneNewWorkflowIsPackedPerRoundWhileAlreadySubmittedTargetsAreStillRead(){
  var h=new Harness(3,1);h.existing(2,1,workflow("done","SUCCEEDED",true,null,receipt("remote")));
  var result=h.execute();assertEquals("QUEUED",result.pendingStatus());assertTrue(result.message().contains("1 款待提交"));assertTrue(result.message().contains("1 / 3 款已确认"));
  verify(h.workflows,times(1)).create(anyString(),anyMap(),anyString(),any());
  h.execute();verify(h.workflows,times(2)).create(anyString(),anyMap(),anyString(),any());
 }

 @Test void orderedModelDetailsArePackedOncePerVariantRegardlessOfColorCount(){
  var h=new Harness(1,2,2);
  try(var details=mockStatic(CommerceModelDetailWorkflow.class,CALLS_REAL_METHODS)){
   for(int variant=1;variant<=2;variant++){
    int selectedVariant=variant;
    details.when(()->CommerceModelDetailWorkflow.requiredKeys(any(),eq(selectedVariant),anyList(),eq(h.db),any(),eq(h.archive),eq("org")))
     .thenReturn(List.of("detail-v"+variant+"-first","detail-v"+variant+"-second"));
   }
   assertEquals("QUEUED",h.execute().pendingStatus());assertEquals("QUEUED",h.execute().pendingStatus());
   for(int variant=1;variant<=2;variant++){
    int selectedVariant=variant;
    verify(h.drafts).prepare(eq("run"),eq(h.items.getFirst()),eq(variant),argThat(manifest->manifest.size()==2
     &&List.of("detail-v"+selectedVariant+"-first","detail-v"+selectedVariant+"-second").equals(manifest.getFirst().get("detailKeys"))
     &&!manifest.get(1).containsKey("detailKeys")
     &&"black".equals(manifest.getFirst().get("colorKey"))&&"white".equals(manifest.get(1).get("colorKey"))),eq(h.user));
    details.verify(()->CommerceModelDetailWorkflow.requiredKeys(argThat(source->"source-0".equals(source.captureId())),eq(selectedVariant),anyList(),eq(h.db),any(),eq(h.archive),eq("org")));
   }
  }
 }

 @Test void missingOrInvalidRequiredDetailStopsBeforeCapturePackingAndPlatformSubmission(){
  for(String problem:List.of("缺少第 2 张详情图","详情图引用不匹配","详情图视觉检查未通过","详情图归档已缺失")){
   var h=new Harness(1,1);
   try(var details=mockStatic(CommerceModelDetailWorkflow.class,CALLS_REAL_METHODS)){
    details.when(()->CommerceModelDetailWorkflow.requiredKeys(any(),eq(1),anyList(),eq(h.db),any(),eq(h.archive),eq("org"))).thenThrow(new IllegalArgumentException(problem));
    var result=h.execute();assertEquals("WAITING_INPUT",result.pendingStatus());assertTrue(result.message().contains(problem));
    verifyNoInteractions(h.drafts);verify(h.workflows,never()).create(anyString(),anyMap(),anyString(),any());
   }
  }
 }

 @Test void shopeeRetainsAllDetailsLocallyAndSubmitsEvenWhenGalleryCapacityIsExceeded(){
  for(int count:List.of(8,9,12)){
   var h=new Harness(1,1);var item=h.items.getFirst();var shop=h.shops.selectById(item.shopId());when(shop.getPlatform()).thenReturn("SHOPEE");
   var options=new LinkedHashMap<String,Object>(h.options.forSubmission("org","batch",item));options.put("logisticInfo",List.of(Map.of("logistic_id",1,"enabled",true)));
   when(h.options.forSubmission("org","batch",item)).thenReturn(options);
   var keys=java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i->"detail-"+i).toList();
   try(var details=mockStatic(CommerceModelDetailWorkflow.class,CALLS_REAL_METHODS)){
    details.when(()->CommerceModelDetailWorkflow.requiredKeys(any(),eq(1),anyList(),eq(h.db),any(),eq(h.archive),eq("org"))).thenReturn(keys);
    var result=h.execute();
    verify(h.drafts).prepare(eq("run"),eq(item),eq(1),argThat(manifest->keys.equals(manifest.getFirst().get("detailKeys"))),eq(h.user));
    assertEquals("QUEUED",result.pendingStatus());
    assertFalse(result.message().contains("主图库最多"));
    verify(h.workflows).create(eq("draft-item-0-1"),argThat(body->"SHOPEE".equals(body.get("destinationPlatform"))),eq("commerce-publish-run-item-0-1"),eq(h.user));
    // A later poll reuses the linked workflow, never creates a second external product.
    assertEquals("QUEUED",h.execute().pendingStatus());
    verify(h.workflows,times(1)).create(anyString(),anyMap(),anyString(),any());
    verify(h.drafts,times(1)).prepare(anyString(),any(),anyInt(),anyList(),any());

   }
  }
 }

 @Test void tiktokCanSubmitNineDetailsWithoutApplyingShopeeGalleryLimit(){
  var h=new Harness(1,1);var keys=java.util.stream.IntStream.rangeClosed(1,9).mapToObj(i->"detail-"+i).toList();
  try(var details=mockStatic(CommerceModelDetailWorkflow.class,CALLS_REAL_METHODS)){
   details.when(()->CommerceModelDetailWorkflow.requiredKeys(any(),eq(1),anyList(),eq(h.db),any(),eq(h.archive),eq("org"))).thenReturn(keys);
   assertEquals("QUEUED",h.execute().pendingStatus());
   verify(h.drafts).prepare(eq("run"),eq(h.items.getFirst()),eq(1),argThat(manifest->keys.equals(manifest.getFirst().get("detailKeys"))),eq(h.user));
   verify(h.workflows).create(eq("draft-item-0-1"),anyMap(),eq("commerce-publish-run-item-0-1"),eq(h.user));
  }
 }

 @Test void legacySelectionWithoutDetailsKeepsManifestFreeOfDetailRequirements(){
  var h=new Harness(1,1);assertEquals("QUEUED",h.execute().pendingStatus());
  verify(h.drafts).prepare(eq("run"),eq(h.items.getFirst()),eq(1),argThat(manifest->manifest.size()==1&&!manifest.getFirst().containsKey("detailKeys")),eq(h.user));
 }

 @Test void frontOnlyTemplateDoesNotRequireBackAssets(){
  var h=new Harness(1,1);assertFalse(h.stage.hasBackPrints(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of()),"org"));
  assertFalse(h.stage.hasBackPrints(surface(1,Map.of(),Map.of(),Map.of()),"org"));
  verifyNoInteractions(h.archive,h.backs);
  assertThrows(IllegalArgumentException.class,()->h.stage.hasBackPrints(surface(-1,Map.of(),Map.of(),Map.of()),"org"));
 }

 @Test void backAssetsAreRequiredOnlyWhenBackUvActuallyContainsPrints()throws Exception{
  var h=new Harness(1,1);String hash="a".repeat(64),key=CommerceSurfaceTemplateInputs.key("org",hash);
  when(h.archive.read("org",key)).thenReturn(uv(false));
  assertFalse(h.stage.hasBackPrints(surface(-1,Map.of("BACK",hash),Map.of(),Map.of()),"org"));
  when(h.archive.read("org",key)).thenReturn(uv(true));
  assertThrows(IllegalArgumentException.class,()->h.stage.hasBackPrints(surface(-1,Map.of("BACK",hash),Map.of(),Map.of()),"org"));
  assertTrue(h.stage.hasBackPrints(surface(-1,Map.of("BACK",hash),Map.of("BACK",hash),Map.of("BACK",hash)),"org"));
 }

 @Test void photoPlacementsPublishEveryPrintedSupplementalViewWithSameFrontColorAndVariant(){
  var h=new Harness(1,1);var json=new ObjectMapper();String hash="a".repeat(64);
  var prints=List.of("BACK","LEFT","RIGHT").stream().map(side->new CommercePrintTemplateService.PhotoPrint(side,hash,side,.2,.2,.2,.2,0)).toList();
  var config=new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(),Map.of(),null,hash,"refs",List.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),null,prints);
  when(h.templates.snapshot("org","template")).thenReturn(new CommerceTemplateVersion("template","org","template-id",1,"PRINT_LAYOUT","Photo",json.writeValueAsString(config),"user",Instant.now()));
  var sides=new LinkedHashMap<String,CommerceSurfaceTemplateInputs.Side>();
  for(String side:List.of("BACK","LEFT","RIGHT"))sides.put(side,new CommerceSurfaceTemplateInputs.Side(side,"original","photo","blank","uv",new byte[0],new byte[0],Set.of(side)));
  when(h.backs.render("batch","front-item-0:1",h.user)).thenReturn(new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(new byte[]{1})));
  when(h.backs.render("batch","front-item-0:1","LEFT",h.user)).thenReturn(new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(new byte[]{2})));
  when(h.backs.render("batch","front-item-0:1","RIGHT",h.user)).thenReturn(new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(new byte[]{3})));
  when(h.archive.store(eq("org"),any())).thenAnswer(call->new CommerceImageArchive.Archived("side-"+((byte[])call.getArgument(1))[0],"hash","image/png",1));
  try(var inputs=mockStatic(CommerceSurfaceTemplateInputs.class,CALLS_REAL_METHODS)){
   inputs.when(()->CommerceSurfaceTemplateInputs.load(eq(config),eq(h.archive),eq("org"))).thenReturn(sides);
   var result=h.execute();assertEquals("QUEUED",result.pendingStatus());
   verify(h.drafts).prepare(eq("run"),eq(h.items.getFirst()),eq(1),argThat(manifest->{
    var row=manifest.getFirst();return manifest.size()==1&&"front-key-item-0:1".equals(row.get("frontKey"))&&"side-1".equals(row.get("backKey"))&&"side-2".equals(row.get("leftKey"))&&"side-3".equals(row.get("rightKey"))&&"black".equals(row.get("colorKey"));
   }),eq(h.user));
  }
 }

 private static CommercePrintTemplateService.Configuration surface(double normalZ,Map<String,String> uv,Map<String,String> photos,Map<String,String> blanks){
  var print=new CommercePrintTemplateService.SurfacePrint("print","art",List.of(0d,0d,0d),List.of(0d,0d,normalZ),List.of(1d,0d,0d),.2,.2,0,"torso");
  return new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(),photos,null,null,null,List.of(print),Map.of(),blanks,uv,Map.of(),List.of("print"),null);
 }
 private static CommerceDesignImageGateway.Reference uv(boolean visible)throws Exception{
  var image=new java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_ARGB);if(visible)image.setRGB(8,8,0xff808001);
  var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
  return new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(bytes.toByteArray()));
 }
 private static ProductPublishWorkflowService.PublishStageView receipt(String id){return new ProductPublishWorkflowService.PublishStageView("audit","PUBLISHED",false,"TIKTOK",id,"DRAFT",null);}
 private static ProductPublishWorkflowService.WorkflowView workflow(String id,String status,boolean attempted,String error,ProductPublishWorkflowService.PublishStageView receipt){
  var wf=mock(ProductPublishWorkflowService.WorkflowView.class);when(wf.id()).thenReturn(id);when(wf.status()).thenReturn(status);when(wf.publicationAttempted()).thenReturn(attempted);when(wf.errorMessage()).thenReturn(error);when(wf.publication()).thenReturn(receipt);when(wf.currentStep()).thenReturn("PUBLISH");return wf;
 }

 private static final class Harness{
  final CommerceBatchMapper batches=mock(CommerceBatchMapper.class);final CommerceBatchCreativePlanMapper plans=mock(CommerceBatchCreativePlanMapper.class);
  final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);final ShopMapper shops=mock(ShopMapper.class);final JdbcTemplate db=mock(JdbcTemplate.class);
  final CommerceBackPreviewService backs=mock(CommerceBackPreviewService.class);final CommerceImageArchive archive=mock(CommerceImageArchive.class);
  final CommercePublicationDrafts drafts=mock(CommercePublicationDrafts.class);final ProductPublishWorkflowService workflows=mock(ProductPublishWorkflowService.class);
  final CommerceBatchPublicationOptions options=mock(CommerceBatchPublicationOptions.class);final PlatformPrincipal user=mock(PlatformPrincipal.class);
  final CommerceBatchPublishStage stage=new CommerceBatchPublishStage(batches,plans,templates,mock(ProductCaptureService.class),shops,db,new ObjectMapper(),backs,archive,drafts,workflows);
  final CommerceBatchExecution run=new CommerceBatchExecution("run","org","batch","user","PUBLISH","RUNNING","",null,null,Instant.now(),Instant.now());
  final List<CommerceBatchItem> items=new ArrayList<>();final Map<String,Map<String,Object>> records=new HashMap<>();
  Harness(int count,int variants){this(count,variants,1);}
  Harness(int count,int variants,int colors){
   ReflectionTestUtils.setField(stage,"publicationOptions",options);when(user.organizationId()).thenReturn("org");
   var json=new ObjectMapper();var sources=new ArrayList<CommerceBatchCreativePlanValidator.Selection>();var jobs=new ArrayList<Map<String,Object>>();
   for(int i=0;i<count;i++){
    String itemId="item-"+i,capture="source-"+i,shopId="shop-"+i;var item=new CommerceBatchItem(itemId,"org","batch",capture,1,shopId,"店铺"+i,0,"DRAFT",Instant.now());items.add(item);
    var shop=mock(Shop.class);when(shop.getOrganizationId()).thenReturn("org");when(shop.getPlatform()).thenReturn("TIKTOK_SHOP");when(shop.getStatus()).thenReturn("ACTIVE");when(shops.selectById(shopId)).thenReturn(shop);
    when(options.forSubmission("org","batch",item)).thenReturn(Map.of("categoryId","category-"+itemId,"publishStock",999,"autoConvertPrice",true,"weightGrams",250,"packageLengthCm",30,"packageWidthCm",25,"packageHeightCm",2,"warehouseId","warehouse"));
    var colorTargets=new ArrayList<CommerceSourceColorResolver.Color>();
    for(String color:List.of("black","white").subList(0,colors))colorTargets.add(new CommerceSourceColorResolver.Color(color,color.equals("black")?"Black":"White",List.of(color+"-sku"),"image",true));
    sources.add(new CommerceBatchCreativePlanValidator.Selection(capture,1,"image",List.of(),colorTargets));
    for(int v=1;v<=variants;v++){
     int variant=v;String key=itemId+":"+v,draft="draft-"+itemId+"-"+v;
     when(db.queryForList(PUBLICATIONS,"org","run",itemId,v)).thenAnswer(call->records.containsKey(key)?List.of(records.get(key)):List.of());
     for(var color:colorTargets){var front=new HashMap<String,Object>();front.put("id","front-"+key+(color.key().equals("black")?"":"-"+color.key()));front.put("capture_id",capture);front.put("capture_version",1L);front.put("status","READY");front.put("kind","MOCKUP_FRONT");front.put("variant_index",v);front.put("color_key",color.key());front.put("storage_key","front-key-"+key+(color.key().equals("black")?"":"-"+color.key()));jobs.add(front);}
     when(drafts.prepare(eq("run"),eq(item),eq(v),anyList(),eq(user))).thenReturn(draft);
     var pending=workflow("workflow-"+key,"PENDING",false,null,null);
     when(workflows.create(eq(draft),anyMap(),eq("commerce-publish-run-"+itemId+"-"+v),eq(user))).thenReturn(new ProductPublishWorkflowService.CreateResult(pending,true,false));
     String pendingId=pending.id();when(workflows.get(eq(draft),eq(pendingId),eq(user))).thenReturn(pending);
     link(i,variant);
    }
   }
   when(batches.items("org","batch")).thenReturn(items);
   var plan=new CommerceBatchCreativePlanValidator.Plan("template",sources,variants,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS","LOCAL_PHOTO_V1");
   when(plans.forExecution("org","run")).thenReturn(new CommerceBatchCreativePlanMapper.Row("plan","org","batch",1,json.writeValueAsString(plan),"user",Instant.now()));
   when(templates.snapshot("org","template")).thenReturn(new CommerceTemplateVersion("template","org","template-id",1,"PRINT_LAYOUT","Template",json.writeValueAsString(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of())),"user",Instant.now()));
   when(db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status='READY'","run","org")).thenReturn(jobs);
  }
  void link(int index,int variant){var item=items.get(index);when(db.update(eq(LINK),anyString(),eq("org"),eq("run"),eq(item.id()),eq(variant))).thenAnswer(call->{records.put(item.id()+":"+variant,Map.of("capture_id","draft-"+item.id()+"-"+variant,"workflow_id",call.getArgument(1)));return 1;});}
  void existing(int index,int variant,ProductPublishWorkflowService.WorkflowView wf){var item=items.get(index);String draft="draft-"+item.id()+"-"+variant;records.put(item.id()+":"+variant,Map.of("capture_id",draft,"workflow_id",wf.id()));when(workflows.get(draft,wf.id(),user)).thenReturn(wf);}
  CommerceBatchStageHandler.Result execute(){return stage.execute(run,user);}
 }
}
