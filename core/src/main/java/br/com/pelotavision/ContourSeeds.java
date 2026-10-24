package br.com.pelotavision;
import org.opencv.core.*;import org.opencv.imgproc.Imgproc;import java.util.*;
/** Sparse-scene fallback: isolated elongated objects may not supply any Hough circle seed. */
final class ContourSeeds {
 static PelletDetector.Result detect(Mat gray,PelletDetector.Parameters cfg){
  long start=System.nanoTime();double scale=Math.min(1,(cfg.belt?1600.:800.)/Math.max(gray.cols(),gray.rows()));Mat g=new Mat(),mask=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
  try{
   Imgproc.resize(gray,g,new Size(),scale,scale,Imgproc.INTER_AREA);Imgproc.GaussianBlur(g,g,new Size(5,5),1);
   byte[] bytes=new byte[(int)g.total()];g.get(0,0,bytes);int[] hist=new int[256];int n=0;
   for(int y=0;y<g.rows();y++)for(int x=0;x<g.cols();x++)if(x<3||y<3||x>=g.cols()-3||y>=g.rows()-3){hist[bytes[y*g.cols()+x]&255]++;n++;}
   int med=0,sum=0;for(;med<255;med++){sum+=hist[med];if(sum>=n/2)break;}
   double threshold=Imgproc.threshold(g,mask,0,255,Imgproc.THRESH_BINARY|Imgproc.THRESH_OTSU);if(Core.minMaxLoc(g).maxVal-Core.minMaxLoc(g).minVal<24)return new PelletDetector.Result(Collections.emptyList(),gray.cols(),gray.rows(),0,0,false,"isolated_contour_seed_v1");
   if(med>threshold)Core.bitwise_not(mask,mask);Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_NONE);
   List<PelletDetector.Detection> out=new ArrayList<>();
   for(MatOfPoint contour:contours){if(out.size()>=cfg.maxObjects)break;if(contour.rows()<30)continue;double area=Imgproc.contourArea(contour);if(area<Math.PI*Math.pow(gray.cols()*cfg.minRadiusFraction*scale,2))continue;MatOfPoint2f points=new MatOfPoint2f(contour.toArray());RotatedRect fit;try{fit=Imgproc.fitEllipse(points);}finally{points.release();}
    double a=Math.max(fit.size.width,fit.size.height)/2,b=Math.min(fit.size.width,fit.size.height)/2,r=Math.sqrt(a*b);
    if(b<=0||b/a<.45||r>gray.cols()*cfg.maxRadiusFraction*scale||area/(Math.PI*a*b)<.85||area/(Math.PI*a*b)>1.12)continue;
    boolean partial=fit.center.x-a<0||fit.center.y-a<0||fit.center.x+a>=g.cols()||fit.center.y+a>=g.rows();
    out.add(new PelletDetector.Detection(fit.center.x/scale,fit.center.y/scale,r/scale,area/(scale*scale),Math.min(1,area/(Math.PI*a*b)),partial,false));
   }
   out.sort(Comparator.comparingDouble((PelletDetector.Detection d)->d.y).thenComparingDouble(d->d.x));
   return new PelletDetector.Result(out,gray.cols(),gray.rows(),out.size(),(System.nanoTime()-start)/1_000_000,out.size()>=cfg.maxObjects,"isolated_contour_seed_v1");
  }finally{for(MatOfPoint c:contours)c.release();g.release();mask.release();hierarchy.release();}
 }
}
