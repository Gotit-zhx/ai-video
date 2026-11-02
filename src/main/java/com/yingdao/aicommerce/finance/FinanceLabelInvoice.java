package com.yingdao.aicommerce.finance;

import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.function.Consumer;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Warehouse invoice interpretation; document summaries are controls, never extra cost rows. */
final class FinanceLabelInvoice {
    record Line(int sourceRow,String eventKey,String orderId,String shopHint,String customer,String warehouse,
                String document,LocalDate date,BigDecimal amount,String bucketKey,int sourcePriority,int billingPriority,
                String billingStatus,String feeType,String sheet,BigDecimal unitPrice,BigDecimal quantity){}
    record Result(String customer,LocalDate start,LocalDate end,int rows,BigDecimal amount,BigDecimal invoiceAmount,String type){}
    static String value(List<String> row,int col){return col>=0&&col<row.size()?row.get(col).trim():"";}
    static int col(List<String> row,String...names){return FinanceOrderCharges.column(row,names);}
    static BigDecimal money(String value){
        try{
            if(!value.matches("[+-]?\\d+(\\.\\d{1,6})?"))throw new IllegalArgumentException();
            var number=new BigDecimal(value);
            if(number.precision()-number.scale()>18)throw new IllegalArgumentException();
            return number.setScale(6,RoundingMode.UNNECESSARY);
        }catch(RuntimeException e){throw bad("费用金额必须为有效人民币数值，最多 6 位小数；公式需先转为值");}
    }
    static LocalDate date(String value){
        for(var formatter:List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT))){
            try{return LocalDate.parse(value,formatter);}catch(DateTimeParseException ignored){}
        }
        // Excel serial dates are supported without applying workbook locale to order identifiers.
        if(value.matches("\\d{5}(\\.0+)?")){
            try{return LocalDate.of(1899,12,30).plusDays(new BigDecimal(value).longValueExact());}
            catch(RuntimeException ignored){}
        }
        throw bad("交易日期不正确："+value);
    }
    static String bounded(String value,int max,String name){
        if(value.length()>max||value.startsWith("[FORMULA]")||value.startsWith("[UNSAFE_NUMBER]"))throw bad(name+"格式不正确；长订单号须保留文本格式");
        return value;
    }
    static Result parse(Path path,Path scratch,Consumer<Line> consumer)throws Exception{
        try(var book=new FinanceInvoiceWorkbook(path,scratch)){
            if(!book.sheets.containsKey("账单汇总")||!book.sheets.containsKey("出库订单"))return flat(book,consumer);
            var metadata=new HashMap<String,String>();final BigDecimal[] expected={null},invoice={null};
            book.rows("账单汇总",(row,cells)->{
                if(row>2000)throw bad("账单汇总结构过大");
                String label=value(cells,0);
                if(Set.of("结算客户","结算周期","币种").contains(label))metadata.put(label,value(cells,1));
                if(label.equals("出库费"))expected[0]=money(value(cells,2));
                if(label.equals("总费用"))invoice[0]=money(value(cells,2));
            });
            String customer=bounded(metadata.getOrDefault("结算客户",""),120,"结算客户");
            if(customer.isBlank()||!"CNY".equalsIgnoreCase(metadata.get("币种")))throw bad("需要结算客户及人民币 CNY 账单，不自动换算其他币种");
            String period=metadata.getOrDefault("结算周期","");
            var matcher=java.util.regex.Pattern.compile("^(\\d{2}/\\d{2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})\\s*[-至~]\\s*(\\d{2}/\\d{2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})$").matcher(period);
            if(!matcher.matches())throw bad("未识别到账单结算周期");
            LocalDate start=date(matcher.group(1)),end=date(matcher.group(2));
            if(start.isAfter(end)||end.isAfter(start.plusYears(1)))throw bad("账单周期不正确");
            final List<List<String>> header=new ArrayList<>();final int[] count={0};final BigDecimal[] total={BigDecimal.ZERO},footer={null};
            book.rows("出库订单",(row,cells)->{
                if(header.isEmpty()){
                    if(col(cells,"WMS单号")<0)return;
                    for(String required:List.of("交易日期","仓库","客户","WMS单号","参考号","操作费"))
                        if(col(cells,required)<0)throw bad("出库订单缺少「"+required+"」列");
                    header.add(cells);return;
                }
                var h=header.getFirst();
                String document=value(cells,col(h,"WMS单号")),status=value(cells,col(h,"计费状态"));
                String amount=value(cells,col(h,"贴单金额","操作费"));
                if(document.isBlank()){
                    if(status.equals("汇总")||status.equals("合计")){footer[0]=money(amount);return;}
                    if(!amount.isBlank()&&money(amount).signum()!=0)throw bad("第 "+row+" 行有金额但缺少 WMS 单号");
                    return;
                }
                String reference=bounded(value(cells,col(h,"参考号")),255,"第 "+row+" 行参考号");
                if(!reference.isEmpty()&&!reference.matches("[A-Za-z0-9_-]+"))throw bad("第 "+row+" 行参考号不正确；平台订单号须为完整文本");
                String warehouse=bounded(value(cells,col(h,"仓库")),120,"仓库");
                String client=bounded(value(cells,col(h,"客户")),120,"客户");
                bounded(document,200,"WMS单号");
                if(!client.equals(customer)||warehouse.isBlank())throw bad("第 "+row+" 行客户或仓库与账单不一致");
                LocalDate transaction=date(value(cells,col(h,"交易日期")));
                if(transaction.isBefore(start)||transaction.isAfter(end))throw bad("第 "+row+" 行交易日期超出账单周期");
                BigDecimal fee=money(amount);total[0]=total[0].add(fee);
                if(++count[0]>500_000)throw bad("单份账单最多 500,000 条出库记录，请拆分");
                // Stable across source files and row reorderings. Multiple shipments per order remain distinct.
                String key=hash("label-outbound-v1\0"+customer+"\0"+warehouse+"\0"+document);
                consumer.accept(new Line(row,key,reference,bounded(value(cells,col(h,"店铺编码")),255,"店铺编码"),
                    customer,warehouse,document,transaction,fee,bucket(customer,warehouse,reference,transaction),1,2,status,"出库费","出库订单",null,null));
            });
            if(header.isEmpty()||count[0]==0)throw bad("没有识别到出库订单明细");
            if(expected[0]==null||invoice[0]==null)throw bad("账单汇总缺少出库费或总费用，无法核对完整性");
            if(total[0].compareTo(expected[0])!=0||footer[0]!=null&&total[0].compareTo(footer[0])!=0)
                throw bad("出库操作费明细与账单汇总不一致，整份账单暂不生效");
            return new Result(customer,start,end,count[0],total[0],invoice[0],"WAREHOUSE_INVOICE");
        }
    }
    static String bucket(String customer,String warehouse,String reference,LocalDate date){
        return hash("label-order-date-v1\0"+customer+"\0"+warehouse+"\0"+reference+"\0"+date);
    }
    /** Flat monthly fee exports have no summary sheet or WMS number; reference is the platform order. */
    static Result flat(FinanceInvoiceWorkbook book,Consumer<Line> consumer)throws Exception{
        int[] count={0};BigDecimal[] total={BigDecimal.ZERO};LocalDate[] range={null,null};
        String[] customer={null};boolean[] found={false};
        for(String sheet:book.sheets.keySet()){
            final List<List<String>> header=new ArrayList<>();
            book.rows(sheet,(row,cells)->{
                if(header.isEmpty()){
                    if(row>50||col(cells,"参考号")<0||col(cells,"金额","贴单金额")<0)return;
                    for(String name:List.of("交易日期","仓库","客户","类型","单价","计费数量","计费项类型"))
                        if(col(cells,name)<0)throw bad("费用明细缺少「"+name+"」列");
                    header.add(cells);found[0]=true;return;
                }
                var h=header.getFirst();
                if(value(cells,col(h,"参考号")).equals("参考号"))return;
                String reference=bounded(value(cells,col(h,"参考号")),255,"第 "+row+" 行参考号");
                String feeType=bounded(value(cells,col(h,"计费项类型")),64,"计费项类型");
                String type=value(cells,col(h,"类型")),status=bounded(value(cells,col(h,"计费状态")),64,"计费状态");
                String amount=value(cells,col(h,"贴单金额","金额"));
                if(Set.of("合计","汇总","总计").contains(value(cells,0)))return;
                if(!"OutBound".equalsIgnoreCase(type)||(!feeType.isBlank()&&!Set.of("出库费","贴单费","贴单成本").contains(feeType)))
                    throw bad("第 "+row+" 行不是订单出库贴单费用，请单独导出出库费用明细");
                if(!reference.isBlank()&&!reference.matches("[A-Za-z0-9_-]+"))throw bad("第 "+row+" 行参考号不正确，订单编号须为完整文本");
                String currency=value(cells,col(h,"币种"));
                if(!currency.isBlank()&&!Set.of("CNY","RMB","人民币").contains(currency.toUpperCase(Locale.ROOT)))throw bad("仅支持人民币贴单金额，不自动折算");
                String client=bounded(value(cells,col(h,"客户")),120,"客户"),warehouse=bounded(value(cells,col(h,"仓库")),120,"仓库");
                if(client.isBlank()||warehouse.isBlank())throw bad("第 "+row+" 行客户或仓库为空");
                LocalDate date=date(value(cells,col(h,"交易日期")));
                BigDecimal fee=money(amount),unit=optionalMoney(value(cells,col(h,"单价"))),quantity=optionalMoney(value(cells,col(h,"计费数量")));
                // The numeric amount is the financial source, including an
                // explicit zero. Exporter billing-status text is metadata only.
                if(unit!=null&&quantity!=null&&unit.multiply(quantity).compareTo(fee)!=0)
                    throw bad("第 "+row+" 行单价 × 计费数量与金额不一致");
                if(feeType.isBlank()&&fee.signum()!=0)throw bad("第 "+row+" 行存在金额但未说明费用类型");
                if(++count[0]>500_000)throw bad("每份账单最多 500,000 条记录，请拆分");
                total[0]=total[0].add(fee);
                range[0]=range[0]==null||date.isBefore(range[0])?date:range[0];range[1]=range[1]==null||date.isAfter(range[1])?date:range[1];
                customer[0]=customer[0]==null?client:customer[0].equals(client)?client:"多个客户";
                String bucket=bucket(client,warehouse,reference,date);
                String event=hash("label-flat-v1\0"+bucket+(reference.isBlank()?"\0"+sheet+"\0"+row:""));
                consumer.accept(new Line(count[0],event,reference,"",client,warehouse,"",date,fee,bucket,2,
                    feeType.isBlank()?1:2,status,feeType,sheet,unit,quantity));
            });
        }
        if(!found[0]||count[0]==0)throw bad("未识别到仓库账单或包含参考号、金额的订单费用明细");
        return new Result(customer[0],range[0],range[1],count[0],total[0],total[0],"ORDER_FEES");
    }
    static BigDecimal optionalMoney(String value){return value.isBlank()?null:money(value);}
}
