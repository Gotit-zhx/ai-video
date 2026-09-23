package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatch;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceDrapePlanPolicyTest {
    static Plan withPolicy(Plan p,String policy){return new Plan(p.printTemplateSnapshotId(),p.sources(),p.variantsPerPrint(),p.variationArtworkId(),p.artworkSource(),p.outputPolicy(),policy,p.reusedArtworks());}
    static CommercePrintTemplateService.Configuration prints(List<CommercePrintTemplateService.PhotoPrint> prints){
        var c=DrapeTestFixture.configuration();
        return new CommercePrintTemplateService.Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),c.referenceSetId(),c.surfacePrints(),c.renderedViews(),c.blankRenderedViews(),c.surfaceUvViews(),c.placementMasks(),c.renderPrintIds(),c.renderBinding(),prints);
    }

    @Test void drapeRemainsLocalWithoutEnteringThePaidEmptyBackgroundBranch(){
        var p=new Plan("snapshot",List.of(new Selection("capture",1,"image")),2,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",GROK_DRAPE_POLICY);
        assertTrue(drapeOutput(p));assertTrue(localPhotoOutput(p));assertFalse(sceneOutput(p));
        assertFalse(drapeOutput(withPolicy(p,GROK_SCENE_POLICY)));assertTrue(sceneOutput(withPolicy(p,GROK_SCENE_POLICY)));
        assertTrue(localPhotoOutput(withPolicy(p,LOCAL_PHOTO_POLICY)));assertFalse(sceneOutput(withPolicy(p,LOCAL_PHOTO_POLICY)));
    }

    @Test void exactlyOneFrontPlacementAllowsIndependentBackAndSidePrints(){
        var c=DrapeTestFixture.configuration();assertEquals(3,c.photoPrints().size());
        assertDoesNotThrow(()->validateRenderTemplate(GROK_DRAPE_POLICY,c));
        assertThrows(IllegalArgumentException.class,()->validateRenderTemplate(GROK_DRAPE_POLICY,SurfaceTemplateFixture.configuration()));
        var backOnly=prints(c.photoPrints().stream().filter(p->!"FRONT".equals(p.side())).toList());
        assertThrows(IllegalArgumentException.class,()->validateRenderTemplate(GROK_DRAPE_POLICY,backOnly));
        var twoFront=new ArrayList<>(c.photoPrints());twoFront.add(new CommercePrintTemplateService.PhotoPrint("other-front",SavedArtworkFixture.GROUP_B,"FRONT",.6,.3,.1,.1,0));
        assertThrows(IllegalArgumentException.class,()->validateRenderTemplate(GROK_DRAPE_POLICY,prints(twoFront)));
        assertDoesNotThrow(()->validateRenderTemplate(GROK_SCENE_POLICY,backOnly),"Existing scene workflow keeps back-only templates");
    }

    @Test void newDrapeRunBudgetsThreeMotherAttemptsPerSourceWithoutPaidColorOutputs()throws Exception{
        var h=new CommerceSavedArtworkTest.Harness();h.assets.put(CommerceSurfaceTemplateInputs.key("org","b".repeat(64)),DrapeTestFixture.white());var colors=List.of("black","white","pink").stream().map(c->new CommerceSourceColorResolver.Color(c,c,List.of(c+"-s",c+"-m"),"image-"+c,true)).toList();
        var source=new Selection("capture",1,"image",List.of(),colors);
        var p=new Plan("fixed-snapshot",List.of(source),2,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",GROK_DRAPE_POLICY);
        var launch=new CommerceBatchLaunchService(mock(CommerceBatchService.class),mock(CommerceBatchCreativePlanService.class),mock(CommerceBatchExecutionService.class),h.archive);
        assertEquals(18,launch.requiredModelCalls(p,DrapeTestFixture.configuration(),"org"),"(1 extraction + 2 designs × 2 groups) × 3 print attempts + 3 mother attempts");
        assertEquals(15,launch.requiredModelCalls(withPolicy(p,LOCAL_PHOTO_POLICY),DrapeTestFixture.configuration(),"org"));
        var singleColor=new Plan(p.printTemplateSnapshotId(),List.of(new Selection("capture",1,"image",List.of(),List.of(colors.getFirst()))),2,null,p.artworkSource(),p.outputPolicy(),p.renderPolicy());
        assertEquals(18,launch.requiredModelCalls(singleColor,DrapeTestFixture.configuration(),"org"));
        var saved=new Plan(p.printTemplateSnapshotId(),p.sources(),2,null,"SAVED_ARTWORK",p.outputPolicy(),p.renderPolicy(),List.of(
                new FrozenDesign(Map.of(SavedArtworkFixture.GROUP_A,h.a,SavedArtworkFixture.GROUP_B,h.b)),
                new FrozenDesign(Map.of(SavedArtworkFixture.GROUP_A,h.b,SavedArtworkFixture.GROUP_B,h.a))));
        assertEquals(3,launch.requiredModelCalls(saved,DrapeTestFixture.configuration(),"org"));
        assertEquals(0,launch.requiredModelCalls(withPolicy(saved,LOCAL_PHOTO_POLICY),DrapeTestFixture.configuration(),"org"));
        assertEquals(1,launch.requiredModelCalls(withPolicy(saved,GROK_SCENE_POLICY),DrapeTestFixture.configuration(),"org"));
        var twoSources=new Plan(saved.printTemplateSnapshotId(),List.of(source,new Selection("second",1,"image",List.of(),colors)),2,null,saved.artworkSource(),saved.outputPolicy(),saved.renderPolicy(),saved.reusedArtworks());
        assertEquals(6,launch.requiredModelCalls(twoSources,DrapeTestFixture.configuration(),"org"));
        assertEquals(2,launch.requiredModelCalls(withPolicy(twoSources,GROK_SCENE_POLICY),DrapeTestFixture.configuration(),"org"));
        var manySources=java.util.stream.IntStream.range(0,334).mapToObj(i->new Selection("source-"+i,1,"image",List.of(),colors)).toList();
        var overLimit=new Plan(saved.printTemplateSnapshotId(),manySources,2,null,saved.artworkSource(),saved.outputPolicy(),saved.renderPolicy(),saved.reusedArtworks());
        assertTrue(assertThrows(BusinessException.class,()->launch.requiredModelCalls(overLimit,DrapeTestFixture.configuration(),"org")).getMessage().contains("1002"));
    }

    @Test void explicitSaveFreezesDrapeAndPrepareStartDoesNotReplaceItWithDefault(){
        var h=new CommerceBatchCreativePlanServiceTest();h.surfaceDraft(DrapeTestFixture.configuration());
        var r=new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",List.of(new Selection("c",1,"image")),2,null,"SOURCE_PRODUCT",null,GROK_DRAPE_POLICY);
        var saved=h.service.save("b",r,h.user);assertEquals(GROK_DRAPE_POLICY,saved.plan().renderPolicy());
        var captured=org.mockito.ArgumentCaptor.forClass(CommerceBatchCreativePlanMapper.Row.class);verify(h.plans).insert(captured.capture());
        when(h.plans.latest("org","b")).thenReturn(captured.getValue());
        assertSame(captured.getValue(),h.service.prepareStart("b",1,h.user));
        assertEquals(GROK_DRAPE_POLICY,h.json.readValue(captured.getValue().planJson(),Plan.class).renderPolicy());
    }

    @Test void initializePassesExplicitPolicyAndOldConstructorStillDefaultsToScene(){
        var h=new CommerceBatchCreativePlanServiceTest();h.surfaceDraft(DrapeTestFixture.configuration());
        assertEquals(GROK_DRAPE_POLICY,h.service.initialize("b","surface-snapshot",2,null,GROK_DRAPE_POLICY,h.user).plan().renderPolicy());
        var old=new CommerceBatchCreativePlanServiceTest();old.surfaceDraft(DrapeTestFixture.configuration());
        assertEquals(GROK_SCENE_POLICY,old.service.initialize("b","surface-snapshot",2,old.user).plan().renderPolicy());
        var local=new CommerceBatchCreativePlanServiceTest();local.surfaceDraft(DrapeTestFixture.configuration());
        assertEquals(LOCAL_PHOTO_POLICY,local.service.initialize("b","surface-snapshot",2,null,LOCAL_PHOTO_POLICY,local.user).plan().renderPolicy());
    }

    @Test void nullPolicyReplayOfCompletedBatchUsesOriginalRequestHashWithoutReinitializing(){
        var h=new CommerceBatchServiceTest();
        var legacy=new CommerceBatchService.CreateRequest("original","workflow",List.of(new CommerceBatchService.Target("capture","shop")),"photo");
        String oldHash=CommerceImageArchive.sha(h.json.writeValueAsBytes(legacy));
        var existing=new CommerceBatch("already-completed","org","original","workflow","original-key",oldHash,"COMPLETED","u",Instant.now());
        when(h.batches.byKey("org","original-key")).thenReturn(existing);when(h.batches.items("org",existing.id())).thenReturn(List.of());
        var replay=h.withCreativePlans().create(legacy,"original-key",h.publisher);
        assertEquals(existing.id(),replay.id());assertEquals("COMPLETED",replay.status());
        verify(h.batches,never()).insert(any());verify(h.batches,never()).insertItem(any());
        verifyNoInteractions(h.templates,h.captures,h.shops,h.operating,h.creativePlans);
    }

    @Test void invalidPolicyRejectsSaveAndLaunchBeforeWritingOrStarting(){
        var h=new CommerceBatchCreativePlanServiceTest();h.surfaceDraft(DrapeTestFixture.configuration());
        var r=new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",List.of(new Selection("c",1,"image")),2,null,"SOURCE_PRODUCT",null,"UNSUPPORTED");
        assertThrows(BusinessException.class,()->h.service.save("b",r,h.user));verify(h.plans,never()).insert(any());
        var launch=new CommerceBatchLaunchServiceTest();var request=new CommerceBatchLaunchService.Request("test","workflow",launch.request.targets(),"photo",true,null,null,"UNSUPPORTED");
        assertThrows(BusinessException.class,()->launch.service.launch(request,"request-key",launch.user));verifyNoInteractions(launch.batches,launch.plans,launch.executions);
    }

    @Test void createForwardsPolicyAndPolicyChangesCannotReuseTheSameIdempotencyKey(){
        var h=new CommerceBatchServiceTest();var base=h.setup("org","org","PH");h.workflowPrint("photo",2);
        when(h.templates.snapshot("org","photo")).thenReturn(new CommerceTemplateVersion("photo","org","root",1,"PRINT_LAYOUT","photo",h.json.writeValueAsString(DrapeTestFixture.configuration()),"u",Instant.now()));
        var request=new CommerceBatchService.CreateRequest(base.name(),base.templateSnapshotId(),base.targets(),"photo",null,GROK_DRAPE_POLICY);
        var state=new java.util.concurrent.atomic.AtomicReference<CommerceBatch>();when(h.batches.byKey("org","drape-key")).thenAnswer(call->state.get());
        doAnswer(call->{state.set(call.getArgument(0));return 1;}).when(h.batches).insert(any());
        var service=h.withCreativePlans();var first=service.create(request,"drape-key",h.publisher);assertEquals(first.id(),service.create(request,"drape-key",h.publisher).id());
        verify(h.creativePlans,times(1)).initialize(first.id(),"photo",2,null,GROK_DRAPE_POLICY,h.publisher);
        var changed=new CommerceBatchService.CreateRequest(base.name(),base.templateSnapshotId(),base.targets(),"photo",null,GROK_SCENE_POLICY);
        assertThrows(BusinessException.class,()->service.create(changed,"drape-key",h.publisher));verify(h.batches,times(1)).insert(any());
    }

    @Test void launchCarriesPolicyAndReplaysExistingRunWithoutAddingAnotherCall()throws Exception{
        var h=new CommerceBatchLaunchServiceTest();h.configure(1,2);when(h.executions.get("batch",h.user)).thenReturn(h.execution);
        when(h.executions.limit("batch",h.user)).thenReturn(new CommerceBatchModelLimitService.Limit(16,1,false));
        var r=new CommerceBatchLaunchService.Request(h.request.name(),h.request.templateSnapshotId(),h.request.targets(),h.request.printTemplateSnapshotId(),true,null,null,GROK_DRAPE_POLICY);
        assertEquals(16,h.service.launch(r,"request-key",h.user).maxModelCalls());
        verify(h.batches).create(new CommerceBatchService.CreateRequest(r.name(),r.templateSnapshotId(),r.targets(),r.printTemplateSnapshotId(),null,GROK_DRAPE_POLICY),"request-key",h.user);
        verify(h.executions,never()).start(anyString(),anyBoolean(),anyInt(),anyInt(),any());verifyNoInteractions(h.plans,h.archive);
    }

    @Test void optionalNullPolicyDoesNotChangeLegacySerializedCreateRequest(){
        var h=new CommerceBatchServiceTest();var legacy=new CommerceBatchService.CreateRequest("name","workflow",List.of(new CommerceBatchService.Target("capture","shop")),"photo");
        assertFalse(h.json.writeValueAsString(legacy).contains("renderPolicy"));
        assertEquals(h.json.writeValueAsString(legacy),h.json.writeValueAsString(new CommerceBatchService.CreateRequest("name","workflow",legacy.targets(),"photo",null,null)));
    }
}
