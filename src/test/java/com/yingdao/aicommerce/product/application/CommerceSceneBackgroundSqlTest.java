package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;

import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual cache-hit INSERT ... SELECT and lease predicates; never uses an application database. */
@EnabledIfEnvironmentVariable(named="HEYI_ARTWORK_LIBRARY_TEST_URL",
        matches="jdbc:mysql://(127\\.0\\.0\\.1|heyi-video-e2e-mysql):[0-9]+/heyi_artwork_library_test[a-zA-Z0-9_]*(\\?.*)?")
class CommerceSceneBackgroundSqlTest {
    static JdbcTemplate db;
    @TempDir Path storage;
    final ObjectMapper json=new ObjectMapper();
    final ModelConfigService models=mock(ModelConfigService.class);
    final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);
    final CommerceBatchArtworkLedger ledger=mock(CommerceBatchArtworkLedger.class);
    final MarketplaceImageDownloader downloader=mock(MarketplaceImageDownloader.class);
    final CommerceCaptureSourceArchiveService sources=mock(CommerceCaptureSourceArchiveService.class);
    final ModelConfigService.RuntimeModelConnection model=new ModelConfigService.RuntimeModelConnection(
            "scene-sql-model","https://unused.invalid/v1","grok-imagine-image-2.0","OPENAI_COMPATIBLE","CUSTOM","unit-only");
    String org,runId;
    PlatformPrincipal user;
    CommerceBatchExecution run;
    CommerceImageArchive archive;
    CommerceBatchArtworkService service;
    CommercePrintTemplateService.Configuration config;
    CommerceImageArchive.Archived original,front,background;
    ProductCaptureService.CaptureDetail detail;
    Plan plan;
    List<String> references;
    String cacheKey;

    @BeforeAll static void isolatedSchema()throws Exception {
        String url=System.getenv("HEYI_ARTWORK_LIBRARY_TEST_URL");
        assertTrue(url.matches("jdbc:mysql://(127\\.0\\.0\\.1|heyi-video-e2e-mysql):[0-9]+/heyi_artwork_library_test[a-zA-Z0-9_]*(\\?.*)?"));
        var source=new DriverManagerDataSource(url,System.getenv("HEYI_ARTWORK_LIBRARY_TEST_USER"),System.getenv("HEYI_ARTWORK_LIBRARY_TEST_PASSWORD"));
        try(var connection=source.getConnection()){
            ensureMigration(connection,"commerce_batch_executions","V68__batch_execution_checkpoints.sql");
            ensureMigration(connection,"commerce_batch_artwork_jobs","V75__batch_artwork_jobs.sql");
            ensureMigration(connection,"commerce_mockup_derivatives","V97__commerce_mockup_derivatives.sql");
            try(var statement=connection.createStatement()){
                statement.execute("CREATE TABLE IF NOT EXISTS commerce_batch_artwork_checks(job_id VARCHAR(36) PRIMARY KEY,report_json JSON NOT NULL,created_at TIMESTAMP(6) NOT NULL)");
            }
        }
        db=new JdbcTemplate(source);
    }

    private static void ensureMigration(Connection connection,String table,String migration)throws Exception {
        try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,table,new String[]{"TABLE"})){
            if(!tables.next())ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+migration));
        }
    }

    @BeforeEach void fixture()throws Exception {
        org=UUID.randomUUID().toString();runId=UUID.randomUUID().toString();
        user=new PlatformPrincipal("scene-user",org,"scene-sql","u","u","",true,List.of(),List.of("product:edit","product:publish"));
        var now=Instant.now();
        run=new CommerceBatchExecution(runId,org,UUID.randomUUID().toString(),user.userId(),"MOCKUP","RUNNING",null,"scene-lease",now.plusSeconds(600),now,now);
        db.update("INSERT INTO commerce_batch_executions(id,organization_id,batch_id,created_by,stage,status,message,lease_token,lease_until,created_at,updated_at) VALUES(?,?,?,?,'MOCKUP','RUNNING','test','scene-lease',DATE_ADD(NOW(6),INTERVAL 10 MINUTE),NOW(6),NOW(6))",
                run.id(),org,run.batchId(),run.createdBy());
        archive=new CommerceImageArchive(storage.toString());
        original=archive.store(org,SavedArtworkFixture.image(0xff282828,false));
        front=archive.store(org,SavedArtworkFixture.image(0xffeeeeee,false));
        background=archive.store(org,SavedArtworkFixture.image(0xff806044,false));
        var base=SavedArtworkFixture.configuration();
        config=new CommercePrintTemplateService.Configuration(base.product(),base.areas(),Map.of("FRONT",front.sha256()),
                base.reconstruction(),base.sourceImageId(),base.referenceSetId(),base.surfacePrints(),base.renderedViews(),
                base.blankRenderedViews(),base.surfaceUvViews(),base.placementMasks(),base.renderPrintIds(),base.renderBinding(),base.photoPrints());
        var selected=new Selection("source-1",7,"selected-image");
        plan=new Plan("snapshot",List.of(selected),3,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",GROK_SCENE_POLICY);
        var image=mock(ProductCaptureImage.class);when(image.getId()).thenReturn("selected-image");
        detail=new ProductCaptureService.CaptureDetail(mock(ProductCapture.class),List.of(),List.of(image),List.of(),Map.of(),Map.of());
        when(sources.find(anyString(),eq("selected-image"),eq(7L),eq(user))).thenReturn(original.storageKey());
        when(models.selectCommerceMockupRuntimeConnection(user)).thenReturn(Optional.of(model));
        service=new CommerceBatchArtworkService(db,mock(CommerceBatchCreativePlanMapper.class),mock(CommerceTemplateMapper.class),
                mock(ProductCaptureService.class),archive,downloader,models,gateway,ledger,mock(CommerceBatchExecutionService.class),json,sources);
        references=CommerceSceneBackgroundAssets.references(original.storageKey(),config,org);
        var cache=new CommerceMockupDerivativeCache(db,archive,json);
        String fingerprint=CommerceBatchArtworkService.fingerprint(model);
        cacheKey=CommerceSceneBackgroundAssets.cacheKey(cache,references,fingerprint,CommerceSceneBackgroundAssets.PROMPT);
        cache.store(org,cacheKey,CommerceSceneBackgroundAssets.KIND,Base64.getDecoder().decode(archive.read(org,background.storageKey()).dataBase64()),
                CommerceSceneBackgroundAssets.report(references,fingerprint));
    }

    @AfterEach void cleanup(){
        if(db==null||runId==null)return;
        db.update("DELETE c FROM commerce_batch_artwork_checks c JOIN commerce_batch_artwork_jobs j ON j.id=c.job_id WHERE j.execution_id=? AND j.organization_id=?",runId,org);
        db.update("DELETE FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=?",runId,org);
        db.update("DELETE FROM commerce_mockup_derivatives WHERE organization_id=?",org);
        db.update("DELETE FROM commerce_batch_executions WHERE id=? AND organization_id=?",runId,org);
    }

    private CommerceBatchStageHandler.Result prepare(Selection source,List<Map<String,Object>> jobs){
        return ReflectionTestUtils.invokeMethod(service,"prepareScene",run,plan,source,detail,config,jobs,user);
    }
    private List<Map<String,Object>> jobs(){
        return db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? ORDER BY capture_id",runId,org);
    }

    @Test void cachedReadyInsertBindsSourceReferencesAndCacheWithoutAnotherProviderCall(){
        var source=plan.sources().getFirst();
        var result=prepare(source,List.of());assertEquals("QUEUED",result.pendingStatus(),result.message());
        var rows=jobs();assertEquals(1,rows.size());var row=rows.getFirst();
        assertEquals(source.captureId(),row.get("capture_id"));assertEquals(7L,((Number)row.get("capture_version")).longValue());
        assertEquals("READY",row.get("status"));assertEquals("SCENE_BACKGROUND",row.get("kind"));
        assertEquals(0,((Number)row.get("variant_index")).intValue());assertNull(row.get("color_key"));
        assertEquals(CommerceBatchArtworkService.requestKey(runId,source.captureId(),"SCENE_BACKGROUND",0,null),row.get("request_key"));
        assertEquals(CommerceSceneBackgroundAssets.PROMPT,row.get("prompt"));
        assertEquals(model.id(),row.get("model_config_id"));assertEquals(CommerceBatchArtworkService.fingerprint(model),row.get("model_fingerprint"));
        assertEquals(references,json.readValue((String)row.get("reference_keys"),List.class));
        assertEquals(original.storageKey(),references.getFirst());assertEquals(front.storageKey(),references.get(1));
        assertNotEquals(CommerceSurfaceTemplateInputs.key(org,config.sourceImageId()),references.getFirst());
        assertEquals(background.storageKey(),row.get("storage_key"));assertEquals(background.sha256(),row.get("content_sha256"));
        var report=json.readTree((String)row.get("result_json"));assertEquals("grok-empty-scene-v1",report.path("method").asText());
        assertEquals(json.valueToTree(references),report.path("referenceKeys"));
        assertEquals(report,json.readTree(db.queryForObject("SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id=?",String.class,row.get("id"))));
        assertNotNull(new CommerceMockupDerivativeCache(db,archive,json).find(org,cacheKey));
        clearInvocations(models,sources);
        assertNull(prepare(source,rows));
        verifyNoInteractions(models,sources,gateway,ledger,downloader);
    }

    @Test void duplicateStaleTurnsCreateOneRowPerSourceAndAnotherSourceReusesTheSamePlate(){
        var first=plan.sources().getFirst();
        assertEquals("QUEUED",prepare(first,List.of()).pendingStatus());
        assertEquals("UNKNOWN",prepare(first,List.of()).pendingStatus());
        assertEquals(1,jobs().size(),"A repeated stale worker view must hit the unique request constraint");
        var second=new Selection("source-2",7,"selected-image");
        assertEquals("QUEUED",prepare(second,jobs()).pendingStatus());
        var rows=jobs();assertEquals(2,rows.size());
        assertEquals(List.of("source-1","source-2"),rows.stream().map(row->row.get("capture_id")).toList());
        for(var row:rows){
            assertEquals(background.storageKey(),row.get("storage_key"));
            assertEquals(references,json.readValue((String)row.get("reference_keys"),List.class));
            assertEquals(CommerceBatchArtworkService.requestKey(runId,(String)row.get("capture_id"),"SCENE_BACKGROUND",0,null),row.get("request_key"));
        }
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_checks c JOIN commerce_batch_artwork_jobs j ON j.id=c.job_id WHERE j.execution_id=?",Integer.class,runId));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM commerce_mockup_derivatives WHERE organization_id=?",Integer.class,org));
        verifyNoInteractions(gateway,ledger,downloader);
    }

    @Test void expiredReplacedOrPausedLeaseCannotInsertEvenWhenSceneCacheExists(){
        for(String condition:List.of("expired","replaced-token","other-owner","paused")){
            db.update("UPDATE commerce_batch_executions SET status='RUNNING',created_by=?,lease_token='scene-lease',lease_until=DATE_ADD(NOW(6),INTERVAL 10 MINUTE) WHERE id=?",run.createdBy(),runId);
            switch(condition){
                case "expired"->db.update("UPDATE commerce_batch_executions SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",runId);
                case "replaced-token"->db.update("UPDATE commerce_batch_executions SET lease_token='new-worker' WHERE id=?",runId);
                case "other-owner"->db.update("UPDATE commerce_batch_executions SET created_by='other-user' WHERE id=?",runId);
                case "paused"->db.update("UPDATE commerce_batch_executions SET status='PAUSED' WHERE id=?",runId);
            }
            var result=prepare(plan.sources().getFirst(),List.of());
            assertEquals("UNKNOWN",result.pendingStatus(),condition+": "+result.message());
            assertTrue(jobs().isEmpty(),condition+" must not insert a cached READY row");
        }
        verifyNoInteractions(gateway,ledger,downloader);
    }
}
