package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommercePrintTemplateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/v1/commerce-print-templates")
public class CommercePrintTemplateController {
 private final CommercePrintTemplateService service;private final com.yingdao.aicommerce.product.application.CommerceSampleService samples;
 public CommercePrintTemplateController(CommercePrintTemplateService service,com.yingdao.aicommerce.product.application.CommerceSampleService samples){this.service=service;this.samples=samples;}
 public record ImportBlank(String sampleId,boolean reviewed){}
 @PostMapping("/base-images/from-sample") public ApiResponse<java.util.Map<String,String>> importBlank(@RequestBody ImportBlank request,@AuthenticationPrincipal PlatformPrincipal user){
  if(request==null||!request.reviewed())throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"请先核对纯色候选图的版型与印花残留");
  var image=samples.blankImage(request.sampleId(),user);var asset=service.upload(java.util.Base64.getDecoder().decode(image.dataBase64()),user);return ApiResponse.ok(java.util.Map.of("id",asset.sha256()));
 }
 @PostMapping(value="/base-images/prepare",consumes="multipart/form-data")
 public ApiResponse<java.util.Map<String,String>> prepare(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,@RequestParam(defaultValue="false") boolean alreadyPlain,@AuthenticationPrincipal PlatformPrincipal user)throws java.io.IOException{
  if(file.getSize()>8*1024*1024)throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"图片不能超过8MiB");
  var original=service.upload(file.getBytes(),user);
  if(alreadyPlain)return ApiResponse.ok(java.util.Map.of("id",original.sha256(),"status","READY"));
  var result=samples.blankUploaded(original,user);
  if(!"READY".equals(result.status()))return ApiResponse.ok(java.util.Map.of("status",result.status()));
  var image=samples.blankImage(result.id(),user);var blank=service.upload(java.util.Base64.getDecoder().decode(image.dataBase64()),user);
  return ApiResponse.ok(java.util.Map.of("id",blank.sha256(),"status","READY"));
 }
 @PostMapping(value="/base-images",consumes="multipart/form-data") public ApiResponse<java.util.Map<String,String>> upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,@AuthenticationPrincipal PlatformPrincipal user)throws java.io.IOException{
  if(file.getSize()>8*1024*1024)throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"底图不能超过8MiB");
  var asset=service.upload(file.getBytes(),user);return ApiResponse.ok(java.util.Map.of("id",asset.sha256()));
 }
 @GetMapping("/base-images/{id}") public org.springframework.http.ResponseEntity<byte[]> image(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){var asset=service.image(id,user);return org.springframework.http.ResponseEntity.ok().header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").contentType(org.springframework.http.MediaType.parseMediaType(asset.mimeType())).body(java.util.Base64.getDecoder().decode(asset.dataBase64()));}
 @PostMapping(value="/rendered-views",consumes="multipart/form-data")
 public ApiResponse<CommercePrintTemplateService.RenderAssets> renderedViews(
   @RequestParam("configuration") String configuration,
   @RequestParam java.util.Map<String,org.springframework.web.multipart.MultipartFile> files,
   @AuthenticationPrincipal PlatformPrincipal user)throws java.io.IOException{
  var config=new tools.jackson.databind.ObjectMapper().readValue(configuration,CommercePrintTemplateService.Configuration.class);
  var bytes=new java.util.HashMap<String,byte[]>();
  for(var entry:files.entrySet()){
   if(entry.getValue().getSize()>8*1024*1024)throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"渲染图片不能超过8MiB");
   bytes.put(entry.getKey(),entry.getValue().getBytes());
  }
  return ApiResponse.ok(service.archiveRenders(config,bytes,user));
 }
 @GetMapping public ApiResponse<List<CommercePrintTemplateService.View>> list(@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.list(user));}
 @GetMapping("/snapshots/{id}") public ApiResponse<CommercePrintTemplateService.View> snapshot(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.snapshot(id,user));}
 @PostMapping public ApiResponse<CommercePrintTemplateService.View> save(@RequestBody CommercePrintTemplateService.Request request,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.save(request,user));}
}
