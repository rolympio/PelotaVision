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
 * Depois: imagem de resultado (original, elipses/trincas, histograma) e mapa granulométrico.
 */
final class PdfReport {
    private PdfReport() {}
    static final int W=595,H=842,M=40;
    private static final int INK=Color.rgb(28,38,46),MUTED=Color.rgb(96,110,120),ACCENT=Color.rgb(0,122,135),RULE=Color.rgb(205,214,220),BAND=Color.rgb(238,243,245);

    static byte[] render(JSONObject json,Bitmap annotated,Bitmap map) throws Exception {
        PdfDocument doc=new PdfDocument();
        try {
            Writer w=new Writer(doc,json.optString("pdf_file",""));
            header(w,json);
            summary(w,json);
            lowLight(w,json);
            models(w,json);
            bins(w,json);
            notes(w,json);
            w.finish();
            if(annotated!=null)imagePage(doc,w,annotated,"Resultado da análise","Original sem marcações, elipses ajustadas, possíveis trincas e histograma (todas × trincadas).");
            if(map!=null)imagePage(doc,w,map,"Mapa granulométrico","Cores pela classe de diâmetro equivalente; legenda na própria imagem.");
            ByteArrayOutputStream out=new ByteArrayOutputStream();doc.writeTo(out);return out.toByteArray();
        } finally {doc.close();}
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

    private static void imagePage(PdfDocument doc,Writer w,Bitmap image,String title,String caption) {
        PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(W,H,++w.pageNo).create());
        Canvas c=page.getCanvas();
        Paint t=w.paint(14,true,INK);c.drawText(title,M,M+14,t);
        Paint cap=w.paint(9,false,MUTED);c.drawText(caption,M,M+30,cap);
        float top=M+42,maxW=W-2*M,maxH=H-top-M-16;
        float s=Math.min(maxW/image.getWidth(),maxH/image.getHeight());
        float dw=image.getWidth()*s,dh=image.getHeight()*s,left=M+(maxW-dw)/2;
        Paint p=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
        // ~200 dpi no tamanho impresso: mantém o PDF leve sem perder leitura das elipses.
        float dpiScale=200f/72f;int tw=Math.max(1,Math.round(dw*dpiScale)),th=Math.max(1,Math.round(dh*dpiScale));
        Bitmap draw=image;if(tw<image.getWidth())draw=Bitmap.createScaledBitmap(image,tw,th,true);
        try{c.drawBitmap(draw,new Rect(0,0,draw.getWidth(),draw.getHeight()),new RectF(left,top,left+dw,top+dh),p);}
        finally{if(draw!=image)draw.recycle();}
        footer(c,w);
        doc.finishPage(page);
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
        final PdfDocument doc;final String file;PdfDocument.Page page;Canvas canvas;float y;int pageNo;
        Writer(PdfDocument doc,String file){this.doc=doc;this.file=file;newPage();}
        void newPage(){if(page!=null){footer(canvas,this);doc.finishPage(page);}page=doc.startPage(new PdfDocument.PageInfo.Builder(W,H,++pageNo).create());canvas=page.getCanvas();y=M;}
        void finish(){if(page!=null){footer(canvas,this);doc.finishPage(page);page=null;}}
        void ensure(float h){if(y+h>H-M-20)newPage();}
        Paint paint(float size,boolean bold,int color){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(size);p.setColor(color);p.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);return p;}
        void line(String s,float size,boolean bold,int color){ensure(size+4);Paint p=paint(size,bold,color);canvas.drawText(s,M,y+size,p);y+=size+4;}
        void rule(){Paint p=paint(1,false,RULE);p.setStrokeWidth(.8f);canvas.drawLine(M,y,W-M,y,p);y+=8;}
        void section(String title){ensure(40);y+=6;Paint p=paint(11.5f,true,ACCENT);canvas.drawText(title.toUpperCase(new Locale("pt","BR")),M,y+11,p);y+=17;}
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
