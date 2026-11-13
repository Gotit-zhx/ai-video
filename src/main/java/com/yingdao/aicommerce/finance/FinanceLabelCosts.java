package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Bounded invoice ingestion and generation-based publication. Web reads never parse XLSX files. */
@Service
public class FinanceLabelCosts {
    static final int BATCH=500;
    static final long MAX_FILE=96L*1024*1024,MAX_QUEUED=512L*1024*1024;
    final FinanceRepository repo;final FinanceService service;final Path directory;
    final Semaphore uploads=new Semaphore(2);
    long nextFileCleanup;
    final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-label-invoices");t.setDaemon(true);return t;});
    @Autowired public FinanceLabelCosts(FinanceRepository repo,FinanceService service,
        @Value("${finance.label-import-directory:/data/automation-attachments/finance-label-imports}") String directory){
        this.repo=repo;this.service=service;this.directory=Path.of(directory).toAbsolutePath().normalize();
    }
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(()->com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::tick),25,2,TimeUnit.SECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();}
    static void dirty(FinanceRepository repo,String org){
        repo.jdbc().update("""
            INSERT INTO finance_label_state(organization_id,requested_revision) VALUES(?,1)
            ON DUPLICATE KEY UPDATE requested_revision=requested_revision+1,error_message=NULL,retry_after=NULL,updated_at=UTC_TIMESTAMP(6)
            """,org);
    }
    static void sourceChanged(FinanceRepository repo,String org){
        dirty(repo,org);
        repo.jdbc().update("UPDATE finance_label_state SET source_revision=source_revision+1 WHERE organization_id=?",org);
    }
    static void orderArrived(FinanceRepository repo,Scope scope,Entry entry){
        if(!entry.kind().equals("ORDER")||!entry.source().equals("API"))return;
        // A consistent indexed read never waits for the invoice generation's row
        // locks. Keep the durable notification in the order transaction, but do
        // not modify invoice rows or the company-wide publication state here.
        boolean needed=Boolean.TRUE.equals(repo.jdbc().queryForObject("""
            SELECT EXISTS(SELECT 1 FROM finance_label_invoice_rows FORCE INDEX(idx_label_invoice_order)
              WHERE organization_id=? AND order_id=? AND matched_shop_id IS NULL)
            """,Boolean.class,scope.organizationId(),entry.orderId()));
        if(needed)repo.jdbc().update("""
            INSERT INTO finance_label_arrivals(organization_id,order_id) VALUES(?,?)
            ON DUPLICATE KEY UPDATE revision=revision+1
            """,scope.organizationId(),entry.orderId());
    }
    int arrivals(){
        return repo.transaction(()->{
            var jobs=repo.jdbc().queryForList("""
                SELECT organization_id,order_id FROM finance_label_arrivals
                ORDER BY organization_id,order_id LIMIT 200 FOR UPDATE SKIP LOCKED
                """);
            var changed=new LinkedHashSet<String>();
            for(var job:jobs){
                if(repo.jdbc().update("""
                    UPDATE finance_label_invoice_rows r FORCE INDEX(idx_label_invoice_order)
                    STRAIGHT_JOIN finance_label_imports i ON i.id=r.import_id
                    SET r.match_checked=FALSE,r.match_revision=r.match_revision+1
                    WHERE r.organization_id=? AND r.order_id=? AND r.matched_shop_id IS NULL
                      AND i.status IN ('READY','PREPARED')
                    """,job.get("organization_id"),job.get("order_id"))>0)
                    changed.add(text(job.get("organization_id")));
                repo.jdbc().update("DELETE FROM finance_label_arrivals WHERE organization_id=? AND order_id=?",
                    job.get("organization_id"),job.get("order_id"));
            }
            for(String org:changed)dirty(repo,org);
            return jobs.size();
        });
    }
    public Map<String,Object> upload(Scope scope,String actor,String name,InputStream input)throws Exception{
        if(!name.toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw bad("请上传仓库月度 XLSX 账单");
        if(!uploads.tryAcquire())throw bad("正在接收其他账单，请稍后重试");
        Path path=null;boolean retained=false;
        try{
            Files.createDirectories(directory);
            if(Files.getFileStore(directory).getUsableSpace()<FinanceInvoiceWorkbook.MAX_EXPANDED+MAX_FILE)
                throw bad("服务器临时空间不足，请释放空间后重试；已有账单不受影响");
            path=directory.resolve(UUID.randomUUID()+".xlsx");
            var digest=MessageDigest.getInstance("SHA-256");long bytes=0;
            try(var out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){
                byte[] buffer=new byte[64*1024];int n;
                while((n=input.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    if((bytes+=n)>MAX_FILE)throw bad("单份账单最大 96 MB，请拆分上传");
                    out.write(buffer,0,n);digest.update(buffer,0,n);
                }
            }
            if(bytes==0)throw bad("文件为空");
            String sha=HexFormat.of().formatHex(digest.digest()),storage=path.getFileName().toString(),filename=name.substring(0,Math.min(name.length(),250));
            long size=bytes;
            var result=repo.transaction(()->{
                // The singleton row serializes quota reservations only; parsing does not hold this lock.
                repo.jdbc().queryForList("SELECT id FROM finance_label_worker_lock WHERE id=1 FOR UPDATE");
                var old=repo.jdbc().queryForList("SELECT id,status,storage_name FROM finance_label_imports WHERE organization_id=? AND content_hash=? FOR UPDATE",scope.organizationId(),sha);
                if(!old.isEmpty()&&!Set.of("REMOVED","EXPIRED","FAILED").contains(text(old.getFirst().get("status"))))
                    return Map.<String,Object>of("id",old.getFirst().get("id"),"duplicate",true,"status",old.getFirst().get("status"));
                long queued=number(repo.jdbc().queryForObject("SELECT COALESCE(SUM(stored_bytes),0) FROM finance_label_imports WHERE storage_name<>''",Long.class));
                if(queued+size>MAX_QUEUED)throw bad("后台账单队列已达 512 MB，请等待现有文件处理完成后再上传");
                dirty(repo,scope.organizationId());
                String id=old.isEmpty()?UUID.randomUUID().toString():text(old.getFirst().get("id"));
                repo.jdbc().update("""
                    INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,created_by)
                    VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE source_name=VALUES(source_name),storage_name=VALUES(storage_name),
                      stored_bytes=VALUES(stored_bytes),status='QUEUED',row_count=0,matched_count=0,amount_cny=0,
                      unmatched_count=0,review_count=0,billing_failed_count=0,conflict_count=0,
                      error_message=NULL,attempts=0,updated_at=UTC_TIMESTAMP(6)
                    """,id,scope.organizationId(),filename,sha,storage,size,actor);
                return Map.<String,Object>of("id",id,"duplicate",false,"status","QUEUED",
                    "replacedStorage",old.isEmpty()?"":text(old.getFirst().get("storage_name")));
            });
            retained=!Boolean.TRUE.equals(result.get("duplicate"));
            String replaced=text(result.get("replacedStorage"));
            if(replaced.matches("[a-f0-9-]{36}\\.xlsx"))Files.deleteIfExists(directory.resolve(replaced));
            result=new LinkedHashMap<>(result);result.remove("replacedStorage");
            return result;
        }finally{
            if(!retained&&path!=null)Files.deleteIfExists(path);
            uploads.release();
        }
    }
    public void remove(Scope scope,String id){
        repo.transaction(()->{
            sourceChanged(repo,scope.organizationId());
            var rows=repo.jdbc().queryForList("SELECT status FROM finance_label_imports WHERE organization_id=? AND id=? FOR UPDATE",scope.organizationId(),id);
            if(rows.isEmpty())throw bad("未找到本公司的账单");
            String status=text(rows.getFirst().get("status"));
            if(Set.of("PARSING","PREPARED").contains(status))throw bad("账单正在校验，请完成后移除");
            if(!status.equals("REMOVED"))repo.jdbc().update("UPDATE finance_label_imports SET status='REMOVE_QUEUED',updated_at=UTC_TIMESTAMP(6) WHERE id=?",id);
            return null;
        });
    }
    public void retry(Scope scope,String id){
        repo.transaction(()->{
            dirty(repo,scope.organizationId());
            if(repo.jdbc().update("UPDATE finance_label_imports SET status='QUEUED',attempts=0,error_message=NULL,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=? AND status='FAILED' AND storage_name<>''",scope.organizationId(),id)!=1)
                throw bad("这份账单不能直接重试，请重新上传原文件");
            return null;
        });
    }
    public void rematch(Scope scope){repo.transaction(()->{
        dirty(repo,scope.organizationId());
        repo.jdbc().update("UPDATE finance_label_state SET rematch_all=TRUE WHERE organization_id=?",scope.organizationId());
        return null;
    });}
    /** Page source rows before joining display information; never sort the whole invoice with wide joined rows. */
    Map<String,Object> sourcePage(Scope scope,Filter filter,Map<String,String> query){
        String file=query.getOrDefault("fileId",""),status=query.getOrDefault("matchStatus","");
        int page=(int)Math.max(1,Math.min(1_000_000,number(query.getOrDefault("page","1"))));
        // Retired UI filter from an already-open browser is treated as "all".
        if(status.equals("BILLING_FAILED"))status="";
        if(!Set.of("","MATCHED","UNMATCHED","REVIEW","CONFLICT").contains(status))throw bad("匹配状态不正确");
        var shops=repo.jdbc().queryForList("SELECT id,platform,group_id,status FROM finance_connections WHERE organization_id=?",scope.organizationId());
        var args=new ArrayList<Object>();String where="1=1";
        if(status.equals("CONFLICT"))where+=" AND EXISTS(SELECT 1 FROM finance_label_state ls JOIN finance_label_order_totals lc ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation WHERE ls.organization_id=r.organization_id AND lc.shop_id=r.matched_shop_id AND lc.order_id=r.order_id AND lc.pending_count>0)";
        else if(!status.isBlank()){where+=" AND r.match_status=?";args.add(status);}
        boolean restricted=!filter.shopId().isBlank()||!filter.groupId().isBlank()||!filter.platform().isBlank();
        var selected=shops.stream().filter(s->!"REMOVED".equals(s.get("status")))
            .filter(s->filter.shopId().isBlank()||filter.shopId().equals(text(s.get("id"))))
            .filter(s->filter.platform().isBlank()||filter.platform().equals(text(s.get("platform"))))
            .filter(s->filter.groupId().isBlank()||("UNGROUPED".equals(filter.groupId())?text(s.get("group_id")).isBlank():filter.groupId().equals(text(s.get("group_id")))))
            .map(s->text(s.get("id"))).toList();
        if(restricted){
            boolean includeUnbound="UNGROUPED".equals(filter.groupId())&&filter.shopId().isBlank()&&filter.platform().isBlank();
            String allowed=selected.isEmpty()?"FALSE":"r.matched_shop_id IN ("+String.join(",",Collections.nCopies(selected.size(),"?"))+")";
            where+=" AND ("+allowed+(includeUnbound?" OR r.matched_shop_id IS NULL":"")+")";args.addAll(selected);
        }else{
            var removed=shops.stream().filter(s->"REMOVED".equals(s.get("status"))).map(s->text(s.get("id"))).toList();
            if(!removed.isEmpty()){
                where+=" AND (r.matched_shop_id IS NULL OR r.matched_shop_id NOT IN ("+String.join(",",Collections.nCopies(removed.size(),"?"))+"))";args.addAll(removed);
            }
        }
        var selection=FinanceRecordSearch.orders(new FinanceRepository.Query(where,args),"r.order_id",query.get("numbers"));
        String countSource="finance_label_invoice_rows r"+("1=1".equals(selection.where())?" FORCE INDEX(uk_label_file_event)":"");
        var fileArgs=new ArrayList<Object>();fileArgs.add(scope.organizationId());
        String fileWhere="organization_id=? AND status NOT IN ('REMOVED','EXPIRED')";
        if(!file.isBlank()){fileWhere+=" AND id=?";fileArgs.add(file);}
        // Only IDs authorized for this organization enter source reads. Count narrow
        // source indexes, then page within each file's primary key in upload order.
        var sources=repo.jdbc().queryForList("SELECT id FROM finance_label_imports WHERE "+fileWhere+" ORDER BY created_at DESC,id",fileArgs.toArray());
        long count=0,offset=(page-1L)*50;var records=new ArrayList<Map<String,Object>>(50);
        for(var source:sources){
            var rowArgs=new ArrayList<Object>();rowArgs.add(source.get("id"));rowArgs.addAll(selection.args());
            String rowWhere="r.import_id=? AND "+selection.where();
            long size=number(repo.jdbc().queryForObject("SELECT COUNT(*) FROM "+countSource+" WHERE "+rowWhere,Long.class,rowArgs.toArray()));
            count+=size;
            if(offset>=size){offset-=size;continue;}
            if(records.size()>=50)continue;
            rowArgs.add(50-records.size());rowArgs.add(offset);offset=0;
            records.addAll(repo.jdbc().queryForList("""
                SELECT r.import_id,r.source_row,r.order_id,r.shop_hint,r.customer,r.warehouse,r.document_no,r.transaction_date,
                  r.amount_cny,r.match_status,r.match_note,r.matched_shop_id,r.billing_status,r.fee_type,r.source_sheet,
                  r.unit_price_cny,r.billed_quantity,i.source_name,i.status import_status,
                  COALESCE(NULLIF(c.display_name,''),c.name,'') shop_name,c.platform,c.region,c.group_id,
                  COALESCE(lc.pending_count,0) conflict_count
                FROM (SELECT r.* FROM finance_label_invoice_rows r FORCE INDEX(PRIMARY) WHERE
                """+rowWhere+" ORDER BY r.source_row LIMIT ? OFFSET ?) r JOIN finance_label_imports i ON i.id=r.import_id"+
                " LEFT JOIN finance_connections c ON c.organization_id=r.organization_id AND c.id=r.matched_shop_id"+
                join("r.organization_id","r.matched_shop_id","r.order_id")+" ORDER BY r.source_row",rowArgs.toArray()));
        }
        return Map.of("rows",records,"total",count,"page",page,"pageSize",50);
    }
    public Map<String,Object> workspace(Scope scope,Filter filter,Map<String,String> query){
        var details=sourcePage(scope,filter,query);
        if("true".equals(query.get("detailsOnly")))return details;
        var files=repo.jdbc().queryForList("""
            SELECT id,source_name,status,source_type,customer,currency,period_start,period_end,row_count,matched_count,
              unmatched_count,review_count,billing_failed_count,conflict_count,amount_cny,error_message,created_at,updated_at FROM finance_label_imports
            WHERE organization_id=? AND status<>'REMOVED' ORDER BY created_at DESC,id LIMIT 200
            """,scope.organizationId());
        var q=repo.query(scope,filter,"r");
        var periodArgs=new ArrayList<Object>();periodArgs.add(scope.organizationId());periodArgs.addAll(q.args());
        var period=repo.jdbc().queryForMap("""
            SELECT COUNT(*) orders,COALESCE(SUM(lc.amount_cny),0) amount_cny,
              COALESCE(SUM(lc.pending_count),0) conflicts,COALESCE(SUM(lc.shipment_count),0) shipments
            FROM finance_label_state ls JOIN finance_label_order_totals lc
              ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation
            JOIN LATERAL (
              SELECT r0.id,r0.organization_id,r0.shop_id,r0.order_id,r0.kind,r0.source,r0.status,
                r0.record_date,r0.platform,r0.category,r0.currency
              FROM finance_records r0 FORCE INDEX(idx_finance_settled_order)
              WHERE r0.organization_id=lc.organization_id AND r0.shop_id=lc.shop_id AND r0.order_id=lc.order_id
                AND
            """+FinanceCostReport.confirmed("r0")+" AND (r0.platform<>'TIKTOK_SHOP' OR r0.category='ORDER')"
              +" ORDER BY r0.record_date,r0.id LIMIT 1) r ON TRUE WHERE ls.organization_id=? AND "
              +FinanceOrderCharges.normalOrder("r")+" AND "+q.where(),periodArgs.toArray());
        var states=repo.jdbc().queryForList("SELECT requested_revision,applied_revision,error_message FROM finance_label_state WHERE organization_id=?",scope.organizationId());
        var state=states.isEmpty()?Map.<String,Object>of():states.getFirst();
        var result=new LinkedHashMap<String,Object>(details);
        result.putAll(Map.of("files",files,"period",period,"state",state,
            "processing",number(state.get("requested_revision"))>number(state.get("applied_revision")),
            "basis","贴单金额取订单费用明细的金额（或仓库账单的操作费），固定人民币。参考号绑定平台订单；重复来源去重，同日同仓正式计费优先于零金额占位。汇总按订单首次结算日期，刷单不计入。"));
        return result;
    }
    Path source(Map<String,Object> job){
        String name=text(job.get("storage_name"));
        if(!name.matches("[a-f0-9-]{36}\\.xlsx"))throw bad("账单临时文件已清理，请重新上传");
        return directory.resolve(name);
    }
    void renew(String token){
        if(Thread.currentThread().isInterrupted()||repo.jdbc().update("UPDATE finance_label_worker_lock SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 3 MINUTE) WHERE id=1 AND lease_token=?",token)!=1)
            throw new IllegalStateException("Invoice worker lease lost");
    }
    public void tick(){
        String token=UUID.randomUUID().toString(),org="",id="";
        boolean locked=false;
        try{
            locked=repo.jdbc().update("""
                UPDATE finance_label_worker_lock SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 3 MINUTE)
                WHERE id=1 AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
                """,token)==1;
            if(!locked)return;
            // Rebuilding faster than retired generations are deleted grows the
            // derived table without bound. Finish cleanup before making another
            // generation; the currently published generation remains readable.
            cleanup(token);
            if(!cleanupGenerations(token))return;
            // Coalesce notifications before one generation build. Each transaction
            // is bounded; a crash leaves uncommitted notifications available.
            for(int batch=0;batch<10;batch++){renew(token);if(arrivals()<200)break;}
            var jobs=repo.jdbc().queryForList("""
                SELECT i.* FROM finance_label_imports i LEFT JOIN finance_label_state s ON s.organization_id=i.organization_id
                WHERE i.status IN ('QUEUED','PARSING') AND (s.retry_after IS NULL OR s.retry_after<=UTC_TIMESTAMP(6))
                ORDER BY i.created_at,i.id LIMIT 1
                """);
            if(!jobs.isEmpty()){
                var job=jobs.getFirst();org=text(job.get("organization_id"));id=text(job.get("id"));
                parse(job,token);
            }
            var scopes=repo.jdbc().queryForList("SELECT organization_id,requested_revision FROM finance_label_state WHERE requested_revision>applied_revision AND (retry_after IS NULL OR retry_after<=UTC_TIMESTAMP(6)) ORDER BY updated_at LIMIT 1");
            if(!scopes.isEmpty()){
                org=text(scopes.getFirst().get("organization_id"));id="";
                requireRebuildSpace();
                reconcile(org,number(scopes.getFirst().get("requested_revision")),token);
            }
            cleanup(token);
            cleanupGenerations(token);
        }catch(Exception failure){
            if(!locked)return;
            if(Thread.currentThread().isInterrupted())return; // Leave PARSING durable so restart automatically replays it.
            boolean databaseFailure=failure instanceof org.springframework.dao.DataAccessException;
            String message=failure instanceof com.yingdao.aicommerce.common.exception.BusinessException?text(failure.getMessage())
                :failure instanceof org.springframework.dao.TransientDataAccessException?"数据库暂忙，请稍后重试；已有生效数据保留":"账单处理暂未完成，可重试；已有生效数据保留";
            message=message.substring(0,Math.min(480,message.length()));
            try{
                if(!id.isBlank())repo.jdbc().update("UPDATE finance_label_imports SET status=IF(? AND attempts<3,'QUEUED','FAILED'),error_message=?,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='PARSING'",databaseFailure,message,id);
                if(!org.isBlank())repo.jdbc().update("UPDATE finance_label_state SET error_message=?,retry_after=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE organization_id=?",message,org);
            }catch(RuntimeException ignored){}
        }finally{
            if(locked)try{repo.jdbc().update("UPDATE finance_label_worker_lock SET lease_token=NULL,lease_until=NULL WHERE id=1 AND lease_token=?",token);}catch(RuntimeException ignored){}
        }
    }
    void parse(Map<String,Object> job,String token)throws Exception{
        String id=text(job.get("id")),org=text(job.get("organization_id"));
        if(repo.jdbc().update("UPDATE finance_label_imports SET status='PARSING',attempts=attempts+1,row_count=0,error_message=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status IN ('QUEUED','PARSING')",id)!=1)return;
        // A restart replays only this unpublished source. Its partial rows have never affected reports.
        while(repo.jdbc().update("DELETE FROM finance_label_invoice_rows WHERE import_id=? LIMIT 1000",id)>0)renew(token);
        var batch=new ArrayList<FinanceLabelInvoice.Line>(BATCH);
        Path scratch=directory.resolve("parse-"+id);
        Files.createDirectories(directory);
        var result=FinanceLabelInvoice.parse(source(job),scratch,line->{
            batch.add(line);
            if(batch.size()>=BATCH){writeBatch(org,id,batch,token);batch.clear();}
        });
        if(!batch.isEmpty())writeBatch(org,id,batch,token);
        renew(token);
        repo.transaction(()->{
            repo.jdbc().update("""
                UPDATE finance_label_imports SET status='PREPARED',source_type=?,customer=?,period_start=?,period_end=?,row_count=?,
                  amount_cny=?,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='PARSING'
                """,result.type(),result.customer(),result.start(),result.end(),result.rows(),result.amount(),id);
            // A previous file may have published while this file was still queued.
            // Preparation therefore creates its own revision, in the same transaction.
            sourceChanged(repo,org);
            return null;
        });
    }
    void writeBatch(String org,String id,List<FinanceLabelInvoice.Line> lines,String token){
        renew(token);
        repo.transaction(()->{
            String tuple="("+String.join(",",Collections.nCopies(20,"?"))+")";var values=new ArrayList<Object>();
            for(var r:lines)Collections.addAll(values,id,r.sourceRow(),org,r.eventKey(),r.orderId(),r.shopHint(),r.customer(),r.warehouse(),r.document(),r.date(),r.amount(),
                r.sourcePriority()==1?r.eventKey():hash("flat-row:"+r.sourceRow()),r.bucketKey(),r.sourcePriority(),r.billingPriority(),r.billingStatus(),r.feeType(),r.sheet(),r.unitPrice(),r.quantity());
            try{
                repo.jdbc().update("""
                    INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,order_id,shop_hint,
                      customer,warehouse,document_no,transaction_date,amount_cny,row_key,bucket_key,source_priority,billing_priority,
                      billing_status,fee_type,source_sheet,unit_price_cny,billed_quantity) VALUES
                    """+String.join(",",Collections.nCopies(lines.size(),tuple)),values.toArray());
            }catch(org.springframework.dao.DuplicateKeyException e){throw bad("账单内存在重复 WMS 出库单号，请核对后重新上传");}
            repo.jdbc().update("UPDATE finance_label_imports SET row_count=row_count+?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",lines.size(),id);
            return null;
        });
    }
    void match(String org,String token){
        String cursor="";int row=0;
        boolean full=Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT rematch_all FROM finance_label_state WHERE organization_id=?",Boolean.class,org));
        for(;;){
            renew(token);
            var lines=repo.jdbc().queryForList("""
                SELECT r.* FROM finance_label_invoice_rows r FORCE INDEX(%s) STRAIGHT_JOIN finance_label_imports i ON i.id=r.import_id
                WHERE r.organization_id=? AND i.status IN ('READY','PREPARED') AND r.matched_shop_id IS NULL
                  %s
                  AND (r.import_id>? OR (r.import_id=? AND r.source_row>?))
                ORDER BY r.import_id,r.source_row LIMIT 500
                """.formatted(full?"idx_label_match_cursor":"idx_label_unchecked_cursor",full?"":"AND r.match_checked=FALSE"),org,cursor,cursor,row);
            if(lines.isEmpty())break;
            var ids=lines.stream().map(r->text(r.get("order_id"))).filter(v->!v.isBlank()).distinct().toList();
            var candidates=new HashMap<String,List<Map<String,Object>>>();
            if(!ids.isEmpty()){
                var args=new ArrayList<Object>(ids);args.add(org);
                for(var r:repo.jdbc().queryForList("""
                    SELECT DISTINCT r.shop_id,r.order_id,c.name,c.display_name
                    FROM (
                    """+String.join(" UNION ALL ",Collections.nCopies(ids.size(),"SELECT ? order_id"))+"""
                    ) wanted STRAIGHT_JOIN finance_connections c ON c.organization_id=? AND c.status<>'REMOVED'
                    STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_settled_order)
                      ON r.organization_id=c.organization_id AND r.shop_id=c.id AND r.kind='ORDER'
                      AND r.order_id=wanted.order_id AND r.source='API'
                    """,args.toArray()))
                    candidates.computeIfAbsent(text(r.get("order_id")),k->new ArrayList<>()).add(r);
            }
            var updates=new ArrayList<Object[]>();
            for(var line:lines){
                var found=candidates.getOrDefault(text(line.get("order_id")),List.of());
                if(found.size()>1){
                    String hint=FinanceOrderCharges.normal(text(line.get("shop_hint")));
                    found=found.stream().filter(r->!hint.isBlank()&&(hint.equals(FinanceOrderCharges.normal(text(r.get("name"))))||hint.equals(FinanceOrderCharges.normal(text(r.get("display_name")))))).toList();
                }
                boolean matched=found.size()==1;
                updates.add(new Object[]{matched?found.getFirst().get("shop_id"):null,matched?"MATCHED":candidates.containsKey(text(line.get("order_id")))?"REVIEW":"UNMATCHED",
                    matched?"按平台订单号唯一匹配":text(line.get("order_id")).isBlank()?"参考号为空，无法自动绑定":candidates.containsKey(text(line.get("order_id")))?"同号订单不唯一，未自动分配":"尚未同步到对应平台订单，后续同步后自动匹配",line.get("import_id"),line.get("source_row"),line.get("match_revision")});
            }
            repo.transaction(()->{
                String selected="SELECT ? matched_shop_id,? match_status,? match_note,? import_id,? source_row,? match_revision";
                repo.jdbc().update("UPDATE finance_label_invoice_rows r JOIN ("+String.join(" UNION ALL ",Collections.nCopies(updates.size(),selected))+
                    ") m ON m.import_id=r.import_id AND m.source_row=r.source_row SET r.matched_shop_id=m.matched_shop_id,r.match_status=m.match_status,r.match_note=m.match_note,r.match_checked=TRUE"+
                    " WHERE r.matched_shop_id IS NULL AND r.match_revision=m.match_revision",updates.stream().flatMap(Arrays::stream).toArray());
                // A prepared file has no previous bindings. Publish bounded progress without
                // rescanning all source rows on every batch or on every browser poll.
                var matched=new HashMap<String,Long>();
                for(var update:updates)if(update[0]!=null)matched.merge(text(update[3]),1L,Long::sum);
                for(var entry:matched.entrySet())repo.jdbc().update(
                    "UPDATE finance_label_imports SET matched_count=matched_count+?,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='PREPARED'",
                    entry.getValue(),entry.getKey());
                return null;
            });
            cursor=text(lines.getLast().get("import_id"));row=(int)number(lines.getLast().get("source_row"));
        }
    }
    void reconcile(String org,long revision,String token){
        long sourceRevision=number(repo.jdbc().queryForObject("SELECT source_revision FROM finance_label_state WHERE organization_id=?",Long.class,org));
        match(org,token);renew(token);
        String generation=UUID.randomUUID().toString();
        // MySQL performs the indexed grouping, with disk-backed temporary tables as needed.
        // No order collection or full workbook is loaded into the application heap.
        timedUpdate("""
            INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count)
            WITH sources AS (
              SELECT r.event_key,r.bucket_key,r.source_priority,
                CASE WHEN r.billing_priority=0 THEN IF(r.fee_type='',1,2) ELSE r.billing_priority END billing_priority,
                r.amount_cny,r.order_id,r.matched_shop_id
              FROM finance_label_invoice_rows r JOIN finance_label_imports i ON i.id=r.import_id
              WHERE r.organization_id=? AND i.status IN ('READY','PREPARED')
            ), priorities AS (
              SELECT bucket_key,MAX(source_priority*10+billing_priority) priority FROM sources GROUP BY bucket_key
            ), evidence AS (
              SELECT r.* FROM sources r JOIN priorities p ON p.bucket_key=r.bucket_key
              WHERE (r.source_priority=1 AND p.priority<20) OR r.source_priority*10+r.billing_priority=p.priority
            ), events AS (
              SELECT event_key,MIN(amount_cny) low_amount,MAX(amount_cny) high_amount,
                COUNT(DISTINCT order_id) orders,COUNT(DISTINCT matched_shop_id) shops,
                SUM(matched_shop_id IS NULL) unresolved FROM evidence GROUP BY event_key
            ), assignments AS (
              SELECT DISTINCT event_key,matched_shop_id,order_id FROM evidence WHERE matched_shop_id IS NOT NULL
            )
            SELECT ?,?,a.matched_shop_id,a.order_id,
              SUM(CASE WHEN e.low_amount=e.high_amount AND e.orders=1 AND e.shops=1 AND e.unresolved=0 THEN e.low_amount ELSE 0 END),
              SUM(CASE WHEN e.low_amount=e.high_amount AND e.orders=1 AND e.shops=1 AND e.unresolved=0 THEN 0 ELSE 1 END),COUNT(*)
            FROM assignments a JOIN events e ON e.event_key=a.event_key
            JOIN finance_connections c ON c.organization_id=? AND c.id=a.matched_shop_id AND c.status<>'REMOVED'
            GROUP BY a.matched_shop_id,a.order_id
            """,org,org,generation,org);
        renew(token);
        // Read the per-file counters without locking the hundreds of thousands
        // of source rows. Only the small result is written in the publish commit.
        var counts=repo.jdbc().query(connection->{
            var statement=connection.prepareStatement("""
                SELECT r.import_id,SUM(r.matched_shop_id IS NOT NULL) matched,
                  SUM(r.match_status='UNMATCHED') unmatched,SUM(r.match_status='REVIEW') review,
                  0 billing_failed,SUM(COALESCE(lc.pending_count,0)>0) conflicts
                FROM finance_label_invoice_rows r
                LEFT JOIN finance_label_order_totals lc ON lc.organization_id=r.organization_id
                  AND lc.generation=? AND lc.shop_id=r.matched_shop_id AND lc.order_id=r.order_id
                WHERE r.organization_id=? GROUP BY r.import_id
                """);
            statement.setQueryTimeout(120);statement.setString(1,generation);statement.setString(2,org);return statement;
        },new org.springframework.jdbc.core.ColumnMapRowMapper());
        renew(token);
        boolean published=repo.transaction(()->{
            var state=repo.jdbc().queryForMap("SELECT requested_revision,source_revision FROM finance_label_state WHERE organization_id=? FOR UPDATE",org);
            if(number(state.get("source_revision"))!=sourceRevision)return false;
            // Continuous order synchronization must not starve publication. A later binding
            // revision remains pending; source changes still reject the whole generation.
            repo.jdbc().update("UPDATE finance_label_state SET active_generation=?,applied_revision=?,error_message=NULL,retry_after=NULL,rematch_all=IF(requested_revision=?,FALSE,rematch_all),updated_at=UTC_TIMESTAMP(6) WHERE organization_id=?",generation,revision,revision,org);
            for(var count:counts)repo.jdbc().update("""
                UPDATE finance_label_imports SET matched_count=?,unmatched_count=?,review_count=?,
                  billing_failed_count=?,conflict_count=?,status='READY',updated_at=UTC_TIMESTAMP(6)
                WHERE organization_id=? AND id=? AND status IN ('READY','PREPARED')
                """,count.get("matched"),count.get("unmatched"),count.get("review"),count.get("billing_failed"),
                    count.get("conflicts"),org,count.get("import_id"));
            repo.jdbc().update("UPDATE finance_label_imports SET status='REMOVED',updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND status='REMOVE_QUEUED'",org);
            return true;
        });
        if(published&&service!=null)service.refreshCostSnapshots(companyScope(org));
    }
    int timedUpdate(String sql,Object...arguments){
        // INSERT ... SELECT under REPEATABLE READ locks every source row. This
        // single-statement snapshot needs no such locks: source_revision still
        // rejects a generation if an invoice was changed before publication.
        var tx=new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(repo.jdbc().getDataSource())));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(120);
        return tx.execute(status->repo.jdbc().update(connection->{
                var statement=connection.prepareStatement(sql);
                statement.setQueryTimeout(120);
                for(int i=0;i<arguments.length;i++)org.springframework.jdbc.core.StatementCreatorUtils.setParameterValue(
                    statement,i+1,org.springframework.jdbc.core.SqlTypeValue.TYPE_UNKNOWN,arguments[i]);
                return statement;
            }));
    }
    void requireRebuildSpace()throws IOException{
        Path existing=directory;
        while(!Files.exists(existing)&&existing.getParent()!=null)existing=existing.getParent();
        if(Files.getFileStore(existing).getUsableSpace()<2L*1024*1024*1024)
            throw bad("服务器可用空间不足 2GB，已暂停贴单重算；当前已生效金额保留");
    }
    void cleanup(String token)throws IOException{
        renew(token);
        for(var job:repo.jdbc().queryForList("""
            SELECT id,storage_name FROM finance_label_imports WHERE storage_name<>'' AND
              (status IN ('READY','REMOVED') OR (status='FAILED' AND updated_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 7 DAY))) LIMIT 10
            """)){
            Files.deleteIfExists(source(job));
            repo.jdbc().update("UPDATE finance_label_imports SET storage_name='',stored_bytes=0,status=IF(status='FAILED','EXPIRED',status) WHERE id=?",job.get("id"));
        }
        var removed=repo.jdbc().queryForList("SELECT id FROM finance_label_imports i WHERE status IN ('REMOVED','EXPIRED') AND EXISTS(SELECT 1 FROM finance_label_invoice_rows r WHERE r.import_id=i.id) LIMIT 1");
        if(!removed.isEmpty())repo.jdbc().update("DELETE FROM finance_label_invoice_rows WHERE import_id=? LIMIT 1000",removed.getFirst().get("id"));
        // Files left by a process crash before queue insertion cannot accumulate forever.
        if(System.currentTimeMillis()>=nextFileCleanup&&Files.isDirectory(directory)){
            nextFileCleanup=System.currentTimeMillis()+TimeUnit.HOURS.toMillis(1);
            try(var paths=Files.newDirectoryStream(directory)){
                for(Path path:paths){
                    if(Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()>System.currentTimeMillis()-TimeUnit.DAYS.toMillis(1))continue;
                    String name=path.getFileName().toString();
                    if(name.matches("[a-f0-9-]{36}\\.xlsx")&&repo.jdbc().queryForList("SELECT 1 FROM finance_label_imports WHERE storage_name=? LIMIT 1",name).isEmpty())Files.deleteIfExists(path);
                    else if(name.matches("parse-[a-f0-9-]{36}")&&Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)){
                        Files.deleteIfExists(path.resolve("strings.bin"));Files.deleteIfExists(path.resolve("offsets.bin"));Files.deleteIfExists(path);
                    }
                }
            }
        }
    }
    /** Bounded cleanup with backpressure: at most one retired generation can accumulate. */
    boolean cleanupGenerations(String token){
        return cleanupGenerations(token,10);
    }
    boolean cleanupGenerations(String token,int maxBatches){
        int batches=0;
        for(var company:repo.jdbc().queryForList("SELECT DISTINCT organization_id FROM finance_label_order_totals")){
            String org=text(company.get("organization_id"));
            for(;;){
                renew(token);
                var current=repo.jdbc().queryForList("SELECT active_generation FROM finance_label_state WHERE organization_id=?",org);
                String active=current.isEmpty()?"":text(current.getFirst().get("active_generation"));
                // Primary-key range lookup skips the active generation instead
                // of repeatedly scanning every published order.
                var stale=repo.jdbc().queryForList("""
                    SELECT generation FROM finance_label_order_totals
                    WHERE organization_id=? AND generation<>? ORDER BY generation LIMIT 1
                    """,org,active);
                if(stale.isEmpty())break;
                if(batches++>=maxBatches)return false;
                String generation=text(stale.getFirst().get("generation"));
                repo.jdbc().update(connection->{
                    var statement=connection.prepareStatement("""
                        DELETE FROM finance_label_order_totals
                        WHERE organization_id=? AND generation=?
                          AND NOT EXISTS(SELECT 1 FROM finance_label_state
                            WHERE organization_id=? AND active_generation=?)
                        LIMIT 5000
                        """);
                    statement.setQueryTimeout(15);
                    statement.setString(1,org);statement.setString(2,generation);
                    statement.setString(3,org);statement.setString(4,generation);
                    return statement;
                });
            }
        }
        return true;
    }
    /** One canonical read relation is shared by dashboard, details, costs and export. */
    static String join(String organization,String shop,String order){
        return " LEFT JOIN finance_label_state ls ON ls.organization_id="+organization+
            " LEFT JOIN finance_label_order_totals lc ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation"+
            " AND lc.shop_id="+shop+" AND lc.order_id="+order+" ";
    }
    static Map<String,Object> overlay(Map<String,Object> product,Map<String,Object> label){
        var out=new LinkedHashMap<>(product);out.remove("labelRuleId");out.remove("labelRuleVersion");
        boolean present=number(label.get("shipment_count"))>0;long pending=number(label.get("pending_count"));
        out.put("labelCostCny",pending>0?null:present?label.get("amount_cny"):null);
        out.put("labelKnownCny",present?label.get("amount_cny"):BigDecimal.ZERO);
        out.put("labelBasis","WAREHOUSE_INVOICE");out.put("chargesPending",pending);
        out.put("labelNote",pending>0?"费用来源存在金额或订单归属冲突，未确认部分未计入":
            present?"按导入账单金额计价（人民币），按参考号绑定；重复来源去重，按订单首次结算日期归属":"未导入对应贴单账单，金额待补齐");
        return out;
    }
}
