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
  Harness(int count,int variants){
   ReflectionTestUtils.setField(stage,"publicationOptions",options);when(user.organizationId()).thenReturn("org");
   var json=new ObjectMapper();var sources=new ArrayList<CommerceBatchCreativePlanValidator.Selection>();var jobs=new ArrayList<Map<String,Object>>();
   for(int i=0;i<count;i++){
    String itemId="item-"+i,capture="source-"+i,shopId="shop-"+i;var item=new CommerceBatchItem(itemId,"org","batch",capture,1,shopId,"店铺"+i,0,"DRAFT",Instant.now());items.add(item);
    var shop=mock(Shop.class);when(shop.getOrganizationId()).thenReturn("org");when(shop.getPlatform()).thenReturn("TIKTOK_SHOP");when(shop.getStatus()).thenReturn("ACTIVE");when(shops.selectById(shopId)).thenReturn(shop);
    when(options.forSubmission("org","batch",item)).thenReturn(Map.of("categoryId","category-"+itemId,"publishStock",999,"autoConvertPrice",true,"weightGrams",250,"packageLengthCm",30,"packageWidthCm",25,"packageHeightCm",2,"warehouseId","warehouse"));
    sources.add(new CommerceBatchCreativePlanValidator.Selection(capture,1,"image",List.of(),List.of(new CommerceSourceColorResolver.Color("black","Black",List.of("sku"),"image",true))));
    for(int v=1;v<=variants;v++){
     int variant=v;String key=itemId+":"+v,draft="draft-"+itemId+"-"+v;
     when(db.queryForList(PUBLICATIONS,"org","run",itemId,v)).thenAnswer(call->records.containsKey(key)?List.of(records.get(key)):List.of());
     var front=new HashMap<String,Object>();front.put("id","front-"+key);front.put("capture_id",capture);front.put("kind","MOCKUP_FRONT");front.put("variant_index",v);front.put("color_key","black");front.put("storage_key","front-key-"+key);jobs.add(front);
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
