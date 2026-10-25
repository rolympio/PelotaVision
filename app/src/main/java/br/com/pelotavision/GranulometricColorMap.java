package br.com.pelotavision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Locale;

/**
 * Mapa granulométrico: coloração contínua das pelotas por diâmetro equivalente.
 * Azul (menores) → ciano → verde → amarelo → vermelho (maiores).
 * A cor é aplicada somente dentro do contorno/elipse, com transparência ajustável.
 * A fotografia original permanece visível por baixo.
 */
final class GranulometricColorMap extends View {
    private PelletDetector.Result result;
    private Granulometry.Distribution distribution;
    private float opacity = 0.25f; // 0.0–0.70
    private boolean showLabels = false;
    private boolean relativeScale = true;
    private double fixedMinMm = 8, fixedMaxMm = 16;
    private AnalysisWindow viewport;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legend = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    GranulometricColorMap(Context context) {
        super(context);
        setWillNotDraw(false);
        fill.setStyle(Paint.Style.FILL);
        legend.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(28);
    }

    void display(PelletDetector.Result result, Granulometry.Distribution distribution) {
        this.result = result;
        this.distribution = distribution;
        invalidate();
    }

    void setOpacity(float value) {
        opacity = Math.max(0f, Math.min(0.70f, value));
        invalidate();
    }

    void setShowLabels(boolean value) {
        showLabels = value;
        invalidate();
    }

    void setRelativeScale(boolean relative) {
        relativeScale = relative;
        invalidate();
    }

    void setFixedRange(double minMm, double maxMm) {
        if (minMm < maxMm) {
            fixedMinMm = minMm;
            fixedMaxMm = maxMm;
            relativeScale = false;
            invalidate();
        }
    }

    void viewport(AnalysisWindow value) {
        viewport = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (result == null || distribution == null || distribution.included == 0) return;

        float scale = viewScale();
        if (scale <= 0) return;

        int w = viewport == null ? result.width : viewport.width;
        int h = viewport == null ? result.height : viewport.height;
        float left = (getWidth() - w * scale) / 2f - (viewport == null ? 0 : viewport.left * scale);
        float top = (getHeight() - h * scale) / 2f - (viewport == null ? 0 : viewport.top * scale);

        canvas.save();
        canvas.clipRect((getWidth() - w * scale) / 2f, (getHeight() - h * scale) / 2f,
                        (getWidth() + w * scale) / 2f, (getHeight() + h * scale) / 2f);

        double min = relativeScale ? distribution.minMm : fixedMinMm;
        double max = relativeScale ? distribution.maxMm : fixedMaxMm;
        if (min >= max) {
            min = distribution.minMm;
            max = distribution.maxMm;
        }
        if (min >= max) {
            // todos iguais: cor intermediária
            int mid = colorFor(0.5);
            fill.setColor(Color.argb((int) (opacity * 255), Color.red(mid), Color.green(mid), Color.blue(mid)));
            for (PelletDetector.Detection d : result.circles) {
                if (!isIncluded(d)) continue;
                drawFilledEllipse(canvas, d, left, top, scale, fill);
            }
        } else {
            for (PelletDetector.Detection d : result.circles) {
                if (!isIncluded(d)) continue;
                double mm = distribution.diameterMm(d.radius * 2); // diâmetro = 2*raio equal-area
                double t = Math.max(0, Math.min(1, (mm - min) / (max - min)));
                int c = colorFor(t);
                fill.setColor(Color.argb((int) (opacity * 255), Color.red(c), Color.green(c), Color.blue(c)));
                drawFilledEllipse(canvas, d, left, top, scale, fill);
                if (showLabels) {
                    float x = left + (float) d.x * scale;
                    float y = top + (float) d.y * scale;
                    float size = Math.max(10, Math.min(22, (float) d.radius * scale * 0.4f));
                    text.setTextSize(size);
                    canvas.drawText(String.format(Locale.getDefault(), "%.1f", mm), x, y, text);
                }
            }
        }

        drawLegend(canvas, min, max, relativeScale);
        canvas.restore();
    }

    private boolean isIncluded(PelletDetector.Detection d) {
        // Aproximação: usa o mesmo critério básico de inclusão da granulometria
        return Double.isFinite(d.radius) && d.radius > 0 && !d.partial;
    }

    private float viewScale() {
        if (result == null) return 0;
        int w = viewport == null ? result.width : viewport.width;
        int h = viewport == null ? result.height : viewport.height;
        return Math.min(getWidth() / (float) w, getHeight() / (float) h);
    }

    private static void drawFilledEllipse(Canvas canvas, PelletDetector.Detection d,
                                          float left, float top, float scale, Paint p) {
        float x = left + (float) d.x * scale;
        float y = top + (float) d.y * scale;
        float a = (float) d.semiMajor * scale;
        float b = (float) d.semiMinor * scale;
        canvas.save();
        canvas.rotate((float) d.angleDeg, x, y);
        canvas.drawOval(new RectF(x - a, y - b, x + a, y + b), p);
        canvas.restore();
    }

    /** Interpolação HSV: 240° (azul) → 0° (vermelho) passando por ciano/verde/amarelo. */
    static int colorFor(double t) {
        float hue = (float) (240 * (1 - Math.max(0, Math.min(1, t))));
        float[] hsv = {hue, 0.85f, 0.95f};
        return Color.HSVToColor(hsv);
    }

    private void drawLegend(Canvas canvas, double min, double max, boolean relative) {
        float barW = Math.min(getWidth() * 0.7f, 420);
        float barH = 18;
        float x0 = (getWidth() - barW) / 2f;
        float y0 = getHeight() - 70;

        legend.setShader(null);
        // Barra de cores
        for (int i = 0; i < 40; i++) {
            float xx = x0 + i * barW / 40f;
            int c = colorFor(i / 39.0);
            legend.setColor(c);
            canvas.drawRect(xx, y0, xx + barW / 40f + 1, y0 + barH, legend);
        }

        text.setTextSize(13);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        String unit = distribution.settings.unit();
        String mode = relative ? "relativa" : "fixa";
        canvas.drawText(String.format(Locale.getDefault(), "Mapa granulométrico (%s)  %.1f – %.1f %s",
                mode, min, max, unit), getWidth() / 2f, y0 - 8, text);

        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.format(Locale.getDefault(), "%.1f", min), x0, y0 + barH + 16, text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.getDefault(), "%.1f", max), x0 + barW, y0 + barH + 16, text);
    }
}
