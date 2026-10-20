package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
@Service
public class CommercePrintTemplateService {
 public record Area(String id,String side,double x,double y,double width,double height,int printWidthMm,int printHeightMm,int dpi){}
 public record Reconstruction(int schemaVersion,String algorithm,Map<String,String> sourceImages,
                              List<List<Double>> vertices,List<List<Integer>> faces,Map<String,Object> quality){}
 public record SurfacePrint(String id,String artworkId,List<Double> position,List<Double> normal,
                            List<Double> tangent,double width,double height,double rotation,String meshPart){}
 /** Coordinates describe the original view image; rotation is in radians about the rectangle centre. */
 public record PhotoPrint(String id,String artworkId,String side,double x,double y,double width,double height,double rotation){}
 public record PlacementBinding(String id,String artworkId){}
 public record Configuration(String product,List<Area> areas,Map<String,String> baseImages,Reconstruction reconstruction,
                             String sourceImageId,String referenceSetId,List<SurfacePrint> surfacePrints,
                             Map<String,String> renderedViews,Map<String,String> blankRenderedViews,
                             Map<String,String> surfaceUvViews,Map<String,String> placementMasks,
                             List<String> renderPrintIds,String renderBinding,List<PhotoPrint> photoPrints,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<CommerceTemplatePalette.Color> colors,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String mainImageStyleTemplateId){
  public Configuration(String product,List<Area> areas,Map<String,String> baseImages,Reconstruction reconstruction,
                       String sourceImageId,String referenceSetId,List<SurfacePrint> surfacePrints,
                       Map<String,String> renderedViews,Map<String,String> blankRenderedViews,
                       Map<String,String> surfaceUvViews,Map<String,String> placementMasks,
                       List<String> renderPrintIds,String renderBinding,List<PhotoPrint> photoPrints){
   this(product,areas,baseImages,reconstruction,sourceImageId,referenceSetId,surfacePrints,renderedViews,blankRenderedViews,surfaceUvViews,placementMasks,renderPrintIds,renderBinding,photoPrints,null,null);
  }
  public Configuration(String product,List<Area> areas,Map<String,String> baseImages,Reconstruction reconstruction,
                       String sourceImageId,String referenceSetId,List<SurfacePrint> surfacePrints,
                       Map<String,String> renderedViews,Map<String,String> blankRenderedViews,
                       Map<String,String> surfaceUvViews,Map<String,String> placementMasks,
                       List<String> renderPrintIds,String renderBinding){
   this(product,areas,baseImages,reconstruction,sourceImageId,referenceSetId,surfacePrints,renderedViews,blankRenderedViews,surfaceUvViews,placementMasks,renderPrintIds,renderBinding,List.of());
  }
  public Configuration(String product,List<Area> areas){this(product,areas,Map.of(),null);}
  public Configuration(String product,List<Area> areas,Map<String,String> baseImages){this(product,areas,baseImages,null);}
  public Configuration(String product,List<Area> areas,Map<String,String> baseImages,Reconstruction reconstruction){
   this(product,areas,baseImages,reconstruction,null,null,List.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),null);
  }
  public Configuration {
   colors=colors==null?null:List.copyOf(colors);
   mainImageStyleTemplateId=mainImageStyleTemplateId==null||mainImageStyleTemplateId.isBlank()?null:mainImageStyleTemplateId.strip();
   areas=areas==null?List.of():List.copyOf(areas);baseImages=immutable(baseImages);
   surfacePrints=surfacePrints==null?List.of():List.copyOf(surfacePrints);
   photoPrints=photoPrints==null?List.of():List.copyOf(photoPrints);
   renderedViews=immutable(renderedViews);blankRenderedViews=immutable(blankRenderedViews);
   surfaceUvViews=immutable(surfaceUvViews);placementMasks=immutable(placementMasks);
   renderPrintIds=renderPrintIds==null?List.of():List.copyOf(renderPrintIds);
  }
 }
 public record RenderAssets(Map<String,String> renderedViews,Map<String,String> blankRenderedViews,
                            Map<String,String> surfaceUvViews,Map<String,String> placementMasks,
                            List<String> renderPrintIds,String renderBinding){}
 public static boolean photoLayout(Configuration c){return c!=null&&!c.photoPrints().isEmpty();}
 public static List<PlacementBinding> placementBindings(Configuration c){
  return photoLayout(c)?c.photoPrints().stream().map(p->new PlacementBinding(p.id(),p.artworkId())).toList()
   :c.surfacePrints().stream().map(p->new PlacementBinding(p.id(),p.artworkId())).toList();
 }
 private static final Set<String> SIDES=Set.of("FRONT","BACK","LEFT","RIGHT");
 private static final Set<String> RENDER_SIDES=Set.of("FRONT","BACK","LEFT","RIGHT","TOP","BOTTOM");
 private static Map<String,String> immutable(Map<String,String> m){return m==null?Map.of():Map.copyOf(m);}
 public record Request(String templateId,int expectedVersion,String name,Configuration configuration){}
 public record View(String templateId,String snapshotId,int version,String name,Configuration configuration,boolean productionReady){}
 private final CommerceTemplateMapper mapper;private final ObjectMapper json;private final CommerceImageArchive archive;private final CommercePrintReferenceService references;
 @org.springframework.beans.factory.annotation.Autowired
 private ProductMainImageTemplateService mainImageStyles;
 public CommercePrintTemplateService(CommerceTemplateMapper mapper,ObjectMapper json){this(mapper,json,null);}
 public CommercePrintTemplateService(CommerceTemplateMapper mapper,ObjectMapper json,CommerceImageArchive archive){this(mapper,json,archive,null);}
 @org.springframework.beans.factory.annotation.Autowired
 public CommercePrintTemplateService(CommerceTemplateMapper mapper,ObjectMapper json,CommerceImageArchive archive,CommercePrintReferenceService references){this.mapper=mapper;this.json=json;this.archive=archive;this.references=references;}
 private void validateReference(Configuration c,PlatformPrincipal user){
  if(c.referenceSetId()==null)return;
  if(references==null)throw bad("参考图任务服务不可用");
  var ref=references.readyReferenceSet(user.organizationId(),c.referenceSetId());
  if(!Objects.equals(ref.originalImageId(),c.sourceImageId())||!ref.baseImages().equals(c.baseImages()))throw bad("商品底图与同组参考不一致，请重新读取参考图");
 }
 public CommerceImageArchive.Archived upload(byte[] bytes,PlatformPrincipal user){
  access(user,true);
  if(bytes==null||bytes.length>8*1024*1024)throw bad("底图不能超过8MiB");
  try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))){
   var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw bad("请上传有效的PNG或JPEG底图");
   var reader=readers.next();try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);
    if(w<64||h<64||w>8192||h>8192||(long)w*h>25000000)throw bad("底图须至少64像素，最长边8192像素且不超过2500万像素");
    if(reader.read(0)==null)throw bad("底图无法解码");
   }finally{reader.dispose();}
   return archive.store(user.organizationId(),bytes);
  }catch(java.io.IOException|IllegalArgumentException e){throw bad("底图内容无效，请上传PNG或JPEG图片");}
 }
 public CommerceDesignImageGateway.Reference image(String hash,PlatformPrincipal user){access(user,false);if(hash==null||!hash.matches("[a-f0-9]{64}"))throw bad("底图标识无效");return archive.read(user.organizationId(),CommerceImageArchive.sha(user.organizationId().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+hash);}
 public List<View> list(PlatformPrincipal user){access(user,false);return mapper.listKind(user.organizationId(),"PRINT_LAYOUT").stream().map(this::view).toList();}
 public View snapshot(String id,PlatformPrincipal user){
  access(user,false);var row=id==null?null:mapper.snapshot(user.organizationId(),id);
  if(row==null||!"PRINT_LAYOUT".equals(row.kind()))throw new BusinessException(HttpStatus.NOT_FOUND,"商品印花模板版本不存在或不可访问");
  return view(row);
 }
 @Transactional public View save(Request r,PlatformPrincipal user){
  access(user,true);if(r==null||r.name()==null||r.name().isBlank()||r.name().length()>120||r.expectedVersion()<0||r.expectedVersion()==Integer.MAX_VALUE)throw bad("模板名称或版本无效");validate(r.configuration());validateReference(r.configuration(),user);if(!placementBindings(r.configuration()).isEmpty())requireSurfaceRenders(r.configuration());
  if(r.configuration().mainImageStyleTemplateId()!=null){
   if(mainImageStyles==null)throw bad("主图风格服务暂不可用");
   mainImageStyles.current(r.configuration().mainImageStyleTemplateId(),user);
  }
  for(String hash:assetIds(r.configuration())){if(archive==null)throw bad("底图存储不可用");archive.read(user.organizationId(),CommerceImageArchive.sha(user.organizationId().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+hash);}
  String id=r.templateId()==null?UUID.randomUUID().toString():r.templateId();if(!id.matches("[a-fA-F0-9-]{36}"))throw bad("模板标识无效");
  if(r.expectedVersion()>0){var root=mapper.lockRoot(user.organizationId(),id);if(root==null)throw new BusinessException(HttpStatus.NOT_FOUND,"模板不存在");var latest=mapper.latest(user.organizationId(),id);if(!"PRINT_LAYOUT".equals(latest.kind())||latest.version()!=r.expectedVersion())throw new BusinessException(HttpStatus.CONFLICT,"模板版本已变化，请重新读取");}
  var row=new CommerceTemplateVersion(UUID.randomUUID().toString(),user.organizationId(),id,r.expectedVersion()+1,"PRINT_LAYOUT",r.name().trim(),json.writeValueAsString(r.configuration()),user.userId(),Instant.now());
  try{mapper.insert(row);}catch(DuplicateKeyException e){throw new BusinessException(HttpStatus.CONFLICT,"版本已存在，请重新读取");}return view(row);
 }
 public static void validate(Configuration c){
  if(c!=null){
   try{CommerceTemplatePalette.validate(c.colors());}catch(IllegalArgumentException failure){throw bad(failure.getMessage());}
   if(c.mainImageStyleTemplateId()!=null&&!c.mainImageStyleTemplateId().matches("[a-fA-F0-9-]{36}"))throw bad("主图风格模板编号无效");
  }
  if(c!=null)for(var entry:c.baseImages().entrySet())if(!Set.of("FRONT","BACK","LEFT","RIGHT").contains(entry.getKey())||!entry.getValue().matches("[a-f0-9]{64}"))throw bad("底图面向或标识无效");
  if(c==null||!Set.of("T_SHIRT","LONG_SLEEVE","SWEATSHIRT","TANK","SHORTS","TROUSERS","CUSHION","PILLOWCASE","BLANKET","TOTE","CAP","MUG","PHONE_CASE").contains(Objects.toString(c.product(),""))||c.areas().size()>8||(c.areas().isEmpty()&&c.reconstruction()==null&&!photoLayout(c)))throw bad("请选择商品类型并设置印花位置");
  var ids=new HashSet<String>();for(var a:c.areas()){
   if(a==null||a.id()==null||!a.id().matches("[a-zA-Z0-9_-]{1,40}")||!ids.add(a.id())||!Set.of("FRONT","BACK","LEFT","RIGHT").contains(Objects.toString(a.side(),"")))throw bad("印花槽位名称重复或面向无效");
   if(!Double.isFinite(a.x())||!Double.isFinite(a.y())||!Double.isFinite(a.width())||!Double.isFinite(a.height())||a.x()<0||a.y()<0||a.width()<=0||a.height()<=0||a.x()+a.width()>1.000001||a.y()+a.height()>1.000001)throw bad("印花区域须在画布边界内");
   if(a.printWidthMm()<1||a.printWidthMm()>1000||a.printHeightMm()<1||a.printHeightMm()>1000||a.dpi()<72||a.dpi()>1200)throw bad("请填写有效的毫米尺寸和DPI");
  }
  validateReconstruction(c.reconstruction(),c.baseImages());
  validateSurfacePrints(c);
  validatePhotoPrints(c);
 }
 private static Set<String> assetIds(Configuration c){
  var result=new HashSet<String>(c.baseImages().values());
  if(c.sourceImageId()!=null)result.add(c.sourceImageId());
  placementBindings(c).forEach(p->result.add(p.artworkId()));
  for(var map:List.of(c.renderedViews(),c.blankRenderedViews(),c.surfaceUvViews(),c.placementMasks()))result.addAll(map.values());
  return result;
 }
 private static void hashId(String id){if(id==null||!id.matches("[a-f0-9]{64}"))throw bad("素材标识无效");}
 private static void vector(List<Double> v,boolean unit){
  if(v==null||v.size()!=3)throw bad("印花表面坐标无效");
  double length=0;for(var n:v){if(n==null||!Double.isFinite(n)||Math.abs(n)>10)throw bad("印花表面坐标无效");length+=n*n;}
  if(unit&&Math.abs(length-1)>.02)throw bad("印花表面方向必须为单位向量");
 }
 private static void validateSurfacePrints(Configuration c){
  if(c.sourceImageId()!=null)hashId(c.sourceImageId());
  if(c.referenceSetId()!=null&&!c.referenceSetId().matches("[a-zA-Z0-9_-]{1,80}"))throw bad("参考图任务标识无效");
  if(c.surfacePrints().size()>32)throw bad("一个模型最多放置32个印花");
  if(!c.surfacePrints().isEmpty()&&(c.reconstruction()==null||c.sourceImageId()==null))throw bad("表面印花需要原始商品图片与三维模型");
  var ids=new HashSet<String>();
  for(var p:c.surfacePrints()){
   if(p==null||p.id()==null||!p.id().matches("[a-zA-Z0-9_-]{1,64}")||!ids.add(p.id()))throw bad("表面印花标识无效或重复");
   hashId(p.artworkId());vector(p.position(),false);vector(p.normal(),true);vector(p.tangent(),true);
   double dot=0;for(int i=0;i<3;i++)dot+=p.normal().get(i)*p.tangent().get(i);
   if(Math.abs(dot)>.02)throw bad("印花切线与表面法线须垂直");
   if(!Double.isFinite(p.width())||!Double.isFinite(p.height())||p.width()<=0||p.height()<=0||p.width()>20||p.height()>20||!Double.isFinite(p.rotation())||Math.abs(p.rotation())>Math.PI*8)throw bad("印花大小或旋转角度无效");
   if(p.meshPart()==null||!p.meshPart().matches("[a-zA-Z0-9_-]{1,64}"))throw bad("印花模型部位无效");
  }
  for(var map:List.of(c.renderedViews(),c.blankRenderedViews(),c.surfaceUvViews(),c.placementMasks()))for(var e:map.entrySet()){
   if(!RENDER_SIDES.contains(e.getKey()))throw bad("印花渲染面向无效");hashId(e.getValue());
  }
 }
 private static void validatePhotoPrints(Configuration c){
  if(!photoLayout(c))return;
  if(c.photoPrints().size()>32)throw bad("四面图最多放置32个印花");
  if(c.reconstruction()!=null||!c.surfacePrints().isEmpty()||!c.areas().isEmpty())throw bad("照片印花不能与三维或旧版槽位混用，请保存当前四面图设置");
  if(c.sourceImageId()==null||c.referenceSetId()==null||!c.baseImages().keySet().containsAll(SIDES))throw bad("照片印花需要原始商品图和同组四面参考图");
  var ids=new HashSet<String>();
  for(var p:c.photoPrints()){
   if(p==null||p.id()==null||!p.id().matches("[a-zA-Z0-9_-]{1,64}")||!ids.add(p.id()))throw bad("照片印花标识无效或重复");
   hashId(p.artworkId());
   if(!SIDES.contains(Objects.toString(p.side(),"")))throw bad("照片印花面向无效");
   if(!Double.isFinite(p.x())||!Double.isFinite(p.y())||!Double.isFinite(p.width())||!Double.isFinite(p.height())
       ||p.x()<0||p.y()<0||p.width()<=0||p.height()<=0||p.x()+p.width()>1.000001||p.y()+p.height()>1.000001)
    throw bad("照片印花区域须在图片边界内");
   if(!Double.isFinite(p.rotation())||Math.abs(p.rotation())>Math.PI*8)throw bad("照片印花旋转角度无效");
  }
 }
 /** A content binding detects stale geometry, placements and swapped assets; it is not visual approval. */
 public static void requireSurfaceRenders(Configuration c){
  validate(c);if(placementBindings(c).isEmpty())return;
  for(var map:List.of(c.renderedViews(),c.blankRenderedViews(),c.surfaceUvViews(),c.placementMasks()))
   if(!map.keySet().containsAll(SIDES)||!map.keySet().equals(c.renderedViews().keySet()))throw bad("请先保存当前四面的完整印花效果和定位图");
  if(photoLayout(c)&&!c.renderedViews().keySet().equals(SIDES))throw bad("照片印花只使用正面、背面、左侧和右侧参考图");
  if(!c.renderPrintIds().equals(placementBindings(c).stream().map(PlacementBinding::id).toList()))throw bad("印花渲染顺序已变化，请重新保存");
  if(!Objects.equals(c.renderBinding(),renderFingerprint(c))&&(photoLayout(c)||!Objects.equals(c.renderBinding(),legacyRenderFingerprint(c))))throw bad("底图或印花位置已变化，请重新保存四面效果");
 }
 public static void validateRenderBinding(Configuration c){requireSurfaceRenders(c);}
 private static Object canonical(Object value){
  if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;}
  if(value instanceof List<?> list)return list.stream().map(CommercePrintTemplateService::canonical).toList();return value;
 }
 public static String renderFingerprint(Configuration c){return (photoLayout(c)?"v3:":"v2:")+fingerprint(c,true);}
 private static String legacyRenderFingerprint(Configuration c){return fingerprint(c,false);}
 private static Object stableRenderNumbers(Object value){
  if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),stableRenderNumbers(v)));return sorted;}
  if(value instanceof List<?> list)return list.stream().map(CommercePrintTemplateService::stableRenderNumbers).toList();
  // MySQL JSON parsing can move a double by one ULP. Nine decimal places are
  // far below a rendered pixel; normalize before hashing, never relax asset IDs.
  if(value instanceof Number number)return new java.math.BigDecimal(number.toString())
    .setScale(9,java.math.RoundingMode.HALF_UP).stripTrailingZeros();
  return value;
 }
 private static String fingerprint(Configuration c,boolean stable){
  var mapper=new ObjectMapper();var data=new LinkedHashMap<String,Object>();
  data.put("version",photoLayout(c)?3:stable?2:1);data.put("product",c.product());data.put("sourceImageId",c.sourceImageId());data.put("referenceSetId",c.referenceSetId());
  data.put("baseImages",c.baseImages());data.put("reconstruction",c.reconstruction());data.put("surfacePrints",c.surfacePrints());
  if(photoLayout(c))data.put("photoPrints",c.photoPrints());
  data.put("renderPrintIds",c.renderPrintIds());data.put("renderedViews",c.renderedViews());data.put("blankRenderedViews",c.blankRenderedViews());
  data.put("surfaceUvViews",c.surfaceUvViews());data.put("placementMasks",c.placementMasks());
  var values=mapper.convertValue(data,Map.class);
  return CommerceImageArchive.sha(mapper.writeValueAsBytes(stable?stableRenderNumbers(values):canonical(values)));
 }
 private static java.awt.image.BufferedImage decodeRender(byte[] bytes)throws java.io.IOException{
  try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))){
   var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw bad("渲染PNG无法读取");
   var reader=readers.next();try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);
    if(w<256||h<256||w>2048||h>2048)throw bad("渲染图片须为256至2048像素");return reader.read(0);
   }finally{reader.dispose();}
  }
 }
 public RenderAssets archiveRenders(Configuration c,Map<String,byte[]> files,PlatformPrincipal user){
  access(user,true);validate(c);validateReference(c,user);if(placementBindings(c).isEmpty())throw bad("请先在商品图上放置印花");
  for(String hash:assetIds(c))image(hash,user);
  var images=new TreeMap<String,String>();var blanks=new TreeMap<String,String>();var uvs=new TreeMap<String,String>();var masks=new TreeMap<String,String>();
  var targets=Map.of("image",images,"blank",blanks,"uv",uvs,"mask",masks);
  var placements=placementBindings(c);var visiblePhotoPrints=new HashSet<String>();
  var viewSides=new LinkedHashSet<String>(SIDES);
  if(photoLayout(c)&&files.keySet().stream().anyMatch(key->key.endsWith("_TOP")||key.endsWith("_BOTTOM")))throw bad("照片印花仅支持已有的四面参考图");
  for(String side:List.of("TOP","BOTTOM"))if(files.keySet().stream().anyMatch(key->key.endsWith("_"+side)))viewSides.add(side);
  for(String side:viewSides){int width=0,height=0;
   java.awt.image.BufferedImage photoBase=photoLayout(c)?CommerceMockupComposer.decode(Base64.getDecoder().decode(image(c.baseImages().get(side),user).dataBase64())):null;
   for(String kind:List.of("image","blank","mask","uv")){
    var bytes=files.get(kind+"_"+side);
    if(bytes==null||bytes.length>8*1024*1024)throw bad("缺少四面印花渲染文件，或图片过大");
    try{
     if(bytes.length<8||bytes[0]!=(byte)0x89||bytes[1]!='P'||bytes[2]!='N'||bytes[3]!='G')throw bad("印花定位文件必须使用无损PNG");
     var decoded=decodeRender(bytes);
     if(decoded==null||decoded.getWidth()<256||decoded.getHeight()<256||decoded.getWidth()>2048||decoded.getHeight()>2048)throw bad("渲染图片须为256至2048像素");
     if(width>0&&(width!=decoded.getWidth()||height!=decoded.getHeight()))throw bad("同一面的效果与定位图尺寸必须一致");
     width=decoded.getWidth();height=decoded.getHeight();
     if(photoBase!=null&&kind.equals("blank")){
      if(width!=photoBase.getWidth()||height!=photoBase.getHeight())throw bad("照片底图与四面参考尺寸不一致，请按原图尺寸保存");
      for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(decoded.getRGB(x,y)!=photoBase.getRGB(x,y))throw bad("照片底图与同组参考像素不一致，请重新保存四面效果");
     }
     if(kind.equals("uv")){
      for(int y=0;y<height;y++)for(int x=0;x<width;x++){int pixel=decoded.getRGB(x,y),slot=pixel&255;
       if((pixel>>>24)>0){
        if(slot<1||slot>placements.size())throw bad("印花UV图包含无效槽位");
        if(photoLayout(c)&&!side.equals(c.photoPrints().get(slot-1).side()))throw bad("照片印花定位图混入另一面的位置，请重新保存四面效果");
        if(photoLayout(c))visiblePhotoPrints.add(placements.get(slot-1).id());
       }
      }
     }
    }catch(java.io.IOException e){throw bad("渲染PNG无法读取");}
    targets.get(kind).put(side,upload(bytes,user).sha256());
   }
  }
  if(photoLayout(c)&&!visiblePhotoPrints.containsAll(placements.stream().map(PlacementBinding::id).toList()))throw bad("部分照片印花未出现在定位图中，请调整位置或大小后保存");
  var order=placements.stream().map(PlacementBinding::id).toList();
  var ready=new Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),c.referenceSetId(),c.surfacePrints(),images,blanks,uvs,masks,order,null,c.photoPrints());
  return new RenderAssets(images,blanks,uvs,masks,order,renderFingerprint(ready));
 }
 /** A saved browser fit is a bounded preview, never server-verified reconstruction evidence. */
 private static void validateReconstruction(Reconstruction r,Map<String,String> baseImages){
  if(r==null)return;
  if(r.schemaVersion()!=1||!"garment-coarse-v1".equals(r.algorithm()))throw bad("三维粗模版本不受支持，请重新生成");
  if(r.sourceImages()==null||r.sourceImages().isEmpty()||r.sourceImages().size()>4)throw bad("三维粗模缺少来源底图");
  if(!r.sourceImages().equals(baseImages))throw bad("三维粗模与当前底图不一致，请重新生成");
  for(var source:r.sourceImages().entrySet()){
   if(!Set.of("FRONT","BACK","LEFT","RIGHT").contains(source.getKey())||source.getValue()==null
           ||!source.getValue().matches("[a-f0-9]{64}")||!source.getValue().equals(baseImages.get(source.getKey())))
    throw bad("三维粗模与当前底图不一致，请重新生成");
  }
  if(r.vertices()==null||r.vertices().size()<3||r.vertices().size()>15000
          ||r.faces()==null||r.faces().isEmpty()||r.faces().size()>30000)throw bad("三维粗模网格数量超出范围");
  for(var vertex:r.vertices()){
   if(vertex==null||vertex.size()!=3)throw bad("三维粗模顶点格式无效");
   for(Double coordinate:vertex)if(coordinate==null||!Double.isFinite(coordinate)||Math.abs(coordinate)>10)
    throw bad("三维粗模坐标无效或超出范围");
  }
  for(var face:r.faces()){
   if(face==null||face.size()!=3)throw bad("三维粗模三角面格式无效");
   for(Integer index:face)if(index==null||index<0||index>=r.vertices().size())throw bad("三维粗模顶点索引无效");
   if(face.get(0).equals(face.get(1))||face.get(1).equals(face.get(2))||face.get(0).equals(face.get(2)))
    throw bad("三维粗模存在退化三角面");
   var a=r.vertices().get(face.get(0));var b=r.vertices().get(face.get(1));var c=r.vertices().get(face.get(2));
   double ux=b.get(0)-a.get(0),uy=b.get(1)-a.get(1),uz=b.get(2)-a.get(2);
   double vx=c.get(0)-a.get(0),vy=c.get(1)-a.get(1),vz=c.get(2)-a.get(2);
   double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
   if(nx*nx+ny*ny+nz*nz<1e-20)throw bad("三维粗模存在退化三角面");
  }
  if(r.quality()!=null)validateQuality(r.quality(),0,new int[]{0});
 }
 private static void validateQuality(Object value,int depth,int[] nodes){
  if(depth>6||++nodes[0]>512)throw bad("三维粗模检测报告过大");
  if(value==null||value instanceof Boolean)return;
  if(value instanceof Number number){if(!Double.isFinite(number.doubleValue()))throw bad("三维粗模检测数值无效");return;}
  if(value instanceof String string){if(string.length()>512)throw bad("三维粗模检测文字过长");return;}
  if(value instanceof Map<?,?> map){
   if(map.size()>32)throw bad("三维粗模检测报告过大");
   for(var entry:map.entrySet()){
    if(!(entry.getKey() instanceof String key)||!key.matches("[A-Za-z0-9_-]{1,64}"))throw bad("三维粗模检测字段无效");
    validateQuality(entry.getValue(),depth+1,nodes);
   }
   return;
  }
  if(value instanceof List<?> list){if(list.size()>16)throw bad("三维粗模检测报告过大");for(var item:list)validateQuality(item,depth+1,nodes);return;}
  throw bad("三维粗模检测报告格式无效");
 }
 private View view(CommerceTemplateVersion row){return new View(row.templateId(),row.id(),row.version(),row.name(),json.readValue(row.configJson(),Configuration.class),false);}
 private static void access(PlatformPrincipal u,boolean edit){if(u==null||u.organizationId()==null||!(edit?u.permissionCodes().contains("product:edit"):u.permissionCodes().contains("product:view")||u.permissionCodes().contains("product:publish")))throw new BusinessException(HttpStatus.FORBIDDEN,"无印花模板权限");}
 private static BusinessException bad(String text){return new BusinessException(HttpStatus.BAD_REQUEST,text);}
}
