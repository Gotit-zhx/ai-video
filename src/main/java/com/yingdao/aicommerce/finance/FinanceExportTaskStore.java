package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.common.exception.BusinessException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Durable export requests. Leases and limits are serialized only during short database writes. */
final class FinanceExportTaskStore {
    enum Lane { SMALL, STANDARD }
    enum Publication { READY, HELD_FOR_STORAGE, LEASE_LOST }
    record Request(String type,Map<String,String> selection,boolean partial,Lane lane){}
    record File(String name,String contentType,String storedName,long bytes){}
    private static final long RETAINED_LIMIT=256L<<20;
    private static final int ACTIVE_LIMIT=64;
    private static final int USER_LANE_LIMIT=2;
    static final String STORAGE_WAIT="文件已生成，等待下载空间释放";
    private static final String ACTIVE="'QUEUED','WAITING','RUNNING'";
    private static final String LEASE="id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)";
    private final FinanceRepository repo;
    private final TransactionTemplate progress;

    FinanceExportTaskStore(FinanceRepository repo){
        this.repo=repo;
        this.progress=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(repo.jdbc().getDataSource())));
        progress.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        progress.setTimeout(5);
    }

    Map<String,Object> submit(Scope owner,String key,Request request){
        if(!text(key).matches("[A-Za-z0-9_-]{16,128}"))throw bad("导出请求编号不正确");
        String selection=repo.write(new TreeMap<>(request.selection()));
        return repo.transaction(()->{
            lock();
            var rows=repo.jdbc().queryForList("""
                SELECT * FROM finance_report_export_jobs
                WHERE organization_id=? AND user_id=? AND request_key=?
                """,owner.organizationId(),owner.userId(),key);
            if(!rows.isEmpty()){
                var prior=rows.getFirst();
                if(!request.type().equals(prior.get("report_type"))||!selection.equals(prior.get("selection_json"))
                        ||request.partial()!=Boolean.TRUE.equals(prior.get("allow_partial")))
                    throw conflict("同一导出请求不能更换筛选条件或数据范围");
                return prior;
            }
            var lane=request.lane();
            var count=repo.jdbc().queryForMap("""
                SELECT COUNT(*) total,COALESCE(SUM(lane='STANDARD'),0) standard_count,
                       COALESCE(SUM(organization_id=? AND user_id=? AND lane=?),0) owned_lane
                FROM finance_report_export_jobs WHERE expires_at>UTC_TIMESTAMP(6) AND state IN (
                """+ACTIVE+")",owner.organizationId(),owner.userId(),lane.name());
            // Keep a queue slot for small requests even when standard requests come from many users.
            if(number(count.get("total"))>=ACTIVE_LIMIT||number(count.get("owned_lane"))>=USER_LANE_LIMIT
                    ||lane==Lane.STANDARD&&number(count.get("standard_count"))>=ACTIVE_LIMIT-1)
                throw conflict("导出任务较多，请等待已有文件生成");
            String id=UUID.randomUUID().toString();
            repo.jdbc().update("""
                INSERT INTO finance_report_export_jobs(id,organization_id,user_id,request_key,report_type,selection_json,
                    allow_partial,lane,expires_at) VALUES(?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY))
                """,id,owner.organizationId(),owner.userId(),key,request.type(),selection,request.partial(),lane.name());
            return repo.jdbc().queryForMap("SELECT * FROM finance_report_export_jobs WHERE id=?",id);
        });
    }

    Map<String,Object> claim(Lane lane){
        return repo.transaction(()->{
            lock();
            var rows=repo.jdbc().queryForList("""
                SELECT j.id FROM finance_report_export_jobs j
                WHERE j.lane=? AND j.expires_at>UTC_TIMESTAMP(6)
                  AND ((j.state IN ('QUEUED','WAITING') AND j.available_at<=UTC_TIMESTAMP(6))
                    OR (j.state='RUNNING' AND j.lease_until<=UTC_TIMESTAMP(6)))
                  AND NOT EXISTS (SELECT 1 FROM finance_report_export_jobs live
                    WHERE live.lane=j.lane AND live.state='RUNNING' AND live.lease_until>UTC_TIMESTAMP(6))
                  AND ((j.stored_name IS NOT NULL AND j.stored_bytes<=?)
                    OR (j.stored_name IS NULL AND NOT EXISTS (
                        SELECT 1 FROM finance_report_export_jobs held
                        WHERE held.lane=j.lane AND held.state IN ('WAITING','RUNNING')
                          AND held.stored_name IS NOT NULL AND held.expires_at>UTC_TIMESTAMP(6))))
                ORDER BY j.available_at,j.created_at,j.id LIMIT 1
                """,lane.name(),availableDownloadBytes());
            if(rows.isEmpty())return Map.of();
            String id=text(rows.getFirst().get("id")),token=UUID.randomUUID().toString();
            repo.jdbc().update("""
                UPDATE finance_report_export_jobs SET state='RUNNING',lease_token=?,
                  lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 90 SECOND),attempts=attempts+1,
                  processed_count=IF(stored_name IS NULL,0,processed_count),
                  total_count=IF(stored_name IS NULL,0,total_count),error_message='' WHERE id=?
                """,token,id);
            return repo.jdbc().queryForMap("SELECT * FROM finance_report_export_jobs WHERE id=?",id);
        });
    }

    private long availableDownloadBytes(){
        long total=number(repo.jdbc().queryForObject(
            "SELECT COALESCE(SUM(stored_bytes),0) FROM finance_report_export_jobs WHERE state='READY'",Long.class));
        return RETAINED_LIMIT-total;
    }

    boolean progress(String id,String lease,long processed,long total){
        return Boolean.TRUE.equals(progress.execute(tx->repo.jdbc().update("""
            UPDATE finance_report_export_jobs SET processed_count=?,total_count=? WHERE
            """+LEASE,processed,total,id,lease)==1));
    }

    boolean owns(String id,String lease){
        return Boolean.TRUE.equals(repo.jdbc().queryForObject(
                "SELECT EXISTS(SELECT 1 FROM finance_report_export_jobs WHERE "+LEASE+")",Boolean.class,id,lease));
    }

    void renew(String id,String lease){
        repo.jdbc().update("UPDATE finance_report_export_jobs SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 90 SECOND) WHERE "+LEASE,id,lease);
    }

    Publication publish(String id,String lease,File result,Object receipt){
        return repo.transaction(()->{
            lock();
            if(!owns(id,lease))return Publication.LEASE_LOST;
            boolean ready=result.bytes()<=availableDownloadBytes();
            int written=repo.jdbc().update("""
                UPDATE finance_report_export_jobs SET state=?,receipt_json=?,file_name=?,content_type=?,stored_name=?,
                  stored_bytes=?,error_message=?,lease_token=NULL,lease_until=NULL,
                  expires_at=IF(?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 MINUTE),expires_at),
                  available_at=IF(?,available_at,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 SECOND)) WHERE
                """+LEASE,ready?"READY":"WAITING",repo.write(receipt),result.name(),result.contentType(),result.storedName(),
                result.bytes(),ready?"":STORAGE_WAIT,ready,ready,id,lease);
            if(written!=1)return Publication.LEASE_LOST;
            return ready?Publication.READY:Publication.HELD_FOR_STORAGE;
        });
    }

    void waitFor(String id,String lease,String reason,Object receipt,int seconds){
        repo.jdbc().update("""
            UPDATE finance_report_export_jobs SET state='WAITING',error_message=?,receipt_json=COALESCE(?,receipt_json),
              available_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),lease_token=NULL,lease_until=NULL WHERE
            """+LEASE,boundedReason(reason),receipt==null?null:repo.write(receipt),seconds,id,lease);
    }

    boolean fail(String id,String lease,String reason){
        return repo.jdbc().update("""
            UPDATE finance_report_export_jobs SET state='FAILED',error_message=?,lease_token=NULL,lease_until=NULL,
              stored_name=NULL,stored_bytes=0 WHERE
            """+LEASE,boundedReason(reason),id,lease)==1;
    }

    boolean retryMissingFile(String id,String lease){
        return repo.jdbc().update("""
            UPDATE finance_report_export_jobs SET state='WAITING',error_message='文件暂时无法读取，系统将重新生成',
              available_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 SECOND),lease_token=NULL,lease_until=NULL,
              stored_name=NULL,stored_bytes=0,receipt_json=NULL,processed_count=0,total_count=0,file_name='',content_type='' WHERE
            """+LEASE,id,lease)==1;
    }

    List<Map<String,Object>> list(Scope owner){
        return repo.jdbc().queryForList("""
            SELECT * FROM finance_report_export_jobs
            WHERE organization_id=? AND user_id=? AND expires_at>UTC_TIMESTAMP(6)
            ORDER BY created_at DESC,id LIMIT 40
            """,owner.organizationId(),owner.userId());
    }

    Map<String,Object> find(Scope owner,String id){
        var rows=repo.jdbc().queryForList("""
            SELECT * FROM finance_report_export_jobs WHERE organization_id=? AND user_id=? AND id=? AND expires_at>UTC_TIMESTAMP(6)
            """,owner.organizationId(),owner.userId(),id);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"文件不存在或已过期");
        return rows.getFirst();
    }

    boolean contains(Scope owner,String id){
        return Boolean.TRUE.equals(repo.jdbc().queryForObject("""
            SELECT EXISTS(SELECT 1 FROM finance_report_export_jobs
              WHERE organization_id=? AND user_id=? AND id=? AND expires_at>UTC_TIMESTAMP(6))
            """,Boolean.class,owner.organizationId(),owner.userId(),id));
    }

    List<Map<String,Object>> expired(){
        return repo.jdbc().queryForList("""
            SELECT id,stored_name FROM finance_report_export_jobs WHERE expires_at<=UTC_TIMESTAMP(6)
              AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) ORDER BY expires_at LIMIT 32
            """);
    }

    void removeExpired(String id){
        repo.jdbc().update("""
            DELETE FROM finance_report_export_jobs WHERE id=? AND expires_at<=UTC_TIMESTAMP(6)
              AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6))
            """,id);
    }

    boolean references(String storedName){
        return Boolean.TRUE.equals(repo.jdbc().queryForObject(
                "SELECT EXISTS(SELECT 1 FROM finance_report_export_jobs WHERE stored_name=?)",Boolean.class,storedName));
    }

    private void lock(){repo.jdbc().queryForList("SELECT id FROM finance_report_export_gate WHERE id=1 FOR UPDATE");}
    private static String boundedReason(String reason){return reason.substring(0,Math.min(reason.length(),300));}
    static BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
