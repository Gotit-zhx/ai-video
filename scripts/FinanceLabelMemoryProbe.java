package com.yingdao.aicommerce.finance;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Run in a separate JVM with -Xmx96m. Generates a large workbook directly to disk. */
public final class FinanceLabelMemoryProbe {
    static void put(ZipOutputStream zip,String name,String text)throws IOException{
        zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    static void row(Writer w,String...cells)throws IOException{
        w.write("<row>");
        for(String cell:cells){w.write("<c t=\"inlineStr\"><is><t>");w.write(cell);w.write("</t></is></c>");}
        w.write("</row>");
    }
    public static void main(String[] args)throws Exception{
        int count=args.length==0?500_000:Integer.parseInt(args[0]);Path folder=Files.createTempDirectory("invoice-memory-probe-");
        Path file=folder.resolve("large.xlsx");long started=System.nanoTime();
        try{
            try(var zip=new ZipOutputStream(Files.newOutputStream(file))){
                put(zip,"xl/workbook.xml","<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"账单汇总\" r:id=\"r1\"/><sheet name=\"出库订单\" r:id=\"r2\"/></sheets></workbook>");
                put(zip,"xl/_rels/workbook.xml.rels","<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"r2\" Target=\"worksheets/sheet2.xml\"/></Relationships>");
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
                var w=new BufferedWriter(new OutputStreamWriter(zip,StandardCharsets.UTF_8));
                w.write("<worksheet><sheetData>");
                row(w,"结算客户","TEST");row(w,"结算周期","09/01/2026-09/30/2026");row(w,"币种","CNY");
                row(w,"出库费",Integer.toString(count*2),Integer.toString(count*2));
                row(w,"总费用",Integer.toString(count*2),Integer.toString(count*2));w.write("</sheetData></worksheet>");w.flush();zip.closeEntry();
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet2.xml"));w.write("<worksheet><dimension ref=\"A1\"/><sheetData>");
                row(w,"交易日期","仓库","客户","店铺编码","WMS单号","订单号","参考号","计费状态","操作费");
                for(int i=0;i<count;i++)row(w,"09/01/2026","WAREHOUSE","TEST","Shop","WMS-"+i,"ERP-"+i,"586200000000"+String.format("%06d",i),"计算成功","2");
                w.write("</sheetData></worksheet>");w.flush();zip.closeEntry();
            }
            long parseStart=System.nanoTime();long[] rows={0};
            var result=FinanceLabelInvoice.parse(file,folder.resolve("shared-strings"),line->rows[0]++);
            if(rows[0]!=count||result.amount().intValueExact()!=count*2)throw new AssertionError("Incomplete invoice parse");
            System.out.printf(Locale.ROOT,"PASS rows=%d amountCny=%s compressedMB=%.2f parseSeconds=%.2f heapLimitMB=%d totalSeconds=%.2f%n",
                rows[0],result.amount().toPlainString(),Files.size(file)/1048576d,(System.nanoTime()-parseStart)/1e9,
                Runtime.getRuntime().maxMemory()/1048576,(System.nanoTime()-started)/1e9);
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(folder);}
    }
}
