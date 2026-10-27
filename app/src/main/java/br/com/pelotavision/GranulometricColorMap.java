package br.com.pelotavision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import java.util.Locale;

/**
 * Mapa granulométrico: cor contínua por diâmetro equivalente (azul → ciano → verde → amarelo → vermelho),
 * aplicada somente dentro da elipse ajustada de cada pelota INCLUÍDA no histograma (mesma regra, mesma
 * escala), com transparência ajustável (padrão 25% de cor sobre 75% da foto). A fotografia continua
 * visível; fora das pelotas, fora da ROI e no fundo da correia nada é colorido.
 * A escala e a cor vêm de {@link GranulometryColorScale} (núcleo comum Android/Windows).
 */
final class GranulometricColorMap extends View {
    private PelletDetector.Result result;
    private Granulometry.Distribution distribution;
    private ReportVisuals visuals = ReportVisuals.defaults();
    private AnalysisWindow viewport;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legend = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);

    GranulometricColorMap(Context context) {
        super(context);
        setWillNotDraw(false);
        setContentDescription("Mapa granulométrico por cores");
        fill.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        halo.setColor(Color.argb(200, 0, 0, 0));
        halo.setTextAlign(Paint.Align.CENTER);
        halo.setStyle(Paint.Style.STROKE);
    }

    void display(PelletDetector.Result result, Granulometry.Distribution distribution) {
        this.result = result;
        this.distribution = distribution;
        invalidate();
    }

    void visuals(ReportVisuals value) {
        if (value != null) visuals = value;
        invalidate();
    }

    void viewport(AnalysisWindow value) {
        viewport = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (result == null || distribution == null || distribution.included == 0) return;
        GranulometryColorScale scale = visuals.scale(distribution);
        if (scale == null) return;
        int w = viewport == null ? result.width : viewport.width;
        int h = viewport == null ? result.height : viewport.height;
        float k = Math.min(getWidth() / (float) w, getHeight() / (float) h);
        if (!(k > 0)) return;
        float ox = (getWidth() - w * k) / 2f, oy = (getHeight() - h * k) / 2f;
        float left = ox - (viewport == null ? 0 : viewport.left * k), top = oy - (viewport == null ? 0 : viewport.top * k);

        canvas.save();
        canvas.clipRect(ox, oy, ox + w * k, oy + h * k);
        int alpha = GranulometryColorScale.alpha(visuals.mapOpacity), rimAlpha = GranulometryColorScale.alpha(GranulometryColorScale.rimOpacity(visuals.mapOpacity));
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);rim.setStyle(Paint.Style.STROKE);
        for (PelletDetector.Detection d : result.circles) {
            Granulometry.Item item = new Granulometry.Item(d.radius * 2, d.partial, d.needsReview);
            if (!GranulometryColorScale.colored(distribution, item)) continue;
            int c = scale.rgb(distribution.diameterMm(item.diameterPx));
            fill.setColor(Color.argb(alpha, (c >> 16) & 255, (c >> 8) & 255, c & 255));
            float x = left + (float) d.x * k, y = top + (float) d.y * k;
            float a = (float) (d.semiMajor > 0 ? d.semiMajor : d.radius) * k, b = (float) (d.semiMinor > 0 ? d.semiMinor : d.radius) * k;
            canvas.save();
            canvas.rotate((float) d.angleDeg, x, y);
            canvas.drawOval(new RectF(x - a, y - b, x + a, y + b), fill);
            if (rimAlpha > 0) {
                float sw = Math.max(1.5f, Math.min(a, b) * 0.2f);
                rim.setStrokeWidth(sw);rim.setColor(Color.argb(rimAlpha, (c >> 16) & 255, (c >> 8) & 255, c & 255));
                canvas.drawOval(new RectF(x - a + sw / 2, y - b + sw / 2, x + a - sw / 2, y + b - sw / 2), rim);
            }
            canvas.restore();
        }
        if (visuals.mapLabels && MapComposer.coloredCount(result, distribution) <= MapComposer.MAX_LABELED) drawLabels(canvas, left, top, k);
        canvas.restore();
        drawLegend(canvas, scale);
    }

    /** Diameter on each pellet, skipping those too small on screen or that would overlap a previous label. */
    private void drawLabels(Canvas canvas, float left, float top, float k) {
        float density = getResources().getDisplayMetrics().density;
        float size = 10 * density;
        text.setTextSize(size);
        halo.setTextSize(size);
        halo.setStrokeWidth(3 * density);
        java.util.List<RectF> used = new java.util.ArrayList<>();
        for (PelletDetector.Detection d : result.circles) {
            Granulometry.Item item = new Granulometry.Item(d.radius * 2, d.partial, d.needsReview);
            if (!GranulometryColorScale.colored(distribution, item)) continue;
            if (d.radius * k < 9 * density) continue;
            String label = HistogramView.number(distribution.diameterMm(item.diameterPx));
            float x = left + (float) d.x * k, y = top + (float) d.y * k + size / 3;
            float half = text.measureText(label) / 2 + 2;
            RectF r = new RectF(x - half, y - size, x + half, y + 3);
            boolean free = true;
            for (RectF u : used) if (RectF.intersects(u, r)) { free = false; break; }
            if (!free) continue;
            used.add(r);
            canvas.drawText(label, x, y, halo);
            canvas.drawText(label, x, y, text);
        }
    }

    private void drawLegend(Canvas canvas, GranulometryColorScale scale) {
        float density = getResources().getDisplayMetrics().density;
        float barW = Math.min(getWidth() * 0.78f, 340 * density), barH = 10 * density;
        float x0 = (getWidth() - barW) / 2f, y0 = getHeight() - 30 * density;
        legend.setShader(null);
        legend.setColor(Color.argb(170, 16, 26, 35));
        canvas.drawRoundRect(new RectF(x0 - 8 * density, y0 - 20 * density, x0 + barW + 8 * density, y0 + barH + 18 * density), 8 * density, 8 * density, legend);
        int[] colors = new int[9];
        for (int i = 0; i < colors.length; i++) colors[i] = 0xFF000000 | GranulometryColorScale.colorAt(i / (colors.length - 1.0));
        legend.setShader(new LinearGradient(x0, 0, x0 + barW, 0, colors, null, Shader.TileMode.CLAMP));
        canvas.drawRect(x0, y0, x0 + barW, y0 + barH, legend);
        legend.setShader(null);
        text.setTextSize(10 * density);
        text.setTextAlign(Paint.Align.CENTER);
        String title = String.format(Locale.getDefault(), "Mapa granulométrico · escala %s · %s · opacidade %d%%",
                scale.label(), scale.unit, Math.round(visuals.mapOpacity * 100));
        canvas.drawText(title, getWidth() / 2f, y0 - 6 * density, text);
        double[] ticks = scale.ticks(scale.equalRange() ? 2 : 5);
        for (int i = 0; i < ticks.length; i++) {
            float x = x0 + barW * i / (ticks.length - 1f);
            text.setTextAlign(i == 0 ? Paint.Align.LEFT : i == ticks.length - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(HistogramView.number(ticks[i]), x, y0 + barH + 12 * density, text);
        }
        text.setTextAlign(Paint.Align.CENTER);
    }
}
