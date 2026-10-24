package br.com.pelotavision;
import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Four draggable endpoints and draggable edge segments; pinch zooms the photo, not its scale. */
final class BeltEditorView extends View {
    private final Bitmap photo;
    private BeltCalibration.Edges edges,start;
    private float zoom=1,panX,panY,downX,downY;
    private int handle=-1;private boolean multi;
    private final ScaleGestureDetector pinch;
    private Runnable changed;
    BeltEditorView(Context c,Bitmap p,BeltCalibration.Edges e){super(c);photo=p;edges=e;setBackgroundColor(Color.BLACK);
        pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector d){multi=true;handle=-1;return true;}
            @Override public boolean onScale(ScaleGestureDetector d){float old=scale(),x=(d.getFocusX()-left())/old,y=(d.getFocusY()-top())/old;zoom=Math.max(1,Math.min(6,zoom*d.getScaleFactor()));panX+=d.getFocusX()-(left()+x*scale());panY+=d.getFocusY()-(top()+y*scale());invalidate();return true;}
        });
    }
    void changed(Runnable r){changed=r;}
    BeltCalibration.Edges edges(){return edges;}
    void edges(BeltCalibration.Edges e){edges=e;invalidate();if(changed!=null)changed.run();}
    void resetZoom(){zoom=1;panX=panY=0;invalidate();}
    float scale(){return Math.min(getWidth()/(float)photo.getWidth(),getHeight()/(float)photo.getHeight())*zoom;}
    float left(){return (getWidth()-photo.getWidth()*scale())/2+panX;}float top(){return (getHeight()-photo.getHeight()*scale())/2+panY;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float s=scale();if(s<=0)return;c.drawBitmap(photo,null,new RectF(left(),top(),left()+photo.getWidth()*s,top()+photo.getHeight()*s),null);drawEdges(c,edges,left(),top(),s,true,getResources().getDisplayMetrics().density);}
    static void drawEdges(Canvas c,BeltCalibration.Edges e,float l,float t,float s,boolean handles,float density){
        if(e==null)return;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.rgb(68,221,241));p.setStrokeWidth(Math.max(2,2*density));
        for(int i=0;i<4;i+=2)c.drawLine(l+(float)e.x(i)*s,t+(float)e.y(i)*s,l+(float)e.x(i+1)*s,t+(float)e.y(i+1)*s,p);
        p.setColor(Color.YELLOW);p.setPathEffect(new DashPathEffect(new float[]{8,5},0));double[] dimension=BeltCalibration.crossSection(e);if(dimension!=null)c.drawLine(l+(float)dimension[0]*s,t+(float)dimension[1]*s,l+(float)dimension[2]*s,t+(float)dimension[3]*s,p);p.setPathEffect(null);
        if(handles){p.setColor(Color.WHITE);for(int i=0;i<4;i++)c.drawCircle(l+(float)e.x(i)*s,t+(float)e.y(i)*s,8*density,p);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        pinch.onTouchEvent(e);float s=scale();if(s<=0)return true;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){multi=false;downX=e.getX();downY=e.getY();start=edges;handle=-1;double best=30*getResources().getDisplayMetrics().density/s,x=(e.getX()-left())/s,y=(e.getY()-top())/s;
            for(int i=0;i<4;i++){double d=Math.hypot(x-edges.x(i),y-edges.y(i));if(d<best){best=d;handle=i;}}
            if(handle<0)for(int i=0;i<4;i+=2){double dx=edges.x(i+1)-edges.x(i),dy=edges.y(i+1)-edges.y(i),u=Math.max(0,Math.min(1,((x-edges.x(i))*dx+(y-edges.y(i))*dy)/(dx*dx+dy*dy))),d=Math.hypot(x-edges.x(i)-u*dx,y-edges.y(i)-u*dy);if(d<best){best=d;handle=4+i/2;}}
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        }else if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){multi=true;handle=-1;}
        else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!pinch.isInProgress()){
            if(!multi&&handle>=0){edges=start.moved(handle,(e.getX()-downX)/s,(e.getY()-downY)/s,photo.getWidth(),photo.getHeight());if(changed!=null)changed.run();}
            else if(!multi&&zoom>1){panX+=e.getX()-downX;panY+=e.getY()-downY;downX=e.getX();downY=e.getY();}
            invalidate();
        }else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){handle=-1;multi=false;performClick();}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
