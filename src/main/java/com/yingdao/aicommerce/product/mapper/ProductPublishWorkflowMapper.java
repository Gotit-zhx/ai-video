package com.yingdao.aicommerce.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.product.domain.ProductPublishWorkflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ProductPublishWorkflowMapper extends BaseMapper<ProductPublishWorkflow> {
    @Select("""
            SELECT * FROM product_publish_workflows
             WHERE organization_id = #{organizationId}
               AND idempotency_key = #{idempotencyKey}
             LIMIT 1
            """)
    ProductPublishWorkflow findByIdempotency(@Param("organizationId") String organizationId,
                                             @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM product_publish_workflows
             WHERE publication_target_key = #{targetKey}
             LIMIT 1
            """)
    ProductPublishWorkflow findByPublicationTarget(@Param("targetKey") String targetKey);

    @Select("""
            SELECT id FROM product_publish_workflows
             WHERE status IN ('PENDING', 'RUNNING')
               AND next_attempt_at <= #{now}
               AND (locked_until IS NULL OR locked_until < #{now})
               AND NOT (publication_attempted = TRUE
                        AND JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.executionMode')) = 'LIVE')
             ORDER BY next_attempt_at, created_at
             LIMIT #{limit}
            """)
    List<String> findClaimableIds(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'RUNNING', locked_by = #{worker}, locked_until = #{leaseUntil},
                   attempt_count = attempt_count + 1, updated_at = #{now}
             WHERE id = #{id}
               AND status IN ('PENDING', 'RUNNING')
               AND next_attempt_at <= #{now}
               AND (locked_until IS NULL OR locked_until < #{now})
               AND NOT (publication_attempted = TRUE
                        AND JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.executionMode')) = 'LIVE')
            """)
    int claim(@Param("id") String id, @Param("worker") String worker,
              @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            UPDATE product_publish_workflows
               SET current_step = #{step}, expected_capture_version = #{captureVersion},
                   updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
            """)
    int markStep(@Param("id") String id, @Param("worker") String worker,
                 @Param("step") String step, @Param("captureVersion") long captureVersion,
                 @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET publication_attempted = TRUE, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
               AND current_step = 'PUBLISH'
               AND JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.executionMode')) = 'LIVE'
            """)
    int markPublicationAttempted(@Param("id") String id, @Param("worker") String worker,
                                 @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'FAILED',
                   error_message = #{message}, locked_by = NULL, locked_until = NULL,
                   completed_at = #{now}, updated_at = #{now}
             WHERE status IN ('PENDING', 'RUNNING')
               AND publication_attempted = TRUE
               AND JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.executionMode')) = 'LIVE'
               AND next_attempt_at <= #{now}
               AND (locked_until IS NULL OR locked_until < #{now})
            """)
    int failExpiredUncertainPublications(@Param("now") Instant now,
                                         @Param("message") String message);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'PENDING', current_step = #{nextStep}, image_job_id = #{imageJobId},
                   expected_capture_version = #{captureVersion}, next_attempt_at = #{nextAttemptAt},
                   retry_count = 0, locked_by = NULL, locked_until = NULL,
                   error_message = NULL, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
            """)
    int imageSucceeded(@Param("id") String id, @Param("worker") String worker,
                       @Param("imageJobId") String imageJobId,
                       @Param("captureVersion") long captureVersion,
                       @Param("nextStep") String nextStep,
                       @Param("nextAttemptAt") Instant nextAttemptAt,
                       @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'PENDING', current_step = 'PUBLISH',
                   expected_capture_version = #{captureVersion}, next_attempt_at = #{nextAttemptAt},
                   retry_count = 0, locked_by = NULL, locked_until = NULL,
                   error_message = NULL, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
            """)
    int approvalSucceeded(@Param("id") String id, @Param("worker") String worker,
                          @Param("captureVersion") long captureVersion,
                          @Param("nextAttemptAt") Instant nextAttemptAt,
                          @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'PENDING', current_step = #{step},
                   image_job_id = COALESCE(#{imageJobId}, image_job_id),
                   publish_audit_id = COALESCE(#{publishAuditId}, publish_audit_id),
                   expected_capture_version = #{captureVersion}, next_attempt_at = #{nextAttemptAt},
                   locked_by = NULL, locked_until = NULL, error_message = NULL, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
            """)
    int waitForProgress(@Param("id") String id, @Param("worker") String worker,
                        @Param("step") String step, @Param("imageJobId") String imageJobId,
                        @Param("publishAuditId") String publishAuditId,
                        @Param("captureVersion") long captureVersion,
                        @Param("nextAttemptAt") Instant nextAttemptAt,
                        @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'PENDING', current_step = 'PUBLISH',
                   expected_capture_version = #{captureVersion}, next_attempt_at = #{nextAttemptAt},
                   media_wait_started_at = COALESCE(media_wait_started_at, #{now}),
                   locked_by = NULL, locked_until = NULL, error_message = #{message}, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
               AND publication_attempted = FALSE
            """)
    int waitForMediaProgress(@Param("id") String id, @Param("worker") String worker,
                             @Param("captureVersion") long captureVersion,
                             @Param("message") String message,
                             @Param("nextAttemptAt") Instant nextAttemptAt,
                             @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'SUCCEEDED', current_step = 'COMPLETED',
                   publish_audit_id = #{publishAuditId}, expected_capture_version = #{captureVersion},
                   publication_target_key = CASE WHEN #{realPublication} THEN publication_target_key ELSE NULL END,
                   locked_by = NULL, locked_until = NULL, error_message = NULL,
                   completed_at = #{now}, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
            """)
    int complete(@Param("id") String id, @Param("worker") String worker,
                 @Param("publishAuditId") String publishAuditId,
                 @Param("captureVersion") long captureVersion,
                 @Param("realPublication") boolean realPublication,
                 @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'FAILED', error_message = #{message},
                   locked_by = NULL, locked_until = NULL,
                   completed_at = #{now}, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
               AND publication_attempted = TRUE
               AND JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.executionMode')) = 'LIVE'
            """)
    int failUncertainPublication(@Param("id") String id, @Param("worker") String worker,
                                 @Param("message") String message, @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'FAILED', error_message = #{message},
                   publication_target_key = CASE WHEN publication_attempted
                                                   AND JSON_UNQUOTE(JSON_EXTRACT(
                                                       request_json, '$.executionMode')) = 'LIVE'
                                                 THEN publication_target_key ELSE NULL END,
                   locked_by = NULL, locked_until = NULL, completed_at = #{now}, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
            """)
    int fail(@Param("id") String id, @Param("worker") String worker,
             @Param("message") String message, @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'FAILED', error_message = #{message},
                   publication_attempted = FALSE, publication_target_key = NULL,
                   locked_by = NULL, locked_until = NULL, completed_at = #{now}, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
            """)
    int failRejectedPublication(@Param("id") String id, @Param("worker") String worker,
                                @Param("message") String message, @Param("now") Instant now);

    @Update("""
            UPDATE product_publish_workflows
               SET status = 'PENDING', next_attempt_at = #{nextAttemptAt},
                   retry_count = retry_count + 1, locked_by = NULL, locked_until = NULL,
                   error_message = #{message}, updated_at = #{now}
             WHERE id = #{id} AND status = 'RUNNING' AND locked_by = #{worker}
               AND NOT (publication_attempted = TRUE
                        AND JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.executionMode')) = 'LIVE')
            """)
    int retry(@Param("id") String id, @Param("worker") String worker,
              @Param("message") String message, @Param("nextAttemptAt") Instant nextAttemptAt,
              @Param("now") Instant now);
}
