package br.com.pelotavision;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Relatório PDF (0.15.0), montado a partir do MESMO JSON gravado ao lado, para que PDF e
 * dados nunca divirjam. A4 retrato (595 x 842 pt). Usa apenas android.graphics.pdf.PdfDocument.
 * Página 1+: identificação, resumo, iluminação/realce, modelos, classes granulométricas.
 * 0.15.2: depois, cada imagem como figura própria — original, filtrada (se houve realce), segmentada,
 * mapa granulométrico e histograma — duas por página quando cabem.
 */
final class PdfReport {
    private PdfReport() {}
    static final int W=595,H=842,M=40;
    private static final int INK=Color.rgb(28,38,46),MUTED=Color.rgb(96,110,120),ACCENT=Color.rgb(0,122,135),RULE=Color.rgb(205,214,220),BAND=Color.rgb(238,243,245);

    /** Destino das páginas: PdfDocument no aparelho; bitmaps nos testes (o Robolectric não implementa PdfDocument). */
    interface Sink { Canvas begin(int number); void end(); }

    /** 0.15.2: uma imagem do relatório, com título e legenda próprios. A figura não é dona do bitmap. */
    static final class Figure {
        final Bitmap image;final String title,caption;
        /** 0.15.3: ocupa até a página inteira (histograma + Rosin–Rammler); as demais usam meia página. */
        final boolean fullPage;
        Figure(Bitmap image,String title,String caption){this(image,title,caption,false);}
        Figure(Bitmap image,String title,String caption,boolean fullPage){this.image=image;this.title=title;this.caption=caption;this.fullPage=fullPage;}
        static Figure fullPage(Bitmap image,ReportPdfContent.FigureText t){return new Figure(image,t.title,t.caption,true);}
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
        Writer w=new Writer(sink,json.optString("pdf_file",""));
        for(ReportPdfContent.Block b:ReportPdfContent.blocks(json))w.block(b);
        if(figures!=null&&!figures.isEmpty()){
            w.ensure(H/2f);w.section("Imagens");
            int n=0;for(Figure f:figures)if(f!=null&&f.image!=null&&!f.image.isRecycled())w.figure(++n,f);
        }
        w.finish();
        return w.pageNo;
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
    private static Figure figure(Bitmap image,ReportPdfContent.FigureText t){return new Figure(image,t.title,t.caption);}

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

    private static void footer(Canvas c,Writer w) {
        Paint f=w.paint(7.5f,false,MUTED);
        c.drawText("PelotaVision · "+w.file,M,H-22,f);
        String n="Página "+w.pageNo;c.drawText(n,W-M-f.measureText(n),H-22,f);
    }

    // ---------- utilitários ----------
    static String num(Object v){return ReportPdfContent.num(v);}
    static String pct(double f){return ReportPdfContent.pct(f);}

    /** Cursor de texto com quebra automática de página. */
    private static final class Writer {
        final Sink sink;final String file;Canvas canvas;float y;int pageNo;
        Writer(Sink sink,String file){this.sink=sink;this.file=file;newPage();}
        void newPage(){if(canvas!=null){footer(canvas,this);sink.end();}canvas=sink.begin(++pageNo);y=M;}
        void finish(){if(canvas!=null){footer(canvas,this);sink.end();canvas=null;}}
        void ensure(float h){if(y+h>H-M-20)newPage();}
        Paint paint(float size,boolean bold,int color){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(size);p.setColor(color);p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);return p;}
        void line(String s,float size,boolean bold,int color){ensure(size+4);Paint p=paint(size,bold,color);canvas.drawText(s,M,y+size,p);y+=size+4;}
        void rule(){Paint p=paint(1,false,RULE);p.setStrokeWidth(.8f);canvas.drawLine(M,y,W-M,y,p);y+=8;}
        void section(String title){ensure(40);y+=6;Paint p=paint(11.5f,true,ACCENT);canvas.drawText(title.toUpperCase(new Locale("pt","BR")),M,y+11,p);y+=17;}
        /** Desenha um bloco de texto comum (ReportPdfContent), igual ao relatório do Windows. */
        void block(ReportPdfContent.Block b){
            int tone=b.tone==ReportPdfContent.Tone.MUTED?MUTED:INK;
            switch(b.kind){
                case TITLE:{ensure(28);Paint t=paint(b.size,true,INK);canvas.drawText(b.text,M,y+b.size,t);y+=28;break;}
                case LINE:line(b.text,b.size,b.bold,tone);break;
                case RULE:y+=6;rule();break;
                case SECTION:section(b.text);break;
                case TABLE:table(b.rows);break;
                case GRID:grid(b.rows,b.fractions);break;
                case NOTE:wrap(b.text,b.size,tone);break;
            }
        }
        /** Figura numerada: título, legenda e imagem; até duas por página quando a imagem é paisagem. */
        void figure(int number,Figure f){
            Paint t=paint(11,true,INK),cap=paint(8.5f,false,MUTED);
            List<String> lines=split(f.caption,cap,W-2*M);
            float maxW=W-2*M,maxH=(H-2*M-20)/(f.fullPage?1f:2f)-26-lines.size()*11.5f;
            float s=Math.min(maxW/f.image.getWidth(),maxH/f.image.getHeight());
            float dw=f.image.getWidth()*s,dh=f.image.getHeight()*s;
            ensure(16+lines.size()*11.5f+4+dh+12);
            canvas.drawText("Figura "+number+" · "+f.title,M,y+11,t);y+=16;
            for(String l:lines){canvas.drawText(l,M,y+9,cap);y+=11.5f;}
            y+=4;
            float left=M+(maxW-dw)/2;
            Paint p=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
            // ~200 dpi no tamanho impresso: mantém o PDF leve sem perder leitura das elipses.
            float dpiScale=200f/72f;int tw=Math.max(1,Math.round(dw*dpiScale)),th=Math.max(1,Math.round(dh*dpiScale));
            Bitmap draw=f.image;if(tw<f.image.getWidth())draw=Bitmap.createScaledBitmap(f.image,tw,th,true);
            try{canvas.drawBitmap(draw,new Rect(0,0,draw.getWidth(),draw.getHeight()),new RectF(left,y,left+dw,y+dh),p);}
            finally{if(draw!=f.image)draw.recycle();}
            Paint frame=paint(1,false,RULE);frame.setStyle(Paint.Style.STROKE);frame.setStrokeWidth(.6f);canvas.drawRect(left,y,left+dw,y+dh,frame);
            y+=dh+12;
        }
        void wrap(String s,float size,int color){Paint p=paint(size,false,color);for(String l:split(s,p,W-2*M)){ensure(size+3);canvas.drawText(l,M,y+size,p);y+=size+3;}y+=4;}
        void table(List<String[]> rows){
            Paint k=paint(9,false,MUTED),v=paint(9.5f,true,INK);float kw=(W-2*M)*.46f,vw=(W-2*M)-kw-8;
            for(int i=0;i<rows.size();i++){
                List<String> vl=split(rows.get(i)[1],v,vw),kl=split(rows.get(i)[0],k,kw);int n=Math.max(vl.size(),kl.size());float h=n*12+5;ensure(h);
                if(i%2==0){Paint b=paint(1,false,BAND);canvas.drawRect(M-4,y,W-M+4,y+h,b);}
                for(int r=0;r<kl.size();r++)canvas.drawText(kl.get(r),M,y+12+r*12,k);
                for(int r=0;r<vl.size();r++)canvas.drawText(vl.get(r),M+kw+8,y+12+r*12,v);
                y+=h;
            }
            y+=4;
        }
        void grid(List<String[]> rows,float[] frac){
            float total=W-2*M;Paint head=paint(9,true,INK),cell=paint(9,false,INK);
            for(int i=0;i<rows.size();i++){
                if(y+16>H-M-20){newPage();if(i>0)drawRow(rows.get(0),frac,total,head,true);}
                drawRow(rows.get(i),frac,total,i==0?head:cell,i==0);
            }
            y+=4;
        }
        private void drawRow(String[] r,float[] frac,float total,Paint p,boolean header){
            float h=15;if(header){Paint b=paint(1,false,BAND);canvas.drawRect(M-4,y,W-M+4,y+h,b);}
            float x=M;for(int c=0;c<r.length;c++){float cw=frac[c]*total;String s=r[c];float tx=c==0?x:x+cw-p.measureText(s)-6;canvas.drawText(s,tx,y+11,p);x+=cw;}
            Paint rule=paint(1,false,RULE);rule.setStrokeWidth(.5f);canvas.drawLine(M-4,y+h,W-M+4,y+h,rule);y+=h;
        }
        static List<String> chunks(String word,Paint p,float width){
            List<String> out=new ArrayList<>();
            if(p.measureText(word)<=width||word.length()<2){out.add(word);return out;}
            int start=0;
            while(start<word.length()){
                int end=start+1;
                while(end<word.length()&&p.measureText(word,start,end+1)<=width)end++;
                out.add(word.substring(start,end));start=end;
            }
            return out;
        }
        static List<String> split(String s,Paint p,float width){
            List<String> out=new ArrayList<>();if(s==null)s="";
            for(String para:s.split("\n")){
                StringBuilder line=new StringBuilder();
                for(String whole:para.split(" ")){
                  // 0.15.2: palavra maior que a linha (ex.: método registrado) é quebrada em pedaços que cabem.
                  for(String word:chunks(whole,p,width)){
                    String trial=line.length()==0?word:line+" "+word;
                    if(p.measureText(trial)<=width||line.length()==0){line.setLength(0);line.append(trial);}
                    else{out.add(line.toString());line.setLength(0);line.append(word);}
                  }
                }
                out.add(line.toString());
            }
            return out;
        }
    }
}
