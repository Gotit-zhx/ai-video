package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.application.CommerceImageArchive;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
import java.util.Base64;
@RestController
@RequestMapping("/api/v1/commerce-publish-images")
public class CommercePublishImageController {
 private final CommerceImageArchive archive;public CommercePublishImageController(CommerceImageArchive archive){this.archive=archive;}
 @GetMapping("/{tenant}/{hash}") public ResponseEntity<byte[]> image(@PathVariable String tenant,@PathVariable String hash,@AuthenticationPrincipal PlatformPrincipal user){
  if(user==null||!user.permissionCodes().contains("product:view"))return ResponseEntity.status(403).build();
  var ref=archive.read(user.organizationId(),tenant+"/"+hash);
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(ref.mimeType())).cacheControl(CacheControl.noStore()).body(Base64.getDecoder().decode(ref.dataBase64()));
 }
}
