package br.com.pelotavision;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class CircleOverlay extends View {
    private PelletDetector.Result result;
    private CrackAnalysis cracks;
    private InterestRegion region;
    private ObjectCalibration.Reference reference;
    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG),outline=new Paint(Paint.ANTI_ALIAS_FLAG);
    private AnalysisWindow viewport;
    void viewport(AnalysisWindow value){viewport=value;invalidate();}
    private float viewScale(){return result==null?0:Math.min(getWidth()/(float)(viewport==null?result.width:viewport.width),getHeight()/(float)(viewport==null?result.height:viewport.height));}
    double[] toPhoto(float vx,float vy){
        float s=viewScale();if(s<=0)return null;int w=viewport==null?result.width:viewport.width,h=viewport==null?result.height:viewport.height;
        double x=(vx-(getWidth()-w*s)/2)/s,y=(vy-(getHeight()-h*s)/2)/s;if(x<0||y<0||x>w||y>h)return null;
        x+=viewport==null?0:viewport.left;y+=viewport==null?0:viewport.top;if(viewport!=null&&viewport.region!=null&&!viewport.region.contains(x,y))return null;return new double[]{x,y};
    }
    private boolean numbered=true;
    private long displayedAt=0;
    CircleOverlay(Context context){super(context);setWillNotDraw(false);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(2.2f*getResources().getDisplayMetrics().density);}
    void display(PelletDetector.Result result,boolean numbered){this.result=result;this.numbered=numbered;displayedAt=android.os.SystemClock.elapsedRealtime();invalidate();}
    void cracks(CrackAnalysis value){cracks=value;invalidate();}
    void reference(ObjectCalibration.Reference value){reference=value;invalidate();}
    void region(InterestRegion value){region=value;invalidate();}
    void clear(){viewport=null;result=null;cracks=null;region=null;reference=null;invalidate();}
    void expire(long now){if(result!=null&&now-displayedAt>1800)clear();}
    static int color(PelletDetector.Detection d){return d.partial?Color.rgb(255,171,70):d.needsReview?Color.rgb(255,224,88):Color.rgb(83,235,143);}
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);if(result==null)return;
        float scale=viewScale();int w=viewport==null?result.width:viewport.width,h=viewport==null?result.height:viewport.height;
        float left=(getWidth()-w*scale)/2-(viewport==null?0:viewport.left*scale),top=(getHeight()-h*scale)/2-(viewport==null?0:viewport.top*scale);
        canvas.save();canvas.clipRect((getWidth()-w*scale)/2,(getHeight()-h*scale)/2,(getWidth()+w*scale)/2,(getHeight()+h*scale)/2);
        if(viewport!=null&&viewport.region!=null&&viewport.region.shape==InterestRegion.Shape.CIRCLE){InterestRegion r=viewport.region;android.graphics.Path p=new android.graphics.Path();p.addCircle(left+(float)r.cx()*scale,top+(float)r.cy()*scale,(float)r.width()*scale/2,android.graphics.Path.Direction.CW);canvas.clipPath(p);}
        draw(canvas,result,left,top,scale,numbered,stroke,text,outline);
        CrackOverlay.draw(canvas,result,cracks,left,top,scale);
        RegionOverlay.draw(canvas,region,left,top,scale,result.width,result.height,false);
        ReferenceOverlay.draw(canvas,reference,left,top,scale);canvas.restore();
    }
    static void drawEllipse(Canvas canvas,PelletDetector.Detection d,float left,float top,float scale,Paint p){
        float x=left+(float)d.x*scale,y=top+(float)d.y*scale,a=(float)d.semiMajor*scale,b=(float)d.semiMinor*scale;
        android.graphics.PathEffect previous=p.getPathEffect();if(!d.ellipseFitted)p.setPathEffect(new android.graphics.DashPathEffect(new float[]{6,4},0));
        canvas.save();canvas.rotate((float)d.angleDeg,x,y);canvas.drawOval(new RectF(x-a,y-b,x+a,y+b),p);canvas.restore();p.setPathEffect(previous);
    }
    static void draw(Canvas canvas,PelletDetector.Result result,float left,float top,float scale,
                     boolean numbered,Paint stroke,Paint text,Paint outline) {
        stroke.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);text.setStyle(Paint.Style.FILL);text.setTextAlign(Paint.Align.CENTER);
        outline.setColor(Color.BLACK);outline.setStyle(Paint.Style.STROKE);outline.setTextAlign(Paint.Align.CENTER);outline.setStrokeWidth(Math.max(2,stroke.getStrokeWidth()));
        int id=0;for(PelletDetector.Detection d:result.circles){
            id++;float x=left+(float)d.x*scale,y=top+(float)d.y*scale,r=(float)d.radius*scale;
            stroke.setColor(color(d));drawEllipse(canvas,d,left,top,scale,stroke);
            if(numbered){float size=Math.max(11,Math.min(28,r*.45f));text.setTextSize(size);outline.setTextSize(size);
                float base=y-(text.ascent()+text.descent())/2;String label=Integer.toString(id);
                canvas.drawText(label,x,base,outline);canvas.drawText(label,x,base,text);}
        }
    }
}
