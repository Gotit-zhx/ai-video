package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;

/** Narrow diagnostic for colored artwork on neutral bases; never authorizes publishing. */
public final class CommerceColoredPrintLocator {
 public record Bounds(int x,int y,int width,int height,int pixels){}
 public record Result(String status,Bounds reference,Bounds candidate,Double shiftXPixels,Double shiftYPixels,Double widthRatio,Double heightRatio,boolean productionReady,String limitation){}
 private static final String LIMIT="仅比较中性底图上的显著彩色变化整体边界，不是通用印花分割。黑白印花、彩色底款、多图案分别定位及图案完整性不在此检查能力内；无偏差提示仍需质量复核。";
 private CommerceColoredPrintLocator(){}
 public static Result compare(byte[] baseBytes,byte[] guideBytes,byte[] candidateBytes){
  var base=CommerceMockupComposer.square(baseBytes);var guide=CommerceMockupComposer.square(guideBytes);var candidate=CommerceMockupComposer.square(candidateBytes);
  int coloredBase=0;for(int y=0;y<1024;y++)for(int x=0;x<1024;x++)if(chroma(base.getRGB(x,y))>=.18)coloredBase++;
  if(coloredBase>104857)return unavailable("UNSUPPORTED_COLORED_BASE",null,null);
  var reference=bounds(base,guide);var result=bounds(base,candidate);
  if(reference==null||result==null)return unavailable("INSUFFICIENT_COLOR_SIGNAL",reference,result);
  double dx=result.x()+result.width()/2.0-reference.x()-reference.width()/2.0,dy=result.y()+result.height()/2.0-reference.y()-reference.height()/2.0;
  double wr=(double)result.width()/reference.width(),hr=(double)result.height()/reference.height();
  boolean drift=Math.abs(dx)>10||Math.abs(dy)>10||Math.abs(wr-1)>.05||Math.abs(hr-1)>.05;
  return new Result(drift?"COLOR_BOUNDS_DRIFT_REVIEW":"REVIEW_REQUIRED",reference,result,dx,dy,wr,hr,false,LIMIT+" 当前提示阈值为中心偏移10像素或尺寸变化5%，尚未跨商品类目校准。");
 }
 private static Result unavailable(String status,Bounds reference,Bounds candidate){return new Result(status,reference,candidate,null,null,null,null,false,LIMIT);}
 private static double chroma(int color){int r=(color>>16)&255,g=(color>>8)&255,b=color&255,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));return max==0?0:(double)(max-min)/max;}
 private static Bounds bounds(BufferedImage base,BufferedImage target){
  int[] xs=new int[1024],ys=new int[1024];int count=0;
  for(int y=0;y<1024;y++)for(int x=0;x<1024;x++){int a=base.getRGB(x,y),b=target.getRGB(x,y);int delta=Math.max(Math.abs((a&255)-(b&255)),Math.max(Math.abs(((a>>8)&255)-((b>>8)&255)),Math.abs(((a>>16)&255)-((b>>16)&255))));if(chroma(b)<.18||delta<40)continue;xs[x]++;ys[y]++;count++;}
  if(count<256)return null;
  int low=Math.max(1,(int)Math.ceil(count*.01)),high=(int)Math.ceil(count*.99);int x=quantile(xs,low),y=quantile(ys,low),right=quantile(xs,high),bottom=quantile(ys,high);
  if(right-x<8||bottom-y<8)return null;return new Bounds(x,y,right-x+1,bottom-y+1,count);
 }
 private static int quantile(int[] histogram,int target){int total=0;for(int i=0;i<histogram.length;i++){total+=histogram[i];if(total>=target)return i;}return histogram.length-1;}
}
