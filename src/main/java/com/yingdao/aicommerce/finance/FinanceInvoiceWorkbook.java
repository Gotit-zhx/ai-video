package com.yingdao.aicommerce.finance;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.*;
import javax.xml.stream.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Streaming XLSX reader. Shared strings live on disk, never in a workbook-sized heap collection. */
final class FinanceInvoiceWorkbook implements AutoCloseable {
    static final long MAX_EXPANDED=512L*1024*1024;
    static final int MAX_ROWS=500_010, MAX_TEXT=16_384;
    final ZipFile zip;
    final Path scratch;
    final RandomAccessFile strings,offsets;
    final Map<String,String> sheets=new LinkedHashMap<>();
    final XMLInputFactory xml=XMLInputFactory.newFactory();
    long stringCount;
    FinanceInvoiceWorkbook(Path path,Path scratch)throws Exception{
        this.scratch=scratch;Files.createDirectories(scratch);
        zip=new ZipFile(path.toFile());
        strings=new RandomAccessFile(scratch.resolve("strings.bin").toFile(),"rw");
        offsets=new RandomAccessFile(scratch.resolve("offsets.bin").toFile(),"rw");
        try{
            xml.setProperty(XMLInputFactory.SUPPORT_DTD,false);
            xml.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
            xml.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External XML is disabled");});
            long expanded=0;int count=0;
            for(var entries=zip.entries();entries.hasMoreElements();){
                var e=entries.nextElement();
                if(++count>500||e.getSize()<0||(expanded+=e.getSize())>MAX_EXPANDED)throw bad("工作簿解压后超过 512 MB 或结构过大，请拆分文件");
            }
            var relations=new HashMap<String,String>();
            scan("xl/_rels/workbook.xml.rels",r->{
                if(r.isStartElement()&&r.getLocalName().equals("Relationship")){
                    String target=r.getAttributeValue(null,"Target"),mode=r.getAttributeValue(null,"TargetMode");
                    if("External".equals(mode))return;
                    if(target!=null){
                        String normalized=Path.of(target.startsWith("/")?target.substring(1):"xl/"+target).normalize().toString();
                        if(!normalized.startsWith("xl/")||normalized.contains(".."))throw bad("工作簿引用不正确");
                        relations.put(r.getAttributeValue(null,"Id"),normalized);
                    }
                }
            });
            scan("xl/workbook.xml",r->{
                if(r.isStartElement()&&r.getLocalName().equals("sheet")){
                    String id=r.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");
                    String name=r.getAttributeValue(null,"name");
                    if(name!=null&&relations.containsKey(id))sheets.put(name,relations.get(id));
                }
            });
            if(zip.getEntry("xl/sharedStrings.xml")!=null){
                final StringBuilder[] value={null};
                scan("xl/sharedStrings.xml",r->{
                    if(r.isStartElement()&&r.getLocalName().equals("si"))value[0]=new StringBuilder();
                    else if(r.isStartElement()&&r.getLocalName().equals("t")&&value[0]!=null)append(value[0],text(r));
                    else if(r.isEndElement()&&r.getLocalName().equals("si")){
                        if(++stringCount>3_000_000)throw bad("共享文本超过 300 万项，请拆分工作簿");
                        byte[] bytes=value[0].toString().getBytes(StandardCharsets.UTF_8);
                        offsets.writeLong(strings.getFilePointer());strings.writeInt(bytes.length);strings.write(bytes);value[0]=null;
                    }
                });
            }
        }catch(Exception e){close();throw e;}
    }
    interface Event {void accept(XMLStreamReader reader)throws Exception;}
    void scan(String name,Event event)throws Exception{
        var entry=zip.getEntry(name);if(entry==null)throw bad("工作簿缺少 "+name);
        try(var in=new LimitedInputStream(zip.getInputStream(entry),MAX_EXPANDED)){
            var reader=xml.createXMLStreamReader(in);
            try{while(reader.hasNext()){reader.next();event.accept(reader);}}
            finally{reader.close();}
        }
    }
    static String text(XMLStreamReader r)throws XMLStreamException{
        var out=new StringBuilder();
        while(r.hasNext()){
            int type=r.next();
            if(type==XMLStreamConstants.END_ELEMENT)break;
            if(type==XMLStreamConstants.CHARACTERS||type==XMLStreamConstants.CDATA)append(out,r.getText());
            if(type==XMLStreamConstants.START_ELEMENT)throw bad("工作簿单元格结构不正确");
        }
        return out.toString();
    }
    static void append(StringBuilder target,String value){
        if(target.length()+value.length()>MAX_TEXT)throw bad("单元格内容超过 16,384 字符");
        target.append(value);
    }
    String string(String value)throws IOException{
        long index;
        try{index=Long.parseLong(value);}catch(NumberFormatException e){throw bad("共享文本索引不正确");}
        if(index<0||index>=stringCount)throw bad("共享文本索引越界");
        offsets.seek(index*8);strings.seek(offsets.readLong());int size=strings.readInt();
        if(size<0||size>MAX_TEXT*4)throw bad("共享文本过长");
        byte[] bytes=new byte[size];strings.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
    void rows(String name,BiConsumer<Integer,List<String>> consumer)throws Exception{
        String path=sheets.get(name);if(path==null)throw bad("未找到「"+name+"」工作表");
        var row=new ArrayList<String>();final int[] rowNumber={0},count={0},column={0};
        final String[] type={""};final boolean[] formula={false};var value=new StringBuilder();
        scan(path,r->{
            if(r.isStartElement())switch(r.getLocalName()){
                case "row"->{
                    if(++count[0]>MAX_ROWS)throw bad("单份账单最多 500,000 条记录，请按月份拆分");
                    String number=r.getAttributeValue(null,"r");
                    rowNumber[0]=number==null?rowNumber[0]+1:Integer.parseInt(number);row.clear();
                }
                case "c"->{
                    String ref=r.getAttributeValue(null,"r");int c=0;
                    if(ref!=null)for(char ch:ref.toCharArray()){if(ch<'A'||ch>'Z')break;c=c*26+ch-'A'+1;if(c>200)throw bad("最多支持 200 列");}
                    column[0]=c==0?row.size():c-1;type[0]=Objects.toString(r.getAttributeValue(null,"t"),"");
                    value.setLength(0);formula[0]=false;
                }
                case "f"->formula[0]=true;
                case "v","t"->append(value,text(r));
            };
            if(r.isEndElement())switch(r.getLocalName()){
                case "c"->{
                    while(row.size()<=column[0])row.add("");
                    String cell=formula[0]?"[FORMULA]":type[0].equals("s")?string(value.toString()):value.toString();
                    // Never silently accept an 18-digit identifier rounded by Excel numeric storage.
                    if(!formula[0]&&(type[0].isEmpty()||type[0].equals("n"))&&cell.matches("\\d{16,}"))cell="[UNSAFE_NUMBER]"+cell;
                    row.set(column[0],cell);
                }
                case "row"->{if(row.stream().anyMatch(v->!v.isBlank()))consumer.accept(rowNumber[0],List.copyOf(row));}
            };
        });
    }
    @Override public void close()throws IOException{
        try{zip.close();}finally{try{strings.close();}finally{offsets.close();}}
        Files.deleteIfExists(scratch.resolve("strings.bin"));Files.deleteIfExists(scratch.resolve("offsets.bin"));Files.deleteIfExists(scratch);
    }
    static final class LimitedInputStream extends FilterInputStream{
        long remaining;
        LimitedInputStream(InputStream in,long max){super(in);remaining=max;}
        void consumed(long n)throws IOException{if(n>0&&(remaining-=n)<0)throw new IOException("Workbook expanded size limit");}
        @Override public int read()throws IOException{int n=super.read();if(n>=0)consumed(1);return n;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{int n=in.read(bytes,offset,length);consumed(n);return n;}
    }
}
