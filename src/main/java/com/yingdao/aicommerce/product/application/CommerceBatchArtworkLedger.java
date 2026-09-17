package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** The lease check, call reservation and submission intent commit before any external call. */
@Service
public class CommerceBatchArtworkLedger {
    private final JdbcTemplate db;
    private final CommerceBatchModelLimitService limits;
    public CommerceBatchArtworkLedger(JdbcTemplate db, CommerceBatchModelLimitService limits) {this.db=db;this.limits=limits;}

    @Transactional
    public String claim(CommerceBatchExecution run, String jobId, String key) {
        var leases=db.queryForList("SELECT id,status FROM commerce_batch_executions WHERE id=? AND organization_id=? AND created_by=? AND status IN ('RUNNING','PAUSING') AND lease_token=? AND lease_until>NOW(6) FOR UPDATE",
                run.id(),run.organizationId(),run.createdBy(),run.leaseToken());
        if(leases.isEmpty())return "LEASE_LOST";
        if("PAUSING".equals(leases.getFirst().get("status")))return "PAUSE_REQUESTED";
        var jobs=db.queryForList("SELECT status FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? AND request_key=? FOR UPDATE",jobId,run.id(),run.organizationId(),key);
        if(jobs.isEmpty()||!"PREPARED".equals(jobs.getFirst().get("status")))return "ALREADY_CLAIMED";
        if(!limits.reserve(run.id(),run.organizationId(),key,run.stage()))return "LIMIT_REACHED";
        db.update("UPDATE commerce_batch_artwork_jobs SET status='SUBMITTING',message='已记录提交意图，等待模型结果',updated_at=NOW(6) WHERE id=?",jobId);
        return "CLAIMED";
    }
}
