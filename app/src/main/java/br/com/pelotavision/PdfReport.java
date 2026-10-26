package br.com.pelotavision;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
        Figure(Bitmap image,String title,String caption){this.image=image;this.title=title;this.caption=caption;}
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
        header(w,json);
        summary(w,json);
        lowLight(w,json);
        models(w,json);
        bins(w,json);
        notes(w,json);
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
        String treatment=treatmentLabel(json);
        boolean roi=json.optJSONObject("region_of_interest")!=null;
        String scope=roi?"ROI ampliada. ":"";
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
            out.add(new Figure(original,"Imagem original",scope+"Pixels originais da foto, sem filtro e sem marcações. Elipses, diâmetros e trincas são medidos nestes pixels."));
            if(filtered)out.add(new Figure(filteredPanel,"Imagem filtrada · "+treatment,scope+"Tratamento de iluminação usado para propor os objetos. Serve à visualização e à segmentação; não é usado nas medidas."));
            out.add(new Figure(segmented,"Imagem segmentada",(filtered?"Sobre a imagem filtrada. ":"")+"Elipses ajustadas e possíveis trincas. Vermelho: possível trinca · amarelo: revisão · tracejado: forma sem ajuste."));
        }else if(annotated!=null){
            if(filtered)out.add(new Figure(filteredPanel,"Imagem filtrada · "+treatment,scope+"Tratamento de iluminação usado para propor os objetos; não é usado nas medidas."));
            out.add(new Figure(annotated,"Resultado da análise","Original sem marcações, "+(filtered?"segmentação sobre a imagem filtrada":"elipses ajustadas")+", possíveis trincas e histograma (todas × trincadas)."));
        }
        if(map!=null)out.add(new Figure(map,"Mapa granulométrico",(filtered?"Sobre a imagem filtrada. ":"")+"Cor pela classe de diâmetro equivalente de cada pelota incluída; legenda na própria imagem."));
        if(summary!=null)out.add(new Figure(summary,"Resumo da imagem e histograma","Contagem, triagem de trincas, forma aparente 2D e distribuição por número (todas × trincadas)."));
        return out;
    }
    static String treatmentLabel(JSONObject json){
        JSONObject l=json.optJSONObject("low_light");if(l==null)return LowLightInfo.label(LowLightPreprocessor.Mode.ORIGINAL);
        LowLightPreprocessor.Mode mode;try{mode=LowLightPreprocessor.Mode.valueOf(l.optString("mode","ORIGINAL"));}catch(IllegalArgumentException e){mode=LowLightPreprocessor.Mode.ORIGINAL;}
        return LowLightInfo.label(mode);
    }

    // ---------- seções ----------
    private static void header(Writer w,JSONObject j) {
        Paint t=w.paint(20,true,INK);w.canvas.drawText("Relatório de análise de pelotas",M,w.y+20,t);w.y+=28;
        long ms=j.optLong("created_at_epoch_ms",System.currentTimeMillis());
        String when=new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",new Locale("pt","BR")).format(new Date(ms));
        w.line(j.optString("app","PelotaVision")+"  ·  "+when,10,false,MUTED);
        w.line("Arquivos: "+j.optString("annotated_file","—").replace("_resultado.png","")+"_* (Imagens e Downloads / PelotaVision)",9,false,MUTED);
        w.y+=6;w.rule();
    }
    private static void summary(Writer w,JSONObject j) {
        w.section("Resumo");
        JSONObject h=j.optJSONObject("histogram");String unit=h==null?"px":h.optString("unit","px");
        List<String[]> rows=new ArrayList<>();
        rows.add(new String[]{"Pelotas detectadas",String.valueOf(j.optInt("count",0))});
        if(h!=null){
            rows.add(new String[]{"No histograma (incluídas / excluídas)",h.optInt("included")+" / "+h.optInt("excluded")});
            rows.add(new String[]{"Diâmetro médio",num(h.opt("mean_"+unit))+" "+unit});
            rows.add(new String[]{"Mínimo / máximo",num(h.opt("min_"+unit))+" / "+num(h.opt("max_"+unit))+" "+unit});
            rows.add(new String[]{"D10 / D50 / D90",num(h.opt("d10_"+unit))+" / "+num(h.opt("d50_"+unit))+" / "+num(h.opt("d90_"+unit))+" "+unit});
            rows.add(new String[]{"Largura de classe",num(h.opt("bin_width_"+unit))+" "+unit+(h.optBoolean("bin_width_auto_adjusted")?" (ajustada automaticamente)":"")});
        }
        JSONObject s=j.optJSONObject("physical_scale");
        if(s!=null)rows.add(new String[]{"Escala",num(s.opt("original_pixels_per_mm"))+" px/mm · "+s.optString("source","—")+(s.optBoolean("estimated")?" (estimada)":"")});
        else rows.add(new String[]{"Escala","sem calibração física — valores em pixels"});
        JSONObject c=j.optJSONObject("cracks");
        if(c==null||"not_analyzed".equals(c.optString("status")))rows.add(new String[]{"Trincas","não analisadas"});
        else{
            rows.add(new String[]{"Trincas: possíveis / sem indício / inconclusivas",c.optInt("possible_crack_pellets")+" / "+c.optInt("no_indication_pellets")+" / "+c.optInt("inconclusive_pellets")});
            rows.add(new String[]{"Trincas revisadas manualmente",String.valueOf(c.optInt("manually_reviewed_pellets"))});
        }
        JSONObject roi=j.optJSONObject("region_of_interest");
        rows.add(new String[]{"Região de interesse",roi==null?"imagem inteira":roi.optString("shape","ROI")+" · "+roi.optInt("excluded_outside_or_crossing")+" objeto(s) fora/cruzando a borda"});
        rows.add(new String[]{"Tempo de análise",j.optLong("analysis_ms")+" ms"});
        w.table(rows);
    }
    private static void lowLight(Writer w,JSONObject j) {
        w.section("Iluminação e tratamento de imagem escura");
        JSONObject l=j.optJSONObject("low_light");
        if(l==null){w.wrap("Não registrado nesta análise (modo detalhado ou análise anterior à 0.15.0).",9,MUTED);return;}
        List<String[]> rows=new ArrayList<>();
        JSONObject q=l.optJSONObject("illumination");
        if(q!=null){
            rows.add(new String[]{"Nível médio de cinza (0–255)",num(q.opt("mean_gray"))});
            rows.add(new String[]{"Desvio-padrão (contraste)",num(q.opt("std_gray"))});
            rows.add(new String[]{"Pixels saturados em preto / branco",pct(q.optDouble("clipped_black_fraction"))+" / "+pct(q.optDouble("clipped_white_fraction"))});
            rows.add(new String[]{"Imagem escura ou de baixo contraste?",q.optBoolean("needs_correction")?"sim":"não"});
        }
        String sel=l.optString("selection","not_applied");
        rows.add(new String[]{"Seleção do tratamento",LowLightInfo.selectionLabel(sel)});
        LowLightPreprocessor.Mode mode;try{mode=LowLightPreprocessor.Mode.valueOf(l.optString("mode","ORIGINAL"));}catch(IllegalArgumentException e){mode=LowLightPreprocessor.Mode.ORIGINAL;}
        rows.add(new String[]{"Tratamento aplicado",LowLightInfo.label(mode)});
        if(l.opt("original_score") instanceof Number)rows.add(new String[]{"Escore heurístico: original → escolhido",num(l.opt("original_score"))+" → "+num(l.opt("chosen_score"))});
        w.table(rows);
        w.wrap("O realce serve só para propor objetos. Elipses, diâmetros e recortes de trinca são medidos nos pixels ORIGINAIS. Os escores são heurísticos de cobertura, não acurácia nem confiança.",8,MUTED);
    }
    private static void models(Writer w,JSONObject j) {
        w.section("Método e modelos");
        List<String[]> rows=new ArrayList<>();
        JSONObject sc=j.optJSONObject("scene");if(sc!=null)rows.add(new String[]{"Cena",sc.optString("mode","—")});
        JSONObject am=j.optJSONObject("analysis_models");
        if(am!=null){
            JSONObject seg=am.optJSONObject("segmentation");if(seg!=null)rows.add(new String[]{"Localização / segmentação",seg.optString("selected","—")});
            JSONObject cls=am.optJSONObject("crack_classifier");if(cls!=null)rows.add(new String[]{"Classificador de trincas",cls.optString("effective","—")+(cls.optString("requested").equals(cls.optString("effective"))?"":" (solicitado: "+cls.optString("requested")+")")});
        }
        JSONObject nn=j.optJSONObject("neural_model");if(nn!=null)rows.add(new String[]{"Rede neural",nn.optString("model_id","—")+(nn.optBoolean("demonstration_model")?" (demonstração)":"")});
        rows.add(new String[]{"Método registrado",j.optString("method","—")});
        w.table(rows);
    }
    private static void bins(Writer w,JSONObject j) {
        JSONObject h=j.optJSONObject("histogram");if(h==null)return;JSONArray b=h.optJSONArray("bins");if(b==null||b.length()==0)return;
        String u=h.optString("unit","px");
        w.section("Distribuição granulométrica (por número)");
        List<String[]> rows=new ArrayList<>();rows.add(new String[]{"Classe ("+u+")","Pelotas","% em número"});
        for(int i=0;i<b.length();i++){JSONObject o=b.optJSONObject(i);if(o==null)continue;
            rows.add(new String[]{num(o.opt("lower_"+u))+" – "+num(o.opt("upper_"+u)),String.valueOf(o.optInt("count")),num(o.opt("percent_by_number"))});}
        w.grid(rows,new float[]{.5f,.25f,.25f});
    }
    private static void notes(Writer w,JSONObject j) {
        w.section("Limitações");
        w.wrap("Distribuição por número de objetos visíveis em imagem 2D; não substitui peneiramento nem ensaio validado. Diâmetro equivalente da elipse = 2·√(a·b). Triagem de trincas pela superfície visível: \"sem indício\" não prova ausência de trinca. Modelos experimentais não foram validados com amostras reais de produção. O JSON e o CSV de mesmo nome contêm todos os valores por pelota.",8,MUTED);
    }

    private static void footer(Canvas c,Writer w) {
        Paint f=w.paint(7.5f,false,MUTED);
        c.drawText("PelotaVision · "+w.file,M,H-22,f);
        String n="Página "+w.pageNo;c.drawText(n,W-M-f.measureText(n),H-22,f);
    }

    // ---------- utilitários ----------
    static String num(Object v) {
        if(!(v instanceof Number))return "—";double d=((Number)v).doubleValue();if(!Double.isFinite(d))return "—";
        if(Math.abs(d-Math.rint(d))<1e-9&&Math.abs(d)<1e9)return String.format(new Locale("pt","BR"),"%,d",(long)Math.rint(d));
        return String.format(new Locale("pt","BR"),Math.abs(d)>=100?"%,.1f":"%,.2f",d);
    }
    static String pct(double f){return Double.isFinite(f)?String.format(new Locale("pt","BR"),"%.1f%%",100*f):"—";}

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
        /** Figura numerada: título, legenda e imagem; até duas por página quando a imagem é paisagem. */
        void figure(int number,Figure f){
            Paint t=paint(11,true,INK),cap=paint(8.5f,false,MUTED);
            List<String> lines=split(f.caption,cap,W-2*M);
            float maxW=W-2*M,maxH=(H-2*M-20)/2f-26-lines.size()*11.5f;
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
        static List<String> split(String s,Paint p,float width){
            List<String> out=new ArrayList<>();if(s==null)s="";
            for(String para:s.split("\n")){
                StringBuilder line=new StringBuilder();
                for(String word:para.split(" ")){
                    String trial=line.length()==0?word:line+" "+word;
                    if(p.measureText(trial)<=width||line.length()==0){line.setLength(0);line.append(trial);}
                    else{out.add(line.toString());line.setLength(0);line.append(word);}
                }
                out.add(line.toString());
            }
            return out;
        }
    }
}
