package com.yingdao.aicommerce.product.application;

import java.util.List;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Uses an explicit edit mask; never guesses garment boundaries from colors. Coordinates use the existing 1024 square canvas. */
public final class CommerceArtworkLocalEdit {
 public record Region(double x,double y,double width,double height) {}
 private CommerceArtworkLocalEdit() {}
 public static void validate(List<Region> regions) {
  if(regions==null||regions.isEmpty()||regions.size()>8)throw new IllegalArgumentException("请标记1至8个旧印花范围");
  for(var r:regions)if(r==null||!Double.isFinite(r.x())||!Double.isFinite(r.y())||!Double.isFinite(r.width())||!Double.isFinite(r.height())||r.x()<0||r.y()<0||r.width()<=0||r.height()<=0||r.x()+r.width()>1||r.y()+r.height()>1)throw new IllegalArgumentException("编辑范围须在图片画布内");
 }
 public static List<Region> placement(List<CommercePrintTemplateService.Area> areas) {
  CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",areas));
  return areas.stream().map(a->new Region(a.x(),a.y(),a.width(),a.height())).toList();
 }
 public static byte[] preserve(byte[] original,byte[] generated,List<Region> regions) {
  validate(regions);
  var base=CommerceMockupComposer.square(original);var edited=CommerceMockupComposer.square(generated);
  var corrections=regions.stream().map(r->new Correction(base,edited,r)).toList();
  // Feather inwards only. The outside is bit-for-bit identical to the normalized source.
  for(int y=0;y<1024;y++)for(int x=0;x<1024;x++) {
   double alpha=0;Correction selected=null;
   for(var correction:corrections){var r=correction.region;int left=(int)Math.floor(r.x()*1024),top=(int)Math.floor(r.y()*1024),right=(int)Math.ceil((r.x()+r.width())*1024),bottom=(int)Math.ceil((r.y()+r.height())*1024);
    if(x>=left&&x<right&&y>=top&&y<bottom){double weight=Math.min(1.0,Math.min(Math.min(x-left+1,right-x),Math.min(y-top+1,bottom-y))/4.0);if(weight>alpha){alpha=weight;selected=correction;}}
   }
   if(alpha==0)continue;
   int a=base.getRGB(x,y),b=edited.getRGB(x,y),rgb=0xff000000;
   for(int shift:new int[]{0,8,16}){double value=Math.max(0,Math.min(255,((b>>shift)&255)+selected.value(x,y,shift/8)));rgb|=((int)Math.round(((a>>shift)&255)*(1-alpha)+value*alpha))<<shift;}
   base.setRGB(x,y,rgb);
  }
  try(var out=new ByteArrayOutputStream()){ImageIO.write(base,"png",out);return out.toByteArray();}catch(java.io.IOException e){throw new IllegalStateException("局部编辑编码失败",e);}
 }
 /** Low-frequency harmonic boundary correction removes color seams without copying source print into the interior. */
 private static final class Correction {
  final Region region;final int left,top,right,bottom;final int n=49;final double[][] values=new double[3][n*n];
  Correction(java.awt.image.BufferedImage base,java.awt.image.BufferedImage edited,Region r){
   region=r;left=(int)Math.floor(r.x()*1024);top=(int)Math.floor(r.y()*1024);right=Math.min(1023,(int)Math.ceil((r.x()+r.width())*1024)-1);bottom=Math.min(1023,(int)Math.ceil((r.y()+r.height())*1024)-1);
   for(int c=0;c<3;c++){
    double sum=0;int count=0;
    for(int gy=0;gy<n;gy++)for(int gx=0;gx<n;gx++)if(gx==0||gy==0||gx==n-1||gy==n-1){int x=(int)Math.round(left+(right-left)*gx/(double)(n-1)),y=(int)Math.round(top+(bottom-top)*gy/(double)(n-1));double d=(((base.getRGB(x,y)>>(c*8))&255)-((edited.getRGB(x,y)>>(c*8))&255));values[c][gy*n+gx]=d;sum+=d;count++;}
    for(int gy=1;gy<n-1;gy++)for(int gx=1;gx<n-1;gx++)values[c][gy*n+gx]=sum/count;
    for(int pass=0;pass<600;pass++){double max=0;for(int gy=1;gy<n-1;gy++)for(int gx=1;gx<n-1;gx++){int i=gy*n+gx;double delta=((values[c][i-1]+values[c][i+1]+values[c][i-n]+values[c][i+n])/4-values[c][i])*1.7;values[c][i]+=delta;max=Math.max(max,Math.abs(delta));}if(max<.01)break;}
   }
  }
  double value(int x,int y,int c){double gx=(x-left)*(n-1)/(double)Math.max(1,right-left),gy=(y-top)*(n-1)/(double)Math.max(1,bottom-top);int ix=Math.min(n-2,(int)gx),iy=Math.min(n-2,(int)gy);double fx=gx-ix,fy=gy-iy;return values[c][iy*n+ix]*(1-fx)*(1-fy)+values[c][iy*n+ix+1]*fx*(1-fy)+values[c][(iy+1)*n+ix]*(1-fx)*fy+values[c][(iy+1)*n+ix+1]*fx*fy;}
 }
}
