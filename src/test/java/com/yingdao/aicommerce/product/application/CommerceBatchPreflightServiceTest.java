package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import com.yingdao.aicommerce.product.mapper.CommerceBatchReviewMapper;
import com.yingdao.aicommerce.product.mapper.CommerceListingQuotaMapper;
import com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper;
import com.yingdao.aicommerce.product.mapper.CommerceBatchExecutionMapper;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CommerceBatchPreflightServiceTest {
    final CommerceBatchService batches=mock(CommerceBatchService.class);
    final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
    final ProductCaptureService captures=mock(ProductCaptureService.class);
    final ShopMapper shops=mock(ShopMapper.class);
    final ShopOperatingSettingsMapper settings=mock(ShopOperatingSettingsMapper.class);
    final ObjectMapper json=new ObjectMapper();
    final CommerceListingQuotaMapper quota=mock(CommerceListingQuotaMapper.class);
    final CommerceBatchPreflightService service=new CommerceBatchPreflightService(batches,templates,captures,shops,settings,json,mock(CommerceBatchReviewMapper.class),quota);
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","User","x",true,List.of(),List.of("product:view"));
    void setup(long liveVersion,String state,String env){
        var item=new CommerceBatchItem("item","org","batch","capture",1,"shop","name",1,"DRAFT",Instant.now());
        when(batches.get("batch",user)).thenReturn(new CommerceBatchService.View("batch","name","snapshot","DRAFT",Instant.now(),List.of(item)));
        var config=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,CommerceBatchPlanner.Platform.SHOPEE,"PH",1,1,1,1,50,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE);
        when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"WORKFLOW","name",json.writeValueAsString(config),"u",Instant.now()));
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(liveVersion);when(capture.getStatus()).thenReturn(state);
        var detail=new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(),List.of(),Map.of(),Map.of());
        when(captures.get("capture",user)).thenReturn(detail);
        when(captures.approvalMissingFields(capture,List.of(),List.of())).thenReturn(List.of());
        var shop=mock(Shop.class);when(shop.getId()).thenReturn("shop");when(shop.getOrganizationId()).thenReturn("org");when(shop.getStatus()).thenReturn("ACTIVE");when(shop.getPlatform()).thenReturn("SHOPEE");when(shop.getRegion()).thenReturn("PH");when(shops.selectById("shop")).thenReturn(shop);
        when(settings.get("org","shop")).thenReturn(new ShopOperatingSettings("shop","org","",env,50,null,1,"u",Instant.now()));
    }
    @Test void changedProductAndUnreviewedStatusAreBothReported(){setup(2,"READY","TEST");var report=service.check("batch",user);assertEquals(2,report.items().getFirst().blockers().size());assertEquals(0,report.itemsWithoutDataBlockers());assertFalse(report.executable());}
    @Test void cleanItemDoesNotPretendProductionAndExecutionAreReady(){setup(1,"APPROVED","TEST");var report=service.check("batch",user);assertEquals(1,report.itemsWithoutDataBlockers());assertFalse(report.executable());assertTrue(report.batchBlockers().stream().anyMatch(s->s.contains("生产文件")));verify(captures,never()).approve(anyString(),any());}
    @Test void changedEnvironmentMustBeConfirmedAgain(){setup(1,"APPROVED","UNKNOWN");var report=service.check("batch",user);assertTrue(report.items().getFirst().blockers().contains("店铺环境尚未确认"));}
    @Test void sharedApprovalValidationIsUsed(){setup(1,"APPROVED","TEST");when(captures.approvalMissingFields(any(),anyList(),anyList())).thenReturn(List.of("SKU 图片"));assertTrue(service.check("batch",user).items().getFirst().blockers().contains("商品资料缺失：SKU 图片"));}
    void reserve(int count){
        var date=java.time.LocalDate.of(2026,9,8);
        when(quota.list("org","batch")).thenReturn(List.of(new CommerceListingReservation("r","org","batch","item","shop",date,"Asia/Manila","u",Instant.now())));
        when(quota.reservedCount("org","shop",date)).thenReturn(count);
    }
    @Test void realReservationClearsMissingQuotaButDoesNotPermitExecution(){setup(1,"APPROVED","TEST");reserve(1);var report=service.check("batch",user);assertFalse(report.batchBlockers().stream().anyMatch(s->s.contains("额度")));assertFalse(report.executable());}
    @Test void loweredShopLimitChecksAllBatchesNotOnlyThisBatch(){setup(1,"APPROVED","TEST");reserve(51);assertTrue(service.check("batch",user).batchBlockers().stream().anyMatch(s->s.contains("跨批次预留超过")));}
    @Test void missingLedgerIsNotTreatedAsValidReservation(){setup(1,"APPROVED","TEST");reserve(0);assertTrue(service.check("batch",user).batchBlockers().stream().anyMatch(s->s.contains("台账不一致")));}
    @Test void supportedCrossPlatformMarketIsRevalidatedFromActualShop(){
        setup(1,"APPROVED","TEST");
        when(shops.selectById("shop").getPlatform()).thenReturn("TIKTOK_SHOP");
        when(shops.selectById("shop").getRegion()).thenReturn("MY");
        var report=service.check("batch",user);
        assertTrue(report.items().getFirst().blockers().isEmpty());
        assertFalse(report.executable());
        assertTrue(report.batchBlockers().stream().anyMatch(s->s.contains("自动上架尚未开放")));
    }
    @Test void changedUnsupportedDestinationIsBlocked(){
        setup(1,"APPROVED","TEST");when(shops.selectById("shop").getRegion()).thenReturn("US");
        assertFalse(service.check("batch",user).items().getFirst().blockers().isEmpty());
    }
    @Test void actualTargetOutsideHistoricalTemplateSelectionIsStillRevalidated(){
        setup(1,"APPROVED","TEST");
        var config=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,CommerceBatchPlanner.Platform.SHOPEE,"PH",1,1,1,99,50,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE,List.of("other-shop"));
        when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"WORKFLOW","name",json.writeValueAsString(config),"u",Instant.now()));
        assertTrue(service.check("batch",user).items().getFirst().blockers().isEmpty());
    }
    @Test void moreThanFiftyActualShopsCannotPassPreflightEvenWhenHistoricalTemplateCountIsValid(){
        setup(1,"APPROVED","TEST");
        var items=java.util.stream.IntStream.range(0,51).mapToObj(i->new CommerceBatchItem("item-"+i,"org","batch","capture",1,"shop-"+i,"name",1,"DRAFT",Instant.now())).toList();
        when(batches.get("batch",user)).thenReturn(new CommerceBatchService.View("batch","name","snapshot","DRAFT",Instant.now(),items));
        assertTrue(service.check("batch",user).batchBlockers().stream().anyMatch(s->s.contains("目标店铺数须为1至50")));
    }

    final CommerceBatchCreativePlanMapper plans=mock(CommerceBatchCreativePlanMapper.class);
    final CommerceBatchExecutionMapper executions=mock(CommerceBatchExecutionMapper.class);
    final CommerceBatchReviewMapper reviews=mock(CommerceBatchReviewMapper.class);
    final CommerceBatchPreflightService currentService=new CommerceBatchPreflightService(batches,templates,captures,shops,settings,json,reviews,quota,plans,executions);
    CommerceBatchCreativePlanMapper.Row plan(String policy){
        var value=new CommerceBatchCreativePlanValidator.Plan("print",List.of(new CommerceBatchCreativePlanValidator.Selection("capture",1,"image")),1,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",policy);
        return new CommerceBatchCreativePlanMapper.Row("plan","org","batch",1,json.writeValueAsString(value),"u",Instant.now());
    }
    void started(){
        var now=Instant.now();
        when(executions.get("org","batch")).thenReturn(new CommerceBatchExecution("run","org","batch","u","MOCKUP","WAITING_INPUT","",null,null,now,now));
    }
    @Test void localPhotoDraftDoesNotRequireManualReviewOrClaimImplementedPublishingIsUnavailable(){
        setup(1,"READY","TEST");reserve(1);
        when(plans.latest("org","batch")).thenReturn(plan("LOCAL_PHOTO_V1"));
        var report=currentService.check("batch",user);
        assertTrue(report.batchBlockers().isEmpty());
        assertTrue(report.items().getFirst().blockers().isEmpty());
        assertFalse(report.executable(),"A read-only report never replaces start and publication validation");
        verifyNoInteractions(reviews);
        verify(captures,never()).approve(anyString(),any());
    }
    @Test void localPhotoRetainsVersionDataEnvironmentAndQuotaChecks(){
        setup(2,"READY","UNKNOWN");
        when(plans.latest("org","batch")).thenReturn(plan("LOCAL_PHOTO_V1"));
        when(captures.approvalMissingFields(any(),anyList(),anyList())).thenReturn(List.of("SKU 价格"));
        var report=currentService.check("batch",user);
        assertTrue(report.items().getFirst().blockers().contains("商品版本已变化，请重新建批次以固定新版本"));
        assertTrue(report.items().getFirst().blockers().contains("商品资料缺失：SKU 价格"));
        assertTrue(report.items().getFirst().blockers().contains("店铺环境尚未确认"));
        assertTrue(report.batchBlockers().stream().anyMatch(s->s.contains("额度预留")));
        assertFalse(report.items().getFirst().blockers().stream().anyMatch(s->s.contains("人工审核")));
    }
    @Test void existingLegacyExecutionCannotBorrowNewLocalPhotoDraftToBypassItsChecks(){
        setup(1,"READY","TEST");started();
        when(plans.latest("org","batch")).thenReturn(plan("LOCAL_PHOTO_V1"));
        when(plans.forExecution("org","run")).thenReturn(plan(null));
        var report=currentService.check("batch",user);
        assertTrue(report.batchBlockers().stream().anyMatch(s->s.contains("自动上架尚未开放")));
        assertTrue(report.items().getFirst().blockers().contains("商品尚未完成人工审核"));
        assertFalse(report.executable());
        verify(plans,never()).latest(anyString(),anyString());
    }
    @Test void frozenLocalPhotoExecutionKeepsAutomaticChecksWithoutConsultingDraft(){
        setup(1,"READY","TEST");reserve(1);started();
        when(plans.latest("org","batch")).thenReturn(plan(null));
        when(plans.forExecution("org","run")).thenReturn(plan("LOCAL_PHOTO_V1"));
        var report=currentService.check("batch",user);
        assertTrue(report.batchBlockers().isEmpty());
        assertTrue(report.items().getFirst().blockers().isEmpty());
        verify(plans,never()).latest(anyString(),anyString());
    }
    @Test void absentCreativePlanDoesNotEnableUnimplementedLegacyPublication(){
        setup(1,"READY","TEST");
        var report=currentService.check("batch",user);
        assertTrue(report.batchBlockers().stream().anyMatch(s->s.contains("自动上架尚未开放")));
        assertFalse(report.executable());
    }
}
