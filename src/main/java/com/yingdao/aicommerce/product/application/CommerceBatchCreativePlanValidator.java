package com.yingdao.aicommerce.product.application;

import java.util.*;

/** Validates explicit immutable source choices before a batch may submit paid generation. */
public final class CommerceBatchCreativePlanValidator {
 public record Source(String captureId,long version,List<String> imageIds){}
 public record Selection(String captureId,long version,String imageId,List<CommerceArtworkLocalEdit.Region> cleanupAreas,
                         List<CommerceSourceColorResolver.Color> colorTargets,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<String> detailImageIds){
  public Selection(String captureId,long version,String imageId,List<CommerceArtworkLocalEdit.Region> cleanupAreas,List<CommerceSourceColorResolver.Color> colorTargets){this(captureId,version,imageId,cleanupAreas,colorTargets,List.of());}
  public Selection(String captureId,long version,String imageId,List<CommerceArtworkLocalEdit.Region> cleanupAreas){this(captureId,version,imageId,cleanupAreas,List.of());}
  public Selection(String captureId,long version,String imageId){this(captureId,version,imageId,List.of(),List.of());}
  public Selection {cleanupAreas=cleanupAreas==null?List.of():List.copyOf(cleanupAreas);colorTargets=colorTargets==null?List.of():List.copyOf(colorTargets);detailImageIds=detailImageIds==null?List.of():List.copyOf(detailImageIds);}
 }
 public static final String LOCAL_PHOTO_POLICY="LOCAL_PHOTO_V1";
 public static final String GROK_SCENE_POLICY="GROK_SCENE_V1";
 public static final String GROK_DRAPE_POLICY="GROK_DRAPE_REUSE_V1";
 public static final int DETAIL_MAX_ATTEMPTS=2;
 public static boolean modelDetailsSupported(CommercePrintTemplateService.Configuration configuration){
  return configuration!=null&&Set.of("T_SHIRT","LONG_SLEEVE","SWEATSHIRT","TANK","SHORTS","TROUSERS").contains(Objects.toString(configuration.product(),""));
 }
 public record ReusedDesign(Map<String,String> groupArtifacts){
  public ReusedDesign {groupArtifacts=groupArtifacts==null?Map.of():Collections.unmodifiableMap(new TreeMap<>(groupArtifacts));}
 }
 public record FrozenArtwork(String id,String storageKey,String contentSha256){}
 public record FrozenDesign(Map<String,FrozenArtwork> groupArtifacts){
  public FrozenDesign {groupArtifacts=groupArtifacts==null?Map.of():Collections.unmodifiableMap(new TreeMap<>(groupArtifacts));}
 }
 public record Plan(String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint,String variationArtworkId,String artworkSource,String outputPolicy,String renderPolicy,
                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<FrozenDesign> reusedArtworks){
  public Plan(String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint,String variationArtworkId,String artworkSource,String outputPolicy,String renderPolicy){this(printTemplateSnapshotId,sources,variantsPerPrint,variationArtworkId,artworkSource,outputPolicy,renderPolicy,List.of());}
  public Plan(String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint,String variationArtworkId,String artworkSource,String outputPolicy){this(printTemplateSnapshotId,sources,variantsPerPrint,variationArtworkId,artworkSource,outputPolicy,null);}
  public Plan(String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint,String variationArtworkId,String artworkSource){this(printTemplateSnapshotId,sources,variantsPerPrint,variationArtworkId,artworkSource,null);}
  public Plan(String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint,String variationArtworkId){this(printTemplateSnapshotId,sources,variantsPerPrint,variationArtworkId,null,null);}
  public Plan(String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint){this(printTemplateSnapshotId,sources,variantsPerPrint,null,null,null);}
  public Plan {sources=sources==null?null:List.copyOf(sources);reusedArtworks=reusedArtworks==null?List.of():List.copyOf(reusedArtworks);}
 }
 public static boolean sourceProductStyle(Plan plan){return plan!=null&&"SOURCE_PRODUCT".equals(plan.artworkSource());}
 public static boolean savedArtwork(Plan plan){return plan!=null&&"SAVED_ARTWORK".equals(plan.artworkSource());}
 public static boolean groupedArtwork(Plan plan){return sourceProductStyle(plan)||savedArtwork(plan);}
 public static void validateReusedDesigns(List<ReusedDesign> designs,CommercePrintTemplateService.Configuration configuration){
  if(designs==null||designs.isEmpty()||designs.size()>20)throw new IllegalArgumentException("请选择1至20款已有印花组合");
  if(!CommercePrintTemplateService.photoLayout(configuration))throw new IllegalArgumentException("已有印花复用需要保存好的四面照片印花模板");
  var groups=new HashSet<>(CommerceSurfaceTemplateInputs.artworkGroups(configuration));
  var seen=new HashSet<Map<String,String>>();
  for(var design:designs)if(design==null||!design.groupArtifacts().keySet().equals(groups)
    ||design.groupArtifacts().values().stream().anyMatch(id->id==null||id.isBlank()||id.length()>100)
    ||!seen.add(design.groupArtifacts()))throw new IllegalArgumentException("每款已有印花须覆盖模板全部位置组，且不能重复选择同一组合");
 }
 public static void validateFrozenArtworks(Plan plan,CommercePrintTemplateService.Configuration configuration,String organizationId){
  if(!savedArtwork(plan)){if(!plan.reusedArtworks().isEmpty())throw new IllegalArgumentException("已有印花与创作模式不一致");return;}
  if(!localPhotoOutput(plan)||!sourceColorOutput(plan)||plan.variationArtworkId()!=null||plan.variantsPerPrint()!=plan.reusedArtworks().size())throw new IllegalArgumentException("已有印花的固定款数或出图配置不一致");
  var requested=new ArrayList<ReusedDesign>();var contentDesigns=new HashSet<Map<String,String>>();
  for(var design:plan.reusedArtworks()){
   var ids=new TreeMap<String,String>();var content=new TreeMap<String,String>();
   for(var entry:design.groupArtifacts().entrySet()){
    var art=entry.getValue();
    if(art==null||art.contentSha256()==null||!art.contentSha256().matches("[a-f0-9]{64}")
      ||!Objects.equals(CommerceSurfaceTemplateInputs.key(organizationId,art.contentSha256()),art.storageKey()))throw new IllegalArgumentException("已有印花的组织或原始图片指纹不一致");
    ids.put(entry.getKey(),art.id());content.put(entry.getKey(),art.contentSha256());
   }
   if(!contentDesigns.add(content))throw new IllegalArgumentException("所选款式的印花内容重复，请为不同款选择不同印花组合");
   requested.add(new ReusedDesign(ids));
  }
  validateReusedDesigns(requested,configuration);
 }
 public static boolean sourceColorOutput(Plan plan){return plan!=null&&"SOURCE_SKU_COLORS".equals(plan.outputPolicy());}
 public static boolean drapeOutput(Plan plan){return plan!=null&&GROK_DRAPE_POLICY.equals(plan.renderPolicy());}
 public static boolean sceneOutput(Plan plan){return plan!=null&&GROK_SCENE_POLICY.equals(plan.renderPolicy());}
 public static boolean localPhotoOutput(Plan plan){return plan!=null&&(LOCAL_PHOTO_POLICY.equals(plan.renderPolicy())||sceneOutput(plan)||drapeOutput(plan));}
 public static void validateRenderPolicy(String policy){
  if(policy!=null&&!Set.of(LOCAL_PHOTO_POLICY,GROK_SCENE_POLICY,GROK_DRAPE_POLICY).contains(policy))throw new IllegalArgumentException("商品套版方式无效，请重新保存创作配置");
 }
 public static void validateRenderTemplate(String policy,CommercePrintTemplateService.Configuration configuration){
  validateRenderPolicy(policy);
  if((GROK_SCENE_POLICY.equals(policy)||GROK_DRAPE_POLICY.equals(policy))&&!CommercePrintTemplateService.photoLayout(configuration))
   throw new IllegalArgumentException("场景出图需要已保存的四面照片印花模板");
  if(GROK_DRAPE_POLICY.equals(policy)&&configuration.photoPrints().stream().filter(print->"FRONT".equals(print.side())).count()!=1)
   throw new IllegalArgumentException("一次母图复用目前需要一个正面印花位置，背面和侧面位置可保留；请调整正面位置后保存");
  if(GROK_DRAPE_POLICY.equals(policy))CommerceDrapeReuseWorkflow.printId(configuration);
 }
 public static void validateOutputPolicy(String policy){
  if(policy!=null&&!"SOURCE_SKU_COLORS".equals(policy))throw new IllegalArgumentException("商品出图规则无效，请重新保存创作配置");
 }
 public static void validateColorTargets(Selection source,List<String> readableImages){
  if(source.colorTargets().isEmpty())throw new IllegalArgumentException("缺少已固定的商品颜色，请重新保存创作配置");
  var seen=new HashSet<String>();
  for(var color:source.colorTargets()) {
   if(color==null||color.key()==null||color.key().isBlank()||!seen.add(color.key())||color.label()==null||color.label().isBlank())
    throw new IllegalArgumentException("商品颜色分组无效或重复，请重新保存创作配置");
   if(!color.hasColorReference()||color.imageId()==null||color.imageId().isBlank()||(readableImages!=null&&!readableImages.contains(color.imageId())))
    throw new IllegalArgumentException("颜色「"+color.label()+"」缺少本商品可读取的 SKU 色样图，请补齐对应颜色图片后重新保存；不会猜测颜色");
  }
 }
 public static void validateDetailImageIds(Selection source,List<String> readableImages){
  var seen=new HashSet<String>();
  for(String id:source.detailImageIds())if(id==null||id.isBlank()||id.length()>100||!seen.add(id)
    ||(readableImages!=null&&!readableImages.contains(id)))
   throw new IllegalArgumentException("固定详情图不属于当前来源商品、已丢失或重复，请重新保存创作配置");
 }
 public static void validateArtworkSource(String source){
  if(source!=null&&!Set.of("SOURCE_PRODUCT","TEMPLATE_ARTWORK","SAVED_ARTWORK").contains(source))throw new IllegalArgumentException("印花风格来源无效，请重新保存创作配置");
 }
 private CommerceBatchCreativePlanValidator(){}
 public static Plan validate(String printSnapshot,String templateKind,List<Source> batchSources,List<Selection> selected,int variants){
  if(printSnapshot==null||printSnapshot.isBlank()||!"PRINT_LAYOUT".equals(templateKind))throw new IllegalArgumentException("请选择独立的商品套版模板版本，不能使用铺货规则模板替代");
  if(variants<1||variants>20)throw new IllegalArgumentException("每组印花裂变数量须为1至20");
  if(batchSources==null||batchSources.isEmpty()||selected==null)throw new IllegalArgumentException("缺少批次来源与提取图片选择");
  var expected=new LinkedHashMap<String,Source>();
  for(var source:batchSources){
   if(source==null||source.captureId()==null||source.imageIds()==null)throw new IllegalArgumentException("批次来源不完整");
   var old=expected.putIfAbsent(source.captureId(),source);
   if(old!=null&&(old.version()!=source.version()||!new HashSet<>(old.imageIds()).equals(new HashSet<>(source.imageIds()))))throw new IllegalArgumentException("同一商品在不同店铺目标中出现不一致的来源版本");
  }
  var seen=new HashSet<String>();
  for(var selection:selected){
   if(selection==null||!seen.add(selection.captureId()))throw new IllegalArgumentException("同一来源商品只能选择一次提取原图");
   if(!selection.cleanupAreas().isEmpty())CommerceArtworkLocalEdit.validate(selection.cleanupAreas());
   var source=expected.get(selection.captureId());
   if(source==null||source.version()!=selection.version())throw new IllegalArgumentException("所选来源不属于当前批次固定版本");
   if(selection.imageId()==null||!source.imageIds().contains(selection.imageId()))throw new IllegalArgumentException("提取原图不属于所选来源商品，不能猜测或使用任意外部地址");
   validateDetailImageIds(selection,source.imageIds());
  }
  if(!seen.equals(expected.keySet()))throw new IllegalArgumentException("请为每个来源商品选择提取原图；不会默认为第一张或推测背面");
  return new Plan(printSnapshot,List.copyOf(selected),variants);
 }
}
