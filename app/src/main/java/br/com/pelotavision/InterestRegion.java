package br.com.pelotavision;
import java.util.*;
/** Immutable ROI in upright analyzed-photo pixels; coordinates remain in the full photo even when the active display/input is cropped. */
public final class InterestRegion {
 public enum Shape { RECTANGLE, CIRCLE }
 public final Shape shape;public final double left,top,right,bottom;
 public InterestRegion(Shape shape,double left,double top,double right,double bottom){
  if(shape==null||!Double.isFinite(left+top+right+bottom)||left<0||top<0||right<=left||bottom<=top)throw new IllegalArgumentException("Área inválida.");
  if(shape==Shape.CIRCLE&&Math.abs((right-left)-(bottom-top))>1e-6)throw new IllegalArgumentException("O círculo deve ter largura e altura iguais.");
  this.shape=shape;this.left=left;this.top=top;this.right=right;this.bottom=bottom;
 }
 public double width(){return right-left;}public double height(){return bottom-top;}
 public double cx(){return (left+right)/2;}public double cy(){return (top+bottom)/2;}
 public boolean contains(double x,double y){return shape==Shape.CIRCLE?Math.hypot(x-cx(),y-cy())<=width()/2:x>=left&&x<=right&&y>=top&&y<=bottom;}
 public boolean includes(PelletDetector.Detection d){
  double e=1e-7;
  return shape==Shape.CIRCLE?d.farthestDistance(cx(),cy())<=width()/2+e:
   d.x-d.extentX()>=left-e&&d.x+d.extentX()<=right+e&&d.y-d.extentY()>=top-e&&d.y+d.extentY()<=bottom+e;
 }
 public static InterestRegion initial(Shape shape,int w,int h){
  double size=Math.min(w,h)*.8;return shape==Shape.CIRCLE?new InterestRegion(shape,(w-size)/2,(h-size)/2,(w+size)/2,(h+size)/2):new InterestRegion(shape,w*.1,h*.1,w*.9,h*.9);
 }
 public InterestRegion moved(double dx,double dy,double w,double h){
  dx=Math.max(-left,Math.min(w-right,dx));dy=Math.max(-top,Math.min(h-bottom,dy));return new InterestRegion(shape,left+dx,top+dy,right+dx,bottom+dy);
 }
 public InterestRegion scaled(double factor,double w,double h,double minimum){
  double maxFactor=Math.min(Math.min(cx(),w-cx())*2/width(),Math.min(cy(),h-cy())*2/height());
  factor=Math.max(Math.max(minimum/width(),minimum/height()),Math.min(maxFactor,factor));
  factor=Math.min(maxFactor,factor);double hw=width()*factor/2,hh=height()*factor/2;
  return new InterestRegion(shape,cx()-hw,cy()-hh,cx()+hw,cy()+hh);
 }
 /** Handle order: NW,N,NE,E,SE,S,SW,W. Circle exposes N,E,S,W. */
 public InterestRegion resized(int handle,double dx,double dy,double w,double h,double minimum){
  if(shape==Shape.CIRCLE){
   double size=width(),maxSize,newSize,nx=cx(),ny=cy();
   if(handle==1){maxSize=Math.min(bottom,2*Math.min(nx,w-nx));newSize=Math.max(Math.min(minimum,maxSize),Math.min(maxSize,size-dy));return new InterestRegion(shape,nx-newSize/2,bottom-newSize,nx+newSize/2,bottom);}
   if(handle==5){maxSize=Math.min(h-top,2*Math.min(nx,w-nx));newSize=Math.max(Math.min(minimum,maxSize),Math.min(maxSize,size+dy));return new InterestRegion(shape,nx-newSize/2,top,nx+newSize/2,top+newSize);}
   if(handle==7){maxSize=Math.min(right,2*Math.min(ny,h-ny));newSize=Math.max(Math.min(minimum,maxSize),Math.min(maxSize,size-dx));return new InterestRegion(shape,right-newSize,ny-newSize/2,right,ny+newSize/2);}
   maxSize=Math.min(w-left,2*Math.min(ny,h-ny));newSize=Math.max(Math.min(minimum,maxSize),Math.min(maxSize,size+dx));return new InterestRegion(shape,left,ny-newSize/2,left+newSize,ny+newSize/2);
  }
  double l=left,t=top,r=right,b=bottom;
  if(handle==0||handle==6||handle==7)l=Math.max(0,Math.min(right-minimum,left+dx));
  if(handle==2||handle==3||handle==4)r=Math.min(w,Math.max(left+minimum,right+dx));
  if(handle==0||handle==1||handle==2)t=Math.max(0,Math.min(bottom-minimum,top+dy));
  if(handle==4||handle==5||handle==6)b=Math.min(h,Math.max(top+minimum,bottom+dy));
  return new InterestRegion(shape,l,t,r,b);
 }
 public List<Integer> indices(PelletDetector.Result all){List<Integer> out=new ArrayList<>();for(int i=0;i<all.circles.size();i++)if(includes(all.circles.get(i)))out.add(i);return out;}
 public static PelletDetector.Result select(PelletDetector.Result all,List<Integer> ids){List<PelletDetector.Detection> out=new ArrayList<>();for(int i:ids)out.add(all.circles.get(i));return new PelletDetector.Result(out,all.width,all.height,all.candidates,all.durationMs,all.candidateLimit,all.method);}
}
