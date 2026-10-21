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
@EnabledIfEnvironmentVariable(named="WATERMARK_QUEUE_TEST_DB",matches="jdbc:mysql://127\\.0\\.0\\.1:\\d+/watermark_queue_test.*")
class ProductWatermarkQueueIntegrationTest {
 static JdbcTemplate db;static TransactionTemplate tx;static ProductWatermarkQueue queue;
 String org=UUID.randomUUID().toString(),capture=UUID.randomUUID().toString();
 @BeforeAll static void schema()throws Exception{
  var ds=new DriverManagerDataSource(System.getenv("WATERMARK_QUEUE_TEST_DB"),System.getenv().getOrDefault("WATERMARK_QUEUE_TEST_USER","root"),System.getenv().getOrDefault("WATERMARK_QUEUE_TEST_PASSWORD",""));db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));queue=new ProductWatermarkQueue(db,new ObjectMapper());
  db.execute("CREATE TABLE product_captures(id CHAR(36) PRIMARY KEY,organization_id CHAR(36),status VARCHAR(32),deleted_at TIMESTAMP(6) NULL,version BIGINT DEFAULT 0,updated_at TIMESTAMP(6)) ENGINE=InnoDB");
  db.execute("CREATE TABLE product_image_processing_jobs(id CHAR(36) PRIMARY KEY,organization_id CHAR(36),capture_id CHAR(36),provider VARCHAR(32),status VARCHAR(32),processed_images INT DEFAULT 0,response_json JSON,error_message TEXT,completed_at TIMESTAMP(6) NULL,updated_at TIMESTAMP(6)) ENGINE=InnoDB");
  db.execute("CREATE TABLE product_capture_images(id CHAR(36) PRIMARY KEY,organization_id CHAR(36),capture_id CHAR(36),status VARCHAR(32),processed_url TEXT,error_message TEXT,updated_at TIMESTAMP(6)) ENGINE=InnoDB");
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V81__watermark_image_queue.sql")).split(";"))if(!sql.isBlank())db.execute(sql);
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V83__watermark_queue_backoff.sql")).split(";"))if(!sql.isBlank())db.execute(sql);
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V79__product_watermark_checks.sql")).split(";"))if(!sql.isBlank())db.execute(sql);
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V80__local_watermark_screen.sql")).split(";"))if(!sql.isBlank())db.execute(sql);
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V109__watermark_automatic_retries.sql")).split(";"))if(!sql.isBlank())db.execute(sql);
  for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V123__watermark_missing_result_recovery.sql")).split(";"))if(!sql.isBlank())db.execute(sql);
 }
 @BeforeEach void reset(){db.update("DELETE FROM product_watermark_checks");db.update("DELETE FROM product_watermark_queue");db.update("DELETE FROM product_capture_images");db.update("DELETE FROM product_image_processing_jobs");db.update("DELETE FROM product_captures");}
 @Test void generationRetryIsAtomicTenantScopedAndNeverReplaysReceivedResults(){
  var ledger=new ProductWatermarkLedger(db);
  String id="a".repeat(64);ledger.prepare(id,org,capture,UUID.randomUUID().toString(),"b".repeat(64),"unit-source");
  db.update("UPDATE product_watermark_checks SET status='REVIEW_REQUIRED' WHERE id=?",id);
  assertFalse(ledger.retryGeneration(id,"foreign","REVIEW_REQUIRED",0));
  assertTrue(ledger.retryGeneration(id,org,"REVIEW_REQUIRED",0));
  assertFalse(ledger.retryGeneration(id,org,"REVIEW_REQUIRED",0));
  db.update("UPDATE product_watermark_checks SET status='REVIEW_REQUIRED',generation_json='{}' WHERE id=?",id);
  assertFalse(ledger.retryGeneration(id,org,"REVIEW_REQUIRED",1));
  ledger.recoverReceived(id,org,"REVIEW_REQUIRED","{}");
  assertEquals("RESULT_RECEIVED",ledger.get(id,org).get("status"));
  assertThrows(IllegalStateException.class,()->ledger.recoverReceived(id,"foreign","RESULT_RECEIVED","{}"));
 }
 @Test void recordedGenerationSurvivesModelChangesOnlyForTheExactSourceAndTenant(){
  var ledger=new ProductWatermarkLedger(db);String image=UUID.randomUUID().toString(),hash="c".repeat(64);
  String old="a".repeat(64),next="b".repeat(64);
  ledger.prepare(old,org,capture,image,hash,"unit-source");
  db.update("UPDATE product_watermark_checks SET status='RESULT_RECEIVED',detection_json='{\"decision\":\"UNCERTAIN\"}',generation_json='{}',candidate_key='saved-image' WHERE id=?",old);
  ledger.prepare(next,org,capture,image,hash,"unit-source");
  assertFalse(ledger.reuseReceivedGeneration(next,"foreign",capture,image,hash));
  assertFalse(ledger.reuseReceivedGeneration(next,org,capture,image,"d".repeat(64)));
  assertTrue(ledger.reuseReceivedGeneration(next,org,capture,image,hash));
  assertEquals("RESULT_RECEIVED",ledger.get(next,org).get("status"));
  assertEquals("{}",ledger.get(next,org).get("generation_json"));
  assertEquals("saved-image",ledger.get(next,org).get("candidate_key"));
 }
 @Test void missingResultsRequireTwoSpacedConfirmationsAndPreserveBoundedAttemptHistory(){
  var ledger=new ProductWatermarkLedger(db);String id="e".repeat(64);
  ledger.prepare(id,org,capture,UUID.randomUUID().toString(),"f".repeat(64),"source");
  String response="{\"result\":{\"url\":\"https://example.test/expired\"}}";
  db.update("UPDATE product_watermark_checks SET status='RESULT_RECEIVED',generation_json=?,detection_json='{}' WHERE id=?",response,id);
  assertThrows(IllegalArgumentException.class,()->tx.execute(s->ledger.missingResult(id,org,response,503)));
  assertFalse(tx.<Boolean>execute(s->ledger.missingResult(id,"foreign",response,404)));
  assertFalse(tx.<Boolean>execute(s->ledger.missingResult(id,org,"{}",404)));
  assertFalse(tx.<Boolean>execute(s->ledger.missingResult(id,org,response,404)));
  assertFalse(tx.<Boolean>execute(s->ledger.missingResult(id,org,response,404)));
  assertEquals("RESULT_RECEIVED",ledger.get(id,org).get("status"));
  db.update("UPDATE product_watermark_checks SET missing_result_since=DATE_SUB(NOW(6),INTERVAL 11 SECOND) WHERE id=?",id);
  assertTrue(tx.<Boolean>execute(s->ledger.missingResult(id,org,response,404)));
  assertFalse(tx.<Boolean>execute(s->ledger.missingResult(id,org,response,404)));
  var row=ledger.get(id,org);assertEquals("DETECTED",row.get("status"));assertNull(row.get("generation_json"));
  assertEquals(1,row.get("generation_retry_count"));assertTrue(row.get("unavailable_results_json").toString().contains("expired"));
  assertEquals(id+"_retry_1",AiWatermarkRemovalProvider.generationRequestId(id,row));
  db.update("UPDATE product_watermark_checks SET status='EDITING' WHERE id=?",id);ledger.generated(id,org,response);
  assertFalse(tx.<Boolean>execute(s->ledger.missingResult(id,org,response,410)));
  db.update("UPDATE product_watermark_checks SET missing_result_since=DATE_SUB(NOW(6),INTERVAL 11 SECOND) WHERE id=?",id);
  assertTrue(tx.<Boolean>execute(s->ledger.missingResult(id,org,response,410)));
  db.update("UPDATE product_watermark_checks SET status='EDITING' WHERE id=?",id);ledger.generated(id,org,response);
  assertThrows(IllegalStateException.class,()->tx.execute(s->ledger.missingResult(id,org,response,404)));
  assertEquals(2,db.queryForObject("SELECT JSON_LENGTH(unavailable_results_json) FROM product_watermark_checks WHERE id=?",Integer.class,id));
 }
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
 @Test void restartRequeuesExpiredWorkForDurableResultReconciliation(){
  seed(2);var work=tx.execute(s->queue.claim());
  tx.executeWithoutResult(s->queue.release(work.get(1)));
  tx.executeWithoutResult(s->queue.finishExpired(work.get(0)));
  assertEquals("RUNNING",db.queryForObject("SELECT status FROM product_watermark_queue WHERE id=?",String.class,work.get(0).id()));
  db.update("UPDATE product_watermark_queue SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",work.get(0).id());
  for(var expired:queue.expired())tx.executeWithoutResult(s->queue.finishExpired(expired));
  var claimed=tx.execute(s->queue.claim());assertEquals(1,claimed.size());assertEquals(work.get(1).id(),claimed.getFirst().id());
  assertEquals("QUEUED",db.queryForObject("SELECT status FROM product_watermark_queue WHERE id=?",String.class,work.get(0).id()));
  assertEquals(1,db.queryForObject("SELECT retry_count FROM product_watermark_queue WHERE id=?",Integer.class,work.get(0).id()));
  tx.executeWithoutResult(s->queue.finish(work.get(0),"/late",Map.of(),null));
  assertEquals("QUEUED",db.queryForObject("SELECT status FROM product_watermark_queue WHERE id=?",String.class,work.get(0).id()));
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
 @Test void failureRetriesPersistAcrossQueueRecreationAndPreserveSuccessfulSibling(){
  var job=seed(2);var work=tx.execute(s->queue.claim());
  tx.executeWithoutResult(s->queue.finish(work.get(0),"/verified",Map.of("checkId","kept"),null));
  var retry=work.get(1);
  for(int count=1;count<=6;count++){
   final var current=retry;
   tx.executeWithoutResult(s->queue.deferFailure(current,"read or generation failure"));
   assertEquals(count,db.queryForObject("SELECT retry_count FROM product_watermark_queue WHERE id=?",Integer.class,retry.id()));
   assertEquals("RUNNING",db.queryForObject("SELECT status FROM product_image_processing_jobs WHERE id=?",String.class,job.getId()));
   assertTrue(tx.execute(s->queue.claim()).isEmpty());
   db.update("UPDATE product_watermark_queue SET next_attempt_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",retry.id());
   var restored=new ProductWatermarkQueue(db,new ObjectMapper());
   retry=tx.execute(s->restored.claim()).getFirst();
  }
  final var exhausted=retry;
  tx.executeWithoutResult(s->queue.deferFailure(exhausted,"still rejected"));
  assertEquals("FAILED",db.queryForObject("SELECT status FROM product_image_processing_jobs WHERE id=?",String.class,job.getId()));
  assertEquals(1,db.queryForObject("SELECT processed_images FROM product_image_processing_jobs WHERE id=?",Integer.class,job.getId()));
  assertEquals("/verified",db.queryForObject("SELECT processed_url FROM product_capture_images WHERE id=?",String.class,work.get(0).imageId()));
  assertTrue(db.queryForObject("SELECT error_message FROM product_watermark_queue WHERE id=?",String.class,retry.id()).contains("6次"));
 }
}
