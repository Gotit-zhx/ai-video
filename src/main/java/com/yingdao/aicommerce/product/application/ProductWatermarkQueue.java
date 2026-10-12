package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable global admission limit; transactions never include model or image HTTP calls. */
@Service
public class ProductWatermarkQueue {
 public static final int MAX_CONCURRENT = 500;
 public static final int MAX_RETRIES = 6;
 private final JdbcTemplate db;
 private final ObjectMapper json;
 public record Work(String id,String organizationId,String captureId,String imageId,String jobId,String token) {}
 public ProductWatermarkQueue(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 @Transactional
 public void enqueue(ProductImageProcessingJob job,List<ProductCaptureImage> images){
  for(var image:images){
   String id=CommerceImageArchive.sha((job.getId()+":"+image.getId()).getBytes(StandardCharsets.UTF_8));
   int inserted=db.update("INSERT IGNORE INTO product_watermark_queue(id,organization_id,capture_id,image_id,job_id) VALUES(?,?,?,?,?)",id,job.getOrganizationId(),job.getCaptureId(),image.getId(),job.getId());
   if(inserted==1)db.update("UPDATE product_capture_images SET status='PENDING',error_message=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=?",image.getId(),job.getOrganizationId());
  }
 }
 @Transactional
 public void recoverUnqueuedJobs(){
  db.queryForObject("SELECT id FROM product_watermark_queue_guard WHERE id=1 FOR UPDATE",Integer.class);
  var jobs=db.queryForList("SELECT j.id,j.organization_id,j.capture_id FROM product_image_processing_jobs j JOIN product_captures c ON c.id=j.capture_id AND c.organization_id=j.organization_id WHERE j.status='RUNNING' AND j.provider='AI_WATERMARK_V1' AND c.status='IMAGE_PROCESSING' AND c.deleted_at IS NULL AND NOT EXISTS(SELECT 1 FROM product_watermark_queue q WHERE q.job_id=j.id) ORDER BY j.updated_at LIMIT 20 FOR UPDATE");
  for(var job:jobs){
   db.update("INSERT IGNORE INTO product_watermark_queue(id,organization_id,capture_id,image_id,job_id) SELECT SHA2(CONCAT(?,':',i.id),256),i.organization_id,i.capture_id,i.id,? FROM product_capture_images i WHERE i.capture_id=? AND i.organization_id=?",job.get("id"),job.get("id"),job.get("capture_id"),job.get("organization_id"));
  }
 }
 @Transactional
 public List<Work> claim(){
  db.queryForObject("SELECT id FROM product_watermark_queue_guard WHERE id=1 FOR UPDATE",Integer.class);
  int running=db.queryForObject("SELECT COUNT(*) FROM product_watermark_queue WHERE status='RUNNING'",Integer.class);
  int available=Math.max(0,MAX_CONCURRENT-running);
  if(available==0)return List.of();
  var rows=db.queryForList("SELECT id,organization_id,capture_id,image_id,job_id FROM product_watermark_queue WHERE status='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at<=NOW(6)) ORDER BY created_at,id LIMIT ? FOR UPDATE",available);
  var work=new ArrayList<Work>();
  for(var row:rows){
   String token=UUID.randomUUID().toString();
   String id=(String)row.get("id"),org=(String)row.get("organization_id"),image=(String)row.get("image_id");
   db.update("UPDATE product_watermark_queue SET status='RUNNING',worker_token=?,lease_until=DATE_ADD(NOW(6),INTERVAL 1 HOUR),updated_at=NOW(6) WHERE id=?",token,id);
   db.update("UPDATE product_capture_images SET status='PROCESSING',error_message=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=?",image,org);
   work.add(new Work(id,org,(String)row.get("capture_id"),image,(String)row.get("job_id"),token));
  }
  return work;
 }
 @Transactional
 public void release(Work work){
  if(db.update("UPDATE product_watermark_queue SET status='QUEUED',worker_token=NULL,lease_until=NULL,updated_at=NOW(6) WHERE id=? AND worker_token=? AND status='RUNNING'",work.id(),work.token())==1)
   db.update("UPDATE product_capture_images SET status='PENDING',updated_at=NOW(6) WHERE id=? AND organization_id=?",work.imageId(),work.organizationId());
 }
 @Transactional
 public void defer(Work work,String message){
  if(db.update("UPDATE product_watermark_queue SET status='QUEUED',worker_token=NULL,lease_until=NULL,error_message=?,next_attempt_at=TIMESTAMPADD(SECOND,LEAST(300,30*POW(2,LEAST(throttle_count,4)))+MOD(CRC32(id),17),NOW(6)),throttle_count=throttle_count+1,updated_at=NOW(6) WHERE id=? AND worker_token=? AND status='RUNNING'",message,work.id(),work.token())==1)
   db.update("UPDATE product_capture_images SET status='PENDING',error_message=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=?",work.imageId(),work.organizationId());
 }
 @Transactional
 public void deferDownload(Work work,String message){
  deferFailure(work,"结果图片下载失败；保留已有生成结果："+message);
 }
 @Transactional
 public void deferFailure(Work work,String message){
  db.queryForObject("SELECT id FROM product_image_processing_jobs WHERE id=? AND organization_id=? FOR UPDATE",String.class,work.jobId(),work.organizationId());
  var rows=db.queryForList("SELECT retry_count FROM product_watermark_queue WHERE id=? AND worker_token=? AND status='RUNNING' FOR UPDATE",work.id(),work.token());
  if(rows.isEmpty())return;
  var row=rows.getFirst();
  int retries=row.get("retry_count") instanceof Number n?n.intValue():0;
  if(retries>=MAX_RETRIES){finish(work,null,null,"已自动重试6次仍失败："+message);return;}
  String progress="自动重试 "+(retries+1)+"/"+MAX_RETRIES+"："+message;
  progress=progress.substring(0,Math.min(1000,progress.length()));
  int delay=downloadRetryDelay(retries);
  if(db.update("UPDATE product_watermark_queue SET status='QUEUED',worker_token=NULL,lease_until=NULL,error_message=?,retry_count=?,next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(6)),updated_at=NOW(6) WHERE id=? AND worker_token=? AND status='RUNNING'",progress,retries+1,delay,work.id(),work.token())==1)
   db.update("UPDATE product_capture_images SET status='PENDING',error_message=?,updated_at=NOW(6) WHERE id=? AND organization_id=?",progress,work.imageId(),work.organizationId());
 }
 static int downloadRetryDelay(int retries){return new int[]{10,30,60,120,300,300}[Math.max(0,Math.min(5,retries))];}
 public void heartbeat(Work work){db.update("UPDATE product_watermark_queue SET lease_until=DATE_ADD(NOW(6),INTERVAL 1 HOUR) WHERE id=? AND worker_token=? AND status='RUNNING'",work.id(),work.token());}
 @Transactional
 public void finish(Work work,String url,Map<String,Object> result,String failure){
  // Serialize aggregation only within one job, so no sibling completion loses counts.
  db.queryForObject("SELECT id FROM product_image_processing_jobs WHERE id=? AND organization_id=? FOR UPDATE",String.class,work.jobId(),work.organizationId());
  String error=failure==null?null:failure.substring(0,Math.min(1000,failure.length()));
  int changed=db.update("UPDATE product_watermark_queue SET status=?,result_url=?,result_json=?,error_message=?,lease_until=NULL,updated_at=NOW(6) WHERE id=? AND worker_token=? AND status='RUNNING'",error==null?"SUCCEEDED":"FAILED",url,result==null?null:json.writeValueAsString(result),error,work.id(),work.token());
  if(changed!=1)return;
  if(error==null)db.update("UPDATE product_capture_images SET status='PROCESSED',processed_url=?,error_message=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=?",url,work.imageId(),work.organizationId());
  else db.update("UPDATE product_capture_images SET status='FAILED',error_message=?,updated_at=NOW(6) WHERE id=? AND organization_id=?",error,work.imageId(),work.organizationId());
  var rows=db.queryForList("SELECT image_id,status,result_url,result_json,error_message FROM product_watermark_queue WHERE job_id=?",work.jobId());
  var successes=new ArrayList<Map<String,Object>>();String firstError=null;int pending=0;
  for(var row:rows){
   if("SUCCEEDED".equals(row.get("status")))successes.add(Map.of("imageId",row.get("image_id"),"processedUrl",row.get("result_url"),"providerResponse",json.readValue((String)row.get("result_json"),Map.class)));
   else if("FAILED".equals(row.get("status"))){if(firstError==null)firstError=Objects.toString(row.get("error_message"),"图片处理失败");}
   else pending++;
  }
  String report=json.writeValueAsString(Map.of("dryRun",false,"images",successes));
  db.update("UPDATE product_image_processing_jobs SET processed_images=?,response_json=?,updated_at=NOW(6) WHERE id=?",successes.size(),report,work.jobId());
  if(pending==0){
   db.update("UPDATE product_image_processing_jobs SET status=?,error_message=?,completed_at=NOW(6),updated_at=NOW(6) WHERE id=?",firstError==null?"SUCCEEDED":"FAILED",firstError,work.jobId());
   db.update("UPDATE product_captures SET status=?,version=version+1,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='IMAGE_PROCESSING'",firstError==null?"READY":"FAILED",work.captureId(),work.organizationId());
  }
 }
 @Transactional
 public void finishExpired(Work work){
  db.queryForObject("SELECT id FROM product_image_processing_jobs WHERE id=? AND organization_id=? FOR UPDATE",String.class,work.jobId(),work.organizationId());
  var expired=db.queryForList("SELECT id FROM product_watermark_queue WHERE id=? AND worker_token=? AND status='RUNNING' AND lease_until<NOW(6) FOR UPDATE",work.id(),work.token());
  if(!expired.isEmpty())deferFailure(work,"服务中断或处理超时，自动核对已保存结果后继续");
 }
 public List<Work> expired(){
  return db.query("SELECT id,organization_id,capture_id,image_id,job_id,worker_token FROM product_watermark_queue WHERE status='RUNNING' AND lease_until<NOW(6)",(rs,n)->new Work(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)));
 }
 public List<Map<String,Object>> progress(String org,String capture){
  return db.queryForList("SELECT image_id AS imageId,status,error_message AS message,updated_at AS updatedAt FROM (SELECT image_id,status,error_message,updated_at,ROW_NUMBER() OVER(PARTITION BY image_id ORDER BY created_at DESC,id DESC) rn FROM product_watermark_queue WHERE organization_id=? AND capture_id=?) latest WHERE rn=1",org,capture);
 }
}
