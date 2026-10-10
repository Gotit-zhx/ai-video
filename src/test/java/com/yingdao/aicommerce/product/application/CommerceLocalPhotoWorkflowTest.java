package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local derivatives are reusable assets, never additional image-model attempts. */
class CommerceLocalPhotoWorkflowTest {
    final ObjectMapper json=new ObjectMapper();
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final CommerceImageArchive archive=mock(CommerceImageArchive.class);
    final CommerceLocalPhotoWorkflow workflow=spy(new CommerceLocalPhotoWorkflow(db,archive,json));
    final Instant now=Instant.parse("2026-09-14T00:00:00Z");
    final CommerceBatchExecution run=new CommerceBatchExecution("run","org","batch","user","MOCKUP","RUNNING",null,"lease",now.plusSeconds(300),now,now);
    final String base=key('b'),matte=key('a'),product=key('c');

    static String key(char value){return CommerceImageArchive.sha("org".getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+String.valueOf(value).repeat(64);}
    CommerceSourceColorResolver.Color color(String key,String label){return new CommerceSourceColorResolver.Color(key,label,List.of(key+"-s",key+"-m"),key+"-image",true);}
    CommerceBatchCreativePlanValidator.Selection selection(List<CommerceSourceColorResolver.Color> colors){return new CommerceBatchCreativePlanValidator.Selection("capture",1,"source-image",List.of(),colors);}
    CommerceBatchCreativePlanValidator.Plan plan(CommerceBatchCreativePlanValidator.Selection source){return new CommerceBatchCreativePlanValidator.Plan("template",List.of(source),1,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS","LOCAL_PHOTO_V1");}
    CommercePrintTemplateService.Configuration config(){return new CommercePrintTemplateService.Configuration("T_SHIRT",
            List.of(new CommercePrintTemplateService.Area("front","FRONT",.4,.4,.2,.2,100,100,300)),Map.of("FRONT","b".repeat(64)));}
    Map<String,Object> job(String kind,int variant,String color,String storage){
        var row=new LinkedHashMap<String,Object>();row.put("id",kind+"-"+variant+"-"+color);row.put("capture_id","capture");row.put("variant_index",variant);
        row.put("kind",kind);row.put("color_key",color);row.put("color_label",color);row.put("status","READY");row.put("storage_key",storage);
        row.put("content_sha256",storage.substring(65));row.put("model_config_id","LOCAL_PHOTO_V1");row.put("reference_keys",json.writeValueAsString(kind.equals("MOCKUP")?List.of(base,matte):List.of()));
        return row;
    }
    List<Map<String,Object>> completed(String color){return List.of(job("MATTE",1,null,matte),job("BASE_COLOR",0,color,base));}
    Map<String,Object> report(String color){
        var report=new LinkedHashMap<String,Object>();report.put("status","LOCAL_PHOTO_REVIEW_REQUIRED");report.put("method","LOCAL_PHOTO_V1");
        report.put("productionReady",false);report.put("modelCalls",0);report.put("colorKey",color);report.put("colorLabel",color);
        report.put("baseKey",base);report.put("artworkKeys",Map.of("front",matte));
        report.put("photoCheck",Map.of("status","PHOTO_COMPOSITED_REVIEW_REQUIRED","protectedChangedPixels",0,"visiblePrintIds",List.of("front"),"mappedPixels",100,"productionReady",false));
        return report;
    }
    void stubReports(Map<String,Object> report){when(db.queryForList(startsWith("SELECT result_json"),anyString(),eq("org")))
            .thenReturn(List.of(Map.of("result_json",json.writeValueAsString(report))));}

    @Test void photoMarkerGroupUsesOneNewArtworkAcrossFrontBackAndSidePlacements() {
        var source=selection(List.of(color("black","black")));String artwork="a".repeat(64);
        var prints=List.of("FRONT","BACK","LEFT","RIGHT").stream().map(side->new CommercePrintTemplateService.PhotoPrint(side,artwork,side,.2,.2,.3,.3,0)).toList();
        var config=new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(),Map.of(),null,"b".repeat(64),"refs",List.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of("FRONT","BACK","LEFT","RIGHT"),"photo-binding",prints);
        var jobs=List.of(job("MATTE_G1",1,null,matte),job("BASE_COLOR",0,"black",base));var seen=new ArrayList<String>();
        doAnswer(call->{
            String side=((String)call.getArgument(2)).substring("MOCKUP_".length());List<?> inputs=call.getArgument(7);
            assertEquals(Map.of(side,matte),inputs.get(3));seen.add(side);return CommerceBatchStageHandler.Result.progress("same-group");
        }).when(workflow).local(eq(run),eq(source),startsWith("MOCKUP_"),eq(1),any(),anyList(),eq(CommercePhotoMockupComposer.PHOTO_VERSION),any(),any(),anyString());
        for(String side:List.of("FRONT","BACK","LEFT","RIGHT")) {
            var target=new CommerceSurfaceTemplateInputs.Side(side,key('7'),base,key('d'),key('e'),new byte[0],new byte[0],Set.of(side));
            assertEquals("same-group",workflow.execute(run,plan(source),source,config,target,base,jobs,ignored->{throw new AssertionError("ready color must be reused");}).message());
        }
        assertEquals(List.of("FRONT","BACK","LEFT","RIGHT"),seen);verifyNoInteractions(db,archive);
    }

    @Test void scenePlateIsFrozenInLocalOutputCacheAndReferenceBindings() {
        var source=selection(List.of(color("black","black")));var config=SavedArtworkFixture.configuration();
        var plan=new CommerceBatchCreativePlanValidator.Plan("template",List.of(source),1,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS","GROK_SCENE_V1");
        String scene=key('2');var plate=job("SCENE_BACKGROUND",0,null,scene);
        plate.put("prompt",CommerceSceneBackgroundAssets.PROMPT);plate.put("reference_keys",json.writeValueAsString(List.of(key('5'),base)));
        var jobs=List.of(plate,job("MATTE_G1",1,null,matte),job("MATTE_G2",1,null,matte),job("BASE_COLOR",0,"black",base));
        doAnswer(call->{
            List<String> refs=call.getArgument(5);List<?> inputs=call.getArgument(7);
            assertTrue(refs.contains(scene));assertTrue(inputs.contains(scene));assertTrue(inputs.contains(CommerceSceneBackgroundComposer.VERSION));
            assertEquals(Map.of("front",matte),inputs.get(3));return CommerceBatchStageHandler.Result.progress("scene-bound");
        }).when(workflow).local(eq(run),eq(source),eq("MOCKUP_FRONT"),eq(1),any(),anyList(),eq(CommercePhotoMockupComposer.PHOTO_VERSION),any(),any(),anyString());
        var side=new CommerceSurfaceTemplateInputs.Side("FRONT",key('7'),base,key('d'),key('e'),new byte[0],new byte[0],Set.of("front"));
        assertEquals("scene-bound",workflow.execute(run,plan,source,config,side,base,jobs,ignored->{throw new AssertionError("ready color must be reused");}).message());
    }

    @Test void sceneReviewRejectsOldWhiteBackgroundOutputDespiteValidLocalPrintReport() {
        var source=selection(List.of(color("black","black")));var config=SavedArtworkFixture.configuration();
        var plan=new CommerceBatchCreativePlanValidator.Plan("template",List.of(source),1,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS","GROK_SCENE_V1");
        var plate=job("SCENE_BACKGROUND",0,null,key('2'));plate.put("prompt",CommerceSceneBackgroundAssets.PROMPT);
        plate.put("reference_keys",json.writeValueAsString(List.of(key('5'),base)));
        var output=job("MOCKUP_FRONT",1,"black",product);output.put("reference_keys",json.writeValueAsString(List.of(base,matte)));
        stubReports(report("black"));
        var result=workflow.review(run,plan,config,List.of(plate,job("MATTE_G1",1,null,matte),job("BASE_COLOR",0,"black",base),output));
        assertNotNull(result);assertTrue(result.message().contains("共用场景"));
        verify(db,never()).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_checks"),any(),any());
    }

    @Test void sameMasterIsSharedAcrossColorsButColorIdentityIsIncludedInCachedResultBinding() {
        var capturedInputs=new ArrayList<Object>();var capturedRefs=new ArrayList<List<String>>();
        doAnswer(call->{capturedRefs.add(call.getArgument(5));capturedInputs.add(call.getArgument(7));return CommerceBatchStageHandler.Result.progress("local");})
                .when(workflow).local(eq(run),any(),eq("MOCKUP"),eq(1),any(),anyList(),anyString(),any(),any(),anyString());
        for(var color:List.of(color("black","黑色"),color("black-alt","经典黑"))) {
            var source=selection(List.of(color));
            var result=workflow.execute(run,plan(source),source,config(),null,base,completed(color.key()),ignored->{throw new AssertionError("ready color bases must be reused");});
            assertEquals("QUEUED",result.pendingStatus());
        }
        assertEquals(List.of(base,matte),capturedRefs.getFirst());assertEquals(capturedRefs.getFirst(),capturedRefs.getLast(),"same design bytes and base are reused");
        var cache=new CommerceMockupDerivativeCache(db,archive,json);
        assertNotEquals(cache.key(CommercePhotoMockupComposer.VERSION,capturedInputs.getFirst()),cache.key(CommercePhotoMockupComposer.VERSION,capturedInputs.getLast()),
                "two distinct frozen SKU color identities must not reuse a report labeled with the first color");
        verifyNoInteractions(archive,db);
    }

    @Test void readyLocalProductsDoNotRecomputeMastersBasesOrFinalImages() {
        var color=color("black","黑色");var source=selection(List.of(color));var jobs=new ArrayList<>(completed(color.key()));jobs.add(job("MOCKUP",1,color.key(),product));
        assertNull(workflow.execute(run,plan(source),source,config(),null,base,jobs,ignored->{throw new AssertionError("ready base must not be re-archived");}));
        verifyNoInteractions(db,archive);
    }

    @Test void missingVariationStopsBeforeAnyLocalImageOrCacheAccess() {
        var source=selection(List.of(color("black","黑色")));
        var result=workflow.execute(run,plan(source),source,config(),null,base,List.of(),ignored->{throw new AssertionError("must check artwork first");});
        assertFalse(result.complete());assertEquals("WAITING_INPUT",result.pendingStatus());assertTrue(result.message().contains("缺少已归档的新印花"));
        verifyNoInteractions(db,archive);
    }

    @Test void validLocalReviewRetainsExplicitBusinessReviewRequirement() {
        var color=color("black","black");var source=selection(List.of(color));var jobs=new ArrayList<>(completed(color.key()));jobs.add(job("MOCKUP",1,color.key(),product));
        stubReports(report(color.key()));
        assertNull(workflow.review(run,plan(source),config(),jobs));
        verify(archive).read("org",product);
        var saved=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_checks"),eq("MOCKUP-1-black"),saved.capture());
        var check=json.readTree(saved.getValue());assertFalse(check.path("productionReady").asBoolean());assertEquals("LOCAL_PHOTO_REVIEW_REQUIRED",check.path("status").asText());
    }

    @Test void localReviewRejectsAReportBoundToAnotherColorBaseOrArtwork() {
        var color=color("black","black");var source=selection(List.of(color));var jobs=new ArrayList<>(completed(color.key()));jobs.add(job("MOCKUP",1,color.key(),product));
        var wrongColor=report(color.key());wrongColor.put("colorKey","blue");
        var wrongBase=report(color.key());wrongBase.put("baseKey",key('d'));
        var wrongArtwork=report(color.key());wrongArtwork.put("artworkKeys",Map.of("front",key('e')));
        for(var mismatched:List.of(wrongColor,wrongBase,wrongArtwork)) {
            stubReports(mismatched);
            var rejected=workflow.review(run,plan(source),config(),jobs);
            assertNotNull(rejected,"review must reject mismatched frozen input bindings");
            assertTrue(rejected.message().contains("检查记录与当前颜色")||rejected.message().contains("印花绑定不完整"),rejected.message());
        }
        verify(db,never()).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_checks"),any(),any());
        verifyNoInteractions(archive);
    }

    @Test void incompleteColorOutputsCannotReachBatchReviewCompletion() {
        var source=selection(List.of(color("black","black"),color("white","white")));
        var jobs=new ArrayList<>(completed("black"));jobs.add(job("MOCKUP",1,"black",product));stubReports(report("black"));
        var result=workflow.review(run,plan(source),config(),jobs);assertNotNull(result);assertFalse(result.complete());
        assertTrue(result.message().contains("各款各色主图尚未全部归档"));
    }

    @Test void twoVisibleArtworkGroupsCannotSwapMastersWhileKeepingTheSameReferenceSet() throws Exception {
        var config=RepeatedSurfaceArtworkFixture.configuration();var source=selection(List.of(color("black","black")));
        String second=key('e'),blank=CommerceSurfaceTemplateInputs.key("org",config.blankRenderedViews().get("FRONT")),uv=CommerceSurfaceTemplateInputs.key("org",config.surfaceUvViews().get("FRONT"));
        var output=job("MOCKUP_FRONT",1,"black",product);output.put("reference_keys",json.writeValueAsString(List.of(base,blank,uv,matte,second)));
        var jobs=List.of(job("MATTE_G1",1,null,matte),job("MATTE_G2",1,null,second),job("BASE_COLOR",0,"black",base),output);
        var swapped=report("black");swapped.put("artworkKeys",Map.of("front-checker",second,"front-repeat",second,"fixed-badge",matte));
        swapped.put("photoCheck",Map.of("status","PHOTO_COMPOSITED_REVIEW_REQUIRED","protectedChangedPixels",0,"visiblePrintIds",List.of("front-checker","front-repeat","fixed-badge"),"mappedPixels",100,"productionReady",false));
        stubReports(swapped);
        when(archive.read("org",uv)).thenReturn(SurfaceTemplateFixture.reference(RepeatedSurfaceArtworkFixture.assets().get(config.surfaceUvViews().get("FRONT"))));
        var rejected=workflow.review(run,plan(source),config,jobs);
        assertNotNull(rejected,"reference-set membership is insufficient: each print must use its own frozen group master");
        assertTrue(rejected.message().contains("图案互换"),rejected.message());
        verify(archive,never()).read("org",product);
        verify(db,never()).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_checks"),any(),any());
    }

    @Test void cacheValidatesTenantStorageOwnershipOnEveryReuse(@TempDir Path directory)throws Exception {
        var realArchive=new CommerceImageArchive(directory.toString());var cache=new CommerceMockupDerivativeCache(db,realArchive,json);
        var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);image.setRGB(8,8,0xffff0000);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);var stored=realArchive.store("org",bytes.toByteArray());
        String cacheKey=cache.key(CommerceArtworkMatte.VERSION,List.of(stored.storageKey()));
        var row=Map.<String,Object>of("storage_key",stored.storageKey(),"content_sha256",stored.sha256(),"report_json","{}");
        when(db.queryForList(startsWith("SELECT storage_key"),eq("org"),eq(cacheKey))).thenReturn(List.of(row));
        when(db.queryForList(startsWith("SELECT storage_key"),eq("other-org"),eq(cacheKey))).thenReturn(List.of(row));
        assertEquals(stored.storageKey(),cache.find("org",cacheKey).storageKey());
        assertThrows(IllegalArgumentException.class,()->cache.find("other-org",cacheKey));
        assertNotEquals(cacheKey,cache.key(CommerceArtworkMatte.VERSION+"-next",List.of(stored.storageKey())));
        assertNotEquals(cacheKey,cache.key(CommerceArtworkMatte.VERSION,List.of(key('e'))));
    }

    CommerceBatchCreativePlanValidator.Plan templatePlan(CommerceBatchCreativePlanValidator.Selection source,int variants) {
        return new CommerceBatchCreativePlanValidator.Plan("template",List.of(source),variants,RepeatedSurfaceArtworkFixture.CHECKER,"TEMPLATE_ARTWORK","SOURCE_SKU_COLORS","LOCAL_PHOTO_V1");
    }
    CommerceSurfaceTemplateInputs.Side front(CommercePrintTemplateService.Configuration config) {
        return new CommerceSurfaceTemplateInputs.Side("FRONT",key('7'),base,key('d'),key('e'),new byte[0],new byte[0],Set.of("front-checker","front-repeat","fixed-badge"));
    }
    Map<String,Object> preservedBadge(String output) {
        var row=job("MATTE_G2",0,null,output);String input=CommerceSurfaceTemplateInputs.key("org",RepeatedSurfaceArtworkFixture.BADGE);
        row.put("reference_keys",json.writeValueAsString(List.of(input)));
        row.put("model_fingerprint",new CommerceMockupDerivativeCache(db,archive,json).key(CommerceArtworkMatte.VERSION,List.of(input)));
        return row;
    }
    void verifyGuideReadsOnly(){
        verify(db,atLeastOnce()).queryForList(startsWith("SELECT background_seeds_json"),eq("org"),anyString());
        verifyNoMoreInteractions(db);
    }
    byte[] uploadedGraphic(int background)throws Exception {
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)image.setRGB(x,y,background);
        for(int y=12;y<52;y++)for(int x=12;x<52;x++)image.setRGB(x,y,0xff331177);
        for(int y=25;y<39;y++)for(int x=25;x<39;x++)image.setRGB(x,y,0xffffffff);
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }

    @Test void unselectedUploadedGroupIsMattedBeforeAnyProductAndKeepsEnclosedWhite()throws Exception {
        var config=RepeatedSurfaceArtworkFixture.configuration();var source=selection(List.of(color("black","black")));
        String input=CommerceSurfaceTemplateInputs.key("org",RepeatedSurfaceArtworkFixture.BADGE);
        when(archive.read("org",input)).thenReturn(SurfaceTemplateFixture.reference(uploadedGraphic(0xffffffff)));
        doAnswer(call->{
            assertEquals(List.of(input),call.getArgument(5));assertEquals(List.of(input),call.getArgument(7));
            Supplier<CommerceLocalPhotoWorkflow.Produced> producer=call.getArgument(8);var produced=producer.get();
            var png=CommerceMockupComposer.decode(produced.png());assertEquals(0,png.getRGB(0,0)>>>24);
            assertEquals(0xffffffff,png.getRGB(30,30),"white details inside the uploaded graphic remain intact");
            assertEquals(CommerceArtworkMatte.Mode.EDGE_CONNECTED_WHITE,((CommerceArtworkMatte.Report)produced.report()).mode());
            return CommerceBatchStageHandler.Result.progress("preserved");
        }).when(workflow).local(eq(run),eq(source),eq("MATTE_G2"),eq(0),isNull(),anyList(),eq(CommerceArtworkMatte.VERSION),any(),any(),anyString());
        var result=workflow.execute(run,templatePlan(source,3),source,config,front(config),base,List.of(),ignored->{throw new AssertionError("matte is prepared first");});
        assertEquals("preserved",result.message());assertEquals("QUEUED",result.pendingStatus());
        verify(workflow,never()).local(eq(run),any(),startsWith("MOCKUP"),anyInt(),any(),anyList(),anyString(),any(),any(),anyString());
        verifyGuideReadsOnly();
    }

    @Test void onePreservedGroupMasterIsReusedForEveryVariantAndColorAtItsOriginalPositions() {
        var config=RepeatedSurfaceArtworkFixture.configuration();var source=selection(List.of(color("black","black"),color("white","white")));
        String kept=key('6'),second=key('8');var jobs=new ArrayList<Map<String,Object>>();
        jobs.add(preservedBadge(kept));jobs.add(job("MATTE",1,null,matte));jobs.add(job("MATTE",2,null,second));
        jobs.add(job("BASE_COLOR",0,"black",base));jobs.add(job("BASE_COLOR",0,"white",base));
        var seen=new ArrayList<String>();
        doAnswer(call->{
            int variant=call.getArgument(3);CommerceSourceColorResolver.Color color=call.getArgument(4);
            List<?> inputs=call.getArgument(7);Map<?,?> replacements=(Map<?,?>)inputs.get(3);
            assertEquals(Map.of("front-checker",variant==1?matte:second,"front-repeat",variant==1?matte:second,"fixed-badge",kept),replacements);
            List<String> refs=call.getArgument(5);assertTrue(refs.contains(kept));assertFalse(refs.contains(CommerceSurfaceTemplateInputs.key("org",RepeatedSurfaceArtworkFixture.BADGE)));
            seen.add(variant+":"+color.key());return CommerceBatchStageHandler.Result.progress("composed");
        }).when(workflow).local(eq(run),eq(source),eq("MOCKUP_FRONT"),anyInt(),any(),anyList(),anyString(),any(),any(),anyString());
        for(int variant=1;variant<=2;variant++)for(var color:source.colorTargets()) {
            var result=workflow.execute(run,templatePlan(source,2),source,config,front(config),base,jobs,ignored->{throw new AssertionError("base already prepared");});
            assertEquals("composed",result.message());jobs.add(job("MOCKUP_FRONT",variant,color.key(),product));
        }
        assertEquals(List.of("1:black","1:white","2:black","2:white"),seen);
        assertNull(workflow.execute(run,templatePlan(source,2),source,config,front(config),base,jobs,ignored->{throw new AssertionError();}));
        verifyNoInteractions(archive); verifyGuideReadsOnly();
    }

    @Test void unsafeUnselectedArtworkStopsClearlyInsteadOfPassingAnOpaqueBackgroundToComposition()throws Exception {
        var config=RepeatedSurfaceArtworkFixture.configuration();var source=selection(List.of(color("black","black")));
        String input=CommerceSurfaceTemplateInputs.key("org",RepeatedSurfaceArtworkFixture.BADGE);
        when(archive.read("org",input)).thenReturn(SurfaceTemplateFixture.reference(uploadedGraphic(0xffccccaa)));
        doAnswer(call->{Supplier<CommerceLocalPhotoWorkflow.Produced> producer=call.getArgument(8);return producer.get();})
                .when(workflow).local(eq(run),eq(source),eq("MATTE_G2"),eq(0),isNull(),anyList(),anyString(),any(),any(),anyString());
        var result=workflow.execute(run,templatePlan(source,1),source,config,front(config),base,List.of(),ignored->{throw new AssertionError();});
        assertEquals("WAITING_INPUT",result.pendingStatus());assertTrue(result.message().contains("背景不是均匀白底"),result.message());
        verify(workflow,never()).local(eq(run),any(),startsWith("MOCKUP"),anyInt(),any(),anyList(),anyString(),any(),any(),anyString());
        verifyGuideReadsOnly();
    }

    @Test void staticMatteFromAnotherUploadCannotBeReusedJustBecauseItsGroupNumberMatches() {
        var config=RepeatedSurfaceArtworkFixture.configuration();var source=selection(List.of(color("black","black")));
        var wrong=preservedBadge(key('6'));wrong.put("reference_keys",json.writeValueAsString(List.of(key('9'))));
        var result=workflow.execute(run,templatePlan(source,1),source,config,front(config),base,List.of(wrong),ignored->{throw new AssertionError();});
        assertEquals("WAITING_INPUT",result.pendingStatus());assertTrue(result.message().contains("固定上传素材不一致"),result.message());
        verifyNoInteractions(db,archive);
    }

    @Test void templateReviewBindsUnselectedSlotsToTheirPreparedMasterInsteadOfTheOriginalUpload()throws Exception {
        var config=RepeatedSurfaceArtworkFixture.configuration();var source=selection(List.of(color("black","black")));String kept=key('6');
        String original=CommerceSurfaceTemplateInputs.key("org",RepeatedSurfaceArtworkFixture.BADGE),uv=CommerceSurfaceTemplateInputs.key("org",config.surfaceUvViews().get("FRONT"));
        when(archive.read("org",uv)).thenReturn(SurfaceTemplateFixture.reference(RepeatedSurfaceArtworkFixture.assets().get(config.surfaceUvViews().get("FRONT"))));
        var productJob=job("MOCKUP_FRONT",1,"black",product);productJob.put("reference_keys",json.writeValueAsString(List.of(base,matte,original,kept)));
        var jobs=new ArrayList<>(List.of(job("MATTE",1,null,matte),job("BASE_COLOR",0,"black",base),productJob));
        var rawReport=report("black");rawReport.put("artworkKeys",Map.of("front-checker",matte,"front-repeat",matte,"fixed-badge",original));stubReports(rawReport);
        var missing=workflow.review(run,templatePlan(source,1),config,jobs);assertNotNull(missing);assertTrue(missing.message().contains("不能直接套用原上传图"));
        jobs.add(preservedBadge(kept));var wrong=workflow.review(run,templatePlan(source,1),config,jobs);assertNotNull(wrong);assertTrue(wrong.message().contains("图案互换"));
        var correct=report("black");correct.put("artworkKeys",Map.of("front-checker",matte,"front-repeat",matte,"fixed-badge",kept));stubReports(correct);
        assertNull(workflow.review(run,templatePlan(source,1),config,jobs));
        verify(archive,times(1)).read("org",product);
        verify(db,times(1)).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_checks"),eq(productJob.get("id")),anyString());
    }
}
