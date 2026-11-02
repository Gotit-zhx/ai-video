package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only, company-scoped upstream evidence. No remote HTTP is performed while browsing. */
@Service
public class FinanceQianyiReturns {
    final FinanceRepository repo;final FinanceService finance;
    private final java.util.concurrent.Semaphore exports=new java.util.concurrent.Semaphore(1);
    public FinanceQianyiReturns(FinanceRepository repo,FinanceService finance){this.repo=repo;this.finance=finance;}
    static final String FROM="""
         FROM finance_qianyi_returns r JOIN finance_connections c
           ON c.organization_id=r.organization_id AND c.id=r.shop_id WHERE
        """;
    static final String COST="""
        (SELECT SUM(k.amount_cny) FROM finance_qianyi_return_costs k
          WHERE k.organization_id=r.organization_id AND k.shop_id=r.shop_id AND k.return_number=r.return_number)
        """;
    static final String REFUND_RAW="JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.orderRefundAmount'))";
    static final String REFUND="CASE WHEN "+REFUND_RAW+" REGEXP '^-?[0-9]{1,17}([.][0-9]{1,6})?$' THEN CAST("+REFUND_RAW+" AS DECIMAL(24,6)) ELSE NULL END";
    static final String CURRENCY="COALESCE(NULLIF(NULLIF(UPPER(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.currency'))),'NULL'),''),'UNKNOWN')";
    static final String BASIS="数据来源：千易售后接口。创建/更新时间按北京时间，入库日期按店铺业务时区。合计覆盖当前筛选全部单据；订单退款金额按接口币种分别汇总，不等同于已付款金额。退款金额仅供售后核对，不再次扣减营业成本或利润。";
    static final String COLUMNS="""
        r.shop_id,r.return_number,r.order_id,r.receipt_date,r.return_state,r.refund_state,r.aftersale_type,
        r.cost_status,r.cost_note,r.source_created_at,r.source_updated_at,r.updated_at,
        COALESCE(NULLIF(c.display_name,''),c.name) shop_name,c.platform,c.region,c.timezone,
        JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.shop')) qianyi_shop,
        JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.orderNumber')) qianyi_order_number,
        JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.warehouse')) warehouse,
        JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.finishTime')) finish_time,
        JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.receiveTime')) receive_time,
        JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.isDeleted')) is_deleted,
        """+REFUND+" refund_amount,"+CURRENCY+" refund_currency ";
    static String dateField(Map<String,String> input){
        String value=input.getOrDefault("dateField","CREATED");
        if(!Set.of("CREATED","UPDATED","RECEIPT").contains(value))throw bad("千易日期口径不正确");
        return value;
    }
    FinanceRepository.Query query(Scope scope,Map<String,String> input){
        var args=new ArrayList<Object>(List.of(scope.organizationId()));
        String where="r.organization_id=? AND c.status<>'REMOVED'";
        String shop=text(input.get("shopId"));
        if(!shop.isBlank())finance.shopScope(scope,shop);
        String group=text(input.get("groupId"));
        if("UNGROUPED".equals(group))where+=" AND (c.group_id IS NULL OR c.group_id='')";
        for(var pair:List.of(new String[]{"r.shop_id",shop},new String[]{"c.group_id","UNGROUPED".equals(group)?"":group},
                new String[]{"c.platform",text(input.get("platform"))},new String[]{"c.currency",text(input.get("currency"))})){
            if(!pair[1].isBlank()){where+=" AND "+pair[0]+"=?";args.add(pair[1]);}
        }
        for(var pair:List.of(new String[]{"r.return_state","returnStatus"},new String[]{"r.refund_state","refundStatus"},
                new String[]{"r.aftersale_type","type"},new String[]{"r.cost_status","costStatus"})){
            String value=text(input.get(pair[1]));
            if(value.equals("NOT_RETURNED")){where+=" AND "+pair[0]+"=''";continue;}
            if(!value.isBlank()){
                if(!value.matches("[A-Z0-9_]{1,64}"))throw bad("千易状态筛选不正确");
                where+=" AND "+pair[0]+"=?";args.add(value);
            }
        }
        String field=dateField(input);
        if(!text(input.get("start")).isBlank()||!text(input.get("end")).isBlank()){
            LocalDate start,end;
            try{start=LocalDate.parse(input.get("start"));end=LocalDate.parse(input.get("end"));}
            catch(RuntimeException invalid){throw bad("请填写完整的千易日期范围");}
            if(start.isAfter(end)||end.isAfter(start.plusYears(5)))throw bad("千易查询日期范围应在五年以内");
            if(field.equals("RECEIPT")){
                where+=" AND r.receipt_date>=? AND r.receipt_date<=?";args.add(start);args.add(end);
            }else{
                String column=field.equals("CREATED")?"r.source_created_at":"r.source_updated_at";
                where+=" AND "+column+">=? AND "+column+"<?";
                // Qianyi query day boundaries use its documented China business timezone.
                var zone=ZoneId.of("Asia/Shanghai");
                args.add(start.atStartOfDay(zone).toInstant().toEpochMilli());
                args.add(end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
            }
        }
        var ids=FinanceRecordSearch.numbers(input.get("numbers"));
        if(!ids.isEmpty()){
            String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
            where+=" AND (r.order_id IN ("+slots+") OR r.return_number IN ("+slots+"))";
            args.addAll(ids);args.addAll(ids);
        }
        return new FinanceRepository.Query(where,args);
    }
    public Map<String,Object> list(Scope scope,Map<String,String> input){
        var query=query(scope,input);int page=(int)Math.max(1,Math.min(10000,number(input.getOrDefault("page","1"))));
        var args=new ArrayList<>(query.args());args.add(50);args.add(50L*(page-1));
        String sort=switch(dateField(input)){case "RECEIPT"->"r.receipt_date";case "UPDATED"->"r.source_updated_at";default->"r.source_created_at";};
        return repo.readSnapshot(()->{
            var summary=summary(query);long total=number(summary.get("records"));
            var items=repo.jdbc().queryForList("SELECT "+COLUMNS+FROM+query.where()+" ORDER BY "+sort+" DESC,r.shop_id,r.return_number LIMIT ? OFFSET ?",args.toArray());
            return Map.of("items",items,"total",total,"page",page,"pageSize",50,"source","QIANYI","dateField",dateField(input),
                "summary",summary,"basis",BASIS);
        });
    }
    Map<String,Object> summary(FinanceRepository.Query query){
        var totals=new LinkedHashMap<>(repo.jdbc().queryForMap("SELECT COUNT(*) records"+FROM+query.where(),query.args().toArray()));
        totals.put("refundTotals",repo.jdbc().queryForList("SELECT "+CURRENCY+" currency,COUNT(*) records,COUNT("+REFUND+
            ") known_count,CASE WHEN MIN("+CURRENCY+")<>'UNKNOWN' THEN SUM("+REFUND+") ELSE NULL END amount"+
            FROM+query.where()+" GROUP BY "+CURRENCY+" ORDER BY currency",query.args().toArray()));
        return totals;
    }
    public byte[] export(Scope scope,Map<String,String> input){
        var query=query(scope,input);
        if(!exports.tryAcquire())throw bad("千易报表正在生成，请稍后重试");
        try{
            return repo.readSnapshot(()->{
                var totals=summary(query);long count=number(totals.get("records"));
                if(count>50000)throw bad("千易售后导出最多 50,000 条，请缩小日期或店铺范围");
                try(var book=new FinanceCostWorkbook()){
                    var notes=book.sheet("统计口径",List.of("项目","内容"));
                    notes.row(List.of("来源与口径",BASIS));
                    for(var field:List.of("start","end","dateField","shopId","groupId","platform","type","returnStatus","refundStatus","costStatus","numbers"))
                        notes.row(List.of(field,input.getOrDefault(field,"")));
                    notes.row(List.of("筛选单据数",count));
                    for(var money:rows(totals.get("refundTotals")))
                        notes.row(Arrays.asList("千易订单退款金额（"+money.get("currency")+"）",money.get("amount")));
                    var detail=book.sheet("千易售后明细",List.of("平台","店铺","千易售后单号","平台订单号","售后类型","退货状态","退款状态",
                        "千易创建时间（北京时间）","千易更新时间（北京时间）","退回入库日期（店铺时区）","退款金额","退款币种"));
                    for(int offset=0;offset<count;offset+=500){
                        var args=new ArrayList<>(query.args());args.add(500);args.add(offset);
                        var items=repo.jdbc().queryForList("SELECT "+COLUMNS+FROM+query.where()+
                            " ORDER BY r.source_created_at DESC,r.shop_id,r.return_number LIMIT ? OFFSET ?",args.toArray());
                        for(var row:items)detail.row(Arrays.asList(
                            "SHOPEE".equals(row.get("platform"))?"虾皮":"TikTok",row.get("shop_name"),row.get("return_number"),row.get("order_id"),
                            label(text(row.get("aftersale_type"))),label(text(row.get("return_state"))),label(text(row.get("refund_state"))),
                            sourceTime(row.get("source_created_at")),sourceTime(row.get("source_updated_at")),row.get("receipt_date"),
                            row.get("refund_amount"),row.get("refund_currency")));
                    }
                    return book.bytes();
                }catch(java.io.IOException failure){throw bad("千易报表生成失败，请重试");}
            });
        }finally{exports.release();}
    }
    static Object sourceTime(Object value){return value==null||number(value)<=0?null:LocalDateTime.ofInstant(Instant.ofEpochMilli(number(value)),ZoneId.of("Asia/Shanghai"));}
    static String label(String value){return switch(value){
        case "SALES_RETURN"->"退货单";case "RETURN"->"退款单";case "RESHIP"->"补发货单";
        case "NEW"->"待提交";case "WAIT_AUDIT"->"待审核";case "WAIT_BUYER_RETURN"->"待买家退回";
        case "WAIT_STORAGE"->"待收货";case "RECEIVING"->"部分收货";case "FINISH"->"已完成";case "CLOSE"->"已关闭";
        case "NO_NEED_RETURN"->"无需退货";case "WAIT_PAYMENT"->"待退款";case "REFUSE_REFUND"->"拒绝退款";case "PAY_FAILED"->"退款失败";
        case "READY"->"成本已确认";case "PENDING"->"待匹配 / 待补齐";case "WAIT_COST_BASIS"->"成本口径待确认";
        case "WAIT_RECEIPT"->"等待退回入库";case "NO_RETURN"->"无需入库冲回";case "CLOSED"->"已关闭";
        default->value.isBlank()?"千易未返回":value;};}
    public Map<String,Object> detail(Scope scope,String shop,String number){
        finance.shopScope(scope,shop);
        return repo.readSnapshot(()->{
            var found=repo.jdbc().queryForList("SELECT "+COLUMNS+",r.data_json"+FROM+"r.organization_id=? AND r.shop_id=? AND r.return_number=? AND c.status<>'REMOVED'",
                scope.organizationId(),shop,number);
            if(found.isEmpty())throw bad("没有找到本公司的千易售后单");
            var result=new LinkedHashMap<>(found.getFirst());
            result.put("source","QIANYI");result.put("sourceFields",FinanceQianyiEvidence.sanitize(repo.read(text(result.remove("data_json")))));
            result.put("basis",BASIS);
            return result;
        });
    }
}
