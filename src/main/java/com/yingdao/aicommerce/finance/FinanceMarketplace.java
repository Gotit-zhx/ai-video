package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.infrastructure.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only official connectors. Each step is checkpointed; never performs marketplace writes. */
@Component
public class FinanceMarketplace {
    final FinanceShopAuthorization authorization;final ShopeeOfficialClient shopee;final TikTokShopOfficialClient tiktok;final FinanceRepository repo;
    public FinanceMarketplace(FinanceShopAuthorization authorization,ShopeeOfficialClient shopee,TikTokShopOfficialClient tiktok,FinanceRepository repo){
        this.authorization=authorization;this.shopee=shopee;this.tiktok=tiktok;this.repo=repo;
    }
    public record Step(Map<String,Object> checkpoint,boolean done){}
    public Step step(Scope scope,Map<String,Object> state,Map<String,Object> checkpoint){
        String shopId=text(state.get("shop_id")),channel=text(state.get("channel")),zone=text(state.get("timezone"));
        MarketplaceAccess.Connection c=authorization.connection(scope,shopId);
        if("TIKTOK_SHOP".equals(c.shop().getPlatform()))return tik(scope,c,channel,zone,checkpoint);
        if("SHOPEE".equals(c.shop().getPlatform()))return sho(scope,c,channel,zone,checkpoint);
        throw bad("不支持的财务平台");
    }
    Map<String,Object> tikGet(MarketplaceAccess.Connection c,String path,Map<String,?> query){
        return map(tiktok.apiGet(path,c.tokens().accessToken(),c.shop().getShopCipher(),query).get("data"));
    }
    Step tik(Scope s,MarketplaceAccess.Connection c,String channel,String zone,Map<String,Object> cp){
        Map<String,Object> next=new LinkedHashMap<>(cp);String cursor=text(cp.get("cursor"));
        long from=number(cp.get("from")),to=number(cp.get("to"));String shop=c.shop().getId();
        Map<String,Object> q=new LinkedHashMap<>(Map.of("page_size",20,"sort_order","ASC"));
        if(!cursor.isEmpty())q.put("page_token",cursor);
        Map<String,Object> data;
        if(channel.equals("PENDING")){
            q.put("sort_field","order_create_time");q.put("search_time_ge",from);q.put("search_time_lt",to);
            data=tikGet(c,"/finance/202507/orders/unsettled",q);
            var pendingRows=requiredRows(data,"transactions");
            for(var tx:pendingRows){
                var raw=new LinkedHashMap<>(tx);raw.put("_finance_sync",text(cp.get("run")));
                save(s,c,"PENDING",first(tx,"id"),first(tx,"order_id","adjustment_order_id"),"",first(tx,"type"),"UNSETTLED",
                        date(tx.get("order_create_time"),zone),first(tx,"currency"),amount(tx,"est_settlement_amount"),raw);
            }
            var result=advance(next,data,cursor,"next_page_token");
            long received=number(cp.get("pendingCount"))+pendingRows.size();
            if(result.done()){
                if(data.containsKey("total_count")&&received!=number(data.get("total_count")))throw bad("待结算交易数量不完整，已保留进度等待重试");
                String start=Instant.ofEpochSecond(from).atZone(ZoneId.of(zone)).toLocalDate().toString();
                String end=Instant.ofEpochSecond(to-1).atZone(ZoneId.of(zone)).toLocalDate().toString();
                repo.jdbc().update("DELETE FROM finance_records WHERE organization_id=? AND user_id=? AND shop_id=? AND kind='PENDING' AND source='API'"
                                +" AND record_date>=? AND record_date<=? AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_sync')),'')<>?",
                        s.organizationId(),s.userId(),shop,start,end,text(cp.get("run")));
            }else next.put("pendingCount",received);
            return result;
        }
        if(channel.equals("SETTLEMENT")){
            List<Map<String,Object>> bills=new ArrayList<>(rows(cp.get("bills")));
            if(!bills.isEmpty()){
                var bill=bills.getFirst();String billId=text(bill.get("id"));q.put("sort_field","order_create_time");
                q.remove("page_token");if(!text(cp.get("transactionCursor")).isBlank())q.put("page_token",cp.get("transactionCursor"));
                data=tikGet(c,"/finance/202501/statements/"+identifier(billId)+"/statement_transactions",q);
                LocalDate day=date(bill.get("statement_time"),"UTC");String currency=text(data.get("currency"));
                var transactions=requiredRows(data,"transactions");
                save(s,c,"BILL",billId,"",billId,"结算账单","PARTIAL",day,currency,amount(data,"total_settlement_amount"),data);
                for(var tx:transactions){
                    if("RESERVE".equals(tx.get("type")))continue;
                    String id=first(tx,"id");String order=first(tx,"order_id","adjustment_order_id");
                    save(s,c,"SETTLEMENT",id,order,billId,first(tx,"type"),"SETTLED",day,currency,amount(tx,"settlement_amount"),tx);
                    for(var fee:Map.of("fee_tax_amount","平台费用及税费","shipping_cost_amount","物流费用","adjustment_amount","调整").entrySet())
                        if(amount(tx,fee.getKey())!=null)save(s,c,"FEE",id+":"+fee.getKey(),order,billId,fee.getValue(),"SETTLED",day,currency,amount(tx,fee.getKey()).negate(),Map.of("value",tx.get(fee.getKey()),"basis","平台扣费负数转换为费用正数，返还相反"));
                }
                String token=first(data,"next_page_token");
                long received=number(cp.get("transactionCount"))+transactions.size();
                if(token.isEmpty()){
                    if(data.containsKey("total_count")&&received!=number(data.get("total_count")))throw bad("账单交易数量不完整，已保留进度等待重试");
                    save(s,c,"BILL",billId,"",billId,"结算账单","SETTLED",day,currency,amount(data,"total_settlement_amount"),data);
                    bills.removeFirst();next.remove("transactionCursor");next.remove("transactionCount");next.put("bills",bills);
                }else {if(token.equals(text(cp.get("transactionCursor"))))throw bad("平台分页游标重复");next.put("transactionCursor",token);next.put("transactionCount",received);}
                if(bills.isEmpty()&&Boolean.TRUE.equals(cp.get("lastBillPage")))return new Step(next,true);
                return new Step(next,false);
            }
            q.put("sort_field","statement_time");q.put("statement_time_ge",from);q.put("statement_time_lt",to);
            data=tikGet(c,"/finance/202309/statements",q);bills=requiredRows(data,"statements");
            next.put("bills",bills);String token=first(data,"next_page_token");
            next.put("cursor",token);next.put("lastBillPage",token.isEmpty());
            if(!token.isEmpty()&&token.equals(cursor))throw bad("平台分页游标重复");
            return new Step(next,bills.isEmpty()&&token.isEmpty());
        }
        q.put("sort_field","update_time");
        Map<String,Object> body=Map.of("update_time_ge",from,"update_time_lt",to);
        String path=channel.equals("ORDER")?"/order/202309/orders/search":"/return_refund/202309/returns/search";
        data=map(tiktok.apiPost(path,c,q,body).get("data"));
        String key=channel.equals("ORDER")?"orders":"return_orders";
        for(var row:channel.equals("ORDER")?tikOrderRows(data,cursor):requiredRows(data,key)){
            if(channel.equals("ORDER")){
                String status=first(row,"status");BigDecimal amount=amount(row,"payment.total_amount");
                if(Set.of("UNPAID","CANCELLED").contains(status))amount=null;
                save(s,c,"ORDER",first(row,"id"),first(row,"id"),"","订单",status,date(row.get("create_time"),zone),first(row,"payment.currency"),amount,row);
            }else{
                String status=first(row,"return_status");
                boolean complete=Set.of("RETURN_OR_REFUND_REQUEST_COMPLETE","REPLACEMENT_REQUEST_REFUND_SUCCESS").contains(status);
                save(s,c,"REFUND",first(row,"return_id"),first(row,"order_id"),"",first(row,"return_type"),status,
                        date(row.get("create_time"),zone),first(row,"refund_amount.currency").isBlank()?currencyFor(c.shop().getRegion()):first(row,"refund_amount.currency"),
                        complete?amount(row,"refund_amount.refund_total"):null,row);
            }
        }
        return advance(next,data,cursor,"next_page_token");
    }
    Step sho(Scope s,MarketplaceAccess.Connection c,String channel,String zone,Map<String,Object> cp){
        long from=number(cp.get("from")),to=number(cp.get("to"));String cursor=text(cp.get("cursor"));
        Map<String,Object> q=new LinkedHashMap<>();Map<String,Object> data;Map<String,Object> next=new LinkedHashMap<>(cp);
        if(channel.equals("ORDER")){
            q.putAll(Map.of("time_range_field","update_time","time_from",from,"time_to",to-1,"page_size",20,"cursor",cursor));
            data=map(shopee.shopGet("/api/v2/order/get_order_list",c,q).get("response"));
            List<Map<String,Object>> list=requiredRows(data,"order_list");
            if(!list.isEmpty()){
                String ids=String.join(",",list.stream().map(r->first(r,"order_sn")).toList());
                var detail=map(shopee.shopGet("/api/v2/order/get_order_detail",c,Map.of("order_sn_list",ids,
                        "response_optional_fields","total_amount,item_list,actual_shipping_fee,estimated_shipping_fee,payment_method")).get("response"));
                for(var row:requiredRows(detail,"order_list")){
                    String status=first(row,"order_status");BigDecimal value=Set.of("UNPAID","CANCELLED").contains(status)?null:amount(row,"total_amount");
                    save(s,c,"ORDER",first(row,"order_sn"),first(row,"order_sn"),"","订单",status,date(row.get("create_time"),zone),first(row,"currency"),value,row);
                }
            }
            if(Boolean.TRUE.equals(data.get("more"))&&first(data,"next_cursor").isEmpty())throw bad("订单分页缺少游标");
            return advance(next,data,cursor,"next_cursor");
        }
        if(channel.equals("REFUND")){
            int page=(int)Math.max(1,number(cp.get("page")));
            q.putAll(Map.of("update_time_from",from,"update_time_to",to-1,"page_no",page,"page_size",20));
            data=map(shopee.shopGet("/api/v2/returns/get_return_list",c,q).get("response"));
            for(var row:requiredRows(data,"return")){
                String status=first(row,"status");
                save(s,c,"REFUND",first(row,"return_sn"),first(row,"order_sn"),"","退货退款",status,date(row.get("create_time"),zone),first(row,"currency"),
                        status.equals("CLOSED")?amount(row,"refund_amount"):null,row);
            }
            boolean more=Boolean.TRUE.equals(data.get("more"));next.put("page",page+1);return new Step(next,!more);
        }
        String start=Instant.ofEpochSecond(from).atZone(ZoneId.of(zone)).toLocalDate().toString();
        String end=Instant.ofEpochSecond(to).atZone(ZoneId.of(zone)).toLocalDate().toString();
        if(start.equals(end))end=LocalDate.parse(start).plusDays(1).toString();
        q.putAll(Map.of("date_from",start,"date_to",end,"income_status",1,"page_size",5,"cursor",cursor));
        Map<String,Object> envelope=shopee.shopGet("/api/v2/payment/get_income_detail",c,q);
        data=shopeeIncomePage(envelope);
        for(var row:data.isEmpty()?List.<Map<String,Object>>of():requiredRows(data,"list")){
            String id=first(row,"order_sn"),currency=first(row,"currency");
            if(id.isBlank())throw bad("收入明细缺少订单编号，已保留同步进度");
            LocalDate day=date(row.get("actual_payout_time"),zone);
            save(s,c,"BILL",id,id,id,"订单释放收入","PARTIAL",day,currency,amount(row,"released_amount"),row);
            Map<String,Object> detail=map(shopee.shopGet("/api/v2/payment/get_escrow_detail",c,Map.of("order_sn",id)).get("response"));
            Map<String,Object> income=map(detail.get("order_income"));
            if(income.isEmpty())throw bad("平台尚未返回订单结算拆分");
            save(s,c,"SETTLEMENT",id,id,id,"订单结算","SETTLED",day,currency,amount(income,"escrow_amount_after_adjustment","escrow_amount"),detail);
            for(String fee:List.of("commission_fee","service_fee","seller_transaction_fee","final_shipping_fee","reverse_shipping_fee","order_ams_commission_fee","campaign_fee","withholding_tax","fbs_fee","seller_order_processing_fee","pay_per_sale")){
                BigDecimal value=amount(income,fee);if(value!=null)save(s,c,"FEE",id+":"+fee,id,id,fee,"SETTLED",day,currency,value,Map.of("field",fee,"amount",value));
            }
            save(s,c,"BILL",id,id,id,"订单释放收入","SETTLED",day,currency,amount(row,"released_amount"),row);
        }
        Object pagination=data.get("next_page");
        if(pagination!=null&&!(pagination instanceof Map<?,?>))throw bad("平台收入分页结构异常，已保留同步进度");
        return advance(next,map(pagination),cursor,"cursor");
    }
    static Map<String,Object> shopeeIncomePage(Map<String,Object> envelope){
        // Shopee's documented envelope is income_detail_list; live shop responses also
        // return the same page directly under response, including {} for an empty range.
        Object page=envelope.get("income_detail_list");
        if(page==null){
            Object response=envelope.get("response");
            if(!(response instanceof Map<?,?>))throw bad("平台收入响应缺少数据页，已保留同步进度");
            Map<String,Object> body=map(response);
            page=body.containsKey("income_detail_list")?body.get("income_detail_list"):body;
        }
        if(!(page instanceof Map<?,?>))throw bad("平台收入响应结构异常，已保留同步进度");
        Map<String,Object> result=map(page);
        if(!result.isEmpty()&&!result.containsKey("list"))throw bad("平台收入响应缺少明细列表，已保留同步进度");
        return result;
    }
    static String identifier(String id){if(!id.matches("[A-Za-z0-9_-]{1,128}"))throw bad("平台返回的标识无效");return id;}
    static List<Map<String,Object>> tikOrderRows(Map<String,Object> data,String cursor){
        // Search Orders can issue a token on the last populated page, then omit
        // orders on the terminal page. total_count is the whole search's count,
        // not the number of rows on this page (observed: total_count=133).
        // Accept only an explicit terminal page of an existing pagination chain;
        // a missing body, invalid list or empty first page with a positive total
        // must still fail and retain the checkpoint.
        Object count=data.get("total_count");
        BigDecimal total=decimal(count);
        if(data.get("orders")==null&&!text(cursor).isBlank()
                &&"".equals(data.get("next_page_token"))&&total!=null
                &&total.signum()>=0&&total.stripTrailingZeros().scale()<=0)return List.of();
        return requiredRows(data,"orders");
    }
    static List<Map<String,Object>> requiredRows(Map<String,Object> data,String key){
        Object value=data.get(key);
        // Empty return search omits return_orders. Other envelopes must explicitly declare an empty result.
        BigDecimal total=decimal(data.get("total_count"));
        if(value==null&&(key.equals("return_orders")||total!=null&&total.signum()==0))return List.of();
        if(!(value instanceof List<?>))throw bad("平台财务响应结构异常："+key);
        if(((List<?>)value).stream().anyMatch(row->!(row instanceof Map<?,?>)))throw bad("平台财务记录结构异常："+key);
        return rows(value);
    }
    Step advance(Map<String,Object> next,Map<String,Object> data,String before,String field){
        String token=first(data,field);if(!token.isBlank()&&token.equals(before))throw bad("平台分页游标重复");
        next.put("cursor",token);return new Step(next,token.isEmpty());
    }
    void save(Scope s,MarketplaceAccess.Connection c,String kind,String id,String order,String statement,String category,String status,
              LocalDate day,String currency,BigDecimal amount,Map<String,Object> raw){
        repo.save(s,new Entry(c.shop().getId(),c.shop().getPlatform(),kind,id,order,statement,category,status,day,currency,
                amount,null,null,null,null,"API",raw));
    }
}
