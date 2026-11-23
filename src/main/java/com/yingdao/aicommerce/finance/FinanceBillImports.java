package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.DatabaseUserDetailsService;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
class FinanceBillImports {
    private static final int BATCH=256;
    private static final Set<String> REQUIRED=Set.of("external_id","date","amount","currency");
    private static final Set<String> COLUMNS=Set.of("external_id","date","amount","currency","order_id","category","statement_id");
    private static final java.util.regex.Pattern SPOOLED=java.util.regex.Pattern.compile("[0-9a-f-]{36}\\.(upload|part)");
    private Instant sweepAfter=Instant.EPOCH;
    @Value("${APP_FINANCE_BILL_XLSX_PROCESS:false}")
    private boolean workbookProcess;
    @org.springframework.beans.factory.annotation.Autowired
    private FinanceWorkerCapacity capacity;
    private final FinanceRepository repo;
    private final FinanceService finance;
    private final DatabaseUserDetailsService users;
    private final TransactionTemplate publication;
    private final Path root;
    private final Set<String> organizations;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{
        var thread=new Thread(task,"finance-bill-import");
        thread.setDaemon(true);
        return thread;
    });

    FinanceBillImports(FinanceRepository repo,FinanceService finance,DatabaseUserDetailsService users,
            PlatformTransactionManager manager,
            @Value("${FINANCE_STAGED_BILL_IMPORT_ORGANIZATIONS:}") String configured,
            @Value("${FINANCE_STAGED_BILL_IMPORT_ROOT:/data/automation-attachments/finance-bill-imports}") String root){
        this.repo=repo;this.finance=finance;this.users=users;
        this.root=Path.of(root).toAbsolutePath().normalize();
        this.organizations=new HashSet<>(Arrays.asList(configured.split("\\s*,\\s*")));
        publication=new TransactionTemplate(manager);
        publication.setTimeout(75);
        publication.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    boolean enabled(Scope owner){return organizations.contains(owner.organizationId());}

    Map<String,Object> upload(Scope owner,String original,long size,InputStream input)throws IOException{
        try(input){
            if(!enabled(owner))throw bad("当前公司未开启后台账单导入");
            String name=text(original).replace('\\','/');
            name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
            String suffix=name.toLowerCase(Locale.ROOT);
            if(!suffix.endsWith(".csv")&&!suffix.endsWith(".xlsx"))throw bad("请选择 UTF-8 CSV 或 XLSX 文件");
            if(name.length()>250)throw bad("文件名不能超过 250 字符");
            if(size<1||size>FinanceBillImportFile.MAX_BYTES)throw bad("文件不能为空或超过 40 MB");
            Files.createDirectories(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            String id=UUID.randomUUID().toString(),filename=name;
            Path temporary=root.resolve(id+".part");
            try{
                try(var channel=FileChannel.open(temporary,Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
                    var out=Channels.newOutputStream(channel);
                    long copied=0;
                    byte[] buffer=new byte[64*1024];
                    int read;
                    while((read=input.read(buffer))!=-1){
                        if((copied+=read)>size)throw bad("文件大小与上传信息不符");
                        out.write(buffer,0,read);
                    }
                    if(copied!=size)throw bad("文件上传不完整，请重试");
                    channel.force(true);
                }
                Files.move(temporary,file(id),StandardCopyOption.ATOMIC_MOVE);
                repo.transaction(()->{
                    gate();
                    var counts=repo.jdbc().queryForMap("""
                        SELECT COUNT(*) total,COALESCE(SUM(organization_id=? AND actor_id=?),0) owned
                        FROM finance_bill_import_jobs WHERE state NOT IN ('COMPLETE','FAILED','EXPIRED','CANCELED')
                        """,owner.organizationId(),owner.userId());
                    if(number(counts.get("total"))>=20||number(counts.get("owned"))>=3)
                        throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"账单任务较多，请等待现有任务完成");
                    repo.jdbc().update("""
                        INSERT INTO finance_bill_import_jobs(id,organization_id,actor_id,filename,byte_count,state,expires_at)
                        VALUES(?,?,?,?,?,'PREVIEW_QUEUED',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY))
                        """,id,owner.organizationId(),owner.userId(),filename,size);
                    return null;
                });
            }finally{
                Files.deleteIfExists(temporary);
                // A failed commit can have an uncertain outcome. The sweeper checks ownership before removing uploads.
            }
            return Map.of("id",id,"state","PREVIEW_QUEUED","filename",filename);
        }
    }

    List<Map<String,Object>> list(Scope owner){
        return repo.jdbc().queryForList("""
            SELECT id,state,filename,shop_id,row_count,duplicate_file,message,created_at FROM finance_bill_import_jobs
            WHERE organization_id=? AND actor_id=? AND expires_at>UTC_TIMESTAMP(6)
            ORDER BY created_at DESC,id DESC LIMIT 20
            """,owner.organizationId(),owner.userId()).stream().map(this::view).toList();
    }

    Map<String,Object> status(Scope owner,String id){return view(owned(owner,id,false));}

    Map<String,Object> confirm(Scope owner,String id,String shopId,Map<String,Object> selected){
        return repo.transaction(()->{
            var job=owned(owner,id,true);
            var mapping=mapping(selected);
            String serialized=repo.write(mapping),state=text(job.get("state"));
            if(!state.equals("AWAIT_MAPPING")){
                if(shopId!=null&&shopId.equals(job.get("shop_id"))&&job.get("mapping_json")!=null
                        &&mapping.equals(repo.read(text(job.get("mapping_json")))))return view(job);
                throw bad("请等待文件预览完成后确认导入");
            }
            var preview=repo.read(text(job.get("preview_json")));
            int columns=((List<?>)preview.get("columns")).size();
            if(mapping.values().stream().filter(value->!value.isBlank()).anyMatch(value->Integer.parseInt(value)>=columns))
                throw bad("列映射超出文件标题范围");
            var shop=shop(owner,shopId);
            repo.jdbc().update("""
                UPDATE finance_bill_import_jobs SET state='QUEUED',shop_id=?,ledger_user_id=?,platform=?,mapping_json=?,
                next_at=UTC_TIMESTAMP(6),failures=0,message=NULL WHERE id=?
                """,shopId,shop.get("user_id"),shop.get("platform"),serialized,id);
            return view(owned(owner,id,false));
        });
    }

    void discard(Scope owner,String id){
        repo.transaction(()->{
            var job=owned(owner,id,true);
            String state=text(job.get("state"));
            if(state.equals("CANCELED"))return null;
            if(!Set.of("PREVIEW_QUEUED","PREVIEWING","AWAIT_MAPPING").contains(state))
                throw bad("账单已确认导入，无法放弃这份文件");
            repo.jdbc().update("""
                UPDATE finance_bill_import_jobs SET state='CANCELED',preview_json=NULL,lease_token=NULL,lease_until=NULL
                WHERE id=?
                """,id);
            return null;
        });
    }

    private static Map<String,String> mapping(Map<String,Object> selected){
        if(selected==null||!COLUMNS.containsAll(selected.keySet()))throw bad("列映射不正确");
        var mapping=new TreeMap<String,String>();
        selected.forEach((key,value)->{
            String column=text(value);
            if(!column.isBlank()&&(!column.matches("[0-9]{1,3}")||Integer.parseInt(column)>=FinanceBillImportFile.MAX_COLUMNS))
                throw bad("列映射不正确");
            mapping.put(key,column);
        });
        var required=new HashSet<>(REQUIRED);
        if(!text(mapping.get("statement_id")).isBlank())required.remove("external_id");
        if(required.stream().anyMatch(key->text(mapping.get(key)).isBlank()))throw bad("请补齐账单编号、日期、币种和金额的列");
        return mapping;
    }

    private Map<String,Object> owned(Scope owner,String id,boolean lock){
        var found=repo.jdbc().queryForList("""
            SELECT * FROM finance_bill_import_jobs WHERE organization_id=? AND actor_id=? AND id=?
              AND expires_at>UTC_TIMESTAMP(6)
            """+(lock?" FOR UPDATE":""),owner.organizationId(),owner.userId(),id);
        if(found.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"没有找到该账单导入任务");
        return found.getFirst();
    }

    private Map<String,Object> view(Map<String,Object> job){
        var result=new LinkedHashMap<String,Object>();
        result.put("id",job.get("id"));
        result.put("state",job.get("state"));
        result.put("filename",job.get("filename"));
        result.put("shopId",text(job.get("shop_id")));
        result.put("rows",number(job.get("row_count")));
        result.put("duplicate",Boolean.TRUE.equals(job.get("duplicate_file")));
        result.put("message",text(job.get("message")));
        if(job.get("preview_json")!=null&&"AWAIT_MAPPING".equals(job.get("state")))
            result.putAll(repo.read(text(job.get("preview_json"))));
        return result;
    }

    private Map<String,Object> shop(Scope owner,String id){
        if(id==null||id.isBlank())throw bad("请选择财务店铺");
        repo.requireShopWrite(owner,id);
        return repo.jdbc().queryForMap("""
            SELECT user_id,platform FROM finance_connections WHERE organization_id=? AND id=? AND status<>'REMOVED' FOR SHARE
            """,owner.organizationId(),id);
    }

    private void gate(){repo.jdbc().queryForObject("SELECT id FROM finance_bill_import_gate WHERE id=1 FOR UPDATE",Integer.class);}
    private Path file(String id){return root.resolve(id+".upload");}

    @PostConstruct void start(){worker.scheduleWithFixedDelay(this::scheduled,35,2,TimeUnit.SECONDS);}
    @PreDestroy void stop(){worker.shutdownNow();}

    private void scheduled(){
        MaintenanceGate.runIfOpen(()->{
            try(var permit=capacity==null?null:capacity.acquireBulk()){
                if(capacity==null||permit!=null)tick();
            }catch(RuntimeException failure){
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance bill task deferred: {}",failure.getClass().getSimpleName());
            }
        });
    }

    void tick(){
        var job=claim();
        if(job==null){cleanup();sweepOrphans();return;}
        String id=text(job.get("id")),lease=text(job.get("lease_token")),state=text(job.get("state"));
        try{
            Scope owner=currentOwner(job);
            switch(state){
                case "PREVIEWING"->preview(job);
                case "VALIDATING"->validateFile(job,owner);
                case "PUBLISHING"->publish(job,owner);
                default->throw new IllegalStateException("Unexpected bill import state");
            }
        }catch(UsernameNotFoundException missing){
            fail(id,lease,"提交人已停用或已失去财务权限");
        }catch(BusinessException invalid){
            fail(id,lease,invalid.getMessage());
        }catch(IOException|RuntimeException transientFailure){
            retry(job,"账单暂时无法处理，系统将重试");
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance bill task retry: {}",transientFailure.getClass().getSimpleName());
        }
    }

    private Scope currentOwner(Map<String,Object> job){
        var principal=users.loadByUserId(text(job.get("actor_id")));
        if(!principal.isEnabled()||!Objects.equals(principal.organizationId(),job.get("organization_id")))
            throw new UsernameNotFoundException("Bill import actor unavailable");
        finance.requireManagement(principal);
        return new Scope(principal.organizationId(),principal.userId());
    }

    private Map<String,Object> claim(){
        return repo.transaction(()->{
            gate();
            if(Boolean.TRUE.equals(repo.jdbc().queryForObject("""
                SELECT EXISTS(SELECT 1 FROM finance_bill_import_jobs WHERE state IN ('PREVIEWING','VALIDATING','PUBLISHING')
                  AND lease_until>UTC_TIMESTAMP(6))
                """,Boolean.class)))return null;
            var tasks=repo.jdbc().queryForList("""
                SELECT * FROM finance_bill_import_jobs
                WHERE state IN ('PREVIEW_QUEUED','PREVIEWING','QUEUED','VALIDATING','STAGED','PUBLISHING')
                  AND next_at<=UTC_TIMESTAMP(6) AND expires_at>UTC_TIMESTAMP(6)
                  AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6))
                ORDER BY next_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if(tasks.isEmpty())return null;
            var job=tasks.getFirst();
            String state=switch(text(job.get("state"))){
                case "PREVIEW_QUEUED","PREVIEWING"->"PREVIEWING";
                case "QUEUED","VALIDATING"->"VALIDATING";
                default->"PUBLISHING";
            };
            String lease=UUID.randomUUID().toString();
            repo.jdbc().update("""
                UPDATE finance_bill_import_jobs SET state=?,lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)
                WHERE id=?
                """,state,lease,job.get("id"));
            job.put("state",state);job.put("lease_token",lease);
            return job;
        });
    }

    private static final class PreviewRead extends RuntimeException {}

    private void preview(Map<String,Object> job)throws IOException{
        String id=text(job.get("id")),lease=text(job.get("lease_token"));
        var rows=new ArrayList<List<String>>();
        try{
            FinanceBillImportFile.rows(file(id),text(job.get("filename")),(line,row)->{
                rows.add(row.stream().map(String::trim).map(cell->cell.substring(0,Math.min(160,cell.length()))).toList());
                if(rows.size()==7)throw new PreviewRead();
            });
        }catch(PreviewRead complete){}
        if(rows.size()<2)throw bad("文件需要标题行和至少一行数据");
        repo.jdbc().update("""
            UPDATE finance_bill_import_jobs SET state='AWAIT_MAPPING',preview_json=?,message=NULL,lease_token=NULL,lease_until=NULL,failures=0
            WHERE id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
            """,repo.write(Map.of("columns",rows.getFirst(),"sample",rows.subList(1,rows.size()))),id,lease);
    }

    private void validateFile(Map<String,Object> job,Scope owner)throws IOException{
        String id=text(job.get("id")),lease=text(job.get("lease_token")),filename=text(job.get("filename"));
        String shopId=text(job.get("shop_id")),platform=text(job.get("platform"));
        Scope ledger=new Scope(owner.organizationId(),text(job.get("ledger_user_id")));
        var mapping=repo.read(text(job.get("mapping_json")));
        String digest=digest(file(id),repo.write(new TreeMap<>(mapping)));
        Long old=imported(ledger,shopId,digest);
        if(old!=null){
            repo.transaction(()->{
                lockLease(id,lease);
                requireSameShop(owner,shopId,job);
                complete(id,lease,old,true);
                return null;
            });
            return;
        }
        clearStage(id,lease);
        var batch=new ArrayList<Object[]>(BATCH);
        var unique=new HashSet<String>();
        boolean[] header={false};
        java.util.function.BiConsumer<Integer,List<String>> validate=(line,row)->{
            if(!header[0]){header[0]=true;return;}
            if(unique.size()>=FinanceBillImportFile.MAX_ROWS)throw bad("第 "+line+" 行超过单份账单 100,000 条上限");
            for(String column:mapping.keySet()){
                String cell=FinanceImport.value(row,mapping,column);
                if(cell.startsWith("[FORMULA]")||cell.startsWith("[UNSAFE_NUMBER]"))
                    throw bad("第 "+line+" 行映射列含公式或数字已失去精度，请使用原始文本");
            }
            var entry=FinanceImport.mappedEntry(shopId,platform,"BILL",mapping,row,line,filename);
            if(entry.externalId().length()>128||entry.orderId().length()>128||entry.category().length()>100)
                throw bad("第 "+line+" 行账单编号、订单编号或类别过长");
            if(entry.date().getYear()<1000||entry.date().getYear()>9999)throw bad("第 "+line+" 行日期超出支持范围");
            if(!unique.add(entry.externalId()))throw FinanceImport.duplicateRow(line);
            batch.add(new Object[]{id,line,id(ledger,entry),entry.externalId(),entry.orderId(),entry.category(),
                    Date.valueOf(entry.date()),entry.currency(),entry.amount(),repo.write(entry.raw())});
            if(batch.size()==BATCH)stage(id,lease,batch,unique.size());
        };
        if(workbookProcess&&filename.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            FinanceBillImportProcess.rows(file(id),filename,validate);
        else FinanceBillImportFile.rows(file(id),filename,validate);
        if(unique.isEmpty())throw bad("文件没有数据");
        if(!batch.isEmpty())stage(id,lease,batch,unique.size());
        repo.jdbc().update("""
            UPDATE finance_bill_import_jobs SET state='STAGED',content_hash=?,row_count=?,lease_token=NULL,lease_until=NULL,
              next_at=UTC_TIMESTAMP(6),failures=0,message=NULL
            WHERE id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)
            """,digest,unique.size(),id,lease);
    }

    private void clearStage(String id,String lease){
        while(Boolean.TRUE.equals(repo.transaction(()->{
            lockLease(id,lease);
            return repo.jdbc().update("DELETE FROM finance_bill_import_stage WHERE job_id=? LIMIT 1024",id)==1024;
        }))){}
        repo.jdbc().update("UPDATE finance_bill_import_jobs SET row_count=0 WHERE id=? AND lease_token=?",id,lease);
    }

    private void stage(String id,String lease,List<Object[]> batch,int rows){
        repo.transaction(()->{
            lockLease(id,lease);
            repo.jdbc().batchUpdate("""
                INSERT INTO finance_bill_import_stage(job_id,source_row,record_id,external_id,order_id,category,
                  record_date,currency,amount,raw_json) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,batch);
            repo.jdbc().update("""
                UPDATE finance_bill_import_jobs SET row_count=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)
                WHERE id=? AND lease_token=?
                """,rows,id,lease);
            return null;
        });
        batch.clear();
    }

    private void lockLease(String id,String lease){
        if(repo.jdbc().queryForList("""
            SELECT id FROM finance_bill_import_jobs WHERE id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE
            """,id,lease).isEmpty())throw bad("账单任务已由新的工作器接手");
    }

    private void publish(Map<String,Object> job,Scope owner){
        String id=text(job.get("id")),lease=text(job.get("lease_token")),shopId=text(job.get("shop_id"));
        String digest=text(job.get("content_hash"));
        int count=(int)number(job.get("row_count"));
        publication.executeWithoutResult(status->{
            lockLease(id,lease);
            var shop=requireSameShop(owner,shopId,job);
            Scope ledger=new Scope(owner.organizationId(),text(shop.get("user_id")));
            Long old=imported(ledger,shopId,digest);
            if(old!=null){complete(id,lease,old,true);return;}
            repo.jdbc().update("""
                INSERT INTO finance_imports(id,organization_id,user_id,shop_id,file_name,content_hash,row_count,kind)
                VALUES(?,?,?,?,?,?,?,'BILL')
                """,id,owner.organizationId(),ledger.userId(),shopId,job.get("filename"),digest,count);
            // Same settlement goods rule as FinanceRepository.billGoods, in the publication snapshot for the entire file.
            repo.jdbc().update("""
                INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,
                  category,status,record_date,currency,amount,revenue,source,raw_json)
                SELECT s.record_id,?,?,?,?,'BILL',s.external_id,s.order_id,s.external_id,s.category,'IMPORTED',
                  s.record_date,s.currency,s.amount,g.goods,'IMPORT',s.raw_json
                FROM finance_bill_import_stage s
                LEFT JOIN finance_records existing ON existing.id=s.record_id
                LEFT JOIN LATERAL (
                  SELECT CASE WHEN COUNT(*)>0 AND COUNT(r.revenue)=COUNT(*) THEN SUM(r.revenue) END goods
                  FROM finance_records r WHERE r.organization_id=? AND r.shop_id=? AND r.kind='SETTLEMENT'
                    AND r.source='API' AND r.statement_id=s.external_id AND r.currency=s.currency
                ) g ON TRUE
                WHERE s.job_id=? AND (existing.id IS NULL OR NOT (
                  CAST(existing.order_id AS BINARY)<=>CAST(s.order_id AS BINARY)
                  AND CAST(existing.statement_id AS BINARY)<=>CAST(s.external_id AS BINARY)
                  AND CAST(existing.category AS BINARY)<=>CAST(s.category AS BINARY)
                  AND existing.status='IMPORTED' AND existing.record_date<=>s.record_date
                  AND CAST(existing.currency AS BINARY)<=>CAST(s.currency AS BINARY)
                  AND existing.amount<=>s.amount AND existing.revenue<=>g.goods
                  AND existing.impressions IS NULL AND existing.clicks IS NULL AND existing.conversions IS NULL))
                ORDER BY s.record_id
                ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),statement_id=VALUES(statement_id),category=VALUES(category),
                  status=VALUES(status),record_date=VALUES(record_date),currency=VALUES(currency),amount=VALUES(amount),
                  revenue=VALUES(revenue),impressions=NULL,clicks=NULL,conversions=NULL,raw_json=VALUES(raw_json),updated_at=UTC_TIMESTAMP(6)
                """,owner.organizationId(),ledger.userId(),shopId,shop.get("platform"),owner.organizationId(),shopId,id);
            complete(id,lease,count,false);
            finance.refreshAfterCommit(owner);
        });
    }

    private Map<String,Object> requireSameShop(Scope owner,String shopId,Map<String,Object> job){
        var current=shop(owner,shopId);
        if(!Objects.equals(current.get("user_id"),job.get("ledger_user_id"))||!Objects.equals(current.get("platform"),job.get("platform")))
            throw bad("财务店铺的归属已变更，请重新选择店铺并上传");
        return current;
    }

    private Long imported(Scope ledger,String shop,String digest){
        var rows=repo.jdbc().queryForList("""
            SELECT row_count FROM finance_imports WHERE organization_id=? AND user_id=? AND shop_id=?
              AND kind='BILL' AND content_hash=?
            """,Long.class,ledger.organizationId(),ledger.userId(),shop,digest);
        return rows.isEmpty()?null:rows.getFirst();
    }

    static String digest(Path path,String mapping)throws IOException{
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException missing){throw new IllegalStateException(missing);}
        try(var out=Base64.getEncoder().wrap(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){
            Files.copy(path,out);
        }
        return HexFormat.of().formatHex(digest.digest(mapping.getBytes(StandardCharsets.UTF_8)));
    }

    private void complete(String id,String lease,long rows,boolean duplicate){
        repo.jdbc().update("""
            UPDATE finance_bill_import_jobs SET state='COMPLETE',row_count=?,duplicate_file=?,message=NULL,lease_token=NULL,
              lease_until=NULL,preview_json=NULL WHERE id=? AND lease_token=?
            """,rows,duplicate,id,lease);
    }

    private void fail(String id,String lease,String message){
        repo.jdbc().update("""
            UPDATE finance_bill_import_jobs SET state='FAILED',message=?,lease_token=NULL,lease_until=NULL,preview_json=NULL
            WHERE id=? AND lease_token=?
            """,message.substring(0,Math.min(message.length(),500)),id,lease);
    }

    private void retry(Map<String,Object> job,String message){
        String id=text(job.get("id")),lease=text(job.get("lease_token"));
        if(number(job.get("failures"))>=4){fail(id,lease,"多次处理失败，请重新上传账单");return;}
        repo.jdbc().update("""
            UPDATE finance_bill_import_jobs SET failures=failures+1,lease_token=NULL,lease_until=NULL,message=?,
              next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND)
            WHERE id=? AND lease_token=?
            """,message,id,lease);
    }

    private void cleanup(){
        repo.jdbc().update("""
            UPDATE finance_bill_import_jobs SET state='EXPIRED',preview_json=NULL,lease_token=NULL,lease_until=NULL
            WHERE expires_at<=UTC_TIMESTAMP(6) AND state NOT IN ('COMPLETE','FAILED','EXPIRED','CANCELED')
              AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) LIMIT 10
            """);
        var rows=repo.jdbc().queryForList("""
            SELECT id,file_cleaned FROM finance_bill_import_jobs WHERE state IN ('COMPLETE','FAILED','EXPIRED','CANCELED')
              AND (file_cleaned=FALSE OR expires_at<=UTC_TIMESTAMP(6)) ORDER BY created_at LIMIT 4
            """);
        for(var row:rows){
            String id=text(row.get("id"));
            if(!Boolean.TRUE.equals(row.get("file_cleaned"))){
                int affected=repo.jdbc().update("DELETE FROM finance_bill_import_stage WHERE job_id=? LIMIT 4096",id);
                if(affected==4096)continue;
                try{Files.deleteIfExists(file(id));}
                catch(IOException unavailable){continue;}
                repo.jdbc().update("UPDATE finance_bill_import_jobs SET file_cleaned=TRUE WHERE id=?",id);
            }
            repo.jdbc().update("DELETE FROM finance_bill_import_jobs WHERE id=? AND file_cleaned=TRUE AND expires_at<=UTC_TIMESTAMP(6)",id);
        }
    }
    private void sweepOrphans(){
        Instant now=Instant.now();
        if(now.isBefore(sweepAfter)||!Files.isDirectory(root))return;
        sweepAfter=now.plus(Duration.ofMinutes(15));
        Instant cutoff=now.minus(Duration.ofHours(2));
        try(var paths=Files.list(root)){
            var candidates=paths.filter(path->{
                String name=path.getFileName().toString();
                return SPOOLED.matcher(name).matches()||name.startsWith("work-");
            }).filter(path->{
                try{return Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);}
                catch(IOException missing){return false;}
            }).limit(24).toList();
            for(Path path:candidates){
                String name=path.getFileName().toString();
                if(name.startsWith("work-")&&Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)){
                    Files.deleteIfExists(path.resolve("strings.bin"));
                    Files.deleteIfExists(path.resolve("offsets.bin"));
                    if(name.startsWith("work-bill-decoder-")){
                        Path decoder=path.resolve("xlsx");
                        if(Files.isDirectory(decoder,LinkOption.NOFOLLOW_LINKS)){
                            Files.deleteIfExists(decoder.resolve("strings.bin"));
                            Files.deleteIfExists(decoder.resolve("offsets.bin"));
                        }
                        Files.deleteIfExists(decoder);
                        Files.deleteIfExists(path.resolve("rows.bin"));
                    }
                    Files.deleteIfExists(path);
                }else if(name.endsWith(".part")||(name.endsWith(".upload")&&!Boolean.TRUE.equals(repo.jdbc().queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM finance_bill_import_jobs WHERE id=?)",Boolean.class,name.substring(0,36))))){
                    Files.deleteIfExists(path);
                }
            }
        }catch(IOException failure){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance bill spool cleanup deferred");
        }
    }

}
