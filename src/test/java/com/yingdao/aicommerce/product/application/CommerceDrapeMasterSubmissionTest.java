package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;

class CommerceDrapeMasterSubmissionTest {
    CommerceSavedArtworkTest.Harness harness(String state)throws Exception{
        var h=new CommerceSavedArtworkTest.Harness();h.plan=new Plan(h.plan.printTemplateSnapshotId(),h.plan.sources(),1,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",GROK_DRAPE_POLICY);h.stubPlan();
        var config=DrapeTestFixture.configuration();when(h.templates.snapshot("org","fixed-snapshot")).thenReturn(new CommerceTemplateVersion("fixed-snapshot","org","template",1,"PRINT_LAYOUT","photo",h.json.writeValueAsString(config),"u",Instant.now()));
        String white=CommerceSurfaceTemplateInputs.key("org","b".repeat(64)),uv=CommerceSurfaceTemplateInputs.key("org","c".repeat(64));h.assets.put(white,DrapeTestFixture.white());
        h.addBase();add(h,"EXTRACT",0,null,h.a.storageKey(),"IMG2");add(h,"MATTE_G1",1,null,h.a.storageKey(),CommerceLocalPhotoWorkflow.POLICY);add(h,"MATTE_G2",1,null,h.b.storageKey(),CommerceLocalPhotoWorkflow.POLICY);
        add(h,"MOCKUP_FRONT",1,"black",white,CommerceLocalPhotoWorkflow.POLICY);
        var guide=add(h,CommerceDrapeReuseWorkflow.GUIDE,0,null,white,CommerceLocalPhotoWorkflow.POLICY);guide.put("reference_keys",h.json.writeValueAsString(List.of(white,uv,h.a.storageKey())));
        var master=add(h,CommerceDrapeReuseWorkflow.MASTER,0,null,h.b.storageKey(),"grok");master.put("id","drape-mother");master.put("status",state);master.put("prompt",CommerceDrapeReuseWorkflow.PROMPT);
        master.put("request_key","mother-request");master.put("model_fingerprint",CommerceBatchArtworkService.fingerprint(CommerceGrokSceneWorkflowTest.GROK));master.put("reference_keys",h.json.writeValueAsString(List.of(h.b.storageKey(),white,h.a.storageKey())));
        return h;
    }
    Map<String,Object> add(CommerceSavedArtworkTest.Harness h,String kind,int variant,String color,String key,String model){var j=new LinkedHashMap<String,Object>();j.put("id",kind+variant+color);j.put("capture_id","capture");j.put("kind",kind);j.put("variant_index",variant);j.put("color_key",color);j.put("status","READY");j.put("storage_key",key);j.put("content_sha256",key.substring(65));j.put("model_config_id",model);j.put("reference_keys","[]");h.jobs.add(j);return j;}
    @Test void unknownAndSubmittingMotherNeverStartAnotherPaidGeneration()throws Exception{
        for(String state:List.of("UNKNOWN","SUBMITTING")){
            var h=harness(state);var result=h.execute("MOCKUP");assertEquals("UNKNOWN",result.pendingStatus(),result.message());
            verifyNoInteractions(h.models,h.gateway,h.ledger,h.sourceArchives);
        }
    }
    @Test void preparedMotherSubmitsOneStrictGrokRequestWithFrozenIdAndReferences()throws Exception{
        var h=harness("PREPARED");var model=CommerceGrokSceneWorkflowTest.GROK;
        when(h.models.commerceMockupConnectionById("org","grok")).thenReturn(Optional.of(model));when(h.ledger.claim(any(),eq("drape-mother"),eq("mother-request"))).thenReturn("CLAIMED");
        when(h.gateway.generateProductPreview(anyString(),eq("drape-mother"),eq(model),anyList(),eq(true))).thenThrow(new IllegalStateException("unknown after submit"));
        var result=h.execute("MOCKUP");assertEquals("UNKNOWN",result.pendingStatus(),result.message());
        verify(h.gateway,times(1)).generateProductPreview(eq(CommerceDrapeReuseWorkflow.PROMPT),eq("drape-mother"),eq(model),argThat(refs->refs.size()==3),eq(true));
        verify(h.models,never()).selectCommerceMockupRuntimeConnection(any());verify(h.models,never()).imageConnection(anyString());verify(h.models,never()).transparentArtworkConnection(anyString());verifyNoInteractions(h.sourceArchives);
    }
    @Test void exhaustedMotherBudgetStopsBeforeProviderAndRetainsFixedJob()throws Exception{
        var h=harness("PREPARED");when(h.models.commerceMockupConnectionById("org","grok")).thenReturn(Optional.of(CommerceGrokSceneWorkflowTest.GROK));
        when(h.ledger.claim(any(),eq("drape-mother"),eq("mother-request"))).thenReturn("LIMIT_REACHED");
        assertTrue(h.execute("MOCKUP").message().contains("调用次数上限"));verifyNoInteractions(h.gateway);
        assertEquals(1,h.jobs.stream().filter(j->CommerceDrapeReuseWorkflow.MASTER.equals(j.get("kind"))).count());
    }
    @Test void readyMotherWithRepeatedLocalTrackingFailureNeverReentersPaidSubmission()throws Exception{
        var h=harness("READY");var processor=mock(CommerceDrapeImageProcessor.class);
        org.springframework.test.util.ReflectionTestUtils.setField(h.service,"drapeProcessor",processor);
        var report=Map.of("artworkKeys",Map.of("front",h.a.storageKey()));
        when(h.db.queryForList("SELECT result_json FROM commerce_batch_artwork_jobs WHERE id=?","MOCKUP_FRONT1black"))
                .thenReturn(List.of(Map.of("result_json",h.json.writeValueAsString(report))));
        when(processor.prepare(any(),any(),any(),any(),anyList())).thenThrow(new IllegalArgumentException("FEATURE_TRACKING_FAILED"));
        for(int attempt=0;attempt<2;attempt++){
            var stopped=h.execute("MOCKUP");assertEquals("WAITING_INPUT",stopped.pendingStatus(),stopped.message());
            assertTrue(stopped.message().contains("FEATURE_TRACKING_FAILED"));assertTrue(stopped.message().contains("不会追加生图"));
        }
        verify(processor,times(2)).prepare(any(),any(),any(),any(),anyList());
        verifyNoInteractions(h.models,h.gateway,h.ledger,h.sourceArchives);
        assertEquals(1,h.jobs.stream().filter(j->CommerceDrapeReuseWorkflow.MASTER.equals(j.get("kind"))).count());
        assertFalse(h.jobs.stream().anyMatch(j->CommerceDrapeReuseWorkflow.BLANK.equals(j.get("kind"))||CommerceDrapeReuseWorkflow.OUTPUT.equals(j.get("kind"))));
    }
}
