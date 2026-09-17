package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;

class CommerceDrapeReuseWorkflowTest {
    @Test void onePreparedMotherFeedsTwoDesignsAndTwoColorsAndReadyOutputsAreReused()throws Exception{
        var h=new Harness();assertEquals("QUEUED",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        for(int i=0;i<4;i++)assertEquals("QUEUED",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertNull(h.workflow.finish(h.run,h.source,h.config,h.jobs));assertNull(h.workflow.finish(h.run,h.source,h.config,h.jobs));
        verify(h.processor,times(1)).prepare(any(),any(),any(),any(),eq(h.config.renderPrintIds()));
        verify(h.processor,times(4)).render(any(),any(),any(),any(),any(),anyString(),eq(false));
        var outputs=h.jobs.stream().filter(j->CommerceDrapeReuseWorkflow.OUTPUT.equals(j.get("kind"))).toList();assertEquals(4,outputs.size());
        var identities=new HashSet<String>();
        for(var output:outputs){
            var report=h.reports.get(output.get("id"));assertEquals(h.masterKey,report.get("masterKey"));assertEquals(0,report.get("modelCalls"));
            int v=((Number)output.get("variant_index")).intValue();assertEquals(h.inks.get(v),report.get("artworkKey"));
            identities.add(v+":"+report.get("colorKey"));
        }
        assertEquals(Set.of("1:black","1:pink","2:black","2:pink"),identities);
        assertNull(h.workflow.review(h.run,h.plan,h.config,h.jobs));
    }
    @Test void alreadyReadyGuideNeverRecomposesOrCallsLocalProcessor()throws Exception{
        var h=new Harness();assertNull(h.workflow.prepareGuide(h.run,h.source,h.config,h.jobs));verifyNoInteractions(h.processor,h.local);
    }
    @Test void rebuiltGuideWithIdenticalPixelsCannotReuseAReportPointingToRetiredJobId()throws Exception{
        var h=new Harness();h.workflow.finish(h.run,h.source,h.config,h.jobs);h.workflow.finish(h.run,h.source,h.config,h.jobs);
        Object first=outputCacheInputs(h);
        h.jobs.remove(h.find(CommerceDrapeReuseWorkflow.OUTPUT,1,"black"));
        var guide=h.find("MOCKUP_FRONT",1,"black");String old=(String)guide.get("id");guide.put("id","rebuilt-guide");h.reports.put("rebuilt-guide",h.reports.remove(old));
        h.workflow.finish(h.run,h.source,h.config,h.jobs);Object rebuilt=outputCacheInputs(h);
        var cache=new CommerceMockupDerivativeCache(h.db,h.archive,h.json);
        assertNotEquals(cache.key("drape-output",first),cache.key("drape-output",rebuilt),"report contains execution-local guide ID, so pixel-only cache identity would resurrect a retired guide");
        assertEquals("rebuilt-guide",h.reports.get(h.find(CommerceDrapeReuseWorkflow.OUTPUT,1,"black").get("id")).get("localGuideId"));
    }
    private Object outputCacheInputs(Harness h){return org.mockito.Mockito.mockingDetails(h.local).getInvocations().stream().filter(i->i.getMethod().getName().equals("local")&&CommerceDrapeReuseWorkflow.OUTPUT.equals(i.getArgument(2))).reduce((a,b)->b).orElseThrow().getArgument(7);}
    @Test void masterReferencesUseOriginalPoseThenFrozenGuideAndSeedArtwork()throws Exception{
        var h=new Harness();assertEquals(List.of(h.originalKey,h.guideKey,h.inks.get(1)),h.workflow.masterReferences(h.source,h.jobs,h.originalKey));
    }
    @Test void wrongMasterGuideBindingStopsBeforeLocalPreparation()throws Exception{
        var h=new Harness();h.find(CommerceDrapeReuseWorkflow.MASTER,0,null).put("reference_keys",h.json.writeValueAsString(List.of(h.originalKey,h.originalKey,h.inks.get(1))));
        assertThrows(IllegalArgumentException.class,()->h.workflow.finish(h.run,h.source,h.config,h.jobs));verifyNoInteractions(h.processor,h.local);
    }
    @Test void failedPreparationDoesNotArchiveReadyBlankOrOutputs()throws Exception{
        var h=new Harness();var rejected=new LinkedHashMap<>(h.prepared());rejected.put("report",Map.of("passed",false,"outsideCleanupChangedPixels",1));
        when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenReturn(rejected);
        assertThrows(IllegalArgumentException.class,()->h.workflow.finish(h.run,h.source,h.config,h.jobs));
        assertNull(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null));assertEquals(0,h.jobs.stream().filter(j->CommerceDrapeReuseWorkflow.OUTPUT.equals(j.get("kind"))).count());
        verify(h.processor,never()).render(any(),any(),any(),any(),any(),anyString(),anyBoolean());
    }
    @Test void failedRenderDoesNotArchiveReadyOutputAndKeepsThePreparedMother()throws Exception{
        var h=new Harness();h.workflow.finish(h.run,h.source,h.config,h.jobs);
        doReturn(Map.of("data",Harness.encoded(h.output),"report",Map.of("passed",false,"outsideGarmentChangedPixels",2))).when(h.processor).render(any(),any(),any(),any(),any(),anyString(),anyBoolean());
        assertThrows(IllegalArgumentException.class,()->h.workflow.finish(h.run,h.source,h.config,h.jobs));
        assertNotNull(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null));assertNull(h.find(CommerceDrapeReuseWorkflow.OUTPUT,1,"black"));
        verify(h.processor,times(1)).prepare(any(),any(),any(),any(),anyList());
    }
    @Test void localServiceFailureNeverLosesThePaidMotherOrCreatesReadyResults()throws Exception{
        var h=new Harness();when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenThrow(new IllegalArgumentException("TRACKING_LOW"));
        assertThrows(IllegalArgumentException.class,()->h.workflow.finish(h.run,h.source,h.config,h.jobs));
        assertEquals("READY",h.find(CommerceDrapeReuseWorkflow.MASTER,0,null).get("status"));assertNull(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null));
    }
    @Test void fixedLocalTrackerCanResumeTheSameMotherAfterFailedPreparation()throws Exception{
        var h=new Harness();var mother=h.find(CommerceDrapeReuseWorkflow.MASTER,0,null);var savedMother=new LinkedHashMap<>(mother);
        when(h.processor.prepare(any(),any(),any(),any(),anyList()))
                .thenThrow(new IllegalArgumentException("FEATURE_TRACKING_FAILED"))
                .thenAnswer(call->h.prepared());
        assertThrows(IllegalArgumentException.class,()->h.workflow.finish(h.run,h.source,h.config,h.jobs));
        assertNull(h.find(CommerceDrapeReuseWorkflow.BLANK,0,null));assertEquals(savedMother,mother);
        assertEquals("QUEUED",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        for(int i=0;i<4;i++)assertEquals("QUEUED",h.workflow.finish(h.run,h.source,h.config,h.jobs).pendingStatus());
        assertNull(h.workflow.finish(h.run,h.source,h.config,h.jobs));assertNull(h.workflow.review(h.run,h.plan,h.config,h.jobs));
        assertSame(mother,h.find(CommerceDrapeReuseWorkflow.MASTER,0,null));assertEquals(savedMother,mother);
        assertEquals(1,h.jobs.stream().filter(j->CommerceDrapeReuseWorkflow.MASTER.equals(j.get("kind"))).count());
        verify(h.processor,times(2)).prepare(eq(h.master),eq(h.guide),eq(h.uv),eq(h.assets.get(h.inks.get(1))),eq(h.config.renderPrintIds()));
        verify(h.processor,times(4)).render(any(),any(),any(),any(),any(),anyString(),anyBoolean());
    }
    @Test void reviewRejectsMismatchedMotherArtworkColorGuideMaskAndPrintId()throws Exception{
        var h=new Harness();for(int i=0;i<5;i++)h.workflow.finish(h.run,h.source,h.config,h.jobs);
        var job=h.find(CommerceDrapeReuseWorkflow.OUTPUT,1,"black");var original=new LinkedHashMap<>(h.reports.get(job.get("id")));
        for(String field:List.of("masterKey","blankKey","artworkKey","colorKey","colorLabel","localGuideId","localGuideKey","alphaKey","printId")){
            var changed=new LinkedHashMap<>(original);changed.put(field,"different");h.reports.put((String)job.get("id"),changed);
            var result=h.workflow.review(h.run,h.plan,h.config,h.jobs);assertNotNull(result,"changed "+field+" must not pass");assertFalse(result.complete());
        }
        h.reports.put((String)job.get("id"),original);assertNull(h.workflow.review(h.run,h.plan,h.config,h.jobs));
        h.jobs.remove(h.find(CommerceDrapeReuseWorkflow.OUTPUT,2,"pink"));assertNotNull(h.workflow.review(h.run,h.plan,h.config,h.jobs));
    }

    static final class Harness {
        final ObjectMapper json=new ObjectMapper();final JdbcTemplate db=mock(JdbcTemplate.class);final CommerceImageArchive archive=mock(CommerceImageArchive.class);
        final CommerceDrapeImageProcessor processor=mock(CommerceDrapeImageProcessor.class);final CommerceLocalPhotoWorkflow local=mock(CommerceLocalPhotoWorkflow.class);
        final CommerceDrapeReuseWorkflow workflow=new CommerceDrapeReuseWorkflow(db,archive,json,processor,local);
        final List<Map<String,Object>> jobs=new ArrayList<>();final Map<String,Map<String,Object>> reports=new HashMap<>();final Map<String,byte[]> assets=new HashMap<>();final Map<Integer,String> inks=new HashMap<>();
        final Selection source=new Selection("capture",1,"image",List.of(),List.of(new CommerceSourceColorResolver.Color("black","Black",List.of("sku-black"),"image-black",true),new CommerceSourceColorResolver.Color("pink","Pink",List.of("sku-pink"),"image-pink",true)));
        final Plan plan=new Plan("snapshot",List.of(source),2,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",GROK_DRAPE_POLICY);
        final CommerceBatchExecution run=new CommerceBatchExecution("run","org","batch","user","MOCKUP","RUNNING",null,"lease",Instant.now().plusSeconds(300),Instant.now(),Instant.now());
        final CommercePrintTemplateService.Configuration config;
        final byte[] master,guide,uv,blank,alpha,output;final String originalKey,masterKey,guideKey,uvKey;
        Harness()throws Exception{
            var c=SavedArtworkFixture.configuration();var prints=c.photoPrints().stream().map(p->"FRONT".equals(p.side())?new CommercePrintTemplateService.PhotoPrint(p.id(),p.artworkId(),p.side(),.35,.2,.15,.15,0):p).toList();
            config=new CommercePrintTemplateService.Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),c.referenceSetId(),c.surfacePrints(),c.renderedViews(),c.blankRenderedViews(),c.surfaceUvViews(),c.placementMasks(),c.renderPrintIds(),c.renderBinding(),prints);
            master=SavedArtworkFixture.image(0xffcccccc,false);guide=SavedArtworkFixture.image(0xffeeeeee,false);uv=SavedArtworkFixture.uv(1);blank=SavedArtworkFixture.image(0xffdddddd,false);alpha=SavedArtworkFixture.image(0xffffffff,true);output=SavedArtworkFixture.image(0xff123456,false);
            masterKey=put(master);guideKey=put(guide);uvKey=put(uv);originalKey=put(SavedArtworkFixture.image(0xff654321,false));
            inks.put(1,put(SavedArtworkFixture.image(0xffff0000,true)));inks.put(2,put(SavedArtworkFixture.image(0xff0000ff,true)));
            when(archive.read(eq("org"),anyString())).thenAnswer(call->{byte[] bytes=assets.get(call.getArgument(1));if(bytes==null)throw new IllegalArgumentException("missing asset");return SurfaceTemplateFixture.reference(bytes);});
            when(archive.store(eq("org"),any(byte[].class))).thenAnswer(call->{byte[] bytes=call.getArgument(1);String key=put(bytes);return new CommerceImageArchive.Archived(key,CommerceImageArchive.sha(bytes),"image/png",bytes.length);});
            when(db.queryForList(eq("SELECT result_json FROM commerce_batch_artwork_jobs WHERE id=?"),anyString())).thenAnswer(call->{var r=reports.get(call.getArgument(1));return r==null?List.of():List.of(Map.of("result_json",json.writeValueAsString(r)));});
            add(CommerceDrapeReuseWorkflow.GUIDE,0,null,guideKey,List.of(guideKey,uvKey,inks.get(1)),Map.of());
            var mother=add(CommerceDrapeReuseWorkflow.MASTER,0,null,masterKey,List.of(originalKey,guideKey,inks.get(1)),Map.of());mother.put("prompt",CommerceDrapeReuseWorkflow.PROMPT);
            for(var color:source.colorTargets()){
                add("BASE_COLOR",0,color.key(),guideKey,List.of(guideKey,originalKey),Map.of("mode","SOURCE_PHOTO_COLOR","observedSourceRgb",color.key().equals("black")?"#202020":"#ee7788"));
                for(int v=1;v<=2;v++)add("MOCKUP_FRONT",v,color.key(),guideKey,List.of(guideKey,inks.get(v)),Map.of("artworkKeys",Map.of("front",inks.get(v))));
            }
            when(processor.prepare(any(),any(),any(),any(),anyList())).thenAnswer(call->prepared());
            when(processor.render(any(),any(),any(),any(),any(),anyString(),anyBoolean())).thenAnswer(call->{
                var check=new LinkedHashMap<String,Object>();check.put("passed",true);check.put("outsideGarmentChangedPixels",0);check.put("sourceSha256",CommerceImageArchive.sha(master));check.put("blankSha256",CommerceImageArchive.sha(blank));check.put("alphaSha256",CommerceImageArchive.sha(alpha));check.put("artworkSha256",CommerceImageArchive.sha(call.getArgument(4)));check.put("outputSha256",CommerceImageArchive.sha(output));check.put("targetRgb",((String)call.getArgument(5)).toLowerCase(Locale.ROOT));check.put("originalColor",false);check.put("printId","front");check.put("method",CommerceDrapeImageProcessor.VERSION);
                return Map.of("data",encoded(output),"algorithm",CommerceDrapeImageProcessor.VERSION,"report",check);
            });
            // Exercise each producer before marking a derivative ready. Any validation exception
            // leaves the immutable mother and existing derivatives in place, just as local() does.
            doAnswer(call->{Supplier<CommerceLocalPhotoWorkflow.Produced> producer=call.getArgument(8);var made=producer.get();String kind=call.getArgument(2);int v=call.getArgument(3);CommerceSourceColorResolver.Color color=call.getArgument(4);
                add(kind,v,color==null?null:color.key(),put(made.png()),call.getArgument(5),json.convertValue(made.report(),Map.class));return CommerceBatchStageHandler.Result.progress("local");
            }).when(local).local(eq(run),eq(source),anyString(),anyInt(),any(),anyList(),anyString(),any(),any(),anyString());
        }
        String put(byte[] png){String key=CommerceSurfaceTemplateInputs.key("org",CommerceImageArchive.sha(png));assets.put(key,png);return key;}
        Map<String,Object> add(String kind,int v,String color,String key,List<String> refs,Map<String,Object> report){var j=new LinkedHashMap<String,Object>();j.put("id",kind+"-"+v+"-"+color);j.put("kind",kind);j.put("variant_index",v);j.put("color_key",color);j.put("capture_id",source.captureId());j.put("status","READY");j.put("storage_key",key);j.put("reference_keys",json.writeValueAsString(refs));j.put("model_config_id",CommerceLocalPhotoWorkflow.POLICY);jobs.add(j);reports.put((String)j.get("id"),new LinkedHashMap<>(report));return j;}
        Map<String,Object> find(String kind,int v,String color){return jobs.stream().filter(j->kind.equals(j.get("kind"))&&((Number)j.get("variant_index")).intValue()==v&&Objects.equals(color,j.get("color_key"))).findFirst().orElse(null);}
        Map<String,Object> prepared(){
            var mapping=new LinkedHashMap<String,Object>();mapping.put("algorithm",CommerceDrapeImageProcessor.VERSION);mapping.put("printId","front");mapping.put("sourceSha256",CommerceImageArchive.sha(master));mapping.put("guideSha256",CommerceImageArchive.sha(guide));mapping.put("uvSha256",CommerceImageArchive.sha(uv));mapping.put("artworkSha256",inks.get(1).substring(65));mapping.put("blankSha256",CommerceImageArchive.sha(blank));mapping.put("alphaSha256",CommerceImageArchive.sha(alpha));
            return Map.of("blank",encoded(blank),"alpha",encoded(alpha),"mapping",mapping,"report",Map.of("passed",true,"outsideCleanupChangedPixels",0),"algorithm",CommerceDrapeImageProcessor.VERSION,"sourceSha256",CommerceImageArchive.sha(master),"guideSha256",CommerceImageArchive.sha(guide),"uvSha256",CommerceImageArchive.sha(uv),"artworkSha256",inks.get(1).substring(65));
        }
        static String encoded(byte[] bytes){return Base64.getEncoder().encodeToString(bytes);}
    }
}
