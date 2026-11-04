package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** A shop-column projection of the same settled fee facts used by the dashboard. */
final class FinanceFeeMatrix {
    private FinanceFeeMatrix(){}
    static byte[] workbook(Filter filter,Map<String,Object> facts){
        var shops=new ArrayList<>(rows(facts.get("shops")));
        shops.removeIf(shop->!FinanceGroups.matches(shop,filter));
        shops.sort(Comparator.comparing((Map<String,Object> shop)->text(shop.get("platform")))
                .thenComparing(shop->first(shop,"display_name","name")).thenComparing(shop->text(shop.get("id"))));
        var ids=new HashSet<String>();shops.forEach(shop->ids.add(text(shop.get("id"))));
        var fees=rows(facts.get("fees")).stream().filter(row->filter.currency().isBlank()||filter.currency().equals(text(row.get("currency")))).toList();
        if(fees.stream().anyMatch(row->"UNASSIGNED".equals(row.get("shop_id")))){
            shops.add(Map.of("id","UNASSIGNED","name","未关联店铺","platform","","currency",""));
            ids.add("UNASSIGNED");
        }
        if(fees.stream().anyMatch(row->!ids.contains(text(row.get("shop_id")))))
            throw bad("店铺范围已变化，请重新选择后导出");
        if(shops.isEmpty())throw bad("当前筛选没有可导出的店铺");
        if(shops.size()>1000)throw bad("店铺超过 1,000 家，请按分组导出");
        var currencies=new TreeSet<String>();
        {
            fees.forEach(row->currencies.add(text(row.get("currency"))));
            if(currencies.isEmpty()){
                if(!filter.currency().isBlank())currencies.add(filter.currency());
                else shops.forEach(shop->{if(!text(shop.get("currency")).isBlank())currencies.add(text(shop.get("currency")));});
            }
        }
        if(currencies.isEmpty())throw bad("当前店铺没有可用币种");
        var sheets=new LinkedHashMap<String,List<List<String>>>();
        var numeric=new LinkedHashMap<String,Set<Integer>>();
        var names=new HashMap<String,Long>();
        shops.forEach(shop->names.merge(first(shop,"display_name","name"),1L,Long::sum));
        var columns=new ArrayList<String>();
        for(var shop:shops){
            String name=first(shop,"display_name","name");
            if(name.isBlank())name=text(shop.get("id"));
            if(names.getOrDefault(name,0L)>1)name+=" ["+text(shop.get("id"))+"]";
            columns.add(name);
        }
        boolean incomplete=false;
        for(String currency:currencies){
            var selected=fees.stream().filter(row->currency.equals(text(row.get("currency")))).toList();
            var categories=new TreeSet<String>();
            selected.forEach(row->categories.add(text(row.get("category"))));
            var amounts=new HashMap<List<String>,BigDecimal>();
            var counts=new HashMap<List<String>,Long>();
            var missing=new HashSet<List<String>>();
            for(var row:selected){
                var key=List.of(text(row.get("category")),text(row.get("shop_id")));
                counts.merge(key,number(row.get("records")),Long::sum);
                if(number(row.get("known_amounts"))<number(row.get("records"))||decimal(row.get("amount"))==null)missing.add(key);
                BigDecimal value=decimal(row.get("amount"));
                if(value!=null)amounts.merge(key,value,BigDecimal::add);
            }
            incomplete|=!missing.isEmpty();
            var amountRows=new ArrayList<List<String>>();var countRows=new ArrayList<List<String>>();
            var header=new ArrayList<String>();header.add("费用项目（"+currency+"）");header.addAll(columns);header.add("合计");
            amountRows.add(header);var countHeader=new ArrayList<>(header);countHeader.set(0,"费用项目（条）");countRows.add(countHeader);
            var totals=new BigDecimal[shops.size()];Arrays.fill(totals,BigDecimal.ZERO);
            var totalCounts=new long[shops.size()];var missingColumns=new boolean[shops.size()];
            for(String category:categories){
                var amountRow=new ArrayList<String>();var countRow=new ArrayList<String>();
                amountRow.add(label(category));countRow.add(label(category));
                BigDecimal total=BigDecimal.ZERO;long count=0;boolean missingTotal=false;
                for(int i=0;i<shops.size();i++){
                    var key=List.of(category,text(shops.get(i).get("id")));
                    var value=amounts.getOrDefault(key,BigDecimal.ZERO);
                    boolean unknown=missing.contains(key);missingColumns[i]|=unknown;missingTotal|=unknown;
                    amountRow.add(unknown?"待补齐":value.toPlainString());total=total.add(value);totals[i]=totals[i].add(value);
                    long n=counts.getOrDefault(key,0L);countRow.add(Long.toString(n));count+=n;totalCounts[i]+=n;
                }
                amountRow.add(missingTotal?"待补齐":total.toPlainString());countRow.add(Long.toString(count));
                amountRows.add(amountRow);countRows.add(countRow);
            }
            var totalRow=new ArrayList<String>();var countRow=new ArrayList<String>();totalRow.add("平台费用合计");countRow.add("费用记录合计");
            BigDecimal grand=BigDecimal.ZERO;long count=0;boolean missingTotal=false;
            for(int i=0;i<shops.size();i++){
                totalRow.add(missingColumns[i]?"待补齐":totals[i].toPlainString());countRow.add(Long.toString(totalCounts[i]));
                grand=grand.add(totals[i]);count+=totalCounts[i];missingTotal|=missingColumns[i];
            }
            totalRow.add(missingTotal?"待补齐":grand.toPlainString());countRow.add(Long.toString(count));
            amountRows.add(totalRow);countRows.add(countRow);
            var indices=new HashSet<Integer>();for(int i=1;i<header.size();i++)indices.add(i);
            sheets.put("平台费用-"+currency,amountRows);numeric.put("平台费用-"+currency,indices);
            sheets.put("记录数-"+currency,countRows);numeric.put("记录数-"+currency,indices);
        }
        var notes=new ArrayList<List<String>>();
        notes.add(List.of("统计口径","说明"));
        notes.add(List.of("日期范围",filter.start()+" 至 "+filter.end()));
        notes.add(List.of("店铺分组",filter.groupId().isBlank()?"全部分组":"UNGROUPED".equals(filter.groupId())?"未分组":
                rows(facts.get("groups")).stream().filter(g->filter.groupId().equals(g.get("id"))).map(g->text(g.get("name"))).findFirst().orElse("所选分组")));
        notes.add(List.of("店铺范围",filter.shopId().isBlank()?"当前分组与平台下的全部店铺":columns.getFirst()));
        notes.add(List.of("平台",filter.platform().isBlank()?"全部平台":filter.platform()));
        notes.add(List.of("金额口径","平台原币按币种分别列示，不折算人民币，禁止跨币种合计"));
        notes.add(List.of("费用范围","与已结算平台费用构成一致；按平台结算日期，排除刷单关联费用。正数扣减，负数补贴或返还。"));
        notes.add(List.of("记录数","费用记录条数，不等于去重订单数"));
        notes.add(List.of("数据完整性",incomplete?"存在金额未返回的记录，标记待补齐，不提供不完整合计":"0 表示当前已同步记录没有该费用；不代表店铺历史同步全部完成"));
        notes.add(List.of("生成时间",java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString()));
        sheets.put("统计口径",notes);
        return FinanceWorkbook.xlsx(sheets,numeric);
    }
    static String label(String key){
        return switch(key){
            case "commission_fee"->"佣金";case "service_fee"->"服务费";case "seller_transaction_fee"->"支付手续费";
            case "final_shipping_fee"->"物流费用";case "reverse_shipping_fee"->"逆向物流";
            case "order_ams_commission_fee"->"达人佣金";case "campaign_fee"->"活动费用";case "withholding_tax"->"代扣税";
            case "fbs_fee"->"仓储履约";case "seller_order_processing_fee"->"订单处理费";case "pay_per_sale"->"成交广告费";
            default->key.isBlank()?"其他":key;
        };
    }
}
