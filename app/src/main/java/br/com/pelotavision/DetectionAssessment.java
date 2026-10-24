package br.com.pelotavision;
import java.util.*;
/** Heuristic evidence for comparing detector settings; never edits or filters detections. */
public final class DetectionAssessment {
 private DetectionAssessment(){}
 public static final class Context {
  public final Granulometry.Settings scale;public final int originalWidth,originalHeight;public final InterestRegion roi;public final double expectedMinimumMm;
  public final AnalysisWindow window;private boolean mapped;private double sx=1,sy=1,tx,ty;
  public static Context tile(Granulometry.Settings scale,int ow,int oh,InterestRegion roi,double minimum,DetailGeometry map,TilePlan.Tile tile){Context c=new Context(scale,ow,oh,null,minimum,new AnalysisWindow(map.previewWidth,map.previewHeight,null));c.mapped=true;c.sx=map.sx;c.sy=map.sy;c.tx=map.tx+tile.left*map.sx;c.ty=map.ty+tile.top*map.sy;c.tileRoi=roi;return c;}
  private InterestRegion tileRoi;
  boolean mappedInput(){return mapped;}
  boolean hasInputRoi(){return mapped?tileRoi!=null:roi!=null;}
  double[] inputMapping(){return mapped?new double[]{sx,sy,tx,ty}:new double[]{1,1,window==null?0:window.left,window==null?0:window.top};}
  AnalysisWindow detectionWindow(){return mapped?null:window;}
  PelletDetector.Result filterScope(PelletDetector.Result input){if(!mapped||tileRoi==null)return input;List<PelletDetector.Detection> out=new ArrayList<>();for(PelletDetector.Detection d:input.circles)if(tileRoi.includes(d.affine(sx,sy,tx,ty)))out.add(d);return new PelletDetector.Result(out,input.width,input.height,input.candidates,input.durationMs,input.candidateLimit,input.method);}

  boolean insideAt(double x,double y){return mapped?tileRoi==null||tileRoi.contains(x*sx+tx,y*sy+ty):roi==null||roi.contains(x,y);}
  public Context(Granulometry.Settings scale,int ow,int oh,InterestRegion roi,double minimum){this(scale,ow,oh,roi,minimum,null);}
  public Context(Granulometry.Settings scale,int ow,int oh,InterestRegion roi,double minimum,AnalysisWindow window){this.window=window;if(!Double.isFinite(minimum)||minimum<0)throw new IllegalArgumentException("Mínimo esperado deve ser zero ou positivo.");this.scale=scale;originalWidth=ow;originalHeight=oh;this.roi=window==null?roi:window.localRegion;expectedMinimumMm=minimum;}
  public boolean physical(){return scale!=null&&!scale.pixelsOnly()&&!scale.estimated()&&originalWidth>0&&originalHeight>0;}
  public boolean minimumActive(){return physical()&&expectedMinimumMm>0;}
  boolean sample(PelletDetector.Detection d){
   if(mapped?tileRoi!=null&&!tileRoi.includes(d.affine(sx,sy,tx,ty)):roi!=null&&!roi.includes(d))return false;
   // Exclude the USER-selected reference's spatial footprint from scoring only. Never rebind its identity.
   ObjectCalibration.Reference ref=scale==null?null:scale.selectedObject;
   return !referenceExcludedAt(d.x,d.y,.65);
  }
  boolean referenceExcludedAt(double x,double y,double margin){ObjectCalibration.Reference ref=scale==null?null:scale.selectedObject;return ref!=null&&ref.excludeFromStatistics&&ref.geometry.normalizedDistance(mapped?x*sx+tx:x+(window==null?0:window.left),mapped?y*sy+ty:y+(window==null?0:window.top))<=margin;
  }
  double pixelsPerMm(PelletDetector.Result result){return scale.analyzedPixelsPerMm(originalWidth,originalHeight,window==null?result.width:window.fullWidth,window==null?result.height:window.fullHeight)/(mapped?Math.sqrt(sx*sy):1);
  }
 }
 public static final class Weibull {
  public final int n;public final double shape,scale,ks;
  Weibull(int n,double shape,double scale,double ks){this.n=n;this.shape=shape;this.scale=scale;this.ks=ks;}
  public boolean available(){return Double.isFinite(ks);}
  public double cdf(double d){return -Math.expm1(-Math.pow(d/scale,shape));}
 }
 /** Two-parameter Weibull MLE on observed NUMBER-weighted diameters, not on histogram bins. */
 public static Weibull weibull(List<Double> values){
  int n=values.size();if(n<12)return new Weibull(n,Double.NaN,Double.NaN,Double.NaN);
  double[] logs=new double[n];double mean=0,min=Double.POSITIVE_INFINITY,max=-min;
  for(int i=0;i<n;i++){double d=values.get(i);if(!Double.isFinite(d)||d<=0)return new Weibull(n,Double.NaN,Double.NaN,Double.NaN);logs[i]=Math.log(d);mean+=logs[i]/n;min=Math.min(min,logs[i]);max=Math.max(max,logs[i]);}
  if(max-min<1e-4)return new Weibull(n,Double.NaN,Double.NaN,Double.NaN);
  double lo=.15,hi=100;
  for(int step=0;step<70;step++){double k=(lo+hi)/2,sum=0,weighted=0;for(double x:logs){double w=Math.exp(k*(x-max));sum+=w;weighted+=w*x;}double equation=1/k+mean-weighted/sum;if(equation>0)lo=k;else hi=k;}
  double k=(lo+hi)/2,sum=0;for(double x:logs)sum+=Math.exp(k*(x-max));double lambda=Math.exp(max+Math.log(sum/n)/k);
  double[] x=new double[n];for(int i=0;i<n;i++)x[i]=values.get(i);Arrays.sort(x);double ks=0;
  for(int i=0;i<n;i++){double cdf=-Math.expm1(-Math.pow(x[i]/lambda,k));ks=Math.max(ks,Math.max((i+1.)/n-cdf,cdf-i/(double)n));}
  return new Weibull(n,k,lambda,ks);
 }
 public static final class Metrics {
  public final int total,valid,partial,review,overlapping,belowMinimum;public final double fitQuality,validFraction,overlapFraction,partialFraction,belowFraction;public final Weibull weibull;
  Metrics(int total,int valid,int partial,int review,int overlapping,int below,double quality,Weibull weibull){this.total=total;this.valid=valid;this.partial=partial;this.review=review;this.overlapping=overlapping;belowMinimum=below;fitQuality=quality;validFraction=total==0?0:valid/(double)total;overlapFraction=total==0?0:overlapping/(double)total;partialFraction=total==0?0:partial/(double)total;belowFraction=valid==0?0:below/(double)valid;this.weibull=weibull;}
 }
 public static Metrics measure(PelletDetector.Result result,Context context){
  List<PelletDetector.Detection> selected=new ArrayList<>();for(PelletDetector.Detection d:result.circles)if(context.sample(d))selected.add(d);
  int valid=0,partial=0,review=0,below=0;double quality=0;List<Double> diameters=new ArrayList<>();
  double ppm=context.physical()?context.pixelsPerMm(result):1;
  for(PelletDetector.Detection d:selected){if(d.partial)partial++;if(d.needsReview)review++;if(!ShapeStatistics.includes(d))continue;valid++;quality+=.6*clamp(d.fitSupport)+.4*clamp(1-d.fitRms/.055);double diameter=2*d.radius/ppm;diameters.add(diameter);if(context.minimumActive()&&diameter<context.expectedMinimumMm-1e-9)below++;}
  boolean[] overlap=new boolean[selected.size()];for(int i=0;i<selected.size();i++)for(int j=0;j<i;j++){PelletDetector.Detection a=selected.get(i),b=selected.get(j);double distance=Math.hypot(a.x-b.x,a.y-b.y);if(distance<.55*(a.radius+b.radius)||a.normalizedDistance(b.x,b.y)<.55||b.normalizedDistance(a.x,a.y)<.55){overlap[i]=true;overlap[j]=true;}}
  int overlaps=0;for(boolean b:overlap)if(b)overlaps++;return new Metrics(selected.size(),valid,partial,review,overlaps,below,valid==0?0:quality/valid,weibull(diameters));
 }
 public static double score(Metrics m,int maximumValid,double stability,boolean limit){
  if(m.valid==0)return 0;
  double rr=m.weibull.available()?clamp(1-m.weibull.ks/.25):.5;
  return Math.max(0,Math.min(100,30*m.fitQuality+20*m.validFraction+25*m.valid/Math.max(1.,maximumValid)+15*clamp(stability)+5*rr-20*m.overlapFraction-8*m.partialFraction-35*m.belowFraction-(limit?15:0)));
 }
 public static double coverageScore(Metrics m,DetectionEvidence.Coverage evidence,double stability,boolean limit){
  if(m.valid==0)return 0;
  double rr=m.weibull.available()?clamp(1-m.weibull.ks/.25):.5;
  return Math.max(0,Math.min(100,45*evidence.covered+25*evidence.validated+15*evidence.weightedFitQuality+10*clamp(stability)+5*evidence.purity+rr-20*m.overlapFraction-35*m.belowFraction-(limit?15:0)));
 }
 static double clamp(double x){return Double.isFinite(x)?Math.max(0,Math.min(1,x)):0;}
 /** One-to-one geometric agreement. Not truth or a detection confidence. */
 public static double agreement(PelletDetector.Result a,PelletDetector.Result b){
  List<PelletDetector.Detection> aa=new ArrayList<>(),bb=new ArrayList<>();for(PelletDetector.Detection d:a.circles)if(ShapeStatistics.includes(d))aa.add(d);for(PelletDetector.Detection d:b.circles)if(ShapeStatistics.includes(d))bb.add(d);
  if(aa.isEmpty()||bb.isEmpty())return 0;boolean[] used=new boolean[bb.size()];int matches=0;
  for(PelletDetector.Detection d:aa){int best=-1;double distance=Double.POSITIVE_INFINITY;for(int j=0;j<bb.size();j++){if(used[j])continue;PelletDetector.Detection e=bb.get(j);double ratio=d.radius/e.radius,ds=Math.hypot(d.x-e.x,d.y-e.y)/(d.radius+e.radius);if(ratio>=.7&&ratio<=1.43&&ds<=.3&&ds<distance){best=j;distance=ds;}}if(best>=0){matches++;used[best]=true;}}
  return 2.*matches/(aa.size()+bb.size());
 }
}
