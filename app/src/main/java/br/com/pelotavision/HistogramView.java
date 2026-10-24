package br.com.pelotavision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Locale;

/** Same renderer for on-screen chart and exported photo footer. */
final class HistogramView extends View {
    private Granulometry.Distribution data;
    HistogramView(Context context){super(context);setContentDescription("Histograma da distribuição por número de pelotas");}
    void display(Granulometry.Distribution value){data=value;invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(data!=null)draw(canvas,new RectF(0,0,getWidth(),getHeight()),data);}
    static void draw(Canvas c,RectF box,Granulometry.Distribution d) {
        int saved=c.save();c.clipRect(box);c.translate(box.left,box.top);
        float width=box.width(),height=box.height(),s=Math.min(width/380f,height/216f);
        String unit=d.settings.unit();
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.rgb(16,26,35));c.drawRect(0,0,width,height,p);
        float left=44*s,right=width-14*s,top=90*s,bottom=height-46*s;
        p.setColor(Color.WHITE);p.setTextSize(14*s);p.setFakeBoldText(true);
        c.drawText("Distribuição por número de pelotas",12*s,21*s,p);p.setFakeBoldText(false);
        p.setTextSize(11*s);p.setColor(Color.rgb(181,198,208));
        c.drawText(String.format(Locale.getDefault(),"n=%d · excluídas=%d · classe=%s %s",d.included,d.excluded,number(d.settings.binWidthMm),unit),12*s,39*s,p);
        String stats=d.included==0?"Nenhuma região incluída no histograma":String.format(Locale.getDefault(),"D10 %s  |  D50 %s  |  D90 %s %s",number(d.d10),number(d.d50),number(d.d90),unit);
        c.drawText(stats,12*s,56*s,p);
        if(d.included>0)c.drawText("Média "+number(d.meanMm)+" "+unit+" · faixa "+number(d.minMm)+"–"+number(d.maxMm)+" "+unit,12*s,71*s,p);
        if(d.binCount()==0){c.restoreToCount(saved);return;}
        double max=0;for(int i=0;i<d.binCount();i++)max=Math.max(max,d.percent(i));
        double yMax=Math.min(100,Math.max(10,Math.ceil(max/10)*10));
        p.setTextSize(9*s);p.setStrokeWidth(s);
        for(int tick=0;tick<=4;tick++) {
            float y=bottom-(bottom-top)*tick/4f;p.setColor(Color.rgb(49,68,81));c.drawLine(left,y,right,y,p);
            p.setColor(Color.rgb(181,198,208));p.setTextAlign(Paint.Align.RIGHT);c.drawText(number(yMax*tick/4),left-5*s,y+3*s,p);
        }
        p.setTextAlign(Paint.Align.LEFT);p.setColor(Color.rgb(181,198,208));c.drawText("%",12*s,top+4*s,p);
        float step=(right-left)/d.binCount();p.setColor(Color.rgb(82,216,190));
        for(int i=0;i<d.binCount();i++) {
            float x=left+i*step,y=(float)(bottom-(bottom-top)*d.percent(i)/yMax);
            c.drawRect(x+Math.min(s,step*.12f),y,x+step-Math.min(s,step*.12f),bottom,p);
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
