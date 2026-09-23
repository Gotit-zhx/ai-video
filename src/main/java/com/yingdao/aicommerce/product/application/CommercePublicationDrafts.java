package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class CommercePublicationDrafts {
 public static final String PREFIX="/api/v1/commerce-publish-images/";
 private static final String HTML_ATTRIBUTES="(?:[^<>\"']|\"[^\"]*\"|'[^']*')*";
 private static final Pattern MEDIA_CONTENT=Pattern.compile("<(video|audio|iframe|object|svg|canvas)\\b"+HTML_ATTRIBUTES+">.*?</\\1\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
 private static final Pattern MEDIA_TAG=Pattern.compile("</?(?:img|image|picture|source|video|audio|track|iframe|object|embed|svg|canvas)\\b"+HTML_ATTRIBUTES+">",Pattern.CASE_INSENSITIVE);
 private static final Pattern HTML_TAG=Pattern.compile("<[a-z][a-z0-9:-]*\\b"+HTML_ATTRIBUTES+">",Pattern.CASE_INSENSITIVE);
 private static final Pattern BACKGROUND_MEDIA=Pattern.compile("\\s+(?:background\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)|style\\s*=\\s*(?:\"[^\"]*url\\s*\\([^\"]*\"|'[^']*url\\s*\\([^']*'))",Pattern.CASE_INSENSITIVE);
 private static final Pattern EMPTY_MEDIA_LINK=Pattern.compile("<a\\b"+HTML_ATTRIBUTES+">\\s*</a\\s*>",Pattern.CASE_INSENSITIVE);
 private static final Pattern SIZE_CHART_LABEL=Pattern.compile("^(?:size[ _-]*(?:chart|guide)|measurement[ _-]*chart|尺码表|尺碼表|尺寸表|尺码指南|尺碼指南)(?:\\s*[（(](?:cm|mm|inch|inches|厘米|英寸)[）)])?$",Pattern.CASE_INSENSITIVE);
 private final JdbcTemplate db;private final ProductCaptureService service;private final ProductCaptureMapper captures;
 private final ProductCaptureImageMapper images;private final ProductCaptureSkuMapper skus;private final ObjectMapper json;private final CommerceImageArchive archive;
 @org.springframework.beans.factory.annotation.Autowired
 private com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader imageDownloader;
 @org.springframework.beans.factory.annotation.Autowired
 private CommerceDetailRestyleRevision restyles;
 public CommercePublicationDrafts(JdbcTemplate db,ProductCaptureService service,ProductCaptureMapper captures,ProductCaptureImageMapper images,ProductCaptureSkuMapper skus,ObjectMapper json,CommerceImageArchive archive){this.db=db;this.service=service;this.captures=captures;this.images=images;this.skus=skus;this.json=json;this.archive=archive;}
 @Transactional
 public String prepare(String execution,CommerceBatchItem item,int variant,List<Map<String,Object>> manifest,PlatformPrincipal user) {
  if(restyles!=null)restyles.lockExecution(execution,user.organizationId());
  db.update("INSERT IGNORE INTO commerce_batch_publications(organization_id,execution_id,item_id,variant_index,manifest_json) VALUES(?,?,?,?,?)",user.organizationId(),execution,item.id(),variant,json.writeValueAsString(manifest));
  var row=db.queryForMap("SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? FOR UPDATE",user.organizationId(),execution,item.id(),variant);
  if(!json.readTree((String)row.get("manifest_json")).equals(json.valueToTree(manifest))){
   if(restyles==null)throw new IllegalArgumentException("发布素材已固定，不能覆盖已提交的商品");
   return restyles.refresh(user.organizationId(),execution,item.id(),variant,row,manifest);
  }
  if(row.get("capture_id")!=null)return (String)row.get("capture_id");
  var detail=service.get(item.captureId(),user);var original=detail.capture();
  if(original.getVersion()!=item.captureVersion())throw new IllegalArgumentException("来源版本已变化");
  var sourceData=json.readValue(original.getNormalizedData(),Map.class);
  var normalized=publicationData(sourceData);
  String description=publicationDescription(original.getDescription());
  normalized.put("description",description);
  var detailKeys=detailKeys(manifest);
  var capture=new ProductCapture(user.organizationId(),user.userId(),"commerce-"+execution+"-"+item.id()+"-"+variant,
   CommerceImageArchive.sha(json.writeValueAsBytes(manifest)),original.getSourcePlatform(),original.getSourceUrl(),original.getSourceShopId(),original.getSourceProductId(),
   original.getTitle(),description,original.getCategoryName(),original.getBrandName(),original.getCurrency(),original.getPriceMin(),original.getPriceMax(),json.writeValueAsString(normalized),"{}");
  captures.insert(capture);
  // Reserve the gallery positions immediately after MAIN for generated details.
  // Shopee uses only the first nine gallery images; SKU color bindings use URLs.
  Map<String,String> skuImages=new HashMap<>();int position=1+detailKeys.size();boolean first=true;
  for(var entry:manifest){
   String front=(String)entry.get("frontKey");String url=addImage(capture,front,first?"MAIN":"VARIANT",first?0:position++,(String)entry.get("colorLabel"));first=false;
   if(entry.get("backKey") instanceof String back){
    addImage(capture,back,"GALLERY",position++,entry.get("colorLabel")+" 背面");
    String pair=archive.store(user.organizationId(),pair(bytes(user.organizationId(),front),bytes(user.organizationId(),back))).storageKey();
    url=addImage(capture,pair,"VARIANT",position++,entry.get("colorLabel")+" 正背面");
   }
   for(String side:List.of("left","right"))if(entry.get(side+"Key") instanceof String view)
    addImage(capture,view,"GALLERY",position++,entry.get("colorLabel")+(side.equals("left")?" 左侧":" 右侧"));
   for(Object sku:(List<?>)entry.get("skuKeys"))if(skuImages.put((String)sku,url)!=null)throw new IllegalArgumentException("SKU 被分配到多个颜色");
  }
  for(int index=0;index<detailKeys.size();index++)addImage(capture,detailKeys.get(index),"DETAIL",index+1,"商品详情 "+(index+1));
  var plannedSkus=plannedSkus(detail.skus(),manifest,skuImages);
  for(var planned:plannedSkus){
   var sku=planned.source();
   skus.insert(new ProductCaptureSku(user.organizationId(),capture.getId(),sku.getSkuKey(),sku.getExternalSkuId(),sku.getSellerSku(),planned.name(),sku.getPrice(),sku.getOriginalPrice(),sku.getStock(),planned.imageUrl(),"COMMERCE_GENERATED",planned.attributesJson(),"{}",planned.order()));
  }
  // Sizing evidence remains useful after changing the artwork. Include charts identified
  // during watermark detection, using source ownership to read protected watermark URLs.
  var detectedCharts=new HashSet<>(db.queryForList("SELECT DISTINCT image_id FROM product_watermark_checks WHERE organization_id=? AND capture_id=? AND status='READY' AND JSON_UNQUOTE(JSON_EXTRACT(detection_json,'$.imageType'))='SIZE_CHART'",
          String.class,user.organizationId(),original.getId()));
  for(var chart:sourceSizeCharts(detail,sourceData.get("sizeChartImageId"),detectedCharts)){
   var binary=imageDownloader.downloadProductImage(chart.getProcessedUrl(),chart);
   String chartKey=archive.store(user.organizationId(),binary.bytes()).storageKey();
   String chartUrl=PREFIX+chartKey;
   var copied=new ProductCaptureImage(user.organizationId(),capture.getId(),chartUrl+"?original","SIZE_CHART",chart.getAltText(),position++,binary.contentType(),json.writeValueAsString(Map.of("commerceStorageKey",chartKey)));
   copied.processed(chartUrl);images.insert(copied);
   if(chart.getId().equals(sourceData.get("sizeChartImageId")))normalized.put("sizeChartImageId",copied.getId());
  }
  normalized.put("imageCompleteness",Map.of("complete",true,"expectedCount",position,"capturedCount",position,"requiredSkuImageCount",manifest.size(),"coveredSkuImageCount",manifest.size(),"requiredDetailImageCount",detailKeys.size(),"coveredDetailImageCount",detailKeys.size(),"issues",List.of()));
  // This is a new product: source-page completeness (96 old styles in this case)
  // cannot describe the generated color/size matrix after style replacement.
  // plannedSkus has already checked every source key and rejected conflicting
  // prices/stock for combinations that collapse into one generated SKU.
  normalized.put("skuCompleteness",generatedSkuCompleteness(plannedSkus));
  capture.updateDraft(capture.getTitle(),description,capture.getCategoryName(),capture.getBrandName(),capture.getCurrency(),capture.getPriceMin(),capture.getPriceMax(),json.writeValueAsString(normalized));
  capture.imagesReady();captures.updateById(capture);
  service.approve(capture.getId(),user); // Data validation only; user's publish instruction supplies approval.
  db.update("UPDATE commerce_batch_publications SET capture_id=? WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=?",capture.getId(),user.organizationId(),execution,item.id(),variant);
  return capture.getId();
 }
 record PlannedSku(ProductCaptureSku source,String name,String attributesJson,String imageUrl,int order) {}
 Map<String,Object> generatedSkuCompleteness(List<PlannedSku> planned){
  int count=planned.size();
  int dimensions=planned.isEmpty()?0:
    json.readValue(planned.getFirst().attributesJson(),Map.class).size();
  return Map.of("expectedCount",count,"capturedCount",count,"complete",count>0,
    "source","COMMERCE_GENERATED","hasVariants",count>1,"variantDimensionCount",dimensions,"issues",List.of());
 }
 List<PlannedSku> plannedSkus(List<ProductCaptureSku> source,List<Map<String,Object>> manifest,Map<String,String> skuImages){
  var byKey=new HashMap<String,ProductCaptureSku>();
  for(var sku:source)if(byKey.put(sku.getSkuKey(),sku)!=null)throw new IllegalArgumentException("来源 SKU 编号重复");
  var result=new ArrayList<PlannedSku>();
  var assigned=new HashSet<String>();
  for(var color:manifest){
   String label=Objects.toString(color.get("colorLabel"),"").strip();
   if(label.isEmpty()||!(color.get("skuKeys") instanceof List<?> keys))throw new IllegalArgumentException("颜色分组缺少 SKU");
   var grouped=new LinkedHashMap<String,ProductCaptureSku>();
   var attributesByGroup=new HashMap<String,Map<String,String>>();
   for(var rawKey:keys){
    String key=Objects.toString(rawKey,""),image=skuImages.get(key);
    var sku=byKey.get(key);
    if(sku==null||image==null||!assigned.add(key))throw new IllegalArgumentException("来源 SKU 分组不完整或重复："+key);
    Map<String,Object> original=json.readValue(sku.getAttributesJson(),Map.class);
    var attributes=new LinkedHashMap<String,String>();
    String colorName=null;
    for(var entry:original.entrySet())if(CommerceSourceColorResolver.isColorDimension(entry.getKey())){colorName=entry.getKey();break;}
    if(colorName==null)colorName="Color";
    attributes.put(colorName,label);
    for(var entry:original.entrySet())if(!CommerceSourceColorResolver.isColorDimension(entry.getKey()))
     attributes.put(entry.getKey(),Objects.toString(entry.getValue(),"").strip());
    String group=json.writeValueAsString(attributes);
    var previous=grouped.get(group);
    if(previous!=null){
     if(!samePrice(previous.getPrice(),sku.getPrice())||!samePrice(previous.getOriginalPrice(),sku.getOriginalPrice())
       ||!Objects.equals(previous.getStock(),sku.getStock()))
      throw new IllegalArgumentException("同一颜色尺码的来源款式售价或库存不同，请先核对："+label+" / "+attributes);
     if(styleRank(sku)<styleRank(previous))grouped.put(group,sku);
    }else grouped.put(group,sku);
    attributesByGroup.put(group,attributes);
   }
   var groups=new ArrayList<>(grouped.keySet());
   groups.sort(Comparator.comparingInt((String key)->sizeRank(attributesByGroup.get(key)))
     .thenComparing(key->key));
   for(var group:groups){
    var sku=grouped.get(group);var attrs=attributesByGroup.get(group);
    String size=attrs.entrySet().stream().filter(e->isSize(e.getKey())).map(Map.Entry::getValue).findFirst().orElse("");
    result.add(new PlannedSku(sku,label+(size.isBlank()?"":" / "+size),group,skuImages.get(sku.getSkuKey()),result.size()));
   }
  }
  if(assigned.size()!=byKey.size())throw new IllegalArgumentException("存在未分配颜色的来源 SKU");
  return List.copyOf(result);
 }
 private static boolean samePrice(java.math.BigDecimal a,java.math.BigDecimal b){
  return a==null?b==null:b!=null&&a.compareTo(b)==0;
 }
 private int styleRank(ProductCaptureSku sku){
  Map<String,Object> attrs=json.readValue(sku.getAttributesJson(),Map.class);
  String color=attrs.entrySet().stream().filter(e->CommerceSourceColorResolver.isColorDimension(e.getKey()))
    .map(e->Objects.toString(e.getValue(),"")).findFirst().orElse("");
  var suffix=Pattern.compile("/\\s*([1-9][0-9]*)$").matcher(color);
  if(!suffix.find())return 0;
  try{return Integer.parseInt(suffix.group(1));}catch(NumberFormatException ignored){return Integer.MAX_VALUE;}
 }
 private static boolean isSize(String name){return Set.of("size","sizes","尺码","尺碼","尺寸").contains(name.strip().toLowerCase(Locale.ROOT));}
 private static int sizeRank(Map<String,String> attributes){
  String size=attributes.entrySet().stream().filter(e->isSize(e.getKey())).map(Map.Entry::getValue).findFirst().orElse("");
  var matcher=Pattern.compile("^(S|M|L|XL|XXL|XXXL|[2-9]XL)(?:\\b|\\()",
    Pattern.CASE_INSENSITIVE).matcher(size);
  if(!matcher.find())return 100;
  return switch(matcher.group(1).toUpperCase(Locale.ROOT)){
   case "S"->0;case "M"->1;case "L"->2;case "XL"->3;case "XXL","2XL"->4;case "XXXL","3XL"->5;
   default->6;
  };
 }
 private static List<String> detailKeys(List<Map<String,Object>> manifest){
  if(manifest.isEmpty())throw new IllegalArgumentException("发布素材缺少主图");
  var result=new ArrayList<String>();var seen=new HashSet<String>();
  for(int index=0;index<manifest.size();index++){
   Object value=manifest.get(index).get("detailKeys");
   if(value==null)continue;
   if(!(value instanceof List<?> keys))throw new IllegalArgumentException("发布详情图片清单无效");
   if(index>0&&!keys.isEmpty())throw new IllegalArgumentException("每款商品的详情图片只能打包一次");
   for(Object key:keys){
    if(!(key instanceof String text)||text.isBlank()||!seen.add(text))throw new IllegalArgumentException("发布详情图片缺失或重复");
    result.add(text);
   }
  }
  return List.copyOf(result);
 }
 static String publicationDescription(String source){
  if(source==null)return null;
  // Keep the original wording and HTML tables; old product photos and video embeds
  // cannot be matched to this new draft's generated image records.
  String result=MEDIA_CONTENT.matcher(source).replaceAll("");
  result=MEDIA_TAG.matcher(result).replaceAll("");
  var tags=HTML_TAG.matcher(result);var cleaned=new StringBuilder();
  while(tags.find())tags.appendReplacement(cleaned,java.util.regex.Matcher.quoteReplacement(BACKGROUND_MEDIA.matcher(tags.group()).replaceAll("")));
  tags.appendTail(cleaned);result=cleaned.toString();
  return EMPTY_MEDIA_LINK.matcher(result).replaceAll("");
 }
 static Map<String,Object> publicationData(Map<String,Object> source){
  var result=new LinkedHashMap<String,Object>(source);
  for(String field:List.of("videos","video","videoUrl","video_url","videoUrls","video_urls","videoInfo","video_info","videoList","video_list","mainVideo","main_video","images","imageUrls","image_urls","skus","sizeChartImageId"))result.remove(field);
  for(String field:List.of("description","productDescription","descriptionHtml","description_html"))if(result.get(field) instanceof String value)result.put(field,publicationDescription(value));
  return result;
 }
 static List<ProductCaptureImage> sourceSizeCharts(ProductCaptureService.CaptureDetail source,Object selectedId){
  return sourceSizeCharts(source,selectedId,Set.of());
 }
 static List<ProductCaptureImage> sourceSizeCharts(ProductCaptureService.CaptureDetail source,Object selectedId,Set<String> detectedIds){
  var result=new ArrayList<ProductCaptureImage>();var seen=new HashSet<String>();
  boolean selectedPresent=source.images().stream().anyMatch(image->image.getId().equals(selectedId));
  for(var image:source.images()){
   boolean selected=image.getId().equals(selectedId);
   boolean detected=detectedIds.contains(image.getId());
   if(selectedPresent&&!selected&&!detected)continue;
   String label=Objects.toString(image.getAltText(),"").trim();
   boolean chart=detected||"SIZE_CHART".equalsIgnoreCase(image.getRoleName())||(label.length()<=60&&SIZE_CHART_LABEL.matcher(label).matches());
   if(!selected&&!chart)continue;
   if(!source.capture().getOrganizationId().equals(image.getOrganizationId())||!source.capture().getId().equals(image.getCaptureId()))throw new IllegalArgumentException("尺码表图片不属于来源商品");
   if(!"PROCESSED".equals(image.getStatus())||image.getProcessedUrl()==null||image.getProcessedUrl().isBlank())throw new IllegalArgumentException("来源商品尺码表尚未处理完成");
   if(seen.add(image.getProcessedUrl()))result.add(image);
  }
  return result;
 }
 private String addImage(ProductCapture capture,String key,String role,int order,String label){
  String url=PREFIX+key;
  var image=new ProductCaptureImage(capture.getOrganizationId(),capture.getId(),url+"?original",role,label,order,"image/png",json.writeValueAsString(Map.of("commerceStorageKey",key)));
  image.processed(url);images.insert(image);return image.getOriginalUrl();
 }
 private byte[] bytes(String org,String key){return Base64.getDecoder().decode(archive.read(org,key).dataBase64());}
 static byte[] pair(byte[] front,byte[] back){
  try{
   var output=new java.awt.image.BufferedImage(1600,1000,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=output.createGraphics();
   g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,1600,1000);g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
   int x=0;for(byte[] bytes:List.of(front,back)){var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));if(image==null)throw new IllegalArgumentException("发布素材不是有效图片");double scale=Math.min(800.0/image.getWidth(),1000.0/image.getHeight());int w=(int)(image.getWidth()*scale),h=(int)(image.getHeight()*scale);g.drawImage(image,x+(800-w)/2,(1000-h)/2,w,h,null);x+=800;}
   g.dispose();var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(output,"png",bytes);return bytes.toByteArray();
  }catch(java.io.IOException e){throw new IllegalArgumentException("正背面 SKU 图片打包失败",e);}
 }
 public boolean owns(String org,String capture){return db.queryForObject("SELECT COUNT(*) FROM commerce_batch_publications WHERE organization_id=? AND capture_id=?",Integer.class,org,capture)>0;}
}
