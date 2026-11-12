package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@RestController
@RequestMapping("/api/v1/finance/advertising")
public class FinanceAdSpendController {
    final FinanceAdSpendService spend;final FinanceController guard;
    public FinanceAdSpendController(FinanceAdSpendService spend,FinanceController guard){this.spend=spend;this.guard=guard;}
    @ModelAttribute public void authorize(@AuthenticationPrincipal PlatformPrincipal p,HttpServletRequest r){guard.authorize(p,r);}
    Map<String,Object> report(Map<String,String> q,PlatformPrincipal p){return spend.report(guard.s(p),guard.filter(q),"CNY".equals(q.get("displayCurrency")),q.get("fxSnapshot"),q.get("search"),q.get("assignment"));}
    @GetMapping("/spend") public ApiResponse<?> list(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var result=report(q,p);var items=rows(result.get("items"));int page;
        try{page=Math.max(1,Integer.parseInt(q.getOrDefault("page","1")));}catch(Exception e){throw bad("页码不正确");}
        int start=(int)Math.min(items.size(),(long)(page-1)*50);result.put("items",items.subList(start,Math.min(items.size(),start+50)));result.put("total",items.size());result.put("page",page);result.put("pageSize",50);return ApiResponse.ok(result);
    }
    @GetMapping("/spend/{key}") public ApiResponse<?> source(@PathVariable String key,@AuthenticationPrincipal PlatformPrincipal p){return ApiResponse.ok(spend.sourceDetails(guard.s(p),key));}
    record Assignment(String organizationId,String fingerprint,List<Map<String,Object>> allocations){}
    @PutMapping("/spend/{key}/allocations") public ApiResponse<?> assign(@PathVariable String key,@RequestBody Assignment body,@AuthenticationPrincipal PlatformPrincipal p){if(!p.organizationId().equals(body.organizationId()))throw bad("登录公司已变化，请重新打开分配窗口");spend.assign(guard.s(p),p.userId(),key,body.fingerprint(),body.allocations());return ApiResponse.ok("已保存核实金额；未分配余额继续保留，历史版本可追溯");}
    @PostMapping("/imports") public ApiResponse<?> importFile(@RequestParam MultipartFile file,@RequestParam String organizationId,@RequestParam String platform,@RequestParam(defaultValue="") String accountId,@RequestParam String accountName,@RequestParam(defaultValue="false") boolean debitNegative,@RequestParam(defaultValue="true") boolean preview,@AuthenticationPrincipal PlatformPrincipal p)throws Exception{
        if(!p.organizationId().equals(organizationId))throw bad("登录公司已变化，请重新预览报表");
        if(file.getSize()>12*1024*1024)throw bad("广告报表上限为 12 MiB");
        var entries=FinanceAdSpendImport.parse(FinanceWorkbook.read(file.getBytes(),text(file.getOriginalFilename())),platform,accountId,accountName,debitNegative);
        if(preview){var result=new LinkedHashMap<String,Object>();result.put("rows",entries.size());result.put("sample",entries.subList(0,Math.min(5,entries.size())));result.put("totals",FinanceAdSpendReport.summaries(entries,"currency"));return ApiResponse.ok(result);}
        return ApiResponse.ok(spend.saveImport(guard.s(p),p.userId(),entries,text(file.getOriginalFilename())));
    }
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam Map<String,String> q,@AuthenticationPrincipal PlatformPrincipal p){
        var data=report(q,p);var sheets=new LinkedHashMap<String,List<List<String>>>();
        sheets.put("分组广告消耗",sheet(rows(data.get("groups")),List.of("platform","group_name","currency","cash_amount","credit_amount","grant_amount","amount","net_amount","pending_records"),List.of("平台","分组","币种","现金消耗","信用额度消耗","广告费用赠款消耗","广告消耗（投放报表）","净广告消耗","待分配记录数")));
        sheets.put("店铺广告消耗",sheet(rows(data.get("shops")),List.of("platform","shop_name","group_name","currency","cash_amount","credit_amount","grant_amount","amount","net_amount","records"),List.of("平台","店铺","分组","币种","现金消耗","信用额度消耗","广告费用赠款消耗","广告消耗（投放报表）","净广告消耗","记录数")));
        var detailRows=new ArrayList<Map<String,Object>>();for(var original:rows(data.get("items"))){var row=new LinkedHashMap<>(original);var fx=map(row.get("fx"));row.put("fx_business_date",fx.getOrDefault("businessDate",row.get("record_date")));row.put("fx_rate_date",fx.get("rateDate"));row.put("fx_provider",fx.get("provider"));detailRows.add(row);}
        sheets.put("店铺消耗明细",sheet(detailRows,List.of("record_date","platform","shop_name","group_name","account_name","account_id","campaign_name","campaign_id","source_currency","original_cash_amount","original_credit_amount","original_grant_amount","original_amount","original_net_amount","currency","cash_amount","credit_amount","grant_amount","amount","net_amount","source","assignment_status","funding_basis","fx_business_date","fx_rate_date","cny_rate","fx_provider"),List.of("日期","平台","店铺","分组","广告账户","广告账户ID","推广系列名称","推广系列ID","原币","原币现金消耗","原币信用额度消耗","原币广告赠款消耗","原币消耗合计","原币净消耗","展示币种","现金消耗","信用额度消耗","广告费用赠款消耗","广告消耗（投放报表）","净广告消耗","来源","归属状态","金额口径","换算业务日","汇率公布日","1原币兑人民币","汇率来源")));
        var metadataFields=List.of("report_type","campaign_objective","campaign_structure","campaign_automation_type","official_store_ids","metadata_endpoint","metadata_observed_at","metadata_status");
        var metadataLabels=List.of("原始投放类型","推广目标","系列结构","系列自动化方式","官方目标店铺ID（非历史资金归属）","系列资料接口","系列资料查询时间","系列资料状态");
        var detail=sheets.get("店铺消耗明细");
        for(int index=0;index<detail.size();index++){
            var row=new ArrayList<>(detail.get(index));
            if(index==0)row.addAll(metadataLabels);
            else for(String field:metadataFields){Object value=detailRows.get(index-1).get(field);row.add(value instanceof List<?> list?String.join(",",list.stream().map(FinanceData::text).toList()):text(value).isBlank()?"未提供":text(value));}
            detail.set(index,row);
        }
        sheets.put("账户级资金核对",sheet(rows(data.get("accountFunding")),List.of("source","platform","account_name","account_id","currency","cash_amount","credit_amount","grant_amount","amount","net_amount","tax_amount"),List.of("来源","平台","账户","账户ID","展示币种","现金消耗","信用额度消耗","广告费用赠款消耗","来源费用合计","净广告消耗","预估税额（单列）")));
        sheets.put("历史汇率",sheet(rows(data.get("fxEvidence")),List.of("businessDate","rateDate","currency","rate","provider"),List.of("换算业务日","汇率公布日","原币","1原币兑人民币","汇率来源")));
        var notes=new ArrayList<List<String>>();notes.add(List.of("说明","内容"));notes.add(List.of("金额分项","未提供表示源数据未知，不能当作零；三项全已知时与合计核对。"));notes.add(List.of("范围","三表汇总按完整筛选；搜索和归属状态仅限制明细。API 与资金报表重叠不重复相加；不改利润流水。"));notes.add(List.of("重叠排除记录数",text(data.get("overlapExcluded"))));notes.add(List.of("账户资金","BC账户日费用与普通BASIC投放分来源核对，不相加、不复制到店铺或系列；预估税额单列，不再加进合计。"));notes.add(List.of("换算日期","每行按广告报表业务日的历史率转换后汇总，周末和节假日采用不晚于业务日的最近公布率；缺失率保留未知。"));
        notes.add(List.of("净消耗","TikTok net_cost / billed_cost：不含广告赠款、优惠券的消耗；不等于现金付款。资金报表仅在现金和授信均明确时汇总净额。"));notes.add(List.of("账户分项","cash_spend/voucher_spend 仅支持账户级，可能延迟24–48小时；账户级资金核对与店铺/GMV明细范围可能不同，不可相加或按比例分配。未取得授信消耗明细，不将 ad credit 或 spend_credits 转化事件误作授信金额。"));
        notes.add(List.of("官方店铺关系","系列/广告组接口的当前推广目标仅作来源核对，不等于历史三项资金已逐店分配；系列元信息不改原始投放类型、金额、店铺归属或利润。"));
        map(data.get("fx")).forEach((k,v)->notes.add(List.of(k,text(v))));sheets.put("口径与汇率",notes);
        var numeric=new HashMap<String,Set<Integer>>();numeric.put("分组广告消耗",Set.of(3,4,5,6,7,8));numeric.put("店铺广告消耗",Set.of(4,5,6,7,8,9));numeric.put("店铺消耗明细",Set.of(9,10,11,12,13,15,16,17,18,19,25));
        numeric.put("账户级资金核对",Set.of(5,6,7,8,9,10));numeric.put("历史汇率",Set.of(3));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=advertising-spend.xlsx").cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(FinanceWorkbook.xlsx(sheets,numeric));
    }
    static List<List<String>> sheet(List<Map<String,Object>> rows,List<String> fields,List<String> labels){var out=new ArrayList<List<String>>();out.add(labels);for(var r:rows)out.add(fields.stream().map(k->r.get(k)!=null?text(r.get(k)):number(r.get("known_"+k))>0?text(r.get("partial_"+k))+"（已知部分）":text(r.get("reason_"+k)).isBlank()?"未提供":text(r.get("reason_"+k))).toList());return out;}
}
