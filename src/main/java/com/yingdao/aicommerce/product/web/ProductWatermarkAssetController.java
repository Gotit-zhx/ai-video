package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.application.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Base64;
@RestController
@RequestMapping("/api/v1/product-watermark/assets")
public class ProductWatermarkAssetController {
 private final ProductWatermarkAssets assets;private final ProductCaptureService captures;
 public ProductWatermarkAssetController(ProductWatermarkAssets assets,ProductCaptureService captures){this.assets=assets;this.captures=captures;}
 @GetMapping("/{id}") public ResponseEntity<byte[]> image(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){
  var row=assets.record(user.organizationId(),id);String capture=(String)row.get("capture_id"),image=(String)row.get("image_id");
  var detail=captures.get(capture,user);
  if(detail.images().stream().noneMatch(i->i.getId().equals(image)))throw new IllegalArgumentException("来源图片已变化");
  var result=assets.read(user.organizationId(),capture,image,id);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(result.mimeType())).body(Base64.getDecoder().decode(result.dataBase64()));
 }
}
