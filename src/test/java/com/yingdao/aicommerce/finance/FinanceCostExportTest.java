package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceCostExportTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final FinanceRepository repo=new FinanceRepository(db,new ObjectMapper());
    final FinanceCostExport export=new FinanceCostExport(repo);
    final Scope scope=new Scope("org","COMPANY");
    final Filter filter=new Filter(LocalDate.of(2026,9,24),LocalDate.of(2026,10,23),"shop","TIKTOK_SHOP","","day","group");
    Map<String,Object> row(String status,String id){
        var line=new LinkedHashMap<String,Object>(Map.of("sellerSku","(PACK)*2","productId","10000000000000001","skuId","20000000000000002","lineId","line-"+id,
            "quantity",new BigDecimal("3"),"factor",new BigDecimal("2"),"unitPriceCny",new BigDecimal("1.234567"),
            "amountCny",new BigDecimal("7.407402"),"priceMonth","2026-09","previousPrice",true));
        line.put("warehouse","菲律宾海外仓");line.put("costCode","PACK");
        var cost=new LinkedHashMap<String,Object>(Map.of("status",status,"lines",List.of(line),"warehouse","菲律宾海外仓","dateBasis","order","costMonth","2026-10",
            "lineCount",1,"matchedLines",1,"fallbackLines",1,"note","使用历史价格"));
        var row=new LinkedHashMap<String,Object>();
        row.put("order_record_id",id);row.put("order_id",id);row.put("shop_name","测试店（店铺1616）");row.put("shop_id","shop");
        row.put("platform","TIKTOK_SHOP");row.put("group_name","母婴");row.put("region","PH");row.put("timezone","Asia/Manila");
        row.put("order_status","COMPLETED");row.put("record_date",java.sql.Date.valueOf("2026-10-23"));
        row.put("currency","PHP");row.put("amount",new BigDecimal("199.50"));row.put("cost_status",status);
        row.put("amount_cny",new BigDecimal("7.407402"));row.put("known_amount_cny",new BigDecimal("7.407402"));
        row.put("cost_json",repo.write(cost));row.put("cost_version",2L);row.put("cost_source","母婴成本.xlsx");
        row.put("cost_updated_at",java.sql.Timestamp.from(Instant.parse("2026-10-23T06:00:00Z")));
        row.put("raw_json",repo.write(Map.of("create_time",1792713600L,"line_items",List.of(Map.of("id","line-"+id,"seller_sku","(PACK)*2","product_name","=不应执行的商品名","sku_name","XL")))));
        return row;
    }
    void results(List<Map<String,Object>> rows){
        when(db.queryForObject(startsWith("SELECT COUNT(*)"),eq(Long.class),any(Object[].class))).thenReturn((long)rows.size());
        when(db.queryForList(contains("cost_json"),any(Object[].class))).thenAnswer(inv->{
            Object[] args=inv.getArguments();int offset=((Number)args[args.length-1]).intValue();
            return rows.subList(offset,Math.min(offset+500,rows.size()));
        });
    }
    static Document sheet(byte[] bytes,int n)throws Exception{
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            ZipEntry e;while((e=zip.getNextEntry())!=null)if(e.getName().equals("xl/worksheets/sheet"+n+".xml"))
                return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
        }throw new AssertionError("Missing sheet");
    }
    static Element cell(Document d,String ref){
        var cells=d.getElementsByTagName("c");
        for(int i=0;i<cells.getLength();i++){var c=(Element)cells.item(i);if(c.getAttribute("r").equals(ref))return c;}
        throw new AssertionError(ref);
    }
    static String value(Document d,String ref){return cell(d,ref).getTextContent();}
    @Test void detailedWorkbookPreservesPrecisionIdsStatusesDatesAndSnapshots()throws Exception{
        var estimated=row("ESTIMATED","586742209199441786");
        var sampleRaw=new LinkedHashMap<>(repo.read(text(estimated.get("raw_json"))));sampleRaw.put("order_type","SELLER_FUND_FREE_SAMPLE");sampleRaw.put("is_sample_order",true);estimated.put("raw_json",repo.write(sampleRaw));
        var pending=row("INCOMPLETE","586742209199441787");pending.put("amount_cny",null);
        var partial=map(repo.read(text(pending.get("cost_json"))));var missing=new LinkedHashMap<String,Object>(rows(partial.get("lines")).getFirst());
        missing.remove("amountCny");missing.remove("unitPriceCny");missing.put("issue","缺少成本价");partial.put("lines",List.of(missing));pending.put("cost_json",repo.write(partial));
        var queued=row("ESTIMATED","586742209199441788");queued.put("queued_id","queued");
        var excluded=row("EXCLUDED","586742209199441789");excluded.put("amount_cny",BigDecimal.ZERO);excluded.put("known_amount_cny",BigDecimal.ZERO);
        excluded.put("cost_json",repo.write(Map.of("status","EXCLUDED","lines",List.of(),"note","未付款，不计成本")));
        results(List.of(estimated,pending,queued,excluded));
        byte[] bytes=export.export(scope,filter,"ORDER","");
        var summary=sheet(bytes,1);var orders=sheet(bytes,2);var lines=sheet(bytes,3);
        assertEquals(5,orders.getElementsByTagName("row").getLength());assertEquals(5,lines.getElementsByTagName("row").getLength());
        assertEquals("586742209199441786",value(orders,"C2"));assertEquals("inlineStr",cell(orders,"C2").getAttribute("t"));
        assertEquals("7.407402",value(orders,"N2"));assertEquals("",cell(orders,"N2").getAttribute("t"));
        assertEquals("",value(orders,"N3"));assertEquals("",value(orders,"N4"));assertEquals("",value(orders,"O4"));
        assertEquals("0",value(orders,"N5"));assertEquals("后台计算中",value(orders,"P4"));
        assertEquals("3",cell(orders,"G2").getAttribute("s"));assertEquals("4",cell(orders,"H2").getAttribute("s"));
        assertEquals("2026-09",value(lines,"V2"));assertEquals("1.234567",value(lines,"S2"));
        assertEquals("商家承担的免费样品单",value(orders,"AC2"));assertEquals("商家承担的免费样品单",value(lines,"AD2"));
        assertEquals("3",value(lines,"Q2"));assertEquals("2",value(lines,"R2"));
        assertEquals("=不应执行的商品名",value(lines,"I2"));assertEquals("inlineStr",cell(lines,"I2").getAttribute("t"));
        assertEquals(0,lines.getElementsByTagName("f").getLength());
        assertEquals("",value(summary,"B17"));assertEquals("14.814804",value(summary,"B18"));
        assertTrue(orders.getElementsByTagName("autoFilter").getLength()>0);
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/order-cost-export-test.xlsx"),bytes);
    }
    @Test void exportsEveryPageAndUsesCompanyShopDatesGroupFilter()throws Exception{
        var data=new ArrayList<Map<String,Object>>();
        for(int i=0;i<501;i++)data.add(row("READY","order-"+i));
        results(data);
        var bytes=export.export(scope,filter,"ORDER","");
        assertEquals(502,sheet(bytes,2).getElementsByTagName("row").getLength());
        var calls=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("queryForList")).toList();
        assertEquals(2,calls.size());
        String sql=(String)calls.getFirst().getArgument(0);
        assertTrue(sql.contains("r.organization_id=?"));assertTrue(sql.contains("r.shop_id=?"));
        assertTrue(sql.contains("r.record_date>=?"));assertTrue(sql.contains("fg.group_id=?"));
        assertTrue(Arrays.asList(calls.getFirst().getArguments()).contains("org"));
    }
    @Test void recordDrilldownUsesSelectedSourceAndDoesNotLimitOrderCreationDates(){
        results(List.of());export.export(scope,filter,"BILL","selected-bill");
        var call=mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("queryForObject")).findFirst().orElseThrow();
        String sql=(String)call.getArgument(0);
        assertTrue(sql.contains("r.kind='BILL'"));assertTrue(sql.contains("r.organization_id=? AND r.id=?"));
        assertFalse(sql.contains("r.record_date>="));assertTrue(Arrays.asList(call.getArguments()).contains("selected-bill"));
    }
    @Test void oversizedOrInvalidExportFailsInsteadOfTruncating(){
        when(db.queryForObject(startsWith("SELECT COUNT(*)"),eq(Long.class),any(Object[].class))).thenReturn(50001L);
        assertThrows(RuntimeException.class,()->export.export(scope,filter,"ORDER",""));
        assertThrows(RuntimeException.class,()->export.export(scope,filter,"AD",""));
        verify(db,never()).queryForList(anyString(),any(Object[].class));
    }
}
