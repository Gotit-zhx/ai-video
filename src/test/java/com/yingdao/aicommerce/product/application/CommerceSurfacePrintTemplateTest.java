package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.product.application.CommercePrintTemplateService.*;

class CommerceSurfacePrintTemplateTest {
 @TempDir Path temp;
 final PlatformPrincipal user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:edit","product:view"));
 byte[] png(int size,int color)throws Exception{var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<size;y++)for(int x=0;x<size;x++)image.setRGB(x,y,color);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
 Configuration config(String image){var bases=Map.of("FRONT",image,"BACK",image,"LEFT",image,"RIGHT",image);
  var mesh=new Reconstruction(1,"garment-coarse-v1",bases,List.of(List.of(0.,0.,0.),List.of(1.,0.,0.),List.of(0.,1.,0.)),List.of(List.of(0,1,2)),Map.of());
  var print=new SurfacePrint("p1",image,List.of(.2,.2,0.),List.of(0.,0.,1.),List.of(1.,0.,0.),.2,.3,.1,"outer");
  return new Configuration("T_SHIRT",List.of(),bases,mesh,image,null,List.of(print),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),null);
 }
 Configuration rendered(Configuration c,RenderAssets r){return new Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),c.referenceSetId(),c.surfacePrints(),r.renderedViews(),r.blankRenderedViews(),r.surfaceUvViews(),r.placementMasks(),r.renderPrintIds(),r.renderBinding());}
 Map<String,byte[]> files()throws Exception{var files=new HashMap<String,byte[]>();for(String side:List.of("FRONT","BACK","LEFT","RIGHT")){files.put("image_"+side,png(256,0xff123456));files.put("blank_"+side,png(256,0xffaaaaaa));files.put("mask_"+side,png(256,0xffffffff));files.put("uv_"+side,png(256,0xff808001));}return files;}
 @Test void archiveSaveReloadBindsActualArtworkCoordinatesAndAllFourViews()throws Exception{
  var mapper=mock(CommerceTemplateMapper.class);var service=new CommercePrintTemplateService(mapper,new ObjectMapper(),new CommerceImageArchive(temp.toString()));
  var hash=service.upload(png(64,0xffaabbcc),user).sha256();var input=config(hash);
  assertThrows(BusinessException.class,()->requireSurfaceRenders(input));
  var assets=service.archiveRenders(input,files(),user);var ready=rendered(input,assets);assertDoesNotThrow(()->requireSurfaceRenders(ready));
  var view=service.save(new Request(null,0,"任意位置印花",ready),user);assertEquals(ready,view.configuration());assertFalse(view.productionReady());
  var capture=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.domain.CommerceTemplateVersion.class);verify(mapper).insert(capture.capture());when(mapper.listKind("org","PRINT_LAYOUT")).thenReturn(List.of(capture.getValue()));
  var reread=service.list(user).getFirst().configuration();assertEquals(ready,reread);assertDoesNotThrow(()->requireSurfaceRenders(reread));
  assertEquals("image/png",service.image(assets.surfaceUvViews().get("LEFT"),user).mimeType());
  var json=new ObjectMapper();var tree=json.readTree(json.writeValueAsString(ready));((tools.jackson.databind.node.ObjectNode)tree.path("surfacePrints").get(0)).put("rotation",.6);
  assertThrows(BusinessException.class,()->requireSurfaceRenders(json.treeToValue(tree,Configuration.class)));
 }
 @Test void mysqlDoubleRoundingDoesNotInvalidateRendersButMovingPrintStillDoes()throws Exception{
  var service=new CommercePrintTemplateService(mock(CommerceTemplateMapper.class),new ObjectMapper(),new CommerceImageArchive(temp.toString()));
  var c=config(service.upload(png(64,0xff888888),user).sha256());var mapper=new ObjectMapper();
  var tree=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(c);
  ((tools.jackson.databind.node.ArrayNode)tree.path("surfacePrints").get(0).path("position")).set(0,mapper.valueToTree(0.09548893141911563));
  c=mapper.treeToValue(tree,Configuration.class);var assets=service.archiveRenders(c,files(),user);var ready=rendered(c,assets);
  assertTrue(ready.renderBinding().startsWith("v2:"));
  tree=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(ready);
  var position=(tools.jackson.databind.node.ArrayNode)tree.path("surfacePrints").get(0).path("position");
  position.set(0,mapper.valueToTree(0.09548893141911564));
  var roundtrip=mapper.treeToValue(tree,Configuration.class);assertDoesNotThrow(()->requireSurfaceRenders(roundtrip));
  position.set(0,mapper.valueToTree(0.09648893141911564));
  var moved=mapper.treeToValue(tree,Configuration.class);assertThrows(BusinessException.class,()->requireSurfaceRenders(moved));
 }
 @Test void swappedGuideHashAndReorderedPrintsInvalidateBinding()throws Exception{
  var service=new CommercePrintTemplateService(mock(CommerceTemplateMapper.class),new ObjectMapper(),new CommerceImageArchive(temp.toString()));var c=config(service.upload(png(64,0xff888888),user).sha256());var r=service.archiveRenders(c,files(),user);
  var changed=new RenderAssets(r.blankRenderedViews(),r.blankRenderedViews(),r.surfaceUvViews(),r.placementMasks(),r.renderPrintIds(),r.renderBinding());assertThrows(BusinessException.class,()->requireSurfaceRenders(rendered(c,changed)));
  var order=new RenderAssets(r.renderedViews(),r.blankRenderedViews(),r.surfaceUvViews(),r.placementMasks(),List.of("other"),r.renderBinding());assertThrows(BusinessException.class,()->requireSurfaceRenders(rendered(c,order)));
 }
 @Test void wrongProjectionSizeOrSlotRejectedBeforeSavingTemplate()throws Exception{
  var service=new CommercePrintTemplateService(mock(CommerceTemplateMapper.class),new ObjectMapper(),new CommerceImageArchive(temp.toString()));var c=config(service.upload(png(64,0xff888888),user).sha256());var files=files();files.put("uv_LEFT",png(256,0xff808020));assertThrows(BusinessException.class,()->service.archiveRenders(c,files,user));
  files.put("uv_LEFT",png(512,0xff808001));assertThrows(BusinessException.class,()->service.archiveRenders(c,files,user));
 }
 @Test void invalidAnchorNormalsAndUnarchivedArtworkAreRejected()throws Exception{
  var service=new CommercePrintTemplateService(mock(CommerceTemplateMapper.class),new ObjectMapper(),new CommerceImageArchive(temp.toString()));var c=config("a".repeat(64));assertThrows(IllegalStateException.class,()->service.archiveRenders(c,files(),user));
  var p=c.surfacePrints().getFirst();var invalid=new SurfacePrint(p.id(),p.artworkId(),p.position(),List.of(0.,0.,2.),p.tangent(),p.width(),p.height(),p.rotation(),p.meshPart());
  assertThrows(BusinessException.class,()->validate(new Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),null,List.of(invalid),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),null)));
 }
 @Test void verifiesActualBrowserExportsWhenRequested()throws Exception{
  var directory=System.getProperty("surface.contract");org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null);
  var out=Path.of(directory);var json=new ObjectMapper();var c=json.treeToValue(json.readTree(java.nio.file.Files.readString(out.resolve("saved-configuration.json"))).path("configuration"),Configuration.class);
  var refs=mock(CommercePrintReferenceService.class);when(refs.readyReferenceSet("org",c.referenceSetId())).thenReturn(new CommercePrintReferenceService.View(c.referenceSetId(),"READY","READY",c.referenceSetId(),c.sourceImageId(),c.baseImages(),"fixture",false,null,false,"fixture"));
  var mapper=mock(CommerceTemplateMapper.class);var service=new CommercePrintTemplateService(mapper,json,new CommerceImageArchive(temp.toString()),refs);
  for(var file:List.of(Path.of("output/garment-kuose-20260911/source.png"),out.resolve("test-artwork.png")))service.upload(java.nio.file.Files.readAllBytes(file),user);
  for(String side:List.of("front","back","left","right"))service.upload(java.nio.file.Files.readAllBytes(Path.of("output/garment-kuose-20260911/v4/"+side+".png")),user);
  var files=new HashMap<String,byte[]>();for(String side:List.of("FRONT","BACK","LEFT","RIGHT"))for(String kind:List.of("image","blank","uv","mask")){
   var bytes=java.nio.file.Files.readAllBytes(out.resolve("export-"+side+"-"+kind+".png"));service.upload(bytes,user);files.put(kind+"_"+side,bytes);
  }
  var ready=rendered(c,service.archiveRenders(c,files,user));var view=service.save(new Request(null,0,"真实浏览器契约验证",ready),user);
  assertEquals(c.surfacePrints(),view.configuration().surfacePrints());requireSurfaceRenders(view.configuration());
  java.nio.file.Files.writeString(out.resolve("browser-java-contract.json"),json.writeValueAsString(Map.of("passed",true,"vertices",c.reconstruction().vertices().size(),"prints",c.surfacePrints().size(),"binding",view.configuration().renderBinding(),"productionReady",view.productionReady())));
 }

 @Test void optionalTopBottomGuidesMustBeCompleteAndBound()throws Exception{
  var service=new CommercePrintTemplateService(mock(CommerceTemplateMapper.class),new ObjectMapper(),new CommerceImageArchive(temp.toString()));var c=config(service.upload(png(64,0xff888888),user).sha256());var all=files();
  for(String side:List.of("TOP","BOTTOM"))for(String kind:List.of("image","blank","mask","uv"))all.put(kind+"_"+side,all.get(kind+"_FRONT"));
  var assets=service.archiveRenders(c,all,user);assertEquals(6,assets.surfaceUvViews().size());assertDoesNotThrow(()->requireSurfaceRenders(rendered(c,assets)));
  all.remove("uv_TOP");assertThrows(BusinessException.class,()->service.archiveRenders(c,all,user));
 }

}
