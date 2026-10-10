package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;

class CommerceGrokSceneWorkflowTest {
    static final ModelConfigService.RuntimeModelConnection GROK=new ModelConfigService.RuntimeModelConnection("grok","http://unused","grok-imagine-image","OPENAI_COMPATIBLE","CUSTOM","test-only");
    static Plan scene(Plan p){return new Plan(p.printTemplateSnapshotId(),p.sources(),p.variantsPerPrint(),p.variationArtworkId(),p.artworkSource(),p.outputPolicy(),GROK_SCENE_POLICY,p.reusedArtworks());}
    static String key(char c){return CommerceSurfaceTemplateInputs.key("org",String.valueOf(c).repeat(64));}
    static CommerceSavedArtworkTest.Harness harness()throws Exception{
        var h=new CommerceSavedArtworkTest.Harness();h.plan=scene(h.plan);h.stubPlan();return h;
    }
    static Map<String,Object> job(CommerceSavedArtworkTest.Harness h,String capture,String state){
        var j=new LinkedHashMap<String,Object>();j.put("id","scene-"+capture);j.put("execution_id","run");j.put("capture_id",capture);
        j.put("kind",CommerceSceneBackgroundAssets.KIND);j.put("variant_index",0);j.put("status",state);j.put("prompt",CommerceSceneBackgroundAssets.PROMPT);
        j.put("model_config_id",GROK.id());j.put("model_fingerprint",CommerceBatchArtworkService.fingerprint(GROK));j.put("request_key","scene-key");
        j.put("reference_keys",h.json.writeValueAsString(List.of(key('5'),key('b'))));j.put("storage_key",key('2'));j.put("content_sha256","2".repeat(64));return j;
    }

    @Test void hybridPolicyAddsOneScenePerSourceToPrintBudgetAndOldSavedModeStaysFree()throws Exception{
        var h=new CommerceSavedArtworkTest.Harness();var launch=new CommerceBatchLaunchService(mock(CommerceBatchService.class),mock(CommerceBatchCreativePlanService.class),mock(CommerceBatchExecutionService.class),h.archive);
        h.assets.put(key('b'),SavedArtworkFixture.image(0xffaaaaaa,true));
        assertEquals(0,launch.requiredModelCalls(h.plan,h.config,"org"));assertEquals(1,launch.requiredModelCalls(scene(h.plan),h.config,"org"));
        var sources=List.of(h.plan.sources().getFirst(),new Selection("second",1,"image",List.of(),h.plan.sources().getFirst().colorTargets()));
        var saved=new Plan("fixed-snapshot",sources,1,null,"SAVED_ARTWORK","SOURCE_SKU_COLORS",GROK_SCENE_POLICY,h.plan.reusedArtworks());
        assertEquals(2,launch.requiredModelCalls(saved,h.config,"org"));
        var generated=new Plan("fixed-snapshot",sources,2,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",GROK_SCENE_POLICY);
        assertEquals(32,launch.requiredModelCalls(generated,h.config,"org"),"2 sources × (1 extraction + 2 variants × 2 groups) × 3 print attempts + 2 backgrounds");
        assertTrue(localPhotoOutput(saved));assertTrue(sceneOutput(saved));assertFalse(sceneOutput(h.plan));
    }
    @Test void scenePreflightRejectsUnsegmentableReferenceBeforeAnyModelRequest()throws Exception{
        var h=harness();var launch=new CommerceBatchLaunchService(mock(CommerceBatchService.class),mock(CommerceBatchCreativePlanService.class),mock(CommerceBatchExecutionService.class),h.archive);
        assertTrue(assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->launch.requiredModelCalls(h.plan,h.config,"org")).getMessage().contains("预检未通过"));
        verifyNoInteractions(h.models,h.gateway,h.ledger);
    }
    @Test void backgroundIdentityUsesExtractionPhotoAndCompositionNotTemplateOriginalOrSku(){
        var h=mock(org.springframework.jdbc.core.JdbcTemplate.class);var archive=mock(CommerceImageArchive.class);var json=new tools.jackson.databind.ObjectMapper();
        var cache=new CommerceMockupDerivativeCache(h,archive,json);var config=SavedArtworkFixture.configuration();
        var refs=CommerceSceneBackgroundAssets.references(key('5'),config,"org");assertEquals(List.of(key('5'),key('b')),refs);
        assertNotEquals(key('a'),refs.getFirst(),"Template source image is not extraction scene evidence");
        String first=CommerceSceneBackgroundAssets.cacheKey(cache,refs,"model",CommerceSceneBackgroundAssets.PROMPT);
        assertEquals(first,CommerceSceneBackgroundAssets.cacheKey(cache,List.copyOf(refs),"model",CommerceSceneBackgroundAssets.PROMPT));
        assertNotEquals(first,CommerceSceneBackgroundAssets.cacheKey(cache,List.of(key('2'),key('b')),"model",CommerceSceneBackgroundAssets.PROMPT));
        assertNotEquals(first,CommerceSceneBackgroundAssets.cacheKey(cache,refs,"changed-model",CommerceSceneBackgroundAssets.PROMPT));
        assertNotEquals(first,CommerceSceneBackgroundAssets.cacheKey(cache,refs,"model",CommerceSceneBackgroundAssets.PROMPT+"changed"));
    }
    @Test void sceneLookupIsSourceSpecificAndRejectsWrongStatusOrComposition()throws Exception{
        var h=harness();var own=job(h,"capture","READY");var other=job(h,"other","READY");var source=h.plan.sources().getFirst();
        assertEquals(key('2'),CommerceSceneBackgroundAssets.readyKey(h.plan,h.config,source,List.of(other,own),"org",h.archive,h.json));
        own.put("prompt",CommerceSceneBackgroundAssets.LEGACY_PROMPT);
        assertEquals(key('2'),CommerceSceneBackgroundAssets.readyKey(h.plan,h.config,source,List.of(own),"org",h.archive,h.json),"Archived backgrounds remain readable after the style-only prompt upgrade");
        own.put("prompt",CommerceSceneBackgroundAssets.LEGACY_STYLE_PROMPT);
        assertEquals(key('2'),CommerceSceneBackgroundAssets.readyKey(h.plan,h.config,source,List.of(own),"org",h.archive,h.json),"Frozen style-background jobs remain readable after clarifying continuous empty space");
        own.put("prompt","unrecognized scene prompt");
        assertThrows(IllegalArgumentException.class,()->CommerceSceneBackgroundAssets.readyKey(h.plan,h.config,source,List.of(own),"org",h.archive,h.json));
        own.put("prompt",CommerceSceneBackgroundAssets.PROMPT);
        assertThrows(IllegalArgumentException.class,()->CommerceSceneBackgroundAssets.readyKey(h.plan,h.config,source,List.of(other),"org",h.archive,h.json));
        own.put("status","UNKNOWN");assertThrows(IllegalArgumentException.class,()->CommerceSceneBackgroundAssets.readyKey(h.plan,h.config,source,List.of(own),"org",h.archive,h.json));
        own.put("status","READY");own.put("reference_keys",h.json.writeValueAsString(List.of(key('5'),key('a'))));
        assertThrows(IllegalArgumentException.class,()->CommerceSceneBackgroundAssets.readyKey(h.plan,h.config,source,List.of(own),"org",h.archive,h.json));
    }
    @Test void uncertainSceneNeverSubmitsAgainOrFallsBackToAnotherModel()throws Exception{
        for(String state:List.of("UNKNOWN","SUBMITTING")){
            var h=harness();h.jobs.add(job(h,"capture",state));var result=h.execute("MOCKUP");
            assertEquals("UNKNOWN",result.pendingStatus());verifyNoInteractions(h.models,h.gateway,h.ledger,h.sourceArchives);
        }
    }
    @Test void preparedSceneUsesFrozenGrokIdAndOneProductRequest()throws Exception{
        var h=harness();h.jobs.add(job(h,"capture","PREPARED"));when(h.models.commerceMockupConnectionById("org","grok")).thenReturn(Optional.of(GROK));
        when(h.ledger.claim(any(),eq("scene-capture"),eq("scene-key"))).thenReturn("CLAIMED");
        when(h.gateway.generateProductPreview(anyString(),eq("scene-capture"),eq(GROK),anyList(),eq(true))).thenThrow(new IllegalStateException("timeout after submission"));
        var result=h.execute("MOCKUP");assertEquals("UNKNOWN",result.pendingStatus());
        verify(h.gateway,times(1)).generateProductPreview(eq(CommerceSceneBackgroundAssets.PROMPT),eq("scene-capture"),eq(GROK),argThat(refs->refs.size()==2),eq(true));
        verify(h.models,never()).selectCommerceMockupRuntimeConnection(any());verify(h.models,never()).transparentArtworkConnection(anyString());verify(h.models,never()).imageConnection(anyString());
        verify(h.db).update(contains("status='UNKNOWN'"),eq("scene-capture"));
    }
    @Test void changedGrokFingerprintStopsBeforeClaimOrProviderCall()throws Exception{
        var h=harness();var j=job(h,"capture","PREPARED");j.put("model_fingerprint","changed");h.jobs.add(j);
        when(h.models.commerceMockupConnectionById("org","grok")).thenReturn(Optional.of(GROK));
        assertTrue(h.execute("MOCKUP").message().contains("配置已变化"));verifyNoInteractions(h.gateway,h.ledger);
    }
    @Test void savedArtworkSceneReusesExtractionArchiveAndCachedPlateWithoutPaidRequest()throws Exception{
        var h=harness();when(h.sourceArchives.find("capture","image",1,h.user)).thenReturn(key('5'));
        when(h.models.selectCommerceMockupRuntimeConnection(h.user)).thenReturn(Optional.of(GROK));
        when(h.db.queryForList(startsWith("SELECT storage_key,content_sha256,report_json"),eq("org"),anyString()))
                .thenReturn(List.of(Map.of("storage_key",key('2'),"content_sha256","2".repeat(64),"report_json","{}")));
        doReturn(1).when(h.db).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_jobs"),any(Object[].class));
        assertEquals("QUEUED",h.execute("MOCKUP").pendingStatus());
        var refs=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(h.db).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_jobs"),anyString(),eq("org"),eq("run"),eq("capture"),eq(1L),eq("SCENE_BACKGROUND"),anyString(),eq(CommerceSceneBackgroundAssets.PROMPT),eq("grok"),anyString(),refs.capture(),anyString(),eq(key('2')),eq("2".repeat(64)),eq("run"),eq("org"),eq("lease"),eq("u"));
        assertEquals(List.of(key('5'),key('b')),h.json.readValue(refs.getValue(),List.class));
        verifyNoInteractions(h.gateway,h.ledger);verify(h.sourceArchives).find("capture","image",1,h.user);
    }
}
