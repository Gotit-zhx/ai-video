package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Example-only assets: no capture, batch, inventory or publication side effects. */
@Service
public class CommerceSampleService {
 private static final String TRANSPARENCY_REJECTED="TRANSPARENCY_REJECTED";
 private static final String TRANSPARENCY_FAILURE="独立印花未通过原生透明 PNG 检查；原始生成结果已归档保留，同一请求只重新校验已有结果，不会再次调用生图模型";
 public record Request(String product,int variant,boolean confirmed,String parentId,String assetKind){public Request(String product,int variant,boolean confirmed,String parentId){this(product,variant,confirmed,parentId,"PRODUCT_IMAGE");}}
 public record View(String id,String product,int variant,String status,String model,String imageUrl,String parentId,String assetKind){
  @com.fasterxml.jackson.annotation.JsonProperty("message") public String message(){return TRANSPARENCY_REJECTED.equals(status)?TRANSPARENCY_FAILURE:"REJECTED".equals(status)?"生图请求已明确停止，同一请求不会再次调用模型；请查看返回的停止原因":null;}
 }
 private final JdbcTemplate db;private final ModelConfigService models;private final CommerceDesignImageGateway gateway;
 private final CommerceImageArchive archive;private final MarketplaceImageDownloader downloader;private final ObjectMapper json;
 public CommerceSampleService(JdbcTemplate db,ModelConfigService models,CommerceDesignImageGateway gateway,CommerceImageArchive archive,MarketplaceImageDownloader downloader,ObjectMapper json){this.db=db;this.models=models;this.gateway=gateway;this.archive=archive;this.downloader=downloader;this.json=json;}
 private void access(PlatformPrincipal u){if(u==null||u.organizationId()==null||!u.permissionCodes().contains("product:edit"))throw error(HttpStatus.FORBIDDEN,"无示例创作权限");}
 public List<View> list(PlatformPrincipal u){access(u);return db.query("SELECT * FROM commerce_sample_previews WHERE organization_id=? AND created_by=? ORDER BY created_at DESC LIMIT 40",(r,n)->new View(r.getString("id"),r.getString("product"),r.getInt("variant_index"),r.getString("status"),r.getString("model_id"),r.getString("storage_key")==null||!"READY".equals(r.getString("status"))?null:"/api/v1/commerce-samples/"+r.getString("id")+"/image",r.getString("parent_id"),r.getString("asset_kind")),u.organizationId(),u.userId());}
 private Map<String,Object> row(String id,PlatformPrincipal u){access(u);var rows=db.queryForList("SELECT * FROM commerce_sample_previews WHERE id=? AND organization_id=? AND created_by=?",id,u.organizationId(),u.userId());if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"示例不存在");return rows.getFirst();}
 public CommerceDesignImageGateway.Reference image(String id,PlatformPrincipal u){var r=row(id,u);if(TRANSPARENCY_REJECTED.equals(r.get("status")))throw error(HttpStatus.CONFLICT,TRANSPARENCY_FAILURE);if(!"READY".equals(r.get("status")))throw error(HttpStatus.CONFLICT,"示例尚未归档");var reference=archive.read(u.organizationId(),(String)r.get("storage_key"));requireNativePrint(id,r,Base64.getDecoder().decode(reference.dataBase64()),u);return reference;}
 public CommerceDesignImageGateway.Reference printImage(String id,PlatformPrincipal u){var r=row(id,u);if(!Set.of("PRINT_EXTRACT","PRINT_VARIANT").contains(Objects.toString(r.get("asset_kind"),"")))throw error(HttpStatus.CONFLICT,"必须使用独立印花，不能把整件商品当作印花贴上去");return image(id,u);}
 public CommerceDesignImageGateway.Reference blankImage(String id,PlatformPrincipal u){var r=row(id,u);if(!"BLANK_PRODUCT".equals(r.get("asset_kind")))throw error(HttpStatus.CONFLICT,"请选择去除印花后的纯色底图候选");return image(id,u);}
 public CommerceMockupPositionCheck.Report checkMockup(String id,PlatformPrincipal u){
  var sample=row(id,u);if(!"MOCKUP_CANDIDATE".equals(sample.get("asset_kind")))throw error(HttpStatus.CONFLICT,"仅套版候选支持位置变化检查");
  var rows=db.queryForList("SELECT g.*,t.config_json FROM commerce_mockup_guides g JOIN commerce_template_versions t ON t.id=g.template_snapshot_id AND t.organization_id=g.organization_id WHERE g.id=? AND g.organization_id=? AND g.created_by=?",sample.get("parent_id"),u.organizationId(),u.userId());
  if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"来源套版参考图不存在");var guide=rows.getFirst();
  var config=json.readValue((String)guide.get("config_json"),CommercePrintTemplateService.Configuration.class);var bindings=json.readTree((String)guide.get("bindings_json"));
  if(CommerceSurfaceTemplateInputs.enabled(config)){
   var target=CommerceSurfaceTemplateInputs.load(config,archive,u.organizationId()).get((String)guide.get("side"));
   if(target==null)throw error(HttpStatus.CONFLICT,"当前面没有已保存的可见三维印花");
   var result=CommerceSurfaceMockupComposer.check(target.blank(),target.uv(),Base64.getDecoder().decode(image(id,u).dataBase64()),config.renderPrintIds());
   return new CommerceMockupPositionCheck.Report(result.status(),result.outsideChangedPixels(),result.outsideComparedPixels(),result.outsideChangedRatio(),40,0,false,result.limitation(),null);
  }
  var areas=config.areas().stream().filter(a->a.side().equals(guide.get("side"))&&bindings.has(a.id())).toList();
  var base=archive.read(u.organizationId(),CommerceImageArchive.sha(u.organizationId().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+guide.get("base_hash"));var candidate=image(id,u);
  var reference=archive.read(u.organizationId(),(String)guide.get("storage_key"));
  try{return CommerceMockupPositionCheck.check(Base64.getDecoder().decode(base.dataBase64()),Base64.getDecoder().decode(reference.dataBase64()),Base64.getDecoder().decode(candidate.dataBase64()),areas);}catch(IllegalArgumentException e){throw error(HttpStatus.CONFLICT,"当前图片无法完成位置检查："+e.getMessage());}
 }
 public View blankUploaded(CommerceImageArchive.Archived original, PlatformPrincipal u){
  access(u);
  String key="uploaded-"+original.sha256();
  db.update("INSERT IGNORE INTO commerce_sample_previews(id,organization_id,created_by,request_key,product,variant_index,status,model_id,prompt,storage_key,asset_kind,created_at,updated_at) VALUES(?,?,?,?,?,0,'READY','UPLOAD','Uploaded original',?,'PRODUCT_IMAGE',NOW(6),NOW(6))",UUID.randomUUID().toString(),u.organizationId(),u.userId(),key,"UPLOADED",original.storageKey());
  var source=db.queryForMap("SELECT id FROM commerce_sample_previews WHERE organization_id=? AND created_by=? AND request_key=?",u.organizationId(),u.userId(),key);
  return generate(new Request("UPLOADED",0,true,(String)source.get("id"),"BLANK_PRODUCT"),"blank-volume-v2-"+original.sha256(),u);
 }
 public View generate(Request request,String key,PlatformPrincipal u){
  access(u);if(request==null||!request.confirmed()||key==null||!key.matches("[A-Za-z0-9_-]{8,80}"))throw error(HttpStatus.BAD_REQUEST,"请确认一次模型调用费用并提供请求编号");
  String prompt=printKind(request.assetKind())?printPrompt(request.assetKind(),request.variant()):"UPLOADED".equals(request.product()) && "BLANK_PRODUCT".equals(request.assetKind()) ? blankPrompt() : prompt(request.product(),request.variant());
  String kind=request.assetKind()==null?"PRODUCT_IMAGE":request.assetKind();
  if(!Set.of("PRODUCT_IMAGE","PRINT_EXTRACT","PRINT_VARIANT","BLANK_PRODUCT","MOCKUP_CANDIDATE").contains(kind))throw error(HttpStatus.BAD_REQUEST,"不支持的素材类型");
  // Replaying a received/failed result must not depend on a still-configured model,
  // a live provider URL, or a new parent read. It never claims another paid attempt.
  var previous=db.queryForList("SELECT * FROM commerce_sample_previews WHERE organization_id=? AND created_by=? AND request_key=?",u.organizationId(),u.userId(),key);
  if(!previous.isEmpty()&&!"PREPARED".equals(previous.getFirst().get("status"))){
   var saved=previous.getFirst();
   if(!Objects.equals(request.product(),saved.get("product"))||!(saved.get("variant_index") instanceof Number variant)||variant.intValue()!=request.variant()
     ||!Objects.equals(kind,saved.get("asset_kind"))||!Objects.equals(request.parentId(),saved.get("parent_id")))throw error(HttpStatus.CONFLICT,"同一请求编号不能改变示例内容或原图");
   String savedId=(String)saved.get("id");collect(savedId,u);return view(row(savedId,u));
  }
  List<CommerceDesignImageGateway.Reference> references=List.of();
  if("MOCKUP_CANDIDATE".equals(kind)){
   if(request.parentId()==null||request.variant()!=0)throw error(HttpStatus.BAD_REQUEST,"套版必须选择已保存参考图，且不能同时裂变配色");
   var guides=db.queryForList("SELECT g.*,t.config_json FROM commerce_mockup_guides g JOIN commerce_template_versions t ON t.id=g.template_snapshot_id AND t.organization_id=g.organization_id WHERE g.id=? AND g.organization_id=? AND g.created_by=?",request.parentId(),u.organizationId(),u.userId());
   if(guides.isEmpty())throw error(HttpStatus.NOT_FOUND,"套版参考图不存在");var guide=guides.getFirst();
   var config=json.readValue((String)guide.get("config_json"),CommercePrintTemplateService.Configuration.class);
   if(!request.product().equals(config.product()))throw error(HttpStatus.CONFLICT,"商品类型与套版模板不一致");
   String baseKey=CommerceImageArchive.sha(u.organizationId().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+guide.get("base_hash");
   if(CommerceSurfaceTemplateInputs.enabled(config)){
    var target=CommerceSurfaceTemplateInputs.load(config,archive,u.organizationId()).get((String)guide.get("side"));
    if(target==null)throw error(HttpStatus.CONFLICT,"当前面没有已保存的可见三维印花");
    references=List.of(archive.read(u.organizationId(),target.originalKey()),archive.read(u.organizationId(),target.photoKey()),archive.read(u.organizationId(),(String)guide.get("storage_key")));
    prompt=CommerceBatchArtworkService.surfacePrompt(target.side(),false);
   }else{
    references=List.of(archive.read(u.organizationId(),baseKey),archive.read(u.organizationId(),(String)guide.get("storage_key")));
    prompt=mockupPrompt((String)guide.get("side"));
   }
  }else if(!"PRODUCT_IMAGE".equals(kind)){
   if(request.parentId()==null)throw error(HttpStatus.BAD_REQUEST,"印花任务必须选择来源素材");
   var parent=row(request.parentId(),u);
   String expected=kind.equals("PRINT_VARIANT")?"PRINT_EXTRACT":"PRODUCT_IMAGE";
   if(!"READY".equals(parent.get("status"))||!expected.equals(parent.get("asset_kind"))||!request.product().equals(parent.get("product")))throw error(HttpStatus.CONFLICT,"来源素材类型或商品类型不匹配，必须先提取再裂变印花");
   if(kind.equals("BLANK_PRODUCT")&&request.variant()!=0)throw error(HttpStatus.BAD_REQUEST,"去除印花仅使用基础版本，不允许同时改变配色或构图");
   references=List.of(image(request.parentId(),u));prompt=kind.equals("BLANK_PRODUCT")?blankPrompt():printPrompt(kind,request.variant());
   if("UPLOADED".equals(request.product())&&"BLANK_PRODUCT".equals(kind))prompt="以参考照片中的同一商品生成去印花后的纯色商品底图。清除原装饰印花，保持原有颜色、款式、缝线、材质和可见面向。让商品像隐形人台自然撑起一样呈现体积、合理衣身空间、自然褶皱与真实光影，不是平铺或压扁；不得改变袖长、领口或剪裁，不添加人、手、人台本体、新印花或文字。只输出原照片对应的一个面，不拼接多视图。";
  }else if(request.variant()>0){
   if(request.parentId()==null)throw error(HttpStatus.BAD_REQUEST,"请先选择一张基础示例作为裂变原图");
   var parent=row(request.parentId(),u);
   if(!"READY".equals(parent.get("status"))||!"PRODUCT_IMAGE".equals(parent.get("asset_kind"))||!request.product().equals(parent.get("product"))||((Number)parent.get("variant_index")).intValue()!=0)throw error(HttpStatus.CONFLICT,"原图必须是同商品类型的已完成基础示例");
   references=List.of(image(request.parentId(),u));
   prompt+=" 原图是唯一产品参考。保留原图产品外形、缝线、尺寸比例、位置、背景和拍摄角度；只改变正面印花的配色或构图，不改变产品结构。";
  }else if(request.parentId()!=null)throw error(HttpStatus.BAD_REQUEST,"基础示例不应关联原图");
  var model=(printKind(kind)?models.transparentArtworkConnection(u.organizationId()):models.imageConnection(u.organizationId()))
    .orElseThrow(()->error(HttpStatus.CONFLICT,printKind(kind)?"当前公司没有可用的 IMG2.0 透明印花模型":"当前公司没有可用图片模型"));
  String id=UUID.randomUUID().toString();
  db.update("INSERT IGNORE INTO commerce_sample_previews(id,organization_id,created_by,request_key,product,variant_index,status,model_id,prompt,parent_id,asset_kind,created_at,updated_at) VALUES(?,?,?,?,?,?,'PREPARED',?,?,?,?,NOW(6),NOW(6))",id,u.organizationId(),u.userId(),key,request.product(),request.variant(),model.modelId(),prompt,request.parentId(),kind);
  var stored=db.queryForMap("SELECT * FROM commerce_sample_previews WHERE organization_id=? AND created_by=? AND request_key=?",u.organizationId(),u.userId(),key);id=(String)stored.get("id");
  if(!prompt.equals(stored.get("prompt"))||!Objects.equals(request.parentId(),stored.get("parent_id")))throw error(HttpStatus.CONFLICT,"同一请求编号不能改变示例内容或原图");
  if(db.update("UPDATE commerce_sample_previews SET status='SUBMITTING',updated_at=NOW(6) WHERE id=? AND status='PREPARED'",id)==1){
   try{var result=Set.of("PRINT_EXTRACT","PRINT_VARIANT").contains(kind)
     ?gateway.generateTransparentArtwork(prompt,id,model,references):gateway.generateSample(prompt,id,model,references);
    db.update("UPDATE commerce_sample_previews SET status='RESULT_RECEIVED',result_json=?,updated_at=NOW(6) WHERE id=?",json.writeValueAsString(result),id);}
   catch(CommerceImageRejectedException failure){
    var refusal=new LinkedHashMap<String,Object>();refusal.put("message",failure.getMessage());refusal.put("failureKind",failure.failureKind());refusal.put("providerStatus",failure.providerStatus());
    db.update("UPDATE commerce_sample_previews SET status='REJECTED',result_json=?,updated_at=NOW(6) WHERE id=? AND status='SUBMITTING'",json.writeValueAsString(refusal),id);
    throw error(HttpStatus.CONFLICT,failure.getMessage()+"；同一请求不会自动重试生图");
   }
   catch(RuntimeException failure){db.update("UPDATE commerce_sample_previews SET status='UNKNOWN',updated_at=NOW(6) WHERE id=?",id);throw error(HttpStatus.CONFLICT,"示例结果待核查，请勿换请求编号重复生成");}
  }
  collect(id,u);return view(row(id,u));
 }
 private void collect(String id,PlatformPrincipal u){var r=row(id,u);String status=Objects.toString(r.get("status"),"");
  if("REJECTED".equals(status))throw error(HttpStatus.CONFLICT,json.readTree(Objects.toString(r.get("result_json"),"{}")).path("message").asText("模型明确拒绝本次请求")+"；同一请求不会自动重试生图");
  if(!"RESULT_RECEIVED".equals(status)&&!(printKind(r.get("asset_kind"))&&Set.of(TRANSPARENCY_REJECTED,"READY").contains(status)))return;
  byte[] bytes;
  if(r.get("storage_key") instanceof String key&&!key.isBlank())bytes=Base64.getDecoder().decode(archive.read(u.organizationId(),key).dataBase64());
  else{
   var response=json.readValue((String)r.get("result_json"),CommerceDesignImageGateway.Response.class);
   if(response.artifact()!=null&&response.artifact().dataBase64()!=null){if(response.artifact().dataBase64().length()>12_000_000)throw error(HttpStatus.CONFLICT,"示例图片过大");bytes=Base64.getDecoder().decode(response.artifact().dataBase64());}
   else{if(response.result()==null||response.result().url()==null)throw error(HttpStatus.CONFLICT,"模型没有返回图片");bytes=downloader.download(response.result().url()).bytes();}
   var stored=archive.store(u.organizationId(),bytes);
   // Persist the paid output before enforcing quality. A rejection preserves both
   // the provider response and the exact original image, without whitening/cutout.
   db.update("UPDATE commerce_sample_previews SET storage_key=?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND created_by=?",stored.storageKey(),id,u.organizationId(),u.userId());
  }
  requireNativePrint(id,r,bytes,u);
  if(!"READY".equals(status))db.update("UPDATE commerce_sample_previews SET status='READY',updated_at=NOW(6) WHERE id=? AND organization_id=? AND created_by=? AND status IN ('RESULT_RECEIVED','TRANSPARENCY_REJECTED')",id,u.organizationId(),u.userId());
 }
 private void requireNativePrint(String id,Map<String,Object> row,byte[] bytes,PlatformPrincipal u){
  if(!printKind(row.get("asset_kind")))return;
  try{CommerceTransparentArtworkPolicy.requireNative(bytes);}
  catch(IllegalArgumentException rejected){
   db.update("UPDATE commerce_sample_previews SET status='TRANSPARENCY_REJECTED',updated_at=NOW(6) WHERE id=? AND organization_id=? AND created_by=? AND status IN ('RESULT_RECEIVED','READY','TRANSPARENCY_REJECTED')",id,u.organizationId(),u.userId());
   throw error(HttpStatus.CONFLICT,TRANSPARENCY_FAILURE+"。原因："+rejected.getMessage());
  }
 }
 private static boolean printKind(Object kind){return Set.of("PRINT_EXTRACT","PRINT_VARIANT").contains(Objects.toString(kind,""));}
 private static View view(Map<String,Object> row){String id=(String)row.get("id"),status=(String)row.get("status");return new View(id,(String)row.get("product"),((Number)row.get("variant_index")).intValue(),status,(String)row.get("model_id"),row.get("storage_key")==null||!"READY".equals(status)?null:"/api/v1/commerce-samples/"+id+"/image",(String)row.get("parent_id"),(String)row.get("asset_kind"));}
 static String prompt(String product,int variant){
  String subject=switch(Objects.toString(product,"")){case "CUSHION"->"方形布艺抱枕";case "T_SHIRT"->"圆领短袖T恤";case "TROUSERS"->"休闲长裤";default->throw error(HttpStatus.BAD_REQUEST,"示例支持抱枕、短袖和长裤");};
  if(variant<0||variant>3)throw error(HttpStatus.BAD_REQUEST,"示例版本为0至3");
  return "生成一张用于软件示例展示的原创商品摄影图："+subject+"，正面单件居中，浅灰背景，柔和棚拍光，清楚展示布料质感。原创抽象植物印花，"+List.of("简洁自然构图","暖色配色变化","冷色配色变化","疏朗几何构图变化").get(variant)+"。只生成单张完整图片，禁止拼贴、九宫格、人物、手、文字、商标、水印。不要额外展示背面，不凭空添加背面图案。这是概念示例，不是实物货源证明。";
 }
 static String printPrompt(String kind,int variant){
  String operation;
  if("PRINT_EXTRACT".equals(kind)) operation="从唯一参考商品照片提取已有装饰印花，校正透视、褶皱和曲面形变，忠实保留清晰可见的图案、线条、笔触、配色关系与相对构图，供后续识别原印花画风；只参考当前照片可见内容，不推测背面。排除叠加在照片上的半透明水印、店铺水印和摄影署名，不把水印提取为印花；设计本身的文字与水印区分处理；不增加新元素，不臆造被遮挡的图案。";
  else if("PRINT_VARIANT".equals(kind)) {
   if(variant<0||variant>3)throw error(HttpStatus.BAD_REQUEST,"印花变体方向为0至3");
   operation="参考图是从来源商品提取的独立印花，只作为画风参考。先分析其线条粗细、轮廓简化方式、笔触或颗粒、上色和明暗处理、配色关系、视觉氛围，再用一致的绘画语言创作一张新的印花。"
    +"允许大幅自由二创，主体、物种、角色身份、表情、姿态、服饰、配饰、道具和构图均可重新设计；猫可以变成狗、兔、熊、狐狸或其他动物与原创卡通角色，无须保留原物种、原角色外形、姿态或可辨识主题。"
    +"画风与原印花统一，不以临摹、只换颜色或小幅改表情代替新设计；抽象或几何原图则延续其平面造型语言自由重组，不强行加入动物。"
    +List.of("自由选择新的主体与构图。","优先创作不同物种或角色身份，并重新设计姿态。","优先创作新主体及一组与之呼应的道具或情境元素。","优先使用新主体、动态姿态和新的元素组合重构画面。").get(variant)
    +"只生成独立印花，保持为同一系列的绘画风格；不要输出分析文字。";
  } else throw error(HttpStatus.BAD_REQUEST,"不支持的印花创作类型");
  return operation+" 输出一张独立的平面二维印花 PNG，使用真实 alpha 透明通道，不带底图。图案外围和没有设计内容的空隙应透明，边缘清晰并保留自然的半透明抗锯齿。"
   +"不要额外绘制白色底板、白色矩形、白色垫片、贴纸白边或图稿外围投影；不要用不透明的底色替代透明。不要画表示透明背景的棋盘底格；原印花设计自身的格纹或棋盘元素可以作为图案。"
   +"保留原设计本来具有的白色填充、白色文字、线条、高光和有意绘制的白色图形，不要把这些白色设计元素当成背景删除。图案本身可有卡通明暗层次。"
   +"不得把实物衣服、抱枕、布料照片、缝线、商品轮廓或摄影场景作为图案底图；图案角色自身的服装和道具属于设计内容，可以按原画风保留或创作，不得误当作商品底图删除。"
   +"不要额外说明标签、分析文字或多格拼贴。透明通道与图案完整性须由后续程序检查，输出不作为生产文件保证。";
 }
 static String blankPrompt(){return "对唯一参考商品照片做局部编辑：清除标记范围内的旧装饰印花及叠加水印，恢复同一件商品的纯色布料底图；照片上的半透明文字、店铺水印也要去除，水印覆盖的背景局部延续邻近背景。衣领标签、尺码标记和场景实物上原有的文字不得当作水印删除。保留原图版型、颜色、材质纹理、缝线、轮廓、领口、袖长、肩部结构、褶皱、尺寸比例、光影、背景和拍摄视角。斜肩不得变成无袖，不改变任何衣服结构。被印花覆盖处仅延续邻近布料的底色与纹理，不增加其他图案。禁止把新印花贴上去、全图重绘成另一款商品、增加背面、人物、手、文字、拼贴或九宫格。只输出一张完整的去印花底图候选；输出需要检查是否有残留图案或版型变化，不能视为已验证成品。";}
 static String mockupPrompt(String side){
  if(!Set.of("FRONT","BACK","LEFT","RIGHT").contains(Objects.toString(side,"")))throw error(HttpStatus.BAD_REQUEST,"套版面向无效");
  return "图片1是唯一商品底图，图片2是按用户保存的坐标和大小合成的印花位置参考。只输出一张"+java.util.Map.of("FRONT","正面","BACK","背面","LEFT","左侧","RIGHT","右侧").get(side)+"商品套版效果候选。以图片1保持版型、颜色、领口、肩部、袖子、缝线、材质、轮廓、褶皱、背景、光影和拍摄角度，不重画另一款商品，斜肩不得改成无袖。将图片2的印花按照同一位置、大小、配色和方向贴合图片1的布料表面，仅做局部透视与布纹融合，不扩大或重新设计图案。参考印花外围的纯白矩形是画布背景，不是贴纸或白色布片：去掉该外围矩形，恢复底图布色；不要把图案内部本来属于设计的白色元素全部删除。如果边界无法分辨，不得编造新元素。禁止增加另一面、背面图案、文字、水印、人物、手、九宫格或对比拼贴。输出仍需检查位置漂移、版型变化和图案完整性，不代表生产文件。";
 }
 private static BusinessException error(HttpStatus status,String message){return new BusinessException(status,message);}
}
