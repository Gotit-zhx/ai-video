package com.yingdao.aicommerce.bd;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/bd")
public class BdController {
 private final BdService service;
 public BdController(BdService service){this.service=service;}
 @GetMapping("/members") public Object members(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.members(p));}
 @GetMapping("/records/{kind}") public Object list(@PathVariable String kind,@RequestParam(defaultValue="false") boolean deleted,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.list(kind,deleted,p));}
 @PostMapping("/records/{kind}") public Object create(@PathVariable String kind,@RequestBody Map<String,Object>b,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.created(service.save(kind,null,b,p));}
 @PutMapping("/records/{kind}/{id}") public Object update(@PathVariable String kind,@PathVariable String id,@RequestBody Map<String,Object>b,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.save(kind,id,b,p));}
 @PostMapping("/records/{kind}/import") public Object imports(@PathVariable String kind,@RequestBody List<Map<String,Object>>b,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.importRows(kind,b,p));}
 @PostMapping("/records/{id}/trash") public Object trash(@PathVariable String id,@RequestBody Map<String,Object>b,@AuthenticationPrincipal PlatformPrincipal p){service.deleted(id,Boolean.TRUE.equals(b.get("deleted")),p);return ApiResponse.ok("已更新");}
 @GetMapping("/records/{id}/followups") public Object history(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.followups(id,p));}
 @PostMapping("/records/{id}/followups") public Object historyAdd(@PathVariable String id,@RequestBody Map<String,Object>b,@AuthenticationPrincipal PlatformPrincipal p){service.followup(id,b,p);return ApiResponse.ok("已记录");}
 @PostMapping("/records/{id}/lock") public Object lock(@PathVariable String id,@RequestBody Map<String,Object>b,@AuthenticationPrincipal PlatformPrincipal p){service.lock(id,b,p);return ApiResponse.ok("已更新");}
}
