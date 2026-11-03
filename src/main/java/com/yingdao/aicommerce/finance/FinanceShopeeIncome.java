package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Bounded missing-income checks and the official batch escrow response. */
final class FinanceShopeeIncome {
    private FinanceShopeeIncome(){}

    static Set<String> complete(FinanceRepository repo,Scope scope,String shop,
                                List<Map<String,Object>> income,String zone){
        if(income.isEmpty())return Set.of();
        if(income.size()>30)throw bad("收入补抓页超出批次上限，已保留进度");
        var expected=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:income){
            String id=first(row,"order_sn");
            if(id.isBlank()||expected.put(id,row)!=null)throw bad("收入补抓页订单编号缺失或重复，已保留进度");
            FinanceMarketplace.settlementDay(row.get("actual_payout_time"),zone);
            if(amount(row,"released_amount")==null||!first(row,"currency").matches("[A-Z]{3}"))
                throw bad("收入补抓页金额或币种无效，已保留进度");
        }
        var args=new ArrayList<Object>(List.of(scope.organizationId(),scope.userId(),shop));args.addAll(expected.keySet());
        var found=repo.jdbc().queryForList("""
            SELECT external_id,kind,record_date,currency,amount,revenue FROM finance_records
            WHERE organization_id=? AND user_id=? AND shop_id=? AND platform='SHOPEE' AND source='API'
              AND kind IN ('BILL','SETTLEMENT') AND status='SETTLED' AND external_id IN (
            """+String.join(",",Collections.nCopies(expected.size(),"?"))+")",args.toArray());
        var kinds=new HashMap<String,Set<String>>();
        for(var row:found){
            String id=text(row.get("external_id"));var wanted=expected.get(id);
            if(wanted==null||row.get("revenue")==null||decimal(row.get("amount"))==null)continue;
            if(!text(row.get("currency")).equals(first(wanted,"currency"))
                    ||!text(row.get("record_date")).equals(FinanceMarketplace.settlementDay(wanted.get("actual_payout_time"),zone).toString())
                    ||decimal(row.get("amount")).compareTo(amount(wanted,"released_amount"))!=0)continue;
            kinds.computeIfAbsent(id,k->new HashSet<>()).add(text(row.get("kind")));
        }
        var complete=new HashSet<String>();
        kinds.forEach((id,types)->{if(types.containsAll(Set.of("BILL","SETTLEMENT")))complete.add(id);});
        return complete;
    }

    static Map<String,Map<String,Object>> details(ShopeeOfficialClient client,MarketplaceAccess.Connection connection,
                                                FinanceRepository repo,List<Map<String,Object>> income){
        if(income.isEmpty())return Map.of();
        if(income.size()>30)throw bad("收入明细批次过大，已保留进度");
        var ids=income.stream().map(r->FinanceMarketplace.identifier(first(r,"order_sn"))).distinct().toList();
        var result=new LinkedHashMap<String,Map<String,Object>>();
        // Live API requires a JSON array in a POST body; its documentation incorrectly labels this GET.
        // Official guidance recommends up to 20 per request (hard limit 50).
        for(int start=0;start<ids.size();start+=20){
            var batch=ids.subList(start,Math.min(start+20,ids.size()));
            var response=client.shopPost("/api/v2/payment/get_escrow_detail_batch",connection,
                Map.of("order_sn_list",batch));
            Object raw=response.get("response");
            if(raw==null)raw=response.get("escrow_detail_list");
            if(!(raw instanceof List<?> list)||list.stream().anyMatch(r->!(r instanceof Map<?,?>)))
                throw bad("批量结算响应缺少明细，已保留进度");
            var seen=new HashSet<String>();
            for(var item:rows(raw)){
                var detail=map(item.get("escrow_detail"));String id=first(detail,"order_sn");
                if(!batch.contains(id)||!seen.add(id))throw bad("批量结算订单不匹配或重复，已保留进度");
                if(!first(item,"fail_error").isBlank()||map(detail.get("order_income")).isEmpty())
                    throw bad("平台尚未返回完整的批量结算明细，已保留进度");
                result.put(id,detail);
            }
            if(seen.size()!=batch.size())throw bad("批量结算缺少订单明细，已保留进度");
        }
        return result;
    }
}
