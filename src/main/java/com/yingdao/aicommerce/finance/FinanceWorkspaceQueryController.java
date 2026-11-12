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
    final FinanceController guard;final FinanceWorkspaceQueryService queries;
    public FinanceWorkspaceQueryController(FinanceController guard,FinanceWorkspaceQueryService queries){this.guard=guard;this.queries=queries;}
    @GetMapping("/query") public ApiResponse<?> query(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal principal){
        var scope=guard.s(principal);
        var selection=new FinanceWorkspaceQueryService.Selection(q.get("view"),q.get("kind"),"ALL".equals(q.get("orderScope")),
            "CNY".equals(q.get("displayCurrency")),q.get("fxSnapshot"),page(q.getOrDefault("page","1")),page(q.getOrDefault("spendPage","1")),q.get("numbers"),q.get("search"),q.get("assignment"));
        return ApiResponse.ok(queries.query(scope,guard.personal(principal),guard.filter(q),selection));
    }
    @GetMapping("/status") public ApiResponse<?> status(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal principal){
        return ApiResponse.ok(queries.status(guard.s(principal),guard.filter(q)));
    }
    private static int page(String value){try{int page=Integer.parseInt(value);if(page<1)throw new NumberFormatException();return page;}catch(NumberFormatException invalid){throw bad("页码不正确");}}
}
