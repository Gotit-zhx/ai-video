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
    @org.springframework.beans.factory.annotation.Autowired private FinanceOrderCharges charges;
    @org.springframework.beans.factory.annotation.Autowired private FinanceLabelCosts labelCosts;
    @GetMapping("/label-costs") public ApiResponse<?> labelCosts(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=service.authorize(p);String shop=q.getOrDefault("shopId","");if(!shop.isBlank())service.ownedShop(scope,shop);
        return ApiResponse.ok(labelCosts.workspace(scope,costFilter(q),q));
    }
    @PostMapping("/label-costs/import") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<?> importLabelCosts(@RequestParam MultipartFile file,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{
        try(var input=file.getInputStream()){
            return ApiResponse.ok(labelCosts.upload(service.authorize(p),p.userId(),text(file.getOriginalFilename()),input));
        }
    }
    @DeleteMapping("/label-costs/imports/{id}") public ApiResponse<?> removeLabelCosts(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal p){
        labelCosts.remove(service.authorize(p),id);return ApiResponse.ok("正在移除账单费用，完成后自动更新");
    }
    @PostMapping("/label-costs/imports/{id}/retry") public ApiResponse<?> retryLabelCosts(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal p){
        labelCosts.retry(service.authorize(p),id);return ApiResponse.ok("已加入重试队列");
    }
    @PostMapping("/label-costs/rematch") public ApiResponse<?> rematchLabelCosts(@AuthenticationPrincipal PlatformPrincipal p){
        labelCosts.rematch(service.authorize(p));return ApiResponse.ok("已加入自动匹配队列");
    }
    @GetMapping("/charges") public ApiResponse<?> charges(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=service.authorize(p);String shopId=q.getOrDefault("shopId","");if(!shopId.isBlank())service.ownedShop(scope,shopId);
        return ApiResponse.ok(charges.workspace(scope,q.getOrDefault("groupId",""),(int)Math.min(Integer.MAX_VALUE,Math.max(1,number(q.getOrDefault("page","1")))),costFilter(q),q.get("numbers")));
    }
    @PostMapping("/charges/rematch") public ApiResponse<?> rematchCharges(@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){
        charges.rematch(service.authorize(p),p.userId(),text(body.get("groupId")));return ApiResponse.ok("已重新匹配");
    }
    @PostMapping("/charges/preview") public ApiResponse<?> previewCharges(@RequestParam String groupId,@RequestParam MultipartFile file,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{return ApiResponse.ok(charges.preview(service.authorize(p),groupId,file.getBytes(),text(file.getOriginalFilename())));}
    @PostMapping("/charges/import") public ApiResponse<?> importCharges(@RequestParam String groupId,@RequestParam MultipartFile file,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{return ApiResponse.ok(charges.upload(service.authorize(p),p.userId(),groupId,file.getBytes(),text(file.getOriginalFilename())));}
    @DeleteMapping("/charges/imports/{id}") public ApiResponse<?> removeCharges(@PathVariable String id,@RequestParam String groupId,@AuthenticationPrincipal PlatformPrincipal p){charges.remove(service.authorize(p),p.userId(),groupId,id);return ApiResponse.ok("来源已移除，后台重算中");}
    @PostMapping("/charges/rules") public ApiResponse<?> chargeRule(@RequestBody Map<String,Object> b,@AuthenticationPrincipal PlatformPrincipal p){throw bad("贴单单价配置已停用，请导入月度仓库账单");}
    @DeleteMapping("/charges/rules/{id}") public ApiResponse<?> deleteChargeRule(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal p){throw bad("贴单单价配置已停用，原规则不再参与统计");}

    @org.springframework.beans.factory.annotation.Autowired
    private FinanceCostExport costExport;
    @GetMapping("/costs/returns") public ApiResponse<?> returnedCosts(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=service.authorize(p);String shopId=q.getOrDefault("shopId","");if(!shopId.isBlank())service.ownedShop(scope,shopId);
        return ApiResponse.ok(workspace.returnCosts(scope,costFilter(q),q.get("numbers")));
    }
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
    @GetMapping("/costs") public ApiResponse<?> costs(@RequestParam(required=false) String shopId,@RequestParam(defaultValue="") String groupId,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.costs(service.authorize(p),shopId,groupId));}
    @GetMapping("/costs/summary") public ApiResponse<?> costSummary(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=service.authorize(p);String shopId=q.getOrDefault("shopId","");if(!shopId.isBlank())service.ownedShop(scope,shopId);
        return ApiResponse.ok(workspace.costSummary(scope,costFilter(q),q.getOrDefault("kind","SETTLED_ORDER"),q.get("displayCurrency"),q.get("fxSnapshot")));
    }
    @GetMapping("/costs/orders") public ApiResponse<?> orderCosts(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=service.authorize(p);String shopId=q.getOrDefault("shopId","");if(!shopId.isBlank())service.ownedShop(scope,shopId);
        var filter=costFilter(q);
        int page=(int)Math.min(Integer.MAX_VALUE,Math.max(1,number(q.getOrDefault("page","1"))));
        return ApiResponse.ok(workspace.orderCosts(scope,filter,q.getOrDefault("kind","SETTLED_ORDER"),q.getOrDefault("recordId",""),page,q.getOrDefault("costStatus","ALL"),q.get("numbers")));
    }
    @GetMapping("/costs/orders/export") public org.springframework.http.ResponseEntity<byte[]> exportOrderCosts(
            @RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=service.authorize(p);String shopId=q.getOrDefault("shopId","");if(!shopId.isBlank())service.ownedShop(scope,shopId);
        var filter=costFilter(q);
        byte[] bytes=costExport.export(scope,filter,q.getOrDefault("kind","SETTLED_ORDER"),q.getOrDefault("recordId",""),q.getOrDefault("costStatus","ALL"),q.get("numbers"));
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store, private")
            .header("Content-Disposition","attachment; filename=\"order-costs-"+filter.start()+"-"+filter.end()+".xlsx\"")
            .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(bytes);
    }
    private static Filter costFilter(Map<String,String> q){
        var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        return new Filter(q.containsKey("start")?FinanceCostWorkspace.day(q.get("start")):today.minusDays(29),
                q.containsKey("end")?FinanceCostWorkspace.day(q.get("end")):today,q.getOrDefault("shopId",""),q.getOrDefault("platform",""),
                q.getOrDefault("currency",""),"day",q.getOrDefault("groupId",""));
    }
    @PostMapping("/costs/import") public ApiResponse<?> upload(@RequestParam String groupId,@RequestParam MultipartFile file,@RequestParam(required=false) String metadata,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{
        Map<String,Object> meta=metadata==null||metadata.isBlank()?Map.of():workspace.decode(metadata);
        return ApiResponse.ok(workspace.inputSafety().stage(p,"IMPORT",groupId,"","",Map.of(),file.getBytes(),text(file.getOriginalFilename()),meta));
    }
    public ApiResponse<?> upload(String groupId,MultipartFile file,PlatformPrincipal p)throws Exception{return upload(groupId,file,null,p);}
    @PutMapping("/costs/{shopId}/settings") public ApiResponse<?> settings(@PathVariable String shopId,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return stage(p,"SETTINGS","",shopId,"",body);}
    @PutMapping("/costs/{shopId}/mapping") public ApiResponse<?> mapping(@PathVariable String shopId,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return stage(p,"MAPPING","",shopId,"",body);}
    @DeleteMapping("/costs/groups/{groupId}/files/{id}") public ApiResponse<?> removeSource(@PathVariable String groupId,@PathVariable String id,@RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return stage(p,"REMOVE_SOURCE",groupId,"",id,body==null?Map.of():body);}
    @PostMapping("/costs/{shopId}/manual-mapping") public ApiResponse<?> manualMapping(@PathVariable String shopId,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return stage(p,"MANUAL_CREATE","",shopId,"",body);}
    @PutMapping("/costs/groups/{groupId}/manual/{id}") public ApiResponse<?> editManual(@PathVariable String groupId,@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return stage(p,"MANUAL_UPDATE",groupId,"",id,body);}
    @DeleteMapping("/costs/groups/{groupId}/manual/{id}") public ApiResponse<?> deleteManual(@PathVariable String groupId,@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return stage(p,"MANUAL_DELETE",groupId,"",id,body);}
    private ApiResponse<?> stage(PlatformPrincipal p,String type,String group,String shop,String target,Map<String,Object> body){return ApiResponse.ok(workspace.inputSafety().stage(p,type,group,shop,target,body,null,"",map(body.get("metadata"))));}
    @GetMapping("/costs/input-changes") public ApiResponse<?> inputChanges(@RequestParam(defaultValue="") String groupId,@RequestParam(defaultValue="") String shopId,@RequestParam(defaultValue="1") int page,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.inputSafety().list(p,groupId,shopId,page));}
    @GetMapping("/costs/input-changes/{id}") public ApiResponse<?> inputChange(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.inputSafety().detail(p,id));}
    @PostMapping("/costs/input-changes/{id}/confirm") public ApiResponse<?> confirmInput(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.inputSafety().confirm(p,id,body));}
    @PostMapping("/costs/input-changes/{id}/revise") public ApiResponse<?> reviseInput(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.inputSafety().revise(p,id,body));}
    @PostMapping("/costs/input-changes/{id}/reject") public ApiResponse<?> rejectInput(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.inputSafety().reject(p,id,body));}
    @PostMapping("/costs/input-changes/{id}/undo") public ApiResponse<?> undoInput(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.inputSafety().undo(p,id,body));}
    @PostMapping("/costs/{shopId}/auto-match") public ApiResponse<?> autoMatch(@PathVariable String shopId,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.autoMatch(service.authorize(p),p.userId(),shopId,body));}
    @GetMapping("/expenses") public ApiResponse<?> expenses(@RequestParam Map<String,String> query,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.expenses(service.authorize(p),query));}
    @PostMapping("/expenses") public ApiResponse<?> addExpense(@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.expense(service.authorize(p),p.userId(),null,body));}
    @PutMapping("/expenses/{id}") public ApiResponse<?> editExpense(@PathVariable String id,@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(workspace.expense(service.authorize(p),p.userId(),id,body));}
    @PostMapping("/expense-rules") public ApiResponse<?> rule(@RequestBody Map<String,Object> body,@AuthenticationPrincipal PlatformPrincipal p){workspace.expenseRule(service.authorize(p),p.userId(),body);return ApiResponse.ok("分摊规则已保存，待执行");}
}
