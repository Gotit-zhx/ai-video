package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.application.CommerceCaptureSourceArchiveService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
@RequestMapping("/api/v1/product-captures/{capture}/images/{image}/source-file")
public class CommerceCaptureSourceArchiveController {
 private final CommerceCaptureSourceArchiveService service;
 public CommerceCaptureSourceArchiveController(CommerceCaptureSourceArchiveService service){this.service=service;}
 @PostMapping public ApiResponse<CommerceCaptureSourceArchiveService.View> upload(@PathVariable String capture,@PathVariable String image,@RequestParam long version,@RequestPart("file") MultipartFile file,@AuthenticationPrincipal PlatformPrincipal user)throws java.io.IOException{
  if(file.isEmpty()||file.getSize()>8*1024*1024)throw new BusinessException(HttpStatus.BAD_REQUEST,"原图文件须为有效图片且不超过8MiB");
  return ApiResponse.ok(service.upload(capture,image,version,file.getBytes(),user));
 }
}
