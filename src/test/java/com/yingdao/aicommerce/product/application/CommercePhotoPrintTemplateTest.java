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

class CommercePhotoPrintTemplateTest {
 @TempDir Path temp;
 final PlatformPrincipal user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:edit","product:view"));
 final ObjectMapper json=new ObjectMapper();
 byte[] png(int size,int color)throws Exception{var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<size;y++)for(int x=0;x<size;x++)image.setRGB(x,y,color);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
 Configuration config(String hash,List<PhotoPrint> prints){return new Configuration("T_SHIRT",List.of(),Map.of("FRONT",hash,"BACK",hash,"LEFT",hash,"RIGHT",hash),null,hash,"photo-reference",List.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),null,prints);}
 Configuration rendered(Configuration c,RenderAssets r){return new Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),c.referenceSetId(),c.surfacePrints(),r.renderedViews(),r.blankRenderedViews(),r.surfaceUvViews(),r.placementMasks(),r.renderPrintIds(),r.renderBinding(),c.photoPrints());}
 PhotoPrint print(String id,String hash,String side){return new PhotoPrint(id,hash,side,.2,.3,.2,.25,0);}
 Map<String,byte[]> files(Configuration c)throws Exception{
  var files=new HashMap<String,byte[]>();
  for(String side:List.of("FRONT","BACK","LEFT","RIGHT")){
   files.put("image_"+side,png(256,0xff123456));files.put("blank_"+side,png(256,0xffaaaaaa));files.put("mask_"+side,png(256,0xffffffff));
   int slot=0;for(int i=0;i<c.photoPrints().size();i++)if(side.equals(c.photoPrints().get(i).side()))slot=i+1;
   files.put("uv_"+side,png(256,slot==0?0:0xff808000|slot));
  }
  return files;
 }
 CommercePrintTemplateService service(CommerceTemplateMapper mapper,CommerceImageArchive archive){
  var refs=mock(CommercePrintReferenceService.class);
  when(refs.readyReferenceSet(eq("org"),eq("photo-reference"))).thenAnswer(invocation->{
   var hash=archive.store("org",png(256,0xffaaaaaa)).sha256();
   return new CommercePrintReferenceService.View("photo-reference","READY","READY","photo-reference",hash,Map.of("FRONT",hash,"BACK",hash,"LEFT",hash,"RIGHT",hash),"fixture",false,null,false,"fixture");
  });
  return new CommercePrintTemplateService(mapper,json,archive,refs);
 }
 @Test void savesPhotoOnlyPlacementsAndReusesGroupAcrossFacesWithoutMesh()throws Exception{
  var mapper=mock(CommerceTemplateMapper.class);var archive=new CommerceImageArchive(temp.toString());var service=service(mapper,archive);
  var hash=service.upload(png(256,0xffaaaaaa),user).sha256();
  var c=config(hash,List.of(print("front-marker",hash,"FRONT"),print("back-marker",hash,"BACK")));
  assertTrue(photoLayout(c));assertNull(c.reconstruction());assertTrue(c.surfacePrints().isEmpty());
  var ready=rendered(c,service.archiveRenders(c,files(c),user));assertTrue(ready.renderBinding().startsWith("v3:"));
  var saved=service.save(new Request(null,0,"照片四面",ready),user);assertEquals(ready,saved.configuration());
  var roundtrip=json.readValue(json.writeValueAsString(ready),Configuration.class);requireSurfaceRenders(roundtrip);
  assertEquals(List.of(hash),CommerceSurfaceTemplateInputs.artworkGroups(roundtrip));
  var loaded=CommerceSurfaceTemplateInputs.load(roundtrip,archive,"org");assertEquals(Set.of("FRONT","BACK"),loaded.keySet());
  assertEquals(Set.of("back-marker"),loaded.get("BACK").visiblePrintIds());
  byte[] variation={1,2,3};assertArrayEquals(variation,CommerceSurfaceTemplateInputs.replacements(roundtrip,loaded.get("BACK"),Map.of(hash,variation)).get("back-marker"));
 }
 @Test void allPhotoGeometryAndArtworkChangesInvalidateRenderBinding()throws Exception{
  var archive=new CommerceImageArchive(temp.toString());var service=service(mock(CommerceTemplateMapper.class),archive);var hash=service.upload(png(256,0xffaaaaaa),user).sha256();
  var c=config(hash,List.of(print("p",hash,"FRONT")));var ready=rendered(c,service.archiveRenders(c,files(c),user));
  for(String field:List.of("x","y","width","height","rotation","artworkId","side")){
   var tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(ready);var p=(tools.jackson.databind.node.ObjectNode)tree.path("photoPrints").get(0);
   if(field.equals("side"))p.put(field,"LEFT");else if(field.equals("artworkId"))p.put(field,"b".repeat(64));else p.put(field,.4);
   assertThrows(BusinessException.class,()->requireSurfaceRenders(json.treeToValue(tree,Configuration.class)),field);
  }
 }
 @Test void rejectsInvalidCoordinatesMixedModesAndMissingReferenceBindings(){
  String hash="a".repeat(64);var c=config(hash,List.of(print("p",hash,"FRONT")));assertDoesNotThrow(()->validate(c));
  var tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(c);((tools.jackson.databind.node.ObjectNode)tree.path("photoPrints").get(0)).put("x",.95);
  var outside=json.treeToValue(tree,Configuration.class);assertThrows(BusinessException.class,()->validate(outside));
  tree=(tools.jackson.databind.node.ObjectNode)json.valueToTree(c);tree.putNull("referenceSetId");var missing=json.treeToValue(tree,Configuration.class);assertThrows(BusinessException.class,()->validate(missing));
  var mixed=new Configuration(c.product(),List.of(new Area("area","FRONT",.1,.1,.1,.1,100,100,300)),c.baseImages(),null,c.sourceImageId(),c.referenceSetId(),List.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),null,c.photoPrints());
  assertThrows(BusinessException.class,()->validate(mixed));
 }
 @Test void rejectsCrossFaceUvsAlteredBlanksAndInvisiblePrints()throws Exception{
  var archive=new CommerceImageArchive(temp.toString());var service=service(mock(CommerceTemplateMapper.class),archive);var hash=service.upload(png(256,0xffaaaaaa),user).sha256();var c=config(hash,List.of(print("p",hash,"BACK")));
  var wrongFace=files(c);wrongFace.put("uv_FRONT",png(256,0xff808001));assertThrows(BusinessException.class,()->service.archiveRenders(c,wrongFace,user));
  var changed=files(c);changed.put("blank_BACK",png(256,0xffababab));assertThrows(BusinessException.class,()->service.archiveRenders(c,changed,user));
  var invisible=files(c);invisible.put("uv_BACK",png(256,0));assertThrows(BusinessException.class,()->service.archiveRenders(c,invisible,user));
 }
 @Test void backOnlyPlacementKeepsBlankFrontPrimaryAndIndependentBack()throws Exception{
  var archive=new CommerceImageArchive(temp.toString());var service=service(mock(CommerceTemplateMapper.class),archive);var hash=service.upload(png(256,0xffaaaaaa),user).sha256();var c=config(hash,List.of(print("back",hash,"BACK")));
  var ready=rendered(c,service.archiveRenders(c,files(c),user));
  assertTrue(CommerceSurfaceTemplateInputs.loadPrimary(ready,archive,"org").visiblePrintIds().isEmpty());
  assertEquals(Set.of("BACK"),CommerceSurfaceTemplateInputs.load(ready,archive,"org").keySet());
 }
 @Test void oldConfigurationJsonDefaultsToLegacyMode(){
  var c=json.readValue("{\"product\":\"T_SHIRT\",\"areas\":[{\"id\":\"p\",\"side\":\"FRONT\",\"x\":0.1,\"y\":0.1,\"width\":0.2,\"height\":0.2,\"printWidthMm\":100,\"printHeightMm\":100,\"dpi\":300}]}",Configuration.class);
  assertFalse(photoLayout(c));assertTrue(c.photoPrints().isEmpty());assertDoesNotThrow(()->validate(c));
 }
}
