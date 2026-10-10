package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.domain.ShopOperatingSettings;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import com.yingdao.aicommerce.shop.mapper.ShopOperatingSettingsMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Opt-in: real MySQL and MyBatis/Spring transactions in a fresh disposable schema only. No workers or model gateway. */
@EnabledIfEnvironmentVariable(named="HEYI_LAUNCH_TEST_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_launch_test[a-zA-Z0-9_]*")
class CommerceBatchLaunchIntegrationTest {
    static final List<String> TABLES=List.of("commerce_batch_execution_events","commerce_batch_execution_plans",
            "commerce_batch_model_reservations","commerce_batch_model_limits","commerce_batch_executions",
            "commerce_batch_creative_plans","commerce_batch_items","commerce_batches");
    static DriverManagerDataSource dataSource;
    static DataSourceTransactionManager transactionManager;
    static JdbcTemplate db;
    static SqlSessionTemplate sessions;
    final ObjectMapper json=new ObjectMapper();
    final PlatformPrincipal user=new PlatformPrincipal("user","org","org","user","User","",true,List.of(),List.of("product:view","product:edit","product:publish"));
    CommerceBatchMapper batches;
    CommerceTemplateMapper templates;
    CommerceBatchExecutionMapper runs;
    CommerceBatchCreativePlanMapper plans;
    ProductCaptureMapper captureMapper;
    ProductCaptureService captures;
    ShopMapper shops;
    ShopOperatingSettingsMapper operating;
    CommerceImageArchive archive;

    @BeforeAll static void createIsolatedSchema() throws Exception {
        String url=System.getenv("HEYI_LAUNCH_TEST_URL");
        assertNotNull(url);assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_launch_test[a-zA-Z0-9_]*"));
        dataSource=new DriverManagerDataSource(url,"root","");
        try(var connection=dataSource.getConnection()) {
            try(var existing=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})) {
                assertFalse(existing.next(),"Use a newly created empty heyi_launch_test schema; never the application database");
            }
            for(String migration:List.of("V55__add_commerce_template_versions.sql","V56__add_commerce_batch_drafts.sql",
                    "V68__batch_execution_checkpoints.sql","V73__batch_model_call_limits.sql","V74__batch_creative_plans.sql"))
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+migration));
        }
        transactionManager=new DataSourceTransactionManager(dataSource);db=new JdbcTemplate(dataSource);
        var configuration=new Configuration();configuration.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(CommerceBatchMapper.class,CommerceTemplateMapper.class,CommerceBatchExecutionMapper.class,CommerceBatchCreativePlanMapper.class))
            configuration.addMapper(mapper);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(dataSource);factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        sessions=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
    }

    @BeforeEach void setup() throws Exception {
        for(String table:TABLES)db.update("DELETE FROM "+table);
        db.update("DELETE FROM commerce_template_versions");
        batches=sessions.getMapper(CommerceBatchMapper.class);templates=sessions.getMapper(CommerceTemplateMapper.class);
        runs=sessions.getMapper(CommerceBatchExecutionMapper.class);plans=sessions.getMapper(CommerceBatchCreativePlanMapper.class);
        captureMapper=mock(ProductCaptureMapper.class);captures=mock(ProductCaptureService.class);
        shops=mock(ShopMapper.class);operating=mock(ShopOperatingSettingsMapper.class);archive=mock(CommerceImageArchive.class);
        var capture=mock(ProductCapture.class);when(capture.getId()).thenReturn("capture");when(capture.getOrganizationId()).thenReturn("org");
        when(capture.getVersion()).thenReturn(1L);when(capture.getTitle()).thenReturn("Test garment");
        when(captureMapper.selectById("capture")).thenReturn(capture);
        var image=mock(ProductCaptureImage.class);when(image.getId()).thenReturn("source-image");
        when(image.getCaptureId()).thenReturn("capture");when(image.getOrganizationId()).thenReturn("org");
        when(image.getRoleName()).thenReturn("MAIN");when(image.getOriginalUrl()).thenReturn("https://example.invalid/source.png");
        when(captures.get("capture",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(image),List.of(),Map.of(),Map.of()));
        var shop=mock(Shop.class);when(shop.getId()).thenReturn("shop");when(shop.getOrganizationId()).thenReturn("org");
        when(shop.getPlatform()).thenReturn("TIKTOK_SHOP");when(shop.getRegion()).thenReturn("MY");when(shop.getStatus()).thenReturn("ACTIVE");when(shop.getName()).thenReturn("Sandbox shop");
        when(shops.selectById("shop")).thenReturn(shop);
        when(operating.get("org","shop")).thenReturn(new ShopOperatingSettings("shop","org","group","TEST",50,null,1,"user",Instant.now()));
        var photo=CommerceLocalPhotoIntegrationTest.png(false);
        when(archive.read(eq("org"),anyString())).thenReturn(SurfaceTemplateFixture.reference(photo));
        var workflow=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,CommerceBatchPlanner.Platform.TIKTOK_SHOP,"MY",
                1,2,1,1,50,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE,List.of(),List.of(),"MYR","print");
        templates.insert(new CommerceTemplateVersion("workflow","org","workflow-root",1,"WORKFLOW","Workflow",json.writeValueAsString(workflow),"user",Instant.now()));
        var photoTemplate=new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(new CommercePrintTemplateService.Area("front","FRONT",.35,.35,.25,.25,200,200,300)),Map.of("FRONT",CommerceImageArchive.sha(photo)));
        templates.insert(new CommerceTemplateVersion("print","org","print-root",1,"PRINT_LAYOUT","Print",json.writeValueAsString(photoTemplate),"user",Instant.now()));
    }

    @SuppressWarnings("unchecked") <T> T transactional(T target) {
        var factory=new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactionManager,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    CommerceBatchLaunchService service(CommerceBatchExecutionMapper executionMapper) {
        var creative=transactional(new CommerceBatchCreativePlanService(batches,executionMapper,plans,templates,captures,json));
        var draft=transactional(new CommerceBatchService(batches,templates,captureMapper,shops,json,operating,creative));
        var limits=new CommerceBatchModelLimitService(db);
        var execution=transactional(new CommerceBatchExecutionService(executionMapper,batches,captures,limits,creative));
        return transactional(new CommerceBatchLaunchService(draft,creative,execution,archive));
    }

    CommerceBatchLaunchService.Request request() {
        return new CommerceBatchLaunchService.Request("Launch integration","workflow",
                List.of(new CommerceBatchService.Target("capture","shop")),"print",true);
    }
    int count(String table) {return Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));}

    @Test void lateStartFailureRollsBackRealBatchItemsPlanRunAndLimitWrites() {
        var failingRuns=mock(CommerceBatchExecutionMapper.class,delegatesTo(runs));
        doAnswer(call->{
            runs.event(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            for(String table:TABLES)assertEquals(table.equals("commerce_batch_model_reservations")?0:1,count(table),"real rows must exist inside the shared transaction: "+table);
            throw new BusinessException(HttpStatus.CONFLICT,"injected failure after execution and limit insert");
        }).when(failingRuns).event(anyString(),anyString(),anyString(),anyString(),anyString(),any(Instant.class));
        var error=assertThrows(BusinessException.class,()->service(failingRuns).launch(request(),"rollback-launch-key",user));
        assertTrue(error.getMessage().contains("injected failure"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        for(String table:TABLES)assertEquals(0,count(table),"failed launch left committed work in "+table);
        assertEquals(2,count("commerce_template_versions"),"the pre-existing templates must be retained");
        var retry=service(runs).launch(request(),"rollback-launch-key",user);
        assertEquals("QUEUED",retry.execution().status());assertEquals(1,count("commerce_batches"));
        assertEquals(1,count("commerce_batch_executions"));assertEquals(1,count("commerce_batch_model_limits"));
    }

    @Test void successfulLaunchAndSameKeyReplayShareOneCommittedRunPlanAndLimit() {
        var launch=service(runs);var first=launch.launch(request(),"committed-launch-key",user);
        var retry=launch.launch(request(),"committed-launch-key",user);
        assertEquals(first.batch().id(),retry.batch().id());assertEquals(first.execution().id(),retry.execution().id());
        assertEquals("QUEUED",retry.execution().status());assertEquals(9,first.maxModelCalls());assertEquals(9,retry.maxModelCalls());
        for(String table:TABLES)assertEquals(table.equals("commerce_batch_model_reservations")?0:1,count(table),"replay duplicated persisted work in "+table);
        assertEquals(0,db.queryForObject("SELECT reserved_calls FROM commerce_batch_model_limits WHERE execution_id=?",Integer.class,first.execution().id()));
        assertEquals("SOURCE_PRODUCT",json.readTree(plans.forExecution("org",first.execution().id()).planJson()).path("artworkSource").asText());
        assertEquals("LOCAL_PHOTO_V1",json.readTree(plans.forExecution("org",first.execution().id()).planJson()).path("renderPolicy").asText());
        var changed=new CommerceBatchLaunchService.Request("Changed request","workflow",request().targets(),"print",true);
        assertThrows(BusinessException.class,()->launch.launch(changed,"committed-launch-key",user));
        assertEquals(1,count("commerce_batches"));assertEquals(1,count("commerce_batch_executions"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
