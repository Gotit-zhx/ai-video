package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    public KnowledgeController(KnowledgeService service) { this.service = service; }
    @GetMapping("/articles")
    public Object list(@RequestParam(defaultValue="") String category, @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="") String language,
            @RequestParam(defaultValue="1") int page, @AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(service.list(category,q,status,language,page,user));
    }
    @GetMapping("/articles/{id}")
    public Object get(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.get(id,user)); }
    @PostMapping("/articles")
    public Object create(@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.created(service.save(null,body,user)); }
    @PutMapping("/articles/{id}")
    public Object update(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.save(id,body,user)); }
    @DeleteMapping("/articles/{id}")
    public Object delete(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.delete(id,body,user)); }
    @PostMapping("/articles/{id}/status")
    public Object changeStatus(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.changeStatus(id,body,user)); }
    @GetMapping("/articles/{id}/versions")
    public Object versions(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.versions(id,user)); }
    @GetMapping("/access/users")
    public Object writerAccess(@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.writerAccess(user)); }
    @PutMapping("/access/users/{id}")
    public Object setWriterAccess(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.setWriterAccess(id,body,user)); }
    @GetMapping("/retrieval")
    public Object retrieval(@RequestParam String q,@RequestParam(defaultValue="") String language,@AuthenticationPrincipal PlatformPrincipal user) { return ApiResponse.ok(service.retrieve(q,language,user)); }
}
