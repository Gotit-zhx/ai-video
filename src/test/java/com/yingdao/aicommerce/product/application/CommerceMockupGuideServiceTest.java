package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommerceMockupGuideServiceTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);final CommerceSampleService samples=mock(CommerceSampleService.class);final CommercePrintTemplateService bases=mock(CommercePrintTemplateService.class);final CommerceImageArchive archive=mock(CommerceImageArchive.class);final ObjectMapper json=new ObjectMapper();
 final CommerceMockupGuideService service=new CommerceMockupGuideService(db,templates,samples,bases,archive,json);
 final PlatformPrincipal user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:edit"));
 void template(Map<String,String> images){var area=new CommercePrintTemplateService.Area("front","FRONT",.3,.3,.3,.3,200,200,300);when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"PRINT_LAYOUT","test",json.writeValueAsString(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area),images)),"u",Instant.now()));}
 @Test void missingBaseStopsBeforeReadingPrintOrWriting(){template(Map.of());assertThrows(BusinessException.class,()->service.create(new CommerceMockupGuideService.Request("snapshot","FRONT",Map.of("front","print")),"request-123",user));verifyNoInteractions(samples,bases,archive);}
 @Test void mismatchedSideCannotMixFrontPrintIntoBack(){template(Map.of("BACK","a".repeat(64)));assertThrows(BusinessException.class,()->service.create(new CommerceMockupGuideService.Request("snapshot","BACK",Map.of("front","print")),"request-123",user));verifyNoInteractions(samples,bases,archive);}
 @Test void foreignOrMissingSnapshotFails(){assertThrows(BusinessException.class,()->service.create(new CommerceMockupGuideService.Request("foreign","FRONT",Map.of("front","print")),"request-123",user));verify(templates).snapshot("org","foreign");verifyNoInteractions(samples,bases,archive);}
 @Test void replayCannotChangeBindings(){when(db.queryForList("SELECT * FROM commerce_mockup_guides WHERE organization_id=? AND created_by=? AND request_key=?","org","u","request-123")).thenReturn(List.of(Map.of("template_snapshot_id","snapshot","side","FRONT","bindings_json","{}")));assertThrows(BusinessException.class,()->service.create(new CommerceMockupGuideService.Request("snapshot","FRONT",Map.of("front","print")),"request-123",user));verifyNoInteractions(templates,samples,bases,archive);}

 @Test void backSurfaceUsesUvAndBlankModelInsteadOfLegacyFrontRectangle() throws Exception {
  assertSurfaceGuide("BACK");
 }
 @Test void topSurfaceGuideDoesNotRequireTopGeneratedPhoto() throws Exception {
  assertSurfaceGuide("TOP");
 }
 private void assertSurfaceGuide(String side) throws Exception {
  var config=SurfaceTemplateFixture.configuration();
  if(!side.equals("BACK")){
   var uv=new HashMap<>(SurfaceTemplateFixture.sides(SurfaceTemplateFixture.hash('f')));uv.put(side,SurfaceTemplateFixture.hash('e'));
   config=SurfaceTemplateFixture.withUvViews(config,uv);
  }
  when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"PRINT_LAYOUT","surface",json.writeValueAsString(config),"u",Instant.now()));
  byte[] blank=SurfaceTemplateFixture.blank(),visible=SurfaceTemplateFixture.visibleUv(),hidden=SurfaceTemplateFixture.hiddenUv();
  when(archive.read(eq("org"),anyString())).thenAnswer(call->{String path=call.getArgument(1);return SurfaceTemplateFixture.reference(path.endsWith(SurfaceTemplateFixture.hash('e'))?visible:path.endsWith(SurfaceTemplateFixture.hash('f'))?hidden:blank);});
  when(samples.printImage("variant",user)).thenReturn(SurfaceTemplateFixture.reference(CommerceSurfaceMockupComposerTest.png(new int[][]{{0xff00ff00}})));
  when(archive.store(eq("org"),any())).thenReturn(new CommerceImageArchive.Archived("stored",SurfaceTemplateFixture.hash('3'),"image/png",100));
  when(db.queryForMap(startsWith("SELECT * FROM commerce_mockup_guides"),eq("org"),eq("u"),eq("surface-request")))
    .thenReturn(Map.of("id","guide","template_snapshot_id","snapshot","side",side,"bindings_json","{\"back-slot\":\"variant\"}"));
  var result=service.create(new CommerceMockupGuideService.Request("snapshot",side,Map.of("back-slot","variant")),"surface-request",user);
  assertEquals(side,result.side());
  var output=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(archive).store(eq("org"),output.capture());
  assertEquals(0xff00ff00,CommerceMockupComposer.decode(output.getValue()).getRGB(0,0));
  assertEquals(0xff444444,CommerceMockupComposer.decode(output.getValue()).getRGB(1,1));
  verifyNoInteractions(bases);
 }
}
