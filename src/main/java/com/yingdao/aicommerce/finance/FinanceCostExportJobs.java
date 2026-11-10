package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Personal, bounded and process-local exports. Restarting deliberately discards task metadata. */
@Service
public class FinanceCostExportJobs implements AutoCloseable {
    private static final long RETENTION=30*60_000L,REUSE=60_000L;
    private final FinanceService service;
    private final FinanceCostExport exporter;
    private final LongSupplier clock;
    private final Executor executor;
    private final ExecutorService owned;
    private final ScheduledExecutorService cleaner;
    private final Path tempRoot;
    private final long maxFileBytes,maxRetainedBytes;
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private final Set<Job> retired=new HashSet<>();
    private boolean closed;

    private record Selection(Filter filter,String source,String recordId,String costStatus,String numbers) {}
    private static final class Job {
        final String id=UUID.randomUUID().toString();
        final Scope owner;
        final Selection selection;
        final Set<String> keys=new HashSet<>();
        final long created;
        String state="QUEUED",error="";
        long completed,processed,total,bytes;
        int readers;
        boolean generating,retired;
        Path directory;
        FinanceCostExport.GeneratedFile file;
        Job(Scope owner,Selection selection,String key,long created){this.owner=owner;this.selection=selection;keys.add(key);this.created=created;}
    }
    @Autowired
    public FinanceCostExportJobs(FinanceService service,FinanceCostExport exporter){
        this(service,exporter,System::currentTimeMillis,new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"finance-cost-export");t.setDaemon(true);return t;},
                new ThreadPoolExecutor.AbortPolicy()),true,Path.of(System.getProperty("java.io.tmpdir")),128L*1024*1024,256L*1024*1024);
    }
    FinanceCostExportJobs(FinanceService service,FinanceCostExport exporter,LongSupplier clock,Executor executor,
            boolean own,Path tempRoot,long maxFileBytes,long maxRetainedBytes){
        this.service=service;this.exporter=exporter;this.clock=clock;this.executor=executor;
        this.owned=own?(ExecutorService)executor:null;this.tempRoot=tempRoot;
        this.maxFileBytes=maxFileBytes;this.maxRetainedBytes=maxRetainedBytes;
        cleaner=own?Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-cost-export-cleanup");t.setDaemon(true);return t;}):null;
        if(cleaner!=null)cleaner.scheduleWithFixedDelay(this::cleanupExpired,1,1,TimeUnit.MINUTES);
    }
    public Map<String,Object> submit(Scope owner,Filter filter,String source,String recordId,String costStatus,String numbers,String key){
        if(owner==null||text(owner.organizationId()).isBlank()||text(owner.userId()).isBlank())throw bad("请先登录");
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,128}"))throw bad("导出请求编号不正确，请重新打开导出面板");
        Selection selected=normalize(filter,source,recordId,costStatus,numbers);
        if(!selected.filter.shopId().isEmpty())service.ownedShop(companyScope(owner.organizationId()),selected.filter.shopId());
        synchronized(this){
            if(closed)throw bad("导出服务正在关闭，请稍后重新生成");
            prune();
            for(Job job:jobs.values())if(job.owner.equals(owner)&&job.keys.contains(key)){
                if(!job.selection.equals(selected))throw bad("同一个导出请求不能更换筛选条件");
                return view(job);
            }
            for(Job job:jobs.values())if(job.owner.equals(owner)&&job.selection.equals(selected)
                    &&(pending(job)||("READY".equals(job.state)&&clock.getAsLong()-job.completed<REUSE))){
                if(job.keys.size()>=128)throw bad("这份报表已提交，请在导出面板查看任务");
                job.keys.add(key);return view(job);
            }
            if(MaintenanceGate.instance().closed())throw bad("系统正在维护，请稍后重新生成");
            if(jobs.values().stream().filter(j->j.owner.equals(owner)&&pending(j)).count()>=2)throw bad("已有两份报表正在生成，请等待其中一份完成");
            if(jobs.size()+retired.size()>=16)throw bad("导出任务较多，请稍后再试");
            Job job=new Job(owner,selected,key,clock.getAsLong());jobs.put(job.id,job);
            try{MaintenanceGate.execute(executor,()->generate(job));}
            catch(RuntimeException failure){jobs.remove(job.id);throw bad("导出队列已满，请稍后再试");}
            return view(job);
        }
    }
    private static Selection normalize(Filter f,String source,String recordId,String status,String numbers){
        if(f==null)throw bad("请选择有效导出日期");
        String shop=bounded(f.shopId(),200),group=bounded(f.groupId(),36),record=bounded(recordId,200);
        Filter normalized=new Filter(f.start(),f.end(),shop,text(f.platform()).trim().toUpperCase(Locale.ROOT),
                text(f.currency()).trim().toUpperCase(Locale.ROOT),"day",group);
        String kind=FinanceCostExport.kind(FinanceCostReport.scopeKind(text(source).trim(),!record.isEmpty()));
        String selection=String.join(",",new TreeSet<>(FinanceRecordSearch.numbers(numbers)));
        return new Selection(normalized,kind,record,FinanceCostReport.costStatus(text(status).trim()),selection);
    }
    private static String bounded(String input,int length){String result=text(input).trim();if(result.length()>length||result.chars().anyMatch(Character::isISOControl))throw bad("导出筛选条件不正确");return result;}

    private void generate(Job job){
        synchronized(this){if(closed||job.retired)return;job.state="RUNNING";job.generating=true;}
        try{
            Path directory;
            try{directory=Files.createTempDirectory(tempRoot,"finance-cost-export-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}
            catch(UnsupportedOperationException unsupported){directory=Files.createTempDirectory(tempRoot,"finance-cost-export-");}
            synchronized(this){job.directory=directory;}
            Selection selected=job.selection;
            var file=exporter.exportFile(companyScope(job.owner.organizationId()),selected.filter,selected.source,selected.recordId,
                    selected.costStatus,selected.numbers,directory,(processed,total)->{
                        synchronized(this){job.processed=Math.max(0,processed);job.total=Math.max(job.processed,total);}
                    });
            long bytes=validateFile(directory,file);
            synchronized(this){
                job.generating=false;
                if(closed||job.retired){retire(job);return;}
                prune();
                long retained=retainedBytes();
                if(bytes>maxFileBytes||bytes>maxRetainedBytes-retained)throw bad("导出文件超过临时空间上限，请缩小范围或等待旧任务过期");
                job.file=file;job.bytes=bytes;job.state="READY";job.completed=clock.getAsLong();job.processed=file.orders();job.total=file.orders();
            }
        }catch(Exception failure){
            synchronized(this){
                job.generating=false;job.state="FAILED";job.completed=clock.getAsLong();
                job.error=failure instanceof BusinessException?failure.getMessage():"成本报表生成失败，请稍后重新生成；财务数据未改变";
                cleanupFiles(job);if(closed||job.retired)retire(job);
            }
        }
    }
    private long validateFile(Path directory,FinanceCostExport.GeneratedFile file)throws IOException{
        if(file==null||file.path()==null||file.filename()==null||file.filename().contains("/")||file.filename().contains("\\")
                ||file.filename().chars().anyMatch(Character::isISOControl))throw new IOException("Invalid export result");
        Path parent=directory.toAbsolutePath().normalize(),path=file.path().toAbsolutePath().normalize();
        if(!path.startsWith(parent)||path.equals(parent)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid export path");
        for(Path current=path;!current.equals(parent);current=current.getParent())if(Files.isSymbolicLink(current))throw new IOException("Invalid export link");
        if(!Set.of("application/zip","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").contains(file.contentType()))throw new IOException("Invalid export type");
        return Files.size(path);
    }
    public synchronized List<Map<String,Object>> list(Scope owner){
        prune();return jobs.values().stream().filter(job->job.owner.equals(owner)).sorted(Comparator.comparingLong((Job job)->job.created).reversed()).map(this::view).toList();
    }
    public synchronized Map<String,Object> status(Scope owner,String id){prune();return view(require(owner,id));}
    public record Download(String filename,String contentType,long length,InputStream stream) {}
    public synchronized Download download(Scope owner,String id){
        prune();Job job=require(owner,id);
        if(!"READY".equals(job.state))throw new BusinessException(HttpStatus.CONFLICT,"报表尚未生成完成");
        try{
            InputStream input=Files.newInputStream(job.file.path(),LinkOption.NOFOLLOW_LINKS);job.readers++;
            InputStream pinned=new FilterInputStream(input){
                private boolean finished;
                @Override public void close()throws IOException{
                    synchronized(this){if(finished)return;finished=true;}
                    try{super.close();}finally{synchronized(FinanceCostExportJobs.this){job.readers--;if(job.retired)cleanupFiles(job);}}
                }
            };
            return new Download(job.file.filename(),job.file.contentType(),job.bytes,pinned);
        }catch(IOException failure){throw new BusinessException(HttpStatus.GONE,"导出文件已不可用，请重新生成");}
    }
    private Job require(Scope owner,String id){Job job=jobs.get(id);if(job==null||!job.owner.equals(owner))throw new BusinessException(HttpStatus.NOT_FOUND,"导出任务不存在或已过期，请重新生成（服务重启后任务不保留）");return job;}
    private boolean pending(Job job){return "QUEUED".equals(job.state)||"RUNNING".equals(job.state);}
    private synchronized void cleanupExpired(){prune();}
    private void prune(){
        long now=clock.getAsLong();var expired=jobs.values().stream().filter(j->!pending(j)&&now-j.completed>=RETENTION).toList();
        for(Job job:expired){jobs.remove(job.id);retire(job);}
        for(Job job:new ArrayList<>(retired))cleanupFiles(job);
    }
    private long retainedBytes(){return jobs.values().stream().mapToLong(j->j.bytes).sum()+retired.stream().mapToLong(j->j.bytes).sum();}
    private void retire(Job job){job.retired=true;retired.add(job);cleanupFiles(job);}
    /** Directories are created by this task; walk never follows symbolic links. Readers pin cleanup. */
    private void cleanupFiles(Job job){
        if(job.generating||job.readers>0)return;
        if(job.directory!=null){
            try(var paths=Files.walk(job.directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
            catch(IOException failure){return;}
            job.directory=null;job.bytes=0;
        }
        if(job.retired)retired.remove(job);
    }
    private Map<String,Object> view(Job job){
        var result=new LinkedHashMap<String,Object>();var f=job.selection.filter;
        result.put("id",job.id);result.put("status",job.state);result.put("error",job.error);
        result.put("filename",job.file==null?"":job.file.filename());result.put("contentType",job.file==null?"":job.file.contentType());
        result.put("orders",job.file==null?0L:job.file.orders());result.put("lines",job.file==null?0L:job.file.lines());result.put("parts",job.file==null?0:job.file.parts());
        result.put("processed",job.processed);result.put("total",job.total);result.put("createdAt",job.created);result.put("expiresAt",pending(job)?null:job.completed+RETENTION);
        result.put("start",f.start().toString());result.put("end",f.end().toString());result.put("shopId",f.shopId());result.put("groupId",f.groupId());result.put("platform",f.platform());result.put("currency",f.currency());
        result.put("kind",job.selection.source);result.put("recordId",job.selection.recordId);result.put("costStatus",job.selection.costStatus);result.put("numbers",job.selection.numbers);
        return result;
    }
    @PreDestroy @Override public void close(){
        synchronized(this){if(closed)return;closed=true;for(Job job:jobs.values())retire(job);jobs.clear();}
        if(cleaner!=null)cleaner.shutdownNow();
        if(owned!=null)for(Runnable queued:owned.shutdownNow())queued.run(); // Release maintenance tracking without generating files.
    }
}
