package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceBatchLaunchServiceTest {
    @Test void publicationConfigurationIsSavedBeforeAnyPaidImagePreparation() throws Exception {
        configure(1,2);
        var options=mock(CommerceBatchPublicationOptions.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicationOptions",options);
        var prepared=List.of(new CommerceBatchPublicationOptions.PublicationRequest("capture","shop",Map.of("autoConvertPrice",true)));
        var launch=new CommerceBatchLaunchService.Request(request.name(),request.templateSnapshotId(),request.targets(),request.printTemplateSnapshotId(),true,prepared);
        service.launch(launch,"request-key",user);
        var sequence=inOrder(options,plans,executions);
        sequence.verify(executions).get("batch",user);
        sequence.verify(options).save("batch",prepared,user);
        sequence.verify(options).requireReady("batch",user);
        sequence.verify(plans).get("batch",user);
        sequence.verify(executions).start(eq("batch"),eq(true),anyInt(),eq(3),eq(user));
    }
    @Test void missingPublicationConfigurationStopsBeforeImagesOrExecution() throws Exception {
        configure(1,2);
        var options=mock(CommerceBatchPublicationOptions.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicationOptions",options);
        doThrow(new BusinessException(HttpStatus.CONFLICT,"发布配置缺失")).when(options).save("batch",null,user);
        assertThrows(BusinessException.class,()->service.launch(request,"request-key",user));
        verifyNoInteractions(archive);verify(executions,never()).start(anyString(),anyBoolean(),anyInt(),anyInt(),any());
    }
    final CommerceBatchService batches=mock(CommerceBatchService.class);
    final CommerceBatchCreativePlanService plans=mock(CommerceBatchCreativePlanService.class);
    final CommerceBatchExecutionService executions=mock(CommerceBatchExecutionService.class);
    final CommerceImageArchive archive=mock(CommerceImageArchive.class);
    final CommerceBatchLaunchService service=new CommerceBatchLaunchService(batches,plans,executions,archive);
    final PlatformPrincipal user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:edit","product:publish"));
    final CommerceBatchLaunchService.Request request=new CommerceBatchLaunchService.Request("batch","workflow",
            List.of(new CommerceBatchService.Target("capture","shop")),"print",true);
    final CommerceBatchService.View batch=new CommerceBatchService.View("batch","batch","workflow","DRAFT",Instant.now(),List.of());
    final CommerceBatchExecution execution=new CommerceBatchExecution("run","org","batch","u","CAPTURE","QUEUED",null,null,null,Instant.now(),Instant.now());

    CommerceBatchCreativePlanValidator.Plan plan(int sourceCount,int variants,String mode) {
        var sources=java.util.stream.IntStream.range(0,sourceCount).mapToObj(i->
                new CommerceBatchCreativePlanValidator.Selection("capture-"+i,1,"image-"+i)).toList();
        return new CommerceBatchCreativePlanValidator.Plan("print",sources,variants,null,mode);
    }
    void configure(int sources,int variants) throws Exception {
        when(batches.create(any(),eq("request-key"),eq(user))).thenReturn(batch);
        when(plans.get("batch",user)).thenReturn(new CommerceBatchCreativePlanService.View(3,plan(sources,variants,"SOURCE_PRODUCT"),List.of(),false,RepeatedSurfaceArtworkFixture.configuration()));
        when(executions.start(eq("batch"),eq(true),anyInt(),eq(3),eq(user))).thenReturn(execution);
        var assets=RepeatedSurfaceArtworkFixture.assets();
        when(archive.read(eq("org"),anyString())).thenAnswer(call->
                SurfaceTemplateFixture.reference(assets.get(((String)call.getArgument(1)).substring(65))));
    }
    @Test void createsThenQueuesExistingWorkflowWithExactUniqueGroupsAndVisibleFaces() throws Exception {
        configure(2,3);
        var result=service.launch(request,"request-key",user);
        assertSame(batch,result.batch());assertSame(execution,result.execution());
        assertEquals(54,result.maxModelCalls()); // Base 26 + two retry slots for each of 14 logical print generations.
        var sequence=inOrder(batches,plans,executions);
        sequence.verify(batches).create(new CommerceBatchService.CreateRequest("batch","workflow",request.targets(),"print"),"request-key",user);
        sequence.verify(executions).get("batch",user);
        sequence.verify(plans).get("batch",user);
        sequence.verify(executions).start("batch",true,54,3,user);
        verifyNoMoreInteractions(batches,plans,executions);
    }
    @Test void noConfirmationOrMissingPermissionStopsBeforeCreatingAnyDraft() {
        var unconfirmed=new CommerceBatchLaunchService.Request(request.name(),request.templateSnapshotId(),request.targets(),request.printTemplateSnapshotId(),false);
        assertThrows(BusinessException.class,()->service.launch(unconfirmed,"request-key",user));
        assertThrows(BusinessException.class,()->service.launch(null,"request-key",user));
        var noPublish=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:edit"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.launch(request,"request-key",noPublish)).getStatus());
        verifyNoInteractions(batches,plans,executions,archive);
    }
    @Test void retryReturnsSameRunAndOriginalLimitWithoutReestimatingOrStartingAgain() throws Exception {
        configure(1,2);when(executions.get("batch",user)).thenReturn(execution);
        when(executions.limit("batch",user)).thenReturn(new CommerceBatchModelLimitService.Limit(37,12,false));
        var result=service.launch(request,"request-key",user);
        assertSame(execution,result.execution());assertEquals(37,result.maxModelCalls());
        verifyNoInteractions(plans,archive);verify(executions,never()).start(anyString(),anyBoolean(),anyInt(),anyInt(),any());
    }
    @Test void missingPrintPlanRejectsLaunchWithoutEnqueuingExecution() throws Exception {
        configure(1,2);when(plans.get("batch",user)).thenReturn(new CommerceBatchCreativePlanService.View(0,null,List.of(),false,null));
        assertTrue(assertThrows(BusinessException.class,()->service.launch(request,"request-key",user)).getMessage().contains("商品印花模板"));
        verify(executions,never()).start(anyString(),anyBoolean(),anyInt(),anyInt(),any());verifyNoInteractions(archive);
    }
    @Test void modelBudgetOverOneThousandIsRejectedWithoutTruncatingTheRequestedVariants() throws Exception {
        configure(13,20);
        var error=assertThrows(BusinessException.class,()->service.launch(request,"request-key",user));
        assertTrue(error.getMessage().contains("2119"));assertTrue(error.getMessage().contains("减少商品或裂变款式数量"));
        assertTrue(error.getMessage().contains("含印花重试的调用上限"));assertFalse(error.getMessage().contains("预计需要"));
        verify(executions,never()).start(anyString(),anyBoolean(),anyInt(),anyInt(),any());
    }
    @Test void retryHeadroomMustFitTheSameThousandCallLimitEvenWhenFirstAttemptsWouldFit() {
        // These 100 products need only 500 first calls, but 1,100 including print retry headroom.
        var error=assertThrows(BusinessException.class,()->service.requiredModelCalls(plan(100,2,"SOURCE_PRODUCT"),planar(true),"org"));
        assertTrue(error.getMessage().contains("1100"));assertTrue(error.getMessage().contains("含印花重试的调用上限"));
        verifyNoInteractions(archive,executions);
    }
    CommercePrintTemplateService.Configuration planar(boolean uploaded) {
        return new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(new CommercePrintTemplateService.Area("front","FRONT",.2,.2,.3,.3,200,200,300)),
                uploaded?Map.of("FRONT",SurfaceTemplateFixture.hash('a')):Map.of());
    }
    @Test void planarBudgetIncludesOneExtractionAndOptionalBlankPerSource() {
        assertEquals(57,service.requiredModelCalls(plan(3,4,"SOURCE_PRODUCT"),planar(true),"org"));
        assertEquals(60,service.requiredModelCalls(plan(3,4,"SOURCE_PRODUCT"),planar(false),"org"));
        assertEquals(1000,service.requiredModelCalls(plan(50,4,"SOURCE_PRODUCT"),planar(false),"org"));
        verifyNoInteractions(archive);
    }
    @Test void legacySurfaceTemplateReuseDoesNotBudgetAnotherExtractionModelCall() throws Exception {
        configure(1,2);
        var legacy=new CommerceBatchCreativePlanValidator.Plan("print",plan(2,3,null).sources(),3,RepeatedSurfaceArtworkFixture.CHECKER,null);
        assertEquals(30,service.requiredModelCalls(legacy,RepeatedSurfaceArtworkFixture.configuration(),"org"));
    }
    @Test void incompleteSurfaceAssetsPreventQueuingAnyGeneration() throws Exception {
        configure(1,2);when(archive.read(eq("org"),anyString())).thenThrow(new IllegalStateException("archive missing"));
        assertTrue(assertThrows(BusinessException.class,()->service.launch(request,"request-key",user)).getMessage().contains("尚未启动图片生成"));
        verify(executions,never()).start(anyString(),anyBoolean(),anyInt(),anyInt(),any());
    }

    CommerceBatchCreativePlanValidator.Plan colorPlan(int variants,String mode,int... colorsPerSource) {
        var sources=new ArrayList<CommerceBatchCreativePlanValidator.Selection>();
        for(int source=0;source<colorsPerSource.length;source++) {
            var colors=new ArrayList<CommerceSourceColorResolver.Color>();
            for(int color=0;color<colorsPerSource[source];color++) {
                // Multiple size SKUs remain one output for this color.
                String key="color-"+color;
                colors.add(new CommerceSourceColorResolver.Color(key,key,List.of(key+"-s",key+"-m",key+"-l"),"image-"+source+"-"+color,true));
            }
            sources.add(new CommerceBatchCreativePlanValidator.Selection("capture-"+source,1,"image-"+source,List.of(),colors));
        }
        return new CommerceBatchCreativePlanValidator.Plan("print",sources,variants,
                "SOURCE_PRODUCT".equals(mode)?null:RepeatedSurfaceArtworkFixture.CHECKER,mode,"SOURCE_SKU_COLORS");
    }

    @Test void sourceColorLaunchBudgetsEachColorOnceAcrossSizesAndKeepsVariantsPerDesign() throws Exception {
        configure(1,4);
        var plan=colorPlan(4,"SOURCE_PRODUCT",3);
        when(plans.get("batch",user)).thenReturn(new CommerceBatchCreativePlanService.View(3,plan,List.of(),false,RepeatedSurfaceArtworkFixture.configuration()));
        var result=service.launch(request,"request-key",user);
        assertEquals(39,result.maxModelCalls()); // Base 21 + two retry slots for 1 extraction and 8 group variants.
        verify(executions).start("batch",true,39,3,user);
    }

    @Test void differentSourcesSumTheirOwnFrozenColorsInsteadOfMultiplyingVisibleFaces() throws Exception {
        configure(1,2);
        assertEquals(86,service.requiredModelCalls(colorPlan(4,"SOURCE_PRODUCT",3,5),RepeatedSurfaceArtworkFixture.configuration(),"org"));
        assertEquals(58,service.requiredModelCalls(colorPlan(4,"TEMPLATE_ARTWORK",3,5),RepeatedSurfaceArtworkFixture.configuration(),"org"));
        assertEquals(31,service.requiredModelCalls(colorPlan(4,"SOURCE_PRODUCT",1),RepeatedSurfaceArtworkFixture.configuration(),"org"));
    }
    @Test void extraSkuColorsAddOnlySinglePhotoCallsWithoutMultiplyingTheirBudgetByPrintRetries() throws Exception {
        configure(1,2);
        int oneColor=service.requiredModelCalls(colorPlan(2,"SOURCE_PRODUCT",1),RepeatedSurfaceArtworkFixture.configuration(),"org");
        int fourColors=service.requiredModelCalls(colorPlan(2,"SOURCE_PRODUCT",4),RepeatedSurfaceArtworkFixture.configuration(),"org");
        assertEquals(17,oneColor);assertEquals(23,fourColors);
        assertEquals(6,fourColors-oneColor,"Three additional colors produce one photo per style, with no extra photo retry multiplier");
    }

    @Test void planarColorPolicyUsesOneExtractionOneArtworkOneImagePerColorAndOptionalBlank() {
        var plan=colorPlan(4,"SOURCE_PRODUCT",3,5);
        assertEquals(62,service.requiredModelCalls(plan,planar(true),"org"));
        assertEquals(64,service.requiredModelCalls(plan,planar(false),"org"));
        verifyNoInteractions(archive);
    }

    @Test void missingFrozenColorEvidenceRejectsLaunchBeforeModelWork() throws Exception {
        configure(1,2);
        var empty=colorPlan(2,"SOURCE_PRODUCT",0);
        when(plans.get("batch",user)).thenReturn(new CommerceBatchCreativePlanService.View(3,empty,List.of(),false,RepeatedSurfaceArtworkFixture.configuration()));
        assertTrue(assertThrows(BusinessException.class,()->service.launch(request,"request-key",user)).getMessage().contains("已固定的商品颜色"));
        var color=new CommerceSourceColorResolver.Color("black","Black",List.of("s","m"),"main",false);
        var invalid=new CommerceBatchCreativePlanValidator.Plan("print",List.of(new CommerceBatchCreativePlanValidator.Selection("capture",1,"main",List.of(),List.of(color))),2,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS");
        assertTrue(assertThrows(BusinessException.class,()->service.requiredModelCalls(invalid,RepeatedSurfaceArtworkFixture.configuration(),"org")).getMessage().contains("不会猜测颜色"));
        verify(executions,never()).start(anyString(),anyBoolean(),anyInt(),anyInt(),any());verifyNoInteractions(archive);
    }

    @Test void sourceColorBudgetRejectsOverflowWithoutDroppingColorsOrClampingVariants() throws Exception {
        configure(1,20);
        var plan=colorPlan(20,"SOURCE_PRODUCT",50);
        assertTrue(assertThrows(BusinessException.class,()->service.requiredModelCalls(plan,RepeatedSurfaceArtworkFixture.configuration(),"org")).getMessage().contains("1123"));
    }

    @Test void localPhotoBudgetCountsArtworkAndFirstBlankButNeverChargesForSkuComposites() {
        var prior=colorPlan(4,"SOURCE_PRODUCT",3,5);
        var local=new CommerceBatchCreativePlanValidator.Plan(prior.printTemplateSnapshotId(),prior.sources(),prior.variantsPerPrint(),null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS","LOCAL_PHOTO_V1");
        assertEquals(32,service.requiredModelCalls(local,planar(false),"org")); // 10 prints x 3 attempts + 2 unchanged blank calls.
        assertEquals(64,service.requiredModelCalls(prior,planar(false),"org")); // A new run of the old render policy still has 32 single-call color photos.
        assertNull(prior.renderPolicy());
        verifyNoInteractions(archive);
    }
    @Test void unknownOrIncompletePhotoPolicyCannotStart() {
        var prior=colorPlan(2,"SOURCE_PRODUCT",3);
        for(String policy:List.of("UNKNOWN","LOCAL_PHOTO_V1")) {
            var invalid=new CommerceBatchCreativePlanValidator.Plan("print",prior.sources(),2,null,"SOURCE_PRODUCT",null,policy);
            assertThrows(BusinessException.class,()->service.requiredModelCalls(invalid,planar(false),"org"));
        }
        verifyNoInteractions(archive);
    }

    @Test void detailCallsMatchFrozenOriginalCountsPerVariantAndNeverMultiplyBySkuColors() throws Exception {
        configure(1,2);
        var base=colorPlan(3,"SOURCE_PRODUCT",1,4);
        var sources=new ArrayList<CommerceBatchCreativePlanValidator.Selection>();
        int index=0;
        for(var source:base.sources())sources.add(new CommerceBatchCreativePlanValidator.Selection(source.captureId(),source.version(),source.imageId(),
                source.cleanupAreas(),source.colorTargets(),index++==0?List.of("detail-a","detail-b"):List.of("detail-c","detail-d","detail-e")));
        var withDetails=new CommerceBatchCreativePlanValidator.Plan(base.printTemplateSnapshotId(),sources,base.variantsPerPrint(),base.variationArtworkId(),base.artworkSource(),base.outputPolicy(),base.renderPolicy());
        int original=service.requiredModelCalls(base,RepeatedSurfaceArtworkFixture.configuration(),"org");
        assertEquals(original+30,service.requiredModelCalls(withDetails,RepeatedSurfaceArtworkFixture.configuration(),"org"),
                "3 variants × (2 + 3) original details × at most 2 attempts; SKU color counts do not multiply details");
    }

    @Test void frozenDetailsShareTheTotalCallLimitAndCannotBeAppliedToNonGarments() {
        var base=colorPlan(20,"SOURCE_PRODUCT",1);
        var original=base.sources().getFirst();
        var ids=java.util.stream.IntStream.range(0,30).mapToObj(i->"detail-"+i).toList();
        var source=new CommerceBatchCreativePlanValidator.Selection(original.captureId(),original.version(),original.imageId(),List.of(),original.colorTargets(),ids);
        var detailed=new CommerceBatchCreativePlanValidator.Plan(base.printTemplateSnapshotId(),List.of(source),20,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS");
        assertTrue(assertThrows(BusinessException.class,()->service.requiredModelCalls(detailed,planar(true),"org")).getMessage().contains("超过单批1000次"));
        var cushion=new CommercePrintTemplateService.Configuration("CUSHION",planar(true).areas(),planar(true).baseImages());
        assertTrue(assertThrows(BusinessException.class,()->service.requiredModelCalls(detailed,cushion,"org")).getMessage().contains("仅适用于服装"));
    }

    @Test void springTransactionRollsBackCreatedDraftWorkWhenStartingFails() throws Exception {
        configure(1,2);
        var dataSource=mock(DataSource.class);var connection=mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        var statement=mock(java.sql.PreparedStatement.class);when(connection.prepareStatement("INSERT draft work")).thenReturn(statement);
        when(batches.create(any(),eq("request-key"),eq(user))).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            var enlisted=DataSourceUtils.getConnection(dataSource);
            try {enlisted.prepareStatement("INSERT draft work").executeUpdate();}
            finally {DataSourceUtils.releaseConnection(enlisted,dataSource);}
            return batch;
        });
        when(executions.start("batch",true,19,3,user)).thenThrow(new BusinessException(HttpStatus.CONFLICT,"start rejected"));
        var proxyFactory=new ProxyFactory(service);
        proxyFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),new AnnotationTransactionAttributeSource()));
        var proxied=(CommerceBatchLaunchService)proxyFactory.getProxy();
        assertThrows(BusinessException.class,()->proxied.launch(request,"request-key",user));
        var sequence=inOrder(connection,statement);sequence.verify(connection).setAutoCommit(false);
        sequence.verify(statement).executeUpdate();sequence.verify(connection).rollback();
        verify(connection,never()).commit();assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
