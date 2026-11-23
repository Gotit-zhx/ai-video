package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@RestController
@RequestMapping("/api/v1/finance/report-exports")
class FinanceReportExportController {
    private final FinanceController guard;
    private final FinanceCostExportController protection;
    private final FinanceReportReadiness readiness;
    private final FinanceReportReminders reminders;
    private final FinanceReportExportJobs jobs;
    private final FinanceLegacyReportExportJobs legacy;
    private final FinanceCostExportJobs previousCosts;
    private final FinanceFeeExportJobs previousFees;
    private final FinancePageActivity activity;

    FinanceReportExportController(FinanceController guard,FinanceCostExportController protection,
            FinanceReportReadiness readiness,FinanceReportReminders reminders,FinanceReportExportJobs jobs,FinanceLegacyReportExportJobs legacy,
            FinanceCostExportJobs previousCosts,FinanceFeeExportJobs previousFees,FinancePageActivity activity){
        this.guard=guard;this.protection=protection;this.readiness=readiness;this.reminders=reminders;
        this.jobs=jobs;this.legacy=legacy;this.previousCosts=previousCosts;this.previousFees=previousFees;this.activity=activity;
    }
    record Request(String type,Map<String,String> selection,String revision,Boolean partial) {}
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal principal,HttpServletRequest request){
        protection.authorize(principal,request);
    }
    @PostMapping("/status") public ApiResponse<?> status(@AuthenticationPrincipal PlatformPrincipal principal,@RequestBody Request body){
        return ApiResponse.ok(jobs.statusView(guard.personal(principal),readiness.assessForDisplay(guard.s(principal),select(body))));
    }
    @PostMapping("/activity") public ResponseEntity<ApiResponse<?>> activity(@AuthenticationPrincipal PlatformPrincipal principal,
            @RequestBody FinancePageActivity.Request body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(activity.read(guard.s(principal),body)));
    }
    @PostMapping public ApiResponse<?> create(@AuthenticationPrincipal PlatformPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Request body){
        var selected=select(body);
        boolean partial=Boolean.TRUE.equals(body.partial());
        var owner=guard.personal(principal);var company=guard.s(principal);
        if(jobs.enabled(owner))return ApiResponse.ok(jobs.submit(owner,company,selected,key,partial));
        if(selected.type()==FinanceReportSelection.Type.ORDER_COST)
            return ApiResponse.ok(previousCosts.submit(owner,selected.filter(),selected.get("kind"),selected.get("recordId"),
                selected.get("costStatus"),selected.get("numbers"),key,legacy.costApproval(company,selected,body.revision(),partial)));
        return ApiResponse.ok(legacy.submit(owner,company,selected,key,body.revision(),partial));
    }
    @PostMapping("/reminders") public ApiResponse<?> subscribe(@AuthenticationPrincipal PlatformPrincipal principal,@RequestBody Request body){
        reminders.subscribe(guard.personal(principal),guard.s(principal),select(body));
        return ApiResponse.ok("已订阅完成提醒");
    }
    @GetMapping("/reminders/{id}") public ResponseEntity<ApiResponse<?>> savedSelection(
            @AuthenticationPrincipal PlatformPrincipal principal,@PathVariable String id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(reminders.savedSelection(guard.personal(principal),id)));
    }
    @PostMapping("/reminders/{id}/dismiss") public ApiResponse<?> dismiss(@AuthenticationPrincipal PlatformPrincipal principal,@PathVariable String id){
        reminders.dismiss(guard.personal(principal),id);
        return ApiResponse.ok("提醒已关闭");
    }
    @GetMapping public ResponseEntity<ApiResponse<?>> list(@AuthenticationPrincipal PlatformPrincipal principal){
        Scope owner=guard.personal(principal);
        var reportJobs=new java.util.ArrayList<>(jobs.list(owner));reportJobs.addAll(legacy.list(owner));
        var value=Map.of("jobs",reportJobs,"costJobs",previousCosts.list(owner),"feeJobs",previousFees.list(owner),
            "reminders",reminders.list(owner),"durableExports",jobs.enabled(owner));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
    @GetMapping("/{id}/download") public ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal PlatformPrincipal principal,@PathVariable String id){
        Scope owner=guard.personal(principal);
        if(!jobs.contains(owner,id)){
            var file=legacy.download(owner,id);
            return attachment(file.filename(),file.contentType(),file.bytes().length,new ByteArrayInputStream(file.bytes()));
        }
        var file=jobs.download(owner,id);
        return attachment(file.filename(),file.contentType(),file.length(),file.stream());
    }
    private ResponseEntity<InputStreamResource> attachment(String filename,String contentType,long length,java.io.InputStream stream){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType(contentType)).contentLength(length).body(new InputStreamResource(stream));
    }
    private FinanceReportSelection select(Request body){return FinanceReportSelection.parse(guard,body.type(),body.selection());}
}
