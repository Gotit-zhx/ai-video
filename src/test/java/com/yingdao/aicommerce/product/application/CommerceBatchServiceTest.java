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
    @Test void acceptsSupportedMarketFromActualShopForLegacyTemplates() {
        var r=setup("org","org","SG");
        assertEquals("shop",service.create(r,"batch-key-1",user).items().getFirst().shopId());
        verify(batches).insert(any());
    }
    @Test void rejectsDuplicatesBeforeWriting() {
        var r=setup("org","org","SG");
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
    void selectedShops(List<String> ids) {
        var config=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.STOCK_MARKETING,
                CommerceBatchPlanner.Platform.SHOPEE,"PH",2,1,1,ids.size(),1,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE,ids);
        when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"WORKFLOW","name",json.writeValueAsString(config),"u",Instant.now()));
    }
    Shop addSecondShop(String environment) {
        var second=mock(Shop.class);when(second.getId()).thenReturn("second");when(second.getOrganizationId()).thenReturn("org");
        when(second.getStatus()).thenReturn("ACTIVE");when(second.getPlatform()).thenReturn("TIKTOK_SHOP");
        when(second.getRegion()).thenReturn("MY");when(second.getName()).thenReturn("马来店");when(shops.selectById("second")).thenReturn(second);
        when(operating.get("org","second")).thenReturn(new ShopOperatingSettings("second","org","group",environment,30,null,1,"u",Instant.now()));
        return second;
    }
    CommerceBatchService.CreateRequest mixedRequest() {
        return new CommerceBatchService.CreateRequest("跨平台批次","snapshot",List.of(new CommerceBatchService.Target("capture","shop"),new CommerceBatchService.Target("capture","second")));
    }
    @Test void mixedPlatformAndMarketKeepTheirOwnShopTargetsAndQuota() {
        setup("org","org","PH");selectedShops(List.of("shop","second"));addSecondShop("TEST");
        var result=service.create(mixedRequest(),"mixed-key-1",user);
        assertEquals(List.of("shop","second"),result.items().stream().map(CommerceBatchItem::shopId).toList());
        assertTrue(result.items().stream().allMatch(i->i.plannedDay()==1));
        verify(batches,times(2)).insertItem(any());
    }
    @Test void actualShopOverridesHistoricalTemplateSelection() {
        var request=setup("org","org","PH");selectedShops(List.of("second"));
        assertEquals("shop",service.create(request,"selected-key",user).items().getFirst().shopId());
        verify(batches).insert(any());
    }
    @Test void mixedTestAndLiveShopsStillCannotCreateOneBatch() {
        setup("org","org","PH");selectedShops(List.of("shop","second"));addSecondShop("LIVE");
        assertTrue(assertThrows(BusinessException.class,()->service.create(mixedRequest(),"mixed-env-key",user)).getMessage().contains("测试店铺与正式店铺"));
        verify(batches,never()).insert(any());
    }
    @Test void inactiveOrUnsupportedShopNeverCreatesBatch() {
        var request=setup("org","org","US");
        assertThrows(BusinessException.class,()->service.create(request,"unsupported-key",user));
        when(shops.selectById("shop").getRegion()).thenReturn("PH");
        when(shops.selectById("shop").getPlatform()).thenReturn("AMAZON");
        assertThrows(BusinessException.class,()->service.create(request,"unsupported-key",user));
        when(shops.selectById("shop").getPlatform()).thenReturn("SHOPEE");
        when(shops.selectById("shop").getStatus()).thenReturn("EXPIRED");
        assertThrows(BusinessException.class,()->service.create(request,"unsupported-key",user));
        verify(batches,never()).insert(any());
    }
    @Test void actualShopCountIgnoresHistoricalCountAndStaleTargetMetadata() {
        setup("org","org","PH");addSecondShop("TEST");
        var config=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.STOCK_MARKETING,
                CommerceBatchPlanner.Platform.SHOPEE,"PH",1,1,1,99,1,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE,
                List.of("deleted-shop"),List.of(new CommerceBatchPlanner.ShopTarget("deleted-shop",CommerceBatchPlanner.Platform.TEMU,"US","USD")),"PHP");
        var original=json.writeValueAsString(config);
        when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"WORKFLOW","old",original,"u",Instant.now()));
        assertEquals(2,service.create(mixedRequest(),"actual-shop-count",user).items().size());
        assertEquals(original,templates.snapshot("org","snapshot").configJson());
        verify(batches,times(2)).insertItem(any());
    }
    @Test void fiftyOneActualShopsAreRejectedBeforeDestinationQueriesOrWrites() {
        setup("org","org","PH");
        var targets=java.util.stream.IntStream.range(0,51).mapToObj(i->new CommerceBatchService.Target("capture","shop-"+i)).toList();
        assertTrue(assertThrows(BusinessException.class,()->service.create(new CommerceBatchService.CreateRequest("too many","snapshot",targets),"shop-limit-key",user)).getMessage().contains("1至50"));
        verifyNoInteractions(shops);verify(batches,never()).insert(any());
    }
    @Test void fiftyActualShopsCanUseASingleShopWorkflowTemplate() {
        setup("org","org","PH");
        var targets=new java.util.ArrayList<CommerceBatchService.Target>();
        for(int i=0;i<50;i++) {
            String id="shop-"+i;var shop=mock(Shop.class);
            when(shop.getId()).thenReturn(id);when(shop.getOrganizationId()).thenReturn("org");when(shop.getStatus()).thenReturn("ACTIVE");
            when(shop.getPlatform()).thenReturn("SHOPEE");when(shop.getRegion()).thenReturn("MY");when(shops.selectById(id)).thenReturn(shop);
            when(operating.get("org",id)).thenReturn(new ShopOperatingSettings(id,"org","group","TEST",10,null,1,"u",Instant.now()));
            targets.add(new CommerceBatchService.Target("capture",id));
        }
        assertEquals(50,service.create(new CommerceBatchService.CreateRequest("fifty shops","snapshot",targets),"fifty-shops-key",user).items().size());
        verify(batches,times(50)).insertItem(any());
    }
    @Test void actualStoreDailyQuotaStillDeterminesSchedule() {
        setup("org","org","PH");
        var capture=mock(ProductCapture.class);when(capture.getId()).thenReturn("capture-two");when(capture.getOrganizationId()).thenReturn("org");when(capture.getVersion()).thenReturn(1L);
        when(captures.selectById("capture-two")).thenReturn(capture);
        var targets=List.of(new CommerceBatchService.Target("capture","shop"),new CommerceBatchService.Target("capture-two","shop"));
        var result=service.create(new CommerceBatchService.CreateRequest("daily quota","snapshot",targets),"daily-quota-key",user);
        assertEquals(List.of(1,2),result.items().stream().map(CommerceBatchItem::plannedDay).toList());
    }
    final CommerceBatchCreativePlanService creativePlans=mock(CommerceBatchCreativePlanService.class);
    final PlatformPrincipal publisher=new PlatformPrincipal("u","org","code","login","User","x",true,List.of(),List.of("product:view","product:edit","product:publish"));
    CommerceBatchService withCreativePlans() {
        return new CommerceBatchService(batches,templates,captures,shops,json,operating,creativePlans);
    }
    void workflowPrint(String print,int count) {
        var config=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,
                CommerceBatchPlanner.Platform.SHOPEE,"PH",1,count,1,1,1,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE,
                List.of(),List.of(),"PHP",print);
        when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"WORKFLOW","configured",json.writeValueAsString(config),"u",Instant.now()));
    }
    void printSnapshot(String id) {
        when(templates.snapshot("org",id)).thenReturn(new CommerceTemplateVersion(id,"org","print-root",1,"PRINT_LAYOUT","print",json.writeValueAsString(SurfaceTemplateFixture.configuration()),"u",Instant.now()));
    }
    @Test void selectedSavedDesignCountOverridesWorkflowCountAndParticipatesInIdempotencyHash(){
        var original=setup("org","org","PH");workflowPrint("photo",20);
        when(templates.snapshot("org","photo")).thenReturn(new CommerceTemplateVersion("photo","org","root",1,"PRINT_LAYOUT","photo",json.writeValueAsString(SavedArtworkFixture.configuration()),"u",Instant.now()));
        var reused=List.of(SavedArtworkFixture.selection("a","b"),SavedArtworkFixture.selection("c","d"));
        var request=new CommerceBatchService.CreateRequest(original.name(),original.templateSnapshotId(),original.targets(),"photo",reused);
        var saved=new java.util.concurrent.atomic.AtomicReference<CommerceBatch>();when(batches.byKey("org","reuse-key")).thenAnswer(call->saved.get());
        doAnswer(call->{saved.set(call.getArgument(0));return 1;}).when(batches).insert(any());
        var creating=withCreativePlans();var first=creating.create(request,"reuse-key",publisher);assertEquals(first.id(),creating.create(request,"reuse-key",publisher).id());
        verify(creativePlans,times(1)).initialize(first.id(),"photo",2,reused,publisher);verify(batches,times(1)).insert(any());
        var changed=new CommerceBatchService.CreateRequest(request.name(),request.templateSnapshotId(),request.targets(),"photo",List.of(SavedArtworkFixture.selection("other","b")));
        assertThrows(BusinessException.class,()->creating.create(changed,"reuse-key",publisher));
    }

    @Test void explicitPrintSelectionOverridesWorkflowBindingAndInitializesDraftAfterItems() {
        var legacy=setup("org","org","PH");workflowPrint("inherited",20);printSnapshot("selected");
        var request=new CommerceBatchService.CreateRequest(legacy.name(),legacy.templateSnapshotId(),legacy.targets(),"selected");
        var result=withCreativePlans().create(request,"explicit-print-key",publisher);
        assertEquals("DRAFT",result.status());assertEquals("snapshot",result.templateSnapshotId());
        var ordered=inOrder(batches,creativePlans);ordered.verify(batches).insert(any());ordered.verify(batches).insertItem(any());
        ordered.verify(creativePlans).initialize(result.id(),"selected",20,publisher);
        verify(templates,never()).snapshot("org","inherited");verifyNoMoreInteractions(creativePlans);
    }
    @Test void omittedPrintSelectionInheritsConfiguredWorkflowPrintAndFullVariantCount() {
        var request=setup("org","org","PH");workflowPrint("inherited",20);printSnapshot("inherited");
        var result=withCreativePlans().create(request,"inherited-print-key",publisher);
        verify(creativePlans).initialize(result.id(),"inherited",20,publisher);
    }
    @Test void foreignOrWrongKindExplicitPrintCannotWriteBatchOrInitializeCreation() {
        var original=setup("org","org","PH");var creating=withCreativePlans();
        var foreign=new CommerceBatchService.CreateRequest(original.name(),original.templateSnapshotId(),original.targets(),"foreign");
        assertThrows(BusinessException.class,()->creating.create(foreign,"foreign-print-key",publisher));
        var wrong=new CommerceBatchService.CreateRequest(original.name(),original.templateSnapshotId(),original.targets(),"snapshot");
        assertThrows(BusinessException.class,()->creating.create(wrong,"wrong-print-kind",publisher));
        verify(batches,never()).insert(any());verifyNoInteractions(creativePlans);
    }
    @Test void explicitPrintNeedsPublishPermissionBeforeAnyBatchWrite() {
        var original=setup("org","org","PH");printSnapshot("selected");
        var request=new CommerceBatchService.CreateRequest(original.name(),original.templateSnapshotId(),original.targets(),"selected");
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->withCreativePlans().create(request,"print-permission-key",user)).getStatus());
        verify(batches,never()).insert(any());verifyNoInteractions(creativePlans);
    }
    @Test void nullOptionalPrintPreservesLegacyJsonAndRequestHashForRetry() {
        var request=setup("org","org","PH");
        String legacy="{\"name\":\"批次\",\"templateSnapshotId\":\"snapshot\",\"targets\":[{\"captureId\":\"capture\",\"shopId\":\"shop\"}]}";
        assertEquals(legacy,json.writeValueAsString(request));
        String hash=CommerceImageArchive.sha(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(batches.byKey("org","legacy-json-key")).thenReturn(new CommerceBatch("existing","org","批次","snapshot","legacy-json-key",hash,"DRAFT","u",Instant.now()));
        assertEquals("existing",withCreativePlans().create(request,"legacy-json-key",publisher).id());
        verify(batches,never()).insert(any());verifyNoInteractions(creativePlans);
    }
    @Test void retryReusesExistingDraftAndDoesNotInitializeCreationAgain() {
        var original=setup("org","org","PH");printSnapshot("selected");
        var request=new CommerceBatchService.CreateRequest(original.name(),original.templateSnapshotId(),original.targets(),"selected");
        var saved=new java.util.concurrent.atomic.AtomicReference<CommerceBatch>();
        when(batches.byKey("org","print-retry-key")).thenAnswer(call->saved.get());
        doAnswer(call->{saved.set(call.getArgument(0));return 1;}).when(batches).insert(any());
        var creating=withCreativePlans();var first=creating.create(request,"print-retry-key",publisher);
        var second=creating.create(request,"print-retry-key",publisher);
        assertEquals(first.id(),second.id());
        verify(batches,times(1)).insert(any());verify(batches,times(1)).insertItem(any());
        verify(creativePlans,times(1)).initialize(first.id(),"selected",1,publisher);
        var changed=new CommerceBatchService.CreateRequest(original.name(),original.templateSnapshotId(),original.targets(),"different");
        assertThrows(BusinessException.class,()->creating.create(changed,"print-retry-key",publisher));
        verifyNoMoreInteractions(creativePlans);
    }
    @Test void concurrentCreateLoserReturnsWinningDraftWithoutInitializingAgain() {
        var original=setup("org","org","PH");printSnapshot("selected");
        var request=new CommerceBatchService.CreateRequest(original.name(),original.templateSnapshotId(),original.targets(),"selected");
        String hash=CommerceImageArchive.sha(json.writeValueAsString(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var winner=new CommerceBatch("winner","org",original.name(),"snapshot","concurrent-print-key",hash,"DRAFT","u",Instant.now());
        when(batches.byKey("org","concurrent-print-key")).thenReturn(null,winner);
        doThrow(new org.springframework.dao.DuplicateKeyException("concurrent winner")).when(batches).insert(any());
        assertEquals("winner",withCreativePlans().create(request,"concurrent-print-key",publisher).id());
        verify(batches,never()).insertItem(any());verifyNoInteractions(creativePlans);
    }
}
