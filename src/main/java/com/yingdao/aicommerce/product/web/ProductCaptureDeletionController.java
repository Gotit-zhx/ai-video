package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.application.ProductCaptureDeletionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/product-captures")
public class ProductCaptureDeletionController {
 private final ProductCaptureDeletionService service;
 public ProductCaptureDeletionController(ProductCaptureDeletionService service){this.service=service;}
 @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void delete(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){service.delete(id,user.organizationId());}
}
