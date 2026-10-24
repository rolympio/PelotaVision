package br.com.pelotavision;
import android.content.Context;import android.graphics.*;import android.view.View;
/** Enlarged selected object with a guide matching the dimension entered by the user. */
final class ReferencePreviewView extends View {
 private Bitmap photo;private final PelletDetector.Detection object;private final int ow,oh;private ObjectCalibration.Measure measure=ObjectCalibration.Measure.MAJOR_AXIS;
 ReferencePreviewView(Context c,Bitmap photo,PelletDetector.Detection d,int ow,int oh){super(c);this.photo=photo;object=d;this.ow=ow;this.oh=oh;}
 void measure(ObjectCalibration.Measure value){measure=value;invalidate();}
 void detach(){photo=null;}
 @Override protected void onDraw(Canvas canvas){
  canvas.drawColor(Color.rgb(16,26,35));if(photo==null||photo.isRecycled())return;
  int x0=Math.max(0,(int)Math.floor(object.x-object.extentX()*1.35)),y0=Math.max(0,(int)Math.floor(object.y-object.extentY()*1.35)),x1=Math.min(photo.getWidth(),(int)Math.ceil(object.x+object.extentX()*1.35)),y1=Math.min(photo.getHeight(),(int)Math.ceil(object.y+object.extentY()*1.35));
  float scale=Math.min(getWidth()/(float)(x1-x0),getHeight()/(float)(y1-y0)),left=(getWidth()-(x1-x0)*scale)/2,top=(getHeight()-(y1-y0)*scale)/2;Paint p=new Paint(Paint.FILTER_BITMAP_FLAG);
  canvas.drawBitmap(photo,new Rect(x0,y0,x1,y1),new RectF(left,top,left+(x1-x0)*scale,top+(y1-y0)*scale),p);
  ReferenceOverlay.drawGeometry(canvas,object,measure,ow,oh,photo.getWidth(),photo.getHeight(),left-x0*scale,top-y0*scale,scale,false);
 }
}
