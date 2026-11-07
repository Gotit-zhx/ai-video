package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs real local image processing and MySQL idempotency against an explicitly disposable schema. */
@EnabledIfEnvironmentVariable(named="HEYI_LOCAL_PHOTO_TEST_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_local_photo_test[a-zA-Z0-9_]*")
class CommerceLocalPhotoIntegrationTest {
    static JdbcTemplate db;
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    CommerceImageArchive archive;
    final PlatformPrincipal user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:edit","product:publish"));
    final CommerceBatchCreativePlanMapper plans=mock(CommerceBatchCreativePlanMapper.class);
    final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
    final ProductCaptureService captures=mock(ProductCaptureService.class);
    final MarketplaceImageDownloader downloader=mock(MarketplaceImageDownloader.class);
    final ModelConfigService models=mock(ModelConfigService.class);
    final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);
    final CommerceBatchArtworkLedger ledger=mock(CommerceBatchArtworkLedger.class);
    final CommerceBatchExecutionService executions=mock(CommerceBatchExecutionService.class);
    final CommerceCaptureSourceArchiveService sourceArchives=mock(CommerceCaptureSourceArchiveService.class);

    @BeforeAll static void schema()throws Exception {
        var source=new DriverManagerDataSource(System.getenv("HEYI_LOCAL_PHOTO_TEST_URL"),System.getenv().getOrDefault("HEYI_TEST_DB_USER","root"),System.getenv().getOrDefault("HEYI_TEST_DB_PASSWORD",""));
        try(var connection=source.getConnection()) {
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})) {assertFalse(tables.next(),"Only an empty disposable schema is accepted");}
            for(var file:List.of("V68__batch_execution_checkpoints.sql","V73__batch_model_call_limits.sql","V75__batch_artwork_jobs.sql","V96__batch_artwork_colors.sql","V97__commerce_mockup_derivatives.sql","V100__commerce_matte_guides.sql"))
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
            try(var statement=connection.createStatement()) {
                statement.execute("CREATE TABLE commerce_batch_artwork_checks(job_id VARCHAR(36) PRIMARY KEY,report_json JSON NOT NULL,created_at TIMESTAMP(6) NOT NULL)");
                statement.execute("CREATE TABLE commerce_batch_creation_modes(batch_id VARCHAR(36),organization_id VARCHAR(36),mode VARCHAR(32))");
            }
        }
        db=new JdbcTemplate(source);
    }
    @BeforeEach void setup() {
        for(var table:List.of("commerce_batch_artwork_checks","commerce_mockup_derivatives","commerce_batch_artwork_jobs","commerce_batch_model_reservations","commerce_batch_model_limits","commerce_batch_execution_events","commerce_batch_creation_modes","commerce_batch_executions"))db.update("DELETE FROM "+table);
        archive=new CommerceImageArchive(directory.toString());
        db.update("INSERT INTO commerce_batch_executions(id,organization_id,batch_id,created_by,stage,status,message,lease_token,lease_until,created_at,updated_at) VALUES('run','org','batch','u','MOCKUP','RUNNING','test','lease',DATE_ADD(NOW(6),INTERVAL 20 MINUTE),NOW(6),NOW(6))");
        db.update("INSERT INTO commerce_batch_model_limits(execution_id,organization_id,max_calls,reserved_calls,created_at) VALUES('run','org',3,3,NOW(6))");
        db.update("INSERT INTO commerce_batch_creation_modes VALUES('batch','org','CREATIVE_ONLY')");
    }
    CommerceBatchExecution run(String stage) {return new CommerceBatchExecution("run","org","batch","u",stage,"RUNNING",null,"lease",Instant.now().plusSeconds(1200),Instant.now(),Instant.now());}
    CommerceBatchArtworkService service(){return new CommerceBatchArtworkService(db,plans,templates,captures,archive,downloader,models,gateway,ledger,executions,json,sourceArchives);}
    CommerceLocalPhotoWorkflow local(){return new CommerceLocalPhotoWorkflow(db,archive,json);}
    CommerceBatchCreativePlanValidator.Selection selection(){return new CommerceBatchCreativePlanValidator.Selection("capture",1,"image");}
    List<Map<String,Object>> jobs(){return db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id='run' ORDER BY created_at,id");}
    static byte[] png(boolean art)throws Exception {
        var image=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,256,256);
        if(art){g.setColor(new Color(160,40,80));g.fillOval(45,45,166,166);g.setColor(Color.WHITE);g.fillOval(85,85,86,86);}
        else {g.setColor(new Color(220,211,193));g.fillPolygon(new int[]{75,105,151,181,231,211,181,181,75,75,45,25},new int[]{35,25,25,35,80,120,105,231,231,105,120,80},12);}
        g.dispose();var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
    void ready(String id,String kind,int variant,String key) {
        db.update("INSERT INTO commerce_batch_artwork_jobs(id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,storage_key,content_sha256,created_at,updated_at) VALUES(?,'org','run','capture',1,?,?,?,'READY','original prompt','original-model','fingerprint','[]',?,?,NOW(6),NOW(6))",id,kind,variant,id,key,key.substring(65));
    }
    @Test void fullWorkerPathCreatesOneImagePerColorAndVariantWithoutAnyModelCallsAndKeepsOriginals()throws Exception {
        String photo=archive.store("org",png(false)).storageKey(),art=archive.store("org",CommerceArtworkLibraryServiceTest.artwork(true)).storageKey();
        var colors=List.of(new CommerceSourceColorResolver.Color("default","原图色",List.of("size-s","size-m"),"image",true),
                new CommerceSourceColorResolver.Color("cream","米色",List.of("cream-s","cream-m","cream-l"),"color-image",true));
        var selected=new CommerceBatchCreativePlanValidator.Selection("capture",1,"image",List.of(),colors);
        var plan=new CommerceBatchCreativePlanValidator.Plan("template",List.of(selected),2,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",CommerceLocalPhotoWorkflow.POLICY);
        var config=new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(new CommercePrintTemplateService.Area("front","FRONT",.36,.35,.25,.25,200,200,300)),Map.of("FRONT",photo.substring(65)));
        when(plans.forExecution("org","run")).thenReturn(new CommerceBatchCreativePlanMapper.Row("p","org","batch",1,json.writeValueAsString(plan),"u",Instant.now()));
        when(templates.snapshot("org","template")).thenReturn(new CommerceTemplateVersion("template","org","root",1,"PRINT_LAYOUT","template",json.writeValueAsString(config),"u",Instant.now()));
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(1L);
        var first=mock(ProductCaptureImage.class);when(first.getId()).thenReturn("image");
        var second=mock(ProductCaptureImage.class);when(second.getId()).thenReturn("color-image");
        when(captures.get("capture",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(first,second),List.of(),Map.of(),Map.of()));
        when(sourceArchives.find("capture","image",1,user)).thenReturn(photo);when(sourceArchives.find("capture","color-image",1,user)).thenReturn(photo);
        ready("extract","EXTRACT",0,art);ready("variant1","VARIATE",1,art);ready("variant2","VARIATE",2,art);
        var original=jobs();var service=service();int turns=0;
        for(;turns<15;turns++){var result=service.execute(run("MOCKUP"),user);if(result.complete())break;assertEquals("QUEUED",result.pendingStatus(),result.message());}
        assertTrue(turns<15,"worker must finish bounded local steps");
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE kind='MOCKUP'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE kind='MATTE'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE kind='BASE_COLOR'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE kind IN ('MOCKUP_LEFT','MOCKUP_RIGHT','MOCKUP_BACK')",Integer.class));
        assertEquals(original,db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE id IN ('extract','variant1','variant2') ORDER BY created_at,id"));
        var hashes=db.queryForList("SELECT DISTINCT storage_key FROM commerce_batch_artwork_jobs WHERE kind='MATTE'",String.class);assertEquals(1,hashes.size(),"identical source masters reuse identical alpha derivatives");
        assertEquals(3,db.queryForObject("SELECT reserved_calls FROM commerce_batch_model_limits WHERE execution_id='run'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_model_reservations",Integer.class));
        assertTrue(service.execute(run("REVIEW"),user).complete());
        var count=jobs().size();assertTrue(service.execute(run("MOCKUP"),user).complete());assertEquals(count,jobs().size());
        verifyNoInteractions(models,gateway,ledger,downloader);
    }
    @Test void staleLeaseCachesPureResultButDoesNotPublishArtifact()throws Exception {
        db.update("UPDATE commerce_batch_executions SET lease_token='new-worker' WHERE id='run'");
        var result=local().local(run("MOCKUP"),selection(),"MATTE",1,null,List.of(),"test-v1",List.of("input"),()->{
            try{return new CommerceLocalPhotoWorkflow.Produced(png(true),Map.of("status","test"));}catch(Exception e){throw new RuntimeException(e);}},"done");
        assertEquals("UNKNOWN",result.pendingStatus());assertEquals(0,jobs().size());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM commerce_mockup_derivatives",Integer.class));
    }
    @Test void pausingRunKeepsFinishedLocalImageAndReturnsProgressForWorkerPauseTransition()throws Exception {
        db.update("UPDATE commerce_batch_executions SET status='PAUSING' WHERE id='run'");
        var png=png(true);
        var result=local().local(run("MOCKUP"),selection(),"MATTE",1,null,List.of(),"test-v1",List.of("input"),()->new CommerceLocalPhotoWorkflow.Produced(png,Map.of("status","test")),"done");
        assertEquals("QUEUED",result.pendingStatus());assertEquals(1,jobs().size());assertEquals("READY",jobs().getFirst().get("status"));
    }
    @Test void repeatedAndConcurrentLocalProcessingUsesOneArtifactAndImmutableCache()throws Exception {
        var input=png(true);var count=new AtomicInteger();
        java.util.function.Supplier<CommerceBatchStageHandler.Result> operation=()->local().local(run("MOCKUP"),selection(),"MATTE",1,null,List.of(),"test-v1",List.of("same-input"),()->{count.incrementAndGet();return new CommerceLocalPhotoWorkflow.Produced(input,Map.of("status","test"));},"done");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(operation::get);var b=pool.submit(operation::get);
            assertEquals("QUEUED",a.get(15,TimeUnit.SECONDS).pendingStatus());assertEquals("QUEUED",b.get(15,TimeUnit.SECONDS).pendingStatus());
        }
        assertEquals(1,jobs().size());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM commerce_mockup_derivatives",Integer.class));
        int computed=count.get();operation.get();assertEquals(computed,count.get());
        assertEquals(3,db.queryForObject("SELECT reserved_calls FROM commerce_batch_model_limits",Integer.class));
    }
    @Test void cachedAssetNeverCrossesTenantAndTamperedContentsStopReuse()throws Exception {
        var cache=new CommerceMockupDerivativeCache(db,archive,json);String key=cache.key("test-v1",List.of("input"));
        var entry=cache.store("org",key,"MATTE",png(true),Map.of("status","test"));
        assertNull(cache.find("other-org",key));assertNotNull(cache.find("org",key));
        java.nio.file.Files.write(directory.resolve(entry.storageKey()),png(false));
        assertThrows(IllegalStateException.class,()->cache.find("org",key));
    }
}
