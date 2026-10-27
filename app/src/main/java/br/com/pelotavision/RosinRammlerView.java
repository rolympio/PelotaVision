package br.com.pelotavision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 0.15.3: curva acumulada (% passante) com os pontos nos limites das classes do histograma e a curva
 * Rosin–Rammler ajustada, eixo x logarítmico. A geometria vem de {@link RosinRammler.Chart} (núcleo,
 * igual ao Windows); aqui só se desenha, no mesmo tema do {@link HistogramView}. Mesmo desenho na tela
 * e na figura do relatório PDF.
 */
final class RosinRammlerView extends View {
    static final int BG = Color.rgb(16, 26, 35), TEXT = Color.rgb(181, 198, 208), GRID = Color.rgb(49, 68, 81),
            DATA = Color.WHITE, POINT_FILL = Color.rgb(205, 214, 220), FIT = Color.rgb(64, 180, 235);
    private RosinRammler.Fit fit;

    RosinRammlerView(Context context) { super(context); setContentDescription("Curva acumulada com ajuste Rosin–Rammler"); }

    void display(RosinRammler.Fit f) { fit = f; invalidate(); }
    RosinRammler.Fit fit() { return fit; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (fit != null) draw(canvas, new RectF(0, 0, getWidth(), getHeight()), fit, true);
    }

    /** compact = tela (fórmula + qualidade); false = relatório (também parâmetros e a ressalva da base). */
    static void draw(Canvas c, RectF box, RosinRammler.Fit f, boolean compact) {
        int saved = c.save(); c.clipRect(box); c.translate(box.left, box.top);
        float width = box.width(), height = box.height(), s = Math.min(width / 380f, height / (compact ? 230f : 300f));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(BG); c.drawRect(0, 0, width, height, p);
        p.setColor(Color.WHITE); p.setTextSize(14 * s); p.setFakeBoldText(true);
        c.drawText("Rosin–Rammler · " + f.basis.label, 12 * s, 21 * s, p); p.setFakeBoldText(false);
        p.setTextSize(10.5f * s); p.setColor(TEXT);
        String[] header = compact
                ? new String[]{f.formula(), f.available ? f.quality() : ""}
                : new String[]{f.formula(), f.parameters(), f.quality(), f.basis == RosinRammler.Basis.MASS ? "Massa estimada = esferas de mesma densidade (∝ d³); não é peneiramento." : "Base número: não comparável a parâmetros RR de peneiramento (massa)."};
        float y0 = 21 * s;
        for (String line : header) { y0 += 15 * s; c.drawText(line, 12 * s, y0, p); }
        float left = 44 * s, right = width - 14 * s, top = y0 + 12 * s, bottom = height - 32 * s;
        if (f.points.isEmpty() || bottom - top < 30 * s) { c.restoreToCount(saved); return; }
        RosinRammler.Chart chart = new RosinRammler.Chart(f, left, top, right, bottom);

        p.setTextSize(9 * s); p.setStrokeWidth(Math.max(1, .8f * s));
        for (double yv : chart.yTicks) {
            float y = chart.y(yv);
            p.setColor(GRID); c.drawLine(left, y, right, y, p);
            p.setColor(TEXT); p.setTextAlign(Paint.Align.RIGHT); c.drawText(RosinRammler.tick(yv), left - 5 * s, y + 3 * s, p);
        }
        p.setTextAlign(Paint.Align.CENTER);
        for (double xv : chart.xTicks) {
            float x = chart.x(xv);
            p.setColor(GRID); c.drawLine(x, top, x, bottom, p);
            p.setColor(TEXT); c.drawText(RosinRammler.tick(xv), x, bottom + 12 * s, p);
        }
        c.drawText("Diâmetro (" + f.unit + ") · escala log", (left + right) / 2, height - 6 * s, p);
        c.save(); c.rotate(-90, 11 * s, (top + bottom) / 2); c.drawText("% passante", 11 * s, (top + bottom) / 2, p); c.restore();
        p.setTextAlign(Paint.Align.LEFT);

        c.save(); c.clipRect(left, top - 2, right + 1, bottom + 2);
        float[] curve = chart.curve(140);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG); line.setStyle(Paint.Style.STROKE); line.setStrokeJoin(Paint.Join.ROUND);
        if (curve.length > 0) {
            Path path = new Path();
            for (int i = 0; i < curve.length; i += 2) if (i == 0) path.moveTo(curve[i], curve[i + 1]); else path.lineTo(curve[i], curve[i + 1]);
            line.setColor(FIT); line.setStrokeWidth(2.2f * s); line.setPathEffect(new DashPathEffect(new float[]{6 * s, 4 * s}, 0));
            c.drawPath(path, line);
        }
        Path data = new Path();
        for (int i = 0; i < f.points.size(); i++) { RosinRammler.Point pt = f.points.get(i); float x = chart.x(pt.size), y = chart.y(pt.percentFiner); if (i == 0) data.moveTo(x, y); else data.lineTo(x, y); }
        line.setPathEffect(null); line.setColor(DATA); line.setStrokeWidth(1.4f * s); c.drawPath(data, line);
        float rad = (f.points.size() > 40 ? 2.2f : 3.4f) * s;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG); fill.setColor(POINT_FILL);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG); ring.setStyle(Paint.Style.STROKE); ring.setColor(BG); ring.setStrokeWidth(.9f * s);
        for (RosinRammler.Point pt : f.points) { float x = chart.x(pt.size), y = chart.y(pt.percentFiner); c.drawCircle(x, y, rad, fill); c.drawCircle(x, y, rad, ring); }
        c.restore();

        // Legenda no canto superior esquerdo (vazio numa curva acumulada crescente).
        String a = "Medido (limites de classe)", b = f.available ? "Ajuste RR" : "Ajuste indisponível";
        p.setTextSize(9 * s);
        float lw = Math.max(p.measureText(a), p.measureText(b)) + 34 * s, lh = 30 * s, lx = left + 6 * s, ly = top + 6 * s;
        Paint bg = new Paint(); bg.setColor(Color.argb(225, 16, 26, 35)); c.drawRect(lx, ly, lx + lw, ly + lh, bg);
        float y1 = ly + 11 * s, y2 = y1 + 13 * s;
        line.setColor(DATA); line.setStrokeWidth(1.4f * s); c.drawLine(lx + 5 * s, y1 - 3 * s, lx + 25 * s, y1 - 3 * s, line);
        c.drawCircle(lx + 15 * s, y1 - 3 * s, rad, fill);
        line.setColor(FIT); line.setStrokeWidth(2.2f * s); line.setPathEffect(new DashPathEffect(new float[]{6 * s, 4 * s}, 0)); c.drawLine(lx + 5 * s, y2 - 3 * s, lx + 25 * s, y2 - 3 * s, line);
        p.setColor(TEXT); c.drawText(a, lx + 30 * s, y1, p); c.drawText(b, lx + 30 * s, y2, p);
        c.restoreToCount(saved);
    }
}
