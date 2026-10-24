package br.com.pelotavision;
import org.opencv.core.*;import org.opencv.imgproc.Imgproc;
import java.util.*;
/** Robust ellipse fitted to observed boundary gradients around each localization seed. */
public final class EllipseFitter {
 private static final int RAYS=96;
 private static final class Edge {Point p;int sector,polarity;Edge(double x,double y,int sector,int polarity){p=new Point(x,y);this.sector=sector;this.polarity=polarity;}}
 private static final class Fit {double x,y,a,b,angle,support,rms;Fit(RotatedRect r){x=r.center.x;y=r.center.y;if(r.size.width>=r.size.height){a=r.size.width/2;b=r.size.height/2;angle=r.angle;}else{a=r.size.height/2;b=r.size.width/2;angle=r.angle+90;}angle=((angle%180)+180)%180;}}
 public PelletDetector.Result refine(Mat gray,PelletDetector.Result seeds){return refine(gray,seeds,false);}
 /** belt: trabalha até 1600 px e amplia até 2x, para que pelotas de 5–10 px tenham borda suficiente (raio >= 7 na escala de ajuste). */
 public PelletDetector.Result refine(Mat gray,PelletDetector.Result seeds,boolean belt){
  if(gray.empty()||gray.type()!=CvType.CV_8UC1||gray.cols()!=seeds.width||gray.rows()!=seeds.height)throw new IllegalArgumentException("Imagem e detecções incompatíveis");
  long start=System.nanoTime();double scale=belt?Math.min(2,1600./Math.max(gray.cols(),gray.rows())):Math.min(1,800./Math.max(gray.cols(),gray.rows()));int w=(int)Math.round(gray.cols()*scale),h=(int)Math.round(gray.rows()*scale);double sx=w/(double)gray.cols(),sy=h/(double)gray.rows();
  Mat small=new Mat(),dx=new Mat(),dy=new Mat();List<PelletDetector.Detection> out=new ArrayList<>();
  try{
   Imgproc.resize(gray,small,new Size(w,h),0,0,Imgproc.INTER_LINEAR);Imgproc.GaussianBlur(small,small,new Size(5,5),1);Imgproc.Sobel(small,dx,CvType.CV_32F,1,0);Imgproc.Sobel(small,dy,CvType.CV_32F,0,1);float[] gx=new float[w*h],gy=new float[w*h];dx.get(0,0,gx);dy.get(0,0,gy);
   for(PelletDetector.Detection seed:seeds.circles){
    double x=seed.x*sx,y=seed.y*sy,r=seed.radius*Math.sqrt(sx*sy);Fit f=fit(gx,gy,w,h,x,y,r);
    if(f==null){out.add(new PelletDetector.Detection(seed,seed.x,seed.y,seed.radius,seed.radius,0,false,Double.NaN,Double.NaN,seed.partial,true));continue;}
    // Map the fitted conic through the potentially slightly anisotropic resize using covariance eigenvectors.
    double t=Math.toRadians(f.angle),c=Math.cos(t),s=Math.sin(t),aa=f.a*f.a,bb=f.b*f.b;
    double xx=(aa*c*c+bb*s*s)/(sx*sx),yy=(aa*s*s+bb*c*c)/(sy*sy),xy=(aa-bb)*c*s/(sx*sy),root=Math.hypot(xx-yy,2*xy);
    double a=Math.sqrt(Math.max(0,(xx+yy+root)/2)),b=Math.sqrt(Math.max(0,(xx+yy-root)/2)),angle=Math.toDegrees(.5*Math.atan2(2*xy,xx-yy));
    double cx=f.x/sx,cy=f.y/sy;PelletDetector.Detection fitted=new PelletDetector.Detection(seed,cx,cy,a,b,angle,true,f.support,f.rms,false,seed.needsReview);
    boolean partial=cx-fitted.extentX()<0||cy-fitted.extentY()<0||cx+fitted.extentX()>=gray.cols()||cy+fitted.extentY()>=gray.rows();
    out.add(new PelletDetector.Detection(seed,cx,cy,a,b,angle,true,f.support,f.rms,partial,seed.needsReview));
   }
   return new PelletDetector.Result(out,seeds.width,seeds.height,seeds.candidates,seeds.durationMs+(System.nanoTime()-start)/1_000_000,seeds.candidateLimit,seeds.method+"+ellipse_boundary_v1");
  }finally{small.release();dx.release();dy.release();}
 }
 private static Fit fit(float[] gx,float[] gy,int w,int h,double x,double y,double r){
  if(r<7)return null;List<Edge> edges=new ArrayList<>();
  for(int j=0;j<RAYS;j++){
   double t=2*Math.PI*j/RAYS,c=Math.cos(t),s=Math.sin(t),best=-1;int bx=0,by=0,polarity=0;
   for(double rho=.62*r;rho<1.38*r;rho+=.8){int u=(int)Math.rint(x+rho*c),v=(int)Math.rint(y+rho*s);if(u<1||v<1||u>=w-1||v>=h-1)continue;int p=v*w+u;
    double radial=Math.abs(gx[p]*c+gy[p]*s),tangent=Math.abs(-gx[p]*s+gy[p]*c);if(radial<20||radial<tangent*.85)continue;
    double score=radial*Math.exp(-.5*Math.pow((rho/r-1)/.24,2));if(score>best){best=score;bx=u;by=v;polarity=gx[p]*c+gy[p]*s>=0?1:0;}
   }if(best>=0)edges.add(new Edge(bx,by,j,polarity));
  }
  if(edges.size()<40)return null;Point[] all=new Point[edges.size()];for(int i=0;i<all.length;i++)all[i]=edges.get(i).p;
  Random random=new Random(7351L+Math.round(x*71+y*131+r*233));Fit best=null;double bestScore=-Double.MAX_VALUE;boolean[] selected=null;
  for(int iteration=0;iteration<42;iteration++){
   Point[] points=all;if(iteration>0){points=new Point[8];boolean[] used=new boolean[all.length];for(int j=0;j<8;j++){int i;do{i=random.nextInt(all.length);}while(used[i]);used[i]=true;points[j]=all[i];}}
   Fit f=fitPoints(points);if(!valid(f,x,y,r))continue;double score=0;boolean[] inliers=new boolean[all.length];
   for(int i=0;i<all.length;i++){double e=residual(f,all[i]);inliers[i]=e<.075;score+=(inliers[i]?1:0)-.15*Math.min(e,.3);}
   if(score>bestScore){bestScore=score;best=f;selected=inliers;}
  }
  if(best==null)return null;
  for(int j=0;j<3;j++){
   List<Point> pts=new ArrayList<>();for(int i=0;i<all.length;i++)if(selected[i])pts.add(all[i]);if(pts.size()<32)return null;best=fitPoints(pts.toArray(new Point[0]));if(!valid(best,x,y,r))return null;
   for(int i=0;i<all.length;i++)selected[i]=residual(best,all[i])<.075;
  }
  int n=0,sectors=0,positive=0;double sum2=0;boolean[] coverage=new boolean[8];
  for(int i=0;i<all.length;i++)if(selected[i]){n++;double e=residual(best,all[i]);sum2+=e*e;coverage[edges.get(i).sector/12]=true;positive+=edges.get(i).polarity;}
  for(boolean b:coverage)if(b)sectors++;double rms=n==0?1:Math.sqrt(sum2/n);
  if(n<40||n/(double)RAYS<.55||sectors<7||rms>.055||Math.max(positive,n-positive)<.70*n)return null;
  best.support=n/(double)RAYS;best.rms=rms;return best;
 }
 private static Fit fitPoints(Point[] points){MatOfPoint2f mat=new MatOfPoint2f(points);try{return new Fit(Imgproc.fitEllipse(mat));}catch(CvException e){return null;}finally{mat.release();}}
 private static boolean valid(Fit f,double x,double y,double r){return f!=null&&Double.isFinite(f.x+f.y+f.a+f.b+f.angle)&&Math.hypot(f.x-x,f.y-y)<.40*r&&f.a<1.55*r&&f.b>.48*r&&f.a>.65*r&&f.b<1.35*r&&f.b/f.a>.45;}
 private static double residual(Fit f,Point p){double t=Math.toRadians(f.angle),dx=p.x-f.x,dy=p.y-f.y;return Math.abs(Math.hypot((dx*Math.cos(t)+dy*Math.sin(t))/f.a,(-dx*Math.sin(t)+dy*Math.cos(t))/f.b)-1);}
}
