package br.com.pelotavision;
import android.graphics.*;
/** Shared screen/export markings in upright photo coordinates. */
final class CrackOverlay {
 static void draw(Canvas canvas,PelletDetector.Result pellets,CrackAnalysis cracks,float left,float top,float scale){
  if(cracks==null)return;
  Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
  for(int i=0;i<cracks.entries.size();i++){
   CrackAnalysis.Entry entry=cracks.entries.get(i);PelletDetector.Detection d=pellets.circles.get(i);
   if(entry.status==CrackAnalysis.Status.NO_INDICATION)continue;
   p.setColor(entry.status==CrackAnalysis.Status.POSSIBLE?Color.rgb(255,72,83):Color.rgb(255,190,76));p.setStrokeWidth(Math.max(1.8f,2.2f*scale));
   CircleOverlay.drawEllipse(canvas,d,left,top,scale,p);
   if(entry.status!=CrackAnalysis.Status.POSSIBLE)continue;
   p.setStrokeWidth(Math.max(1.5f,1.8f*scale));
   canvas.save();Path clip=new Path();clip.addOval(new RectF((float)-d.semiMajor*scale,(float)-d.semiMinor*scale,(float)d.semiMajor*scale,(float)d.semiMinor*scale),Path.Direction.CW);Matrix transform=new Matrix();transform.setRotate((float)d.angleDeg);transform.postTranslate(left+(float)d.x*scale,top+(float)d.y*scale);clip.transform(transform);canvas.clipPath(clip);float[] runs=entry.markedRuns();
   for(int n=0;n+3<runs.length;n+=4)canvas.drawLine(left+runs[n]*scale,top+runs[n+1]*scale,left+runs[n+2]*scale,top+runs[n+3]*scale,p);canvas.restore();
  }
 }
}
