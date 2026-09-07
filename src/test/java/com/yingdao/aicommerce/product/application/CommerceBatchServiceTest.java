package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import com.yingdao.aicommerce.shop.mapper.ShopOperatingSettingsMapper;
import com.yingdao.aicommerce.shop.domain.ShopOperatingSettings;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CommerceBatchServiceTest {
    final CommerceBatchMapper batches=mock(CommerceBatchMapper.class);
    final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
    final ProductCaptureMapper captures=mock(ProductCaptureMapper.class);
    final ShopMapper shops=mock(ShopMapper.class);
    final ObjectMapper json=new ObjectMapper();
    final ShopOperatingSettingsMapper operating=mock(ShopOperatingSettingsMapper.class);
    final CommerceBatchService service=new CommerceBatchService(batches,templates,captures,shops,json,operating);
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","User","x",true,List.of(),List.of("product:view","product:edit"));
    CommerceBatchService.CreateRequest setup(String captureOrg,String shopOrg,String market) {
        var config=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.STOCK_MARKETING,
                CommerceBatchPlanner.Platform.SHOPEE,"PH",1,1,1,1,1,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE);
        when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"WORKFLOW","name",json.writeValueAsString(config),"u",Instant.now()));
        var capture=mock(ProductCapture.class); when(capture.getId()).thenReturn("capture");
        when(capture.getOrganizationId()).thenReturn(captureOrg); when(capture.getVersion()).thenReturn(4L);
        when(captures.selectById("capture")).thenReturn(capture);
        var shop=mock(Shop.class); when(shop.getId()).thenReturn("shop"); when(shop.getOrganizationId()).thenReturn(shopOrg);
        when(shop.getStatus()).thenReturn("ACTIVE"); when(shop.getPlatform()).thenReturn("SHOPEE");
        when(shop.getRegion()).thenReturn(market); when(shop.getName()).thenReturn("测试店");
        when(shops.selectById("shop")).thenReturn(shop);
        when(operating.get("org","shop")).thenReturn(new ShopOperatingSettings("shop","org","group","TEST",50,null,1,"u",Instant.now()));
        return new CommerceBatchService.CreateRequest("批次","snapshot",List.of(new CommerceBatchService.Target("capture","shop")));
    }
    @Test void draftPinsTemplateAndProductVersionWithoutDispatch() {
        var v=service.create(setup("org","org","PH"),"batch-key-1",user);
        assertEquals("DRAFT",v.status()); assertEquals("snapshot",v.templateSnapshotId());
        assertEquals(4,v.items().getFirst().captureVersion()); assertEquals(1,v.items().getFirst().plannedDay());
        verify(batches).insert(any()); verify(batches).insertItem(any());
    }
    @Test void deniesForeignProductBeforeWriting() {
        var r=setup("foreign","org","PH");
        assertThrows(BusinessException.class,()->service.create(r,"batch-key-1",user));
        verify(batches,never()).insert(any());
    }
    @Test void deniesForeignShopBeforeWriting() {
        var r=setup("org","foreign","PH");
        assertThrows(BusinessException.class,()->service.create(r,"batch-key-1",user));
        verify(batches,never()).insert(any());
    }
    @Test void rejectsRegionMismatchAndDuplicates() {
        var r=setup("org","org","SG");
        assertThrows(BusinessException.class,()->service.create(r,"batch-key-1",user));
        var duplicated=new CommerceBatchService.CreateRequest("批次","snapshot",List.of(r.targets().getFirst(),r.targets().getFirst()));
        assertThrows(BusinessException.class,()->service.create(duplicated,"batch-key-1",user));
        verify(batches,never()).insert(any());
    }
    @Test void reusedKeyWithDifferentRequestIsRejected() {
        var r=setup("org","org","PH");
        when(batches.byKey("org","batch-key-1")).thenReturn(new CommerceBatch("b","org","name","snapshot","batch-key-1","different","DRAFT","u",Instant.now()));
        assertThrows(BusinessException.class,()->service.create(r,"batch-key-1",user));
        verify(batches,never()).insert(any());
    }
    @Test void unconfirmedEnvironmentCannotCreateBatch() {
        var r=setup("org","org","PH");when(operating.get("org","shop")).thenReturn(null);
        assertThrows(BusinessException.class,()->service.create(r,"batch-key-1",user));
        verify(batches,never()).insert(any());
    }
}
