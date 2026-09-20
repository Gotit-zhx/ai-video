package com.yingdao.aicommerce.product.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Retries only durable image results, never an unresolved model submission. */
@Service
public class CommerceImageArchiveRecovery {
    static final String EVENT="图片归档自动恢复：复用已保存的模型结果，不重新调用模型";
    private final JdbcTemplate db;
    public CommerceImageArchiveRecovery(JdbcTemplate db){this.db=db;}

    @Scheduled(initialDelay=30_000,fixedDelay=15_000)
    @Transactional
    public void recover() {
        var rows=db.queryForList("""
            SELECT e.id,e.stage FROM commerce_batch_executions e
            WHERE e.status='WAITING_INPUT'
              AND e.message LIKE '模型响应已保存，但图片归档未完成%'
              AND EXISTS (SELECT 1 FROM commerce_batch_artwork_jobs j WHERE j.execution_id=e.id AND j.status='RESULT_RECEIVED')
              AND NOT EXISTS (SELECT 1 FROM commerce_batch_artwork_jobs j WHERE j.execution_id=e.id AND j.status IN ('SUBMITTING','UNKNOWN'))
              AND (SELECT COUNT(*) FROM commerce_batch_execution_events v WHERE v.execution_id=e.id AND v.message=?)<3
              AND TIMESTAMPDIFF(SECOND,e.updated_at,NOW())>=30*POW(2,
                  (SELECT COUNT(*) FROM commerce_batch_execution_events v WHERE v.execution_id=e.id AND v.message=?))
            ORDER BY e.updated_at LIMIT 10 FOR UPDATE SKIP LOCKED
            """,EVENT,EVENT);
        for(var row:rows) {
            if(db.update("UPDATE commerce_batch_executions SET status='QUEUED',message=?,updated_at=NOW(6) WHERE id=? AND status='WAITING_INPUT'",EVENT,row.get("id"))!=1)continue;
            db.update("INSERT INTO commerce_batch_execution_events(id,execution_id,stage,status,message,created_at) VALUES(?,?,?,'QUEUED',?,NOW(6))",
                    UUID.randomUUID().toString(),row.get("id"),row.get("stage"),EVENT);
        }
    }
}
