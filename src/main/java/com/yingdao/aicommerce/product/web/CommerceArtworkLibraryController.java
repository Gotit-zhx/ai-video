package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.product.application.CommerceArtworkLibraryService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import org.springframework.util.DigestUtils;

@RestController
@RequestMapping("/api/v1/commerce-artwork-library")
public class CommerceArtworkLibraryController {
    private final CommerceArtworkLibraryService service;

    public CommerceArtworkLibraryController(CommerceArtworkLibraryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CommerceArtworkLibraryService.Page> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String query, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.list(page, query, user));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) {
        var reference = service.image(id, user);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .body(Base64.getDecoder().decode(reference.dataBase64()));
    }

    @PostMapping("/{id}")
    public ApiResponse<Boolean> save(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) {
        service.saveGenerated(id,user);
        return ApiResponse.ok(true);
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String id, @AuthenticationPrincipal PlatformPrincipal user) {
        byte[] bytes = service.thumbnail(id, user);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag("\"" + DigestUtils.md5DigestAsHex(bytes) + "\"")
                .contentLength(bytes.length).body(bytes);
    }
}
