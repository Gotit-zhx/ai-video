package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.zip.*;
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
    private final Limits limits;
    record Limits(int orders,int lines,long totalOrders,long bytes) {
        Limits { if(orders<1||lines<1||totalOrders<1||bytes<1)throw new IllegalArgumentException("Invalid export limits"); }
    }
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceCostExport(FinanceRepository repo){this(repo,new Limits(MAX_ORDERS,MAX_LINES,1_000_000,128L*1024*1024));}
    FinanceCostExport(FinanceRepository repo,Limits limits){this.repo=repo;this.limits=limits;}
    public record GeneratedFile(Path path,String filename,String contentType,long orders,long lines,int parts) {}
    static String kind(String value){
        String kind=text(value).toUpperCase(Locale.ROOT);
        if(!Set.of("SETTLED_ORDER","SAMPLE_SETTLED","ORDER","REFUND","REFUND_SETTLED","SETTLEMENT","PENDING","BILL","RECONCILIATION").contains(kind))throw bad("成本来源类型不正确");
        return kind;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=120)
    public byte[] export(Scope scope,Filter filter,String source,String recordId){
        return export(scope,filter,source,recordId,"ALL");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=120)
    public byte[] export(Scope scope,Filter filter,String source,String recordId,String costStatus){return export(scope,filter,source,recordId,costStatus,"");}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=120)
    public byte[] export(Scope scope,Filter filter,String source,String recordId,String costStatus,String numbers){
        String selectedStatus=FinanceCostReport.costStatus(costStatus);
        if(!slots.tryAcquire())throw bad("当前有其他成本报表正在生成，请稍后重试");
        try(var book=new FinanceCostWorkbook()){
            String kind=kind(FinanceCostReport.scopeKind(source,!text(recordId).isBlank()));
            FinanceRepository.Query q=text(recordId).isBlank()?repo.query(scope,filter,"r"):
                new FinanceRepository.Query("r.organization_id=? AND r.id=? AND "+FinanceRepository.visible("r"),List.of(scope.organizationId(),recordId));
            q=FinanceRecordSearch.orders(q,Set.of("BILL","RECONCILIATION").contains(kind)?"o.order_id":"r.order_id",numbers);
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
                      CASE WHEN j.priority<2 THEN j.order_record_id END queued_id,
                      lc.amount_cny invoice_label_amount,lc.pending_count invoice_label_pending,lc.shipment_count invoice_label_shipments,
                    """+(Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED").contains(kind)?"x.settlement_date":"NULL")+" first_settlement_date"+from+"""
                     LEFT JOIN finance_records o ON o.id=x.order_record_id
                     LEFT JOIN finance_connections s ON s.id=x.shop_id AND s.organization_id=?
                     LEFT JOIN finance_groups g ON g.id=s.group_id AND g.organization_id=s.organization_id
                     LEFT JOIN finance_order_costs c ON c.order_record_id=x.order_record_id AND c.organization_id=s.organization_id
                     LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=x.order_record_id AND j.organization_id=s.organization_id
                     LEFT JOIN finance_cost_books b ON b.id=JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.bookId')) AND b.organization_id=s.organization_id
                    """+FinanceLabelCosts.join("x.organization_id","x.shop_id","x.reference")+FinanceCostReport.costStatusWhere(selectedStatus)+" ORDER BY "
                    +(Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED").contains(kind)?"x.settlement_date":"o.record_date")+" DESC,x.order_record_id,x.shop_id,x.reference LIMIT ? OFFSET ?",args.toArray());
                if(rows.isEmpty())throw bad("导出数据读取不完整，请重试");
                new FinanceCostReport(repo).attachSettlementAmounts(scope,rows);
                for(var row:rows){
                    append(row,orders,lines,totals);
                    if(totals.lines>MAX_LINES)throw bad("SKU 成本明细超过 200,000 行，请缩小日期或店铺范围");
                }
                offset+=rows.size();
            }
            if(total!=null&&totals.orders!=total)throw bad("导出订单数量不一致，请重试");
            writeSummary(summary,filter,kind,recordId,selectedStatus,numbers,totals);
            if(text(recordId).isBlank()&&kind.equals("SETTLED_ORDER")){
                var credits=new FinanceActualCosts(repo).returns(scope,filter,numbers);
                var returned=book.sheet("退货成本冲回",List.of("店铺","订单编号","退回入库日期","商品型号","数量","冲回成本（人民币）","数据来源","千易库存流水编号","千易售后单号"));
                BigDecimal totalReturn=BigDecimal.ZERO;
                for(var r:credits){var evidence=map(r.get("evidence"));totalReturn=totalReturn.add(decimal(r.get("amount_cny")));
                    returned.row(Arrays.asList(r.get("shop_name"),r.get("order_id"),day(r.get("receipt_date")),evidence.get("sku"),evidence.get("quantity"),r.get("amount_cny"),r.get("source_name"),evidence.get("movementId"),evidence.get("receiptId")));
                }
                summary.row(List.of("已确认退货入库成本冲回（人民币）",totalReturn));
                summary.row(List.of("退货冲回口径","仅千易已确认库存流水成本，按实际退回入库日期统计并去重；旧 Excel 退货成本不计入。未确认部分不包含在本小计，零小计不代表全部售后成本为零，请在千易售后明细核对状态。"));
            }
            return book.bytes();
        }catch(IOException e){throw new IllegalStateException("成本报表生成失败，请重试",e);}
        finally{slots.release();}
    }
    private void writeSummary(FinanceCostWorkbook.Sheet summary,Filter filter,String kind,String recordId,String selectedStatus,String numbers,Totals totals)throws IOException{
            summary.row(List.of("报表",kind.equals("SETTLED_ORDER")?"已结算订单商品成本（人民币）":"关联已结算订单商品成本（人民币）"));
            summary.row(List.of("生成时间（北京时间）",LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
            summary.row(List.of("筛选日期",filter.start()+" 至 "+filter.end()));
            summary.row(List.of("订单编号查询",String.join("、",FinanceRecordSearch.numbers(numbers))));
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
            summary.row(List.of("实际出库成本订单",totals.actual));
            summary.row(List.of("SKU 明细行数",totals.lines));
            summary.row(Arrays.asList("已结算商品成本合计（人民币）",totals.pending==0?totals.known:null));
            summary.row(List.of("已计算部分成本（人民币）",totals.known));
            summary.row(List.of("已匹配贴单金额（人民币）",totals.labels));
            summary.row(List.of("贴单账单待补齐订单",totals.labelMissing));
            summary.row(List.of("贴单金额冲突订单",totals.labelConflicts));
            summary.row(List.of("贴单金额口径","仓库账单操作费，人民币原值。按参考号匹配订单，同一 WMS 出库单去重；按订单首次结算日期归属，不再使用分组或国家单价。"));
            summary.row(List.of("订单范围说明",kind.equals("SETTLED_ORDER")?"仅平台已结算订单，按首次结算日期归属；待结算不计入，后续重复结算或调整不重复计整单成本。":kind.equals("ORDER")?"仅核对所选已结算订单，不限制下单日期；不代表当前结算期间的成本合计。":"仅统计所选来源记录关联的已结算去重订单，日期依据为"+basis(kind)+"。"));
            summary.row(List.of("计算口径","实际出库凭据优先：使用 ERP 出库型号、数量及人民币成本。无实际凭据时，使用商家 SKU 标准成本单价 × 购买数量 × 组合数量。"));
            summary.row(List.of("缺失与估算","待补齐的完整成本留空，不按零成本计算。历史价格保留实际取价月份。计算中订单不使用旧成本快照。"));
            summary.row(List.of("刷单口径","刷单订单不计入本报表的结算商品成本；原始财务明细保留黄色标签对应的订单标记。"));
            summary.row(List.of("排除口径","有实际出库凭据的订单保留成本，包括出库后取消；无实际凭据的取消单仍不计标准成本。确认退回入库的商品单独冲回，退款金额不等于入库成本。"));
            summary.row(List.of("核对方式","按订单编号连接订单与 SKU 明细；组合单价、数量、月份均取自同一次已保存计算。订单成本合计按去重订单统计。"));
            summary.row(List.of("月份校正",FinanceReportingMonths.NOTE));
            summary.row(List.of("时间说明","结算归属日期为期间筛选依据；已确认月份显示“按月校正”，不推测具体完成日。下单日期和下单时间仅供核对，不用于期间筛选；下单时间为店铺时区，成本更新时间使用 UTC。"));
            summary.row(List.of("商品金额口径","销售收入与平台净结算取首次结算账单日期的平台结算字段；缺失时留空，不以原订单金额代替。后续跨日调整请查结算明细。"));
            summary.row(List.of("成本分类","营业成本 = 商品成本 + 贴单成本 − 已确认退货入库成本。样品和退款关联商品成本属于子项，不重复扣减。"));
    }
    /** One transaction covers both key selection and every persisted calculation read. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=600)
    public GeneratedFile exportFile(Scope company,Filter filter,String source,String recordId,String costStatus,String numbers,
                                    Path directory,BiConsumer<Long,Long> progress){
        String selectedStatus=FinanceCostReport.costStatus(costStatus);
        String selectedKind=kind(FinanceCostReport.scopeKind(source,!text(recordId).isBlank()));
        if(!slots.tryAcquire())throw bad("当前有其他成本报表正在生成，请稍后重试");
        var created=new ArrayList<Path>();
        var parts=new ArrayList<FilePart>();
        Part current=null;
        long deadline=System.nanoTime()+Duration.ofMinutes(10).toNanos();
        try{
            Files.createDirectories(directory);
            FinanceRepository.Query q=text(recordId).isBlank()?repo.query(company,filter,"r"):
                new FinanceRepository.Query("r.organization_id=? AND r.id=? AND "+FinanceRepository.visible("r"),List.of(company.organizationId(),recordId));
            q=FinanceRecordSearch.orders(q,Set.of("BILL","RECONCILIATION").contains(selectedKind)?"o.order_id":"r.order_id",numbers);
            String from=" FROM ("+FinanceCostReport.related(selectedKind)+q.where()+") x";
            long total=Objects.requireNonNullElse(repo.jdbc().queryForObject("SELECT COUNT(*)"+from+FinanceCostReport.costStatusWhere(selectedStatus),Long.class,q.args().toArray()),0L);
            if(total>limits.totalOrders())throw bad("成本导出超过 1,000,000 笔订单，请联系管理员安排批量导出");
            progress.accept(0L,total);
            long processed=0,lines=0,outputBytes=0;
            Map<String,Object> cursor=null;
            current=new Part(directory);
            while(processed<total){
                checkTime(deadline);
                var keys=keys(q,from,selectedKind,selectedStatus,cursor);
                if(keys.isEmpty())throw bad("导出数据读取不完整，请重试");
                var page=details(company,keys);
                if(page.size()!=keys.size())throw bad("导出订单关联数据数量不一致，请重试");
                new FinanceCostReport(repo).attachSettlementAmounts(company,page);
                for(var row:page){
                    checkTime(deadline);
                    var orderRows=new ArrayList<List<?>>();var detailRows=new ArrayList<List<?>>();var stats=new Totals();
                    append(row,orderRows::add,values->{
                        if(detailRows.size()>=limits.lines())throw bad("单笔订单 SKU 明细超过单文件上限，无法完整分片，请联系管理员核对该订单");
                        detailRows.add(values);
                    },stats);
                    if(current.totals.orders>0&&(current.totals.orders>=limits.orders()||current.totals.lines+stats.lines>limits.lines())){
                        FilePart part=finishPart(current,company,filter,selectedKind,recordId,selectedStatus,numbers,directory,parts.size()+1,true,limits.bytes()-outputBytes,created,deadline);
                        parts.add(part);outputBytes+=part.bytes();current.close();current=new Part(directory);
                    }
                    for(var values:orderRows)current.orders.row(values);
                    for(var values:detailRows)current.details.row(values);
                    merge(current.totals,stats);processed++;lines+=stats.lines;
                }
                cursor=keys.getLast();
                progress.accept(processed,total);
            }
            if(processed!=total)throw bad("导出订单数量不一致，请重试");
            FilePart part=finishPart(current,company,filter,selectedKind,recordId,selectedStatus,numbers,directory,parts.size()+1,!parts.isEmpty(),limits.bytes()-outputBytes,created,deadline);
            parts.add(part);current.close();current=null;
            checkTime(deadline);
            String basename="订单成本_"+filter.start()+"_"+filter.end();
            if(parts.size()==1)return new GeneratedFile(part.path(),basename+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",processed,lines,1);
            Path archive=Files.createTempFile(directory,"cost-export-",".zip");created.add(archive);
            try(var zip=new ZipOutputStream(bounded(Files.newOutputStream(archive),limits.bytes(),deadline))){
                StringBuilder manifest=new StringBuilder("订单成本分片导出\n公司：").append(company.organizationId()).append("\n日期：").append(filter.start()).append(" 至 ").append(filter.end())
                    .append("\n来源：").append(selectedKind).append("\n来源记录：").append(text(recordId)).append("\n店铺：").append(filter.shopId()).append("\n分组：").append(filter.groupId())
                    .append("\n平台：").append(filter.platform()).append("\n订单原币筛选：").append(filter.currency()).append("\n成本状态：").append(selectedStatus).append("\n订单编号：").append(String.join("、",FinanceRecordSearch.numbers(numbers)))
                    .append("\n订单总数：").append(processed).append("\nSKU 明细总行数：").append(lines)
                    .append("\n同一只读数据库快照；各分片订单互不重复，订单与其全部 SKU 在同一文件。原币金额不跨币种累计。")
                    .append("\n期间退货成本冲回参考仅在第 1 片出现一次，覆盖整个筛选期间，不能作为第 1 片订单的专属冲回金额。\n\n文件\t订单数\tSKU 明细行数\n");
                for(var file:parts){
                    checkTime(deadline);String name=String.format(Locale.ROOT,"订单成本_%03d.xlsx",file.index());
                    zip.putNextEntry(new ZipEntry(name));Files.copy(file.path(),zip);zip.closeEntry();
                    manifest.append(name).append('\t').append(file.orders()).append('\t').append(file.lines()).append('\n');
                    Files.delete(file.path());
                }
                zip.putNextEntry(new ZipEntry("导出清单.txt"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            return new GeneratedFile(archive,basename+".zip","application/zip",processed,lines,parts.size());
        }catch(IOException e){
            cleanup(created);throw new IllegalStateException("成本报表文件生成失败，请重试",e);
        }catch(RuntimeException|Error e){cleanup(created);throw e;}
        finally{
            if(current!=null)try{current.close();}catch(IOException ignored){}
            slots.release();
        }
    }
    private List<Map<String,Object>> keys(FinanceRepository.Query q,String from,String kind,String status,Map<String,Object> cursor){
        boolean settled=Set.of("SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED").contains(kind);
        String date=settled?"x.settlement_date":"sort_order.record_date";
        String dateKey="COALESCE("+date+",'1000-01-01')",idKey="COALESCE(x.order_record_id,'')";
        String statusWhere=FinanceCostReport.costStatusWhere(status);
        var args=new ArrayList<>(q.args());String after="";
        if(cursor!=null){
            Object day=cursor.get("export_sort_date");String id=text(cursor.get("order_record_id")),shop=text(cursor.get("shop_id")),reference=text(cursor.get("reference"));
            after=(statusWhere.isBlank()?" WHERE ":" AND ")+"("+dateKey+"<? OR ("+dateKey+"=? AND ("+idKey+">? OR ("+idKey+"=? AND (x.shop_id>? OR (x.shop_id=? AND x.reference>?))))))";
            args.addAll(Arrays.asList(day,day,id,id,shop,shop,reference));
        }
        return repo.jdbc().queryForList("SELECT x.order_record_id,x.organization_id,x.shop_id,x.reference,"+dateKey+" export_sort_date,"+(settled?"x.settlement_date":"NULL")+" first_settlement_date"+from
            +(settled?"":" LEFT JOIN finance_records sort_order ON sort_order.id=x.order_record_id AND sort_order.organization_id=x.organization_id")+statusWhere+after
            +" ORDER BY "+dateKey+" DESC,"+idKey+",x.shop_id,x.reference LIMIT 500",args.toArray());
    }
    private List<Map<String,Object>> details(Scope company,List<Map<String,Object>> keys){
        var selects=new ArrayList<String>();var args=new ArrayList<Object>();int position=0;
        for(var key:keys){
            selects.add("SELECT ? order_record_id,? organization_id,? shop_id,? reference,? first_settlement_date,? page_position");
            args.addAll(Arrays.asList(key.get("order_record_id"),company.organizationId(),key.get("shop_id"),key.get("reference"),key.get("first_settlement_date"),position++));
        }
        var rows=new ArrayList<>(repo.jdbc().queryForList("""
            SELECT x.page_position,x.order_record_id,x.shop_id,x.reference,o.platform,o.order_id,o.status order_status,
              o.record_date,o.currency,o.amount,o.raw_json,
              COALESCE(NULLIF(s.display_name,''),s.name,x.shop_id) shop_name,s.region,s.timezone,
              g.name group_name,c.status cost_status,c.amount_cny,c.known_amount_cny,c.data_json cost_json,
              c.version cost_version,c.updated_at cost_updated_at,b.source_name cost_source,
              CASE WHEN j.priority<2 THEN j.order_record_id END queued_id,
              lc.amount_cny invoice_label_amount,lc.pending_count invoice_label_pending,lc.shipment_count invoice_label_shipments,
              x.first_settlement_date
            FROM (
            """+String.join(" UNION ALL ",selects)+"""
            ) x
            LEFT JOIN finance_records o ON o.id=x.order_record_id AND o.organization_id=x.organization_id
            LEFT JOIN finance_connections s ON s.id=x.shop_id AND s.organization_id=x.organization_id
            LEFT JOIN finance_groups g ON g.id=s.group_id AND g.organization_id=s.organization_id
            LEFT JOIN finance_order_costs c ON c.order_record_id=x.order_record_id AND c.organization_id=x.organization_id
            LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=x.order_record_id AND j.organization_id=x.organization_id
            LEFT JOIN finance_cost_books b ON b.id=JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.bookId')) AND b.organization_id=x.organization_id
            """+FinanceLabelCosts.join("x.organization_id","x.shop_id","x.reference") ,args.toArray()));
        rows.sort(Comparator.comparingLong(row->number(row.get("page_position"))));
        return rows;
    }
    private FilePart finishPart(Part part,Scope company,Filter filter,String kind,String recordId,String status,String numbers,
                                Path directory,int index,boolean multiple,long remaining,List<Path> created,long deadline)throws IOException{
        writeSummary(part.summary,filter,kind,recordId,status,numbers,part.totals);
        part.summary.row(List.of("公司范围",company.organizationId()));
        part.summary.row(List.of("来源上下文",kind+" / "+text(recordId)));
        part.summary.row(List.of("店铺筛选",filter.shopId().isBlank()?"全部店铺":filter.shopId()));
        part.summary.row(List.of("分组筛选",filter.groupId().isBlank()?"全部分组":filter.groupId()));
        part.summary.row(List.of("分片",multiple?"第 "+index+" 片；本文件汇总仅含本片订单，总范围及分片数量见 ZIP 导出清单":"单文件完整导出"));
        if(text(recordId).isBlank()&&kind.equals("SETTLED_ORDER")){
            if(index==1)writeReturns(part,company,filter,numbers,deadline);
            if(multiple)part.summary.row(List.of("期间退货参考附表","整个筛选期间的退货参考只在第 1 片列出一次；不按分片分摊，不重复累计，不表示第 1 片订单的专属冲回金额。"));
        }
        Path path=Files.createTempFile(directory,"cost-part-",".xlsx");created.add(path);
        try(var output=bounded(Files.newOutputStream(path),remaining,deadline)){part.book.write(output);}
        return new FilePart(path,index,part.totals.orders,part.totals.lines,Files.size(path));
    }
    private void writeReturns(Part part,Scope company,Filter filter,String numbers,long deadline)throws IOException{
        var q=new FinanceActualCosts(repo).receiptQuery(company,filter,numbers);
        var sheet=part.book.sheet("退货成本冲回",List.of("店铺","订单编号","退回入库日期","商品型号","数量","冲回成本（人民币）","数据来源","千易库存流水编号","千易售后单号"));
        BigDecimal amount=BigDecimal.ZERO;String cursor=null;
        while(true){
            checkTime(deadline);var args=new ArrayList<>(q.args());if(cursor!=null)args.add(cursor);
            var page=repo.jdbc().queryForList("SELECT r.id,r.shop_id,r.order_id,r.receipt_date,r.amount_cny,r.source_name,r.data_json,COALESCE(NULLIF(c.display_name,''),c.name) shop_name"
                +FinanceActualCosts.QIANYI_RECEIPTS+q.where()+(cursor==null?"":" AND r.id>?")+" ORDER BY r.id LIMIT 500",args.toArray());
            if(page.isEmpty())break;
            for(var row:page){var evidence=decode(row.get("data_json"));amount=amount.add(decimal(row.get("amount_cny")));
                sheet.row(Arrays.asList(row.get("shop_name"),row.get("order_id"),day(row.get("receipt_date")),evidence.get("sku"),evidence.get("quantity"),row.get("amount_cny"),row.get("source_name"),evidence.get("movementId"),evidence.get("receiptId")));}
            cursor=text(page.getLast().get("id"));
        }
        part.summary.row(List.of("已确认退货入库成本冲回（人民币）",amount));
        part.summary.row(List.of("退货冲回口径","仅千易已确认库存流水成本，按实际退回入库日期统计并去重；旧 Excel 退货成本不计入。未确认部分不包含在本小计，零小计不代表全部售后成本为零，请在千易售后明细核对状态。"));
    }
    private static void checkTime(long deadline){
        if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw bad("成本报表生成超时或已停止，请稍后重试");
    }
    private static OutputStream bounded(OutputStream output,long maximum,long deadline){
        return new FilterOutputStream(output){
            long count;
            private void count(int length){checkTime(deadline);if(count>maximum-length)throw bad("成本报表文件总大小超过 128 MiB，未生成部分报表，请联系管理员安排导出");count+=length;}
            @Override public void write(int b)throws IOException{count(1);out.write(b);}
            @Override public void write(byte[] b,int offset,int length)throws IOException{count(length);out.write(b,offset,length);}
        };
    }
    private static void cleanup(List<Path> paths){for(var path:paths)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
    private static void merge(Totals into,Totals value){
        into.orders+=value.orders;into.lines+=value.lines;into.ready+=value.ready;into.actual+=value.actual;into.estimated+=value.estimated;
        into.pending+=value.pending;into.excluded+=value.excluded;into.labelMissing+=value.labelMissing;into.labelConflicts+=value.labelConflicts;
        into.known=into.known.add(value.known);into.labels=into.labels.add(value.labels);into.shops.addAll(value.shops);
    }
    private record FilePart(Path path,int index,long orders,long lines,long bytes){}
    private static final class Part implements AutoCloseable{
        final FinanceCostWorkbook book;final FinanceCostWorkbook.Sheet summary,orders,details;final Totals totals=new Totals();
        Part(Path directory)throws IOException{
            book=new FinanceCostWorkbook(directory);
            try{summary=book.sheet("汇总说明",List.of("项目","内容"));orders=book.sheet("订单成本",ORDER_HEADERS);details=book.sheet("SKU成本明细",LINE_HEADERS);}
            catch(IOException|RuntimeException e){book.close();throw e;}
        }
        @Override public void close()throws IOException{book.close();}
    }
    static final List<String> ORDER_HEADERS=List.of("平台","店铺","订单编号","店铺国家","店铺分组","商品 SKU","结算归属日期","下单时间（店铺时区）","店铺时区","订单状态","订单状态代码","结算原币","销售收入（结算原币）","商品成本（人民币）","已计算部分成本（人民币）","成本状态","成本仓","成本月份依据","目标成本月份","实际取价月份","SKU 明细行数","匹配成功行数","历史价行数","成本来源文件","成本版本","成本更新时间（UTC）","说明","下单日期（核对用）","订单类型","贴单成本（人民币）","贴单口径","平台净结算（结算原币）","原订单币种（核对用）","原订单金额（核对用）","商品金额口径","校正归属月份","平台原始结算日期","归属校正来源","成本计价依据");
    static final List<String> LINE_HEADERS=List.of("平台","店铺","订单编号","店铺国家","店铺分组","结算归属日期","订单状态","订单成本状态","商品名称","规格名称","商品 SPU 编号","平台 SKU 编号","商家 SKU","商品行编号","成本型号","成本仓","购买或实际出库数量","组合数量","成本单价（人民币）","商品行成本（人民币）","目标成本月份","实际取价月份","历史价格估算","商品行状态","缺失原因或说明","成本来源文件","成本版本","成本更新时间（UTC）","下单日期（核对用）","订单类型","校正归属月份","平台原始结算日期","归属校正来源","成本计价依据","出库单号","来源行");
    static final class Totals{
        long orders,ready,actual,estimated,pending,excluded,lines,labelMissing,labelConflicts;BigDecimal known=BigDecimal.ZERO,labels=BigDecimal.ZERO;
        final Set<String> shops=new LinkedHashSet<>();
    }
    void append(Map<String,Object> row,FinanceCostWorkbook.RowSink orders,FinanceCostWorkbook.RowSink detail,Totals totals)throws IOException{
        Map<String,Object> raw=decode(row.get("raw_json"));
        boolean missing=row.get("order_record_id")==null,queued=row.get("queued_id")!=null;
        var cost=queued?Map.<String,Object>of():decode(row.get("cost_json"));
        var label=new LinkedHashMap<String,Object>();label.put("amount_cny",row.get("invoice_label_amount"));
        label.put("pending_count",row.get("invoice_label_pending"));label.put("shipment_count",row.get("invoice_label_shipments"));
        var invoice=FinanceLabelCosts.overlay(Map.of(),label);
        if(number(row.get("invoice_label_shipments"))==0)totals.labelMissing++;
        if(number(row.get("invoice_label_pending"))>0)totals.labelConflicts++;
        if(decimal(row.get("invoice_label_amount"))!=null)totals.labels=totals.labels.add(decimal(row.get("invoice_label_amount")));
        boolean actual="ERP_ACTUAL".equals(cost.get("basis"));
        String priceBasis=actual?"ERP 实际出库成本":"商家 SKU 标准成本";
        Object costSource=actual?cost.get("sourceName"):row.get("cost_source");
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
        for(var identity:FinanceProductSkus.identities(text(row.get("platform")),raw))
            if(text(identity.get("sellerSku")).isBlank())skus.add(FinanceProductSkus.missingIdentity(identity));
        String note=queued?"后台正在重新计算，旧成本未计入本次导出":missing?"关联订单尚未同步":text(cost.get("note"));
        if(!issues.isEmpty())note+=(note.isBlank()?"":"；")+String.join("；",issues);
        if(note.isBlank()&&!Set.of("READY","ESTIMATED","EXCLUDED").contains(code))note="成本尚未计算或资料待补齐";
        totals.orders++;totals.shops.add(shop);
        if(actual)totals.actual++;
        if(code.equals("EXCLUDED"))totals.excluded++;
        else if(Set.of("READY","ESTIMATED").contains(code)){totals.ready++;if(code.equals("ESTIMATED"))totals.estimated++;}
        else totals.pending++;
        if(known!=null)totals.known=totals.known.add(known);
        orders.row(Arrays.asList(market,shop,orderId,row.get("region"),row.get("group_name"),String.join("\n",skus),settlementDay(row),
            created(raw.get("create_time"),zone),zone,orderStatus(text(row.get("order_status"))),row.get("order_status"),
            row.get("settlement_currency"),decimal(row.get("settlement_goods_amount")),amount,known,status,cost.get("warehouse"),
            cost.isEmpty()?"":actual?"实际出库凭据":"shipment".equals(cost.get("dateBasis"))?"发货月份":"下单月份",cost.get("costMonth"),String.join("、",months),
            cost.get("lineCount"),cost.get("matchedLines"),cost.get("fallbackLines"),queued?null:costSource,
            queued?null:row.get("cost_version"),queued?null:updated,note,date,orderType,
            invoice.get("labelCostCny"),invoice.get("labelNote"),
            row.get("settlement_net_amount"),row.get("currency"),decimal(row.get("amount")),row.get("settlement_amount_note"),row.get("reporting_month"),day(row.get("platform_settlement_date")),row.get("reporting_date_source"),queued?null:priceBasis));
        // Retain an explanatory row even when the source order or its calculation is unavailable.
        if(costLines.isEmpty()){
            detail.row(Arrays.asList(market,shop,orderId,row.get("region"),row.get("group_name"),settlementDay(row),orderStatus(text(row.get("order_status"))),status,
                null,null,null,null,String.join("\n",skus),null,null,cost.get("warehouse"),null,null,null,code.equals("EXCLUDED")?BigDecimal.ZERO:null,
                cost.get("costMonth"),null,null,status,note,queued?null:costSource,queued?null:row.get("cost_version"),queued?null:updated,date,orderType,row.get("reporting_month"),day(row.get("platform_settlement_date")),row.get("reporting_date_source"),queued?null:priceBasis,null,null));
            totals.lines++;return;
        }
        for(var line:costLines){
            var source=sourceLines.stream().filter(s->!text(line.get("lineId")).isBlank()&&text(line.get("lineId")).equals(first(s,"id","line_item_id"))).findFirst().orElseGet(
                ()->sourceLines.stream().filter(s->!text(line.get("productId")).isBlank()&&!text(line.get("skuId")).isBlank()
                    &&text(line.get("productId")).equals(first(s,"product_id","item_id"))&&text(line.get("skuId")).equals(first(s,"sku_id","model_id"))).findFirst().orElse(Map.of()));
            boolean excluded=Boolean.TRUE.equals(line.get("excluded"));
            boolean gift=Boolean.TRUE.equals(line.get("fixedGift"));
            String issue=text(line.get("issue")),lineState=!issue.isBlank()?"待补齐":excluded?"不计成本":gift?"赠品 · 固定 0 成本":Boolean.TRUE.equals(line.get("previousPrice"))?"历史价估算":"已计算";
            detail.row(Arrays.asList(market,shop,orderId,row.get("region"),row.get("group_name"),settlementDay(row),orderStatus(text(row.get("order_status"))),status,
                actual?line.get("name"):first(source,"product_name","item_name"),first(source,"sku_name","model_name"),line.get("productId"),line.get("skuId"),line.get("sellerSku"),line.get("lineId"),
                line.get("costCode"),line.get("warehouse"),line.get("quantity"),line.get("factor"),line.get("unitPriceCny"),line.get("amountCny"),
                cost.get("costMonth"),line.get("priceMonth"),Boolean.TRUE.equals(line.get("previousPrice"))?"是":"否",lineState,
                issue.isBlank()?excluded?"未付款或已取消，不计成本":gift?"GIFT 商品固定零成本，无需成本关联":actual?"ERP 已确认出库成本，实际型号及数量见本行":"单价 × 有效购买数量 × 组合数量":issue,costSource,row.get("cost_version"),updated,date,orderType,row.get("reporting_month"),day(row.get("platform_settlement_date")),row.get("reporting_date_source"),priceBasis,line.get("shipmentId"),line.get("sourceRow")));
            totals.lines++;
        }
    }
    private Map<String,Object> decode(Object value){return new FinanceOrderCosts(repo).decode(value);}
    static Object settlementDay(Map<String,Object> row){return text(row.get("reporting_month")).isBlank()?day(row.get("first_settlement_date")):FinanceReportingMonths.dateLabel(row,"first_settlement_date");}
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
    static String basis(String value){return switch(value){case "SETTLED_ORDER","SAMPLE_SETTLED","REFUND_SETTLED","SETTLEMENT"->"结算归属日期（首次平台结算；已确认月份按月校正）";case "REFUND"->"所选退款记录，不按日期筛选";case "BILL","RECONCILIATION"->"所选账单关联记录，不按日期筛选";case "PENDING"->"待结算不计商品成本";default->"所选订单，不按下单日期筛选";};}
    static String costStatus(String value){return switch(value){case "READY"->"已计算";case "ESTIMATED"->"历史价估算";case "EXCLUDED"->"不计成本";case "QUEUED"->"后台计算中";case "MISSING"->"订单待同步";default->"待补齐";};}
    static String orderStatus(String value){return switch(value){
        case "UNPAID"->"待付款";case "ON_HOLD"->"订单暂挂";case "CANCELLED","CANCELED"->"已取消";case "IN_CANCEL"->"取消处理中";
        case "COMPLETED"->"已完成";case "DELIVERED"->"已送达";case "AWAITING_SHIPMENT","READY_TO_SHIP"->"待发货";case "AWAITING_COLLECTION"->"待揽收";
        case "PARTIALLY_SHIPPING","PARTIALLY_SHIPPED"->"部分发货";case "IN_TRANSIT"->"运输中";case "PROCESSED"->"已处理";case "RETRY_SHIP"->"待重新发货";
        case "SHIPPED"->"已发货";case "TO_CONFIRM_RECEIVE"->"待确认收货";case "TO_RETURN"->"待退货";default->value;};}
}
