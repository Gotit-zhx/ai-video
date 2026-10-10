package com.yingdao.aicommerce.product.application;

import java.util.List;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Preserves native source dimensions and exact decoded pixels outside the explicit mask. */
public final class ProductWatermarkLocalEdit {
 private ProductWatermarkLocalEdit(){}
 /** Small inward/outward working margin for color blending; still subject to the 25% cap. */
 public static List<CommerceArtworkLocalEdit.Region> blendingRegions(List<CommerceArtworkLocalEdit.Region> regions){
  CommerceArtworkLocalEdit.validate(regions);
  var expanded=regions.stream().map(r->{
   double left=r.x()<=.03?0:Math.max(0,r.x()-.01),top=Math.max(0,r.y()-.01);
   double right=r.x()+r.width()>=.97-1e-9?1:Math.min(1,r.x()+r.width()+.01),bottom=Math.min(1,r.y()+r.height()+.01);
   return new CommerceArtworkLocalEdit.Region(left,top,right-left,bottom-top);
  }).toList();
  if(expanded.stream().mapToDouble(r->r.width()*r.height()).sum()>.25)throw new IllegalArgumentException("水印合成范围过大，需要复核");
  return expanded;
 }
 public static void verifyOutside(byte[] original,byte[] output,List<CommerceArtworkLocalEdit.Region> regions){
  CommerceArtworkLocalEdit.validate(regions);
  if(regions.stream().mapToDouble(r->r.width()*r.height()).sum()>.25)throw new IllegalArgumentException("合成范围过大");
  var a=CommerceMockupComposer.decode(original);var b=CommerceMockupComposer.decode(output);
  int w=a.getWidth(),h=a.getHeight();if(w!=b.getWidth()||h!=b.getHeight())throw new IllegalStateException("合成改变了原图尺寸");
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   boolean inside=false;
   for(var r:regions)if(x>=Math.floor(r.x()*w)&&x<Math.ceil((r.x()+r.width())*w)&&y>=Math.floor(r.y()*h)&&y<Math.ceil((r.y()+r.height())*h)){inside=true;break;}
   if(!inside&&a.getRGB(x,y)!=b.getRGB(x,y))throw new IllegalStateException("合成改变了处理范围外的像素");
  }
 }
 public static byte[] preserve(byte[] original,byte[] candidate,List<CommerceArtworkLocalEdit.Region> regions){
  CommerceArtworkLocalEdit.validate(regions);
  double area=regions.stream().mapToDouble(r->r.width()*r.height()).sum();
  if(area>.25)throw new IllegalArgumentException("水印范围过大，请复核商品结构");
  var base=CommerceMockupComposer.decode(original);var generated=CommerceMockupComposer.decode(candidate);
  if(generated.getWidth()!=generated.getHeight())throw new IllegalArgumentException("图片编辑改变了参考画布比例，禁止自动映射");
  int w=base.getWidth(),h=base.getHeight();
  // The image model receives a square, letterboxed reference. Invert that mapping only inside masks.
  double scale=Math.min(generated.getWidth()/(double)w,generated.getHeight()/(double)h);
  double ox=(generated.getWidth()-w*scale)/2,oy=(generated.getHeight()-h*scale)/2;
  var mapped=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   int sx=Math.max(0,Math.min(generated.getWidth()-1,(int)Math.floor(ox+(x+.5)*scale)));
   int sy=Math.max(0,Math.min(generated.getHeight()-1,(int)Math.floor(oy+(y+.5)*scale)));
   mapped.setRGB(x,y,generated.getRGB(sx,sy));
  }
  var corrections=regions.stream().map(r->new WatermarkBoundaryCorrection(base,mapped,r)).toList();
  var result=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   int source=base.getRGB(x,y);double alpha=0;WatermarkBoundaryCorrection selected=null;
   for(var correction:corrections)if(correction.contains(x,y)&&correction.feather(x,y)>alpha){alpha=correction.feather(x,y);selected=correction;}
   if(selected==null){result.setRGB(x,y,source);continue;}
   int edit=mapped.getRGB(x,y),rgb=source&0xff000000;
   for(int shift:new int[]{0,8,16}){
    double corrected=Math.max(0,Math.min(255,((edit>>shift)&255)+selected.at(x,y,shift/8)));
    rgb|=((int)Math.round(((source>>shift)&255)*(1-alpha)+corrected*alpha))<<shift;
   }
   result.setRGB(x,y,rgb);
  }
  try(var out=new ByteArrayOutputStream()){ImageIO.write(result,"png",out);return out.toByteArray();}catch(Exception e){throw new IllegalStateException("去水印图片保存失败",e);}
 }
}
