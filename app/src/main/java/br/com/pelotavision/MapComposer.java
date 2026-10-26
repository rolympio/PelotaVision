package br.com.pelotavision;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import java.util.Locale;

/**
 * Exported granulometric map: the analyzed photo with the color of each included pellet blended by
 * {@link GranulometryColorScale#paintMap} (pixel-identical to the Windows export), cropped like the report
 * (same {@link ReportViewport}), with the continuous legend in mm and the scale mode below the photo.
 */
final class MapComposer {
    private MapComposer() {}

    static Bitmap compose(Bitmap photo, PelletDetector.Result result, Granulometry.Distribution distribution, ReportVisuals visuals,
                          InterestRegion region, boolean cropRoi) {
        GranulometryColorScale scale = visuals.scale(distribution);
        if (scale == null) return null;
        int w = photo.getWidth(), h = photo.getHeight();
        int[] px = new int[w * h];
        photo.getPixels(px, 0, w, 0, 0, w, h);
        GranulometryColorScale.paintMap(px, w, h, result, distribution, scale, visuals.mapOpacity);
        Bitmap painted = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        px = null;
        ReportViewport v = new ReportViewport(w, h, region, cropRoi);
        int band = Math.max(60, Math.round(v.width * 70f / 380));
        Bitmap out = Bitmap.createBitmap(v.width, v.height + band, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(out);
            c.drawColor(Color.rgb(16, 26, 35));
            c.save();
            c.clipRect(0, 0, v.width, v.height);
            c.translate(-v.left * v.scale, -v.top * v.scale);
            c.scale(v.scale, v.scale);
            ReportClip.clip(c, v, region);
            c.drawBitmap(painted, 0, 0, null);
            if (visuals.mapLabels && coloredCount(result, distribution) <= MAX_LABELED) drawLabels(c, result, distribution, v.scale);
            c.restore();
            drawLegend(c, scale, visuals, 0, v.height, v.width, band);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            out.recycle();
            throw e;
        } finally {
            painted.recycle();
        }
    }

    /** Acima disto os rótulos de diâmetro viram ruído (1000+ pelotas na correia): o mapa sai sem eles. */
    static final int MAX_LABELED = 250;
    static int coloredCount(PelletDetector.Result r, Granulometry.Distribution d) {
        int n = 0;
        for (PelletDetector.Detection p : r.circles) if (GranulometryColorScale.colored(d, new Granulometry.Item(2 * p.radius, p.partial, p.needsReview))) n++;
        return n;
    }

    private static void drawLabels(Canvas c, PelletDetector.Result result, Granulometry.Distribution d, float scale) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        float size = 11f / Math.max(.05f, scale);
        t.setTextSize(size); t.setColor(Color.WHITE); t.setTextAlign(Paint.Align.CENTER);
        halo.setTextSize(size); halo.setColor(Color.argb(200, 0, 0, 0)); halo.setTextAlign(Paint.Align.CENTER);
        halo.setStyle(Paint.Style.STROKE); halo.setStrokeWidth(size / 4);
        java.util.List<android.graphics.RectF> used = new java.util.ArrayList<>();
        for (PelletDetector.Detection p : result.circles) {
            Granulometry.Item item = new Granulometry.Item(2 * p.radius, p.partial, p.needsReview);
            if (!GranulometryColorScale.colored(d, item) || p.radius * scale < 9) continue;
            String label = HistogramView.number(d.diameterMm(item.diameterPx));
            float x = (float) p.x, y = (float) p.y + size / 3, half = t.measureText(label) / 2;
            android.graphics.RectF r = new android.graphics.RectF(x - half, y - size, x + half, y + size / 4);
            boolean free = true;
            for (android.graphics.RectF u : used) if (android.graphics.RectF.intersects(u, r)) { free = false; break; }
            if (!free) continue;
            used.add(r);
            c.drawText(label, x, y, halo);
            c.drawText(label, x, y, t);
        }
    }

    static void drawLegend(Canvas c, GranulometryColorScale scale, ReportVisuals visuals, float x, float y, float width, float height) {
        float s = width / 380f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(10 * s);
        c.drawText(String.format(Locale.getDefault(), "Mapa granulométrico · escala %s · cor %d%% sobre foto %d%%", scale.label(),
                Math.round(visuals.mapOpacity * 100), Math.round((1 - visuals.mapOpacity) * 100)), x + 12 * s, y + 16 * s, p);
        float bx = x + 12 * s, bw = width - 24 * s, by = y + 24 * s, bh = 12 * s;
        int[] colors = new int[9];
        for (int i = 0; i < colors.length; i++) colors[i] = 0xFF000000 | GranulometryColorScale.colorAt(i / (colors.length - 1.0));
        p.setShader(new LinearGradient(bx, 0, bx + bw, 0, colors, null, Shader.TileMode.CLAMP));
        c.drawRect(bx, by, bx + bw, by + bh, p);
        p.setShader(null);
        p.setTextSize(9 * s);
        double[] ticks = scale.ticks(scale.equalRange() ? 2 : 5);
        for (int i = 0; i < ticks.length; i++) {
            p.setTextAlign(i == 0 ? Paint.Align.LEFT : i == ticks.length - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            c.drawText((scale.clipsEnds() && i == 0 ? "≤" : scale.clipsEnds() && i == ticks.length - 1 ? "≥" : "") + HistogramView.number(ticks[i]) + (i == ticks.length - 1 ? " " + scale.unit : ""), bx + bw * i / (ticks.length - 1f), by + bh + 11 * s, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Color.rgb(151, 174, 187));
        p.setTextSize(8 * s);
        c.drawText(scale.equalRange() ? "Todos os diâmetros iguais: cor intermediária." : scale.clipsEnds() ? "Escala sobre D10–D90: abaixo de D10 = azul, acima de D90 = vermelho. Fundo e área fora da ROI sem cor." : "Cor dentro da elipse ajustada de cada pelota incluída no histograma; fundo e área fora da ROI sem cor.",
                bx, Math.min(y + height - 4 * s, by + bh + 24 * s), p);
    }
}
