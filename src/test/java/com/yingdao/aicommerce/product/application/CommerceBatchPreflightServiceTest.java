package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
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
    final CommerceBatchPreflightService service=new CommerceBatchPreflightService(batches,templates,captures,shops,settings,json);
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
        var shop=mock(Shop.class);when(shop.getOrganizationId()).thenReturn("org");when(shop.getStatus()).thenReturn("ACTIVE");when(shop.getPlatform()).thenReturn("SHOPEE");when(shop.getRegion()).thenReturn("PH");when(shops.selectById("shop")).thenReturn(shop);
        when(settings.get("org","shop")).thenReturn(new ShopOperatingSettings("shop","org","",env,50,null,1,"u",Instant.now()));
    }
    @Test void changedProductAndUnreviewedStatusAreBothReported(){setup(2,"READY","TEST");var report=service.check("batch",user);assertEquals(2,report.items().getFirst().blockers().size());assertEquals(0,report.itemsWithoutDataBlockers());assertFalse(report.executable());}
    @Test void cleanItemDoesNotPretendProductionAndExecutionAreReady(){setup(1,"APPROVED","TEST");var report=service.check("batch",user);assertEquals(1,report.itemsWithoutDataBlockers());assertFalse(report.executable());assertTrue(report.batchBlockers().stream().anyMatch(s->s.contains("生产文件")));verify(captures,never()).approve(anyString(),any());}
    @Test void changedEnvironmentMustBeConfirmedAgain(){setup(1,"APPROVED","UNKNOWN");var report=service.check("batch",user);assertTrue(report.items().getFirst().blockers().contains("店铺环境尚未确认"));}
    @Test void sharedApprovalValidationIsUsed(){setup(1,"APPROVED","TEST");when(captures.approvalMissingFields(any(),anyList(),anyList())).thenReturn(List.of("SKU 图片"));assertTrue(service.check("batch",user).items().getFirst().blockers().contains("商品资料缺失：SKU 图片"));}
}
