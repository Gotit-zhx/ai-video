package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import java.time.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceFeeMatrixTest {
    static Filter filter(String shop,String group){return new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),shop,"","","day",group);}
    static Map<String,Object> shop(String id,String group,String alias){return Map.of("id",id,"group_id",group,"display_name",alias,"name","原名"+id,"platform","SHOPEE","currency","PHP");}
    static Map<String,Object> fee(String shop,String category,String currency,String amount,long records,long known){
        var row=new HashMap<String,Object>();row.put("shop_id",shop);row.put("category",category);row.put("currency",currency);
        row.put("amount",amount==null?null:new BigDecimal(amount));row.put("records",records);row.put("known_amounts",known);return row;
    }
    static Map<String,Object> facts(List<Map<String,Object>> shops,List<Map<String,Object>> fees){return Map.of("shops",shops,"fees",fees,"groups",List.of(Map.of("id","g","name","母婴")));}
    static Map<String,String> unzip(byte[] bytes)throws Exception{
        var result=new HashMap<String,String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null)result.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }return result;
    }
    @Test void fullMatrixKeepsAliasesNegativeAmountsZeroColumnsCountsAndNumericCells()throws Exception{
        var report=facts(List.of(shop("b","g","店铺2"),shop("a","g","店铺1"),shop("c","","店铺3")),
                List.of(fee("a","commission_fee","PHP","20.125",2,2),fee("b","commission_fee","PHP","-5",1,1)));
        byte[] bytes=FinanceFeeMatrix.workbook(filter("",""),report);
        var rows=FinanceWorkbook.read(bytes,"fees.xlsx");
        assertEquals(List.of("费用项目（PHP）","店铺1","店铺2","店铺3","合计"),rows.getFirst());
        assertEquals(List.of("佣金","20.125","-5","0","15.125"),rows.get(1));
        assertEquals("15.125",rows.getLast().getLast());
        var zip=unzip(bytes);
        assertTrue(zip.get("xl/worksheets/sheet1.xml").contains("<c s=\"2\"><v>20.125</v>"));
        assertTrue(zip.get("xl/worksheets/sheet1.xml").contains("xSplit=\"1\""));
        assertTrue(zip.get("xl/worksheets/sheet2.xml").contains("<c s=\"3\"><v>2</v>"));
    }
    @Test void groupAndSingleShopScopeAndDuplicateAliasesRemainSeparate(){
        var all=List.of(shop("a","g","同名"),shop("b","g","同名"),shop("c","","其他"));
        var grouped=FinanceWorkbook.read(FinanceFeeMatrix.workbook(filter("","g"),facts(all,List.of(fee("a","费用","PHP","5",1,1)))),"fees.xlsx");
        assertEquals(List.of("费用项目（PHP）","同名 [a]","同名 [b]","合计"),grouped.getFirst());
        var single=FinanceWorkbook.read(FinanceFeeMatrix.workbook(filter("b","g"),facts(all,List.of(fee("b","费用","PHP","3",1,1)))),"fees.xlsx");
        assertEquals(List.of("费用项目（PHP）","同名","合计"),single.getFirst());
        assertEquals(List.of("费用","3","3"),single.get(1));
    }
    @Test void nativeCurrenciesStaySeparateAndMissingAmountsNeverBecomeZero()throws Exception{
        var source=facts(List.of(shop("a","g","店铺1")),List.of(fee("a","费用","PHP","100",1,1),fee("a","费用","USD","2",1,1)));
        var nativeZip=unzip(FinanceFeeMatrix.workbook(filter("",""),source));
        assertTrue(nativeZip.get("xl/workbook.xml").contains("平台费用-PHP"));
        assertTrue(nativeZip.get("xl/workbook.xml").contains("平台费用-USD"));
        assertFalse(nativeZip.get("xl/workbook.xml").contains("平台费用-CNY"));
        var rows=FinanceWorkbook.read(FinanceFeeMatrix.workbook(filter("",""),source),"fees.xlsx");
        assertEquals("100",rows.get(1).get(1));
        var missing=facts(List.of(shop("a","g","店铺1")),List.of(fee("a","费用","PHP","100",2,1)));
        assertEquals(List.of("费用","待补齐","待补齐"),FinanceWorkbook.read(FinanceFeeMatrix.workbook(filter("",""),missing),"fees.xlsx").get(1));
    }
    @Test void noRecordsKeepsSelectedShopsAndFormulaLookingAliasesStayText()throws Exception{
        var result=unzip(FinanceFeeMatrix.workbook(filter("",""),facts(List.of(shop("a","","=1+1")),List.of())));
        assertTrue(result.get("xl/worksheets/sheet1.xml").contains("=1+1"));
        assertFalse(result.get("xl/worksheets/sheet1.xml").contains("<f>"));
        assertTrue(result.get("xl/worksheets/sheet1.xml").contains("平台费用合计"));
    }
}
