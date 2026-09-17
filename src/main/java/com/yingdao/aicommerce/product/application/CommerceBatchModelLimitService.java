package com.yingdao.aicommerce.product.application;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.Objects;

/** Call-count reservation, not a currency budget or evidence that a provider charged. */
@Service
public class CommerceBatchModelLimitService {
 public record Limit(int maxCalls,int reservedCalls,boolean monetaryBudget){ }
 private final JdbcTemplate db;
 public CommerceBatchModelLimitService(JdbcTemplate db){this.db=db;}
 public static void validate(int max){if(max<0||max>1000)throw new BusinessException(HttpStatus.BAD_REQUEST,"模型调用上限须为0至1000次");}
 public void initialize(String run,String org,int max){validate(max);db.update("INSERT INTO commerce_batch_model_limits(execution_id,organization_id,max_calls,reserved_calls,created_at) VALUES(?,?,?,0,NOW(6))",run,org,max);}
 public Limit get(String run,String org){var rows=db.queryForList("SELECT * FROM commerce_batch_model_limits WHERE execution_id=? AND organization_id=?",run,org);if(rows.isEmpty())return new Limit(0,0,false);var r=rows.getFirst();return new Limit(((Number)r.get("max_calls")).intValue(),((Number)r.get("reserved_calls")).intValue(),false);}
 @Transactional public boolean reserve(String run,String org,String key,String stage){
  if(key==null||!key.matches("[A-Za-z0-9_-]{8,100}")||!java.util.Set.of("EXTRACT","VARIATE","MOCKUP","REVIEW").contains(Objects.toString(stage,"")))throw new BusinessException(HttpStatus.BAD_REQUEST,"模型请求编号或阶段无效");
  var limits=db.queryForList("SELECT * FROM commerce_batch_model_limits WHERE execution_id=? AND organization_id=? FOR UPDATE",run,org);if(limits.isEmpty())return false;
  var prior=db.queryForList("SELECT stage FROM commerce_batch_model_reservations WHERE execution_id=? AND request_key=?",run,key);
  if(!prior.isEmpty()){if(!stage.equals(prior.getFirst().get("stage")))throw new BusinessException(HttpStatus.CONFLICT,"请求编号不能跨阶段复用");return true;}
  var limit=limits.getFirst();if(((Number)limit.get("reserved_calls")).intValue()>=((Number)limit.get("max_calls")).intValue())return false;
  db.update("INSERT INTO commerce_batch_model_reservations(execution_id,request_key,stage,created_at) VALUES(?,?,?,NOW(6))",run,key,stage);
  db.update("UPDATE commerce_batch_model_limits SET reserved_calls=reserved_calls+1 WHERE execution_id=? AND organization_id=?",run,org);return true;
 }
}
