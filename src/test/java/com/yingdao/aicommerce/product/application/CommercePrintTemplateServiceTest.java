package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommercePrintTemplateServiceTest {
 @Test void acceptsFourFaces(){for(String side:List.of("FRONT","BACK","LEFT","RIGHT")){var a=new CommercePrintTemplateService.Area("slot",side,.2,.2,.3,.3,200,200,300);assertDoesNotThrow(()->CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(a),Map.of(side,"a".repeat(64)))));}}
 @Test void legacyConfigurationDefaultsToOriginalProduct(){var c=new ObjectMapper().readValue("{\"product\":\"T_SHIRT\",\"areas\":[]}",CommercePrintTemplateService.Configuration.class);assertTrue(c.baseImages().isEmpty());}
 @Test void rejectsExternalUrlsAndUnknownBaseSide(){assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.3)),Map.of("FRONT","https://example.com/a.png"))));assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.3)),Map.of("TOP","a".repeat(64)))));}
 @Test void uploadsDecodedImageAndChecksTenantOnSave(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)throws Exception{
  var archive=new CommerceImageArchive(temp.toString());var mapper=mock(CommerceTemplateMapper.class);var service=new CommercePrintTemplateService(mapper,new ObjectMapper(),archive);
  var user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:edit","product:view"));
  var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
  var asset=service.upload(bytes.toByteArray(),user);assertEquals("image/png",service.image(asset.sha256(),user).mimeType());
  var result=service.save(new CommercePrintTemplateService.Request(null,0,"上传底图",new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.3)),Map.of("FRONT",asset.sha256()))),user);assertEquals(asset.sha256(),result.configuration().baseImages().get("FRONT"));
  var outsider=new PlatformPrincipal("u2","other","c","u","u","",true,List.of(),List.of("product:view"));assertThrows(IllegalStateException.class,()->service.image(asset.sha256(),outsider));
  assertThrows(BusinessException.class,()->service.upload("not an image".getBytes(),user));
 }
 @Test void commonProductTypesAreAccepted(){for(String product:List.of("T_SHIRT","LONG_SLEEVE","SWEATSHIRT","TANK","SHORTS","TROUSERS","CUSHION","PILLOWCASE","BLANKET","TOTE","CAP","MUG","PHONE_CASE"))assertDoesNotThrow(()->CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration(product,List.of(area(.3)))));}
 CommercePrintTemplateService.Area area(double x){return new CommercePrintTemplateService.Area("front","FRONT",x,.3,.3,.4,240,300,300);}
 @Test void validatesBoundsAndRejectsDuplicateSlots(){assertDoesNotThrow(()->CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.3)))));assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.9)))));assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.3),area(.3)))));}
 @Test void rejectsNonFiniteCoordinates(){assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(Double.NaN)))));}
 @Test void newVersionIsNotClaimedProductionReady(){var mapper=mock(CommerceTemplateMapper.class);var service=new CommercePrintTemplateService(mapper,new ObjectMapper());var user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:edit"));var result=service.save(new CommercePrintTemplateService.Request(null,0,"胸前主图",new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.3)))),user);assertEquals(1,result.version());assertFalse(result.productionReady());verify(mapper).insert(argThat(row->row.kind().equals("PRINT_LAYOUT")&&row.organizationId().equals("org")));}
 @Test void reconstructionSurvivesSaveAndReloadWithArchivedSources(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)throws Exception{
  var mapper=mock(CommerceTemplateMapper.class);var json=new ObjectMapper();var archive=new CommerceImageArchive(temp.toString());
  var service=new CommercePrintTemplateService(mapper,json,archive);
  var user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:edit","product:view"));
  var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
  var asset=service.upload(bytes.toByteArray(),user);var sources=Map.of("FRONT",asset.sha256());
  var mesh=reconstruction(sources);
  var result=service.save(new CommercePrintTemplateService.Request(null,0,"灰色粗模",configuration(sources,mesh)),user);
  var stored=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.CommerceTemplateVersion.class);verify(mapper).insert(stored.capture());
  when(mapper.listKind("org","PRINT_LAYOUT")).thenReturn(List.of(stored.getValue()));
  var restored=service.list(user).getFirst();
  assertEquals(mesh,restored.configuration().reconstruction());assertEquals(result.snapshotId(),restored.snapshotId());assertFalse(restored.productionReady());
  assertNull(json.readValue("{\"product\":\"T_SHIRT\",\"areas\":[]}",CommercePrintTemplateService.Configuration.class).reconstruction());
 }
 @Test void staleOrMissingModelSourcesAreRejected(){
  var sources=Map.of("FRONT","a".repeat(64));var mesh=reconstruction(sources);
  assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(Map.of("FRONT","b".repeat(64)),mesh)));
  assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(Map.of(),mesh)));
  assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(Map.of("FRONT","a".repeat(64),"BACK","b".repeat(64)),mesh)));
  assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(Map.of(),reconstruction(Map.of()))));
 }
 @Test void invalidMeshCoordinatesIndicesAndExcessivePayloadsAreRejected(){
  var sources=Map.of("FRONT","a".repeat(64));var mesh=reconstruction(sources);
  for(var vertices:List.of(List.of(List.of(Double.NaN,0.,0.),List.of(1.,0.,0.),List.of(0.,1.,0.)),
                           List.of(List.of(11.,0.,0.),List.of(1.,0.,0.),List.of(0.,1.,0.)),
                           Collections.nCopies(15001,List.of(0.,0.,0.)))){
   assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(sources,new CommercePrintTemplateService.Reconstruction(1,"garment-coarse-v1",sources,vertices,mesh.faces(),Map.of()))));
  }
  for(var faces:List.of(List.of(List.of(0,1,9)),List.of(List.of(0,0,1)),Collections.nCopies(30001,List.of(0,1,2)))){
   assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(sources,new CommercePrintTemplateService.Reconstruction(1,"garment-coarse-v1",sources,mesh.vertices(),faces,Map.of()))));
  }
  var collinear=List.of(List.of(0.,0.,0.),List.of(1.,0.,0.),List.of(2.,0.,0.));
  assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(sources,new CommercePrintTemplateService.Reconstruction(1,"garment-coarse-v1",sources,collinear,mesh.faces(),Map.of()))));
  assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(sources,new CommercePrintTemplateService.Reconstruction(2,"unrecognized",sources,mesh.vertices(),mesh.faces(),Map.of()))));
 }
 @Test void nonFiniteOrUnboundedQualityReportsAreRejected(){
  var sources=Map.of("FRONT","a".repeat(64));var mesh=reconstruction(sources);
  for(Map<String,Object> quality:List.<Map<String,Object>>of(Map.of("iou",Double.POSITIVE_INFINITY),Map.of("message","x".repeat(513)),Map.of("reports",Collections.nCopies(17,1)))){
   assertThrows(BusinessException.class,()->CommercePrintTemplateService.validate(configuration(sources,new CommercePrintTemplateService.Reconstruction(1,"garment-coarse-v1",sources,mesh.vertices(),mesh.faces(),quality))));
  }
 }
 private CommercePrintTemplateService.Configuration configuration(Map<String,String> sources,CommercePrintTemplateService.Reconstruction mesh){
  return new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.3)),sources,mesh);
 }
 private CommercePrintTemplateService.Reconstruction reconstruction(Map<String,String> sources){
  return new CommercePrintTemplateService.Reconstruction(1,"garment-coarse-v1",sources,
          List.of(List.of(0.,0.,0.),List.of(1.,0.,0.),List.of(0.,1.,0.)),List.of(List.of(0,1,2)),
          Map.of("views",Map.of("FRONT",Map.of("iou",0.96)),"limitations",List.of("单面深度为估计")));
 }
 @Test void snapshotReadsRequestedHistoricalVersionWithinCurrentTenant(){
  var mapper=mock(CommerceTemplateMapper.class);var json=new ObjectMapper();var service=new CommercePrintTemplateService(mapper,json);
  var user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:view"));
  var config=new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(area(.3)));
  var row=new com.yingdao.aicommerce.product.domain.CommerceTemplateVersion("historical","org","root",2,"PRINT_LAYOUT","v2",json.writeValueAsString(config),"u",java.time.Instant.now());
  when(mapper.snapshot("org","historical")).thenReturn(row);
  var loaded=service.snapshot("historical",user);
  assertEquals("historical",loaded.snapshotId());assertEquals(2,loaded.version());assertEquals(config,loaded.configuration());
  assertFalse(loaded.productionReady());verify(mapper).snapshot("org","historical");verifyNoMoreInteractions(mapper);
 }
 @Test void snapshotRejectsForeignMissingAndWorkflowVersionsWithoutFallback(){
  var mapper=mock(CommerceTemplateMapper.class);var service=new CommercePrintTemplateService(mapper,new ObjectMapper());
  var user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:view"));
  when(mapper.snapshot("org","workflow")).thenReturn(new com.yingdao.aicommerce.product.domain.CommerceTemplateVersion("workflow","org","root",1,"WORKFLOW","rules","{}","u",java.time.Instant.now()));
  for(String id:List.of("foreign","missing","workflow"))
   assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->service.snapshot(id,user)).getStatus());
  verify(mapper,never()).listKind(anyString(),anyString());verify(mapper,never()).latest(anyString(),anyString());
 }
 @Test void snapshotPermissionIsCheckedBeforeReadingTemplate(){
  var mapper=mock(CommerceTemplateMapper.class);var service=new CommercePrintTemplateService(mapper,new ObjectMapper());
  assertThrows(BusinessException.class,()->service.snapshot("print",null));verifyNoInteractions(mapper);
 }
}
