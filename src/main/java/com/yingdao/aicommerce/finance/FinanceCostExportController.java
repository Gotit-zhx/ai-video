package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@RestController
@RequestMapping("/api/v1/finance/cost-exports")
public class FinanceCostExportController {
    private final FinanceService service;
    private final FinanceCostExportJobs jobs;
    public FinanceCostExportController(FinanceService service,FinanceCostExportJobs jobs){this.service=service;this.jobs=jobs;}
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal principal,HttpServletRequest request){
        service.authorize(principal);
        if("POST".equals(request.getMethod())){
            String site=request.getHeader("Sec-Fetch-Site"),origin=request.getHeader("Origin");
            if(site!=null&&!Set.of("same-origin","none").contains(site))throw bad("请从本站发起导出");
            if(origin!=null){
                try{
                    URI uri=URI.create(origin);int originPort=uri.getPort()<0?("https".equalsIgnoreCase(uri.getScheme())?443:80):uri.getPort();
                    if(uri.getHost()==null||uri.getUserInfo()!=null||!request.getServerName().equalsIgnoreCase(uri.getHost())
                            ||!request.getScheme().equalsIgnoreCase(uri.getScheme())||request.getServerPort()!=originPort)throw bad("导出来源不正确");
                }catch(IllegalArgumentException invalid){throw bad("导出来源不正确");}
            }
        }
    }
    private Scope owner(PlatformPrincipal principal){service.authorize(principal);return new Scope(principal.organizationId(),principal.userId());}
    @PostMapping public ApiResponse<?> create(@RequestBody Map<String,String> body,@RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal PlatformPrincipal principal){
        Scope owner=owner(principal);Filter filter;
        try{filter=new Filter(LocalDate.parse(text(body.get("start")).trim()),LocalDate.parse(text(body.get("end")).trim()),
                text(body.get("shopId")).trim(),text(body.get("platform")).trim().toUpperCase(Locale.ROOT),
                text(body.get("currency")).trim().toUpperCase(Locale.ROOT),"day",text(body.get("groupId")).trim());}
        catch(java.time.format.DateTimeParseException invalid){throw bad("请选择有效导出日期");}
        return ApiResponse.ok(jobs.submit(owner,filter,body.getOrDefault("kind","SETTLED_ORDER"),body.getOrDefault("recordId",""),
                body.getOrDefault("costStatus","ALL"),body.getOrDefault("numbers",""),key));
    }
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal PlatformPrincipal principal){return ApiResponse.ok(jobs.list(owner(principal)));}
    @GetMapping("/{id}") public ApiResponse<?> status(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal principal){return ApiResponse.ok(jobs.status(owner(principal),id));}
    @GetMapping("/{id}/download") public ResponseEntity<InputStreamResource> download(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal principal){
        var file=jobs.download(owner(principal),id);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.length()).body(new InputStreamResource(file.stream()));
    }
}
