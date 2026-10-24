package br.com.pelotavision;
import android.graphics.*;
/** Explicit user reference, drawn even if excluded from sample statistics or outside the ROI. */
final class ReferenceOverlay {
 static void draw(Canvas canvas,ObjectCalibration.Reference ref,float left,float top,float scale){if(ref!=null)drawGeometry(canvas,ref.geometry,ref.measure,ref.originalWidth,ref.originalHeight,ref.width,ref.height,left,top,scale,true);}
 static void drawGeometry(Canvas canvas,PelletDetector.Detection d,ObjectCalibration.Measure measure,int ow,int oh,int w,int h,float left,float top,float scale,boolean label){
  Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(2.4f,w/500f*scale));p.setColor(Color.rgb(183,149,255));CircleOverlay.drawEllipse(canvas,d,left,top,scale,p);
  ObjectCalibration.Axes axes=ObjectCalibration.originalAxes(d,ow,oh,w,h);double t=Math.toRadians(axes.angleDeg);float x=left+(float)d.x*scale,y=top+(float)d.y*scale;
  p.setStrokeWidth(Math.max(1.8f,2*scale));p.setColor(Color.rgb(255,230,118));
  if(measure!=ObjectCalibration.Measure.MINOR_AXIS){float vx=(float)(axes.major/2*Math.cos(t)*w/ow)*scale,vy=(float)(axes.major/2*Math.sin(t)*h/oh)*scale;canvas.drawLine(x-vx,y-vy,x+vx,y+vy,p);}
  if(measure!=ObjectCalibration.Measure.MAJOR_AXIS){float vx=(float)(-axes.minor/2*Math.sin(t)*w/ow)*scale,vy=(float)(axes.minor/2*Math.cos(t)*h/oh)*scale;canvas.drawLine(x-vx,y-vy,x+vx,y+vy,p);}
  if(label){p.setStyle(Paint.Style.FILL);p.setTextSize(Math.max(12,Math.min(28,(float)d.semiMinor*.4f)*scale));p.setTypeface(Typeface.DEFAULT_BOLD);p.setColor(Color.rgb(183,149,255));float yy=Math.max(top+p.getTextSize(),y-(float)d.extentY()*scale-5);canvas.drawText("REF",Math.max(left+2,x-(float)d.extentX()*scale),yy,p);}
 }
}
