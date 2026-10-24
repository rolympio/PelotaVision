package br.com.pelotavision;
import android.graphics.*;

/** Report crop transform only. Analysis coordinates and physical scale never change. */
final class ReportViewport {
    final int left,top,right,bottom,width,height;final float scale;
    final boolean cropped,circular;
    ReportViewport(int w,int h,InterestRegion roi,boolean crop){
        cropped=crop&&roi!=null;circular=cropped&&roi.shape==InterestRegion.Shape.CIRCLE;
        left=cropped?Math.max(0,(int)Math.floor(roi.left)):0;top=cropped?Math.max(0,(int)Math.floor(roi.top)):0;
        right=cropped?Math.min(w,(int)Math.ceil(roi.right)):w;bottom=cropped?Math.min(h,(int)Math.ceil(roi.bottom)):h;
        if(right<=left||bottom<=top)throw new IllegalArgumentException("ROI fora da foto.");
        int target=cropped?Math.min(1600,Math.max(380,w)):w;
        // Avoid unbounded allocation for a tall, narrow crop. Never invent resolution beyond this display zoom.
        double factor=cropped?Math.min((double)target/(right-left),4096./(bottom-top)):1;
        width=Math.max(1,(int)Math.round((right-left)*factor));scale=width/(float)(right-left);height=Math.max(1,Math.round((bottom-top)*scale));
    }
    void clip(Canvas c,InterestRegion roi){
        c.clipRect(left,top,right,bottom);if(cropped)c.clipRect((float)roi.left,(float)roi.top,(float)roi.right,(float)roi.bottom);
        if(circular){Path p=new Path();p.addOval(new RectF((float)roi.left,(float)roi.top,(float)roi.right,(float)roi.bottom),Path.Direction.CW);c.clipPath(p);}
    }
}
