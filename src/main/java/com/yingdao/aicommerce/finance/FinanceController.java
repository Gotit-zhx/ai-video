package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
    final FinanceService service;final FinanceRepository repo;final FinanceImport imports;final FinanceAds ads;
    public FinanceController(FinanceService service,FinanceRepository repo,FinanceImport imports,FinanceAds ads){
        this.service=service;this.repo=repo;this.imports=imports;this.ads=ads;
    }
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal p,HttpServletRequest request){
        service.authorize(p);
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
            String site=request.getHeader("Sec-Fetch-Site");
            if(site!=null&&!Set.of("same-origin","none").contains(site))throw new com.yingdao.aicommerce.common.exception.BusinessException(HttpStatus.FORBIDDEN,"请从本站发起财务操作");
            String origin=request.getHeader("Origin");
            if(origin!=null){try{var uri=java.net.URI.create(origin);if(!request.getServerName().equalsIgnoreCase(uri.getHost()))throw bad("操作来源不正确");}catch(IllegalArgumentException e){throw bad("操作来源不正确");}}
        }
    }
    Scope s(PlatformPrincipal p){return service.authorize(p);}
    Filter filter(Map<String,String> q){
        try{return new Filter(LocalDate.parse(q.getOrDefault("start",LocalDate.now().minusDays(29).toString())),
                LocalDate.parse(q.getOrDefault("end",LocalDate.now().toString())),q.get("shopId"),q.get("platform"),q.get("currency"),q.get("granularity"));}
        catch(java.time.format.DateTimeParseException e){throw bad("日期格式不正确");}
    }
    @GetMapping("/overview") public ApiResponse<?> overview(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.dashboard(s(p),filter(q),"CNY".equals(q.get("displayCurrency")),q.get("fxSnapshot")));}
    @GetMapping("/shops") public ApiResponse<?> shops(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(Map.of("items",service.shops(s(p)),"sync",service.syncStates(s(p))));}
    record ShopConfig(boolean enabled,String currency,String timezone){}
    @PutMapping("/shops/{id}") public ApiResponse<?> configure(@PathVariable String id,@RequestBody ShopConfig body,@AuthenticationPrincipal PlatformPrincipal p){
        service.configure(s(p),id,body.enabled,body.currency,body.timezone);return ApiResponse.ok("已保存");
    }
    @PostMapping("/shops/{id}/sync") public ApiResponse<?> sync(@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal p){service.sync(s(p),id);return ApiResponse.ok("已加入同步队列");}
    record Authorization(String platform,String market){}
    @PostMapping("/shops/authorize") public ApiResponse<?> start(@RequestBody Authorization body,@AuthenticationPrincipal PlatformPrincipal p){
        s(p);return ApiResponse.ok(service.shops.start(body.platform,"/?view=finance",body.market,p));
    }
    @GetMapping("/records") public ApiResponse<?> records(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var result=repo.details(s(p),filter(q),q.get("kind"),(int)Math.max(1,number(q.getOrDefault("page","1"))),50);
        if("CNY".equals(q.get("displayCurrency")))service.fx.enrich(rows(result.get("items")),service.fx.quote(q.get("fxSnapshot")));
        return ApiResponse.ok(result);
    }
    @GetMapping("/records/{id}") public ApiResponse<?> record(@PathVariable String id,@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var row=repo.detail(s(p),id);
        if("CNY".equals(q.get("displayCurrency")))service.fx.enrich(List.of(row),service.fx.quote(q.get("fxSnapshot")));
        return ApiResponse.ok(row);
    }
    @GetMapping("/reconciliation") public ApiResponse<?> reconciliation(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var result=repo.reconciliation(s(p),filter(q));if("CNY".equals(q.get("displayCurrency")))service.fx.enrich(result,service.fx.quote(q.get("fxSnapshot")));
        return ApiResponse.ok(Map.of("items",result,"limit",1000));
    }
    @GetMapping("/ads") public ApiResponse<?> ads(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(ads.list(s(p)));}
    record AdConfig(String advertiserId,String shopId,String accessToken,String gmvStoreId){}
    @PostMapping("/ads") public ApiResponse<?> connectAds(@RequestBody AdConfig body,@AuthenticationPrincipal PlatformPrincipal p){ads.connect(s(p),body.advertiserId,body.shopId,body.accessToken,body.gmvStoreId);return ApiResponse.ok("广告授权已验证");}
    record Enabled(boolean enabled){}
    @PutMapping("/ads/{id}") public ApiResponse<?> toggleAds(@PathVariable String id,@RequestBody Enabled body,@AuthenticationPrincipal PlatformPrincipal p){ads.pause(s(p),id,body.enabled);return ApiResponse.ok("已保存");}
    @PostMapping("/imports/preview") public ApiResponse<?> preview(@RequestParam MultipartFile file,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{
        s(p);return ApiResponse.ok(imports.preview(file.getBytes(),text(file.getOriginalFilename())));
    }
    @PostMapping("/imports") public ApiResponse<?> importFile(@RequestParam MultipartFile file,@RequestParam String shopId,
            @RequestParam String kind,@RequestParam String mapping,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{
        return ApiResponse.ok(imports.commit(s(p),shopId,kind,repo.read(mapping),file.getBytes(),text(file.getOriginalFilename())));
    }
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        Scope s=s(p);Filter f=filter(q);var rows=new ArrayList<List<String>>();
        String[] keys={"record_date","platform","shop_id","kind","external_id","order_id","statement_id","category","status","currency","amount","revenue","source"};
        boolean cny="CNY".equals(q.get("displayCurrency"));FinanceFx.Quote quote=cny?service.fx.quote(q.get("fxSnapshot")):null;
        var headers=new ArrayList<>(List.of("业务日期","平台","店铺ID","类型","记录编号","订单编号","账单编号","费用类别","状态","币种","原币金额","归因收入","来源"));
        if(cny)headers.addAll(List.of("折算人民币","人民币归因收入","参考汇率","汇率时间","汇率来源"));rows.add(headers);
        int page=1;long total=0;
        do{
            var result=repo.details(s,f,q.get("kind"),page,100);total=number(result.get("total"));
            if(total>100000)throw bad("本次导出超过 100,000 条，请缩小日期或店铺范围");
            for(var item:FinanceData.rows(result.get("items"))){
                var cells=new ArrayList<>(Arrays.stream(keys).map(key->text(item.get(key))).toList());
                if(cny)cells.addAll(List.of(text(quote.convert(item.get("amount"),text(item.get("currency")))),
                        text(quote.convert(item.get("revenue"),text(item.get("currency")))),text(quote.rates().get(text(item.get("currency")))),quote.time().toString(),"ExchangeRate-API"));rows.add(cells);
            }
            page++;
        }while((page-1L)*100<total);
        boolean csv="csv".equals(q.get("format"));byte[] bytes;
        if(csv)bytes=FinanceWorkbook.csv(rows).getBytes(StandardCharsets.UTF_8);
        else{
            var sheets=new LinkedHashMap<String,List<List<String>>>();sheets.put("财务明细",rows);
            sheets.put("统计口径",List.of(List.of("日期范围",f.start()+" 至 "+f.end()),List.of("币种",f.currency().isBlank()?"原币分列，禁止跨币种直接合计":f.currency()),
                    List.of("范围","当前筛选全部记录；平台已结算不代表银行到账"),List.of("时间口径","订单按创建日，结算费用按结算日，广告按广告账户日"),
                    List.of("生成时间",java.time.Instant.now().toString())));
            var reconciliation=new ArrayList<List<String>>();
            var recHeaders=new ArrayList<>(List.of("店铺ID","账单编号","来源","币种","账单金额","结算明细金额","差异","结果"));
            if(cny)recHeaders.addAll(List.of("人民币账单金额","人民币明细金额","人民币差异","参考汇率"));reconciliation.add(recHeaders);
            var bills=repo.reconciliation(s,f,100001);
            if(bills.size()>100000)throw bad("对账导出超过 100,000 张账单，请缩小日期或店铺范围");
            for(var row:bills){
                var cells=new ArrayList<>(List.of(text(row.get("shop_id")),text(row.get("external_id")),text(row.get("source")),text(row.get("currency")),text(row.get("amount")),text(row.get("detail_amount")),text(row.get("difference")),text(row.get("result"))));
                if(cny)for(String field:List.of("amount","detail_amount","difference"))cells.add(text(quote.convert(row.get(field),text(row.get("currency")))));
                if(cny)cells.add(text(quote.rates().get(text(row.get("currency")))));reconciliation.add(cells);
            }
            if(cny){
                var rateRows=new ArrayList<List<String>>();rateRows.add(List.of("原币","参考汇率"));
                quote.rates().forEach((currency,rate)->rateRows.add(List.of(currency,rate.toPlainString())));
                rateRows.add(List.of("汇率时间",quote.time().toString()));rateRows.add(List.of("汇率来源","ExchangeRate-API · https://www.exchangerate-api.com"));
                rateRows.add(List.of("换算口径","同一参考汇率折算展示，不是历史结汇金额；原币账单不变"));sheets.put("人民币汇率快照",rateRows);
            }
            sheets.put("结算对账",reconciliation);bytes=FinanceWorkbook.xlsx(sheets);
        }
        return ResponseEntity.ok().header("Cache-Control","no-store, private").header("Content-Disposition","attachment; filename=\"finance-"+f.start()+"."+(csv?"csv":"xlsx")+"\"")
                .contentType(MediaType.parseMediaType(csv?"text/csv;charset=UTF-8":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(bytes);
    }
}
