package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@RestController
@RequestMapping("/api/v1/finance/workspace")
public class FinanceWorkspaceQueryController {
    final FinanceController guard;final FinanceWorkspaceQueryService queries;final FinanceTableAccess tableAccess;
    public FinanceWorkspaceQueryController(FinanceController guard,FinanceWorkspaceQueryService queries,FinanceTableAccess tableAccess){
        this.guard=guard;this.queries=queries;this.tableAccess=tableAccess;
    }
    @GetMapping("/query") public ApiResponse<?> query(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal principal){
        var scope=guard.s(principal);
        var selection=new FinanceWorkspaceQueryService.Selection(q.get("view"),q.get("kind"),"ALL".equals(q.get("orderScope")),
            "CNY".equals(q.get("displayCurrency")),q.get("fxSnapshot"),page(q.getOrDefault("page","1")),page(q.getOrDefault("spendPage","1")),q.get("numbers"),q.get("search"),q.get("assignment"),
            "true".equals(q.get("interactivePage")));
        String accessRevision=FinanceTableAccess.cachedView(selection.view())?tableAccess.revision(principal):null;
        var result=queries.query(scope,guard.personal(principal),guard.filter(q),selection);
        return ApiResponse.ok(withAccessRevision(result,accessRevision));
    }
    @GetMapping("/status") public ApiResponse<?> status(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal principal){
        var scope=guard.s(principal);
        String view=q.get("view");
        String accessRevision=FinanceTableAccess.cachedView(view)?tableAccess.revision(principal):null;
        var supported=Arrays.stream(text(q.get("costScopes")).split(",",3)).filter(FinancePublishedCosts.KINDS::contains).distinct().limit(2).toList();
        return ApiResponse.ok(withAccessRevision(queries.status(scope,guard.filter(q),view,supported),accessRevision));
    }
    @GetMapping("/cache-access") public ApiResponse<?> cacheAccess(@AuthenticationPrincipal PlatformPrincipal principal){
        return ApiResponse.ok(Map.of("accessRevision",tableAccess.revision(principal)));
    }
    private static Map<String,Object> withAccessRevision(Map<String,Object> result,String accessRevision){
        if(accessRevision==null)return result;
        var stamped=new LinkedHashMap<>(result);
        stamped.put("accessRevision",accessRevision);
        return stamped;
    }
    private static int page(String value){try{int page=Integer.parseInt(value);if(page<1)throw new NumberFormatException();return page;}catch(NumberFormatException invalid){throw bad("页码不正确");}}
}
