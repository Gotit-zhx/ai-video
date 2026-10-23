package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@RestController
@RequestMapping("/api/v1/finance/workspace")
public class FinanceCostController {
    final FinanceCostWorkspace workspace;final FinanceService service;
    public FinanceCostController(FinanceCostWorkspace workspace,FinanceService service){this.workspace=workspace;this.service=service;}
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal p,HttpServletRequest request){
        service.authorize(p);
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
            service.requireManagement(p);
            String site=request.getHeader("Sec-Fetch-Site"),origin=request.getHeader("Origin");
            if(site!=null&&!Set.of("same-origin","none").contains(site))throw bad("请从本站发起财务操作");
            if(origin!=null){try{if(!request.getServerName().equalsIgnoreCase(java.net.URI.create(origin).getHost()))throw bad("操作来源不正确");}catch(IllegalArgumentException e){throw bad("操作来源不正确");}}
        }
    }
    @GetMapping("/costs") public ApiResponse<?> costs(@RequestParam(required=false) String shopId,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.costs(service.authorize(p),shopId));}
    @GetMapping("/costs/orders") public ApiResponse<?> orderCosts(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=service.authorize(p);String shopId=q.getOrDefault("shopId","");if(!shopId.isBlank())service.ownedShop(scope,shopId);
        var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var filter=new Filter(q.containsKey("start")?FinanceCostWorkspace.day(q.get("start")):today.minusDays(29),
                q.containsKey("end")?FinanceCostWorkspace.day(q.get("end")):today,shopId,q.getOrDefault("platform",""),
                q.getOrDefault("currency",""),"day",q.getOrDefault("groupId",""));
        int page=(int)Math.min(Integer.MAX_VALUE,Math.max(1,number(q.getOrDefault("page","1"))));
        return ApiResponse.ok(new FinanceCostReport(workspace.repo).orders(scope,filter,q.getOrDefault("kind","ORDER"),q.getOrDefault("recordId",""),page));
    }
    @PostMapping("/costs/import") public ApiResponse<?> upload(@RequestParam String groupId,@RequestParam MultipartFile file,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{
        return ApiResponse.ok(workspace.importCosts(service.authorize(p),p.userId(),groupId,file.getBytes(),text(file.getOriginalFilename())));
    }
    @PutMapping("/costs/{shopId}/settings") public ApiResponse<?> settings(@PathVariable String shopId,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){workspace.settings(service.authorize(p),p.userId(),shopId,body);return ApiResponse.ok("成本口径已保存");}
    @PutMapping("/costs/{shopId}/mapping") public ApiResponse<?> mapping(@PathVariable String shopId,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){workspace.mapping(service.authorize(p),p.userId(),shopId,body);return ApiResponse.ok("SKU 关联已保存");}
    @PostMapping("/costs/{shopId}/auto-match") public ApiResponse<?> autoMatch(@PathVariable String shopId,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.autoMatch(service.authorize(p),p.userId(),shopId,body));}
    @GetMapping("/expenses") public ApiResponse<?> expenses(@RequestParam Map<String,String> query,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.expenses(service.authorize(p),query));}
    @PostMapping("/expenses") public ApiResponse<?> addExpense(@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.expense(service.authorize(p),p.userId(),null,body));}
    @PutMapping("/expenses/{id}") public ApiResponse<?> editExpense(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.expense(service.authorize(p),p.userId(),id,body));}
    @PostMapping("/expense-rules") public ApiResponse<?> rule(@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){workspace.expenseRule(service.authorize(p),p.userId(),body);return ApiResponse.ok("分摊规则已保存，待执行");}
}
