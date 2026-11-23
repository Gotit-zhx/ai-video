package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.common.exception.BusinessException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static com.yingdao.aicommerce.finance.FinanceData.bad;

/** Isolates workbook decoding from the application heap. The parent owns the validation and ledger transaction. */
final class FinanceBillImportProcess {
    private static final int MAX_CELL_BYTES=FinanceInvoiceWorkbook.MAX_TEXT*4;
    private static final long MAX_OUTPUT_BYTES=256L*1024*1024;
    private static final int FAILED=-1;

    private FinanceBillImportProcess(){}

    static void rows(Path upload,String filename,BiConsumer<Integer,List<String>> accept)throws IOException{
        Path workspace=Files.createTempDirectory(upload.getParent(),"work-bill-decoder-");
        Path spool=workspace.resolve("rows.bin");
        Process child=null;
        try{
            var launcher=Path.of(System.getProperty("java.home"),"bin","java");
            var builder=new ProcessBuilder(launcher.toString(),"-Xms16m","-Xmx128m","-XX:ActiveProcessorCount=1",
                    "-cp",System.getProperty("java.class.path"),FinanceBillImportProcess.class.getName(),
                    upload.toString(),filename,spool.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            // The decoder uses only the local upload and spool. Never pass datasource or provider credentials.
            builder.environment().clear();
            child=builder.start();
            if(!child.waitFor(90,TimeUnit.SECONDS))throw new IOException("Bill decoder timed out");
            if(child.exitValue()!=0)throw new IOException("Bill decoder exited without a complete stream");
            read(spool,accept);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            throw new IOException("Bill decoder interrupted",interrupted);
        }finally{
            if(child!=null&&child.isAlive()){
                child.destroyForcibly();
                try{child.waitFor(5,TimeUnit.SECONDS);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
            Files.deleteIfExists(spool);
            Path decoder=workspace.resolve("xlsx");
            Files.deleteIfExists(decoder.resolve("strings.bin"));
            Files.deleteIfExists(decoder.resolve("offsets.bin"));
            Files.deleteIfExists(decoder);
            Files.deleteIfExists(workspace);
        }
    }

    static void read(Path spool,BiConsumer<Integer,List<String>> accept)throws IOException{
        if(Files.size(spool)>MAX_OUTPUT_BYTES)throw bad("账单解析结果超过暂存空间上限");
        try(var input=new DataInputStream(new BufferedInputStream(Files.newInputStream(spool)))){
            int line;
            while((line=input.readInt())>0){
                int columns=input.readInt();
                if(columns<1||columns>FinanceBillImportFile.MAX_COLUMNS)throw new IOException("Invalid bill stream");
                var row=new ArrayList<String>(columns);
                for(int column=0;column<columns;column++)row.add(readText(input));
                accept.accept(line,row);
            }
            if(line==FAILED)throw bad(readText(input));
            if(line!=0||input.read()!=-1)throw new IOException("Invalid bill stream ending");
        }
    }

    private static String readText(DataInputStream input)throws IOException{
        int length=input.readInt();
        if(length<0||length>MAX_CELL_BYTES)throw new IOException("Invalid bill cell size");
        byte[] buffer=input.readNBytes(length);
        if(buffer.length!=length)throw new IOException("Truncated bill cell");
        return new String(buffer,StandardCharsets.UTF_8);
    }

    private static void writeText(DataOutputStream output,byte[] bytes)throws IOException{
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    public static void main(String[] args)throws IOException{
        if(args.length!=3)throw new IllegalArgumentException("Expected upload path, filename and output file");
        Path upload=Path.of(args[0]),spool=Path.of(args[2]);
        try(var output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(spool)))){
            long[] size={Integer.BYTES};
            int[] total={0};
            try{
                Path decoder=Files.createDirectory(spool.getParent().resolve("xlsx"));
                FinanceBillImportFile.rows(upload,args[1],decoder,(line,row)->{
                    if(++total[0]>FinanceBillImportFile.MAX_ROWS+1)throw bad("第 "+line+" 行超过单份账单 100,000 条上限");
                    if(row.size()>FinanceBillImportFile.MAX_COLUMNS)throw bad("第 "+line+" 行超过 200 列");
                    var cells=row.stream().map(cell->cell.getBytes(StandardCharsets.UTF_8)).toList();
                    long bytes=2L*Integer.BYTES;
                    for(byte[] cell:cells)bytes+=Integer.BYTES+cell.length;
                    size[0]+=bytes;
                    if(size[0]>MAX_OUTPUT_BYTES-1024)throw bad("账单解析结果超过暂存空间上限");
                    try{
                        output.writeInt(line);output.writeInt(row.size());
                        for(byte[] cell:cells)writeText(output,cell);
                    }catch(IOException failure){throw new UncheckedIOException(failure);}
                });
                output.writeInt(0);
            }catch(BusinessException failure){
                output.writeInt(FAILED);
                writeText(output,failure.getMessage().getBytes(StandardCharsets.UTF_8));
            }catch(UncheckedIOException failure){throw failure.getCause();}
        }
    }
}
