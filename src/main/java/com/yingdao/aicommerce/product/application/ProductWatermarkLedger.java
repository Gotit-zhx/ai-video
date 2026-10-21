package com.yingdao.aicommerce.product.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Commits each paid-call intent outside the surrounding capture transaction. */
@Service
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class ProductWatermarkLedger {
 private final JdbcTemplate db;
 public ProductWatermarkLedger(JdbcTemplate db){this.db=db;}
 public Map<String,Object> prepare(String id,String org,String capture,String image,String sha,String key){
  db.update("INSERT IGNORE INTO product_watermark_checks(id,organization_id,capture_id,image_id,source_sha256,source_key,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'PREPARED',NOW(6),NOW(6))",id,org,capture,image,sha,key);
  return get(id,org);
 }
 /** A new local policy may recompose a received artifact, but never reuse its old approval. */
 public boolean reuseReceivedGeneration(String id,String org,String capture,String image,String sourceHash){
  var prior=db.queryForList("SELECT id,detection_json,generation_json,local_detection_json,candidate_key FROM product_watermark_checks WHERE organization_id=? AND capture_id=? AND image_id=? AND source_sha256=? AND id<>? AND generation_json IS NOT NULL AND detection_json IS NOT NULL ORDER BY created_at DESC LIMIT 1",org,capture,image,sourceHash,id);
  if(prior.isEmpty())return false;
  var row=prior.getFirst();
  return db.update("UPDATE product_watermark_checks SET status='RESULT_RECEIVED',detection_json=JSON_SET(?, '$.reusedFromCheckId', ?),generation_json=?,local_detection_json=?,candidate_key=?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='PREPARED'",row.get("detection_json"),row.get("id"),row.get("generation_json"),row.get("local_detection_json"),row.get("candidate_key"),id,org)==1;
 }
 public Map<String,Object> get(String id,String org){
  var rows=db.queryForList("SELECT * FROM product_watermark_checks WHERE id=? AND organization_id=?",id,org);
  if(rows.isEmpty())throw new IllegalArgumentException("水印处理记录不存在");return rows.getFirst();
 }
 public boolean claim(String id,String org,String expected,String next){
  return db.update("UPDATE product_watermark_checks SET status=?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status=?",next,id,org,expected)==1;
 }
 public void directEdit(String id,String org){
  update("UPDATE product_watermark_checks SET detection_json='{\"method\":\"DIRECT_IMAGE_EDIT\",\"preDetectionSkipped\":true}',status='EDITING',updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='PREPARED'",id,org);
 }
 public List<Map<String,Object>> progress(String org,String capture){
  return db.queryForList("SELECT image_id AS imageId,status,message,updated_at AS updatedAt FROM (SELECT image_id,status,message,updated_at,ROW_NUMBER() OVER(PARTITION BY image_id ORDER BY created_at DESC,id DESC) AS rn FROM product_watermark_checks WHERE organization_id=? AND capture_id=?) latest WHERE rn=1",org,capture);
 }
 public void detection(String id,String org,String json){
  update("UPDATE product_watermark_checks SET detection_json=?,status='DETECTED',updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='DETECTING'",json,id,org);
 }
 public void screened(String id,String org,String json){
  update("UPDATE product_watermark_checks SET local_detection_json=?,status='SCREENED',updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='SCREENING'",json,id,org);
 }
 public void noLocalCandidate(String id,String org,String key){
  update("UPDATE product_watermark_checks SET output_key=?,detection_json=local_detection_json,validation_json='{\"method\":\"UNCHANGED_ORIGINAL_USER_CONFIRMED_SHA256\"}',status='READY',updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='SCREENED'",key,id,org);
 }
 public void edited(String id,String org,String key){
  update("UPDATE product_watermark_checks SET candidate_key=?,status='EDITED',updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='RESULT_RECEIVED'",key,id,org);
 }
 public void generated(String id,String org,String json){
  update("UPDATE product_watermark_checks SET generation_json=?,status='RESULT_RECEIVED',missing_result_count=0,missing_result_since=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='EDITING'",json,id,org);
 }
 /** A missing paid artifact must be confirmed twice, separated in time. Keep the
  * exact response and attempt number before starting a bounded replacement.
  * Timeouts, authorization failures and unknown generation outcomes never enter here.
  */
 public boolean missingResult(String id,String org,String response,int httpStatus){
  if(httpStatus!=404&&httpStatus!=410)throw new IllegalArgumentException("Only missing artifacts may be replaced");
  var rows=db.queryForList("SELECT generation_retry_count,missing_result_count,missing_result_since,"
    +"COALESCE(JSON_LENGTH(unavailable_results_json),0) replacements,"
    +"TIMESTAMPDIFF(SECOND,missing_result_since,NOW(6)) missing_seconds "
    +"FROM product_watermark_checks WHERE id=? AND organization_id=? AND status='RESULT_RECEIVED' "
    +"AND generation_json=? FOR UPDATE",id,org,response);
  if(rows.isEmpty())return false;
  var row=rows.getFirst();int retries=((Number)row.get("generation_retry_count")).intValue();
  if(((Number)row.get("replacements")).intValue()>=2||retries>=6)
   throw new IllegalStateException("图片服务多次返回失效结果，已保留原请求和失败记录，请检查图片服务");
  int count=((Number)row.get("missing_result_count")).intValue();
  long seconds=row.get("missing_seconds") instanceof Number n?n.longValue():0;
  if(count<1||seconds<10){
   db.update("UPDATE product_watermark_checks SET missing_result_count=missing_result_count+1,"
     +"missing_result_since=COALESCE(missing_result_since,NOW(6)),updated_at=NOW(6) WHERE id=? AND organization_id=?",id,org);
   return false;
  }
  update("UPDATE product_watermark_checks SET unavailable_results_json=JSON_ARRAY_APPEND("
    +"COALESCE(unavailable_results_json,JSON_ARRAY()),'$',JSON_OBJECT('response',CAST(generation_json AS JSON),"
    +"'attempt',generation_retry_count,'httpStatus',?,'confirmedAt',NOW(6))),"
    +"generation_json=NULL,candidate_key=NULL,generation_retry_count=generation_retry_count+1,status='DETECTED',"
    +"missing_result_count=0,missing_result_since=NULL,message='结果链接已确认失效，原结果留档后恢复生成',updated_at=NOW(6) "
    +"WHERE id=? AND organization_id=? AND status='RESULT_RECEIVED'",httpStatus,id,org);
  return true;
 }
 public void recoverReceived(String id,String org,String expected,String json){
  update("UPDATE product_watermark_checks SET generation_json=?,status='RESULT_RECEIVED',message=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status=?",json,id,org,expected);
 }
 public boolean retryGeneration(String id,String org,String expected,int retries){
  return db.update("UPDATE product_watermark_checks SET generation_retry_count=generation_retry_count+1,status='DETECTED',message=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status=? AND generation_json IS NULL AND generation_retry_count=? AND generation_retry_count<6",id,org,expected,retries)==1;
 }
 public void ready(String id,String org,String expected,String key,String validation){
  update("UPDATE product_watermark_checks SET output_key=?,validation_json=?,status='READY',updated_at=NOW(6) WHERE id=? AND organization_id=? AND status=?",key,validation,id,org,expected);
 }
 public void hold(String id,String org,String expected,String message){
  update("UPDATE product_watermark_checks SET status='REVIEW_REQUIRED',message=?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status=?",message.substring(0,Math.min(500,message.length())),id,org,expected);
 }
 public void rejectValidation(String id,String org,String validation){
  update("UPDATE product_watermark_checks SET status='REVIEW_REQUIRED',validation_json=?,message='去水印复检未通过，禁止上传',updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='VALIDATING'",validation,id,org);
 }
 private void update(String sql,Object...args){if(db.update(sql,args)!=1)throw new IllegalStateException("水印任务状态已变化，停止当前操作");}
}
