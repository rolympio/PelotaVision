package br.com.pelotavision;
import org.opencv.core.*;
import org.opencv.imgproc.*;
import java.util.*;
/** Gradient-circle proposals in raw and locally equalized luminance; polarity independent. */
public final class AdaptivePelletDetector {
 private static final class Circle {double x,y,r,q;Circle(double x,double y,double r,double q){this.x=x;this.y=y;this.r=r;this.q=q;}}
 public PelletDetector.Result detect(Mat gray,PelletDetector.Parameters cfg,int maximumSide,int scales){
  if(gray.empty()||gray.type()!=CvType.CV_8UC1)throw new IllegalArgumentException("Imagem cinza de 8 bits necessária");
  if(maximumSide<128)throw new IllegalArgumentException("Resolução inválida");long start=System.nanoTime();
  int width=gray.cols(),height=gray.rows();double scale=Math.min(1,Math.min(800,maximumSide)/(double)Math.max(width,height));int w=(int)Math.round(width*scale),h=(int)Math.round(height*scale);
  double sx=w/(double)width,sy=h/(double)height,rs=Math.sqrt(sx*sy);List<Mat> owned=new ArrayList<>();CLAHE clahe=Imgproc.createCLAHE(2,new Size(8,8));
  try{
   Mat g=own(owned),enh=own(owned),dx=own(owned),dy=own(owned);Imgproc.resize(gray,g,new Size(w,h),0,0,Imgproc.INTER_LINEAR);Imgproc.GaussianBlur(g,g,new Size(5,5),1);clahe.apply(g,enh);
   Imgproc.Sobel(g,dx,CvType.CV_32F,1,0);Imgproc.Sobel(g,dy,CvType.CV_32F,0,1);float[] gx=new float[w*h],gy=new float[w*h];dx.get(0,0,gx);dy.get(0,0,gy);
   List<Circle> candidates=new ArrayList<>();double min=Math.max(5,width*cfg.minRadiusFraction*rs),max=Math.max(min+2,width*cfg.maxRadiusFraction*rs);
   // Overlapping bands prevent small circles being suppressed by a broad radius search.
   double[][] bands={{.015,.0375},{.02875,.0625},{.05,.10625},{.0875,.15}};
   double acceptance=Math.max(.63,Math.min(.87,.72+(cfg.threshold-.045)*1.2));
   for(Mat image:new Mat[]{g,enh})for(double[] band:bands){
    int lo=(int)Math.ceil(Math.max(min,w*band[0])),hi=(int)Math.floor(Math.min(max,w*band[1]));if(hi<=lo)continue;
    Mat circles=own(owned);Imgproc.HoughCircles(image,circles,Imgproc.HOUGH_GRADIENT_ALT,1.5,Math.max(10,lo),120,acceptance,lo,hi);
    for(int i=0;i<circles.cols();i++){
     double[] c=circles.get(0,i);double[] radial=new double[72];int n=0,aligned=0;
     for(int j=0;j<72;j++){double angle=2*Math.PI*j/72,a=Math.cos(angle),b=Math.sin(angle);int x=(int)(c[0]+c[2]*a),y=(int)(c[1]+c[2]*b);if(x<=0||y<=0||x>=w-1||y>=h-1)continue;int p=y*w+x;double r=Math.abs(gx[p]*a+gy[p]*b),t=Math.abs(-gx[p]*b+gy[p]*a);radial[n++]=r;if(r>t)aligned++;}
     if(n<24)continue;Arrays.sort(radial,0,n);double at=.65*(n-1);int lower=(int)at;double strength=radial[lower]+(radial[Math.min(n-1,lower+1)]-radial[lower])*(at-lower);double q=strength*aligned/n;
     if(q<12)continue;candidates.add(new Circle(c[0],c[1],c[2],q));
    }
   }
   candidates.sort((a,b)->Double.compare(b.q,a.q));List<Circle> kept=new ArrayList<>();boolean limit=false;
   for(Circle c:candidates){boolean duplicate=false;for(Circle a:kept)if(Math.hypot(c.x-a.x,c.y-a.y)<.75*(c.r+a.r)){duplicate=true;break;}if(duplicate)continue;if(kept.size()>=600){limit=true;break;}kept.add(c);}
   // An empty gradient result is a valid empty frame; LoG is only used for irregular textured scenes
   // when it provides at least three candidates, and its original area method is identified explicitly.
   if(kept.size()<8){PelletDetector.Result contour=ContourSeeds.detect(gray,cfg);if(!contour.circles.isEmpty())return contour;}
   if(kept.size()<2){PelletDetector.Result fallback=new PelletDetector().detect(gray,cfg,maximumSide,scales);if(fallback.circles.size()>=3)return fallback;}
   List<PelletDetector.Detection> result=new ArrayList<>();for(Circle c:kept){double x=c.x/sx,y=c.y/sy,r=c.r/rs;boolean partial=x-r<0||y-r<0||x+r>=width||y+r>=height;boolean review=c.q<18;result.add(new PelletDetector.Detection(x,y,r,Math.PI*r*r,1,partial,review));}
   result.sort(Comparator.comparingDouble((PelletDetector.Detection d)->d.y).thenComparingDouble(d->d.x));
   return new PelletDetector.Result(result,width,height,candidates.size(),(System.nanoTime()-start)/1_000_000,limit,"gradient_circle_CLAHE_v1");
  }finally{for(Mat m:owned)m.release();clahe.collectGarbage();clahe.clear();}
 }
 private static Mat own(List<Mat> mats){Mat m=new Mat();mats.add(m);return m;}
}
