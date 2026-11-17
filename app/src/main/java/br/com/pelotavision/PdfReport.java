package br.com.pelotavision;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório PDF, montado a partir do MESMO JSON gravado ao lado, para que PDF e dados nunca divirjam.
 * A4 retrato (595 x 842 pt), android.graphics.pdf.PdfDocument.
 * 0.18.0: layout de laudo da Planta Piloto decidido no núcleo ({@link ReportLayout}); aqui só se executam
 * as operações de desenho, as mesmas que o Windows executa com PDFBox. O texto usa a fonte do aparelho
 * com a largura ajustada à Helvetica do layout (textScaleX), para as quebras coincidirem com o Windows.
 */
final class PdfReport {
    private PdfReport() {}
    static final int W=595,H=842,M=40;

    /** Destino das páginas: PdfDocument no aparelho; bitmaps nos testes (o Robolectric não implementa PdfDocument). */
    interface Sink { Canvas begin(int number); void end(); }

    /** 0.15.2: uma imagem do relatório, com título e legenda próprios. A figura não é dona do bitmap. */
    static final class Figure {
        final Bitmap image;final String title,caption;
        /** 0.15.3: ocupa até a página inteira (mantido por compatibilidade; o layout novo desenha os gráficos em vetor). */
        final boolean fullPage;
        /** 0.18.0: papel no layout (null: figura sem lugar no laudo). */
        final ReportLayout.Role role;
        Figure(Bitmap image,String title,String caption){this(image,title,caption,false,null);}
        Figure(Bitmap image,String title,String caption,boolean fullPage){this(image,title,caption,fullPage,null);}
        Figure(Bitmap image,String title,String caption,boolean fullPage,ReportLayout.Role role){this.image=image;this.title=title;this.caption=caption;this.fullPage=fullPage;this.role=role;}
        static Figure fullPage(Bitmap image,ReportPdfContent.FigureText t){return new Figure(image,t.title,t.caption,true,t.role);}
    }

    static byte[] render(JSONObject json,List<Figure> figures) throws Exception {
        final PdfDocument doc=new PdfDocument();
        try {
            draw(new Sink(){PdfDocument.Page page;
                public Canvas begin(int n){page=doc.startPage(new PdfDocument.PageInfo.Builder(W,H,n).create());return page.getCanvas();}
                public void end(){doc.finishPage(page);page=null;}},json,figures);
            ByteArrayOutputStream out=new ByteArrayOutputStream();doc.writeTo(out);return out.toByteArray();
        } finally {doc.close();}
    }
    /** Compatibilidade: separa o composto _resultado.png (original, segmentada, resumo + histograma) e acrescenta o mapa. */
    static byte[] render(JSONObject json,Bitmap annotated,Bitmap map) throws Exception {
        List<Bitmap> owned=new ArrayList<>();
        try{return render(json,figures(json,annotated,null,map,owned));}finally{for(Bitmap b:owned)b.recycle();}
    }
    /** Desenha todas as páginas no destino; devolve o número de páginas. */
    static int draw(Sink sink,JSONObject json,Bitmap annotated,Bitmap map) throws Exception {
        List<Bitmap> owned=new ArrayList<>();
        try{return draw(sink,json,figures(json,annotated,null,map,owned));}finally{for(Bitmap b:owned)b.recycle();}
    }
    static int draw(Sink sink,JSONObject json,List<Figure> figures) throws Exception {
        Map<ReportLayout.Role,Bitmap> images=new EnumMap<>(ReportLayout.Role.class);
        if(figures!=null)for(Figure f:figures)if(f!=null&&f.role!=null&&f.image!=null&&!f.image.isRecycled()&&!images.containsKey(f.role))images.put(f.role,f.image);
        Map<ReportLayout.Role,int[]> sizes=new EnumMap<>(ReportLayout.Role.class);
        for(Map.Entry<ReportLayout.Role,Bitmap> e:images.entrySet())sizes.put(e.getKey(),new int[]{e.getValue().getWidth(),e.getValue().getHeight()});
        List<ReportLayout.Page> pages=ReportLayout.build(json,sizes);
        for(ReportLayout.Page p:pages){
            Canvas c=sink.begin(p.number);
            try{for(ReportLayout.Op o:p.ops)draw(c,o,images);}
            finally{sink.end();}
        }
        return pages.size();
    }

    private static int argb(int rgb){return 0xFF000000|rgb;}
    private static Paint stroke(int rgb,float width,float[] dash){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.STROKE);p.setColor(argb(rgb));p.setStrokeWidth(width);
        if(dash!=null&&dash.length>=2)p.setPathEffect(new DashPathEffect(dash,0));
        return p;
    }
    private static Paint fill(int rgb){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.FILL);p.setColor(argb(rgb));return p;}

    /** Executa uma operação do layout comum. */
    static void draw(Canvas c,ReportLayout.Op o,Map<ReportLayout.Role,Bitmap> images){
        switch(o.kind){
            case TEXT:{
                if(o.text==null||o.text.isEmpty())return;
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
                p.setTypeface(Typeface.create(Typeface.SANS_SERIF,o.bold?Typeface.BOLD:Typeface.NORMAL));p.setTextSize(o.size);p.setColor(argb(o.color));
                // A quebra de linha usa a métrica da Helvetica (núcleo). Se a fonte do aparelho passar da
                // largura prevista, reduz o corpo para caber; nunca alarga (sem invadir a coluna vizinha).
                float m=p.measureText(o.text);
                if(m>o.w&&o.w>0)p.setTextSize(o.size*o.w/m);
                c.drawText(o.text,o.x,o.y,p);return;
            }
            case RECT:{
                if(o.fill>=0)c.drawRect(o.x,o.y,o.x+o.w,o.y+o.h,fill(o.fill));
                if(o.stroke>=0&&o.lineWidth>0)c.drawRect(o.x,o.y,o.x+o.w,o.y+o.h,stroke(o.stroke,o.lineWidth,null));
                return;
            }
            case LINE:c.drawLine(o.x,o.y,o.x2,o.y2,stroke(o.stroke,o.lineWidth,o.dash));return;
            case POLY:{
                Path path=new Path();path.moveTo(o.points[0],o.points[1]);
                for(int i=2;i+1<o.points.length;i+=2)path.lineTo(o.points[i],o.points[i+1]);
                c.drawPath(path,stroke(o.stroke,o.lineWidth,o.dash));return;
            }
            case CIRCLE:{
                if(o.fill>=0)c.drawCircle(o.x,o.y,o.w,fill(o.fill));
                if(o.stroke>=0)c.drawCircle(o.x,o.y,o.w,stroke(o.stroke,o.lineWidth,null));
                return;
            }
            case IMAGE:{
                Bitmap src=images.get(o.role);
                if(src==null||src.isRecycled())return;
                int sx=0,sy=0,sw=src.getWidth(),sh=src.getHeight();
                if(o.source!=null){
                    sx=Math.max(0,Math.round(o.source[0]));sy=Math.max(0,Math.round(o.source[1]));
                    sw=Math.max(1,Math.min(src.getWidth()-sx,Math.round(o.source[2])));sh=Math.max(1,Math.min(src.getHeight()-sy,Math.round(o.source[3])));
                }
                // Recorte e redução a ~200 dpi ANTES de desenhar: o PdfDocument embute o bitmap inteiro que recebe.
                int tw=Math.max(1,Math.round(o.w*200f/72f)),th=Math.max(1,Math.round(o.h*200f/72f));
                Bitmap part=(sx==0&&sy==0&&sw==src.getWidth()&&sh==src.getHeight())?src:Bitmap.createBitmap(src,sx,sy,sw,sh);
                Bitmap draw=tw<part.getWidth()?Bitmap.createScaledBitmap(part,tw,th,true):part;
                try{c.drawBitmap(draw,new Rect(0,0,draw.getWidth(),draw.getHeight()),new RectF(o.x,o.y,o.x+o.w,o.y+o.h),new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG));}
                finally{if(draw!=part)draw.recycle();if(part!=src)part.recycle();}
                return;
            }
            default:
        }
    }

    /**
     * Monta as figuras na ordem do relatório. O composto é recortado pelo PhotoComposer.Layout, de modo que
     * os painéis do PDF são pixel a pixel os mesmos do _resultado.png. Bitmaps criados aqui vão para owned.
     * filteredPanel: painel já recortado da imagem filtrada (null quando não houve realce).
     */
    static List<Figure> figures(JSONObject json,Bitmap annotated,Bitmap filteredPanel,Bitmap map,List<Bitmap> owned) {
        List<Figure> out=new ArrayList<>();
        boolean filtered=filteredPanel!=null;
        Bitmap original=null,segmented=null,summary=null;
        if(annotated!=null){
            int ph=json.optInt("photo_panel_height",0);
            PhotoComposer.Layout l=ph>0?new PhotoComposer.Layout(annotated.getWidth(),ph):null;
            if(l!=null&&l.height==annotated.getHeight()){
                original=Bitmap.createBitmap(annotated,0,l.originalTop,annotated.getWidth(),ph);owned.add(original);
                segmented=Bitmap.createBitmap(annotated,0,l.annotatedTop,annotated.getWidth(),ph);owned.add(segmented);
                summary=Bitmap.createBitmap(annotated,0,l.summaryTop,annotated.getWidth(),annotated.getHeight()-l.summaryTop);owned.add(summary);
            }
        }
        if(original!=null){
            out.add(figure(original,ReportPdfContent.original(json)));
            if(filtered)out.add(figure(filteredPanel,ReportPdfContent.filtered(json)));
            out.add(figure(segmented,ReportPdfContent.segmented(filtered)));
        }else if(annotated!=null){
            if(filtered)out.add(figure(filteredPanel,ReportPdfContent.filtered(json)));
            out.add(figure(annotated,ReportPdfContent.composite(filtered)));
        }
        if(map!=null)out.add(figure(map,ReportPdfContent.map(filtered)));
        if(summary!=null)out.add(figure(summary,ReportPdfContent.summaryPanel()));
        return out;
    }
    private static Figure figure(Bitmap image,ReportPdfContent.FigureText t){return new Figure(image,t.title,t.caption,false,t.role);}

    /** 0.15.3: histograma (em cima) e curva acumulada com ajuste Rosin–Rammler (embaixo), mesma página. Bitmap vai para owned. */
    static Figure distributionFigure(JSONObject json,Granulometry.Distribution d,CrackHistogram cracks,ReportVisuals visuals,RosinRammler.Basis basis,List<Bitmap> owned){
        if(d==null)return null;
        int w=1120,hh=620,gap=24,rh=900;
        Bitmap b=Bitmap.createBitmap(w,hh+gap+rh,Bitmap.Config.ARGB_8888);owned.add(b);
        Canvas c=new Canvas(b);c.drawColor(RosinRammlerView.BG);
        HistogramView.draw(c,new android.graphics.RectF(0,0,w,hh),d,cracks,visuals);
        RosinRammlerView.draw(c,new android.graphics.RectF(0,hh+gap,w,hh+gap+rh),RosinRammler.fit(d,basis),false);
        return Figure.fullPage(b,ReportPdfContent.distributionFigure(json));
    }

    // ---------- utilitários ----------
    static String num(Object v){return ReportPdfContent.num(v);}
    static String pct(double f){return ReportPdfContent.pct(f);}
}
