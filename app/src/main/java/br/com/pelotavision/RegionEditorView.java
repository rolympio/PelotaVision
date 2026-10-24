package br.com.pelotavision;
import android.content.Context;
import android.graphics.*;
import android.view.*;
/** Photo-only editor: screen/photo mapping honors FIT_CENTER letterboxing. */
final class RegionEditorView extends View {
 private final Bitmap photo;private InterestRegion region,start;private int handle=-2;private float downX,downY;
 private final ScaleGestureDetector pinch;private boolean pinching=false;
 RegionEditorView(Context c,Bitmap photo,InterestRegion roi){super(c);this.photo=photo;region=roi==null?InterestRegion.initial(InterestRegion.Shape.RECTANGLE,photo.getWidth(),photo.getHeight()):roi;
  pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScaleBegin(ScaleGestureDetector d){pinching=true;return true;}@Override public boolean onScale(ScaleGestureDetector d){region=region.scaled(d.getScaleFactor(),photo.getWidth(),photo.getHeight(),minimum());invalidate();return true;}});setBackgroundColor(Color.BLACK);
 }
 InterestRegion region(){return region;}
 void shape(InterestRegion.Shape shape){if(region.shape!=shape){double size=Math.min(region.width(),region.height());region=shape==InterestRegion.Shape.CIRCLE?new InterestRegion(shape,region.cx()-size/2,region.cy()-size/2,region.cx()+size/2,region.cy()+size/2):new InterestRegion(shape,region.left,region.top,region.right,region.bottom);invalidate();}}
 void reset(){region=InterestRegion.initial(region.shape,photo.getWidth(),photo.getHeight());invalidate();}
 float scale(){return Math.min(getWidth()/(float)photo.getWidth(),getHeight()/(float)photo.getHeight());}
 private double minimum(){return Math.min(Math.min(photo.getWidth(),photo.getHeight())*.2,Math.max(12,40*getResources().getDisplayMetrics().density/Math.max(.01,scale())));}
 float left(){return (getWidth()-photo.getWidth()*scale())/2;}float top(){return (getHeight()-photo.getHeight()*scale())/2;}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float s=scale();if(s<=0)return;canvas.drawBitmap(photo,null,new RectF(left(),top(),left()+photo.getWidth()*s,top()+photo.getHeight()*s),null);RegionOverlay.draw(canvas,region,left(),top(),s,photo.getWidth(),photo.getHeight(),false);
  Paint handlePaint=new Paint(Paint.ANTI_ALIAS_FLAG);handlePaint.setColor(Color.WHITE);float radius=7*getResources().getDisplayMetrics().density;
  for(int i=0;i<8;i++)if(region.shape==InterestRegion.Shape.RECTANGLE||i%2==1){double[] p=RegionOverlay.handle(region,i);canvas.drawCircle(left()+(float)p[0]*s,top()+(float)p[1]*s,radius,handlePaint);}
 }
 @Override public boolean onTouchEvent(MotionEvent event){
  pinch.onTouchEvent(event);float s=scale();if(s<=0)return true;
  double x=(event.getX()-left())/s,y=(event.getY()-top())/s;
  if(event.getActionMasked()==MotionEvent.ACTION_DOWN){pinching=false;start=region;downX=event.getX();downY=event.getY();handle=-2;double best=28*getResources().getDisplayMetrics().density/s;
   for(int i=0;i<8;i++)if(region.shape==InterestRegion.Shape.RECTANGLE||i%2==1){double[] p=RegionOverlay.handle(region,i);double distance=Math.hypot(p[0]-x,p[1]-y);if(distance<best){best=distance;handle=i;}}
   if(handle==-2&&region.contains(x,y))handle=-1;
   if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
  }else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!pinching&&start!=null&&handle>=-1){
   double dx=(event.getX()-downX)/s,dy=(event.getY()-downY)/s;region=handle==-1?start.moved(dx,dy,photo.getWidth(),photo.getHeight()):start.resized(handle,dx,dy,photo.getWidth(),photo.getHeight(),minimum());invalidate();
  }else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){handle=-2;start=null;pinching=false;performClick();}
  return true;
 }
 @Override public boolean performClick(){super.performClick();return true;}
}
