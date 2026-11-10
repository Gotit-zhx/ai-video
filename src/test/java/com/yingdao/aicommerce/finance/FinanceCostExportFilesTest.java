package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real renderer and file packaging; only the database transport is substituted. */
class FinanceCostExportFilesTest extends FinanceCostExportTest {
    @TempDir Path directory;
    void fileResults(List<Map<String,Object>> data){
        when(db.queryForObject(startsWith("SELECT COUNT(*)"),eq(Long.class),any(Object[].class))).thenReturn((long)data.size());
        var pages=new ArrayDeque<List<Map<String,Object>>>();
        for(int i=0;i<data.size();i+=500)pages.add(data.subList(i,Math.min(data.size(),i+500)));
        when(db.queryForList(contains("export_sort_date"),any(Object[].class))).thenAnswer(call->pages.isEmpty()?List.of():pages.removeFirst());
        var details=new ArrayDeque<List<Map<String,Object>>>();
        for(int i=0;i<data.size();i+=500)details.add(data.subList(i,Math.min(data.size(),i+500)));
        when(db.queryForList(contains("cost_json"),any(Object[].class))).thenAnswer(call->details.removeFirst());
    }
    List<byte[]> parts(Path path)throws Exception{
        var result=new ArrayList<byte[]>();
        try(var zip=new ZipInputStream(Files.newInputStream(path))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(entry.getName().endsWith(".xlsx"))result.add(zip.readAllBytes());}
        return result;
    }
    @Test void orderBoundaryCreatesOneZipAndPreservesEveryOrderAndPrecision()throws Exception{
        fileResults(List.of(row("READY","000000000000000001"),row("READY","000000000000000002"),row("INCOMPLETE","000000000000000003")));
        var limited=new FinanceCostExport(repo,new FinanceCostExport.Limits(2,10,100,1024*1024));
        var progress=new ArrayList<Long>();
        var result=limited.exportFile(scope,filter,"SETTLED_ORDER","","ALL","",directory,(processed,total)->progress.add(processed));
        assertEquals("application/zip",result.contentType());assertEquals(2,result.parts());assertEquals(3,result.orders());assertEquals(3,result.lines());
        var files=parts(result.path());assertEquals(2,files.size());
        assertEquals("000000000000000001",value(sheet(files.get(0),2),"C2"));
        assertEquals("000000000000000002",value(sheet(files.get(0),2),"C3"));
        assertEquals("000000000000000003",value(sheet(files.get(1),2),"C2"));
        assertEquals("7.407402",value(sheet(files.get(0),2),"N2"));
        assertEquals("",summaryValue(sheet(files.get(1),1),"已结算商品成本合计（人民币）"));
        assertEquals(3L,progress.getLast());assertEquals(List.of(result.path()),Files.list(directory).toList());
    }
    @Test void skuBoundaryNeverSplitsAnOrderAndSingleOversizedOrderFailsCleanly()throws Exception{
        var two=row("READY","two");var cost=repo.read(FinanceData.text(two.get("cost_json")));
        cost.put("lines",List.of(FinanceData.rows(cost.get("lines")).getFirst(),FinanceData.rows(cost.get("lines")).getFirst()));two.put("cost_json",repo.write(cost));
        fileResults(List.of(row("READY","one"),two));
        var limited=new FinanceCostExport(repo,new FinanceCostExport.Limits(10,2,100,1024*1024));
        var result=limited.exportFile(scope,filter,"ORDER","","ALL","",directory,(p,t)->{});
        var files=parts(result.path());assertEquals(2,files.size());assertEquals(2,sheet(files.get(0),3).getElementsByTagName("row").getLength());assertEquals(3,sheet(files.get(1),3).getElementsByTagName("row").getLength());
        Files.delete(result.path());fileResults(List.of(two));
        var tiny=new FinanceCostExport(repo,new FinanceCostExport.Limits(10,1,100,1024*1024));
        assertThrows(RuntimeException.class,()->tiny.exportFile(scope,filter,"ORDER","","ALL","",directory,(p,t)->{}));
        assertEquals(0,Files.list(directory).count());
    }
    @Test void smallAndEmptyScopesRemainXlsxAndOutputLimitLeavesNoPartialFile()throws Exception{
        fileResults(List.of());var result=export.exportFile(scope,filter,"ORDER","","ALL","",directory,(p,t)->{});
        assertTrue(result.filename().endsWith(".xlsx"));assertEquals(1,result.parts());assertEquals(0,result.orders());
        assertEquals(1,sheet(Files.readAllBytes(result.path()),2).getElementsByTagName("row").getLength());
        Files.delete(result.path());fileResults(List.of(row("READY","one")));
        var tiny=new FinanceCostExport(repo,new FinanceCostExport.Limits(10,10,100,100));
        assertThrows(RuntimeException.class,()->tiny.exportFile(scope,filter,"ORDER","","ALL","",directory,(p,t)->{}));
        assertEquals(0,Files.list(directory).count());
    }
    @Test void defaultLimitsExport50001OrdersAsTwoCompleteFilesWithBoundedPageFixtures()throws Exception{
        // Raising the legacy rejection threshold or dropping the final order must fail this test.
        final long total=50_001,firstNumber=100_000_000_000_000_000L;
        var template=new LinkedHashMap<>(row("INCOMPLETE","template"));
        template.put("raw_json","{}");template.put("cost_json","{}");template.put("amount_cny",null);template.put("known_amount_cny",null);
        var page=new AtomicReference<List<Map<String,Object>>>(List.of());long[] next={0};
        when(db.queryForObject(startsWith("SELECT COUNT(*)"),eq(Long.class),any(Object[].class))).thenReturn(total);
        when(db.queryForList(contains("export_sort_date"),any(Object[].class))).thenAnswer(call->{
            var keys=new ArrayList<Map<String,Object>>();var data=new ArrayList<Map<String,Object>>();
            for(int position=0;position<500&&next[0]<total;position++,next[0]++){
                String number=Long.toString(firstNumber+next[0]);
                keys.add(Map.of("order_record_id","record-"+number,"organization_id","org","shop_id","shop","reference",number,
                    "export_sort_date",java.sql.Date.valueOf("2026-10-23"),"first_settlement_date",java.sql.Date.valueOf("2026-10-23")));
                var value=new LinkedHashMap<>(template);value.put("order_record_id","record-"+number);value.put("order_id",number);
                value.put("reference",number);value.put("page_position",position);data.add(value);
            }
            page.set(data);return keys;
        });
        when(db.queryForList(contains("cost_json"),any(Object[].class))).thenAnswer(call->page.getAndSet(List.of()));
        Path output=Files.createDirectory(directory.resolve("output"));
        long[] lastProgress={-1};
        var result=export.exportFile(scope,filter,"SETTLED_ORDER","","ALL","",output,(processed,expected)->{
            assertEquals(total,expected);assertTrue(processed>=lastProgress[0]);lastProgress[0]=processed;
            clearInvocations(db); // Mockito must not retain 101 pages of SQL parameter arrays.
        });
        assertEquals(total,lastProgress[0]);assertEquals(total,result.orders());assertEquals(total,result.lines());
        assertEquals(2,result.parts());assertEquals("application/zip",result.contentType());
        long inspected=0;int partNumber=0;
        try(var archive=new ZipFile(result.path().toFile())){
            var workbooks=Collections.list(archive.entries()).stream().filter(entry->entry.getName().endsWith(".xlsx")).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            assertEquals(2,workbooks.size());
            for(var workbook:workbooks){
                Path extracted=Files.createTempFile(directory,"inspect-",".xlsx");
                try{
                    try(var input=archive.getInputStream(workbook)){Files.copy(input,extracted,StandardCopyOption.REPLACE_EXISTING);}
                    long orderCount=streamOrderNumbers(extracted,2,firstNumber+inspected);
                    long skuCount=streamOrderNumbers(extracted,3,firstNumber+inspected);
                    assertEquals(partNumber++==0?50_000:1,orderCount);assertEquals(orderCount,skuCount);
                    assertTrue(orderCount<=50_000);assertTrue(skuCount<=200_000);inspected+=orderCount;
                }finally{Files.deleteIfExists(extracted);}
            }
        }
        assertEquals(50_001,inspected,"all orders including the final 100000000000050000 must be present exactly once");
        try(var files=Files.list(output)){assertEquals(List.of(result.path()),files.toList(),"no intermediate XLSX or XML directories remain");}
        Files.delete(result.path());
        try(var files=Files.list(output)){assertEquals(0,files.count());}
    }
    /** Read only the current XML event; never materialize the 50,000-row worksheets. */
    private static long streamOrderNumbers(Path workbook,int sheet,long firstNumber)throws Exception{
        var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        long dataRows=-1,ids=0;boolean numberCell=false;
        try(var zip=new ZipFile(workbook.toFile());var input=zip.getInputStream(zip.getEntry("xl/worksheets/sheet"+sheet+".xml"))){
            var reader=factory.createXMLStreamReader(input);
            try{
                while(reader.hasNext()){
                    int event=reader.next();
                    if(event==XMLStreamConstants.START_ELEMENT){
                        String name=reader.getLocalName();
                        if(name.equals("row"))dataRows++;
                        if(name.equals("c")){
                            numberCell=dataRows>0&&("C"+(dataRows+1)).equals(reader.getAttributeValue(null,"r"));
                            if(numberCell)assertEquals("inlineStr",reader.getAttributeValue(null,"t"));
                        }
                        if(name.equals("t")&&numberCell){
                            assertEquals(Long.toString(firstNumber+dataRows-1),reader.getElementText(),"every exported order/SKU ID must preserve page sequence and text precision");
                            ids++;numberCell=false;
                        }
                    }else if(event==XMLStreamConstants.END_ELEMENT&&reader.getLocalName().equals("c"))numberCell=false;
                }
            }finally{reader.close();}
        }
        assertEquals(dataRows,ids,"every data row must contain its text order number");
        return dataRows;
    }

}
