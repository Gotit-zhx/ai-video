package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Request-local, read-only valuation of returned goods. Never publishes inventory credits. */
final class FinanceQianyiReturnCostSummary {
    static final String BASIS="退货成本按千易有效退货 SKU × 退货数量，使用收货月份的人民币标准成本；沿用店铺成本仓、组合装换算及历史价格规则，不含贴单费。未匹配部分不按零成本计算，仅供统计，不冲回营业成本、不改变利润。";
    final FinanceRepository repo;
    final String organization;
    final Map<String,FinanceOrderCosts.Context> contexts=new HashMap<>();
    final Map<FinanceOrderCosts.CatalogKey,FinanceOrderCosts.Catalog> catalogs=new HashMap<>();
    final Map<String,FinanceOrderCosts.Lookup> lookups=new HashMap<>();
    FinanceQianyiReturnCostSummary(FinanceRepository repo,String organization){this.repo=repo;this.organization=organization;}

    Map<String,Object> calculate(String shop,Map<String,Object> source){
        var context=contexts.computeIfAbsent(shop,id->new FinanceOrderCosts(repo).context(organization,id,catalogs));
        var lookup=context==null?null:lookups.computeIfAbsent(shop,id->FinanceOrderCosts.lookup(receiptContext(context)));
        return value(source,context,lookup);
    }
    static Map<String,Object> pending(String reason){
        var result=new LinkedHashMap<String,Object>();
        result.put("status","INCOMPLETE");result.put("currency","CNY");result.put("amountCny",null);
        result.put("knownAmountCny",BigDecimal.ZERO);result.put("matchedLines",0);result.put("lineCount",1);
        result.put("fallbackLines",0);result.put("note",reason);result.put("lines",List.of());
        return result;
    }
    static Map<String,Object> value(Map<String,Object> source,FinanceOrderCosts.Context context){
        return value(source,context,null);
    }
    static FinanceOrderCosts.Context receiptContext(FinanceOrderCosts.Context context){
        var settings=new LinkedHashMap<>(context.settings());var rules=new LinkedHashMap<>(map(settings.get("rules")));
        rules.put("basis","order");settings.put("rules",rules);
        return new FinanceOrderCosts.Context(context.shopId(),context.groupId(),context.region(),
            "Asia/Shanghai",context.bookId(),context.costs(),settings,Map.of());
    }
    static Map<String,Object> value(Map<String,Object> source,FinanceOrderCosts.Context context,FinanceOrderCosts.Lookup prepared){
        if(context==null)return pending("店铺成本资料尚未配置");
        long received=FinanceQianyiReceipt.received(source.get("receiveTime"));
        if(received<=0)return pending("缺少实际收货时间");
        var items=new ArrayList<Map<String,Object>>();var seen=new HashMap<String,Map<String,Object>>();
        for(var line:rows(source.get("returnSkuList"))){
            if(FinanceQianyiEvidence.deleted(line.get("isDeleted"))||
                    line.containsKey("selected")&&number(line.get("selected"))==0)continue;
            String id=text(line.get("orderSkuId"));
            var item=new LinkedHashMap<String,Object>();
            item.put("model_sku",text(line.get("sku")));item.put("model_quantity_purchased",line.get("quantity"));
            item.put("id",id);
            // Repeated identical source lines are counted once. Conflicting quantities are not guessed.
            if(!id.isBlank()){
                var previous=seen.putIfAbsent(id,item);
                if(previous!=null){if(!previous.equals(item))return pending("千易同一退货商品行内容不一致");continue;}
            }
            items.add(item);
        }
        if(items.isEmpty())return pending("千易尚未返回有效退货商品数量");
        // Qianyi orderSkuId is not a platform model ID: never apply platform mappings by guessed identifiers.
        var receiptContext=receiptContext(context);
        var result=FinanceOrderCosts.calculate(Map.of("status","COMPLETED","platform","SHOPEE",
            "record_date",Instant.ofEpochMilli(received).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()),
            Map.of("item_list",items),receiptContext,prepared);
        result.put("basis","RETURN_RECEIPT_STANDARD");result.put("note",BASIS);
        return result;
    }
    void attach(Map<String,Object> row,Map<String,Object> source){
        if(number(row.get("receipt_qualified"))==1)row.put("returnCost",calculate(text(row.get("shop_id")),source));
    }
    Map<String,Object> totals(FinanceRepository.Query query){
        var totals=new LinkedHashMap<String,Object>();
        totals.put("currency","CNY");totals.put("basis",BASIS);totals.put("knownAmountCny",BigDecimal.ZERO);
        for(String field:List.of("records","completeRecords","pendingRecords","estimatedRecords"))totals.put(field,0L);
        repo.jdbc().query("SELECT r.shop_id,r.data_json"+FinanceQianyiReturns.FROM+query.where()+
            " AND "+FinanceQianyiReturns.QUALIFIED,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                var cost=calculate(rs.getString("shop_id"),repo.read(rs.getString("data_json")));
                totals.put("records",number(totals.get("records"))+1);
                String count=cost.get("amountCny")==null?"pendingRecords":"completeRecords";
                totals.put(count,number(totals.get(count))+1);
                if(number(cost.get("fallbackLines"))>0)totals.put("estimatedRecords",number(totals.get("estimatedRecords"))+1);
                totals.put("knownAmountCny",decimal(totals.get("knownAmountCny")).add(decimal(cost.get("knownAmountCny"))));
            },query.args().toArray());
        totals.put("amountCny",number(totals.get("pendingRecords"))==0?totals.get("knownAmountCny"):null);
        return totals;
    }
}
