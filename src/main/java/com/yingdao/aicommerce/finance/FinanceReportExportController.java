package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
    private final FinanceCostExportJobs previousCosts;
    private final FinanceFeeExportJobs previousFees;

    FinanceReportExportController(FinanceController guard,FinanceCostExportController protection,
            FinanceReportReadiness readiness,FinanceReportReminders reminders,FinanceReportExportJobs jobs,
            FinanceCostExportJobs previousCosts,FinanceFeeExportJobs previousFees){
        this.guard=guard;this.protection=protection;this.readiness=readiness;this.reminders=reminders;
        this.jobs=jobs;this.previousCosts=previousCosts;this.previousFees=previousFees;
    }
    record Request(String type,Map<String,String> selection,String revision,boolean partial) {}
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal principal,HttpServletRequest request){
        protection.authorize(principal,request);
    }
    @PostMapping("/status") public ApiResponse<?> status(@AuthenticationPrincipal PlatformPrincipal principal,@RequestBody Request body){
        return ApiResponse.ok(readiness.assess(guard.s(principal),select(body)));
    }
    @PostMapping public ApiResponse<?> create(@AuthenticationPrincipal PlatformPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Request body){
        var selected=select(body);
        var owner=guard.personal(principal);var company=guard.s(principal);
        if(selected.type()==FinanceReportSelection.Type.ORDER_COST)
            return ApiResponse.ok(previousCosts.submit(owner,selected.filter(),selected.get("kind"),selected.get("recordId"),
                selected.get("costStatus"),selected.get("numbers"),key,jobs.costApproval(company,selected,body.revision(),body.partial())));
        return ApiResponse.ok(jobs.submit(owner,company,selected,key,body.revision(),body.partial()));
    }
    @PostMapping("/reminders") public ApiResponse<?> subscribe(@AuthenticationPrincipal PlatformPrincipal principal,@RequestBody Request body){
        reminders.subscribe(guard.personal(principal),guard.s(principal),select(body));
        return ApiResponse.ok("已订阅完成提醒");
    }
    @GetMapping("/reminders/{id}") public ResponseEntity<ApiResponse<?>> readySelection(
            @AuthenticationPrincipal PlatformPrincipal principal,@PathVariable String id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(reminders.readySelection(guard.personal(principal),id)));
    }
    @PostMapping("/reminders/{id}/dismiss") public ApiResponse<?> dismiss(@AuthenticationPrincipal PlatformPrincipal principal,@PathVariable String id){
        reminders.dismiss(guard.personal(principal),id);
        return ApiResponse.ok("提醒已关闭");
    }
    @GetMapping public ResponseEntity<ApiResponse<?>> list(@AuthenticationPrincipal PlatformPrincipal principal){
        Scope owner=guard.personal(principal);
        var value=Map.of("jobs",jobs.list(owner),"costJobs",previousCosts.list(owner),"feeJobs",previousFees.list(owner),
            "reminders",reminders.list(owner));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
    @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@AuthenticationPrincipal PlatformPrincipal principal,@PathVariable String id){
        var file=jobs.download(guard.personal(principal),id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename(),StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType(file.contentType())).body(file.bytes());
    }
    private FinanceReportSelection select(Request body){return FinanceReportSelection.parse(guard,body.type(),body.selection());}
}
