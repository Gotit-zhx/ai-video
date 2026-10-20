package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** On-demand reverse view from frozen photos, UV placement and the same variant masters. No image generation. */
@Service
public class CommerceBackPreviewService {
 private final JdbcTemplate db; private final CommerceBatchExecutionService executions;
 private final CommerceBatchCreativePlanMapper plans;private final CommerceTemplateMapper templates;
 private final CommerceImageArchive archive;private final ObjectMapper json;private final CommerceGarmentForeground foreground;
 public CommerceBackPreviewService(JdbcTemplate db,CommerceBatchExecutionService executions,CommerceBatchCreativePlanMapper plans,
   CommerceTemplateMapper templates,CommerceImageArchive archive,ObjectMapper json,CommerceGarmentForeground foreground){
  this.db=db;this.executions=executions;this.plans=plans;this.templates=templates;this.archive=archive;this.json=json;this.foreground=foreground;
 }
 public CommerceDesignImageGateway.Reference render(String batch,String id,PlatformPrincipal user){
  return render(batch,id,"BACK",user);
 }
 public CommerceDesignImageGateway.Reference render(String batch,String id,String side,PlatformPrincipal user){
  if(!List.of("BACK","LEFT","RIGHT").contains(side))throw new IllegalArgumentException("辅助商品图视角无效");
  var run=executions.get(batch,user);if(run==null)throw new IllegalArgumentException("批次尚未启动");
  var jobs=db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status='READY'",run.id(),user.organizationId());
  String resolvedId=id;
  var draped=jobs.stream().filter(j->id.equals(j.get("id"))&&CommerceDrapeReuseWorkflow.OUTPUT.equals(j.get("kind"))).findFirst().orElse(null);
  if(draped!=null){
   var binding=json.readTree((String)draped.get("result_json"));
   resolvedId=binding.path("localGuideId").asText("");
   String expected=resolvedId;
   if(jobs.stream().noneMatch(j->expected.equals(j.get("id"))&&Objects.equals(j.get("capture_id"),draped.get("capture_id"))&&Objects.equals(j.get("variant_index"),draped.get("variant_index"))&&Objects.equals(j.get("color_key"),draped.get("color_key"))&&Objects.equals(j.get("storage_key"),binding.path("localGuideKey").asText())))throw new IllegalArgumentException("搭放主图缺少同款同色的背面定位依据");
  }
  final String frontId=resolvedId;
  var front=jobs.stream().filter(j->frontId.equals(j.get("id"))&&"MOCKUP_FRONT".equals(j.get("kind"))&&CommerceLocalPhotoWorkflow.POLICY.equals(j.get("model_config_id"))).findFirst().orElseThrow(()->new IllegalArgumentException("缺少当前商品的已归档正面"));
  var record=plans.forExecution(user.organizationId(),run.id());if(record==null)throw new IllegalArgumentException("缺少固定创作配置");
  var plan=json.readValue(record.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
  var version=templates.snapshot(user.organizationId(),plan.printTemplateSnapshotId());if(version==null)throw new IllegalArgumentException("固定模板不存在");
  var config=json.readValue(version.configJson(),CommercePrintTemplateService.Configuration.class);
  CommerceSurfaceTemplateInputs.validate(config);
  if(!"BACK".equals(side)&&!CommercePrintTemplateService.photoLayout(config))throw new IllegalArgumentException("模型侧面尚未独立校准，不能直接套用照片位置");
  String capture=(String)front.get("capture_id"),color=(String)front.get("color_key");int variant=((Number)front.get("variant_index")).intValue();
  var source=plan.sources().stream().filter(s->capture.equals(s.captureId())).findFirst().orElseThrow();
  var colorSpec=source.colorTargets().stream().filter(c->Objects.equals(c.key(),color)).findFirst().orElseThrow();
  var baseJob=find(jobs,capture,"BASE_COLOR",0,color);
  List<String> refs=json.readValue((String)baseJob.get("reference_keys"),json.getTypeFactory().constructCollectionType(List.class,String.class));
  if(refs.size()<2)throw new IllegalArgumentException("缺少该颜色的固定取色素材");
  String swatch=(String)refs.get(1),back=CommerceSurfaceTemplateInputs.key(user.organizationId(),config.baseImages().get(side)),
    blank=CommerceSurfaceTemplateInputs.key(user.organizationId(),config.blankRenderedViews().get(side)),uv=CommerceSurfaceTemplateInputs.key(user.organizationId(),config.surfaceUvViews().get(side));
  var projection=CommerceSurfaceMockupComposer.inspect(bytes(user.organizationId(),uv),config.renderPrintIds());
  var replacementKeys=new LinkedHashMap<String,String>();var groups=CommerceSurfaceTemplateInputs.artworkGroups(config);
  String selected=CommerceBatchCreativePlanValidator.groupedArtwork(plan)?null:CommerceSurfaceTemplateInputs.variationArtwork(config,plan.variationArtworkId());
  for(var print:CommercePrintTemplateService.placementBindings(config))if(projection.visiblePrintIds().contains(print.id())) {
   String key;
   if(CommerceBatchCreativePlanValidator.groupedArtwork(plan))key=(String)find(jobs,capture,"MATTE_G"+(groups.indexOf(print.artworkId())+1),variant,null).get("storage_key");
   else if(print.artworkId().equals(selected))key=(String)find(jobs,capture,"MATTE",variant,null).get("storage_key");
   else key=(String)find(jobs,capture,"MATTE_G"+(groups.indexOf(print.artworkId())+1),0,null).get("storage_key");
   replacementKeys.put(print.id(),key);
  }
  String sceneKey=CommerceSceneBackgroundAssets.readyKey(plan,config,source,jobs,user.organizationId(),archive,json);
  var cache=new CommerceMockupDerivativeCache(db,archive,json);
  var cacheInputs=new ArrayList<Object>(List.of(side,CommercePrintTemplateService.photoLayout(config),back,blank,uv,swatch,colorSpec.label(),replacementKeys,config.renderPrintIds()));
  if(colorSpec.hex()!=null)cacheInputs.addAll(List.of("template-hex-v1",colorSpec.hex()));
  if(sceneKey!=null)cacheInputs.addAll(List.of(CommerceSceneBackgroundComposer.VERSION,sceneKey));
  String cacheKey=cache.key("photo-view-v2/"+CommerceGarmentColor.VERSION,cacheInputs);
  var entry=cache.find(user.organizationId(),cacheKey);
  if(entry==null){
   var colored=new CommerceLocalPhotoWorkflow(db,archive,json,foreground).prepareColor(bytes(user.organizationId(),back),bytes(user.organizationId(),swatch),colorSpec.label(),CommerceSourceColorResolver.DEFAULT_KEY.equals(color),colorSpec.hex());
   byte[] output=colored.png();Object check=Map.of("plainBack",true);
   if(!replacementKeys.isEmpty()){
    var replacements=new LinkedHashMap<String,byte[]>();replacementKeys.forEach((k,v)->replacements.put(k,bytes(user.organizationId(),v)));
    var composed=CommercePrintTemplateService.photoLayout(config)
      ?CommercePhotoMockupComposer.composePhoto(side,output,bytes(user.organizationId(),back),bytes(user.organizationId(),blank),bytes(user.organizationId(),uv),config.renderPrintIds(),replacements)
      :CommercePhotoMockupComposer.composeRecolored(side,output,bytes(user.organizationId(),back),bytes(user.organizationId(),blank),bytes(user.organizationId(),uv),config.renderPrintIds(),replacements);
    output=composed.png();check=composed.report();
   }
   var report=new LinkedHashMap<String,Object>(Map.of("side",side,"frontArtifactId",id,"artworkKeys",replacementKeys,"color",colorSpec.label(),"check",check,"modelCalls",0,"productionReady",false));
   if(sceneKey!=null){
    var scene=CommerceSceneBackgroundComposer.compose(output,bytes(user.organizationId(),back),bytes(user.organizationId(),sceneKey));
    output=scene.png();report.put("sceneKey",sceneKey);report.put("sceneCheck",scene.report());report.put("renderPolicy",CommerceBatchCreativePlanValidator.GROK_SCENE_POLICY);
   }
   entry=cache.store(user.organizationId(),cacheKey,side+"_PREVIEW",output,report);
  }
  if(entry==null)throw new IllegalArgumentException("背面预览暂未归档，请重试");
  return archive.read(user.organizationId(),entry.storageKey());
 }
 static Map<String,Object> find(List<Map<String,Object>> jobs,String capture,String kind,int variant,String color){
  return jobs.stream().filter(j->capture.equals(j.get("capture_id"))&&kind.equals(j.get("kind"))&&((Number)j.get("variant_index")).intValue()==variant&&Objects.equals(color,j.get("color_key"))).findFirst().orElseThrow(()->new IllegalArgumentException("背面所需的同款印花或颜色素材尚未准备完成"));
 }
 private byte[] bytes(String org,String key){return Base64.getDecoder().decode(archive.read(org,key).dataBase64());}
}
