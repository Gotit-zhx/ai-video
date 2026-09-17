package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceDrapeBackPreviewTest {
    static final class Harness {
        final CommerceSavedArtworkTest.Harness h=new CommerceSavedArtworkTest.Harness();
        final CommerceBatchExecutionService executions=mock(CommerceBatchExecutionService.class);final CommerceGarmentForeground foreground=mock(CommerceGarmentForeground.class);
        final CommerceBackPreviewService service=new CommerceBackPreviewService(h.db,executions,h.plans,h.templates,h.archive,h.json,foreground);
        final Map<String,Object> front,draped;
        Harness()throws Exception{
            h.plan=CommerceDrapePlanPolicyTest.withPolicy(h.plan,CommerceBatchCreativePlanValidator.GROK_DRAPE_POLICY);h.stubPlan();
            when(executions.get("batch",h.user)).thenReturn(new CommerceBatchExecution("run","org","batch","u","MOCKUP","RUNNING",null,"lease",Instant.now().plusSeconds(300),Instant.now(),Instant.now()));
            front=job("precise","MOCKUP_FRONT",1,"black",h.a.storageKey());draped=job("draped","DRAPED_FRONT",1,"black",h.b.storageKey());
            draped.put("result_json",h.json.writeValueAsString(Map.of("localGuideId","precise","localGuideKey",h.a.storageKey())));
            job("matte-1","MATTE_G1",1,null,h.a.storageKey());job("matte-2","MATTE_G2",1,null,h.b.storageKey());
            var base=job("base","BASE_COLOR",0,"black",h.a.storageKey());base.put("reference_keys",h.json.writeValueAsString(List.of(h.a.storageKey(),h.b.storageKey())));
            when(h.db.queryForList(eq("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status='READY'"),eq("run"),eq("org"))).thenAnswer(call->new ArrayList<>(h.jobs));
            when(h.db.queryForList(startsWith("SELECT storage_key,content_sha256,report_json"),eq("org"),anyString())).thenReturn(List.of(Map.of("storage_key",h.a.storageKey(),"content_sha256",h.a.contentSha256(),"report_json","{}")));
        }
        Map<String,Object> job(String id,String kind,int v,String color,String key){var j=new LinkedHashMap<String,Object>();j.put("id",id);j.put("kind",kind);j.put("capture_id","capture");j.put("variant_index",v);j.put("color_key",color);j.put("status","READY");j.put("model_config_id",CommerceLocalPhotoWorkflow.POLICY);j.put("storage_key",key);h.jobs.add(j);return j;}
    }
    @Test void drapedFrontReusesMatchingOriginalGuideForBackAndPrintedSides()throws Exception{
        var h=new Harness();for(String side:List.of("BACK","LEFT","RIGHT"))assertArrayEquals(h.h.assets.get(h.h.a.storageKey()),Base64.getDecoder().decode(h.service.render("batch","draped",side,h.h.user).dataBase64()));
        verifyNoInteractions(h.foreground,h.h.models,h.h.gateway);
        assertEquals(h.h.a.storageKey(),h.front.get("storage_key"));assertEquals(h.h.b.storageKey(),h.draped.get("storage_key"));
    }
    @Test void differentCaptureVariantColorOrGuideKeyCannotSupplyTheBackOfADrapedFront()throws Exception{
        for(String field:List.of("capture_id","variant_index","color_key","storage_key")){
            var h=new Harness();h.front.put(field,field.equals("variant_index")?2:"different");
            var failure=assertThrows(IllegalArgumentException.class,()->h.service.render("batch","draped",h.h.user));assertTrue(failure.getMessage().contains("同款同色"),field);
            verifyNoInteractions(h.foreground,h.h.models,h.h.gateway);
        }
    }
}
