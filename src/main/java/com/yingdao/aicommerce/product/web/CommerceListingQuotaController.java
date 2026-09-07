package com.yingdao.aicommerce.product.web;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.domain.CommerceListingReservation;
import com.yingdao.aicommerce.product.application.CommerceListingQuotaService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/commerce-batches/{id}/quota-reservations")
public class CommerceListingQuotaController {
    private final CommerceListingQuotaService service;
    public CommerceListingQuotaController(CommerceListingQuotaService service){this.service=service;}
    @PostMapping("/cancel-batch") public ApiResponse<CommerceListingQuotaService.Cancellation> cancel(@PathVariable String id,
            @RequestBody CommerceListingQuotaService.CancelRequest r,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.cancel(id,r,u));}
    @GetMapping public ApiResponse<List<CommerceListingReservation>> list(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.list(id,u));}
    @PostMapping public ApiResponse<List<CommerceListingReservation>> reserve(@PathVariable String id,
            @RequestBody CommerceListingQuotaService.Request r,@AuthenticationPrincipal PlatformPrincipal u){return ApiResponse.ok(service.reserve(id,r,u));}
}
