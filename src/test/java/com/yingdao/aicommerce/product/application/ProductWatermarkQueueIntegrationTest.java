package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable MySQL only. No HTTP, real images, or paid model requests. */
@EnabledIfEnvironmentVariable(named="WATERMARK_QUEUE_TEST_DB",matches="jdbc:mysql://127\\.0\\.0\\.1:3306/watermark_queue_test.*")
class ProductWatermarkQueueIntegrationTest {
 static JdbcTemplate db;static TransactionTemplate tx;static ProductWatermarkQueue queue;
 String org=UUID.randomUUID().toString(),capture=UUID.randomUUID().toString();
 @BeforeAll static void schema()throws Exception{
  var ds=new DriverManagerDataSource(System.getenv("WATERMARK_QUEUE_TEST_DB"),"root","");db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));queue=new ProductWatermarkQueue(db,new ObjectMapper());
  db.execute("CREATE TABLE product_captures(id CHAR(36) PRIMARY KEY,organization_id CHAR(36),status VARCHAR(32),deleted_at TIMESTAMP(6) NULL,version BIGINT DEFAULT 0,updated_at TIMESTAMP(6)) ENGINE=InnoDB");
  db.execute("CREATE TABLE product_image_processing_jobs(id CHAR(36) PRIMARY KEY,organization_id CHAR(36),capture_id CHAR(36),provider VARCHAR(32),status VARCHAR(32),processed_images INT DEFAULT 0,response_json JSON,error_message TEXT,completed_at TIMESTAMP(6) NULL,updated_at TIMESTAMP(6)) ENGINE=InnoDB");
  db.execute("CREATE TABLE product_capture_images(id CHAR(36) PRIMARY KEY,organization_id CHAR(36),capture_id CHAR(36),status VARCHAR(32),processed_url TEXT,error_message TEXT,updated_at TIMESTAMP(6)) ENGINE=InnoDB");
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V81__watermark_image_queue.sql")).split(";"))if(!sql.isBlank())db.execute(sql);
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V83__watermark_queue_backoff.sql")).split(";"))if(!sql.isBlank())db.execute(sql);
 }
 @BeforeEach void reset(){db.update("DELETE FROM product_watermark_queue");db.update("DELETE FROM product_capture_images");db.update("DELETE FROM product_image_processing_jobs");db.update("DELETE FROM product_captures");}
 ProductImageProcessingJob seed(int count){
  var job=new ProductImageProcessingJob(org,capture,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"AI_WATERMARK_V1",count,"{}");
  db.update("INSERT INTO product_captures(id,organization_id,status) VALUES(?,?,'IMAGE_PROCESSING')",capture,org);
  db.update("INSERT INTO product_image_processing_jobs(id,organization_id,capture_id,provider,status) VALUES(?,?,?,'AI_WATERMARK_V1','RUNNING')",job.getId(),org,capture);
  var images=new ArrayList<ProductCaptureImage>();
  for(int i=0;i<count;i++){var image=new ProductCaptureImage(org,capture,"https://example.com/test.png","MAIN","",i,"image/png","{}");images.add(image);db.update("INSERT INTO product_capture_images(id,organization_id,capture_id,status) VALUES(?,?,?,'PENDING')",image.getId(),org,capture);}
  tx.executeWithoutResult(s->{queue.enqueue(job,images);queue.enqueue(job,images);});
  assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM product_watermark_queue",Integer.class));return job;
 }
 @Test void concurrentDispatchersShare500SlotsAndOverflowWaits()throws Exception{
  seed(505);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var start=new CountDownLatch(1);
   Callable<List<ProductWatermarkQueue.Work>> task=()->{start.await();return tx.execute(s->queue.claim());};
   var a=pool.submit(task);var b=pool.submit(task);start.countDown();
   var all=new ArrayList<>(a.get(30,TimeUnit.SECONDS));all.addAll(b.get(30,TimeUnit.SECONDS));
   assertEquals(500,all.size());assertEquals(500,all.stream().map(ProductWatermarkQueue.Work::id).distinct().count());
   assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM product_watermark_queue WHERE status='QUEUED'",Integer.class));
   assertTrue(tx.execute(s->queue.claim()).isEmpty());
   tx.executeWithoutResult(s->queue.finish(all.getFirst(),"/verified",Map.of("checkId","test"),null));
   assertEquals(1,tx.execute(s->queue.claim()).size());
   assertEquals(500,db.queryForObject("SELECT COUNT(*) FROM product_watermark_queue WHERE status='RUNNING'",Integer.class));
   assertTrue(queue.progress(UUID.randomUUID().toString(),capture).isEmpty());
  }
 }
 @Test void failureDoesNotCancelSiblingAndAggregatesOnlyAfterAllFinish(){
  var job=seed(2);var work=tx.execute(s->queue.claim());
  tx.executeWithoutResult(s->queue.finish(work.get(0),null,null,"model rejected"));
  assertEquals("RUNNING",db.queryForObject("SELECT status FROM product_image_processing_jobs WHERE id=?",String.class,job.getId()));
  tx.executeWithoutResult(s->queue.finish(work.get(1),"/verified",Map.of("checkId","test"),null));
  assertEquals("FAILED",db.queryForObject("SELECT status FROM product_image_processing_jobs WHERE id=?",String.class,job.getId()));
  assertEquals(1,db.queryForObject("SELECT processed_images FROM product_image_processing_jobs WHERE id=?",Integer.class,job.getId()));
  assertEquals("FAILED",db.queryForObject("SELECT status FROM product_captures WHERE id=?",String.class,capture));
  tx.executeWithoutResult(s->queue.finish(work.get(0),"/late",Map.of(),null));
  assertEquals("FAILED",db.queryForObject("SELECT status FROM product_watermark_queue WHERE id=?",String.class,work.get(0).id()));
 }
 @Test void restartKeepsQueuedAndQuarantinesExpiredWorkWithoutResubmission(){
  seed(2);var work=tx.execute(s->queue.claim());
  tx.executeWithoutResult(s->queue.release(work.get(1)));
  tx.executeWithoutResult(s->queue.finishExpired(work.get(0)));
  assertEquals("RUNNING",db.queryForObject("SELECT status FROM product_watermark_queue WHERE id=?",String.class,work.get(0).id()));
  db.update("UPDATE product_watermark_queue SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",work.get(0).id());
  for(var expired:queue.expired())tx.executeWithoutResult(s->queue.finishExpired(expired));
  var claimed=tx.execute(s->queue.claim());assertEquals(1,claimed.size());assertEquals(work.get(1).id(),claimed.getFirst().id());
  assertEquals("FAILED",db.queryForObject("SELECT status FROM product_watermark_queue WHERE id=?",String.class,work.get(0).id()));
 }
 @Test void legacyRunningJobWithoutQueueIsRecoveredOnce(){
  seed(3);db.update("DELETE FROM product_watermark_queue");
  tx.executeWithoutResult(s->queue.recoverUnqueuedJobs());
  tx.executeWithoutResult(s->queue.recoverUnqueuedJobs());
  assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM product_watermark_queue",Integer.class));
  assertEquals(3,tx.execute(s->queue.claim()).size());
 }
 @Test void explicitRateLimitWaitsInQueueUntilBackoffExpires(){
  seed(1);var work=tx.execute(s->queue.claim()).getFirst();
  tx.executeWithoutResult(s->queue.defer(work,"并发已满"));
  assertTrue(tx.execute(s->queue.claim()).isEmpty());
  assertEquals("QUEUED",db.queryForObject("SELECT status FROM product_watermark_queue WHERE id=?",String.class,work.id()));
  db.update("UPDATE product_watermark_queue SET next_attempt_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",work.id());
  assertEquals(1,tx.execute(s->queue.claim()).size());
 }
 @Test void allSuccessMakesCaptureReady(){
  var job=seed(2);for(var w:tx.execute(s->queue.claim()))tx.executeWithoutResult(s->queue.finish(w,"/verified",Map.of("checkId","test"),null));
  assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM product_image_processing_jobs WHERE id=?",String.class,job.getId()));
  assertEquals("READY",db.queryForObject("SELECT status FROM product_captures WHERE id=?",String.class,capture));
 }
}
