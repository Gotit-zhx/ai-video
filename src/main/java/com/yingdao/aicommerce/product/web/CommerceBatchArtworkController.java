package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceBatchArtworkService;
import com.yingdao.aicommerce.product.application.CommerceBatchArtworkRepairService;
import com.yingdao.aicommerce.product.application.CommerceBatchArtworkRetryService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/commerce-batches/{batch}/execution/artifacts")
public class CommerceBatchArtworkController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.yingdao.aicommerce.product.application.CommerceBackPreviewService backPreviews;
    @GetMapping("/{id}/back-image") public ResponseEntity<byte[]> back(@PathVariable String batch,@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){
        var ref=backPreviews.render(batch,id,user);return ResponseEntity.ok().contentType(MediaType.parseMediaType(ref.mimeType())).cacheControl(CacheControl.noStore()).body(Base64.getDecoder().decode(ref.dataBase64()));
    }
    private final CommerceBatchArtworkService service;
    private final CommerceBatchArtworkRepairService repairs;
    private final CommerceBatchArtworkRetryService retries;
    public CommerceBatchArtworkController(CommerceBatchArtworkService service,CommerceBatchArtworkRepairService repairs,CommerceBatchArtworkRetryService retries){this.service=service;this.repairs=repairs;this.retries=retries;}
    @PostMapping("/{id}/retry") public ApiResponse<CommerceBatchArtworkRetryService.Retried> retry(@PathVariable String batch,@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(retries.retry(batch,id,user));}
    @PostMapping("/repairs") public ApiResponse<Integer> repair(@PathVariable String batch,@RequestBody CommerceBatchArtworkRepairService.Request request,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(repairs.repair(batch,request,user));}
    @GetMapping("/sources/{capture}/image") public ResponseEntity<byte[]> source(@PathVariable String batch,@PathVariable String capture,@AuthenticationPrincipal PlatformPrincipal user){var ref=service.sourcePreview(batch,capture,user);return ResponseEntity.ok().contentType(MediaType.parseMediaType(ref.mimeType())).cacheControl(CacheControl.noStore()).body(Base64.getDecoder().decode(ref.dataBase64()));}
    @GetMapping public ApiResponse<List<CommerceBatchArtworkService.Artifact>> list(@PathVariable String batch,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.list(batch,user));}
    @GetMapping("/{id}/image") public ResponseEntity<byte[]> image(@PathVariable String batch,@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){
        var ref=service.image(batch,id,user);return ResponseEntity.ok().contentType(MediaType.parseMediaType(ref.mimeType())).cacheControl(CacheControl.noStore()).body(Base64.getDecoder().decode(ref.dataBase64()));
    }
}
