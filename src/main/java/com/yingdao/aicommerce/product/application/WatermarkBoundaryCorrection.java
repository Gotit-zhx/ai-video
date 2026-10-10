package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;

/** Smooth color offset inferred at the mask boundary, without copying interior source watermarks. */
final class WatermarkBoundaryCorrection {
    final int left, top, right, bottom, nx, ny;
    final double[][] values;
    WatermarkBoundaryCorrection(BufferedImage source, BufferedImage edit, CommerceArtworkLocalEdit.Region region) {
        left=(int)Math.floor(region.x()*source.getWidth());top=(int)Math.floor(region.y()*source.getHeight());
        right=Math.min(source.getWidth()-1,(int)Math.ceil((region.x()+region.width())*source.getWidth())-1);
        bottom=Math.min(source.getHeight()-1,(int)Math.ceil((region.y()+region.height())*source.getHeight())-1);
        nx=Math.min(49,Math.max(3,right-left+1));
        ny=Math.min(49,Math.max(3,(int)Math.round(nx*(bottom-top+1.0)/(right-left+1.0))));
        values=new double[3][nx*ny];
        double wx=Math.pow((nx-1.0)/Math.max(1,right-left),2),wy=Math.pow((ny-1.0)/Math.max(1,bottom-top),2);
        for(int channel=0;channel<3;channel++) {
            double sum=0;int count=0;
            for(int gy=0;gy<ny;gy++)for(int gx=0;gx<nx;gx++)if(gx==0||gy==0||gx==nx-1||gy==ny-1){
                int x=(int)Math.round(left+(right-left)*gx/(nx-1.0)),y=(int)Math.round(top+(bottom-top)*gy/(ny-1.0));
                double delta=((source.getRGB(x,y)>>(channel*8))&255)-((edit.getRGB(x,y)>>(channel*8))&255);
                delta=Math.max(-24,Math.min(24,delta));
                values[channel][gy*nx+gx]=delta;sum+=delta;count++;
            }
            for(int gy=1;gy<ny-1;gy++)for(int gx=1;gx<nx-1;gx++)values[channel][gy*nx+gx]=sum/count;
            for(int pass=0;pass<600;pass++){
                double change=0;
                for(int gy=1;gy<ny-1;gy++)for(int gx=1;gx<nx-1;gx++){
                    int i=gy*nx+gx;
                    double target=((values[channel][i-1]+values[channel][i+1])*wx+(values[channel][i-nx]+values[channel][i+nx])*wy)/(2*(wx+wy));
                    double delta=(target-values[channel][i])*1.6;values[channel][i]+=delta;change=Math.max(change,Math.abs(delta));
                }
                if(change<.005)break;
            }
        }
    }
    boolean contains(int x,int y){return x>=left&&x<=right&&y>=top&&y<=bottom;}
    double feather(int x,int y){return Math.min(1,Math.min(Math.min(x-left+1,right-x+1),Math.min(y-top+1,bottom-y+1))/4.0);}
    double at(int x,int y,int channel){
        double gx=(x-left)*(nx-1.0)/Math.max(1,right-left),gy=(y-top)*(ny-1.0)/Math.max(1,bottom-top);
        int ix=Math.min(nx-2,(int)gx),iy=Math.min(ny-2,(int)gy);double fx=gx-ix,fy=gy-iy;
        return values[channel][iy*nx+ix]*(1-fx)*(1-fy)+values[channel][iy*nx+ix+1]*fx*(1-fy)
            +values[channel][(iy+1)*nx+ix]*(1-fx)*fy+values[channel][(iy+1)*nx+ix+1]*fx*fy;
    }
}
