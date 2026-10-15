package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCaptureDeletionService {
 private final JdbcTemplate db;
 public ProductCaptureDeletionService(JdbcTemplate db){this.db=db;}
 @Transactional
 public void delete(String id,String org){
  var rows=db.queryForList("SELECT status,deleted_at FROM product_captures WHERE id=? AND organization_id=? AND data_module=? FOR UPDATE",id,org,CommerceModule.current());
  if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"采集商品不存在");
  if(rows.getFirst().get("deleted_at")!=null)return;
  int active=db.queryForObject("SELECT (SELECT COUNT(*) FROM product_publish_workflows WHERE capture_id=? AND organization_id=? AND status IN ('PENDING','RUNNING')) + (SELECT COUNT(*) FROM product_watermark_queue WHERE capture_id=? AND organization_id=? AND status IN ('QUEUED','RUNNING'))",Integer.class,id,org,id,org);
  if(active>0||"IMAGE_PROCESSING".equals(rows.getFirst().get("status")))throw new BusinessException(HttpStatus.CONFLICT,"商品正在处理或上传，请等待任务结束后再删除");
  db.update("UPDATE product_captures SET deleted_at=NOW(6),updated_at=NOW(6),version=version+1 WHERE id=? AND organization_id=? AND data_module=? AND deleted_at IS NULL",id,org,CommerceModule.current());
 }
}
