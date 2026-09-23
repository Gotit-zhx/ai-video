package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge/files")
public class KnowledgeFileController {
    private final KnowledgeFileService service;
    public KnowledgeFileController(KnowledgeFileService service){this.service=service;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@RequestParam("file") MultipartFile file,@RequestParam String requestId,
            @RequestParam(required=false) String articleId,@RequestParam(defaultValue="") String category,
            @AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.created(service.upload(file,requestId,articleId,category,user));}
    @GetMapping
    public Object list(@RequestParam(required=false) String articleId,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="") String q,
            @AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.list(articleId,category,page,q,user));}
    @PostMapping("/{id}/bind")
    public Object bind(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal user){
        return ApiResponse.ok(service.bind(id,KnowledgeService.str(body,"articleId"),KnowledgeService.version(body.get("version")),user));
    }
    @DeleteMapping("/{id}")
    public Object remove(@PathVariable String id,@RequestParam int version,@AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.remove(id,version,user));}
    @GetMapping("/audit")
    public Object audit(@RequestParam(required=false) String articleId,@RequestParam(defaultValue="") String category,
            @AuthenticationPrincipal PlatformPrincipal user){return ApiResponse.ok(service.audit(articleId,category,user));}
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){
        var value=service.download(id,user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(value.object().size()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(value.fileName(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").body(value.object().resource());
    }
    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){
        var value=service.preview(id,user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).contentType(MediaType.parseMediaType(value.contentType()))
                .contentLength(value.object().size()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(value.fileName(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header("Cross-Origin-Resource-Policy","same-origin")
                .header("Content-Security-Policy","sandbox; default-src 'none'").body(value.object().resource());
    }
}
