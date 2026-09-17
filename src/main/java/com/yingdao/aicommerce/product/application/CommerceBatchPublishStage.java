package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.domain.CommerceBatchItem;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Completes only after every target has a real platform draft receipt. */
@Service
public class CommerceBatchPublishStage implements CommerceBatchStageHandler {
 private static final String PUBLICATION_SQL="SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=?";
 private final CommerceBatchMapper batches;
 private final CommerceBatchCreativePlanMapper plans;
 private final CommerceTemplateMapper templates;
 private final ProductCaptureService captures;
 private final ShopMapper shops;
 private final JdbcTemplate db;
 private final ObjectMapper json;
 private final CommerceBackPreviewService backs;
 private final CommerceImageArchive archive;
 private final CommercePublicationDrafts drafts;
 private final ProductPublishWorkflowService workflows;
 @org.springframework.beans.factory.annotation.Autowired
 private CommerceBatchPublicationOptions publicationOptions;

 public CommerceBatchPublishStage(CommerceBatchMapper batches,CommerceBatchCreativePlanMapper plans,
   CommerceTemplateMapper templates,ProductCaptureService captures,ShopMapper shops,JdbcTemplate db,
   ObjectMapper json,CommerceBackPreviewService backs,CommerceImageArchive archive,CommercePublicationDrafts drafts,ProductPublishWorkflowService workflows) {
  this.batches=batches;this.plans=plans;this.templates=templates;this.captures=captures;this.shops=shops;
  this.db=db;this.json=json;this.backs=backs;this.archive=archive;this.drafts=drafts;this.workflows=workflows;
 }
 public CommerceBatchExecution.Stage stage(){return CommerceBatchExecution.Stage.PUBLISH;}
 public Result execute(CommerceBatchExecution run,PlatformPrincipal user) {
  try{return advance(run,user);}
  catch(BusinessException|IllegalArgumentException e){return new Result(false,"草稿上传准备停止："+e.getMessage());}
  catch(RuntimeException e){
   org.slf4j.LoggerFactory.getLogger(getClass()).error("Batch publication failed: {}",run.id(),e);
   return Result.unknown("草稿上传状态暂无法确认，已保留商品与发布记录；恢复时复用原任务，不重复创建。原因："+e.getClass().getSimpleName());
  }
 }

 private Result advance(CommerceBatchExecution run,PlatformPrincipal user) {
  var fixed=plans.forExecution(user.organizationId(),run.id());
  if(fixed==null)return new Result(false,"草稿上传准备停止：缺少固定创作配置");
  var plan=json.readValue(fixed.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
  if(!CommerceBatchCreativePlanValidator.localPhotoOutput(plan))return new Result(false,"草稿上传准备停止：当前出图方式尚未支持商品发布打包");
  if(plan.variantsPerPrint()<1||plan.variantsPerPrint()>20)throw new IllegalArgumentException("固定款式数量无效");
  var version=templates.snapshot(user.organizationId(),plan.printTemplateSnapshotId());
  if(version==null)return new Result(false,"草稿上传准备停止：固定模板不存在");
  var config=json.readValue(version.configJson(),CommercePrintTemplateService.Configuration.class);
  var items=batches.items(user.organizationId(),run.batchId());
  if(items.isEmpty())return new Result(false,"草稿上传准备停止：未选择目标店铺");
  var jobs=db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status='READY'",run.id(),user.organizationId());
  var progress=new Progress(items.size()*plan.variantsPerPrint());
  List<String> supplementalSides=null;
  int created=0;
  for(var item:items){
   int itemCompleted=0;
   for(int variant=1;variant<=plan.variantsPerPrint();variant++){
    String label=label(item,variant);
    try{
     var records=db.queryForList(PUBLICATION_SQL,user.organizationId(),run.id(),item.id(),variant);
     if(!records.isEmpty()&&records.getFirst().get("workflow_id")!=null){
      var record=records.getFirst();
      if(!(record.get("capture_id") instanceof String captureId)||captureId.isBlank()){
       progress.unknown(label,"已有上传任务缺少商品关联，需核对原记录；不会重新创建");continue;
      }
      if(progress.record(workflows.get(captureId,(String)record.get("workflow_id"),user),label))itemCompleted++;
      continue;
     }
     // Inspect all existing workflows every round, but bound new image packing work.
     // An earlier pending/failed target must not starve later items or variants.
     if(created>=1){progress.deferred++;continue;}
     var shop=shops.selectById(item.shopId());
     if(shop==null||!user.organizationId().equals(shop.getOrganizationId()))throw new IllegalArgumentException("目标店铺不可访问");
     if(!"ACTIVE".equals(shop.getStatus()))throw new IllegalArgumentException("目标店铺授权已失效，请重新授权");
     var options=publicationOptions.forSubmission(user.organizationId(),run.batchId(),item);
     if(options==null||options.isEmpty())throw new IllegalArgumentException("发布配置缺失：售价规则、库存、包裹重量尺寸、类目或仓库尚未保存；无需复核图片");
     var optionProblems=CommerceBatchPublicationOptions.validate(options,shop.getPlatform());
     if(!optionProblems.isEmpty())throw new IllegalArgumentException("发布配置不完整："+String.join("、",optionProblems));
     if(supplementalSides==null)supplementalSides=printedSupplementalSides(config,user.organizationId());
     var source=plan.sources().stream().filter(x->x.captureId().equals(item.captureId())).findFirst()
       .orElseThrow(()->new IllegalArgumentException("固定创作配置缺少该来源商品"));
     if(source.colorTargets().isEmpty())throw new IllegalArgumentException("来源商品缺少固定 SKU 颜色");
     var manifest=new ArrayList<Map<String,Object>>();
     for(var color:source.colorTargets()){
      var front=CommerceBackPreviewService.find(jobs,source.captureId(),"MOCKUP_FRONT",variant,color.key());
      var finalFront=CommerceBatchCreativePlanValidator.drapeOutput(plan)?CommerceBackPreviewService.find(jobs,source.captureId(),CommerceDrapeReuseWorkflow.OUTPUT,variant,color.key()):front;
      String frontKey=(String)finalFront.get("storage_key");archive.read(user.organizationId(),frontKey);
      var row=new LinkedHashMap<String,Object>();row.put("colorKey",color.key());row.put("colorLabel",color.label());row.put("skuKeys",color.skuKeys());row.put("frontKey",frontKey);
      for(String side:supplementalSides){
       var image="BACK".equals(side)?backs.render(run.batchId(),(String)front.get("id"),user):backs.render(run.batchId(),(String)front.get("id"),side,user);
       row.put(side.toLowerCase(Locale.ROOT)+"Key",archive.store(user.organizationId(),Base64.getDecoder().decode(image.dataBase64())).storageKey());
      }
      manifest.add(row);
     }
     String capture=drafts.prepare(run.id(),item,variant,manifest,user);
     var body=new LinkedHashMap<String,Object>(options);body.put("confirmed",true);body.put("shopId",item.shopId());body.put("destinationPlatform",shop.getPlatform());
     // This key also recovers a workflow created just before a lost database response.
     var result=workflows.create(capture,body,"commerce-publish-"+run.id()+"-"+item.id()+"-"+variant,user);
     created++;
     int linked=db.update("UPDATE commerce_batch_publications SET workflow_id=? WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? AND workflow_id IS NULL",result.workflow().id(),user.organizationId(),run.id(),item.id(),variant);
     if(linked!=1){
      var saved=db.queryForList(PUBLICATION_SQL,user.organizationId(),run.id(),item.id(),variant);
      if(saved.isEmpty()||!result.workflow().id().equals(saved.getFirst().get("workflow_id")))throw new IllegalStateException("已创建上传任务，关联记录尚未确认");
     }
     if(progress.record(result.workflow(),label))itemCompleted++;
    }catch(BusinessException|IllegalArgumentException e){
     progress.failed(label,e.getMessage());
    }catch(RuntimeException e){
     org.slf4j.LoggerFactory.getLogger(getClass()).error("Batch publication target failed: {} {} V{}",run.id(),item.id(),variant,e);
     progress.unknown(label,"任务结果暂无法确认（"+e.getClass().getSimpleName()+"），恢复时核对原任务，不重复创建");
    }
   }
   if(itemCompleted==plan.variantsPerPrint())db.update("UPDATE commerce_batch_items SET status='PUBLISHED' WHERE organization_id=? AND batch_id=? AND id=?",user.organizationId(),run.batchId(),item.id());
  }
  if(progress.completed==progress.total){
   db.update("UPDATE commerce_batches SET status='PUBLISHED' WHERE organization_id=? AND id=?",user.organizationId(),run.batchId());
   return new Result(true,"草稿上传完成："+progress.completed+" 款均已取得平台商品编号，可到店铺草稿中查看");
  }
  String message=progress.message();
  // Keep collecting independent targets until they settle, even when one needs repair.
  if(progress.pending>0||progress.deferred>0)return Result.progress(message);
  return progress.unknown>0?Result.unknown(message):new Result(false,message);
 }

 List<String> printedSupplementalSides(CommercePrintTemplateService.Configuration config,String org){
  if(!CommercePrintTemplateService.photoLayout(config))return hasBackPrints(config,org)?List.of("BACK"):List.of();
  var sides=CommerceSurfaceTemplateInputs.load(config,archive,org);
  return List.of("BACK","LEFT","RIGHT").stream().filter(side->sides.containsKey(side)&&!sides.get(side).visiblePrintIds().isEmpty()).toList();
 }

 boolean hasBackPrints(CommercePrintTemplateService.Configuration config,String org){
  if(CommercePrintTemplateService.photoLayout(config))return printedSupplementalSides(config,org).contains("BACK");
  if(config.surfacePrints().isEmpty())return false;
  String backUv=config.surfaceUvViews().get("BACK");
  if(backUv==null||backUv.isBlank()){
   boolean onlyFront=config.surfacePrints().stream().allMatch(p->p.normal()!=null&&p.normal().size()==3&&p.normal().get(2)!=null&&p.normal().get(2)>0)
     &&config.areas().stream().noneMatch(area->!"FRONT".equals(area.side()));
   if(onlyFront)return false;
   throw new IllegalArgumentException("模板含背面或侧面印花，但缺少背面位置图，请重新保存模板渲染素材");
  }
  String uv=CommerceSurfaceTemplateInputs.key(org,backUv);
  boolean visible=!CommerceSurfaceMockupComposer.inspect(Base64.getDecoder().decode(archive.read(org,uv).dataBase64()),config.renderPrintIds()).visiblePrintIds().isEmpty();
  if(visible&&(config.baseImages().get("BACK")==null||config.blankRenderedViews().get("BACK")==null))throw new IllegalArgumentException("背面印花位置已保存，但缺少背面商品底图或空白模型图");
  return visible;
 }

 private static String label(CommerceBatchItem item,int variant){
  String shop=Objects.toString(item.shopName(),Objects.toString(item.shopId(),"目标店铺"));
  if(shop.length()>36)shop=shop.substring(0,36);
  String capture=Objects.toString(item.captureId(),"未知商品");
  return shop+" / "+capture.substring(0,Math.min(8,capture.length()))+" / V"+variant;
 }

 private static final class Progress{
  final int total;int completed,pending,deferred,failed,unknown;
  final List<String> issues=new ArrayList<>(),waiting=new ArrayList<>();
  Progress(int total){this.total=total;}
  boolean record(ProductPublishWorkflowService.WorkflowView wf,String label){
   if(wf==null){unknown(label,"上传任务读取结果为空，需核对原任务");return false;}
   if("SUCCEEDED".equals(wf.status())){
    if(wf.publication()==null||wf.publication().dryRun()||wf.publication().externalId()==null||wf.publication().externalId().isBlank())unknown(label,"任务没有真实平台商品编号，尚不能确认草稿已上传");
    else{completed++;return true;}
   }else if(Set.of("PENDING","RUNNING").contains(Objects.toString(wf.status(),""))){
    pending++;waiting.add(label+"："+Objects.toString(wf.currentStep(),"等待平台回执"));
   }else if(Set.of("FAILED","CANCELLED").contains(Objects.toString(wf.status(),""))){
    String reason=Objects.toString(wf.errorMessage(),wf.status());
    if(wf.publicationAttempted())unknown(label,reason+"；外部请求已发出，需核对原商品，禁止重复创建");
    else failed(label,reason+"；商品尚未创建，已保留素材与原任务");
   }else unknown(label,"未知任务状态 "+Objects.toString(wf.status(),"空")+"，需核对原任务");
   return false;
  }
  void failed(String label,String message){failed++;issues.add(label+"："+shortReason(message));}
  void unknown(String label,String message){unknown++;issues.add(label+"："+shortReason(message));}
  String message(){
   String summary="草稿上传："+completed+" / "+total+" 款已确认，"+pending+" 款提交中，"+deferred+" 款待提交，"+failed+" 款需补充，"+unknown+" 款需核对";
   if(!waiting.isEmpty())summary+="。当前等待："+String.join("；",waiting.subList(0,Math.min(2,waiting.size())));
   if(!issues.isEmpty())summary+="。未完成原因："+String.join("；",issues.subList(0,Math.min(3,issues.size())))+(issues.size()>3?"；另有 "+(issues.size()-3)+" 项待处理":"");
   return summary;
  }
  private static String shortReason(String value){String text=Objects.toString(value,"原因未返回");return text.length()>180?text.substring(0,180)+"…":text;}
 }
}
