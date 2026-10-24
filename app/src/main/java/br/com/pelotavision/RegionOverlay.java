package br.com.pelotavision;
import android.graphics.*;
/** Same ROI boundary in the preview and exported annotated panel. */
final class RegionOverlay {
 static void draw(Canvas canvas,InterestRegion roi,float left,float top,float scale,int width,int height,boolean handles){
  if(roi==null)return;canvas.save();canvas.translate(left,top);canvas.scale(scale,scale);
  RectF box=new RectF((float)roi.left,(float)roi.top,(float)roi.right,(float)roi.bottom);
  canvas.save();canvas.clipRect(0,0,width,height);
  if(roi.shape==InterestRegion.Shape.CIRCLE){Path hole=new Path();hole.addOval(box,Path.Direction.CW);canvas.clipOutPath(hole);}else canvas.clipOutRect(box);
  canvas.drawColor(Color.argb(115,0,0,0));canvas.restore();
  Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
  p.setColor(Color.rgb(64,220,242));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2/scale);
  if(roi.shape==InterestRegion.Shape.CIRCLE)canvas.drawOval(box,p);else canvas.drawRect(box,p);
  if(handles){p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);for(int i=0;i<8;i++)if(roi.shape==InterestRegion.Shape.RECTANGLE||i%2==1){double[] q=handle(roi,i);canvas.drawCircle((float)q[0],(float)q[1],7/scale,p);}}
  canvas.restore();
 }
 static double[] handle(InterestRegion r,int i){return new double[]{i==0||i==6||i==7?r.left:i==2||i==3||i==4?r.right:r.cx(),i<=2?r.top:i>=4&&i<=6?r.bottom:r.cy()};}
}
