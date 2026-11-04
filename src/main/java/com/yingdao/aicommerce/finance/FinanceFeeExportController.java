package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@RestController
@RequestMapping("/api/v1/finance/fee-exports")
public class FinanceFeeExportController {
    private final FinanceService service;private final FinanceFeeExportJobs jobs;
    public FinanceFeeExportController(FinanceService service,FinanceFeeExportJobs jobs){this.service=service;this.jobs=jobs;}
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal p,HttpServletRequest request){
        service.authorize(p);
        if("POST".equals(request.getMethod())){
            String site=request.getHeader("Sec-Fetch-Site"),origin=request.getHeader("Origin");
            if(site!=null&&!Set.of("same-origin","none").contains(site))throw bad("请从本站发起导出");
            if(origin!=null){
                try{if(!request.getServerName().equalsIgnoreCase(java.net.URI.create(origin).getHost()))throw bad("导出来源不正确");}
                catch(IllegalArgumentException e){throw bad("导出来源不正确");}
            }
        }
    }
    private Scope owner(PlatformPrincipal p){service.authorize(p);return new Scope(p.organizationId(),p.userId());}
    @PostMapping public ApiResponse<?> create(@RequestBody Map<String,String> body,
            @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal PlatformPrincipal p){
        Filter f;
        try{f=new Filter(LocalDate.parse(body.getOrDefault("start","")),LocalDate.parse(body.getOrDefault("end","")),
                body.get("shopId"),body.get("platform"),body.get("currency"),"day",body.get("groupId"));}
        catch(java.time.format.DateTimeParseException e){throw bad("请选择有效导出日期");}
        // Exports always retain platform-native amounts, independently of the dashboard display.
        return ApiResponse.ok(jobs.submit(owner(p),f,key));
    }
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(jobs.list(owner(p)));}
    @GetMapping("/{id}") public ApiResponse<?> status(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(jobs.status(owner(p),id));}
    @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal p){
        var file=jobs.download(owner(p),id);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store, private")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(file.bytes());
    }
}
