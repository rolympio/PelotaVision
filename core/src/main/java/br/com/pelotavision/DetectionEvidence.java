package br.com.pelotavision;
import org.opencv.core.*;import org.opencv.imgproc.Imgproc;import java.util.*;
/** Low-resolution image evidence, separate from detections and physical measurements. */
public final class DetectionEvidence {
 public final int width,height,imageWidth,imageHeight;public final boolean available,brightForeground;public final double threshold,polarityContrast;public final boolean illuminationCorrected;public final double backgroundPlaneSpan;
 private final byte[] foreground;
 DetectionEvidence(int w,int h,int iw,int ih,byte[] mask,boolean available,boolean bright,double threshold,double contrast){this(w,h,iw,ih,mask,available,bright,threshold,contrast,false,0);}
 private DetectionEvidence(int w,int h,int iw,int ih,byte[] mask,boolean available,boolean bright,double threshold,double contrast,boolean normalized,double span){width=w;height=h;imageWidth=iw;imageHeight=ih;foreground=mask;this.available=available;brightForeground=bright;this.threshold=threshold;polarityContrast=contrast;illuminationCorrected=normalized;backgroundPlaneSpan=span;}
 public static DetectionEvidence create(Mat gray,List<PelletDetector.Result> results){return create(gray,results,384);}
 /** Correia: 1024 px, para que pelotas de ~10 px não virem 2–3 px na máscara de evidência. */
 public static DetectionEvidence create(Mat gray,List<PelletDetector.Result> results,int side){
  double resize=Math.min(1,side/(double)Math.max(gray.cols(),gray.rows()));int w=Math.max(1,(int)Math.round(gray.cols()*resize)),h=Math.max(1,(int)Math.round(gray.rows()*resize));Mat small=new Mat(),binary=new Mat();
  try{
   Imgproc.resize(gray,small,new Size(w,h),0,0,Imgproc.INTER_AREA);Imgproc.GaussianBlur(small,small,new Size(5,5),1.3);byte[] values=new byte[w*h];small.get(0,0,values);
   double threshold=Imgproc.threshold(small,binary,0,255,Imgproc.THRESH_BINARY|Imgproc.THRESH_OTSU);byte[] mask=new byte[w*h];binary.get(0,0,mask);
   PelletDetector.Result reliable=null;double best=-1;for(PelletDetector.Result r:results){int valid=0;for(PelletDetector.Detection d:r.circles)if(ShapeStatistics.includes(d))valid++;double q=valid*valid/(double)Math.max(1,r.circles.size());if(q>best){best=q;reliable=r;}}
   double sum=0,weight=0;
   if(reliable!=null)for(PelletDetector.Detection d:reliable.circles)if(ShapeStatistics.includes(d)){
    double angle=Math.toRadians(d.angleDeg),c=Math.cos(angle),s=Math.sin(angle),inside=0,outside=0;int n=0;
    for(int ray=0;ray<32;ray++){double t=2*Math.PI*ray/32,dx=d.semiMajor*Math.cos(t)*c-d.semiMinor*Math.sin(t)*s,dy=d.semiMajor*Math.cos(t)*s+d.semiMinor*Math.sin(t)*c;int ix=(int)((d.x+.45*dx)*w/gray.cols()),iy=(int)((d.y+.45*dy)*h/gray.rows()),ox=(int)((d.x+1.22*dx)*w/gray.cols()),oy=(int)((d.y+1.22*dy)*h/gray.rows());if(ix<0||iy<0||ox<0||oy<0||ix>=w||ox>=w||iy>=h||oy>=h)continue;inside+=values[iy*w+ix]&255;outside+=values[oy*w+ox]&255;n++;}
    if(n>=16){sum+=(inside-outside)/n*d.area;weight+=d.area;}
   }
   double contrast=weight==0?0:sum/weight;boolean bright=contrast>=0;
   double[] plane=backgroundPlane(values,w,h,bright);double span=Math.abs(plane[1])+Math.abs(plane[2]);boolean normalized=span>Math.max(24,.8*Math.abs(contrast));
   if(normalized){byte[] corrected=new byte[values.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++){double bg=plane[0]+plane[1]*((x+.5)/w-.5)+plane[2]*((y+.5)/h-.5);corrected[y*w+x]=(byte)Math.round(Math.max(0,Math.min(255,(values[y*w+x]&255)-bg+128)));}small.put(0,0,corrected);threshold=Imgproc.threshold(small,binary,0,255,Imgproc.THRESH_BINARY|Imgproc.THRESH_OTSU);binary.get(0,0,mask);}
   int count=0;for(int i=0;i<mask.length;i++){mask[i]=(byte)(((mask[i]!=0)==bright)?1:0);count+=mask[i];}
   double fraction=count/(double)mask.length;Core.MinMaxLocResult range=Core.minMaxLoc(small);boolean available=Math.abs(contrast)>=3&&range.maxVal-range.minVal>=24&&fraction>=.02&&fraction<=.95;
   return new DetectionEvidence(w,h,gray.cols(),gray.rows(),mask,available,bright,threshold,contrast,normalized,span);
  }finally{small.release();binary.release();}
 }
 /** Robust linear background trend from low/high intensity quantiles in a 4x4 grid. */
 private static double[] backgroundPlane(byte[] values,int w,int h,boolean bright){
  double mean=0,sx=0,sy=0,xx=0,yy=0;for(int row=0;row<4;row++)for(int col=0;col<4;col++){int[] hist=new int[256];int n=0;for(int y=row*h/4;y<(row+1)*h/4;y++)for(int x=col*w/4;x<(col+1)*w/4;x++){hist[values[y*w+x]&255]++;n++;}int target=Math.max(1,(int)Math.ceil(n*(bright?.1:.9))),total=0,q=0;for(;q<255;q++){total+=hist[q];if(total>=target)break;}double x=(col+.5)/4-.5,y=(row+.5)/4-.5;mean+=q/16.;sx+=x*q;sy+=y*q;xx+=x*x;yy+=y*y;}return new double[]{mean,sx/xx,sy/yy};
 }
 /** Immutable union masks: duplicate and fragmented detections cannot multiply covered area. */
 public static final class Map {
  private final byte[] all,valid;public final double weightedFitQuality;
  Map(byte[] all,byte[] valid,double quality){this.all=all;this.valid=valid;weightedFitQuality=quality;}
 }
 public Map map(PelletDetector.Result result){
  byte[] all=new byte[width*height],valid=new byte[width*height];double sum=0,weight=0;
  for(PelletDetector.Detection d:result.circles){boolean fitted=ShapeStatistics.includes(d);double t=Math.toRadians(d.angleDeg),c=Math.cos(t),s=Math.sin(t);int left=Math.max(0,(int)((d.x-d.extentX())*width/imageWidth)-1),right=Math.min(width-1,(int)((d.x+d.extentX())*width/imageWidth)+1),top=Math.max(0,(int)((d.y-d.extentY())*height/imageHeight)-1),bottom=Math.min(height-1,(int)((d.y+d.extentY())*height/imageHeight)+1),fg=0;
   for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++){double dx=(x+.5)*imageWidth/width-d.x,dy=(y+.5)*imageHeight/height-d.y,u=(dx*c+dy*s)/d.semiMajor,v=(-dx*s+dy*c)/d.semiMinor;if(u*u+v*v>1)continue;int i=y*width+x;all[i]=1;if(fitted){valid[i]=1;fg+=foreground[i];}}
   if(fitted){sum+=fg*(.6*DetectionAssessment.clamp(d.fitSupport)+.4*DetectionAssessment.clamp(1-d.fitRms/.055));weight+=fg;}
  }return new Map(all,valid,weight==0?0:sum/weight);
 }
 public static final class Coverage {
  public final boolean available;public final int foregroundPixels;public final double covered,validated,purity,weightedFitQuality;
  Coverage(boolean available,int n,double covered,double valid,double purity,double quality){this.available=available;foregroundPixels=n;this.covered=covered;validated=valid;this.purity=purity;weightedFitQuality=quality;}
 }
 public Coverage coverage(Map map,DetectionAssessment.Context context){
  int fg=0,covered=0,valid=0,marked=0;ObjectCalibration.Reference ref=context.scale==null?null:context.scale.selectedObject;
  for(int y=0;y<height;y++)for(int x=0;x<width;x++){double px=(x+.5)*imageWidth/width,py=(y+.5)*imageHeight/height;if(!context.insideAt(px,py))continue;if(context.referenceExcludedAt(px,py,1))continue;int i=y*width+x;if(map.all[i]!=0)marked++;if(foreground[i]==0)continue;fg++;if(map.all[i]!=0)covered++;if(map.valid[i]!=0)valid++;}
  return new Coverage(available&&fg>=16,fg,fg==0?0:covered/(double)fg,fg==0?0:valid/(double)fg,marked==0?0:covered/(double)marked,map.weightedFitQuality);
 }
}
