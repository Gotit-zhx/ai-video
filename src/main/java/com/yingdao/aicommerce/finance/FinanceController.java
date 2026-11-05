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
    final FinanceService service;final FinanceRepository repo;final FinanceImport imports;final FinanceAds ads;final FinanceFields fields;final FinanceAdAuthorization adAuthorization;
    public FinanceController(FinanceService service,FinanceRepository repo,FinanceImport imports,FinanceAds ads){
        this(service,repo,imports,ads,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceController(FinanceService service,FinanceRepository repo,FinanceImport imports,FinanceAds ads,FinanceAdAuthorization adAuthorization){
        this.service=service;this.repo=repo;this.imports=imports;this.ads=ads;this.fields=new FinanceFields(repo);this.adAuthorization=adAuthorization;
    }
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal p,HttpServletRequest request){
        service.authorize(p);
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
            String path=request.getRequestURI();
            boolean personalFields="PUT".equals(request.getMethod())&&path.startsWith("/api/v1/finance/fields/");
            boolean readScreen="POST".equals(request.getMethod())&&path.equals("/api/v1/finance/screen");
            if(!personalFields&&!readScreen)service.requireManagement(p);
            String site=request.getHeader("Sec-Fetch-Site");
            if(site!=null&&!Set.of("same-origin","none").contains(site))throw new com.yingdao.aicommerce.common.exception.BusinessException(HttpStatus.FORBIDDEN,"请从本站发起财务操作");
            String origin=request.getHeader("Origin");
            if(origin!=null){try{var uri=java.net.URI.create(origin);if(!request.getServerName().equalsIgnoreCase(uri.getHost()))throw bad("操作来源不正确");}catch(IllegalArgumentException e){throw bad("操作来源不正确");}}
        }
    }
    Scope s(PlatformPrincipal p){return service.authorize(p);}
    Scope personal(PlatformPrincipal p){return new Scope(p.organizationId(),p.userId());}
    Filter filter(Map<String,String> q){
        var month=FinanceCalendar.previousMonth(java.time.Instant.now());
        try{return new Filter(LocalDate.parse(q.getOrDefault("start",month.atDay(1).toString())),
                LocalDate.parse(q.getOrDefault("end",month.atEndOfMonth().toString())),q.get("shopId"),q.get("platform"),q.get("currency"),q.get("granularity"),q.get("groupId"));}
        catch(java.time.format.DateTimeParseException e){throw bad("日期格式不正确");}
    }
    @GetMapping("/overview") public ApiResponse<?> overview(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.dashboard(s(p),filter(q),"CNY".equals(q.get("displayCurrency")),q.get("fxSnapshot")));}
    @PostMapping("/screen") public ApiResponse<?> screen(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.screen(s(p),filter(q),"CNY".equals(q.get("displayCurrency")),q.get("fxSnapshot")));}
    @GetMapping("/shops") public ApiResponse<?> shops(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(Map.of("items",service.shops(s(p)),"sync",service.syncStates(s(p)),"groups",service.groups(s(p)),"deletions",service.deletions(s(p))));}
    record ShopConfig(boolean enabled,String currency,String timezone,String groupId,String alias){}
    @PutMapping("/shops/{id}") public ApiResponse<?> configure(@PathVariable("id") String id,@RequestBody ShopConfig body,@AuthenticationPrincipal PlatformPrincipal p){
        service.configure(s(p),id,body.enabled,body.currency,body.timezone,body.groupId,body.alias);return ApiResponse.ok("已保存");
    }
    @PostMapping("/shops/{id}/sync") public ApiResponse<?> sync(@PathVariable("id") String id,@AuthenticationPrincipal PlatformPrincipal p){service.sync(s(p),id);return ApiResponse.ok("已加入店铺优先队列，将从现有进度继续；平台限流和异常退避仍会生效");}
    @DeleteMapping("/shops/{id}") public ApiResponse<?> removeShop(@PathVariable("id") String id,@AuthenticationPrincipal PlatformPrincipal p){service.removeShop(s(p),id,p.userId());return ApiResponse.ok("已停止同步并清除授权凭据，历史数据正在后台分批删除");}
    record GroupName(String name){}
    @GetMapping("/groups") public ApiResponse<?> groups(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(service.groups(s(p)));}
    @PostMapping("/groups") public ApiResponse<?> addGroup(@RequestBody GroupName body,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(Map.of("id",service.saveGroup(s(p),null,body.name)));}
    @PutMapping("/groups/{id}") public ApiResponse<?> renameGroup(@PathVariable("id") String id,@RequestBody GroupName body,@AuthenticationPrincipal PlatformPrincipal p){service.saveGroup(s(p),id,body.name);return ApiResponse.ok("分组已保存");}
    @DeleteMapping("/groups/{id}") public ApiResponse<?> deleteGroup(@PathVariable("id") String id,@AuthenticationPrincipal PlatformPrincipal p){service.deleteGroup(s(p),id);return ApiResponse.ok("分组已删除");}
    record Authorization(String platform,String market,String groupId,String shopId){}
    @PostMapping("/shops/authorize") public ApiResponse<?> start(@RequestBody Authorization body,@AuthenticationPrincipal PlatformPrincipal p){
        s(p);return ApiResponse.ok(service.authorization().start(body.platform,body.market,s(p),p.userId(),body.groupId,body.shopId));
    }
    @GetMapping("/records") public ApiResponse<?> records(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var scope=s(p);var selected=filter(q);boolean allOrders=allOrders(q,q.get("kind"));
        var result=new LinkedHashMap<>(repo.details(scope,selected,q.get("kind"),(int)Math.max(1,number(q.getOrDefault("page","1"))),50,q.get("numbers"),allOrders));
        result.put("dateBasis",FinanceCalendar.contract());
        result.put("fieldSettings",fields.settings(personal(p),q.get("kind")));
        if(allOrders){
            // Raw-order lookup does not need the settlement dashboard or its cost aggregates.
            var shops=service.shops(scope);
            result.put("shops",shops);result.put("groups",service.groups(scope));result.put("sync",List.of());
            result.put("currencies",shops.stream().map(shop->text(shop.get("currency"))).filter(c->!c.isBlank()).distinct().sorted().toList());
            result.put("filters",Map.of("start",selected.start(),"end",selected.end(),"shopId",selected.shopId(),
                    "groupId",selected.groupId(),"platform",selected.platform(),"currency",selected.currency(),
                    "displayCurrency","CNY".equals(q.get("displayCurrency"))?"CNY":selected.currency(),"granularity",selected.granularity()));
            result.put("orderScope","ALL");
        }
        // List workspaces already receive this exact settled-order summary from /overview.
        // Refund/bill/settlement summaries keep their own source-specific basis.
        boolean sharedCostScope="SETTLED_ORDER".equals(FinanceCostReport.scopeKind(q.get("kind"),false));
        if(!allOrders&&(!sharedCostScope||!"false".equals(q.get("includeProductCosts"))))
            result.put("productCosts",new FinanceCostReport(repo).summary(s(p),filter(q),q.get("kind")));
        if("CNY".equals(q.get("displayCurrency"))){
            var quote=service.exchangeRates().quote(q.get("fxSnapshot"));
            service.exchangeRates().enrich(rows(result.get("items")),quote);if(allOrders)result.put("fx",quote.metadata());
        }
        return ApiResponse.ok(result);
    }
    @GetMapping("/records/{id}") public ApiResponse<?> record(@PathVariable("id") String id,@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var row=repo.detail(s(p),id,"ALL".equals(q.get("orderScope")));
        if("CNY".equals(q.get("displayCurrency")))service.exchangeRates().enrich(List.of(row),service.exchangeRates().quote(q.get("fxSnapshot")));
        return ApiResponse.ok(row);
    }
    @GetMapping("/reconciliation") public ApiResponse<?> reconciliation(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        Scope scope=s(p);Filter requested=filter(q);boolean cny="CNY".equals(q.get("displayCurrency"));
        var context=service.reconciliationContext(scope,requested,cny,q.get("fxSnapshot"));
        var selected=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),
                text(map(context.get("filters")).get("currency")),requested.granularity(),requested.groupId());
        int page=(int)Math.min(Integer.MAX_VALUE,Math.max(1,number(q.getOrDefault("page","1"))));
        var result=new LinkedHashMap<>(repo.reconciliationPage(scope,selected,page,50,q.get("numbers")));
        result.putAll(context);result.put("fieldSettings",fields.settings(personal(p),"RECONCILIATION"));
        if(cny)service.exchangeRates().enrich(rows(result.get("items")),service.exchangeRates().quote(text(map(context.get("fx")).get("id"))));
        return ApiResponse.ok(result);
    }
    @GetMapping("/fields/{view}") public ApiResponse<?> fields(@PathVariable("view") String view,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(fields.settings(personal(p),view));}
    record FieldSelection(List<String> visible,List<String> exported){}
    @PutMapping("/fields/{view}") public ApiResponse<?> fields(@PathVariable("view") String view,@RequestBody FieldSelection body,@AuthenticationPrincipal PlatformPrincipal p){
        fields.save(personal(p),view,body.visible,body.exported);return ApiResponse.ok("字段设置已保存");
    }
    @GetMapping("/ads") public ApiResponse<?> ads(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(ads.list(s(p)));}
    @GetMapping("/ads/application") public ApiResponse<?> adApplication(@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(adAuthorization.configuration(s(p)));}
    record AdApplication(String appId,String appSecret){}
    @PutMapping("/ads/application") public ApiResponse<?> adApplication(@RequestBody AdApplication body,@AuthenticationPrincipal PlatformPrincipal p){
        s(p);throw bad("广告应用由服务端统一配置，请直接复制广告授权链接");
    }
    record AdAuthorization(String advertiserId,String shopId,String gmvStoreId){}
    @PostMapping("/ads/authorize") public ApiResponse<?> authorizeAds(@RequestBody AdAuthorization body,@AuthenticationPrincipal PlatformPrincipal p){
        return ApiResponse.ok(adAuthorization.start(s(p),body.advertiserId,body.shopId,body.gmvStoreId));
    }
    record AdConfig(String advertiserId,String shopId,String accessToken,String gmvStoreId){}
    @PostMapping("/ads") public ApiResponse<?> connectAds(@RequestBody AdConfig body,@AuthenticationPrincipal PlatformPrincipal p){ads.connect(s(p),body.advertiserId,body.shopId,body.accessToken,body.gmvStoreId);return ApiResponse.ok("广告授权已验证");}
    record Enabled(boolean enabled){}
    @PutMapping("/ads/{id}") public ApiResponse<?> toggleAds(@PathVariable("id") String id,@RequestBody Enabled body,@AuthenticationPrincipal PlatformPrincipal p){ads.pause(s(p),id,body.enabled);return ApiResponse.ok("已保存");}
    @PostMapping("/imports/preview") public ApiResponse<?> preview(@RequestParam("file") MultipartFile file,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{
        s(p);return ApiResponse.ok(imports.preview(file.getBytes(),text(file.getOriginalFilename())));
    }
    @PostMapping("/imports") public ApiResponse<?> importFile(@RequestParam("file") MultipartFile file,@RequestParam("shopId") String shopId,
            @RequestParam("kind") String kind,@RequestParam("mapping") String mapping,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{
        return ApiResponse.ok(imports.commit(s(p),shopId,kind,repo.read(mapping),file.getBytes(),text(file.getOriginalFilename())));
    }
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        Scope s=s(p);Filter f=filter(q);
        String view=FinanceFields.view(q.getOrDefault("view",q.get("kind")));
        boolean rec=view.equals("RECONCILIATION"),cny="CNY".equals(q.get("displayCurrency"));
        FinanceFx.Quote quote=cny?service.exchangeRates().quote(q.get("fxSnapshot")):null;
        boolean allOrders=allOrders(q,view);
        var columns=exportColumns(fields.selected(personal(p),view,true,cny));
        if(allOrders){
            columns=new ArrayList<>(columns.stream().map(field->field.id().equals("record_date")
                    ?new FinanceFields.Field(field.id(),"下单日期（店铺时区）",field.type(),field.group(),field.cny(),field.views(),field.path()):field).toList());
            if(columns.stream().noneMatch(field->field.id().equals("first_settlement_date")))
                columns.add(new FinanceFields.Field("first_settlement_date","首次平台结算日期","date","结算核对",false,"ORDER",""));
        }
        var shopNames=exportShopNames(s);
        var rows=exportRows(columns);
        if(rec)appendReconciliation(rows,columns,s,f,quote,q.get("numbers"));
        else {
            int page=1;long total;
            do{
                var result=repo.details(s,f,view.equals("ALL")?null:view,page,100,q.get("numbers"),allOrders);total=number(result.get("total"));
                if(total>100000)throw bad("本次导出超过 100,000 条，请缩小日期或店铺范围");
                var items=FinanceData.rows(result.get("items"));
                if(cny)service.exchangeRates().enrich(items,quote);
                for(var item:items){item.put("shop_name",shopNames.getOrDefault(text(item.get("shop_id")),""));rows.add(columns.stream().map(field->FinanceFields.cell(item,field)).toList());}
                page++;
            }while((page-1L)*100<total);
        }
        boolean csv="csv".equals(q.get("format"));byte[] bytes;
        if(csv)bytes=FinanceWorkbook.csv(rows).getBytes(StandardCharsets.UTF_8);
        else{
            var sheets=new LinkedHashMap<String,List<List<String>>>();sheets.put(rec?"结算对账":"财务明细",rows);
            sheets.put("店铺日期范围",FinanceCalendar.exportWindows(f,service.shops(s)));
            sheets.put("统计口径",List.of(List.of("日期范围",f.start()+" 至 "+f.end()),List.of("店铺分组",f.groupId().isBlank()?"全部分组":"UNGROUPED".equals(f.groupId())?"未分组":service.groups(s).stream().filter(g->f.groupId().equals(g.get("id"))).map(g->text(g.get("name"))).findFirst().orElse("所选分组")),List.of("币种",f.currency().isBlank()?"原币分列，禁止跨币种直接合计":f.currency()),
                    List.of("日期口径版本",FinanceCalendar.VERSION),List.of("日期选择",FinanceCalendar.NOTE),
                    List.of("完成标识",FinanceCalendar.QUALIFICATION),
                    List.of("官方账单日期","Shopee Income 按当地实际收入释放日；TK 保留现行报表账单日期映射，不用付款日统一替换。具体店铺月份须逐笔核对官方原文件。独立 Adjustment 不能仅凭 Income 抓取完成认定已对齐。"),
                    List.of("千易收货日期","实际 receiveTime 按北京时间独立筛选，不替代平台结算日期。"),
                    List.of("编号查询",String.join("、",FinanceRecordSearch.numbers(q.get("numbers")))),
                    List.of("编号日期范围",allOrders&&!FinanceRecordSearch.numbers(q.get("numbers")).isEmpty()?"完整订单号跨全部下单日期查找；仍按当前公司、店铺、分组、平台和币种限制":"按所选日期范围"),
                    List.of("范围","当前筛选全部记录；列按当前用户的导出配置生成；平台已结算不代表银行到账"),
                    List.of("刷单口径","原始财务明细保留刷单记录及订单标记；经营汇总排除刷单结算金额、关联费用和成本，原始账单对账不删除记录。"),
                    List.of("时间口径",allOrders?"全部官方订单按店铺时区的下单日期筛选，包括取消、未结算和跨月结算订单；首次结算日期单列。收入、利润及成本统计仍按原结算口径。":"已结算订单及关联退款按首次平台结算日期筛选；结算和平台费用按平台结算日期。广告及独立费用按费用日期，待结算按订单日期。"),
                    List.of("收入口径","结算明细及账单主金额展示销售收入；商家优惠和退款已体现在收入中，不再重复扣减。平台费用、物流及调整逐项列示；净结算另列，对账使用平台原始净额。"),
                    List.of("商品成本口径","仅平台已结算订单计入商品成本；整单成本归属首次结算，后续结算调整不重复计成本。未结算成本留空并注明；退款关联成本只作核对，不再次扣减。成本固定人民币，历史价估算保留状态。"),
                    List.of("扩展字段","仅展示平台实际返回的字段；空白表示未返回或不适用。标记原币的扩展金额不做人民币转换。"),
                    List.of("生成时间（北京时间）",FinanceCalendar.display(java.time.Instant.now()))));
            if(!rec&&!allOrders){
                var rc=exportColumns(fields.selected(personal(p),"RECONCILIATION",true,cny));var rr=exportRows(rc);
                appendReconciliation(rr,rc,s,f,quote,q.get("numbers"));sheets.put("结算对账",rr);
            }
            if(cny){
                var rateRows=new ArrayList<List<String>>();rateRows.add(List.of("原币","参考汇率"));
                quote.rates().forEach((currency,rate)->rateRows.add(List.of(currency,rate.toPlainString())));
                rateRows.add(List.of("汇率时间",quote.time().toString()));rateRows.add(List.of("汇率来源","ExchangeRate-API · https://www.exchangerate-api.com"));
                rateRows.add(List.of("换算口径","同一参考汇率折算展示，不是历史结汇金额；原币账单不变"));sheets.put("人民币汇率快照",rateRows);
            }
            bytes=FinanceWorkbook.xlsx(sheets);
        }
        return ResponseEntity.ok().header("Cache-Control","no-store, private").header("Content-Disposition","attachment; filename=\"finance-"+f.start()+"."+(csv?"csv":"xlsx")+"\"")
                .contentType(MediaType.parseMediaType(csv?"text/csv;charset=UTF-8":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(bytes);
    }
    static boolean allOrders(Map<String,String> q,String kind){return "ORDER".equals(kind)&&"ALL".equals(q.get("orderScope"));}
    static ArrayList<List<String>> exportRows(List<FinanceFields.Field> columns){
        var rows=new ArrayList<List<String>>();rows.add(columns.stream().map(FinanceFields.Field::label).toList());return rows;
    }
    static List<FinanceFields.Field> exportColumns(List<FinanceFields.Field> selected){
        var columns=new ArrayList<>(selected);
        if(columns.stream().noneMatch(f->f.id().equals("shop_name")))
            columns.add(0,new FinanceFields.Field("shop_name","店铺（优先别名）","text","主要字段",false,"*",""));
        return columns;
    }
    Map<String,String> exportShopNames(Scope scope){
        var names=new HashMap<String,String>();
        for(var shop:service.shops(scope))names.put(text(shop.get("id")),first(shop,"display_name","name"));
        return names;
    }
    void appendReconciliation(List<List<String>> rows,List<FinanceFields.Field> columns,Scope s,Filter f,FinanceFx.Quote quote,String numbers){
        var items=repo.reconciliation(s,f,100001,numbers);
        if(items.size()>100000)throw bad("对账导出超过 100,000 张账单，请缩小日期或店铺范围");
        if(quote!=null)service.exchangeRates().enrich(items,quote);
        var names=exportShopNames(s);
        for(var item:items){item.put("shop_name",names.getOrDefault(text(item.get("shop_id")),""));rows.add(columns.stream().map(field->FinanceFields.cell(item,field)).toList());}
    }
}
