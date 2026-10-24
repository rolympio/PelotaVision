package br.com.pelotavision;
import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Inspection zoom. The saved report always shows the whole selected ROI. */
final class RoiZoomView extends View {
    private final Bitmap photo;private final PelletDetector.Result result;private final CrackAnalysis cracks;private final InterestRegion roi;
    private final ObjectCalibration.Reference reference;
    private float zoom=1,panX,panY,lastX,lastY;private boolean multi;
    private final ScaleGestureDetector pinch;
    RoiZoomView(Context c,Bitmap photo,PelletDetector.Result result,CrackAnalysis cracks,InterestRegion roi,ObjectCalibration.Reference reference){super(c);this.photo=photo;this.result=result;this.cracks=cracks;this.roi=roi;this.reference=reference;setBackgroundColor(Color.BLACK);
        pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector d){multi=true;return true;}
            @Override public boolean onScale(ScaleGestureDetector d){float old=scale(),x=(d.getFocusX()-left())/old,y=(d.getFocusY()-top())/old;zoom=Math.max(1,Math.min(6,zoom*d.getScaleFactor()));panX+=d.getFocusX()-(left()+x*scale());panY+=d.getFocusY()-(top()+y*scale());invalidate();return true;}
        });
    }
    void reset(){zoom=1;panX=panY=0;invalidate();}
    float scale(){return (float)Math.min(getWidth()/roi.width(),getHeight()/roi.height())*zoom;}
    float left(){return (getWidth()-(float)roi.width()*scale())/2-(float)roi.left*scale()+panX;}
    float top(){return (getHeight()-(float)roi.height()*scale())/2-(float)roi.top*scale()+panY;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float s=scale();if(s<=0)return;c.save();c.translate(left(),top());c.scale(s,s);ReportClip.clip(c,new ReportViewport(photo.getWidth(),photo.getHeight(),roi,true),roi);c.drawBitmap(photo,0,0,null);Paint a=new Paint(3),b=new Paint(3),o=new Paint(3);a.setStrokeWidth(Math.max(1.2f,2/s));CircleOverlay.draw(c,result,0,0,1,true,a,b,o);CrackOverlay.draw(c,result,cracks,0,0,1);ReferenceOverlay.draw(c,reference,0,0,1);c.restore();}
    @Override public boolean onTouchEvent(MotionEvent e){pinch.onTouchEvent(e);switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:multi=false;lastX=e.getX();lastY=e.getY();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);break;
        case MotionEvent.ACTION_POINTER_DOWN:multi=true;break;
        case MotionEvent.ACTION_MOVE:if(!multi&&!pinch.isInProgress()){panX+=e.getX()-lastX;panY+=e.getY()-lastY;lastX=e.getX();lastY=e.getY();invalidate();}break;
        case MotionEvent.ACTION_UP:multi=false;performClick();break;
    }return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
