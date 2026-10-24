package br.com.pelotavision;
import android.graphics.*;

/** Recorte de desenho do relatorio (Android). A geometria fica em core/ReportViewport. */
final class ReportClip {
    private ReportClip() {}
    static void clip(Canvas c,ReportViewport v,InterestRegion roi){
        c.clipRect(v.left,v.top,v.right,v.bottom);if(v.cropped)c.clipRect((float)roi.left,(float)roi.top,(float)roi.right,(float)roi.bottom);
        if(v.circular){Path p=new Path();p.addOval(new RectF((float)roi.left,(float)roi.top,(float)roi.right,(float)roi.bottom),Path.Direction.CW);c.clipPath(p);}
    }
}
