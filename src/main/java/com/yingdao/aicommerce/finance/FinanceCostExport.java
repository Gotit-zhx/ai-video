package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Exports the same deduplicated order scope as the cost panel, using persisted calculation snapshots. */
@Service
public class FinanceCostExport {
    private final FinanceRepository repo;
    private final Semaphore slots=new Semaphore(2);
    static final int MAX_ORDERS=50000,MAX_LINES=200000;
    public FinanceCostExport(FinanceRepository repo){this.repo=repo;}
    static String kind(String value){
        String kind=text(value).toUpperCase(Locale.ROOT);
        if(!Set.of("SETTLED_ORDER","ORDER","REFUND","SETTLEMENT","PENDING","BILL","RECONCILIATION").contains(kind))throw bad("成本来源类型不正确");
        return kind;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=120)
    public byte[] export(Scope scope,Filter filter,String source,String recordId){
        return export(scope,filter,source,recordId,"ALL");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=120)
    public byte[] export(Scope scope,Filter filter,String source,String recordId,String costStatus){
        String selectedStatus=FinanceCostReport.costStatus(costStatus);
        if(!slots.tryAcquire())throw bad("当前有其他成本报表正在生成，请稍后重试");
        try(var book=new FinanceCostWorkbook()){
            String kind=kind(FinanceCostReport.scopeKind(source,!text(recordId).isBlank()));
            FinanceRepository.Query q=text(recordId).isBlank()?repo.query(scope,filter,"r"):
                new FinanceRepository.Query("r.organization_id=? AND r.id=? AND "+FinanceRepository.visible("r"),List.of(scope.organizationId(),recordId));
            String from=" FROM ("+FinanceCostReport.related(kind)+q.where()+") x";
            Long total=repo.jdbc().queryForObject("SELECT COUNT(*)"+from+FinanceCostReport.costStatusWhere(selectedStatus),Long.class,q.args().toArray());
            if(total!=null&&total>MAX_ORDERS)throw bad("订单成本导出最多 50,000 笔订单，请缩小日期或店铺范围");
            var summary=book.sheet("汇总说明",List.of("项目","内容"));
            var orders=book.sheet("订单成本",ORDER_HEADERS);
            var lines=book.sheet("SKU成本明细",LINE_HEADERS);
            var totals=new Totals();int offset=0;
            while(total!=null&&offset<total){
                var args=new ArrayList<>(q.args());args.add(scope.organizationId());args.add(500);args.add(offset);
                var rows=repo.jdbc().queryForList("""
                    SELECT x.order_record_id,x.shop_id,x.reference,o.platform,o.order_id,o.status order_status,
                      o.record_date,o.currency,o.amount,o.raw_json,
                      COALESCE(NULLIF(s.display_name,''),s.name,x.shop_id) shop_name,s.region,s.timezone,
                      g.name group_name,c.status cost_status,c.amount_cny,c.known_amount_cny,c.data_json cost_json,
                      c.version cost_version,c.updated_at cost_updated_at,b.source_name cost_source,
                      j.order_record_id queued_id,
                    """+("SETTLED_ORDER".equals(kind)?"x.settlement_date":"NULL")+" first_settlement_date"+from+"""
                     LEFT JOIN finance_records o ON o.id=x.order_record_id
                     LEFT JOIN finance_connections s ON s.id=x.shop_id AND s.organization_id=?
                     LEFT JOIN finance_groups g ON g.id=s.group_id AND g.organization_id=s.organization_id
                     LEFT JOIN finance_order_costs c ON c.order_record_id=x.order_record_id AND c.organization_id=s.organization_id
                     LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=x.order_record_id AND j.organization_id=s.organization_id
                     LEFT JOIN finance_cost_books b ON b.id=JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.bookId')) AND b.organization_id=s.organization_id
                    """+FinanceCostReport.costStatusWhere(selectedStatus)+" ORDER BY "
                    +("SETTLED_ORDER".equals(kind)?"x.settlement_date":"o.record_date")+" DESC,x.order_record_id,x.shop_id,x.reference LIMIT ? OFFSET ?",args.toArray());
                if(rows.isEmpty())throw bad("导出数据读取不完整，请重试");
                for(var row:rows){
                    append(row,orders,lines,totals);
                    if(totals.lines>MAX_LINES)throw bad("SKU 成本明细超过 200,000 行，请缩小日期或店铺范围");
                }
                offset+=rows.size();
            }
            if(total!=null&&totals.orders!=total)throw bad("导出订单数量不一致，请重试");
            summary.row(List.of("报表",kind.equals("SETTLED_ORDER")?"已结算订单商品成本（人民币）":"关联已结算订单商品成本（人民币）"));
            summary.row(List.of("生成时间（北京时间）",LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
            summary.row(List.of("筛选日期",filter.start()+" 至 "+filter.end()));
            summary.row(List.of("成本状态筛选",selectedStatus.equals("PENDING")?"仅待补齐订单（含订单待同步及后台计算中）":"全部成本状态"));
            summary.row(List.of("日期依据",basis(kind)));
            summary.row(List.of("导出范围",text(recordId).isBlank()?"当前店铺、分组、平台、币种与日期筛选内的全部已结算订单，不受页面分页影响":"所选记录关联的全部已结算订单，不再限制下单日期"));
            summary.row(List.of("店铺",String.join("、",totals.shops)));
            summary.row(List.of("平台筛选",filter.platform().isBlank()?"全部平台":platform(filter.platform())));
            summary.row(List.of("订单原币筛选",filter.currency().isBlank()?"全部原币；原币订单金额不能跨币种相加":filter.currency()));
            summary.row(List.of("订单总数",totals.orders));
            summary.row(List.of("已计算订单（含估算）",totals.ready));
            summary.row(List.of("历史价估算订单",totals.estimated));
            summary.row(List.of("待补齐订单（含计算中）",totals.pending));
            summary.row(List.of("不计成本订单",totals.excluded));
            summary.row(List.of("SKU 明细行数",totals.lines));
            summary.row(Arrays.asList("已结算商品成本合计（人民币）",totals.pending==0?totals.known:null));
            summary.row(List.of("已计算部分成本（人民币）",totals.known));
            summary.row(List.of("订单范围说明",kind.equals("SETTLED_ORDER")?"仅平台已结算订单，按首次结算日期归属；待结算不计入，后续重复结算或调整不重复计整单成本。":kind.equals("ORDER")?"仅核对所选已结算订单，不限制下单日期；不代表当前结算期间的成本合计。":"仅统计所选来源记录关联的已结算去重订单，日期依据为"+basis(kind)+"。"));
            summary.row(List.of("计算口径","商家 SKU 成本单价 × 购买数量 × 组合数量；完整组合装单价优先，成本固定为人民币。"));
            summary.row(List.of("缺失与估算","待补齐的完整成本留空，不按零成本计算。历史价格保留实际取价月份。计算中订单不使用旧成本快照。"));
            summary.row(List.of("排除口径","未付款或已取消订单、已取消商品明细不计商品成本，不受发货状态影响；实际平台费用保留。退款不会自动退回商品成本。商品标准成本不等于仓库实际出库成本。"));
            summary.row(List.of("核对方式","按订单编号连接订单与 SKU 明细；组合单价、数量、月份均取自同一次已保存计算。订单成本合计按去重订单统计。"));
            summary.row(List.of("时间说明","下单时间为店铺时区，平台未提供时留空；下单日期为平台业务日。成本更新时间使用 UTC。"));
            return book.bytes();
        }catch(IOException e){throw new IllegalStateException("成本报表生成失败，请重试",e);}
        finally{slots.release();}
    }
    static final List<String> ORDER_HEADERS=List.of("平台","店铺","订单编号","店铺国家","店铺分组","商品 SKU","下单日期","下单时间（店铺时区）","店铺时区","订单状态","订单状态代码","订单原币","订单金额（原币）","商品成本（人民币）","已计算部分成本（人民币）","成本状态","成本仓","成本月份依据","目标成本月份","实际取价月份","SKU 明细行数","匹配成功行数","历史价行数","成本来源文件","成本版本","成本更新时间（UTC）","说明","首次平台结算日期","订单类型");
    static final List<String> LINE_HEADERS=List.of("平台","店铺","订单编号","店铺国家","店铺分组","下单日期","订单状态","订单成本状态","商品名称","规格名称","商品 SPU 编号","平台 SKU 编号","商家 SKU","商品行编号","成本型号","成本仓","购买数量","组合数量","成本单价（人民币）","商品行成本（人民币）","目标成本月份","实际取价月份","历史价格估算","商品行状态","缺失原因或说明","成本来源文件","成本版本","成本更新时间（UTC）","首次平台结算日期","订单类型");
    static final class Totals{
        long orders,ready,estimated,pending,excluded,lines;BigDecimal known=BigDecimal.ZERO;
        final Set<String> shops=new LinkedHashSet<>();
    }
    void append(Map<String,Object> row,FinanceCostWorkbook.Sheet orders,FinanceCostWorkbook.Sheet detail,Totals totals)throws IOException{
        Map<String,Object> raw=decode(row.get("raw_json"));
        boolean missing=row.get("order_record_id")==null,queued=row.get("queued_id")!=null;
        var cost=queued?Map.<String,Object>of():decode(row.get("cost_json"));
        String code=queued?"QUEUED":missing?"MISSING":text(row.get("cost_status"));
        String status=costStatus(code);
        BigDecimal amount=Set.of("READY","ESTIMATED","EXCLUDED").contains(code)?decimal(row.get("amount_cny")):null;
        BigDecimal known=queued||missing?null:decimal(row.get("known_amount_cny"));
        var costLines=rows(cost.get("lines"));
        String orderId=text(row.get("order_id"));if(orderId.isBlank())orderId=text(row.get("reference"));
        String shop=text(row.get("shop_name")),zone=text(row.get("timezone"));
        if(zone.isBlank())zone=timezoneFor(text(row.get("region")));
        String market=platform(text(row.get("platform")));
        String orderType=FinanceFields.orderType(first(raw,"order_type"),text(raw.get("is_sample_order")));
        Object date=day(row.get("record_date")),updated=timestamp(row.get("cost_updated_at"));
        var skus=new LinkedHashSet<String>();var months=new TreeSet<String>();var issues=new LinkedHashSet<String>();
        for(var line:costLines){if(!text(line.get("sellerSku")).isBlank())skus.add(text(line.get("sellerSku")));if(!text(line.get("priceMonth")).isBlank())months.add(text(line.get("priceMonth")));if(!text(line.get("issue")).isBlank())issues.add(text(line.get("issue")));}
        var sourceLines=rows(raw.get("TIKTOK_SHOP".equals(row.get("platform"))?"line_items":"item_list"));
        if(skus.isEmpty())for(var line:sourceLines){String sku=first(line,"seller_sku","model_sku","item_sku");if(!sku.isBlank())skus.add(sku);}
        String note=queued?"后台正在重新计算，旧成本未计入本次导出":missing?"关联订单尚未同步":text(cost.get("note"));
        if(!issues.isEmpty())note+=(note.isBlank()?"":"；")+String.join("；",issues);
        if(note.isBlank()&&!Set.of("READY","ESTIMATED","EXCLUDED").contains(code))note="成本尚未计算或资料待补齐";
        totals.orders++;totals.shops.add(shop);
        if(code.equals("EXCLUDED"))totals.excluded++;
        else if(Set.of("READY","ESTIMATED").contains(code)){totals.ready++;if(code.equals("ESTIMATED"))totals.estimated++;}
        else totals.pending++;
        if(known!=null)totals.known=totals.known.add(known);
        orders.row(Arrays.asList(market,shop,orderId,row.get("region"),row.get("group_name"),String.join("\n",skus),date,
            created(raw.get("create_time"),zone),zone,orderStatus(text(row.get("order_status"))),row.get("order_status"),
            row.get("currency"),decimal(row.get("amount")),amount,known,status,cost.get("warehouse"),
            cost.isEmpty()?"":"shipment".equals(cost.get("dateBasis"))?"发货月份":"下单月份",cost.get("costMonth"),String.join("、",months),
            cost.get("lineCount"),cost.get("matchedLines"),cost.get("fallbackLines"),queued?null:row.get("cost_source"),
            queued?null:row.get("cost_version"),queued?null:updated,note,day(row.get("first_settlement_date")),orderType));
        // Retain an explanatory row even when the source order or its calculation is unavailable.
        if(costLines.isEmpty()){
            detail.row(Arrays.asList(market,shop,orderId,row.get("region"),row.get("group_name"),date,orderStatus(text(row.get("order_status"))),status,
                null,null,null,null,String.join("\n",skus),null,null,cost.get("warehouse"),null,null,null,code.equals("EXCLUDED")?BigDecimal.ZERO:null,
                cost.get("costMonth"),null,null,status,note,queued?null:row.get("cost_source"),queued?null:row.get("cost_version"),queued?null:updated,day(row.get("first_settlement_date")),orderType));
            totals.lines++;return;
        }
        for(var line:costLines){
            var source=sourceLines.stream().filter(s->!text(line.get("lineId")).isBlank()?text(line.get("lineId")).equals(first(s,"id","line_item_id")):
                text(line.get("productId")).equals(first(s,"product_id","item_id"))&&text(line.get("skuId")).equals(first(s,"sku_id","model_id"))).findFirst().orElse(Map.of());
            boolean excluded=Boolean.TRUE.equals(line.get("excluded"));
            String issue=text(line.get("issue")),lineState=!issue.isBlank()?"待补齐":excluded?"不计成本":Boolean.TRUE.equals(line.get("previousPrice"))?"历史价估算":"已计算";
            detail.row(Arrays.asList(market,shop,orderId,row.get("region"),row.get("group_name"),date,orderStatus(text(row.get("order_status"))),status,
                first(source,"product_name","item_name"),first(source,"sku_name","model_name"),line.get("productId"),line.get("skuId"),line.get("sellerSku"),line.get("lineId"),
                line.get("costCode"),line.get("warehouse"),line.get("quantity"),line.get("factor"),line.get("unitPriceCny"),line.get("amountCny"),
                cost.get("costMonth"),line.get("priceMonth"),Boolean.TRUE.equals(line.get("previousPrice"))?"是":"否",lineState,
                issue.isBlank()?excluded?"未付款或已取消，不计成本":"单价 × 有效购买数量 × 组合数量":issue,row.get("cost_source"),row.get("cost_version"),updated,day(row.get("first_settlement_date")),orderType));
            totals.lines++;
        }
    }
    private Map<String,Object> decode(Object value){return new FinanceOrderCosts(repo).decode(value);}
    static Object day(Object value){try{return value==null?null:LocalDate.parse(text(value).substring(0,10));}catch(RuntimeException e){return null;}}
    static Object timestamp(Object value){return value instanceof java.sql.Timestamp t?t.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime():null;}
    static Object created(Object value,String zone){
        try{
            String t=text(value);if(t.isBlank())return null;
            Instant i=t.matches("\\d{10,13}")?Instant.ofEpochSecond(Long.parseLong(t)/(t.length()>10?1000:1)):Instant.parse(t);
            return i.atZone(ZoneId.of(zone)).toLocalDateTime();
        }catch(RuntimeException e){return null;}
    }
    static String platform(String value){return switch(value){case "TIKTOK_SHOP"->"TikTok Shop";case "SHOPEE"->"虾皮";default->value;};}
    static String basis(String value){return switch(value){case "SETTLED_ORDER"->"首次平台结算日期";case "REFUND"->"退款日期";case "SETTLEMENT"->"结算日期";case "BILL","RECONCILIATION"->"账单日期";case "PENDING"->"待结算日期";default->"下单日期";};}
    static String costStatus(String value){return switch(value){case "READY"->"已计算";case "ESTIMATED"->"历史价估算";case "EXCLUDED"->"不计成本";case "QUEUED"->"后台计算中";case "MISSING"->"订单待同步";default->"待补齐";};}
    static String orderStatus(String value){return switch(value){
        case "UNPAID"->"待付款";case "ON_HOLD"->"订单暂挂";case "CANCELLED","CANCELED"->"已取消";case "IN_CANCEL"->"取消处理中";
        case "COMPLETED"->"已完成";case "DELIVERED"->"已送达";case "AWAITING_SHIPMENT","READY_TO_SHIP"->"待发货";case "AWAITING_COLLECTION"->"待揽收";
        case "PARTIALLY_SHIPPING","PARTIALLY_SHIPPED"->"部分发货";case "IN_TRANSIT"->"运输中";case "PROCESSED"->"已处理";case "RETRY_SHIP"->"待重新发货";
        case "SHIPPED"->"已发货";case "TO_CONFIRM_RECEIVE"->"待确认收货";case "TO_RETURN"->"待退货";default->value;};}
}
