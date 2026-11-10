package com.yingdao.aicommerce.finance;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.*;

/** Bounded-memory XLSX writer for cost reports. IDs remain text; source text is never a formula. */
final class FinanceCostWorkbook implements AutoCloseable {
    private final Path directory;
    private final List<Sheet> sheets = new ArrayList<>();
    private long spoolBytes;
    private final List<OutputStream> spools=new ArrayList<>();
    FinanceCostWorkbook() throws IOException { this(null); }
    FinanceCostWorkbook(Path parent) throws IOException { directory=parent==null?Files.createTempDirectory("finance-cost-export-"):Files.createTempDirectory(parent,"sheets-"); }
    private OutputStream spool(Path path)throws IOException{
        var output=new FilterOutputStream(Files.newOutputStream(path)) {
            private void count(int amount)throws IOException{
                if(spoolBytes>1024L*1024*1024-amount)throw new IOException("成本报表单片临时数据超过 1 GiB，请联系管理员核对异常商品资料");
                spoolBytes+=amount;
            }
            @Override public void write(int b)throws IOException{count(1);out.write(b);}
            @Override public void write(byte[] b,int off,int len)throws IOException{count(len);out.write(b,off,len);}
        };
        spools.add(output);return output;
    }
    Sheet sheet(String name,List<String> headers) throws IOException {
        var sheet=new Sheet(name,headers,directory.resolve("sheet"+(sheets.size()+1)+".xml"),this);
        sheets.add(sheet);return sheet;
    }
    static String column(int index){
        StringBuilder result=new StringBuilder();
        for(int n=index+1;n>0;n=(n-1)/26)result.insert(0,(char)('A'+(n-1)%26));
        return result.toString();
    }
    interface RowSink { void row(List<?> values) throws IOException; }
    static final class Sheet implements RowSink {
        final String name;final List<String> headers;final Path path;final Writer writer;
        final List<Integer> widths=new ArrayList<>();
        int count;boolean finished;
        Sheet(String name,List<String> headers,Path path,FinanceCostWorkbook owner)throws IOException{
            this.name=name;this.headers=headers;this.path=path;writer=new BufferedWriter(new OutputStreamWriter(owner.spool(path),StandardCharsets.UTF_8));
            writer.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\">"
                +(headers.size()>3?"<pane xSplit=\"3\" ySplit=\"1\" topLeftCell=\"D2\" activePane=\"bottomRight\" state=\"frozen\"/>":"")
                +"</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"32\"/><cols>");
            for(int i=0;i<headers.size();i++){
                String h=headers.get(i);int width=h.contains("说明")||h.contains("原因")||h.contains("名称")||h.contains("文件")?42:h.contains("时间")?24:h.contains("编号")||h.contains("SKU")?28:h.contains("金额")||h.contains("成本")?23:20;
                if(headers.size()==2)width=i==0?36:110;
                widths.add(width);
                writer.write("<col min=\""+(i+1)+"\" max=\""+(i+1)+"\" width=\""+width+"\" customWidth=\"1\"/>");
            }
            writer.write("</cols><sheetData>");row(new ArrayList<>(headers));
        }
        public void row(List<?> values)throws IOException{
            if(finished)throw new IllegalStateException("Sheet already finished");
            if(count>=1048576)throw FinanceData.bad("成本报表附表超过 Excel 单页行数上限，未生成部分报表");
            if(values.size()!=headers.size())throw new IllegalArgumentException("Wrong export column count: "+name);
            count++;int height=count==1?38:32;
            for(int i=0;i<values.size();i++)if(values.get(i) instanceof String s){
                int wraps=0;for(String part:s.split("\n",-1)){int length=part.codePoints().map(c->c>255?2:1).sum();wraps+=Math.max(1,(length+widths.get(i)-3)/(widths.get(i)-2));}
                height=Math.max(height,Math.min(409,wraps*15+8));
            }
            writer.write("<row r=\""+count+"\" ht=\""+height+"\" customHeight=\"1\">");
            for(int i=0;i<values.size();i++){
                Object value=values.get(i);String ref=column(i)+count;int style=count==1?1:0;
                if(value==null){writer.write("<c r=\""+ref+"\" s=\"0\"/>");continue;}
                if(count>1&&value instanceof Number n){
                    String numeric=n instanceof BigDecimal d?d.toPlainString():new BigDecimal(n.toString()).toPlainString();
                    if(!numeric.matches("-?\\d+(\\.\\d+)?"))throw new IllegalArgumentException("Invalid export number");
                    boolean integer=n instanceof Integer||n instanceof Long
                        ||(headers.get(i).contains("数量")&&new BigDecimal(numeric).stripTrailingZeros().scale()<=0);
                    writer.write("<c r=\""+ref+"\" s=\""+(integer?6:2)+"\"><v>"+numeric+"</v></c>");continue;
                }
                if(count>1&&(value instanceof LocalDate||value instanceof LocalDateTime)){
                    LocalDateTime time=value instanceof LocalDate d?d.atStartOfDay():(LocalDateTime)value;
                    double serial=ChronoUnit.DAYS.between(LocalDate.of(1899,12,30),time.toLocalDate())+time.toLocalTime().toSecondOfDay()/86400.0;
                    writer.write("<c r=\""+ref+"\" s=\""+(value instanceof LocalDate?3:4)+"\"><v>"+serial+"</v></c>");continue;
                }
                String text=String.valueOf(value);
                if(count>1&&Set.of("待补齐","后台计算中","订单待同步","历史价估算").contains(text))style=5;
                if(text.length()>32767)throw FinanceData.bad("导出字段过长，请缩小范围或核对商品资料");
                writer.write("<c r=\""+ref+"\" s=\""+style+"\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"+FinanceWorkbook.xml(text)+"</t></is></c>");
            }
            writer.write("</row>");
        }
        void finish()throws IOException{
            if(finished)return;
            writer.write("</sheetData><autoFilter ref=\"A1:"+column(headers.size()-1)+count+"\"/></worksheet>");
            writer.close();finished=true;
        }
    }
    byte[] bytes()throws IOException{
        var bytes=new ByteArrayOutputStream();write(bytes);return bytes.toByteArray();
    }
    void write(OutputStream output)throws IOException{
        for(var s:sheets)s.finish();
        try(var zip=new ZipOutputStream(output)){
            var types=new StringBuilder("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            var book=new StringBuilder("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            var rels=new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for(int i=0;i<sheets.size();i++){
                var s=sheets.get(i);String path="worksheets/sheet"+(i+1)+".xml";
                types.append("<Override PartName=\"/xl/").append(path).append("\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                book.append("<sheet name=\"").append(FinanceWorkbook.xml(s.name)).append("\" sheetId=\"").append(i+1).append("\" r:id=\"rId").append(i+1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"").append(path).append("\"/>");
                zip.putNextEntry(new ZipEntry("xl/"+path));Files.copy(s.path,zip);zip.closeEntry();
            }
            rels.append("<Relationship Id=\"rStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            FinanceWorkbook.put(zip,"[Content_Types].xml",types.append("</Types>").toString());
            FinanceWorkbook.put(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            FinanceWorkbook.put(zip,"xl/workbook.xml",book.append("</sheets></workbook>").toString());
            FinanceWorkbook.put(zip,"xl/_rels/workbook.xml.rels",rels.append("</Relationships>").toString());
            FinanceWorkbook.put(zip,"xl/styles.xml",STYLES);zip.finish();
        }
    }
    @Override public void close()throws IOException{
        IOException failure=null;
        for(var s:sheets)try{s.writer.close();}catch(IOException e){failure=e;}
        for(var stream:spools)try{stream.close();}catch(IOException e){failure=e;}
        if(Files.exists(directory))try(var paths=Files.newDirectoryStream(directory)){
            for(var path:paths)try{Files.deleteIfExists(path);}catch(IOException e){failure=e;}
        }catch(IOException e){failure=e;}
        try{Files.deleteIfExists(directory);}catch(IOException e){failure=e;}
        if(failure!=null)throw failure;
    }
    private static final String STYLES="""
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <numFmts count="3"><numFmt numFmtId="164" formatCode="#,##0.00####"/><numFmt numFmtId="165" formatCode="yyyy-mm-dd"/><numFmt numFmtId="166" formatCode="yyyy-mm-dd hh:mm:ss"/></numFmts>
        <fonts count="2"><font><sz val="11"/><color rgb="FF25334D"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
        <fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF4263EB"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF3CD"/><bgColor indexed="64"/></patternFill></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="7">
        <xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1"/></xf>
        <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
        <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
        <xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
        </cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>
        """;
}
