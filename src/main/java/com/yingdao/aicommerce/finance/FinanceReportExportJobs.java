package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.DatabaseUserDetailsService;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
class FinanceReportExportJobs implements AutoCloseable {
    private static final long MAX_GENERAL=32L<<20, MAX_COST=128L<<20;
    private final FinanceRepository repo;
    private final FinanceController guard;
    private final FinanceReportReadiness readiness;
    private final FinanceReportFile generator;
    private final FinanceCostExport costs;
    private final DatabaseUserDetailsService users;
    private final FinanceExportTaskStore store;
    private final Path root;
    @org.springframework.beans.factory.annotation.Autowired
    private FinanceWorkerCapacity capacity;
    private Set<String> enabledOrganizations=Set.of();
    private final ConcurrentMap<String,String> active=new ConcurrentHashMap<>();
    private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(3,r->{
        var thread=new Thread(r,"finance-durable-export");thread.setDaemon(true);return thread;
    });
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(FinanceReportExportJobs.class);

    private record Output(Path path,String name,String type,FinanceReportReadiness.Status receipt){}
    record Download(String filename,String contentType,long length,InputStream stream){}
    private static final class Waiting extends RuntimeException{
        final FinanceReportReadiness.Status receipt;
        final Scope company;
        final FinanceReportSelection selection;
        Waiting(String message,FinanceReportReadiness.Status receipt,Scope company,FinanceReportSelection selection){
            super(message);this.receipt=receipt;this.company=company;this.selection=selection;
        }
    }

    FinanceReportExportJobs(FinanceRepository repo,FinanceController guard,FinanceReportReadiness readiness,FinanceReportFile generator,
            FinanceCostExport costs,DatabaseUserDetailsService users,
            @Value("${finance.report-export-directory:/data/automation-attachments/finance-report-exports}") String directory){
        this.repo=repo;this.guard=guard;this.readiness=readiness;this.generator=generator;this.costs=costs;this.users=users;
        store=new FinanceExportTaskStore(repo);root=Path.of(directory).toAbsolutePath().normalize();
    }
    @Value("${finance.durable-export-organizations:${FINANCE_DURABLE_EXPORT_ORGANIZATIONS:}}")
    void configureOrganizations(String organizations){
        enabledOrganizations=Arrays.stream(organizations.split("[,\\s]+")).filter(id->!id.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    boolean enabled(Scope owner){return enabledOrganizations.contains(owner.organizationId());}
    boolean contains(Scope owner,String id){return store.contains(owner,id);}

    @PostConstruct void start(){
        for(var lane:FinanceExportTaskStore.Lane.values())
            workers.scheduleWithFixedDelay(()->safe(()->scheduled(lane)),2,3,TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(()->safe(this::expire),1,1,TimeUnit.MINUTES);
        // Jobs already inside the maintenance gate may finish while it is closed.
        workers.scheduleWithFixedDelay(this::renewActive,15,15,TimeUnit.SECONDS);
    }
    private void scheduled(FinanceExportTaskStore.Lane lane){
        boolean small=lane==FinanceExportTaskStore.Lane.SMALL;
        try(var permit=capacity==null?null:small?capacity.acquire():capacity.acquireBulk()){
            if(capacity==null||permit!=null)tick(lane);
        }
    }
    private void safe(Runnable action){
        try{MaintenanceGate.runIfOpen(action);}
        catch(RuntimeException failure){LOG.warn("Finance export worker will retry: {}",failure.getClass().getSimpleName());}
    }

    Map<String,Object> submit(Scope owner,Scope company,FinanceReportSelection selection,String key,boolean partial){
        requireSelection(company,selection);
        var filter=selection.filter();
        boolean small=!filter.shopId().isBlank()&&!selection.allDates()&&ChronoUnit.DAYS.between(filter.start(),filter.end())<7;
        var job=store.submit(owner,key,new FinanceExportTaskStore.Request(selection.type().name(),selection.parameters(),partial,
                small?FinanceExportTaskStore.Lane.SMALL:FinanceExportTaskStore.Lane.STANDARD));
        return view(job);
    }

    void tick(FinanceExportTaskStore.Lane lane){
        var job=store.claim(lane);
        if(job.isEmpty())return;
        String id=text(job.get("id")),lease=text(job.get("lease_token"));
        active.put(id,lease);
        String stem=id+"-"+lease;
        Path directory=root.resolve("run-"+stem),file=root.resolve("result-"+stem);
        var owner=new Scope(text(job.get("organization_id")),text(job.get("user_id")));
        boolean partial=Boolean.TRUE.equals(job.get("allow_partial"));
        String saved=text(job.get("stored_name"));
        try{
            var selected=selection(job);
            var company=authorize(owner,selected);
            if(!saved.isBlank()){
                publishPrepared(id,lease,job,saved);
                return;
            }
            var prepared=generator.prepareRates(selected);
            var privateDirectory=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
            Files.createDirectories(root,privateDirectory);
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid report storage root");
            Files.createDirectory(directory,privateDirectory);
            var output=prepared.type()==FinanceReportSelection.Type.ORDER_COST?
                    orderCosts(owner,company,prepared,directory,id,lease,partial):
                    otherReport(owner,company,prepared,directory,partial);
            authorize(owner,selected);
            long bytes=Files.size(output.path());
            if(bytes>(prepared.type()==FinanceReportSelection.Type.ORDER_COST?MAX_COST:MAX_GENERAL)){
                store.fail(id,lease,"文件超过保留上限，请缩小筛选范围后重新导出");return;
            }
            if(!store.owns(id,lease))return;
            if(!Files.isRegularFile(output.path(),LinkOption.NOFOLLOW_LINKS)||!directory.equals(output.path().toAbsolutePath().normalize().getParent()))
                throw new IOException("Invalid report output path");
            Files.move(output.path(),file,StandardCopyOption.ATOMIC_MOVE);
            var result=store.publish(id,lease,new FinanceExportTaskStore.File(output.name(),output.type(),file.getFileName().toString(),bytes),receipt(output.receipt()));
            if(result==FinanceExportTaskStore.Publication.LEASE_LOST)Files.deleteIfExists(file);
        }catch(Waiting pending){
            store.waitFor(id,lease,pending.getMessage(),receipt(pending.receipt),45);
            readiness.requestUpdates(pending.company,pending.selection,pending.receipt);
        }catch(UsernameNotFoundException missing){
            fail(id,lease,saved,"账户已停用或已移除，无法导出");
        }catch(BusinessException failure){
            if(Set.of(HttpStatus.FORBIDDEN,HttpStatus.UNAUTHORIZED,HttpStatus.NOT_FOUND).contains(failure.getStatus()))
                fail(id,lease,saved,"当前账户或筛选范围的权限已变更，请重新选择");
            else if(failure.getMessage().contains("超过")||failure.getMessage().contains("最多"))
                fail(id,lease,saved,failure.getMessage());
            else store.waitFor(id,lease,safeReason(failure),null,60);
        }catch(IOException unavailable){
            store.waitFor(id,lease,"导出文件暂时无法写入，系统将重试",null,60);
        }catch(RuntimeException unavailable){
            store.waitFor(id,lease,"暂时无法读取报表，系统将重试",null,60);
            LOG.warn("Finance export attempt deferred: {}",unavailable.getClass().getSimpleName());
        }finally{
            active.remove(id,lease);
            try{deleteTree(directory);}
            catch(IOException unavailable){LOG.warn("Finance export temporary cleanup deferred");}
            // A failed commit can have an uncertain outcome. Only a later retention
            // sweep removes an unreferenced file after the lease is old enough.
        }
    }

    private void publishPrepared(String id,String lease,Map<String,Object> job,String saved)throws IOException{
        long bytes=number(job.get("stored_bytes"));
        if(!ownedResult(id,saved)||!Files.isRegularFile(root.resolve(saved),LinkOption.NOFOLLOW_LINKS)
                ||Files.size(root.resolve(saved))!=bytes||job.get("receipt_json")==null){
            if(store.retryMissingFile(id,lease))deleteHeldFile(id,saved);
            return;
        }
        var result=new FinanceExportTaskStore.File(text(job.get("file_name")),text(job.get("content_type")),saved,bytes);
        store.publish(id,lease,result,repo.read(text(job.get("receipt_json"))));
    }

    private void fail(String id,String lease,String saved,String message){
        if(store.fail(id,lease,message))deleteHeldFile(id,saved);
    }

    private void deleteHeldFile(String id,String saved){
        if(!ownedResult(id,saved))return;
        try{Files.deleteIfExists(root.resolve(saved));}
        catch(IOException unavailable){LOG.warn("Finance report temporary cleanup deferred");}
    }

    private static boolean ownedResult(String id,String file){
        return storedName(file)&&file.startsWith("result-"+id+"-");
    }

    private void renewActive(){
        try{active.forEach(store::renew);}
        catch(RuntimeException failure){LOG.warn("Finance export lease renewal deferred: {}",failure.getClass().getSimpleName());}
    }

    private Output orderCosts(Scope owner,Scope company,FinanceReportSelection selected,Path directory,String id,String lease,boolean partial){
        long[] last={0};
        java.util.function.BiConsumer<Long,Long> progress=(processed,total)->{
            long now=System.nanoTime();
            if(last[0]==0||processed.equals(total)||now-last[0]>=TimeUnit.SECONDS.toNanos(5)){
                if(!store.progress(id,lease,processed,total))throw conflict("本次导出任务已重新领取");
                last[0]=now;
            }
        };
        boolean wideSettledRange="SETTLED_ORDER".equals(selected.get("kind"))
            &&java.time.temporal.ChronoUnit.DAYS.between(selected.filter().start(),selected.filter().end())>=7;
        var approved=wideSettledRange?costs.exportWithOrderEvidence(company,selected,directory,progress,
            ()->authorize(owner,selected),facts->requireReady(company,selected,partial,readiness.assessInSnapshot(company,selected,facts)))
            :costs.exportApprovedFile(company,selected,directory,progress,()->{
            var started=Instant.now();
            authorize(owner,selected);
            return ready(company,selected,partial).takenAt(started);
        });
        var file=approved.file();
        return new Output(file.path(),file.filename(),file.contentType(),approved.status());
    }

    private Output otherReport(Scope owner,Scope company,FinanceReportSelection selected,Path directory,boolean partial)throws IOException{
        record Report(byte[] data,FinanceReportReadiness.Status receipt){}
        var report=repo.readSnapshot(120,()->{
            var started=Instant.now();
            authorize(owner,selected);
            var status=ready(company,selected,partial).takenAt(started);
            return new Report(generator.generateFromSnapshot(company,owner,selected,status),status);
        });
        Path file=directory.resolve("report");
        Files.write(file,report.data(),StandardOpenOption.CREATE_NEW);
        String name=name(selected,report.receipt().ready());
        return new Output(file,name,selected.csv()?"text/csv;charset=UTF-8":FinanceReportFile.XLSX,report.receipt());
    }

    private FinanceReportReadiness.Status ready(Scope company,FinanceReportSelection selected,boolean partial){
        return requireReady(company,selected,partial,readiness.assessInSnapshot(company,selected));
    }

    private FinanceReportReadiness.Status requireReady(Scope company,FinanceReportSelection selected,boolean partial,
                                                       FinanceReportReadiness.Status status){
        boolean allowed=status.ready()||partial&&!selected.csv()&&(status.state()==FinanceReportReadiness.State.PARTIAL
                ||selected.type()==FinanceReportSelection.Type.ORDER_COST&&status.state()==FinanceReportReadiness.State.PROCESSING);
        if(!allowed){
            String reason=status.gaps().isEmpty()?"当前数据尚未就绪，系统将重新核对":status.gaps().getFirst().detail();
            throw new Waiting(reason,status,company,selected);
        }
        return status;
    }

    private static Map<String,Object> receipt(FinanceReportReadiness.Status status){
        var value=new LinkedHashMap<String,Object>();
        value.put("state",status.state().name());value.put("revision",status.revision());
        value.put("checkedAt",status.checkedAt().toString());
        value.put("dataAsOf",status.dataAsOf()==null?null:status.dataAsOf().toString());
        value.put("extractedAt",status.extractedAt()==null?null:status.extractedAt().toString());
        value.put("gapCount",status.gapCount());value.put("gaps",status.gaps());
        return value;
    }

    Map<String,Object> statusView(Scope owner,FinanceReportReadiness.Status current){
        var result=receipt(current);result.put("durableExports",enabled(owner));return result;
    }

    private static String safeReason(BusinessException failure){
        String message=failure.getMessage();
        if(message.contains("正在生成")||message.contains("历史汇率快照")||message.contains("导出超时"))return message;
        return "导出暂未生成，系统将重新核对";
    }

    private Scope authorize(Scope owner,FinanceReportSelection selection){
        var principal=users.loadByUserId(owner.userId());
        if(!principal.isEnabled()||!owner.equals(guard.personal(principal)))
            throw new BusinessException(HttpStatus.FORBIDDEN,"导出权限已变更");
        var company=guard.s(principal);
        requireSelection(company,selection);
        return company;
    }

    private void requireSelection(Scope company,FinanceReportSelection selection){
        var filter=selection.filter();
        String group=filter.groupId();
        if(!filter.shopId().isBlank()&&repo.jdbc().queryForList("""
            SELECT id FROM finance_connections WHERE organization_id=? AND id=? AND status<>'REMOVED'
              AND (?='' OR (?='UNGROUPED' AND COALESCE(group_id,'')='') OR (?<>'UNGROUPED' AND group_id=?))
            """,company.organizationId(),filter.shopId(),group,group,group,group).isEmpty())
            throw new BusinessException(HttpStatus.NOT_FOUND,"所选店铺不可用");
        if(!Set.of("","UNGROUPED").contains(group)&&repo.jdbc().queryForList("""
            SELECT id FROM finance_groups WHERE organization_id=? AND id=?
            """,company.organizationId(),group).isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"所选分组不可用");
        String record=selection.get("recordId");
        if(!record.isBlank()&&repo.jdbc().queryForList("""
            SELECT r.id FROM finance_records r WHERE r.organization_id=? AND r.id=? AND
            """+FinanceRepository.visible("r"),company.organizationId(),record).isEmpty())
            throw new BusinessException(HttpStatus.NOT_FOUND,"所选财务记录不可用");
    }

    private FinanceReportSelection selection(Map<String,Object> job){
        var input=new TreeMap<String,String>();
        repo.read(text(job.get("selection_json"))).forEach((k,v)->input.put(k,text(v)));
        return FinanceReportSelection.parse(guard,text(job.get("report_type")),input);
    }

    List<Map<String,Object>> list(Scope owner){return store.list(owner).stream().map(this::view).toList();}

    Download download(Scope owner,String id){
        var row=store.find(owner,id);
        if(!"READY".equals(row.get("state")))throw conflict("文件尚未生成");
        try{authorize(owner,selection(row));}
        catch(UsernameNotFoundException missing){throw new BusinessException(HttpStatus.FORBIDDEN,"导出权限已变更");}
        String file=text(row.get("stored_name"));
        if(!storedName(file))throw new BusinessException(HttpStatus.NOT_FOUND,"文件不存在或已过期");
        try{
            return new Download(text(row.get("file_name")),text(row.get("content_type")),number(row.get("stored_bytes")),
                    Files.newInputStream(root.resolve(file),LinkOption.NOFOLLOW_LINKS));
        }catch(IOException missing){throw new BusinessException(HttpStatus.NOT_FOUND,"文件目前无法读取");}
    }

    private Map<String,Object> view(Map<String,Object> job){
        var row=new LinkedHashMap<String,Object>();
        row.put("id",job.get("id"));row.put("status",job.get("state"));row.put("error",job.get("error_message"));
        row.put("durable",true);
        var selected=selection(job);String filename=text(job.get("file_name"));
        row.put("filename",filename.isBlank()?name(selected,true):filename);
        row.put("createdAt",instant(job.get("created_at")));row.put("expiresAt",instant(job.get("expires_at")));
        row.put("processed",job.get("processed_count"));row.put("total",job.get("total_count"));
        row.put("dataStatus",job.get("receipt_json")==null?null:repo.read(text(job.get("receipt_json"))));
        for(String field:List.of("start","end","shopId","groupId","platform","currency","displayCurrency","view"))
            row.put(field,selected.get(field));
        return row;
    }

    private static String name(FinanceReportSelection selected,boolean ready){
        String dates=selected.allDates()?"全部收货日期":selected.filter().start()+"至"+selected.filter().end();
        return (ready?"":"部分数据-")+selected.type().label+"-"+dates+(selected.csv()?".csv":".xlsx");
    }

    void expire(){
        for(var row:store.expired()){
            String file=text(row.get("stored_name"));
            try{
                if(storedName(file))Files.deleteIfExists(root.resolve(file));
                store.removeExpired(text(row.get("id")));
            }catch(IOException unavailable){LOG.warn("Finance report retention cleanup deferred");}
        }
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return;
        try(var entries=Files.newDirectoryStream(root)){
            Instant older=Instant.now().minus(Duration.ofMinutes(24));int removed=0;
            for(Path path:entries){
                if(removed>=32)break;
                String file=path.getFileName().toString();
                boolean stored=storedName(file);
                if(!stored&&!file.matches("run-[a-f0-9-]{73}"))continue;
                if(!Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(older))continue;
                if(!stored||!store.references(file)){deleteTree(path);removed++;}
            }
        }catch(IOException unavailable){LOG.warn("Finance report temporary cleanup deferred");}
    }

    private static boolean storedName(String file){return file.matches("result-[a-f0-9-]{73}");}
    private static void deleteTree(Path path)throws IOException{
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(path)){
            for(var item:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(item);
        }
    }

    static BusinessException conflict(String message){return FinanceExportTaskStore.conflict(message);}
    @PreDestroy @Override public void close(){workers.shutdownNow();}
}
