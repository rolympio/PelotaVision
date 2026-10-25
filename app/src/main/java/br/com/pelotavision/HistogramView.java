package br.com.pelotavision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Locale;

/**
 * Same renderer for on-screen chart and exported photo footer.
 * Without the cracked series it draws exactly the original chart (percent by number, same classes).
 * With it, both series share classes and axes and are overlaid with transparency (never side by side,
 * never stacked): the cracked pellets are a subset of the total.
 */
final class HistogramView extends View {
    static final int ALL_COLOR = Color.rgb(38, 140, 171), CRACKED_COLOR = Color.rgb(255, 140, 26), ORIGINAL_COLOR = Color.rgb(82, 216, 190);
    static final float ALL_ALPHA = .55f, CRACKED_ALPHA = .65f;
    private Granulometry.Distribution data;
    private CrackHistogram cracks;
    private ReportVisuals visuals = ReportVisuals.defaults();

    HistogramView(Context context){super(context);setContentDescription("Histograma da distribuição por número de pelotas");}
    void display(Granulometry.Distribution value){display(value,null,visuals);}
    void display(Granulometry.Distribution value,CrackHistogram cracked,ReportVisuals options){data=value;cracks=cracked;if(options!=null)visuals=options;invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(data!=null)draw(canvas,new RectF(0,0,getWidth(),getHeight()),data,cracks,visuals);}

    static void draw(Canvas c,RectF box,Granulometry.Distribution d){draw(c,box,d,null,null);}

    static void draw(Canvas c,RectF box,Granulometry.Distribution d,CrackHistogram h,ReportVisuals v) {
        if(v==null)v=ReportVisuals.defaults();
        boolean second=h!=null&&v.showCracked;
        boolean showAll=v.showAll||!second;
        // Conditional normalization only exists for the cracked series; the total alone is then shown in % (original chart).
        CrackHistogram.Normalization n=second||v.normalization!=CrackHistogram.Normalization.CONDITIONAL?v.normalization:CrackHistogram.Normalization.PERCENT_OF_ALL;
        int saved=c.save();c.clipRect(box);c.translate(box.left,box.top);
        float width=box.width(),height=box.height(),s=Math.min(width/380f,height/216f);
        String unit=d.settings.unit();
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.rgb(16,26,35));c.drawRect(0,0,width,height,p);
        float left=44*s,right=width-14*s,top=(second?104:90)*s,bottom=height-46*s;
        p.setColor(Color.WHITE);p.setTextSize(14*s);p.setFakeBoldText(true);
        c.drawText(second?"Distribuição: todas × trincadas":"Distribuição por número de pelotas",12*s,21*s,p);p.setFakeBoldText(false);
        p.setTextSize(11*s);p.setColor(Color.rgb(181,198,208));
        c.drawText(String.format(Locale.getDefault(),"n=%d · excluídas=%d · classe=%s %s",d.included,d.excluded,number(d.settings.binWidthMm),unit),12*s,39*s,p);
        String stats=d.included==0?"Nenhuma região incluída no histograma":String.format(Locale.getDefault(),"D10 %s  |  D50 %s  |  D90 %s %s",number(d.d10),number(d.d50),number(d.d90),unit);
        c.drawText(stats,12*s,56*s,p);
        if(second){
            p.setTextSize(10*s);
            c.drawText(indicators(h),12*s,71*s,p);
            float lx=12*s,ly=86*s,sq=8*s;
            if(showAll){p.setColor(withAlpha(ALL_COLOR,ALL_ALPHA));c.drawRect(lx,ly-sq,lx+sq,ly,p);p.setColor(Color.rgb(181,198,208));c.drawText("Todas as pelotas",lx+sq+4*s,ly,p);lx+=p.measureText("Todas as pelotas")+sq+16*s;}
            p.setColor(withAlpha(CRACKED_COLOR,CRACKED_ALPHA));c.drawRect(lx,ly-sq,lx+sq,ly,p);p.setColor(h.available?Color.rgb(181,198,208):Color.rgb(255,170,120));
            c.drawText(h.available?(n==CrackHistogram.Normalization.CONDITIONAL?"Trincadas (distribuição condicional)":"Pelotas trincadas"):"Trincadas: "+h.unavailableReason,lx+sq+4*s,ly,p);
            p.setTextSize(11*s);
        }else if(d.included>0)c.drawText("Média "+number(d.meanMm)+" "+unit+" · faixa "+number(d.minMm)+"–"+number(d.maxMm)+" "+unit,12*s,71*s,p);
        if(d.binCount()==0){c.restoreToCount(saved);return;}
        double max=0;
        for(int i=0;i<d.binCount();i++){
            double all=h!=null?h.allValue(i,n):n==CrackHistogram.Normalization.ABSOLUTE?d.count(i):d.percent(i);
            if(showAll)max=Math.max(max,all);
            if(second&&h.available)max=Math.max(max,h.crackedValue(i,n));
        }
        double yMax=n==CrackHistogram.Normalization.ABSOLUTE?niceCount(max):Math.min(100,Math.max(10,Math.ceil(max/10)*10));
        p.setTextSize(9*s);p.setStrokeWidth(s);
        for(int tick=0;tick<=4;tick++) {
            float y=bottom-(bottom-top)*tick/4f;p.setColor(Color.rgb(49,68,81));c.drawLine(left,y,right,y,p);
            p.setColor(Color.rgb(181,198,208));p.setTextAlign(Paint.Align.RIGHT);c.drawText(number(yMax*tick/4),left-5*s,y+3*s,p);
        }
        p.setTextAlign(Paint.Align.LEFT);p.setColor(Color.rgb(181,198,208));
        c.drawText(n==CrackHistogram.Normalization.ABSOLUTE?"n":"%",12*s,top+4*s,p);
        if(second&&n!=CrackHistogram.Normalization.PERCENT_OF_ALL){p.setTextSize(8*s);c.drawText(CrackHistogram.axisLabel(n),12*s,top-6*s,p);p.setTextSize(9*s);}
        float step=(right-left)/d.binCount(),gap=Math.min(s,step*.12f);
        Paint value=new Paint(Paint.ANTI_ALIAS_FLAG);value.setTextSize(Math.min(9*s,step*.45f));value.setTextAlign(Paint.Align.CENTER);
        boolean labels=v.showValues&&step>=10*s;
        for(int i=0;i<d.binCount();i++) {
            float x=left+i*step;
            if(showAll){
                double all=h!=null?h.allValue(i,n):n==CrackHistogram.Normalization.ABSOLUTE?d.count(i):d.percent(i);
                float y=(float)(bottom-(bottom-top)*Math.min(1,all/yMax));
                p.setColor(second?withAlpha(ALL_COLOR,ALL_ALPHA):ORIGINAL_COLOR);c.drawRect(x+gap,y,x+step-gap,bottom,p);
                if(labels&&all>0){value.setColor(Color.WHITE);c.drawText(compact(all,n),x+step/2,y-2*s,value);}
            }
            if(second&&h.available){
                double cr=h.crackedValue(i,n);
                float y=(float)(bottom-(bottom-top)*Math.min(1,cr/yMax));
                // drawn after the total and narrower, so both stay distinguishable without being side by side
                float inset=Math.min(step*.18f,4*s);
                p.setColor(withAlpha(CRACKED_COLOR,CRACKED_ALPHA));if(cr>0)c.drawRect(x+gap+inset,y,x+step-gap-inset,bottom,p);
                if(labels&&cr>0){value.setColor(Color.rgb(255,200,150));c.drawText(compact(cr,n),x+step/2,Math.min(bottom-2*s,y+value.getTextSize()+1*s),value);}
            }
        }
        p.setColor(Color.rgb(181,198,208));p.setTextAlign(Paint.Align.CENTER);
        int stride=Math.max(1,(int)Math.ceil(d.binCount()/8.));
        float previousRight=-Float.MAX_VALUE;
        for(int boundary=0;boundary<=d.binCount();boundary++) {
            if(boundary%stride!=0&&boundary!=d.binCount())continue;
            float x=left+boundary*step;String label=boundaryLabel(d,boundary);float half=p.measureText(label)/2;
            if(x-half<previousRight+4*s&&boundary!=d.binCount())continue;
            if(boundary<d.binCount()&&x+half>right-p.measureText(boundaryLabel(d,d.binCount()))/2-4*s)continue;
            c.drawText(label,x,bottom+13*s,p);previousRight=x+half;
        }
        c.drawText("Diâmetro projetado estimado ("+unit+")",width/2,height-17*s,p);
        p.setTextSize(8*s);p.setColor(Color.rgb(151,174,187));
        String source=d.settings.estimated()?"ESTIMADA pela média":"original";
        c.drawText(d.settings.pixelsOnly()?"Sem escala física · diâmetros em pixels · [a,b); última [a,b]":"Escala "+number(d.settings.originalPixelsPerMm)+" px/mm · "+source+" · [a,b); última [a,b]",width/2,height-4*s,p);
        c.restoreToCount(saved);
    }

    /** Total, cracked, % among evaluable, uncertain. Never "0 trincadas" when classification is unavailable. */
    static String indicators(CrackHistogram h){
        if(!h.available)return String.format(Locale.getDefault(),"Pelotas: %d · trincadas: classificação indisponível · incertas: %d",h.total,h.uncertainTotal);
        return String.format(Locale.getDefault(),"Pelotas: %d · trincadas: %d (%s%% das %d avaliáveis) · incertas: %d",h.total,h.crackedTotal,number(h.crackedPercentOfEvaluable()),h.evaluable(),h.uncertainTotal);
    }
    static int withAlpha(int color,float alpha){return Color.argb(Math.round(alpha*255),Color.red(color),Color.green(color),Color.blue(color));}
    private static double niceCount(double max){if(max<=4)return 4;double step=Math.pow(10,Math.floor(Math.log10(max/4)));for(double m:new double[]{1,2,2.5,5,10})if(m*step*4>=max)return m*step*4;return Math.ceil(max);}
    private static String compact(double v,CrackHistogram.Normalization n){return n==CrackHistogram.Normalization.ABSOLUTE?String.format(Locale.getDefault(),"%d",Math.round(v)):v>=10?String.format(Locale.getDefault(),"%.0f",v):String.format(Locale.getDefault(),"%.1f",v);}
    private static String boundaryLabel(Granulometry.Distribution d,int index) {
        double value=d.lower(index),bin=d.settings.binWidthMm;
        int digits=Math.min(8,Math.max(0,(int)Math.ceil(-Math.log10(bin))+1));
        if(bin<1e-8)return String.format(Locale.getDefault(),"%.4g",value);
        return String.format(Locale.getDefault(),"%."+digits+"f",value).replaceAll("([.,]\\d*?)0+$","$1").replaceAll("[.,]$","");
    }
    static String number(double value) {
        if(!Double.isFinite(value))return "—";
        if(Math.abs(value)>=1000)return String.format(Locale.getDefault(),"%.0f",value);
        if(Math.abs(value)>0&&Math.abs(value)<.01)return String.format(Locale.getDefault(),"%.2g",value);
        return String.format(Locale.getDefault(),"%.2f",value).replaceAll("([.,]\\d*?)0+$","$1").replaceAll("[.,]$","");
    }
}
