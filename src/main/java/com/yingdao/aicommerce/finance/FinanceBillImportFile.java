package com.yingdao.aicommerce.finance;

import java.io.IOException;
import java.io.PushbackReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import javax.xml.stream.XMLStreamException;
import static com.yingdao.aicommerce.finance.FinanceData.bad;

final class FinanceBillImportFile {
    static final int MAX_ROWS=100_000, MAX_COLUMNS=200;
    static final long MAX_BYTES=40L*1024*1024;

    static void rows(Path file,String filename,BiConsumer<Integer,List<String>> accept)throws IOException{
        rows(file,filename,null,accept);
    }

    static void rows(Path file,String filename,Path workspace,BiConsumer<Integer,List<String>> accept)throws IOException{
        if(filename.toLowerCase(Locale.ROOT).endsWith(".csv")){
            try(var reader=new PushbackReader(Files.newBufferedReader(file),1)){
                csv(reader,accept);
            }catch(java.nio.charset.CharacterCodingException invalid){
                throw bad("CSV 文件请使用 UTF-8 编码");
            }
            return;
        }
        Path scratch=workspace==null?Files.createTempDirectory(file.getParent(),"work-"):workspace;
        try(var workbook=new FinanceInvoiceWorkbook(file,scratch)){
            if(workbook.sheets.isEmpty())throw bad("工作簿没有可读取的工作表");
            workbook.rows(workbook.sheets.keySet().iterator().next(),accept);
        }catch(XMLStreamException|java.util.zip.ZipException|NumberFormatException invalid){
            throw bad("无法读取 XLSX，请检查文件格式后重新上传");
        }catch(IOException|RuntimeException failure){
            throw failure;
        }catch(Exception failure){
            throw new IOException("Cannot read workbook",failure);
        }finally{
            Files.deleteIfExists(scratch.resolve("strings.bin"));
            Files.deleteIfExists(scratch.resolve("offsets.bin"));
            if(workspace==null)Files.deleteIfExists(scratch);
        }
    }

    private static void csv(PushbackReader in,BiConsumer<Integer,List<String>> accept)throws IOException{
        var row=new ArrayList<String>();
        var value=new StringBuilder();
        int line=1,start=1,c=in.read();
        boolean quoted=false,closedQuote=false;
        if(c==0xFEFF)c=in.read();
        while(c!=-1){
            if(c=='\r'||c=='\n'){
                String newline=String.valueOf((char)c);
                if(c=='\r'){
                    int next=in.read();
                    if(next=='\n')newline="\r\n";
                    else if(next!=-1)in.unread(next);
                }
                if(quoted)value.append(newline);
                else{
                    emit(accept,row,value,start);
                    row.clear();
                    value.setLength(0);
                    start=line+1;
                    closedQuote=false;
                }
                line++;
            }else if(c=='"'){
                if(quoted){
                    int next=in.read();
                    if(next=='"')value.append('"');
                    else{
                        quoted=false;
                        closedQuote=true;
                        if(next!=-1)in.unread(next);
                    }
                }else if(value.length()==0&&!closedQuote)quoted=true;
                else throw bad("第 "+start+" 行 CSV 引号格式不正确");
            }else if(c==','&&!quoted){
                if(row.size()>=MAX_COLUMNS-1)throw bad("第 "+start+" 行超过 200 列");
                row.add(value.toString());value.setLength(0);closedQuote=false;
            }else{
                if(closedQuote)throw bad("第 "+start+" 行 CSV 引号后应为逗号或换行");
                value.append((char)c);
            }
            if(value.length()>FinanceInvoiceWorkbook.MAX_TEXT)throw bad("第 "+start+" 行单元格内容过长");
            c=in.read();
        }
        if(quoted)throw bad("第 "+start+" 行 CSV 引号未闭合");
        if(!row.isEmpty()||value.length()>0)emit(accept,row,value,start);
    }

    private static void emit(BiConsumer<Integer,List<String>> accept,List<String> row,StringBuilder value,int line){
        row.add(value.toString());
        if(row.stream().anyMatch(cell->!cell.isBlank()))accept.accept(line,List.copyOf(row));
    }
}
