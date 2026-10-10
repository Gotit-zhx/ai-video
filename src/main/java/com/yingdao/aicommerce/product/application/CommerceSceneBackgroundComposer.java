package com.yingdao.aicommerce.product.application;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Reuses one scene without asking a model to redraw the garment or its placed artwork. */
final class CommerceSceneBackgroundComposer {
    static final String VERSION = "fixed-photo-scene-v1";
    record Report(String method, int width, int height, long foregroundPixels, long edgePixels,
                  long backgroundPixels, long protectedInteriorChangedPixels, String sceneSha256,
                  String geometrySha256, String limitation) {}
    record Result(byte[] png, Report report) {}
    private CommerceSceneBackgroundComposer() {}

    static Result compose(byte[] mockupPng, byte[] referencePhotoPng, byte[] scenePng) {
        var photo = CommerceMockupComposer.decode(mockupPng);
        var reference = CommerceMockupComposer.decode(referencePhotoPng);
        int w=photo.getWidth(), h=photo.getHeight();
        if(reference.getWidth()!=w || reference.getHeight()!=h)
            throw new IllegalArgumentException("场景合成的衣服照片与固定轮廓尺寸不一致，未移动印花位置");
        // Derive alpha from the original blank, never from a dark SKU or white print ink.
        var silhouette = CommerceMockupComposer.decode(CommercePhotoMockupComposer.recolorMask(referencePhotoPng));
        boolean[] mask=new boolean[w*h], interior=new boolean[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)mask[y*w+x]=(silhouette.getRGB(x,y)>>>24)>240;
        long detachedInk=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(!mask[y*w+x]) {
            int before=reference.getRGB(x,y),after=photo.getRGB(x,y),delta=0;
            for(int shift:new int[]{0,8,16})delta=Math.max(delta,Math.abs(channel(before,shift)-channel(after,shift)));
            if(delta<12)continue;
            boolean nearCloth=false;
            for(int dy=-2;dy<=2&&!nearCloth;dy++)for(int dx=-2;dx<=2;dx++) {
                int xx=x+dx,yy=y+dy;if(xx>=0&&yy>=0&&xx<w&&yy<h&&mask[yy*w+xx]){nearCloth=true;break;}
            }
            if(!nearCloth)detachedInk++;
        }
        if(detachedInk>16)throw new IllegalArgumentException("部分印花落在衣服轮廓外，更换背景会丢失图案；请将印花移回衣服范围，已保留原素材");
        for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++) {
            boolean solid=true;
            for(int dy=-2;dy<=2&&solid;dy++)for(int dx=-2;dx<=2;dx++)if(!mask[(y+dy)*w+x+dx]){solid=false;break;}
            interior[y*w+x]=solid;
        }
        var scene=cover(CommerceMockupComposer.decode(scenePng),w,h);
        var output=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        int background=border(reference);long kept=0, edges=0, replaced=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int p=y*w+x, imagePixel=photo.getRGB(x,y), backdrop=scene.getRGB(x,y);
            if(interior[p]){output.setRGB(x,y,imagePixel);kept++;continue;}
            int neighbors=0;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                int xx=x+dx,yy=y+dy;if(xx>=0&&yy>=0&&xx<w&&yy<h&&mask[yy*w+xx])neighbors++;
            }
            if(neighbors==0){output.setRGB(x,y,backdrop);replaced++;continue;}
            int nearest=-1,best=Integer.MAX_VALUE;
            for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++) {
                int xx=x+dx,yy=y+dy,d=dx*dx+dy*dy;
                if(xx>=0&&yy>=0&&xx<w&&yy<h&&interior[yy*w+xx]&&d<best){nearest=yy*w+xx;best=d;}
            }
            // Thin retained details (e.g. a hanger) use their own opaque RGB.
            int inner=nearest<0?imagePixel:photo.getRGB(nearest%w,nearest/w);
            double coverage=neighbors/9d;
            if(nearest>=0) {
                int originalInner=reference.getRGB(nearest%w,nearest/w),original=reference.getRGB(x,y);
                double numerator=0,denominator=0;
                for(int shift:new int[]{0,8,16}) {
                    double contrast=channel(background,shift)-channel(originalInner,shift);
                    numerator+=contrast*(channel(background,shift)-channel(original,shift));denominator+=contrast*contrast;
                }
                // Near-white fabric has no reliable color-key contrast. Its interior stays opaque;
                // only the narrow outer antialiasing band falls back to geometric coverage.
                if(denominator>=144)coverage=clamp(numerator/denominator);
                else if(mask[p]&&neighbors==9)coverage=1;
            }
            if(coverage>=.985&&mask[p]){output.setRGB(x,y,imagePixel);kept++;continue;}
            // Reconstruct mixed edge pixels with uncontaminated nearby cloth/ink, removing white halos.
            output.setRGB(x,y,blend(backdrop,inner,coverage));edges++;
        }
        return new Result(png(output),new Report(VERSION,w,h,kept,edges,replaced,0,
                CommerceImageArchive.sha(scenePng),CommerceImageArchive.sha(referencePhotoPng),
                "固定衣服轮廓与印花内区像素，窄边缘去除原背景混色；背景为共享生成场景，不表示真人姿态、遮挡或物理光照已还原。"));
    }

    private static BufferedImage cover(BufferedImage source,int w,int h) {
        var result=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var g=result.createGraphics();
        g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,w,h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double scale=Math.max(w/(double)source.getWidth(),h/(double)source.getHeight());
        int rw=(int)Math.ceil(source.getWidth()*scale),rh=(int)Math.ceil(source.getHeight()*scale);
        g.drawImage(source,(w-rw)/2,(h-rh)/2,rw,rh,null);g.dispose();return result;
    }
    private static int border(BufferedImage image) {
        int w=image.getWidth(),h=image.getHeight(),n=2*w+2*h-4;int[][] colors=new int[3][n];int at=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(x==0||y==0||x==w-1||y==h-1) {
            int color=image.getRGB(x,y);for(int c=0;c<3;c++)colors[c][at]=channel(color,c*8);at++;
        }
        int rgb=0xff000000;for(int c=0;c<3;c++){java.util.Arrays.sort(colors[c]);rgb|=colors[c][n/2]<<(c*8);}return rgb;
    }
    private static int channel(int rgb,int shift){return(rgb>>>shift)&255;}
    private static double clamp(double value){return Math.max(0,Math.min(1,value));}
    private static int blend(int back,int front,double alpha){int rgb=0xff000000;for(int s:new int[]{0,8,16})rgb|=(int)Math.round(channel(back,s)*(1-alpha)+channel(front,s)*alpha)<<s;return rgb;}
    private static byte[] png(BufferedImage image){try{var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}catch(IOException e){throw new IllegalStateException("场景合成图片无法归档",e);}}
}
