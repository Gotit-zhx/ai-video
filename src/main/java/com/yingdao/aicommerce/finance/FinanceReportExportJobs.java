package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
class FinanceReportExportJobs implements AutoCloseable {
    private static final Duration RETENTION=Duration.ofMinutes(30);
    private static final int MAX_FILE=32*1024*1024;
    private static final long MAX_RETAINED=64L*1024*1024;
    private final FinanceRepository repo;
    private final FinanceReportReadiness readiness;
    private final FinanceReportFile generator;
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"finance-report-exports");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.AbortPolicy());

    private static final class Job {
        final String id=UUID.randomUUID().toString(),key;
        final Scope owner;
        final FinanceReportSelection selection;
        final FinanceReportReadiness.Status receipt;
        final Instant created=Instant.now();
        Instant completed;
        String state="QUEUED",error="";
        byte[] bytes;
        Job(Scope owner,FinanceReportSelection selection,String key,FinanceReportReadiness.Status receipt){
            this.owner=owner;this.selection=selection;this.key=key;this.receipt=receipt;
        }
        String filename(){
            String date=selection.allDates()?"全部收货日期":selection.filter().start()+"至"+selection.filter().end();
            return (receipt.ready()?"":"部分数据-")+selection.type().label+"-"+date+(selection.csv()?".csv":".xlsx");
        }
        boolean pending(){return completed==null;}
    }
    record Download(String filename,String contentType,byte[] bytes) {}

    FinanceReportExportJobs(FinanceRepository repo,FinanceReportReadiness readiness,FinanceReportFile generator){
        this.repo=repo;this.readiness=readiness;this.generator=generator;
    }

    Map<String,Object> submit(Scope owner,Scope company,FinanceReportSelection selection,String key,String revision,boolean partial){
        if(!text(key).matches("[A-Za-z0-9_-]{16,128}"))throw bad("导出请求编号不正确");
        synchronized(this){
            prune();
            Job prior=byKey(owner,key,selection);
            if(prior!=null)return view(prior);
        }
        var status=approve(company,selection,revision,partial);
        synchronized(this){
            prune();
            Job prior=byKey(owner,key,selection);
            if(prior!=null)return view(prior);
            if(jobs.size()>=64||jobs.values().stream().filter(j->j.owner.equals(owner)&&j.pending()).count()>=2)
                throw conflict("导出任务较多，请等待已有文件生成");
            Job job=new Job(owner,selection,key,status);
            jobs.put(job.id,job);
            try{MaintenanceGate.execute(executor,()->generate(job,company));}
            catch(RejectedExecutionException full){jobs.remove(job.id);throw conflict("导出队列已满，请稍后重试");}
            return view(job);
        }
    }

    FinanceReportReadiness.Status approve(Scope company,FinanceReportSelection selection,String revision,boolean partial){
        var status=readiness.assess(company,selection);
        if(!status.revision().equals(revision))throw conflict("数据状态已更新，请确认后重新导出");
        if(!status.ready()&&!(partial&&status.state()==FinanceReportReadiness.State.PARTIAL&&!selection.csv()))
            throw conflict("当前数据尚未就绪，请检查报表状态");
        return status;
    }

    FinanceCostExportJobs.Approval costApproval(Scope company,FinanceReportSelection selected,String revision,boolean partial){
        var status=approve(company,selected,revision,partial);
        return new FinanceCostExportJobs.Approval(status,status.notes(selected),()->{
            if(!status.revision().equals(readiness.assess(company,selected).revision()))
                throw conflict("排队或生成期间数据版本已变化，请按最新状态重新导出");
        });
    }

    private void generate(Job job,Scope company){
        synchronized(this){job.state="RUNNING";}
        try{
            byte[] bytes;
            if(job.selection.cny()){
                // The existing FX loader may extend a public reference snapshot.
                requireVersion(job,readiness.assess(company,job.selection));
                bytes=generator.generate(company,job.owner,job.selection,job.receipt);
                requireVersion(job,readiness.assess(company,job.selection));
            }else{
                bytes=repo.readSnapshot(120,()->{
                    requireVersion(job,readiness.assessInSnapshot(company,job.selection));
                    return generator.generate(company,job.owner,job.selection,job.receipt);
                });
            }
            synchronized(this){
                prune();
                long retained=jobs.values().stream().filter(j->j.bytes!=null).mapToLong(j->j.bytes.length).sum();
                if(bytes.length>MAX_FILE||retained+bytes.length>MAX_RETAINED)throw bad("文件或待下载结果较大，请缩小范围后重试");
                job.bytes=bytes;job.state="READY";job.completed=Instant.now();
            }
        }catch(RuntimeException failure){
            synchronized(this){
                job.state="FAILED";job.completed=Instant.now();
                job.error=failure instanceof BusinessException?failure.getMessage():"报表生成失败，请重新检查并提交";
            }
        }
    }

    private static void requireVersion(Job job,FinanceReportReadiness.Status current){
        if(!job.receipt.revision().equals(current.revision()))throw conflict("排队或生成期间数据版本已变化，请按最新状态重新导出");
    }
    private Job byKey(Scope owner,String key,FinanceReportSelection selection){
        for(var job:jobs.values())if(job.owner.equals(owner)&&job.key.equals(key)){
            if(!job.selection.equals(selection))throw conflict("同一请求不能更换导出条件");
            return job;
        }
        return null;
    }
    synchronized List<Map<String,Object>> list(Scope owner){
        prune();
        return jobs.values().stream().filter(j->j.owner.equals(owner)).sorted(Comparator.comparing((Job j)->j.created).reversed())
            .map(FinanceReportExportJobs::view).toList();
    }
    synchronized Download download(Scope owner,String id){
        prune();
        Job job=jobs.get(id);
        if(job==null||!job.owner.equals(owner))throw new BusinessException(HttpStatus.NOT_FOUND,"文件不存在或已过期");
        if(!"READY".equals(job.state))throw conflict("文件尚未生成");
        return new Download(job.filename(),job.selection.csv()?"text/csv;charset=UTF-8":FinanceReportFile.XLSX,job.bytes);
    }
    private void prune(){
        Instant cutoff=Instant.now().minus(RETENTION);
        jobs.values().removeIf(j->j.completed!=null&&!j.completed.isAfter(cutoff));
    }
    private static Map<String,Object> view(Job job){
        var value=new LinkedHashMap<String,Object>();
        value.put("id",job.id);value.put("status",job.state);value.put("error",job.error);
        value.put("filename",job.filename());value.put("createdAt",job.created);
        value.put("expiresAt",job.pending()?null:job.completed.plus(RETENTION));
        value.put("dataStatus",job.receipt);
        for(String field:List.of("start","end","shopId","groupId","platform","currency","displayCurrency","view"))
            value.put(field,job.selection.get(field));
        return value;
    }
    static BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
    @PreDestroy @Override public void close(){executor.shutdown();}
}
