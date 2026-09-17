package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies the actual worker INSERT and unique request constraint in an explicitly disposable schema. */
@EnabledIfEnvironmentVariable(named="HEYI_ARTWORK_LIBRARY_TEST_URL",matches="jdbc:mysql://(127\\.0\\.0\\.1|heyi-video-e2e-mysql):[0-9]+/heyi_artwork_library_test[a-zA-Z0-9_]*(\\?.*)?")
class CommerceSavedArtworkSqlTest {
 @TempDir Path storage;
 @Test void localReadyInsertUsesFrozenBytesAndDatabaseIdempotencyWithoutModelReservations()throws Exception{
  var source=new DriverManagerDataSource(System.getenv("HEYI_ARTWORK_LIBRARY_TEST_URL"),System.getenv("HEYI_ARTWORK_LIBRARY_TEST_USER"),System.getenv("HEYI_ARTWORK_LIBRARY_TEST_PASSWORD"));
  try(var connection=source.getConnection();var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"commerce_batch_artwork_jobs",new String[]{"TABLE"})){
   if(!tables.next())ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V75__batch_artwork_jobs.sql"));
  }
  var db=new JdbcTemplate(source);var archive=new CommerceImageArchive(storage.toString());var models=mock(ModelConfigService.class);var gateway=mock(CommerceDesignImageGateway.class);var ledger=mock(CommerceBatchArtworkLedger.class);var json=new ObjectMapper();
  var service=new CommerceBatchArtworkService(db,mock(CommerceBatchCreativePlanMapper.class),mock(CommerceTemplateMapper.class),mock(ProductCaptureService.class),archive,mock(MarketplaceImageDownloader.class),models,gateway,ledger,mock(CommerceBatchExecutionService.class),json,mock(CommerceCaptureSourceArchiveService.class));
  String runId=UUID.randomUUID().toString();var run=new CommerceBatchExecution(runId,"saved-sql-org","batch","u","VARIATE","RUNNING",null,"lease",Instant.now(),Instant.now(),Instant.now());
  var user=new PlatformPrincipal("u","saved-sql-org","org","u","u","",true,List.of(),List.of("product:edit","product:publish"));
  var png=SavedArtworkFixture.image(0xff7744bb,true);var stored=archive.store(user.organizationId(),png);
  var frozen=new CommerceBatchCreativePlanValidator.FrozenArtwork("source-library-job",stored.storageKey(),stored.sha256());
  var selection=new CommerceBatchCreativePlanValidator.Selection("capture",1,"image");
  try{
   for(int n=0;n<2;n++){
    CommerceBatchStageHandler.Result result=ReflectionTestUtils.invokeMethod(service,"archiveSavedArtwork",run,selection,"VARIATE_G1",1,frozen,null,user);
    assertEquals("QUEUED",result.pendingStatus());
   }
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE execution_id=?",Integer.class,runId));
   var row=db.queryForMap("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=?",runId);
   assertEquals("READY",row.get("status"));assertEquals("SAVED_ARTWORK",row.get("model_config_id"));assertEquals(stored.sha256(),row.get("content_sha256"));assertEquals(stored.storageKey(),row.get("storage_key"));
   assertEquals(CommerceBatchArtworkService.requestKey(runId,"capture","VARIATE_G1",1,null),row.get("request_key"));
   assertEquals("source-library-job",json.readTree((String)row.get("result_json")).path("sourceArtifactId").asText());
   assertEquals(0,json.readTree((String)row.get("result_json")).path("modelCalls").asInt());
   assertEquals(List.of(stored.storageKey()),json.readValue((String)row.get("reference_keys"),List.class));
   verifyNoInteractions(models,gateway,ledger);
  }finally{db.update("DELETE FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=?",runId,user.organizationId());}
 }
}
