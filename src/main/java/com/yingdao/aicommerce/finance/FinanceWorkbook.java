package com.yingdao.aicommerce.finance;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Small dependency-free workbook IO: text remains text, no formula evaluation or external XML entities. */
public final class FinanceWorkbook {
    private FinanceWorkbook(){}
    public static String csv(List<List<String>> rows){
        StringBuilder out=new StringBuilder("\uFEFF");
        for(var row:rows){out.append(row.stream().map(FinanceWorkbook::safeCsv).reduce((a,b)->a+","+b).orElse("")).append("\r\n");}
        return out.toString();
    }
    static String safeCsv(String s){
        s=s==null?"":s;
        if(s.stripLeading().matches("^[=+@\\-\\t\\r].*")&&!s.matches("-?\\d+(\\.\\d+)?"))s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    public static List<List<String>> parseCsv(String raw){
        if(raw.startsWith("\uFEFF"))raw=raw.substring(1);
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quote=false;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(c=='"'){if(quote&&i+1<raw.length()&&raw.charAt(i+1)=='"'){value.append('"');i++;}else quote=!quote;}
            else if(c==','&&!quote){row.add(value.toString());value.setLength(0);}
            else if((c=='\n'||c=='\r')&&!quote){
                if(c=='\r'&&i+1<raw.length()&&raw.charAt(i+1)=='\n')i++;
                row.add(value.toString());value.setLength(0);if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);row=new ArrayList<>();
            }else value.append(c);
            if(rows.size()>20000||row.size()>200||value.length()>100000)throw bad("单次导入最多 20,000 行、200 列");
        }
        if(quote)throw bad("CSV 引号未闭合");
        if(value.length()>0||!row.isEmpty()){row.add(value.toString());rows.add(row);}return rows;
    }
    public static byte[] xlsx(Map<String,List<List<String>>> sheets){
        return xlsx(sheets,Map.of());
    }
    /** Explicit numeric columns keep dynamic shop headers numeric without treating identifiers as numbers. */
    static byte[] xlsx(Map<String,List<List<String>>> sheets,Map<String,Set<Integer>> numericColumns){
        try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes)){
            StringBuilder types=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
            StringBuilder book=new StringBuilder("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels=new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            if(!numericColumns.isEmpty()){
                types.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
                rels.append("<Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
                put(zip,"xl/styles.xml","""
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
                    <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF354DA0"/><bgColor indexed="64"/></patternFill></fill></fills>
                    <borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs>
                    <cellXfs count="4"><xf fontId="0" fillId="0" borderId="0"/><xf fontId="1" fillId="2" borderId="0" applyAlignment="1"><alignment wrapText="1" vertical="center"/></xf><xf numFmtId="4" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/><xf numFmtId="3" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/></cellXfs>
                    </styleSheet>
                    """);
            }
            int i=0;
            for(var sheet:sheets.entrySet()){
                i++;String path="worksheets/sheet"+i+".xml";
                types.append("<Override PartName=\"/xl/").append(path).append("\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                book.append("<sheet name=\"").append(xml(sheet.getKey())).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"").append(path).append("\"/>");
                boolean matrix=numericColumns.containsKey(sheet.getKey());
                StringBuilder body=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" "
                        +(matrix?"xSplit=\"1\" topLeftCell=\"B2\"":"topLeftCell=\"A2\"")+" state=\"frozen\"/></sheetView></sheetViews>");
                int widthColumns=Math.max(2,sheet.getValue().isEmpty()?2:sheet.getValue().getFirst().size());
                if(!numericColumns.isEmpty())body.append("<cols><col min=\"1\" max=\"1\" width=\"32\" customWidth=\"1\"/><col min=\"2\" max=\"").append(widthColumns).append("\" width=\"")
                        .append(matrix?"26":"100").append("\" customWidth=\"1\"/></cols>");
                body.append("<sheetData>");
                Set<String> numericHeaders=FinanceFields.numericLabels();
                var headers=sheet.getValue().isEmpty()?List.<String>of():sheet.getValue().getFirst();
                int rowIndex=0;
                for(var row:sheet.getValue()){
                    body.append(matrix&&rowIndex==0?"<row ht=\"72\" customHeight=\"1\">":"<row>");int col=0;
                    for(String cell:row){
                        boolean numeric=rowIndex>0&&col<headers.size()&&(matrix?numericColumns.get(sheet.getKey()).contains(col):numericHeaders.contains(headers.get(col)))
                                &&text(cell).matches("-?\\d+(\\.\\d+)?");
                        if(numeric)body.append(matrix?(sheet.getKey().startsWith("记录数-")?"<c s=\"3\"><v>":"<c s=\"2\"><v>"):"<c><v>").append(cell).append("</v></c>");
                        else body.append(matrix&&rowIndex==0?"<c s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">":"<c t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(cell)).append("</t></is></c>");
                        col++;
                    }body.append("</row>");rowIndex++;
                }
                body.append("</sheetData></worksheet>");put(zip,"xl/"+path,body.toString());
            }
            put(zip,"[Content_Types].xml",types.append("</Types>").toString());
            put(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip,"xl/workbook.xml",book.append("</sheets></workbook>").toString());
            put(zip,"xl/_rels/workbook.xml.rels",rels.append("</Relationships>").toString());zip.finish();return bytes.toByteArray();
        }catch(IOException e){throw new IllegalStateException("无法生成报表",e);}
    }
    static void put(ZipOutputStream zip,String name,String content)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    static String xml(String s){return text(s).replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    public static List<List<String>> read(byte[] bytes,String name){
        return read(bytes,name,false);
    }
    /** Cost imports may ignore auxiliary formula columns, but must reject formulas in mapped cost fields. */
    static List<List<String>> readCostTable(byte[] bytes,String name){return read(bytes,name,true);}
    static List<List<String>> readCostInputTable(byte[] bytes,String name){return read(bytes,name,true,true);}
    private static List<List<String>> read(byte[] bytes,String name,boolean markFormulas){return read(bytes,name,markFormulas,false);}
    private static List<List<String>> read(byte[] bytes,String name,boolean markFormulas,boolean retainNumericType){
        if(bytes.length>12*1024*1024)throw bad("文件不能超过 12 MB");
        if(name.toLowerCase(Locale.ROOT).endsWith(".csv"))return parseCsv(new String(bytes,StandardCharsets.UTF_8));
        if(!name.toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw bad("支持 UTF-8 CSV 或 XLSX 文件");
        try {
            Map<String,byte[]> files=new HashMap<>();int expanded=0,count=0;
            try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;
                while((entry=zip.getNextEntry())!=null){if(++count>500)throw bad("工作簿结构过大");
                    var data=zip.readNBytes(32*1024*1024+1);expanded+=data.length;if(expanded>32*1024*1024)throw bad("工作簿解压后过大");
                    files.put(entry.getName(),data);
                }}
            var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();List<String> strings=new ArrayList<>();
            if(files.containsKey("xl/sharedStrings.xml")){var doc=builder.parse(new ByteArrayInputStream(files.get("xl/sharedStrings.xml")));
                var nodes=doc.getElementsByTagName("si");for(int i=0;i<nodes.getLength();i++)strings.add(nodes.item(i).getTextContent());}
            byte[] worksheet=files.get("xl/worksheets/sheet1.xml");if(worksheet==null)throw bad("未找到第一个工作表");
            var doc=builder.parse(new ByteArrayInputStream(worksheet));var nodes=doc.getElementsByTagName("row");
            if(nodes.getLength()>(markFormulas?100001:20001))throw bad("工作表行结构过大，请清理末尾空白格式；单次最多 20,000 行数据");
            List<List<String>> result=new ArrayList<>();
            for(int i=0;i<nodes.getLength();i++){List<String> row=new ArrayList<>();var cells=((Element)nodes.item(i)).getElementsByTagName("c");
                for(int j=0;j<cells.getLength();j++){Element cell=(Element)cells.item(j);String ref=cell.getAttribute("r");
                    int col=0;for(char c:ref.toCharArray()){if(c<'A'||c>'Z')break;col=col*26+c-'A'+1;}col=col==0?row.size():col-1;
                    if(col>199)throw bad("单次导入最多 200 列");while(row.size()<=col)row.add("");
                    if(cell.getElementsByTagName("f").getLength()>0){if(markFormulas){row.set(col,"[FORMULA]");continue;}throw bad("请将公式转换为值后导入");}
                    String type=cell.getAttribute("t");String value=type.equals("inlineStr")?cell.getTextContent():content(cell,"v");
                    if(type.equals("s"))value=strings.get(Integer.parseInt(value));
                    if(retainNumericType&&(type.isBlank()||type.equals("n"))&&!value.isBlank())value="[NUMBER]"+value;
                    row.set(col,value);
                }if(row.stream().anyMatch(v->!v.isBlank()))result.add(row);
                if(result.size()>20001)throw bad("单次导入最多 20,000 行数据");
            }return result;
        }catch(com.yingdao.aicommerce.common.exception.BusinessException e){throw e;}
        catch(Exception e){throw bad("无法读取 XLSX，请使用标准工作簿或 UTF-8 CSV；日期请保存为 YYYY-MM-DD 文本");}
    }
    static String content(Element e,String tag){var list=e.getElementsByTagName(tag);return list.getLength()>0?list.item(0).getTextContent():"";}
}
