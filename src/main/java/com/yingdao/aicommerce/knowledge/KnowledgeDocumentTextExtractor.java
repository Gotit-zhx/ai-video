package com.yingdao.aicommerce.knowledge;

import javax.xml.stream.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Bounded, in-memory extraction. No processes, network requests, macros or formula evaluation. */
public final class KnowledgeDocumentTextExtractor {
    static final int MAX_TEXT=200_000, MAX_UNPACKED=32*1024*1024, MAX_ENTRY=8*1024*1024, MAX_ENTRIES=2048;
    private KnowledgeDocumentTextExtractor() { }
    public record Extraction(String status,String text,String note) { }
    public static Extraction extract(byte[] bytes,String extension) {
        try {
            return switch(extension) {
                case "txt","md","csv" -> plain(bytes);
                case "docx" -> docx(archive(bytes));
                case "xlsx" -> xlsx(archive(bytes));
                case "pdf" -> stored("原文件已保存；尚未接入 PDF 文本解析及扫描件 OCR，未加入附件文本检索");
                case "doc","xls" -> stored("原文件已保存；旧版 Office 二进制格式尚未解析，建议另传 DOCX 或 XLSX 以便文本检索");
                case "ppt","pptx" -> stored("原文件已保存；尚未接入 PowerPoint 幻灯片正文、备注及图片提取，未加入附件文本检索");
                case "png","jpg","jpeg","webp","mp4" -> stored("原文件已保存；图片和视频未进行 OCR 或语音识别，没有生成提取文字");
                default -> stored("原文件已保存；此格式尚未接入文本解析");
            };
        } catch(Exception failure) {
            return stored("原文件已保存；无法安全完整解析此文档（可能加密、损坏、编码不支持或超过解析限制），未加入附件文本检索");
        }
    }
    static Extraction stored(String note) { return new Extraction("STORED_ONLY","",note); }
    static Extraction result(String value,boolean partial,String note) {
        String text=value.strip();
        if(text.isEmpty())return stored("原文件已保存；未提取到可检索文字，图片及扫描内容不会自动识别");
        if(text.length()>MAX_TEXT) {int end=MAX_TEXT;if(Character.isHighSurrogate(text.charAt(end-1)))end--;text=text.substring(0,end);partial=true;}
        return new Extraction(partial?"PARTIAL":"EXTRACTED",text,partial?note+"；仅保存有界文本，图片、公式及未提取部分请查看原件":note);
    }
    static Extraction plain(byte[] bytes) throws CharacterCodingException {
        Charset charset=StandardCharsets.UTF_8;int offset=0;
        if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191)offset=3;
        else if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254){charset=StandardCharsets.UTF_16LE;offset=2;}
        else if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255){charset=StandardCharsets.UTF_16BE;offset=2;}
        var decoder=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        var output=java.nio.CharBuffer.allocate(MAX_TEXT+1);
        var decoded=decoder.decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset),output,true);
        if(decoded.isError())decoded.throwException();boolean truncated=decoded.isOverflow();output.flip();String text=output.toString();
        if(text.codePoints().anyMatch(c->c==0 || Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))return stored("原文件已保存；含非文本控制字符，未加入附件文本检索");
        return result(text,truncated,"已提取文本；CSV 保留原始行列文本，不执行单元格公式");
    }
    static Map<String,byte[]> archive(byte[] bytes) throws IOException {
        var files=new LinkedHashMap<String,byte[]>();long total=0;int count=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                if(++count>MAX_ENTRIES)throw new IOException("Archive entry limit");
                String name=entry.getName();
                if(name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains(".."))throw new IOException("Unsafe archive path");
                if(entry.isDirectory())continue;
                boolean selected=name.equals("word/document.xml")||name.equals("xl/sharedStrings.xml")
                        ||name.equals("xl/workbook.xml")||name.equals("xl/_rels/workbook.xml.rels")
                        ||name.matches("xl/worksheets/[^/]+\\.xml");
                var data=selected?new ByteArrayOutputStream():null;byte[] buffer=new byte[8192];int size=0;
                for(int read;(read=zip.read(buffer))!=-1;) {
                    size+=read;total+=read;if(total>MAX_UNPACKED||size>MAX_ENTRY)throw new IOException("Archive expansion limit");
                    if(selected)data.write(buffer,0,read);
                }
                if(selected&&files.putIfAbsent(name,data.toByteArray())!=null)throw new IOException("Duplicate document part");
            }
        }
        return files;
    }
    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        if(bytes==null)throw new XMLStreamException("Missing document part");
        var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External entities disabled");});
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }
    private static void rejectDtd(int event) throws XMLStreamException {if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE)throw new XMLStreamException("Entities disabled");}
    static Extraction docx(Map<String,byte[]> files) throws XMLStreamException {
        var reader=xml(files.get("word/document.xml"));var out=new StringBuilder();boolean inText=false,partial=false;
        try {
            while(reader.hasNext()) {
                int event=reader.next();rejectDtd(event);
                if(event==XMLStreamConstants.START_ELEMENT) {
                    String name=reader.getLocalName();if(name.equals("t"))inText=true;
                    if(name.equals("tab"))out.append('\t');if(name.equals("br"))out.append('\n');
                    if(Set.of("drawing","pict","object","altChunk").contains(name))partial=true;
                } else if(event==XMLStreamConstants.CHARACTERS&&inText)out.append(reader.getText());
                else if(event==XMLStreamConstants.END_ELEMENT) {String name=reader.getLocalName();if(name.equals("t"))inText=false;if(name.equals("p")||name.equals("tr"))out.append('\n');if(name.equals("tc"))out.append('\t');}
                if(out.length()>MAX_TEXT){partial=true;break;}
            }
        } finally {reader.close();}
        // DOCX extraction is deliberately labeled partial: headers, notes, comments and drawing text are not traversed.
        return result(out.toString(),true,"已提取 Word 正文和表格文字；未提取页眉页脚、批注及嵌入内容");
    }
    static Extraction xlsx(Map<String,byte[]> files) throws XMLStreamException {
        var strings=new ArrayList<String>();
        if(files.containsKey("xl/sharedStrings.xml")) {
            var reader=xml(files.get("xl/sharedStrings.xml"));StringBuilder value=null;boolean inText=false;
            try {while(reader.hasNext()) {
                int event=reader.next();rejectDtd(event);
                if(event==XMLStreamConstants.START_ELEMENT){if(reader.getLocalName().equals("si"))value=new StringBuilder();if(reader.getLocalName().equals("t"))inText=true;}
                if(event==XMLStreamConstants.CHARACTERS&&inText&&value!=null)value.append(reader.getText());
                if(event==XMLStreamConstants.END_ELEMENT){if(reader.getLocalName().equals("t"))inText=false;if(reader.getLocalName().equals("si")){strings.add(value==null?"":value.toString());if(strings.size()>100000)throw new XMLStreamException("Shared string limit");}}
            }}finally{reader.close();}
        }
        var reader=xml(files.get("xl/workbook.xml"));var sheets=new LinkedHashMap<String,String>();
        try{while(reader.hasNext()){int event=reader.next();rejectDtd(event);if(event==XMLStreamConstants.START_ELEMENT&&reader.getLocalName().equals("sheet")){String id=null;for(int i=0;i<reader.getAttributeCount();i++)if(reader.getAttributeLocalName(i).equals("id"))id=reader.getAttributeValue(i);if(id!=null)sheets.put(id,reader.getAttributeValue(null,"name"));}}}finally{reader.close();}
        reader=xml(files.get("xl/_rels/workbook.xml.rels"));var targets=new HashMap<String,String>();
        try{while(reader.hasNext()){int event=reader.next();rejectDtd(event);if(event==XMLStreamConstants.START_ELEMENT&&reader.getLocalName().equals("Relationship")){String target=reader.getAttributeValue(null,"Target"),mode=reader.getAttributeValue(null,"TargetMode");if(target!=null&&!"External".equalsIgnoreCase(mode)){String path=target.startsWith("/xl/")?target.substring(1):"xl/"+target;if(path.matches("xl/worksheets/[^/]+\\.xml"))targets.put(reader.getAttributeValue(null,"Id"),path);}}}}finally{reader.close();}
        var out=new StringBuilder();
        for(var sheet:sheets.entrySet()) {
            if(out.length()>MAX_TEXT)break;String path=targets.get(sheet.getKey());if(path==null||!files.containsKey(path))continue;
            out.append("[工作表 / Sheet: ").append(sheet.getValue()).append("]\n");reader=xml(files.get(path));String type="",cell="";boolean value=false;var cellText=new StringBuilder();
            try{while(reader.hasNext()) {
                int event=reader.next();rejectDtd(event);
                if(event==XMLStreamConstants.START_ELEMENT) {String name=reader.getLocalName();if(name.equals("c")){type=Objects.toString(reader.getAttributeValue(null,"t"),"");cell=Objects.toString(reader.getAttributeValue(null,"r"),"");cellText.setLength(0);}if(name.equals("v")||name.equals("t"))value=true;}
                if(event==XMLStreamConstants.CHARACTERS&&value)cellText.append(reader.getText());
                if(event==XMLStreamConstants.END_ELEMENT) {String name=reader.getLocalName();if(name.equals("v")||name.equals("t"))value=false;if(name.equals("c")){String text=cellText.toString();if(type.equals("s")){int index=Integer.parseInt(text);if(index<0||index>=strings.size())throw new XMLStreamException("Invalid shared string");text=strings.get(index);}if(!text.isBlank())out.append(cell).append(": ").append(text).append('\t');}if(name.equals("row"))out.append('\n');}
                if(out.length()>MAX_TEXT)break;
            }}finally{reader.close();}
        }
        return result(out.toString(),true,"已提取 Excel 单元格文本和缓存值；不计算公式，不解释日期或数字显示格式");
    }
}
