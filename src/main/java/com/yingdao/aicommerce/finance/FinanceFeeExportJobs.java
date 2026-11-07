package com.yingdao.aicommerce.finance;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import com.yingdao.aicommerce.common.exception.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Bounded, short-lived export results. No ledger writes, persistent files or application logs. */
@Service
public class FinanceFeeExportJobs {
    private static final long RETENTION=30*60_000L,MAX_BYTES=64L*1024*1024;
    private final FinanceService service;
    private final Executor executor;private final ExecutorService owned;
    private final LongSupplier clock;
    private final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
    private static final class Job{
        final String id=UUID.randomUUID().toString(),key,fingerprint;
        final Set<String> keys=new HashSet<>();
        final Scope owner;final Filter filter;
        final long created;String state="QUEUED",error="";long completed;byte[] bytes;
        Job(Scope owner,Filter f,String key,long now){
            this.owner=owner;filter=f;this.key=key;created=now;
            keys.add(key);
            fingerprint=f.toString();
        }
        String filename(){return "平台费用全体导出-"+filter.start()+"-"+filter.end()+".xlsx";}
    }
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceFeeExportJobs(FinanceService service){
        this(service,System::currentTimeMillis,new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"finance-fee-export");t.setDaemon(true);return t;},
                new ThreadPoolExecutor.AbortPolicy()),true);
    }
    FinanceFeeExportJobs(FinanceService service,LongSupplier clock,Executor executor,boolean own){
        this.service=service;this.clock=clock;this.executor=executor;owned=own?(ExecutorService)executor:null;
    }
    public synchronized Map<String,Object> submit(Scope owner,Filter f,String key){
        if(key==null||!key.matches("[A-Za-z0-9_-]{16,128}"))throw bad("导出请求编号不正确，请重新打开导出面板");
        prune();
        Job candidate=new Job(owner,f,key,clock.getAsLong());
        for(Job job:jobs.values())if(job.owner.equals(owner)&&job.keys.contains(key)){
            if(!job.fingerprint.equals(candidate.fingerprint))throw bad("同一个导出请求不能更换筛选条件");
            return view(job);
        }
        for(Job job:jobs.values())if(job.owner.equals(owner)&&job.fingerprint.equals(candidate.fingerprint)
                &&(pending(job)||("READY".equals(job.state)&&clock.getAsLong()-job.completed<60_000))){
            if(job.keys.size()>=128)throw bad("这份报表已提交，请在导出面板查看任务");
            job.keys.add(key);return view(job);
        }
        if(jobs.values().stream().filter(job->job.owner.equals(owner)&&pending(job)).count()>=2)
            throw bad("已有两份报表正在生成，请等待其中一份完成");
        if(jobs.size()>=32)throw bad("导出任务较多，请稍后再试");
        jobs.put(candidate.id,candidate);
        try{com.yingdao.aicommerce.ops.MaintenanceGate.execute(executor,()->generate(candidate));}
        catch(RejectedExecutionException full){jobs.remove(candidate.id);throw bad("导出队列已满，请稍后再试");}
        return view(candidate);
    }
    private void generate(Job job){
        synchronized(this){job.state="RUNNING";}
        try{
            var facts=service.feeExportFacts(companyScope(job.owner.organizationId()),job.filter);
            byte[] bytes=FinanceFeeMatrix.workbook(job.filter,facts);
            synchronized(this){
                prune();
                long retained=jobs.values().stream().filter(j->j.bytes!=null).mapToLong(j->j.bytes.length).sum();
                if(bytes.length>8*1024*1024||retained+bytes.length>MAX_BYTES)throw bad("报表较大或导出结果较多，请缩小范围后再试");
                job.bytes=bytes;job.state="READY";job.completed=clock.getAsLong();
            }
        }catch(Exception failure){
            synchronized(this){
                job.state="FAILED";job.completed=clock.getAsLong();
                job.error=failure instanceof BusinessException?failure.getMessage():"报表生成失败，请稍后重新生成；财务数据未改变";
            }
        }
    }
    public synchronized List<Map<String,Object>> list(Scope owner){
        prune();return jobs.values().stream().filter(job->job.owner.equals(owner))
                .sorted(Comparator.comparingLong((Job job)->job.created).reversed()).limit(20).map(this::view).toList();
    }
    public synchronized Map<String,Object> status(Scope owner,String id){prune();return view(require(owner,id));}
    public record Download(String filename,byte[] bytes){}
    public synchronized Download download(Scope owner,String id){
        prune();Job job=require(owner,id);
        if(!"READY".equals(job.state))throw new BusinessException(HttpStatus.CONFLICT,"报表尚未生成完成");
        return new Download(job.filename(),job.bytes);
    }
    private Job require(Scope owner,String id){
        Job job=jobs.get(id);if(job==null||!job.owner.equals(owner))throw new BusinessException(HttpStatus.NOT_FOUND,"导出任务不存在或已过期，请重新生成");
        return job;
    }
    private boolean pending(Job job){return "QUEUED".equals(job.state)||"RUNNING".equals(job.state);}
    private void prune(){long now=clock.getAsLong();jobs.values().removeIf(job->!pending(job)&&now-job.completed>=RETENTION);}
    private Map<String,Object> view(Job job){
        var out=new LinkedHashMap<String,Object>();
        out.put("id",job.id);out.put("status",job.state);out.put("filename",job.filename());out.put("error",job.error);
        out.put("createdAt",job.created);out.put("expiresAt",job.completed==0?null:job.completed+RETENTION);
        out.put("start",job.filter.start().toString());out.put("end",job.filter.end().toString());
        out.put("shopId",job.filter.shopId());out.put("groupId",job.filter.groupId());out.put("platform",job.filter.platform());
        out.put("currency",job.filter.currency());out.put("displayCurrency","ORIGINAL");
        return out;
    }
    @PreDestroy public void close(){if(owned!=null)owned.shutdownNow();}
}
