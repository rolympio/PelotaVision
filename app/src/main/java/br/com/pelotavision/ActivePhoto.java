package br.com.pelotavision;
import android.graphics.*;
/** Display crop only; retained source pixels and calibration coordinates never change. */
final class ActivePhoto {
 private ActivePhoto(){}
 static Bitmap render(Bitmap full,AnalysisWindow window){
  if(window.region==null)return full;
  Bitmap output=Bitmap.createBitmap(window.width,window.height,Bitmap.Config.ARGB_8888);output.eraseColor(Color.rgb(16,26,35));Canvas c=new Canvas(output);
  c.translate(-window.left,-window.top);InterestRegion r=window.region;
  if(r.shape==InterestRegion.Shape.CIRCLE){Path p=new Path();p.addCircle((float)r.cx(),(float)r.cy(),(float)r.width()/2,Path.Direction.CW);c.clipPath(p);}else c.clipRect((float)r.left,(float)r.top,(float)r.right,(float)r.bottom);
  c.drawBitmap(full,0,0,null);return output;
 }
}
