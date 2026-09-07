package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceDesignOutput;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;
import java.util.UUID;
@Service
public class CommerceDesignOutputLedger {
    private final CommerceDesignAttemptMapper attempts;private final CommerceDesignOutputMapper outputs;
    public CommerceDesignOutputLedger(CommerceDesignAttemptMapper attempts,CommerceDesignOutputMapper outputs){this.attempts=attempts;this.outputs=outputs;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public CommerceDesignOutput attach(String org,String attemptId,CommerceImageArchive.Archived image){
        var attempt=attempts.lock(org,attemptId);if(attempt==null)throw new IllegalStateException("生成尝试不存在");
        var existing=outputs.byAttempt(org,attemptId);
        if(existing!=null){if(!existing.contentSha256().equals(image.sha256()))throw new IllegalStateException("同一生成结果的下载内容已变化，保留原归档并需核查");return existing;}
        if(!"RESULT_RECEIVED".equals(attempt.status()))throw new IllegalStateException("生成尝试尚无可归档结果");
        var output=new CommerceDesignOutput(UUID.randomUUID().toString(),org,attempt.designId(),attemptId,image.storageKey(),image.sha256(),image.mimeType(),image.byteLength(),"PENDING_REVIEW",Instant.now());
        outputs.insert(output);
        if(attempts.transition(org,attemptId,"RESULT_RECEIVED","OUTPUT_ARCHIVED",attempt.resultJson(),null)!=1)throw new IllegalStateException("生成尝试状态已变化");
        return output;
    }
}
