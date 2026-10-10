package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@RestController
@RequestMapping("/api/v1/commerce-batches/{batch}/publish-options")
public class CommerceDirectPublishController {
 private final CommerceBatchPublicationOptions options;private final ObjectMapper json;
 public CommerceDirectPublishController(CommerceBatchPublicationOptions options,ObjectMapper json){this.options=options;this.json=json;}
 @GetMapping public ApiResponse<?> get(@PathVariable String batch,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(Map.of("publicationRequests",options.get(batch,user)));}
 @PostMapping public ApiResponse<?> save(@PathVariable String batch,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal user){
  if(body.get("publicationRequests") instanceof List<?> requests){
   options.save(batch,requests.stream().map(row->json.convertValue(row,CommerceBatchPublicationOptions.PublicationRequest.class)).toList(),user);
  }else options.saveOne(batch,(String)body.get("captureId"),(String)body.get("shopId"),body,user);
  return ApiResponse.ok(Map.of("saved",true));
 }
}
