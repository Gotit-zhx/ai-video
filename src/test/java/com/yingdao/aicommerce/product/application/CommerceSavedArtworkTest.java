package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;

class CommerceSavedArtworkTest {
 @Test void allGroupsRequiredAndDuplicateDesignsAndLegacyAreaTemplatesRejected(){
  var c=SavedArtworkFixture.configuration();var design=SavedArtworkFixture.selection("art-a","art-b");
  assertDoesNotThrow(()->validateReusedDesigns(List.of(design),c));
  assertThrows(IllegalArgumentException.class,()->validateReusedDesigns(List.of(new ReusedDesign(Map.of(SavedArtworkFixture.GROUP_A,"art"))),c));
  assertThrows(IllegalArgumentException.class,()->validateReusedDesigns(List.of(design,design),c));
  assertThrows(IllegalArgumentException.class,()->validateReusedDesigns(List.of(design),SurfaceTemplateFixture.configuration()));
 }
 @Test void frozenMasterCannotCrossTenantOrLoseOriginalShaAndCount()throws Exception{
  var art=SavedArtworkFixture.frozen("saved",SavedArtworkFixture.image(0xffff0000,true));var plan=SavedArtworkFixture.plan(art,art);var c=SavedArtworkFixture.configuration();
  assertTrue(groupedArtwork(plan));assertFalse(sourceProductStyle(plan));assertDoesNotThrow(()->validateFrozenArtworks(plan,c,"org"));
  assertThrows(IllegalArgumentException.class,()->validateFrozenArtworks(plan,c,"foreign"));
  var wrong=new Plan(plan.printTemplateSnapshotId(),plan.sources(),2,null,"SAVED_ARTWORK",plan.outputPolicy(),plan.renderPolicy(),plan.reusedArtworks());
  assertThrows(IllegalArgumentException.class,()->validateFrozenArtworks(wrong,c,"org"));
 }
 @Test void distinctLibraryIdsWithSameContentCannotCreateDuplicateDesigns()throws Exception{
  var a=SavedArtworkFixture.frozen("first",SavedArtworkFixture.image(0xffff0000,true));var same=SavedArtworkFixture.frozen("second",SavedArtworkFixture.image(0xffff0000,true));var prior=SavedArtworkFixture.plan(a,a);
  var plan=new Plan(prior.printTemplateSnapshotId(),prior.sources(),2,null,"SAVED_ARTWORK",prior.outputPolicy(),prior.renderPolicy(),List.of(new FrozenDesign(Map.of(SavedArtworkFixture.GROUP_A,a,SavedArtworkFixture.GROUP_B,a)),new FrozenDesign(Map.of(SavedArtworkFixture.GROUP_A,same,SavedArtworkFixture.GROUP_B,same))));
  assertTrue(assertThrows(IllegalArgumentException.class,()->validateFrozenArtworks(plan,SavedArtworkFixture.configuration(),"org")).getMessage().contains("内容重复"));
  assertDoesNotThrow(()->validateFrozenArtworks(prior,SavedArtworkFixture.configuration(),"org"),"Same print across two markers in one design remains allowed");
 }
 @Test void launchBudgetsZeroCallsAfterAllPhotoViewChecks()throws Exception{
  var h=new Harness();var launch=new CommerceBatchLaunchService(mock(CommerceBatchService.class),mock(CommerceBatchCreativePlanService.class),mock(CommerceBatchExecutionService.class),h.archive);
  assertEquals(0,launch.requiredModelCalls(h.plan,h.config,"org"));
 }
 @Test void savedArtworkExtractAndVariateAreLocalIdempotentReferencesWithNoProviderCalls()throws Exception{
  var h=new Harness();h.addBase();
  assertEquals("QUEUED",h.execute("EXTRACT").pendingStatus());assertTrue(h.execute("EXTRACT").complete());
  assertEquals("QUEUED",h.execute("VARIATE").pendingStatus());assertEquals("QUEUED",h.execute("VARIATE").pendingStatus());assertTrue(h.execute("VARIATE").complete());
  assertTrue(h.execute("VARIATE").complete());
  assertEquals(3,h.jobs.stream().filter(j->"SAVED_ARTWORK".equals(j.get("model_config_id"))).count());
  assertEquals(h.a.storageKey(),h.jobs.stream().filter(j->"VARIATE_G1".equals(j.get("kind"))).findFirst().orElseThrow().get("storage_key"));
  assertEquals(h.b.storageKey(),h.jobs.stream().filter(j->"VARIATE_G2".equals(j.get("kind"))).findFirst().orElseThrow().get("storage_key"));
  verifyNoInteractions(h.models,h.gateway,h.ledger,h.sourceArchives);
 }
 @Test void changedSavedBytesStopBeforeAnyInsertOrPaidRetry()throws Exception{
  var h=new Harness();h.addBase();h.assets.put(h.a.storageKey(),SavedArtworkFixture.image(0xff00ff00,true));
  var result=h.execute("EXTRACT");assertFalse(result.complete());assertTrue(result.message().contains("指纹"));
  verify(h.db,never()).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_jobs"),any(Object[].class));verifyNoInteractions(h.models,h.gateway,h.ledger);
 }
 @Test void opaqueSavedMasterStopsWithoutProviderFallback()throws Exception{
  var h=new Harness();var opaque=SavedArtworkFixture.image(0xffff0000,false);h.a=SavedArtworkFixture.frozen("opaque",opaque);h.plan=SavedArtworkFixture.plan(h.a,h.b);h.assets.put(h.a.storageKey(),opaque);h.stubPlan();h.addBase();
  assertFalse(h.execute("EXTRACT").complete());verifyNoInteractions(h.models,h.gateway,h.ledger);
 }
 static final class Harness{
  final JdbcTemplate db=mock(JdbcTemplate.class);final CommerceImageArchive archive=mock(CommerceImageArchive.class);
  final CommerceBatchCreativePlanMapper plans=mock(CommerceBatchCreativePlanMapper.class);final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
  final ProductCaptureService captures=mock(ProductCaptureService.class);final ModelConfigService models=mock(ModelConfigService.class);
  final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);final CommerceBatchArtworkLedger ledger=mock(CommerceBatchArtworkLedger.class);
  final CommerceCaptureSourceArchiveService sourceArchives=mock(CommerceCaptureSourceArchiveService.class);final ObjectMapper json=new ObjectMapper();
  final PlatformPrincipal user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:edit","product:publish"));
  final CommerceBatchArtworkService service=new CommerceBatchArtworkService(db,plans,templates,captures,archive,mock(MarketplaceImageDownloader.class),models,gateway,ledger,mock(CommerceBatchExecutionService.class),json,sourceArchives);
  final CommercePrintTemplateService.Configuration config=SavedArtworkFixture.configuration();final List<Map<String,Object>> jobs=new ArrayList<>();final Map<String,byte[]> assets=new HashMap<>();
  FrozenArtwork a,b;Plan plan;
  Harness()throws Exception{
   byte[] red=SavedArtworkFixture.image(0xffff0000,true),blue=SavedArtworkFixture.image(0xff0000ff,true),blank=SavedArtworkFixture.image(0xffaaaaaa,false);
   a=SavedArtworkFixture.frozen("library-a",red);b=SavedArtworkFixture.frozen("library-b",blue);plan=SavedArtworkFixture.plan(a,b);assets.put(a.storageKey(),red);assets.put(b.storageKey(),blue);
   for(char c:new char[]{'a','b','2','5'})assets.put(CommerceSurfaceTemplateInputs.key("org",String.valueOf(c).repeat(64)),blank);
   for(int i=0;i<4;i++)assets.put(CommerceSurfaceTemplateInputs.key("org",String.valueOf((char)('c'+i)).repeat(64)),SavedArtworkFixture.uv(i==3?0:i+1));
   when(archive.read(eq("org"),anyString())).thenAnswer(call->{byte[] bytes=assets.get((String)call.getArgument(1));if(bytes==null)throw new IllegalArgumentException("missing");return SurfaceTemplateFixture.reference(bytes);});
   var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(1L);var image=mock(ProductCaptureImage.class);when(image.getId()).thenReturn("image");
   when(captures.get("capture",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(image),List.of(),Map.of(),Map.of()));
   when(templates.snapshot("org","fixed-snapshot")).thenReturn(new CommerceTemplateVersion("fixed-snapshot","org","template",1,"PRINT_LAYOUT","photo",json.writeValueAsString(config),"u",Instant.now()));stubPlan();
   when(db.queryForList(startsWith("SELECT id,capture_id,kind"),eq("run"),eq("org"))).thenAnswer(call->new ArrayList<>(jobs));
   doAnswer(call->{var row=new HashMap<String,Object>();row.put("id",call.getArgument(1));row.put("capture_id",call.getArgument(4));row.put("kind",call.getArgument(6));row.put("variant_index",call.getArgument(7));row.put("status","READY");row.put("model_config_id","SAVED_ARTWORK");row.put("reference_keys",call.getArgument(11));row.put("storage_key",call.getArgument(12));row.put("content_sha256",call.getArgument(13));jobs.add(row);return 1;})
     .when(db).update(startsWith("INSERT IGNORE INTO commerce_batch_artwork_jobs"),any(Object[].class));
  }
  void stubPlan(){when(plans.forExecution("org","run")).thenReturn(new CommerceBatchCreativePlanMapper.Row("plan","org","batch",1,json.writeValueAsString(plan),"u",Instant.now()));}
  void addBase(){jobs.add(new HashMap<>(Map.of("id","base","capture_id","capture","kind","BASE_COLOR","variant_index",0,"color_key","black","status","READY","storage_key",CommerceSurfaceTemplateInputs.key("org","b".repeat(64)))));}
  CommerceBatchStageHandler.Result execute(String stage){return service.execute(new CommerceBatchExecution("run","org","batch","u",stage,"RUNNING",null,"lease",Instant.now(),Instant.now(),Instant.now()),user);}
 }
}
