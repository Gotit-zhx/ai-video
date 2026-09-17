package com.yingdao.aicommerce.product.mapper;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
@Mapper public interface CommerceBatchExecutionMapper {
 @Select("SELECT * FROM commerce_batch_executions WHERE organization_id=#{org} AND batch_id=#{batch}")
 CommerceBatchExecution get(@Param("org")String org,@Param("batch")String batch);
 @Select("SELECT * FROM commerce_batch_executions WHERE id=#{id}") CommerceBatchExecution byId(String id);
 @Select("SELECT COUNT(*)>0 FROM commerce_batch_executions e JOIN commerce_batch_creation_modes c ON c.batch_id=e.batch_id AND c.organization_id=e.organization_id WHERE e.id=#{id} AND c.mode='CREATIVE_ONLY'") boolean creationOnly(String id);
 @Select("SELECT id FROM commerce_batch_executions WHERE status='QUEUED' ORDER BY updated_at LIMIT 10") List<String> queued();
 @Insert("INSERT INTO commerce_batch_executions(id,organization_id,batch_id,created_by,stage,status,message,created_at,updated_at) VALUES(#{id},#{organizationId},#{batchId},#{createdBy},#{stage},#{status},#{message},#{createdAt},#{updatedAt})") int insert(CommerceBatchExecution row);
 @Update("UPDATE commerce_batch_executions SET status='RUNNING',lease_token=#{token},lease_until=#{until},updated_at=#{now} WHERE id=#{id} AND status='QUEUED'") int claim(@Param("id")String id,@Param("token")String token,@Param("now")Instant now,@Param("until")Instant until);
 @Update("UPDATE commerce_batch_executions SET lease_until=GREATEST(lease_until,#{until}) WHERE id=#{id} AND status IN ('RUNNING','PAUSING') AND lease_token=#{token} AND lease_until>#{now}") int renew(@Param("id")String id,@Param("token")String token,@Param("now")Instant now,@Param("until")Instant until);
 @Update("UPDATE commerce_batch_executions SET status='PAUSING',message='服务正在停止，等待在途结果保存后暂停后续调用',updated_at=#{now} WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until>#{now}") int drain(@Param("id")String id,@Param("token")String token,@Param("now")Instant now);
 @Update("UPDATE commerce_batch_executions SET message=CASE WHEN status='PAUSING' AND #{status}='QUEUED' THEN '当前结果已保存，已暂停后续调用' ELSE #{message} END,status=CASE WHEN status='PAUSING' AND #{status}='QUEUED' THEN 'PAUSED' ELSE #{status} END,stage=#{stage},lease_token=NULL,lease_until=NULL,updated_at=#{now} WHERE id=#{id} AND status IN ('RUNNING','PAUSING') AND lease_token=#{token} AND lease_until>#{now}") int finish(@Param("id")String id,@Param("token")String token,@Param("stage")String stage,@Param("status")String status,@Param("message")String message,@Param("now")Instant now);
 @Update("UPDATE commerce_batch_executions SET status='UNKNOWN',message='执行租约已过期，结果需核查；不会自动重试',lease_token=NULL,lease_until=NULL,updated_at=#{now} WHERE status IN ('RUNNING','PAUSING') AND lease_until<=#{now}") int expire(Instant now);
 @Update("UPDATE commerce_batch_executions SET status='QUEUED',message='已恢复，等待后台推进',updated_at=#{now} WHERE organization_id=#{org} AND batch_id=#{batch} AND status IN ('WAITING_CAPABILITY','WAITING_INPUT','PAUSED')") int resume(@Param("org")String org,@Param("batch")String batch,@Param("now")Instant now);
 @Update("""
   UPDATE commerce_batch_executions e SET status='QUEUED',message='已核对没有未决模型提交，继续使用持久化结果',updated_at=#{now}
   WHERE e.organization_id=#{org} AND e.batch_id=#{batch} AND e.status='UNKNOWN' AND e.stage IN ('EXTRACT','VARIATE','MOCKUP')
   AND EXISTS (SELECT 1 FROM commerce_batch_artwork_jobs j WHERE j.execution_id=e.id)
   AND NOT EXISTS (SELECT 1 FROM commerce_batch_artwork_jobs j WHERE j.execution_id=e.id AND j.status IN ('SUBMITTING','UNKNOWN'))
   """) int recover(@Param("org")String org,@Param("batch")String batch,@Param("now")Instant now);
 @Update("UPDATE commerce_batch_executions SET message=CASE WHEN status='RUNNING' THEN '正在暂停：等待在途模型结果，不启动后续调用' ELSE '已暂停' END,status=CASE WHEN status='RUNNING' THEN 'PAUSING' ELSE 'PAUSED' END,updated_at=#{now} WHERE organization_id=#{org} AND batch_id=#{batch} AND status IN ('QUEUED','RUNNING')") int pause(@Param("org")String org,@Param("batch")String batch,@Param("now")Instant now);
 @Insert("INSERT INTO commerce_batch_execution_events(id,execution_id,stage,status,message,created_at) VALUES(#{id},#{run},#{stage},#{status},#{message},#{now})") int event(@Param("id")String id,@Param("run")String run,@Param("stage")String stage,@Param("status")String status,@Param("message")String message,@Param("now")Instant now);
}
