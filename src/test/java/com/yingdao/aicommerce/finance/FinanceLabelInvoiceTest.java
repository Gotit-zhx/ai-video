package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceLabelInvoiceTest {
    @TempDir Path temp;
    static byte[] invoice(List<List<String>> rows,String total,String currency){
        var sheets=new LinkedHashMap<String,List<List<String>>>();
        sheets.put("账单汇总",List.of(List.of("结算客户","TEST"),List.of("结算周期","09/01/2026-09/30/2026"),
            List.of("币种",currency),List.of("计费项类型","仓库","汇总"),List.of("出库费",total,total),List.of("总费用",total,total)));
        var data=new ArrayList<List<String>>();
        data.add(List.of("交易日期","仓库","客户","店铺编码","WMS单号","订单号","参考号","计费状态","操作费"));
        data.addAll(rows);data.add(List.of("","","","","","","","汇总",total));
        sheets.put("出库订单",data);
        // Same money represented in another worksheet must never be counted twice.
        sheets.put("库内操作费",data);
        return FinanceWorkbook.xlsx(sheets);
    }
    static List<String> line(String document,String order,String amount){
        return List.of("09/01/2026","WAREHOUSE","TEST","Warehouse Shop Alias",document,"ERP-123",order,"计算成功",amount);
    }
    static List<String> flatLine(String order,String status,String price,String quantity,String amount,String fee){
        return List.of("09/01/2026","WAREHOUSE","TEST",order,"OutBound",status,price,quantity,amount,fee);
    }
    static byte[] flat(List<List<String>> rows){
        var data=new ArrayList<List<String>>();
        data.add(List.of("交易日期","仓库","客户","参考号","类型","计费状态","单价","计费数量","金额","计费项类型"));
        data.addAll(rows);
        return FinanceWorkbook.xlsx(new LinkedHashMap<>(Map.of("Sheet1",data)));
    }
    FinanceLabelInvoice.Result parse(byte[] bytes,List<FinanceLabelInvoice.Line> lines)throws Exception{
        Path file=temp.resolve(UUID.randomUUID()+".xlsx");Files.write(file,bytes);
        return FinanceLabelInvoice.parse(file,temp.resolve(UUID.randomUUID().toString()),lines::add);
    }
    @Test void usesReferenceNumbersAndActualCnyWithNoSummaryOrDetailDuplication()throws Exception{
        var lines=new ArrayList<FinanceLabelInvoice.Line>();
        var result=parse(invoice(List.of(line("W1","586208660231587825","2"),line("W2","260831S49QQ8AA","3")), "5","CNY"),lines);
        assertEquals(2,result.rows());assertEquals(0,decimal("5").compareTo(result.amount()));
        assertEquals("586208660231587825",lines.getFirst().orderId());
        assertEquals("W1",lines.getFirst().document());assertEquals(2,lines.getFirst().sourceRow());
    }
    @Test void validatesTotalsCurrencyAndAmountsIndependentlyOfBillingStatus()throws Exception{
        var row=new ArrayList<>(line("W1","order","2"));
        assertThrows(RuntimeException.class,()->parse(invoice(List.of(row),"3","CNY"),new ArrayList<>()));
        assertThrows(RuntimeException.class,()->parse(invoice(List.of(row),"2","PHP"),new ArrayList<>()));
        row.set(7,"计算失败");
        assertEquals(0,decimal("2").compareTo(parse(invoice(List.of(row),"2","CNY"),new ArrayList<>()).amount()));
        row.set(7,"计算成功");row.set(8,"[FORMULA]");
        assertThrows(RuntimeException.class,()->parse(invoice(List.of(row),"2","CNY"),new ArrayList<>()));
    }
    @Test void preservesZeroAndStableShipmentIdentityAcrossRowReordering()throws Exception{
        var a=new ArrayList<FinanceLabelInvoice.Line>();var b=new ArrayList<FinanceLabelInvoice.Line>();
        parse(invoice(List.of(line("W1","order","0"),line("W2","order","2")),"2","CNY"),a);
        parse(invoice(List.of(line("W2","order","2"),line("W1","order","0")),"2","CNY"),b);
        assertEquals(a.getFirst().eventKey(),b.getLast().eventKey());
        assertNotEquals(a.getFirst().eventKey(),a.getLast().eventKey());assertEquals(0,a.getFirst().amount().signum());
    }
    @Test void suppliedInvoiceReconcilesWithoutLoadingFullWorkbook()throws Exception{
        String file=System.getenv("FINANCE_QA_LABEL_FILE");Assumptions.assumeTrue(file!=null);
        var count=new int[2];var result=FinanceLabelInvoice.parse(Path.of(file),temp.resolve("scratch"),line->{
            count[0]++;if(line.amount().signum()>0)count[1]++;
        });
        assertEquals(648,count[0]);assertEquals(640,count[1]);assertEquals(0,new BigDecimal("1280").compareTo(result.amount()));
        assertFalse(Files.exists(temp.resolve("scratch")),"disk-backed shared strings are cleaned");
    }
    @Test void explicitZeroIsConfirmedRegardlessOfExporterBillingStatus()throws Exception{
        var lines=new ArrayList<FinanceLabelInvoice.Line>();
        var parsed=parse(flat(List.of(flatLine("order","计算成功","1.95","1","1.95","出库费"),
            flatLine("zero","计算成功","0","","0",""),flatLine("failed","计费失败","0","","0",""))),lines);
        assertEquals("ORDER_FEES",parsed.type());assertEquals(3,parsed.rows());assertEquals(0,decimal("1.95").compareTo(parsed.amount()));
        assertEquals("order",lines.getFirst().orderId());assertEquals(1,lines.getLast().billingPriority());
        assertEquals(1,lines.get(1).billingPriority());
        var confirmed=new ArrayList<FinanceLabelInvoice.Line>();
        parse(flat(List.of(flatLine("order","计费失败","2","1","2","出库费"))),confirmed);
        assertEquals(2,confirmed.getFirst().billingPriority());
        assertEquals(0,decimal("2").compareTo(confirmed.getFirst().amount()));
    }
    @Test void flatFeesRejectInvalidArithmeticWithoutPublishingAnyPart(){
        assertThrows(RuntimeException.class,()->parse(flat(List.of(flatLine("order","计算成功","2","2","2","出库费"))),new ArrayList<>()));
        assertThrows(RuntimeException.class,()->parse(flat(List.of(flatLine("order","计费失败","","","","出库费"))),new ArrayList<>()));
    }
}
