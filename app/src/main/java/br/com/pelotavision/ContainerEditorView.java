package br.com.pelotavision;
import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Container rim circle: drag the circle line or its four handles to resize, the center cross to move; pinch zooms the photo, not its scale. */
final class ContainerEditorView extends View {
    private final Bitmap photo;
    private ContainerCalibration.Circle circle,start,other;
    private float zoom=1,panX,panY,downX,downY;
    private int handle=-1;private boolean multi;
    private final ScaleGestureDetector pinch;
    private Runnable changed;
    ContainerEditorView(Context c,Bitmap p,ContainerCalibration.Circle initial){super(c);photo=p;circle=initial;setBackgroundColor(Color.BLACK);
        pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector d){multi=true;handle=-1;return true;}
            @Override public boolean onScale(ScaleGestureDetector d){float old=scale(),x=(d.getFocusX()-left())/old,y=(d.getFocusY()-top())/old;zoom=Math.max(1,Math.min(8,zoom*d.getScaleFactor()));panX+=d.getFocusX()-(left()+x*scale());panY+=d.getFocusY()-(top()+y*scale());invalidate();return true;}
        });
    }
    void changed(Runnable r){changed=r;}
    ContainerCalibration.Circle circle(){return circle;}
    /** Active circle and, dashed, the circle of the other plane for comparison. */
    void circles(ContainerCalibration.Circle active,ContainerCalibration.Circle reference){circle=active;other=reference;invalidate();if(changed!=null)changed.run();}
    void resetZoom(){zoom=1;panX=panY=0;invalidate();}
    float scale(){return Math.min(getWidth()/(float)photo.getWidth(),getHeight()/(float)photo.getHeight())*zoom;}
    float left(){return (getWidth()-photo.getWidth()*scale())/2+panX;}float top(){return (getHeight()-photo.getHeight()*scale())/2+panY;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float s=scale();if(s<=0)return;c.drawBitmap(photo,null,new RectF(left(),top(),left()+photo.getWidth()*s,top()+photo.getHeight()*s),null);drawCircles(c,circle,other,left(),top(),s,true,getResources().getDisplayMetrics().density);}
    static void drawCircles(Canvas c,ContainerCalibration.Circle active,ContainerCalibration.Circle reference,float l,float t,float s,boolean handles,float density){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(2,2*density));
        if(reference!=null){p.setColor(Color.argb(200,190,190,190));p.setPathEffect(new DashPathEffect(new float[]{10,7},0));c.drawCircle(l+(float)reference.cx*s,t+(float)reference.cy*s,(float)reference.r*s,p);p.setPathEffect(null);}
        if(active==null)return;
        p.setColor(Color.YELLOW);c.drawCircle(l+(float)active.cx*s,t+(float)active.cy*s,(float)active.r*s,p);
        p.setPathEffect(new DashPathEffect(new float[]{8,5},0));c.drawLine(l+(float)active.handleX(2)*s,t+(float)active.cy*s,l+(float)active.handleX(0)*s,t+(float)active.cy*s,p);p.setPathEffect(null);
        if(handles){float cx=l+(float)active.cx*s,cy=t+(float)active.cy*s,arm=10*density;c.drawLine(cx-arm,cy,cx+arm,cy,p);c.drawLine(cx,cy-arm,cx,cy+arm,p);
            p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);for(int i=0;i<4;i++)c.drawCircle(l+(float)active.handleX(i)*s,t+(float)active.handleY(i)*s,8*density,p);}
    }
    /** -1 = none, 0 = move (center), 1 = resize (handle or circle line). */
    static int hit(ContainerCalibration.Circle e,double x,double y,double radius){
        if(Math.hypot(x-e.cx,y-e.cy)<radius*1.2)return 0;
        for(int i=0;i<4;i++)if(Math.hypot(x-e.handleX(i),y-e.handleY(i))<radius)return 1;
        return Math.abs(Math.hypot(x-e.cx,y-e.cy)-e.r)<radius*.7?1:-1;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        pinch.onTouchEvent(e);float s=scale();if(s<=0)return true;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){multi=false;downX=e.getX();downY=e.getY();start=circle;
            handle=hit(circle,(e.getX()-left())/s,(e.getY()-top())/s,30*getResources().getDisplayMetrics().density/s);
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        }else if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){multi=true;handle=-1;}
        else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!pinch.isInProgress()){
            if(!multi&&handle==0){circle=start.moved((e.getX()-downX)/s,(e.getY()-downY)/s,photo.getWidth(),photo.getHeight());if(changed!=null)changed.run();}
            else if(!multi&&handle==1){circle=start.resizedTo((e.getX()-left())/s,(e.getY()-top())/s,8,photo.getWidth(),photo.getHeight());if(changed!=null)changed.run();}
            else if(!multi&&zoom>1){panX+=e.getX()-downX;panY+=e.getY()-downY;downX=e.getX();downY=e.getY();}
            invalidate();
        }else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){handle=-1;multi=false;performClick();}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
