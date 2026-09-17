package com.yingdao.aicommerce.product.application;

import java.util.*;
import static com.yingdao.aicommerce.product.application.CommercePrintTemplateService.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;

final class SavedArtworkFixture {
 static final String GROUP_A="2".repeat(64),GROUP_B="5".repeat(64);
 static Configuration configuration(){
  var images=SurfaceTemplateFixture.sides("b".repeat(64));
  var prints=List.of(new PhotoPrint("front",GROUP_A,"FRONT",.25,.25,.4,.4,0),new PhotoPrint("back",GROUP_A,"BACK",.25,.25,.4,.4,0),new PhotoPrint("left",GROUP_B,"LEFT",.25,.25,.4,.4,0));
  var uv=Map.of("FRONT","c".repeat(64),"BACK","d".repeat(64),"LEFT","e".repeat(64),"RIGHT","f".repeat(64));
  var c=new Configuration("T_SHIRT",List.of(),images,null,"a".repeat(64),"reference",List.of(),images,images,uv,images,List.of("front","back","left"),null,prints);
  return new Configuration(c.product(),c.areas(),images,null,c.sourceImageId(),c.referenceSetId(),List.of(),images,images,uv,images,c.renderPrintIds(),renderFingerprint(c),prints);
 }
 static ReusedDesign selection(String a,String b){return new ReusedDesign(Map.of(GROUP_A,a,GROUP_B,b));}
 static FrozenArtwork frozen(String id,byte[] png){var sha=CommerceImageArchive.sha(png);return new FrozenArtwork(id,CommerceSurfaceTemplateInputs.key("org",sha),sha);}
 static Plan plan(FrozenArtwork a,FrozenArtwork b){
  var source=new Selection("capture",1,"image",List.of(),List.of(new CommerceSourceColorResolver.Color("black","Black",List.of("sku"),"image",true)));
  return new Plan("fixed-snapshot",List.of(source),1,null,"SAVED_ARTWORK","SOURCE_SKU_COLORS","LOCAL_PHOTO_V1",List.of(new FrozenDesign(Map.of(GROUP_A,a,GROUP_B,b))));
 }
 static byte[] image(int color,boolean transparent)throws Exception{
  int[][] p=new int[64][64];for(int y=0;y<64;y++)for(int x=0;x<64;x++)p[y][x]=transparent&&(x<8||y<8||x>=56||y>=56)?0:color;
  return CommerceSurfaceMockupComposerTest.png(p);
 }
 static byte[] uv(int slot)throws Exception{
  int[][] p=new int[64][64];if(slot>0)for(int y=16;y<40;y++)for(int x=16;x<40;x++)p[y][x]=0xff000000|((64+(x-16)*127/23)<<16)|((64+(y-16)*127/23)<<8)|slot;
  return CommerceSurfaceMockupComposerTest.png(p);
 }
}
