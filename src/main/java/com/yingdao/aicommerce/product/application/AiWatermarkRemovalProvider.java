package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.application.provider.WatermarkRemovalProvider;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Durable, fail-closed watermark processing. Never deletes or replaces source image files. */
@Component
public class AiWatermarkRemovalProvider implements WatermarkRemovalProvider {
 private static final String ABSENCE_METHOD="ORIGINAL_AND_ENHANCED_PRE_DETECTION_V1";
 private final ProductWatermarkLedger ledger;private final CommerceImageArchive archive;
 private final MarketplaceImageDownloader downloader;private final CommerceDesignImageGateway generator;
 private final ModelConfigService models;private final JdbcTemplate db;private final ObjectMapper json;
 private final RestClient vision;private final String visionModel;
 private final LocalWatermarkDetector localDetector;
 public AiWatermarkRemovalProvider(ProductWatermarkLedger ledger,CommerceImageArchive archive,MarketplaceImageDownloader downloader,
   CommerceDesignImageGateway generator,ModelConfigService models,JdbcTemplate db,ObjectMapper json,LocalWatermarkDetector localDetector,
   @Value("${app.pi-runtime-url}")String url,@Value("${app.pi-runtime-token}")String token,
   @Value("${app.product-capture.watermark.model-id:grok-4.6}")String visionModel){
  this.ledger=ledger;this.archive=archive;this.downloader=downloader;this.generator=generator;this.models=models;this.db=db;this.json=json;this.visionModel=visionModel;this.localDetector=localDetector;
  var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(10000);factory.setReadTimeout(1020000);
  vision=RestClient.builder().baseUrl(url).defaultHeader("X-Internal-Token",token).requestFactory(factory).build();
 }
 @Override public String code(){return "AI_WATERMARK_V1";}
 @Override public boolean dryRun(){return false;}
 @Override public ProcessedImage process(ProductCapture capture,ProductCaptureImage image)throws Exception{
  String org=capture.getOrganizationId();
  var sourceRows=db.queryForList("SELECT storage_key FROM commerce_capture_source_archives WHERE organization_id=? AND capture_id=? AND image_id=? ORDER BY capture_version DESC LIMIT 1",org,capture.getId(),image.getId());
  // Image IDs bind immutable source URLs. Received results and detected-but-unsent retries use
  // their exact archived reference, so remote changes cannot create a new hash or repeat detection.
  if(sourceRows.isEmpty())sourceRows=db.queryForList("SELECT source_key AS storage_key FROM product_watermark_checks WHERE organization_id=? AND capture_id=? AND image_id=? AND ((status IN ('RESULT_RECEIVED','EDITED','READY') AND generation_json IS NOT NULL) OR (status='DETECTED' AND generation_json IS NULL)) ORDER BY updated_at DESC LIMIT 1",org,capture.getId(),image.getId());
  var source=sourceRows.isEmpty()?null:archive.read(org,(String)sourceRows.getFirst().get("storage_key"));
  if(source==null){
   try{var downloaded=downloader.download(image.getOriginalUrl());var stored=archive.store(org,downloaded.bytes());source=archive.read(org,stored.storageKey());}
   catch(RuntimeException e){throw new IllegalStateException("读取采集原图失败："+Objects.toString(e.getMessage(),"未知错误"),e);}
  }
  byte[] original=Base64.getDecoder().decode(source.dataBase64());CommerceMockupComposer.decode(original);
  var stored=archive.store(org,original);
  var imageModel=models.imageConnection(org).orElseThrow(()->new IllegalStateException("请配置图片编辑模型"));
  String id=CommerceImageArchive.sha((org+":"+capture.getId()+":"+image.getId()+":"+stored.sha256()+":"+imageModel.modelId()+":WATERMARK_V12_DETECT_FALLBACK_EDIT").getBytes(StandardCharsets.UTF_8));
  var row=ledger.prepare(id,org,capture.getId(),image.getId(),stored.sha256(),stored.storageKey());
  if("READY".equals(row.get("status")))return result(id,row);
  if("PREPARED".equals(row.get("status"))&&ledger.reuseReceivedGeneration(id,org,capture.getId(),image.getId(),stored.sha256()))row=ledger.get(id,org);
  if("PREPARED".equals(row.get("status"))){
   claim(id,org,"PREPARED","DETECTING");
   Map<String,Object> detection;
   try {
    var driver=models.driverConnection(org).orElseThrow(()->new IllegalStateException("检测模型未配置"));
    String detectorModel=visionModel.isBlank()?driver.modelId():visionModel;
    detection=analyze(driver,detectorModel,source,List.of(),"ORIGINAL");
    if(!"SIZE_CHART".equals(detection.get("imageType")) && "ABSENT".equals(detection.get("decision"))){
     Map<String,Object> confirmation,background;
     try{
      background=WatermarkDetectionView.assessBackground(original);
     }catch(RuntimeException e){background=Map.of("eligibleToSkip",false,"reason","背景像素评估失败，直接生图","assessmentError",Objects.toString(e.getMessage(),"未知错误"));}
     if(!WatermarkDetectionView.permitsSkipping(background)){
      var restricted=new LinkedHashMap<String,Object>();restricted.put("method",ABSENCE_METHOD);restricted.put("originalDetection",detection);restricted.put("backgroundAssessment",background);
      restricted.put("decision","UNCERTAIN");restricted.put("confidence",0);restricted.put("regions",List.of());restricted.put("fallbackToGeneration",true);
      restricted.put("reason","原图不满足跳过生图的背景条件，直接生图；不作有水印断言");detection=restricted;
     }else{
      try{
       var enhanced=WatermarkDetectionView.create(original);
       confirmation=analyze(driver,detectorModel,source,List.of(Map.of("mimeType",enhanced.mimeType(),"data",Base64.getEncoder().encodeToString(enhanced.bytes()))),"ENHANCED_CONFIRMATION");
      }catch(RuntimeException e){confirmation=detectionFailure(e,"增强图前置确认失败，按设置直接生图");}
      detection=confirmedDetection(detection,confirmation,background);
     }
    }
   } catch(RuntimeException e) {
    detection=detectionFailure(e,"前置识别失败，按设置直接生图");
   }
   ledger.detection(id,org,json.writeValueAsString(detection));row=ledger.get(id,org);
  }
  if("DETECTED".equals(row.get("status"))){
   var detection=json.readValue((String)row.get("detection_json"),Map.class);
   if("SIZE_CHART".equals(detection.get("imageType"))){
    // Preserve the source even when OCR is uncertain; never redraw numerical product information.
    ledger.ready(id,org,"DETECTED",stored.storageKey(),"{\"method\":\"PRESERVED_SOURCE_SIZE_CHART\",\"requiresMeasurementValidation\":true,\"postValidationSkipped\":true}");return result(id,ledger.get(id,org));
   }
   if(confirmedAbsent(detection)){
    ledger.ready(id,org,"DETECTED",stored.storageKey(),"{\"method\":\"UNCHANGED_ORIGINAL_DUAL_PRE_DETECTION\",\"postValidationSkipped\":true}");return result(id,ledger.get(id,org));
   }
   claim(id,org,"DETECTED","EDITING");
   String prompt="仅移除参考照片后期叠加的水印。直接清理叠加水印；如无水印则保持原图不变。禁止改变商品的结构、形态、轮廓、比例、颜色、纹理、原有印花、刺绣、品牌、尺码标签和背景实物。不要二创，不要去掉衣服原图案。保持参考图的画布、留白、位置及尺寸，不缩放或移动商品。只输出一张同构图图片，不添加文字或对比排版。";
   try {
    var response=generator.generateArtwork(prompt,id,imageModel,List.of(source));
    ledger.generated(id,org,json.writeValueAsString(response));row=ledger.get(id,org);
   } catch(ImageGenerationNotSentException e) {
    // A lease timeout happens before HTTP submission. Release only this confirmed-unsent
    // intent; ambiguous failures must keep the existing no-automatic-resubmission hold.
    claim(id,org,"EDITING","DETECTED");
    throw new WatermarkRateLimitException(e.getMessage(),e);
   } catch(Exception e) {
    ledger.hold(id,org,"EDITING","生图请求失败或结果未确认，未自动重复提交："+Objects.toString(e.getMessage(),"未知错误"));throw e;
   }
  }
  if("RESULT_RECEIVED".equals(row.get("status"))){
   var response=json.readValue((String)row.get("generation_json"),CommerceDesignImageGateway.Response.class);
   byte[] bytes;
   if(response.artifact()!=null&&response.artifact().dataBase64()!=null)bytes=Base64.getDecoder().decode(response.artifact().dataBase64());
   else if(response.result()!=null){
    try{bytes=downloader.download(response.result().url()).bytes();}
    catch(RuntimeException e){
     String message="生图已成功，下载结果图片失败："+Objects.toString(e.getMessage(),"未知错误")+"；结果已保存，重试只继续下载";
     if(GeneratedImageDownloadPendingException.retryable(e))throw new GeneratedImageDownloadPendingException(message,e);
     throw new IllegalStateException(message,e);
    }
   }
   else throw new IllegalStateException("图片编辑没有返回可归档结果");
   CommerceMockupComposer.decode(bytes);
   ledger.edited(id,org,archive.store(org,bytes).storageKey());row=ledger.get(id,org);
  }
  if("EDITED".equals(row.get("status"))){
   ledger.ready(id,org,"EDITED",(String)row.get("candidate_key"),"{\"method\":\"GENERATED_WITHOUT_POST_VALIDATION\",\"postValidationSkipped\":true}");row=ledger.get(id,org);
  }
  if(!"READY".equals(row.get("status")))throw new IllegalStateException("水印任务需要核查，未重复提交模型："+row.get("status"));
  return result(id,row);
 }
 private void claim(String id,String org,String from,String to){if(!ledger.claim(id,org,from,to))throw new IllegalStateException("水印任务已由其他请求提交");}
 private ProcessedImage result(String id,Map<String,Object> row){return new ProcessedImage(ProductWatermarkAssets.PREFIX+id,Map.of("checkId",id,"status","READY","sourceSha256",row.get("source_sha256"),"detection",json.readValue((String)row.get("detection_json"),Map.class)));}
 private Map<String,Object> analyze(ModelConfigService.RuntimeModelConnection model,String modelId,CommerceDesignImageGateway.Reference source,List<Map<String,Object>> detectionViews,String pass){
  var body=new LinkedHashMap<String,Object>();var driverRequest=new LinkedHashMap<String,Object>(model.driverRequestModel());driverRequest.put("modelId",modelId);body.put("model",driverRequest);body.put("context",Map.of("analysisType","WATERMARK_DETECTION_V1","detectionPass",pass));
  body.put("image",analysisCopy(source));if(!detectionViews.isEmpty())body.put("detectionViews",detectionViews);
  var response=vision.post().uri("/v1/product/analyze").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
  if(response==null||!(response.get("result") instanceof Map<?,?>))throw new IllegalStateException("视觉模型未返回有效判定");return (Map<String,Object>)response.get("result");
 }
 private Map<String,Object> analysisCopy(CommerceDesignImageGateway.Reference image){
  var copy=com.yingdao.aicommerce.video.application.ProductFunctionAnalysisService.compactForValidation(Base64.getDecoder().decode(image.dataBase64()),image.mimeType());
  return Map.of("mimeType",copy.mimeType(),"data",Base64.getEncoder().encodeToString(copy.bytes()));
 }
 private double confidence(Map<String,Object> value){return value.get("confidence") instanceof Number n&&Double.isFinite(n.doubleValue())?n.doubleValue():0;}
 private boolean clearlyAbsent(Map<String,Object> value){
  return "ABSENT".equals(value.get("decision"))&&confidence(value)>=.99&&confidence(value)<=1
    &&value.get("regions") instanceof List<?> regions&&regions.isEmpty();
 }
 private boolean confirmedAbsent(Map<String,Object> value){
  return ABSENCE_METHOD.equals(value.get("method"))&&clearlyAbsent(value)
    &&value.get("backgroundAssessment") instanceof Map<?,?> background&&WatermarkDetectionView.permitsSkipping(background)
    &&value.get("originalDetection") instanceof Map<?,?> original&&clearlyAbsent((Map<String,Object>)original)
    &&value.get("confirmationDetection") instanceof Map<?,?> confirmation&&clearlyAbsent((Map<String,Object>)confirmation);
 }
 private Map<String,Object> confirmedDetection(Map<String,Object> original,Map<String,Object> confirmation,Map<String,Object> background){
  boolean absent=clearlyAbsent(original)&&clearlyAbsent(confirmation);
  var result=new LinkedHashMap<String,Object>();
  result.put("method",ABSENCE_METHOD);result.put("originalDetection",original);result.put("confirmationDetection",confirmation);
  result.put("backgroundAssessment",background);
  result.put("decision",absent?"ABSENT":"PRESENT".equals(confirmation.get("decision"))?"PRESENT":"UNCERTAIN");
  result.put("confidence",absent?Math.min(confidence(original),confidence(confirmation)):confidence(confirmation));
  result.put("regions",confirmation.get("regions") instanceof List<?> regions?regions:List.of());
  result.put("fallbackToGeneration",!absent);
  result.put("reason",absent?"原图与增强辅助图两次前置判定均确认无水印":"前置判定未满足双重高置信无水印条件，直接生图");
  return result;
 }
 private Map<String,Object> detectionFailure(RuntimeException error,String reason){
  return Map.of("decision","UNCERTAIN","confidence",0,"regions",List.of(),"fallbackToGeneration",true,"reason",reason,"detectionError",Objects.toString(error.getMessage(),"未知错误"));
 }
}
