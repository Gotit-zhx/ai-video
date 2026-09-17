package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.ProductCaptureService;
import com.yingdao.aicommerce.product.application.ProductWatermarkLedger;
import com.yingdao.aicommerce.product.application.ProductWatermarkQueue;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/product-captures")
public class ProductWatermarkProgressController {
 private final ProductCaptureService captures;
 private final ProductWatermarkLedger ledger;
 private final ProductWatermarkQueue queue;
 public ProductWatermarkProgressController(ProductCaptureService captures,ProductWatermarkLedger ledger,ProductWatermarkQueue queue){this.captures=captures;this.ledger=ledger;this.queue=queue;}
 @GetMapping("/{id}/image-progress")
 public ApiResponse<List<Map<String,Object>>> progress(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){
  captures.get(id,user);
  var live=new LinkedHashMap<String,Map<String,Object>>();
  for(var row:ledger.progress(user.organizationId(),id))live.put((String)row.get("imageId"),row);
  for(var row:queue.progress(user.organizationId(),id)){
   var detail=live.get(row.get("imageId"));
   var value=new LinkedHashMap<String,Object>(row);
   if("RUNNING".equals(row.get("status")) && detail!=null && "DETECTING".equals(detail.get("status")))value.put("status","DETECTING");
   live.put((String)row.get("imageId"),value);
  }
  return ApiResponse.ok(new ArrayList<>(live.values()));
 }
}
